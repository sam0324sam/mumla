#!/usr/bin/env python3
"""
mumla-hub: Ultra-lightweight zero-dependency OTA & Local Log Storage Server for Mumla PoC walkie-talkies.

Runs on a Taiwan NAS behind HTTPS reverse proxy (https://mumla.hiyoko-sam.com):
  - GET  /health           : Health check
  - GET  /latest           : Cached GitHub dev-latest release metadata JSON
  - GET  /latest.apk       : Disk-cached latest APK stream (resilient over cross-border links)
  - POST /log              : Accepts gzip/plain logs with X-Mumla-Token and stores locally on NAS
  - GET  /logs             : Password-protected HTML log viewer (or ?format=json)
  - GET  /logs/latest      : Password-protected latest log plain text (supports ?user=Hiyoko)
  - GET  /logs/<day>/<fn>  : Password-protected single log view/download (?download=1)

All routes work both with and without the `/mumla` prefix.
"""

import base64
import datetime
import gzip
import hmac
import html
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = int(os.environ.get("PORT", "8088"))
HUB_TOKEN = os.environ.get("HUB_TOKEN", "").strip()
# Optional separate password for viewing logs; defaults to HUB_TOKEN so only one password is needed
LOG_VIEW_PASSWORD = os.environ.get("LOG_VIEW_PASSWORD", "").strip() or HUB_TOKEN
GITHUB_REPO = os.environ.get("GITHUB_REPO", "sam0324sam/mumla").strip()
RELEASE_TAG = os.environ.get("RELEASE_TAG", "dev-latest").strip()
GITHUB_READ_TOKEN = os.environ.get("GITHUB_READ_TOKEN", "").strip()
CACHE_TTL_SEC = int(os.environ.get("CACHE_TTL_SEC", "120"))
MAX_LOG_BYTES = int(os.environ.get("MAX_LOG_BYTES", str(2 * 1024 * 1024)))
MAX_LOCAL_LOGS = int(os.environ.get("MAX_LOCAL_LOGS", "100"))
DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
APK_CACHE_DIR = DATA_DIR / "cache"
LOG_DIR = DATA_DIR / "logs"

APK_CACHE_DIR.mkdir(parents=True, exist_ok=True)
LOG_DIR.mkdir(parents=True, exist_ok=True)

_release_lock = threading.Lock()
_apk_lock = threading.Lock()
_cached_release = None
_cached_release_ts = 0.0
_TW_TZ = datetime.timezone(datetime.timedelta(hours=8))


def _sanitize_segment(raw: str, fallback: str = "unknown") -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9._-]+", "_", (raw or "").strip())
    return cleaned[:48] if cleaned else fallback


def _github_headers(token: str = "") -> dict:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "mumla-hub/2.0",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def _parse_iso8601_ms(ts_str: str) -> int:
    if not ts_str:
        return 0
    try:
        dt = datetime.datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
        return int(dt.timestamp() * 1000)
    except Exception:
        return 0


def _extract_sha(release_json: dict) -> str:
    title = release_json.get("name") or ""
    m = re.search(r"\(([0-9a-fA-F]{7,40})\)", title)
    if m:
        return m.group(1)[:7].lower()
    body = release_json.get("body") or ""
    m2 = re.search(r"`([0-9a-fA-F]{7,40})`", body)
    if m2:
        return m2.group(1)[:7].lower()
    commitish = release_json.get("target_commitish") or ""
    if re.fullmatch(r"[0-9a-fA-F]{7,40}", commitish):
        return commitish[:7].lower()
    return str(release_json.get("id", "unknown"))


def fetch_latest_release(force: bool = False) -> dict:
    global _cached_release, _cached_release_ts
    now = time.time()
    with _release_lock:
        if not force and _cached_release and (now - _cached_release_ts) < CACHE_TTL_SEC:
            return _cached_release

        url = f"https://api.github.com/repos/{GITHUB_REPO}/releases/tags/{RELEASE_TAG}"
        req = urllib.request.Request(url, headers=_github_headers(GITHUB_READ_TOKEN))
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))

        apk_asset = None
        for asset in data.get("assets", []):
            name = asset.get("name", "")
            if name.endswith(".apk"):
                apk_asset = asset
                break
        if not apk_asset:
            raise RuntimeError("No .apk asset found in GitHub release")

        sha = _extract_sha(data)
        published_at = data.get("published_at") or apk_asset.get("updated_at") or ""
        result = {
            "sha": sha,
            "tag": data.get("tag_name", RELEASE_TAG),
            "title": data.get("name", ""),
            "notes": (data.get("body") or "").strip(),
            "published_at": published_at,
            "published_ms": _parse_iso8601_ms(published_at),
            "apk_name": apk_asset.get("name", "mumla-latest-debug.apk"),
            "size": int(apk_asset.get("size", 0)),
            "download_url": apk_asset.get("browser_download_url", ""),
        }
        _cached_release = result
        _cached_release_ts = now
        return result


def ensure_cached_apk(release: dict) -> Path:
    sha = _sanitize_segment(release.get("sha", "latest"), "latest")
    target = APK_CACHE_DIR / f"mumla-{sha}.apk"
    if target.exists() and target.stat().st_size > 100_000:
        return target

    with _apk_lock:
        if target.exists() and target.stat().st_size > 100_000:
            return target

        download_url = release.get("download_url")
        if not download_url:
            raise RuntimeError("Missing APK download_url")

        tmp = APK_CACHE_DIR / f"mumla-{sha}.apk.tmp"
        req = urllib.request.Request(download_url, headers={"User-Agent": "mumla-hub/2.0"})
        with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as out:
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                out.write(chunk)

        os.replace(tmp, target)
        # Keep only the latest cached APK to save NAS disk space
        for old in APK_CACHE_DIR.glob("mumla-*.apk"):
            if old != target:
                try:
                    old.unlink()
                except OSError:
                    pass
        return target


def _list_local_logs(user_filter: str = "") -> list:
    files = sorted(LOG_DIR.rglob("*.log"), key=lambda p: p.stat().st_mtime, reverse=True)
    if user_filter:
        needle = user_filter.lower()
        files = [p for p in files if needle in p.name.lower()]
    return files


def _prune_local_logs() -> None:
    try:
        files = _list_local_logs()
        for old in files[MAX_LOCAL_LOGS:]:
            try:
                old.unlink()
            except OSError:
                pass
        # Clean up empty date folders
        for sub in LOG_DIR.iterdir():
            if sub.is_dir():
                try:
                    if not any(sub.iterdir()):
                        sub.rmdir()
                except OSError:
                    pass
    except Exception:
        pass


def _verify_secret(provided: str, expected: str) -> bool:
    if not expected:
        return True
    return hmac.compare_digest(provided or "", expected)


def _format_size(num_bytes: int) -> str:
    if num_bytes < 1024:
        return f"{num_bytes} B"
    if num_bytes < 1024 * 1024:
        return f"{num_bytes / 1024:.1f} KB"
    return f"{num_bytes / (1024 * 1024):.2f} MB"


class HubHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _split_path(self) -> tuple:
        parsed = urllib.parse.urlsplit(self.path)
        raw_path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)
        prefix = ""
        if raw_path == "/mumla" or raw_path.startswith("/mumla/"):
            prefix = "/mumla"
            norm = raw_path[len("/mumla"):] or "/"
        else:
            norm = raw_path or "/"
        norm = norm.rstrip("/") or "/"
        return prefix, norm, query

    def _check_log_read_auth(self) -> bool:
        if not LOG_VIEW_PASSWORD:
            return True
        # 1. X-Mumla-Token header (for curl / scripts / AI)
        header_token = self.headers.get("X-Mumla-Token", "")
        if header_token and _verify_secret(header_token, LOG_VIEW_PASSWORD):
            return True
        # 2. HTTP Basic Auth (for browsers - username can be anything, password = LOG_VIEW_PASSWORD)
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Basic "):
            try:
                decoded = base64.b64decode(auth[6:].strip()).decode("utf-8", errors="replace")
                if ":" in decoded:
                    user_part, pass_part = decoded.split(":", 1)
                    if _verify_secret(pass_part, LOG_VIEW_PASSWORD) or _verify_secret(user_part, LOG_VIEW_PASSWORD):
                        return True
            except Exception:
                pass
        return False

    def _send_basic_auth_challenge(self) -> None:
        body = b"Unauthorized: please enter Mumla Hub password.\n"
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="Mumla Logs", charset="UTF-8"')
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, code: int, obj: dict) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_log_file(self, file_path: Path, download: bool = False) -> None:
        data = file_path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        if download:
            self.send_header("Content-Disposition", f'attachment; filename="{file_path.name}"')
        self.end_headers()
        self.wfile.write(data)

    def _send_logs_index(self, prefix: str, query: dict) -> None:
        user_filter = (query.get("user", [""])[0] or "").strip()
        files = _list_local_logs(user_filter)
        fmt = (query.get("format", [""])[0] or "").lower()

        if fmt == "json":
            items = []
            for p in files:
                st = p.stat()
                rel = p.relative_to(LOG_DIR).as_posix()
                mtime_tw = datetime.datetime.fromtimestamp(st.st_mtime, tz=_TW_TZ).strftime("%Y-%m-%d %H:%M:%S +0800")
                items.append({
                    "path": rel,
                    "name": p.name,
                    "size": st.st_size,
                    "mtime": mtime_tw,
                    "url": f"{prefix}/logs/{rel}",
                })
            self._send_json(200, {"count": len(items), "logs": items})
            return

        rows = []
        for p in files:
            st = p.stat()
            rel = p.relative_to(LOG_DIR).as_posix()
            rel_quoted = urllib.parse.quote(rel, safe="/")
            mtime_tw = datetime.datetime.fromtimestamp(st.st_mtime, tz=_TW_TZ).strftime("%Y-%m-%d %H:%M:%S")
            view_url = f"{prefix}/logs/{rel_quoted}"
            dl_url = f"{view_url}?download=1"
            rows.append(
                f"<tr>"
                f"<td><a href=\"{view_url}\">{html.escape(rel)}</a></td>"
                f"<td class=\"meta\">{mtime_tw}</td>"
                f"<td class=\"meta\">{_format_size(st.st_size)}</td>"
                f"<td><a class=\"btn\" href=\"{dl_url}\">下載</a></td>"
                f"</tr>"
            )

        table_body = "\n".join(rows) if rows else '<tr><td colspan="4" class="meta">目前尚無任何 Log 紀錄</td></tr>'
        latest_url = f"{prefix}/logs/latest"
        json_url = f"{prefix}/logs?format=json"
        page = f"""<!doctype html>
<html lang="zh-Hant">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mumla Logs</title>
<style>
  body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background:#111; color:#eee; margin:0; padding:16px; }}
  h1 {{ font-size:20px; margin:0 0 12px; }}
  .bar {{ display:flex; gap:10px; flex-wrap:wrap; margin-bottom:14px; }}
  a {{ color:#58a6ff; text-decoration:none; }}
  a:hover {{ text-decoration:underline; }}
  .btn {{ display:inline-block; padding:4px 10px; border:1px solid #30363d; border-radius:6px; background:#21262d; color:#f0f6fc; font-size:13px; }}
  table {{ width:100%; border-collapse:collapse; font-size:14px; }}
  th, td {{ text-align:left; padding:8px 6px; border-bottom:1px solid #262c36; word-break:break-all; }}
  .meta {{ color:#8b949e; white-space:nowrap; font-size:12px; }}
</style>
</head>
<body>
  <h1>Mumla 診斷日誌 ({len(files)} 筆)</h1>
  <div class="bar">
    <a class="btn" href="{latest_url}">查看最新一筆 Log</a>
    <a class="btn" href="{json_url}">JSON 清單</a>
  </div>
  <table>
    <thead><tr><th>檔案</th><th>時間 (GMT+8)</th><th>大小</th><th>操作</th></tr></thead>
    <tbody>{table_body}</tbody>
  </table>
</body>
</html>
""".encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(page)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(page)

    def do_GET(self) -> None:
        prefix, route, query = self._split_path()

        if route in ("/", "/health"):
            self._send_json(200, {
                "status": "ok",
                "repo": GITHUB_REPO,
                "tag": RELEASE_TAG,
                "local_logs": len(_list_local_logs()),
            })
            return

        if route == "/latest":
            try:
                rel = fetch_latest_release()
                public_meta = {
                    "sha": rel["sha"],
                    "tag": rel["tag"],
                    "title": rel["title"],
                    "notes": rel["notes"],
                    "published_at": rel["published_at"],
                    "published_ms": rel["published_ms"],
                    "apk_name": rel["apk_name"],
                    "size": rel["size"],
                }
                self._send_json(200, public_meta)
            except Exception as e:
                print(f"[mumla-hub] /latest error: {e}", flush=True)
                self._send_json(502, {"error": str(e)})
            return

        if route == "/latest.apk":
            try:
                rel = fetch_latest_release()
                apk_path = ensure_cached_apk(rel)
                file_size = apk_path.stat().st_size
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Disposition", f'attachment; filename="{rel["apk_name"]}"')
                self.send_header("Content-Length", str(file_size))
                self.send_header("X-Mumla-Sha", rel["sha"])
                self.end_headers()
                with open(apk_path, "rb") as f:
                    while True:
                        chunk = f.read(65536)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
            except Exception as e:
                print(f"[mumla-hub] /latest.apk error: {e}", flush=True)
                self._send_json(502, {"error": str(e)})
            return

        if route == "/logs" or route.startswith("/logs/"):
            if not self._check_log_read_auth():
                self._send_basic_auth_challenge()
                return

            if route == "/logs":
                self._send_logs_index(prefix, query)
                return

            if route == "/logs/latest":
                user_filter = (query.get("user", [""])[0] or "").strip()
                files = _list_local_logs(user_filter)
                if not files:
                    self._send_json(404, {"error": "no_logs_found"})
                    return
                download = (query.get("download", ["0"])[0] == "1")
                self._send_log_file(files[0], download=download)
                return

            rel_req = urllib.parse.unquote(route[len("/logs/"):])
            try:
                base_resolved = LOG_DIR.resolve()
                target = (LOG_DIR / rel_req).resolve()
                if not str(target).startswith(str(base_resolved) + os.sep) or not target.is_file() or target.suffix != ".log":
                    self._send_json(404, {"error": "not_found"})
                    return
                download = (query.get("download", ["0"])[0] == "1")
                self._send_log_file(target, download=download)
            except Exception:
                self._send_json(404, {"error": "not_found"})
            return

        self._send_json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        _, route, _ = self._split_path()
        if route != "/log":
            self._send_json(404, {"error": "not_found"})
            return

        if not _verify_secret(self.headers.get("X-Mumla-Token", ""), HUB_TOKEN):
            self._send_json(401, {"error": "unauthorized"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send_json(400, {"error": "invalid_content_length"})
            return

        if length <= 0 or length > MAX_LOG_BYTES:
            self._send_json(413, {"error": "payload_too_large_or_empty"})
            return

        raw = self.rfile.read(length)
        if (self.headers.get("Content-Encoding") or "").lower() == "gzip":
            try:
                raw = gzip.decompress(raw)
            except Exception:
                self._send_json(400, {"error": "invalid_gzip"})
                return

        if len(raw) > MAX_LOG_BYTES * 4:
            self._send_json(413, {"error": "decompressed_too_large"})
            return

        device = _sanitize_segment(self.headers.get("X-Device-Name", ""), "device")
        reason = _sanitize_segment(self.headers.get("X-Log-Reason", ""), "manual")

        now_tw = datetime.datetime.now(tz=_TW_TZ)
        day_dir = now_tw.strftime("%Y-%m-%d")
        ts = now_tw.strftime("%H%M%S")
        filename = f"{ts}_{device}_{reason}.log"

        local_dir = LOG_DIR / day_dir
        local_dir.mkdir(parents=True, exist_ok=True)
        local_file = local_dir / filename
        local_file.write_bytes(raw)
        _prune_local_logs()

        rel_path = f"{day_dir}/{filename}"
        print(f"[mumla-hub] Saved log {rel_path} ({len(raw)} bytes)", flush=True)
        self._send_json(200, {
            "status": "ok",
            "path": rel_path,
            "bytes": len(raw),
        })

    def log_message(self, fmt: str, *args) -> None:
        print(f"[mumla-hub] {self.address_string()} - {fmt % args}", flush=True)


def main() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", PORT), HubHandler)
    print(f"[mumla-hub] Listening on 0.0.0.0:{PORT} | repo={GITHUB_REPO} ({RELEASE_TAG}) | local_logs_dir={LOG_DIR}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
