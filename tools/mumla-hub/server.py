#!/usr/bin/env python3
"""
mumla-hub: Ultra-lightweight zero-dependency OTA & Remote Log Relay for Mumla PoC walkie-talkies.

Runs on a Taiwan NAS behind Gcore CDN (or any HTTPS reverse proxy):
  - GET  /mumla/health     : Health check
  - GET  /mumla/latest     : Cached GitHub dev-latest release metadata JSON
  - GET  /mumla/latest.apk : Disk-cached latest APK stream (resilient over cross-border links)
  - POST /mumla/log        : Accepts gzip/plain logs with X-Mumla-Token, stores locally,
                             and asynchronously pushes to a private GitHub log repo.
"""

import base64
import datetime
import gzip
import hmac
import json
import os
import re
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = int(os.environ.get("PORT", "8088"))
HUB_TOKEN = os.environ.get("HUB_TOKEN", "").strip()
GITHUB_REPO = os.environ.get("GITHUB_REPO", "sam0324sam/mumla").strip()
RELEASE_TAG = os.environ.get("RELEASE_TAG", "dev-latest").strip()
GITHUB_READ_TOKEN = os.environ.get("GITHUB_READ_TOKEN", "").strip()
GITHUB_LOG_REPO = os.environ.get("GITHUB_LOG_REPO", "sam0324sam/mumla-logs").strip()
GITHUB_LOG_TOKEN = os.environ.get("GITHUB_LOG_TOKEN", "").strip()
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


def _sanitize_segment(raw: str, fallback: str = "unknown") -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9._-]+", "_", (raw or "").strip())
    return cleaned[:48] if cleaned else fallback


def _github_headers(token: str = "") -> dict:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "mumla-hub/1.0",
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
        req = urllib.request.Request(download_url, headers={"User-Agent": "mumla-hub/1.0"})
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


def _prune_local_logs() -> None:
    try:
        files = sorted(LOG_DIR.rglob("*.log"), key=lambda p: p.stat().st_mtime, reverse=True)
        for old in files[MAX_LOCAL_LOGS:]:
            try:
                old.unlink()
            except OSError:
                pass
    except Exception:
        pass


def _push_log_to_github_async(rel_path: str, content_bytes: bytes, commit_msg: str) -> None:
    if not GITHUB_LOG_REPO or not GITHUB_LOG_TOKEN:
        return

    def _worker():
        try:
            url = f"https://api.github.com/repos/{GITHUB_LOG_REPO}/contents/{rel_path}"
            payload = json.dumps({
                "message": commit_msg,
                "content": base64.b64encode(content_bytes).decode("ascii"),
            }).encode("utf-8")
            req = urllib.request.Request(
                url,
                data=payload,
                method="PUT",
                headers={
                    **_github_headers(GITHUB_LOG_TOKEN),
                    "Content-Type": "application/json",
                },
            )
            with urllib.request.urlopen(req, timeout=30) as resp:
                print(f"[mumla-hub] Pushed log to GitHub {GITHUB_LOG_REPO}/{rel_path} (HTTP {resp.status})", flush=True)
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")[:300]
            print(f"[mumla-hub] GitHub log push failed ({e.code}): {body}", flush=True)
        except Exception as e:
            print(f"[mumla-hub] GitHub log push error: {e}", flush=True)

    threading.Thread(target=_worker, daemon=True).start()


def _verify_token(provided: str) -> bool:
    if not HUB_TOKEN:
        return True
    return hmac.compare_digest(provided or "", HUB_TOKEN)


class HubHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _norm_path(self) -> str:
        path = self.path.split("?", 1)[0].rstrip("/")
        if path.startswith("/mumla/"):
            return path[len("/mumla"):]
        if path == "/mumla":
            return "/"
        return path or "/"

    def _send_json(self, code: int, obj: dict) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        route = self._norm_path()
        if route in ("/", "/health"):
            self._send_json(200, {
                "status": "ok",
                "repo": GITHUB_REPO,
                "tag": RELEASE_TAG,
                "github_log_relay": bool(GITHUB_LOG_REPO and GITHUB_LOG_TOKEN),
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

        self._send_json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        route = self._norm_path()
        if route != "/log":
            self._send_json(404, {"error": "not_found"})
            return

        if not _verify_token(self.headers.get("X-Mumla-Token", "")):
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
        version = _sanitize_segment(self.headers.get("X-App-Version", ""), "unknown")

        now_utc = datetime.datetime.now(datetime.timezone.utc)
        now_tw = now_utc + datetime.timedelta(hours=8)
        day_dir = now_tw.strftime("%Y-%m-%d")
        ts = now_tw.strftime("%H%M%S")
        filename = f"{ts}_{device}_{reason}.log"

        local_dir = LOG_DIR / day_dir
        local_dir.mkdir(parents=True, exist_ok=True)
        local_file = local_dir / filename
        local_file.write_bytes(raw)
        _prune_local_logs()

        rel_path = f"logs/{day_dir}/{filename}"
        commit_msg = f"log({device}): {reason} [{version}]"
        _push_log_to_github_async(rel_path, raw, commit_msg)

        print(f"[mumla-hub] Saved log {rel_path} ({len(raw)} bytes)", flush=True)
        self._send_json(200, {
            "status": "ok",
            "path": rel_path,
            "bytes": len(raw),
            "github_queued": bool(GITHUB_LOG_REPO and GITHUB_LOG_TOKEN),
        })

    def log_message(self, fmt: str, *args) -> None:
        print(f"[mumla-hub] {self.address_string()} - {fmt % args}", flush=True)


def main() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", PORT), HubHandler)
    print(f"[mumla-hub] Listening on 0.0.0.0:{PORT} | repo={GITHUB_REPO} ({RELEASE_TAG}) | log_repo={GITHUB_LOG_REPO}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
