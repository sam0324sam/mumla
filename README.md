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
| **口袋防誤觸與盲操** | 無（放口袋極易誤按螢幕或方向鍵） | **純黑沉浸口袋鎖**（音量下 5 連按解鎖）＋**長按音量上語音報時**＋**電量語音播報** |
| **隊員 GPS 定位** | 無 | **低功耗 5 分鐘定位信標**（零洗版）＋**原生全員定位總表** |
| **純 Wi-Fi 時區校準** | 依賴系統（無 SIM 卡常跳回 GMT+0） | **TimezoneGuardian** 透過 IP 自動校準與每小時守護系統時區 |
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
* **全黑防誤觸鎖定層**：
  * 進入鎖定後以全黑低背光維持前景焦點，**100% 攔截正面按鍵（OK、返回、方向鍵、選單鍵）與音量誤觸**，同時避免系統深度休眠造成 PTT 掉鍵或系統音量音洩漏。
  * **解鎖方式**：連續點擊 **音量下鍵 5 次** 即刻解鎖（每次點擊皆有短促反饋音，解鎖成功播放確認音）。
* **盲操中文語音報時**：
  * 鎖定狀態下 **長按音量上鍵（500ms）**，直接語音播報目前時間（例如「下午三點二十五分」），完全不用掏出機器看螢幕。
* **多段電量語音提醒**：
  * 電量到達 **80% / 60% / 40% / 20% / 10% / 5%** 時自動播報語音提醒，提早掌握充電時機。

### 4. 低功耗零洗版 GPS 定位信標與全員看板（`GpsBeaconManager`）
* **零洗版回報**：每 5 分鐘（以及開機初次定位、低電量最後定位）將座標、時間與電量寫入使用者註解（User Comment），不洗版頻道文字聊天室。
* **原生全員定位總表**：內建隊員位置看板對話框，一鍵查看全隊所有成員的最新位置、更新時間與電量，並修正頻道描述 ACL 寫入錯誤。
* **省電與室內/離線強化**：
  * **靜止 Wi-Fi BSSID 閘門**：停留在同一 Wi-Fi 熱點時不重複喚醒硬體 GPS 晶片，大幅省電。
  * **A-GPS 時間注入與冷啟動追蹤**：加速無網路輔助時的首次定位速度。
  * **離線單筆覆寫佇列**：斷網期間僅保留最新一筆定位，恢復連線後只補傳最新狀態，不造成訊息轟炸。
  * **同 Wi-Fi 隊友座標鏡像（可選設定，預設關閉）**：室內收不到 GPS 訊號時，可選擇參考同 Wi-Fi 下隊友的有效座標。

### 5. 無 SIM 卡時區守護（`TimezoneGuardianManager`）
* 許多 Android 對講機在不插 SIM 卡、僅連 Wi-Fi（缺乏電信 NITZ 校時）時，重開機或休眠後系統時區會漂移回 `GMT+0`，導致語音報時與定位時間錯誤。
* 開機、連網以及每小時自動透過網路 IP 解析當地時區並寫入系統（支援高通系統屬性與 `AlarmManager`），亦可在設定中切換為**手動強制鎖定時區**。

### 6. 開機即用、連線保活與雲端開發流程
* **開機自動啟動並連線（`BootReceiver`）**：監聽開機與快速開機廣播，自動開啟 Mumla 並連回預設 Mumble 伺服器，還原傳統對講機「轉開電源就能講」的體驗。
* **省電與不斷線強化**：加入 `WifiLock` 與忽略電池最佳化權限，配合 `humla` 子模組省電調整，兼顧長續航與背景通話穩定度。
* **雲端自動編譯與行動除錯**：配置 [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml) 自動產出 APK，並附上 [AGENTS.md](AGENTS.md) 供外出時以手機 Termux + ADB 快速除錯更新。

---

## 配合機型與適用硬體

* **主力適配機型**：
  * **Anysecu** 系列 Android 4G PoC 網路對講機（如採用高通平台、具實體側邊 PTT 鍵與正面方向/功能鍵、發出 `unipro.hotkey.ptt.*` 或 `android.intent.action.PTT.*` 廣播的機型，例如 W2Plus / W7 等同架構機種）。
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
