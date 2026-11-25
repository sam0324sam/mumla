# Mumla PoC 對講機專用客製版

本專案基於開源 Android Mumble 客戶端 [Mumla](https://gitlab.com/quite/mumla)（搭配客製版 [sam0324sam/humla](https://github.com/sam0324sam/humla) 音訊/協定核心）深度改造，專為 **Android 實體按鍵 PoC 網路對講機**打造。

目標是把原本面向一般觸控手機的語音聊天 App，轉化成一台**「開機自動連線、放口袋盲操不誤觸、具商業無線電通話紀律與隊員定位能力」**的專業對講機系統。

> 📥 **最新測試版 APK 直接下載**：前往 [Releases (`dev-latest`)](https://github.com/sam0324sam/mumla/releases/tag/dev-latest) 下載 `mumla-latest-debug.apk`。

---

## 與原版 Mumla 的核心差別

| 項目 | 原版 Mumla | 本客製版（PoC 對講機專用） |
| :--- | :--- | :--- |
| **定位與對象** | 一般觸控手機語音聊天室 | 專用 Android PoC 實體按鍵對講機（開機即用） |
| **實體 PTT 整合** | 僅支援通用按鍵映射，休眠或特定機型易掉鍵 | 原生整合 Anysecu / UniPro 實體 PTT 廣播與底層監聽，解決 ALSA 佔用死鎖 |
| **通話紀律（排他）** | 預設多人可同時插話（全雙工混音） | **搶 MIC 排他機制 + ALSA BCLO 防衝突 + TOT 60 秒超時切斷** |
| **提示音效** | 簡單系統音或無明確無線電反饋 | 零延遲 **DMR / Motorola 風格 PCM 合成提示音**，統一音量防爆音 |
| **口袋防誤觸與盲操** | 無（放口袋極易誤按螢幕或方向鍵） | **純黑口袋鎖（支援真正熄屏休眠）**（音量下 5 連按解鎖）＋**長按音量上 16kHz 記憶體語音報時**＋**電量語音播報** |
| **隊員 GPS 定位** | 無 | **自適應低功耗定位信標（5 / 10 / 30 分鐘）**（零洗版）＋**原生全員定位總表** |
| **純 Wi-Fi 時區校準** | 依賴系統（無 SIM 卡常跳回 GMT+0） | **TimezoneGuardian** 透過 IP 自動校準與每小時守護系統時區 |
| **省電與語音延遲** | 麥克風長駐喚醒、語音經 UI 執行緒、100ms 緩衝 | **3 秒無鎖麥克風休眠 + 動態 Ping (Wi-Fi 45s / 4G 25s) + UDP 語音直通 + 40ms 緩衝**（實測待機 ~18.8 mA） |
| **體積與依賴精簡** | 含 CELT7/11、Guava、Jsoup、Tor、公開列表與 30+ 語系 (~17.3 MB) | **純 Opus 核心 + 移除冗餘依賴 + R8 壓縮**（APK 約 **9.9 MB**，含繁中像素字型） |
| **小螢幕顯示與選單** | 面向高解析度手機，細體字在小螢幕糊成灰塊、選單冗長 | **低解析度自動清晰模式（Cubic-11 像素字型 + 純黑高對比 + 緊湊 PTT 條 + Adreno 軟體渲染防花屏）＋極簡側邊選單**（手機端不受影響） |
| **開機自啟** | 需手動開啟 App 並點擊伺服器 | **開機自動啟動並自動連線**，比照傳統無線電開機即講 |
| **編譯與維護** | 需本機 Android Studio / Gradle | 內建 **GitHub Actions 雲端自動編譯 APK** ＋手機 Termux/ADB 遠端除錯指引 |

---

## 新增功能與詳細調整

### 1. 硬體 PTT 原生整合與商業無線電通話紀律
* **原生硬體 PTT 支援**（`TalkBroadcastReceiver` / `MumlaService`）：
  * 直接監聽 `unipro.hotkey.ptt.down/up` 與 `android.intent.action.PTT.down/up` 系統廣播，並搭配底層 logcat 事件監控，不需額外安裝第三方按鍵橋接器。
  * 修復部分對講機在頻繁按壓 PTT 時出現的 **ALSA 錄音裝置忙碌死鎖（capture busy deadlock）**。
* **搶 MIC 排他機制（Exclusive Floor Control）＋ BCLO 防衝突**：
  * 當頻道內已有隊友正在發話時，其他人按下 PTT 會被自動阻擋並播放「頻道忙碌拒絕音（Bonk）」，避免多人同時疊音聽不清楚。
* **TOT（Time-Out Timer）60 秒發話超時保護**：
  * 單次連續發話達 60 秒前發出警示音並自動切斷發射，防止誤壓 PTT 導致整組頻道被長時間鎖死。

### 2. 零延遲 DMR 風格提示音合成器（`PttSoundManager`）
* 捨棄高延遲音檔載入，直接以 16kHz PCM 即時合成商業無線電音效：
  * **發話許可音（Permit Tone）**：Motorola / DMR 雙音提示（900Hz + 1200Hz），按下即可確認取得話權。
  * **忙碌拒絕音（Busy Bonk）**：頻道被佔用時的低頻拒絕提示。
  * **放開結束音（Release Roger）**：鬆開 PTT 的短促結束音。
  * **鎖定 / 5 連按解鎖 / TOT 警告音**：所有操作皆有清楚的聽覺反饋。
* **統一音量防爆音**：所有提示音統一走 `STREAM_MUSIC` 並收斂至 1/3 參考振幅，針對對講機高功率大喇叭最佳化，不再刺耳爆音。

### 3. 口袋純淨防誤觸鎖定＋盲操語音助理（`PocketLockActivity`）
* **全黑防誤觸鎖定層（支援真正熄屏省電）**：
  * 進入鎖定後攔截正面按鍵（OK、返回、方向鍵、選單鍵）與音量誤觸，並於鎖定 2 秒後釋放螢幕喚醒鎖讓螢幕真正熄滅（`mWakefulness=Asleep`），兼顧防誤觸與極致省電。
  * **解鎖方式**：連續點擊 **音量下鍵 5 次** 即刻解鎖（每次點擊短暫喚醒焦點並提供反饋音，解鎖成功播放確認音）。
* **盲操中文語音報時（零 Flash 寫入）**：
  * 鎖定狀態下 **長按音量上鍵（500ms）**，透過專屬背景執行緒以記憶體內 16kHz `AudioTrack` 直接拼接播報目前時間（例如「下午三點二十五分」），零磁碟磨損、零 `MediaPlayer` 延遲。
* **多段電量語音提醒**：
  * 電量到達 **80% / 60% / 40% / 20% / 10% / 5%** 時自動播報語音提醒，提早掌握充電時機。

### 4. 自適應低功耗零洗版 GPS 定位信標與全員看板（`GpsBeaconManager`）
* **零洗版回報**：將座標、時間與電量寫入使用者註解（User Comment），不洗版頻道文字聊天室，並避免自我重複拉取 Comment Blob。
* **原生全員定位總表**：內建兩層式隊員位置看板（總表＋個人詳情）與管理員遠端清除指令，一鍵查看全隊所有成員的在線狀態、最新位置、更新時間與電量。
* **自適應省電與室內／離線強化**：
  * **多段動態降頻**：移動中 **5 分鐘** → 戶外定點（位移 `<30m`）**10 分鐘** → 室內同 Wi-Fi BSSID 且電量穩定（變化 `<2%`）**30 分鐘**；低電量求生模式（`≤20%` 降為 15 分鐘、`≤10%` 降為 30 分鐘）。
  * **靜止 Wi-Fi BSSID 閘門**：停留在同一 Wi-Fi 熱點時直接跳過硬體 GPS 喚醒。
  * **A-GPS 注入節流與冷啟動上限**：XTRA／時間注入節流至每 6 小時一次，冷啟動追蹤上限 90 秒並具室內失敗指數退避。
  * **離線單筆覆寫佇列**：斷網期間僅保留最新一筆定位，恢復連線後只補傳最新狀態；線上成功時略過多餘磁碟寫入。
  * **同 Wi-Fi 隊友座標鏡像（可選設定，預設關閉）**：室內收不到 GPS 訊號時，可選擇參考同 Wi-Fi 下隊友的有效座標。

### 5. 無 SIM 卡時區守護（`TimezoneGuardianManager`）
* 許多 Android 對講機在不插 SIM 卡、僅連 Wi-Fi（缺乏電信 NITZ 校時）時，重開機或休眠後系統時區會漂移回 `GMT+0`，導致語音報時與定位時間錯誤。
* 開機、連網以及每小時自動透過網路 IP 解析當地時區並寫入系統（支援高通系統屬性與 `AlarmManager`），亦可在設定中切換為**手動強制鎖定時區**。

### 6. 開機即用、連線保活與雲端開發流程
* **開機自動啟動並連線（`BootReceiver`）**：監聽開機與快速開機廣播，自動開啟 Mumla 並連回預設 Mumble 伺服器，還原傳統對講機「轉開電源就能講」的體驗。
* **智慧指數退避重連**：網路斷線時透過 `NetworkCallback` 暫停空轉重試，網路恢復瞬間自動重置退避並在 1 秒內自動重連回原頻道。
* **雲端自動編譯與行動除錯**：配置 [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml) 自動產出 APK，並附上 [AGENTS.md](AGENTS.md) 供外出時以手機 Termux + ADB 快速除錯更新。

### 7. 極致省電、低延遲與第一性原理瘦身（實機調優）
* **深度省電架構（Anysecu G393 實測 13 小時過夜待機僅掉 ~2–4%、待機電流 ~18.8 mA）**：
  * **無鎖 3 秒硬體麥克風休眠（`AudioInput`）**：PTT 鬆開閒置 3 秒後由錄音執行緒自身安全停止 `AudioRecord`，釋放 `AudioIn` WakeLock 與麥克風 ADC 供電（零 AB-BA 死鎖風險）。
  * **802.11 PSM + 動態 Keepalive Ping**：使用 `WIFI_MODE_FULL` 取代高功耗鎖，並依網路類型動態調整 Ping 間隔（**純 Wi-Fi `45s`** 避開高通 15 秒尾端計時器，實測射頻休眠佔比達 ~66.7%；**行動網路 `25s`** 兼顧 4G UDP NAT 保活）。
  * **零閒置喚醒與記憶體治理**：延遲初始化 `TextToSpeech`／懸浮窗／熱區、前景通知僅在狀態實際變動時更新並設 `setOnlyAlertOnce(true)`、修復 `ServerAdapter` 執行緒池洩漏（改為共用 15 秒自動回收池）。
* **超低延遲語音管線**：
  * 收到 UDP 語音封包後於 `THREAD_PRIORITY_AUDIO` 執行緒直通解碼（完全繞過 Android UI 主執行緒）、Speex JitterBuffer 邊際由 100ms 縮減至 **40ms**、單人通話採零執行緒切換快速路徑，並重複使用 `DirectByteBuffer` 與封包緩衝區降低 GC 停頓。
* **第一性原理體積瘦身（APK 17.30 MB → ~9.9 MB，含繁中像素字型）**：
  * 轉為純 **Opus** 對講核心，移除過時的 CELT7 / CELT11 編解碼與 8 份原生 `.so`。
  * 移除 `Guava`、`Jsoup`、`Tor / Orbot (netcipher)`、公開伺服器瀏覽、自動抓取大頭貼與 30+ 未使用語系（僅保留 `zh-rTW` / `en` 與 `armeabi-v7a` / `arm64-v8a`）。
  * 語音播報檔降採樣至 16kHz 16-bit Mono 並啟用 R8 程式碼／資源壓縮。

### 8. 小螢幕低解析度清晰模式與極簡對講選單（`DisplayClarityHelper`）
* **精準偵測低解析度硬體（手機端零影響）**：針對 Anysecu G393 等實體 `128×160`（覆寫 `200×240 @ 80dpi`）超小螢幕自動啟用清晰模式，亦可在「外觀設定」手動切換（`自動` / `強制開啟` / `關閉`）。
* **Cubic-11 俐方體像素字型＋純黑高對比主題**：內嵌 SIL OFL 開源繁中點陣字型，搭配純黑底 `#000000`、純白字 `#FFFFFF` 與自動字級縮放（`1.95×`），讓繁體中文字在 1.77 吋小面板上筆畫銳利不發糊。
* **Adreno 304 防花屏與緊湊排版**：低解析度裝置自動改走 Skia 軟體渲染避開 `msm8909go` GPU 破圖，並將房間內螢幕 PTT 按鈕收斂至約 16% 高度、縮減頻道階層縮排。
* **極簡側邊選單與繁中語系修正**：移除贊助、已釘選頻道、存取代碼等冗餘項目（側邊欄僅保留 **所有頻道 / 收藏 / 設定**），將「伺服器資訊」收整進設定頁，並修正語言選單僅提供支援之繁體中文（`zh-TW`）與英文（`en`）。
* **自架 Mumble / Murmur 伺服器建議配置**：
  * 配合客戶端 45s Wi-Fi Ping，建議在伺服器將 `timeout` 設為 **`75`**（Docker 環境變數 `MUMBLE_CONFIG_TIMEOUT=75`）。
  * 若使用 Murmur 1.5+，建議設定 `listenersperchannel=0` 與 `listenersperuser=0`（`MUMBLE_CONFIG_LISTENERSPERCHANNEL=0`、`MUMBLE_CONFIG_LISTENERSPERUSER=0`），可消除對傳統 Mumble 1.2.5 協定客戶端發送的 `[WARNING] ChannelListener` 通知告警。

---

## 配合機型與適用硬體

* **主力適配機型**：
  * **Anysecu** 系列 Android 4G PoC 網路對講機（實測驗證機型：**Anysecu G393** / `msm8909go` Android 8.1 Go，亦相容採用高通平台、具實體側邊 PTT 鍵與 `unipro.hotkey.ptt.*` / `android.intent.action.PTT.*` 廣播的 W2Plus / W7 等同架構機種）。
* **其他相容裝置**：
  * 各類具備實體 PTT 鍵的 **Android PoC 網路對講機 / 三防手機**。
  * 一般 **Android 5.0（API 21）以上智慧型手機**：可與對講機同頻道互通，作為隊長機或後勤端查看「全員定位總表」與即時通話。
* **網路模式**：
  * 支援 **4G/5G SIM 卡**，亦完整支援 **純 Wi-Fi（免插 SIM 卡）** 連手機熱點或場域 Wi-Fi 運作。

---

## 適合使用場景

* **戶外活動與車隊出遊**：登山健行、露營、單車／重機／自駕車隊。放口袋防誤觸、手伸進口袋就能按 PTT 通話或長按音量鍵聽報時、低電量主動語音提醒，並可透過全員定位總表確認隊友位置防走散。
* **活動執行與場域調度**：展覽、市集、演唱會、園區巡檢、工地或倉儲作業。只要有 4G 或 Wi-Fi 覆蓋即可突破傳統無線電地形與距離限制，同時保留專業對講機的搶 MIC 排他紀律與清晰提示音。
* **旅遊與家庭隨身聯絡**：多人出國旅遊或帶長輩小孩同行，對講機開機自動連上伺服器、免滑螢幕、按住就講，免去繁瑣操作又能掌握電量與位置。
* **自架私有語音對講網（Mumble / Murmur）**：重視通訊隱私、不想被商業 PoC 平台年費綁定，希望用自建 Mumble 伺服器搭配平價 Android 對講機打造專屬對講系統的個人或團隊。

---

## 編譯方式

### 方式一：GitHub Actions 雲端自動編譯（推薦）
每次推送至 `master` 分支，GitHub Actions 會自動執行 `:app:assembleFossDebug`，產出：
* Actions Artifact：`mumla-debug-apk`
* Pre-release：[dev-latest](https://github.com/sam0324sam/mumla/releases/tag/dev-latest)（`mumla-latest-debug.apk`）

*(若需讓雲端編譯與本機開發維持相同的 debug 簽名以無痛覆蓋安裝，可將本機 `~/.android/debug.keystore` 轉為 Base64 存入 Repository Secret `DEBUG_KEYSTORE_BASE64`。)*

### 方式二：本機編譯（GNU/Linux / Windows）
本專案使用 JDK 21 與 Git Submodule（[sam0324sam/humla](https://github.com/sam0324sam/humla)），並需要 Android NDK（以 `libraries/humla/build.gradle` 指定的版本為準，預設為 `25.1.8937393`）：

```bash
git clone --recursive https://github.com/sam0324sam/mumla.git
cd mumla

pushd libraries/humla/libs/humla-spongycastle
../../gradlew jar
popd

./gradlew :app:assembleFossDebug
```

---

## 原專案致謝與授權

* 上游專案：[Mumla](https://gitlab.com/quite/mumla)（由 [Daniel Lublin](https://lublin.se) 維護，分支自 Andrew Comminos 的 [Plumble](https://github.com/acomminos/Plumble) 與 [Jumble](https://github.com/acomminos/Jumble)）。
* 授權條款：沿用原專案之 **GNU General Public License v3.0**，詳見 [LICENSE](LICENSE)。
