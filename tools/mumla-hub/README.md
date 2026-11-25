# Mumla Hub（NAS 極簡 OTA + Log 中繼容器）

專為台灣 NAS（搭配 Gcore CDN 域名）設計的零依賴輕量 Docker 服務（記憶體約 ~20 MB），讓位於中國大陸或海外的對講機**完全不需直連 GitHub**，即可完成：
- **OTA 自動更新**：自動代理並快取 GitHub `dev-latest` 最新 APK。
- **遠端除錯日誌回報**：接收對講機上傳的壓縮 log，存一份於 NAS 並自動推上 GitHub 私有倉（`sam0324sam/mumla-logs`），供 AI 直接讀取分析。

## 快速部署步驟

### 1. 建立 GitHub 私有 Log 倉庫與 Fine-grained Token
1. 在 GitHub 建立一個 **Private** 倉庫：`sam0324sam/mumla-logs`（初始化一個 README）。
2. 到 GitHub Settings → Developer settings → Fine-grained personal access tokens：
   - Repository access：選擇 **Only select repositories** → 僅勾選 `sam0324sam/mumla-logs`。
   - Permissions：Repository permissions → **Contents: Read and write**。
   - 產出 Token 備用。

### 2. 啟動 NAS Docker 容器
將整個 `tools/mumla-hub/` 資料夾複製到 NAS 上：
```bash
cp .env.example .env
# 編輯 .env，填入 HUB_TOKEN 與 GITHUB_LOG_TOKEN
docker compose up -d --build
```

### 3. 在現有反向代理（Nginx / Synology / Gcore 源站）掛載路徑
將 `https://你的域名/mumla/` 反向代理到 `http://127.0.0.1:8088/mumla/`：
```nginx
location /mumla/ {
    proxy_pass http://127.0.0.1:8088/mumla/;
    proxy_set_header Host $host;
    client_max_body_size 5M;
    proxy_read_timeout 120s;
}
```
驗證：`curl https://你的域名/mumla/health` 回傳 `{"status":"ok", ...}` 即成功。

### 4. 在對講機的 `/sdcard/mumla_config.json` 填入中繼網址
在原本的 `mumla_config.json` 根層級加上兩行即可：
```json
{
  "hubUrl": "https://你的域名/mumla",
  "hubToken": "與 .env 相同的 HUB_TOKEN",
  "defaultServer": "wan",
  "servers": [ ... ]
}
```
