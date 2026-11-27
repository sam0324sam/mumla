#!/usr/bin/env python3
"""
mumla-hub: Ultra-lightweight zero-dependency OTA, Local Log Storage & Live Ski GPS Map Server.

Runs on a Taiwan NAS behind HTTPS reverse proxy (https://mumla.hiyoko-sam.com):
  - GET  /health           : Health check
  - GET  /latest           : Cached GitHub dev-latest release metadata JSON
  - GET  /latest.apk       : Disk-cached latest APK stream (resilient over cross-border links)
  - POST /log              : Accepts gzip/plain logs with X-Mumla-Token and stores locally on NAS
  - GET  /logs             : Password-protected HTML log viewer (or ?format=json)
  - GET  /logs/latest      : Password-protected latest log plain text (supports ?user=Hiyoko)
  - GET  /logs/<day>/<fn>  : Password-protected single log view/download (?download=1)
  - POST /gps              : Accepts single or batched GPS JSON points with X-Mumla-Token
  - GET  /api/gps          : Password-protected JSON of live positions & full-day tracks (?date=YYYY-MM-DD)
  - GET  /map              : Password-protected interactive Leaflet.js Ski Map & Full-Day Track Viewer

All routes work both with and without the `/mumla` prefix.
"""

import base64
import datetime
import gzip
import hmac
import html
import json
import math
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
# Optional separate password for viewing logs & map; defaults to HUB_TOKEN so only one password is needed
LOG_VIEW_PASSWORD = os.environ.get("LOG_VIEW_PASSWORD", "").strip() or HUB_TOKEN
GITHUB_REPO = os.environ.get("GITHUB_REPO", "sam0324sam/mumla").strip()
RELEASE_TAG = os.environ.get("RELEASE_TAG", "dev-latest").strip()
GITHUB_READ_TOKEN = os.environ.get("GITHUB_READ_TOKEN", "").strip()
CACHE_TTL_SEC = int(os.environ.get("CACHE_TTL_SEC", "120"))
MAX_LOG_BYTES = int(os.environ.get("MAX_LOG_BYTES", str(2 * 1024 * 1024)))
MAX_LOCAL_LOGS = int(os.environ.get("MAX_LOCAL_LOGS", "100"))
MAX_GPS_DAYS = int(os.environ.get("MAX_GPS_DAYS", "30"))
DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
APK_CACHE_DIR = DATA_DIR / "cache"
LOG_DIR = DATA_DIR / "logs"
GPS_DIR = DATA_DIR / "gps"

APK_CACHE_DIR.mkdir(parents=True, exist_ok=True)
LOG_DIR.mkdir(parents=True, exist_ok=True)
GPS_DIR.mkdir(parents=True, exist_ok=True)

_release_lock = threading.Lock()
_apk_lock = threading.Lock()
_gps_lock = threading.Lock()
_cached_release = None
_cached_release_ts = 0.0
_TW_TZ = datetime.timezone(datetime.timedelta(hours=8))


def _sanitize_segment(raw: str, fallback: str = "unknown") -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9._-]+", "_", (raw or "").strip())
    return cleaned[:48] if cleaned else fallback


def _github_headers(token: str = "") -> dict:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "mumla-hub/2.1",
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
        req = urllib.request.Request(download_url, headers={"User-Agent": "mumla-hub/2.1"})
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
        for sub in LOG_DIR.iterdir():
            if sub.is_dir():
                try:
                    if not any(sub.iterdir()):
                        sub.rmdir()
                except OSError:
                    pass
    except Exception:
        pass


def _list_gps_dates() -> list:
    dates = []
    for p in sorted(GPS_DIR.glob("*.jsonl"), reverse=True):
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", p.stem):
            dates.append(p.stem)
    return dates


def _prune_gps_files() -> None:
    try:
        files = sorted(GPS_DIR.glob("*.jsonl"), reverse=True)
        for old in files[MAX_GPS_DAYS:]:
            try:
                old.unlink()
            except OSError:
                pass
    except Exception:
        pass


def _haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6371.0
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def _save_gps_points(points: list) -> int:
    saved = 0
    by_day = {}
    now_ms = int(time.time() * 1000)

    for item in points:
        if not isinstance(item, dict):
            continue
        try:
            lat = float(item["lat"])
            lng = float(item["lng"])
            if not (-90.0 <= lat <= 90.0 and -180.0 <= lng <= 180.0):
                continue
            if lat == 0.0 and lng == 0.0:
                continue
        except (KeyError, TypeError, ValueError):
            continue

        ts_ms = int(item.get("ts") or now_ms)
        if ts_ms < 1_500_000_000_000:
            ts_ms = now_ms

        user = str(item.get("user") or "Unknown").strip()[:40] or "Unknown"
        rec = {
            "user": user,
            "lat": round(lat, 6),
            "lng": round(lng, 6),
            "ts": ts_ms,
            "bat": int(item.get("bat", -1)),
            "charging": bool(item.get("charging", False)),
            "src": str(item.get("src") or "GPS")[:24],
        }
        if item.get("alt") is not None:
            try:
                rec["alt"] = round(float(item["alt"]), 1)
            except (TypeError, ValueError):
                pass
        if item.get("acc") is not None:
            try:
                rec["acc"] = round(float(item["acc"]), 1)
            except (TypeError, ValueError):
                pass
        if item.get("note"):
            rec["note"] = str(item["note"])[:48]

        day_str = datetime.datetime.fromtimestamp(ts_ms / 1000.0, tz=_TW_TZ).strftime("%Y-%m-%d")
        by_day.setdefault(day_str, []).append(rec)
        saved += 1

    if not by_day:
        return 0

    with _gps_lock:
        for day_str, recs in by_day.items():
            day_file = GPS_DIR / f"{day_str}.jsonl"
            with open(day_file, "a", encoding="utf-8") as f:
                for rec in recs:
                    f.write(json.dumps(rec, ensure_ascii=False, separators=(",", ":")) + "\n")
        _prune_gps_files()

    return saved


def _load_gps_day(date_str: str) -> dict:
    today_tw = datetime.datetime.now(tz=_TW_TZ).strftime("%Y-%m-%d")
    available_dates = _list_gps_dates()
    if not date_str or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date_str):
        date_str = available_dates[0] if available_dates else today_tw

    day_file = GPS_DIR / f"{date_str}.jsonl"
    raw_by_user = {}
    if day_file.is_file():
        with _gps_lock:
            lines = day_file.read_text(encoding="utf-8", errors="replace").splitlines()
        for line in lines:
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
                user = rec.get("user") or "Unknown"
                raw_by_user.setdefault(user, []).append(rec)
            except Exception:
                continue

    users_payload = {}
    for user, recs in sorted(raw_by_user.items()):
        recs.sort(key=lambda r: r.get("ts", 0))
        # Deduplicate identical timestamp points and compute total distance
        track = []
        dist_km = 0.0
        for r in recs:
            if track and track[-1]["ts"] == r["ts"]:
                track[-1] = r
                continue
            if track:
                seg = _haversine_km(track[-1]["lat"], track[-1]["lng"], r["lat"], r["lng"])
                if seg >= 0.015:  # ignore <15m GPS jitter
                    dist_km += seg
            track.append(r)

        users_payload[user] = {
            "latest": track[-1],
            "count": len(track),
            "distance_km": round(dist_km, 2),
            "track": track,
        }

    return {
        "date": date_str,
        "today": today_tw,
        "available_dates": available_dates,
        "server_time_ms": int(time.time() * 1000),
        "users": users_payload,
    }


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


def _render_map_html(prefix: str) -> bytes:
    api_url = f"{prefix}/api/gps"
    logs_url = f"{prefix}/logs"
    page = f"""<!doctype html>
<html lang="zh-Hant">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>Mumla 滑雪即時定位地圖</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" crossorigin="">
<style>
  :root {{
    --bg: rgba(15, 23, 42, 0.88);
    --border: rgba(148, 163, 184, 0.25);
    --text: #f8fafc;
    --muted: #94a3b8;
    --accent: #38bdf8;
  }}
  * {{ box-sizing: border-box; }}
  html, body, #map {{ margin:0; padding:0; width:100%; height:100%; background:#0f172a; font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }}
  .topbar {{
    position:fixed; top:10px; left:10px; right:10px; z-index:1000;
    display:flex; gap:8px; align-items:center; flex-wrap:wrap;
    background:var(--bg); backdrop-filter:blur(12px); -webkit-backdrop-filter:blur(12px);
    border:1px solid var(--border); border-radius:14px; padding:8px 12px; color:var(--text);
    box-shadow:0 8px 24px rgba(0,0,0,0.35);
  }}
  .title {{ font-weight:700; font-size:15px; letter-spacing:0.3px; margin-right:auto; display:flex; align-items:center; gap:6px; }}
  .dot-live {{ width:8px; height:8px; border-radius:50%; background:#22c55e; box-shadow:0 0 8px #22c55e; }}
  select, button, .link-btn {{
    background:rgba(30,41,59,0.9); color:var(--text); border:1px solid var(--border);
    border-radius:9px; padding:6px 10px; font-size:13px; cursor:pointer; text-decoration:none;
  }}
  button:active {{ transform:scale(0.97); }}
  .panel {{
    position:fixed; bottom:12px; left:10px; right:10px; z-index:1000;
    max-height:38vh; overflow-y:auto;
    background:var(--bg); backdrop-filter:blur(12px); -webkit-backdrop-filter:blur(12px);
    border:1px solid var(--border); border-radius:16px; padding:10px 12px; color:var(--text);
    box-shadow:0 8px 30px rgba(0,0,0,0.45);
  }}
  .panel-head {{ display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; font-size:12px; color:var(--muted); }}
  .skiers {{ display:grid; grid-template-columns:repeat(auto-fill, minmax(220px, 1fr)); gap:8px; }}
  .card {{
    display:flex; align-items:center; gap:10px; padding:8px 10px;
    background:rgba(30,41,59,0.75); border:1px solid var(--border); border-radius:12px; cursor:pointer;
  }}
  .card:hover {{ border-color:var(--accent); }}
  .badge {{
    width:32px; height:32px; border-radius:50%; display:flex; align-items:center; justify-content:center;
    font-weight:700; font-size:13px; color:#fff; flex-shrink:0; border:2px solid rgba(255,255,255,0.85);
  }}
  .info {{ flex:1; min-width:0; }}
  .name-row {{ display:flex; align-items:center; gap:6px; font-weight:600; font-size:14px; }}
  .sub-row {{ font-size:11px; color:var(--muted); margin-top:2px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }}
  .bat {{ font-size:11px; padding:1px 6px; border-radius:99px; background:rgba(34,197,94,0.2); color:#86efac; }}
  .bat.low {{ background:rgba(245,158,11,0.25); color:#fcd34d; }}
  .bat.crit {{ background:rgba(239,68,68,0.25); color:#fca5a5; }}
  .track-toggle {{ accent-color:var(--accent); width:16px; height:16px; cursor:pointer; }}
  .skier-pin {{
    display:flex; flex-direction:column; align-items:center; transform:translate(-50%,-100%);
  }}
  .pin-pill {{
    padding:3px 8px; border-radius:99px; color:#fff; font-weight:700; font-size:12px;
    white-space:nowrap; border:2px solid #fff; box-shadow:0 3px 10px rgba(0,0,0,0.45);
  }}
  .pin-arrow {{
    width:0; height:0; border-left:6px solid transparent; border-right:6px solid transparent;
    border-top:7px solid #fff; margin-top:-1px;
  }}
  .nav-btn {{
    display:inline-block; margin-top:8px; padding:6px 10px; border-radius:8px;
    background:#2563eb; color:#fff !important; text-decoration:none; font-size:12px; font-weight:600;
  }}
  .leaflet-top.leaflet-right {{ top:68px; }}
  .empty {{ text-align:center; color:var(--muted); font-size:13px; padding:10px 0; }}
</style>
</head>
<body>
<div id="map"></div>

<div class="topbar">
  <div class="title"><span class="dot-live"></span> 滑雪隊友定位</div>
  <select id="dateSelect" title="選擇日期"></select>
  <button id="fitBtn">置中全員</button>
  <button id="refreshBtn">更新</button>
  <a class="link-btn" href="{logs_url}">Log</a>
</div>

<div class="panel">
  <div class="panel-head">
    <span id="statusText">載入中...</span>
    <span>勾選 = 顯示整日軌跡</span>
  </div>
  <div class="skiers" id="skierList"></div>
</div>

<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" crossorigin=""></script>
<script>
const API_URL = {json.dumps(api_url)};
const COLORS = ['#ef4444','#3b82f6','#22c55e','#f59e0b','#a855f7','#ec4899','#14b8a6','#f97316'];

const googleHybrid = L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={{x}}&y={{y}}&z={{z}}', {{
  maxZoom: 20, attribution: 'Google Satellite'
}});
const googleStreets = L.tileLayer('https://mt1.google.com/vt/lyrs=m&x={{x}}&y={{y}}&z={{z}}', {{
  maxZoom: 20, attribution: 'Google Maps'
}});
const openTopo = L.tileLayer('https://{{s}}.tile.opentopomap.org/{{z}}/{{x}}/{{y}}.png', {{
  maxZoom: 17, attribution: 'OpenTopoMap'
}});
const snowPistes = L.tileLayer('https://tiles.opensnowmap.org/pistes/{{z}}/{{x}}/{{y}}.png', {{
  maxZoom: 18, opacity: 0.85, attribution: 'OpenSnowMap'
}});

const map = L.map('map', {{
  center: [36.70, 137.84],
  zoom: 13,
  zoomControl: false,
  layers: [googleHybrid, snowPistes]
}});
L.control.layers(
  {{ '🛰️ Google 衛星混合': googleHybrid, '🗺️ Google 街道': googleStreets, '⛰️ 等高線地形': openTopo }},
  {{ '🎿 滑雪道 / 纜車 (OpenSnowMap)': snowPistes }},
  {{ position: 'topright' }}
).addTo(map);

const userLayers = {{}};
const trackVisible = {{}};
let hasAutoFit = false;
let currentDate = '';

function colorFor(user) {{
  let h = 0;
  for (let i = 0; i < user.length; i++) h = (h * 31 + user.charCodeAt(i)) >>> 0;
  return COLORS[h % COLORS.length];
}}

function relTime(tsMs, nowMs) {{
  const diffSec = Math.max(0, Math.floor((nowMs - tsMs) / 1000));
  if (diffSec < 60) return diffSec + ' 秒前';
  const min = Math.floor(diffSec / 60);
  if (min < 60) return min + ' 分前';
  const hr = Math.floor(min / 60);
  if (hr < 24) return hr + ' 小時前';
  return Math.floor(hr / 24) + ' 天前';
}}

function fmtTime(tsMs) {{
  return new Date(tsMs).toLocaleTimeString('zh-TW', {{ hour12: false, hour:'2-digit', minute:'2-digit', second:'2-digit' }});
}}

function fitAll() {{
  const pts = [];
  Object.values(userLayers).forEach(u => {{
    if (u.latest) pts.push([u.latest.lat, u.latest.lng]);
  }});
  if (pts.length === 1) {{
    map.setView(pts[0], 15);
  }} else if (pts.length > 1) {{
    map.fitBounds(pts, {{ padding: [70, 140], maxZoom: 16 }});
  }}
}}

function clearLayers() {{
  Object.values(userLayers).forEach(u => {{
    if (u.marker) map.removeLayer(u.marker);
    if (u.polyline) map.removeLayer(u.polyline);
  }});
  Object.keys(userLayers).forEach(k => delete userLayers[k]);
}}

async function loadGps(selectedDate) {{
  try {{
    const url = selectedDate ? `${{API_URL}}?date=${{encodeURIComponent(selectedDate)}}` : API_URL;
    const res = await fetch(url, {{ cache: 'no-store' }});
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();

    const dateSel = document.getElementById('dateSelect');
    const dates = data.available_dates.length ? data.available_dates : [data.date];
    if (!dates.includes(data.date)) dates.unshift(data.date);
    dateSel.innerHTML = dates.map(d => `<option value="${{d}}" ${{d === data.date ? 'selected' : ''}}>${{d}}</option>`).join('');
    currentDate = data.date;

    clearLayers();
    const listEl = document.getElementById('skierList');
    const names = Object.keys(data.users || {{}});

    document.getElementById('statusText').textContent =
      `${{data.date}} · ${{names.length}} 人 · 更新於 ${{fmtTime(data.server_time_ms)}}`;

    if (!names.length) {{
      listEl.innerHTML = '<div class="empty">今日尚無 GPS 定位紀錄</div>';
      return;
    }}

    listEl.innerHTML = '';
    names.forEach(user => {{
      const u = data.users[user];
      const latest = u.latest;
      const color = colorFor(user);
      if (trackVisible[user] === undefined) trackVisible[user] = true;

      const latlngs = u.track.map(p => [p.lat, p.lng]);
      const polyline = L.polyline(latlngs, {{
        color, weight: 4, opacity: 0.85, lineJoin: 'round'
      }});
      if (trackVisible[user] && latlngs.length > 1) polyline.addTo(map);

      const batLabel = latest.bat >= 0 ? `${{latest.bat}}%${{latest.charging ? '⚡' : ''}}` : '';
      const icon = L.divIcon({{
        className: '',
        html: `<div class="skier-pin"><div class="pin-pill" style="background:${{color}}">${{user}} ${{batLabel}}</div><div class="pin-arrow"></div></div>`,
        iconSize: [0, 0]
      }});

      const altStr = latest.alt !== undefined ? ` · 海拔 ${{latest.alt}}m` : '';
      const navUrl = `https://www.google.com/maps/search/?api=1&query=${{latest.lat}},${{latest.lng}}`;
      const popupHtml = `
        <div style="font-size:13px;line-height:1.5">
          <b>${{user}}</b> (${{latest.src || 'GPS'}})<br>
          🕒 ${{fmtTime(latest.ts)}} (${{relTime(latest.ts, data.server_time_ms)}})<br>
          🔋 ${{latest.bat >= 0 ? latest.bat + '%' + (latest.charging ? ' 充電中' : '') : '未知'}}${{altStr}}<br>
          🎿 今日軌跡：${{u.distance_km}} km (${{u.count}} 點)<br>
          <a class="nav-btn" href="${{navUrl}}" target="_blank" rel="noopener">🧭 在 Google 地圖導航</a>
        </div>`;

      const marker = L.marker([latest.lat, latest.lng], {{ icon }}).bindPopup(popupHtml).addTo(map);
      userLayers[user] = {{ marker, polyline, latest }};

      const batCls = latest.bat >= 0 && latest.bat <= 10 ? 'crit' : (latest.bat >= 0 && latest.bat <= 20 ? 'low' : '');
      const card = document.createElement('div');
      card.className = 'card';
      card.innerHTML = `
        <div class="badge" style="background:${{color}}">${{user.slice(0,2).toUpperCase()}}</div>
        <div class="info">
          <div class="name-row">
            <span>${{user}}</span>
            ${{batLabel ? `<span class="bat ${{batCls}}">${{batLabel}}</span>` : ''}}
          </div>
          <div class="sub-row">${{relTime(latest.ts, data.server_time_ms)}} · ${{u.distance_km}}km (${{u.count}}點)</div>
        </div>
        <input class="track-toggle" type="checkbox" ${{trackVisible[user] ? 'checked' : ''}} title="顯示軌跡">
      `;
      card.addEventListener('click', (e) => {{
        if (e.target.tagName === 'INPUT') return;
        map.flyTo([latest.lat, latest.lng], Math.max(map.getZoom(), 15), {{ duration: 0.6 }});
        marker.openPopup();
      }});
      card.querySelector('input').addEventListener('change', (e) => {{
        trackVisible[user] = e.target.checked;
        if (e.target.checked) polyline.addTo(map);
        else map.removeLayer(polyline);
      }});
      listEl.appendChild(card);
    }});

    if (!hasAutoFit) {{
      fitAll();
      hasAutoFit = true;
    }}
  }} catch (err) {{
    document.getElementById('statusText').textContent = '更新失敗：' + err.message;
  }}
}}

document.getElementById('dateSelect').addEventListener('change', (e) => {{
  hasAutoFit = false;
  loadGps(e.target.value);
}});
document.getElementById('fitBtn').addEventListener('click', fitAll);
document.getElementById('refreshBtn').addEventListener('click', () => loadGps(currentDate));

loadGps('');
setInterval(() => loadGps(currentDate), 30000);
</script>
</body>
</html>
"""
    return page.encode("utf-8")


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

    def _check_read_auth(self) -> bool:
        if not LOG_VIEW_PASSWORD:
            return True
        header_token = self.headers.get("X-Mumla-Token", "")
        if header_token and _verify_secret(header_token, LOG_VIEW_PASSWORD):
            return True
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
        self.send_header("WWW-Authenticate", 'Basic realm="Mumla Hub", charset="UTF-8"')
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
        map_url = f"{prefix}/map"
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
    <a class="btn" href="{map_url}">🗺️ 即時滑雪地圖</a>
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
                "gps_days": len(_list_gps_dates()),
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

        if route == "/map":
            if not self._check_read_auth():
                self._send_basic_auth_challenge()
                return
            page = _render_map_html(prefix)
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(page)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(page)
            return

        if route == "/api/gps":
            if not self._check_read_auth():
                self._send_basic_auth_challenge()
                return
            date_str = (query.get("date", [""])[0] or "").strip()
            self._send_json(200, _load_gps_day(date_str))
            return

        if route == "/logs" or route.startswith("/logs/"):
            if not self._check_read_auth():
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
        if route not in ("/log", "/gps"):
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

        if route == "/gps":
            try:
                payload = json.loads(raw.decode("utf-8"))
                points = payload if isinstance(payload, list) else payload.get("points", [payload])
                saved = _save_gps_points(points)
                if saved == 0:
                    self._send_json(400, {"error": "no_valid_gps_points"})
                    return
                self._send_json(200, {"status": "ok", "saved": saved})
            except Exception as e:
                self._send_json(400, {"error": f"invalid_gps_json: {e}"})
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
    print(
        f"[mumla-hub] Listening on 0.0.0.0:{PORT} | repo={GITHUB_REPO} ({RELEASE_TAG}) | logs={LOG_DIR} | gps={GPS_DIR}",
        flush=True,
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
