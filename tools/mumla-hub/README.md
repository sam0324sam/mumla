# Mumla Hub（NAS 極簡 OTA + 本機密碼保護 Log 伺服器）

專為台灣 NAS（搭配 HTTPS 反向代理：`https://mumla.hiyoko-sam.com`）設計的零依賴輕量 Docker 服務（記憶體約 ~20 MB），讓對講機**完全不需直連 GitHub**，即可完成：
- **OTA 自動更新**：自動代理並快取 GitHub `dev-latest` 最新 APK。
- **遠端除錯日誌儲存與檢視**：接收對講機上傳的壓縮 log，直接存於 NAS 本機（自動保留最新 100 筆），並提供**密碼保護的網頁與 API** 隨時從外網查閱。

## 外網檢視 / 撈取 Log 方式

- **網頁檢視（手機 / 電腦瀏覽器）**：
  - `https://mumla.hiyoko-sam.com/logs`（若反向代理掛載於 `/mumla/` 則為 `https://mumla.hiyoko-sam.com/mumla/logs`）
  - 瀏覽器會跳出原生帳號密碼框：**帳號隨意填、密碼填 `HUB_TOKEN`（或 `LOG_VIEW_PASSWORD`）**，即可瀏覽、下載所有裝置的 log。
- **程式 / AI 快速撈取最新 Log**：
  - 最新一筆 log：`curl -H "X-Mumla-Token: <密碼>" https://mumla.hiyoko-sam.com/logs/latest`
  - 指定裝置最新 log：`curl -H "X-Mumla-Token: <密碼>" "https://mumla.hiyoko-sam.com/logs/latest?user=Hiyoko"`
  - JSON 清單：`curl -H "X-Mumla-Token: <密碼>" "https://mumla.hiyoko-sam.com/logs?format=json"`

## 快速部署步驟

### 1. 啟動 NAS Docker 容器
將整個 `tools/mumla-hub/` 資料夾複製到 NAS 上：
```bash
cp .env.example .env
# 編輯 .env，填入 HUB_TOKEN（對講機上傳與外網看 Log 共用密碼）
docker compose up -d --build
```

### 2. 在現有反向代理（Nginx / Synology / Gcore 源站）掛載路徑
直接將 `https://mumla.hiyoko-sam.com/`（或 `/mumla/`）反向代理到 `http://127.0.0.1:8088/`：
```nginx
location / {
    proxy_pass http://127.0.0.1:8088/;
    proxy_set_header Host $host;
    client_max_body_size 5M;
    proxy_read_timeout 120s;
}
```
驗證：`curl https://mumla.hiyoko-sam.com/health` 回傳 `{"status":"ok", ...}` 即成功。

### 3. 在對講機的 `/sdcard/mumla_config.json` 填入中繼網址
在原本的 `mumla_config.json` 根層級加上兩行即可：
```json
{
  "hubUrl": "https://mumla.hiyoko-sam.com",
  "hubToken": "與 .env 相同的 HUB_TOKEN",
  "defaultServer": "wan",
  "servers": [ ... ]
}
```
