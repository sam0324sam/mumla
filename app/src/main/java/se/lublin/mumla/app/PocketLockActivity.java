package se.lublin.mumla.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import se.lublin.mumla.service.MumlaService;

/**
 * 口袋純淨防誤觸鎖定層：
 * - 關閉畫面顯示 (全螢幕純黑沉浸 + 最低背光維持焦點，消除系統休眠喚醒掉鍵與系統音量音洩漏)
 * - 100% 阻擋吞噬正面按鍵 (OK、返回、方向鍵、菜單鍵) 與音量+鍵
 * - 連續點擊音量下鍵 5 次解鎖退出，附帶對講機專屬極速短促反饋音與解鎖確認音
 * - 長按音量上鍵 500ms 觸發語音報時
 */
public class PocketLockActivity extends Activity {
    private static final String TAG = "PocketLockActivity";
    private static volatile PocketLockActivity sInstance;
    private static volatile long sLockStartTime = 0;
    private static volatile long sUnlockFinishTime = 0;

    private int mUnlockClicks = 0;
    private long mLastClickTime = 0;
    private long mLastCloseDialogsTime = 0;
    private boolean mInitialKeyReleaseSeen = false;
    private PowerManager.WakeLock mFocusWakeLock;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsUnlocking = false;
    private final Runnable mFinishRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "Unlocking transition complete. Finishing PocketLockActivity.");
            sUnlockFinishTime = System.currentTimeMillis();
            finish();
            overridePendingTransition(0, 0);
            sInstance = null;
        }
    };

    private final Runnable mEnsureWakeFocusRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !mIsUnlocking) {
                acquireWakeFocus(2000L);
            }
        }
    };

    private final Runnable mVolUpLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "Volume-Up Long Press detected in PocketLockActivity! Announcing time.");
            MumlaService service = MumlaService.getInstance();
            if (service != null) {
                service.announceCurrentTime();
            } else {
                Intent intent = new Intent("se.lublin.mumla.action.ANNOUNCE_TIME");
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
            }
        }
    };

    public static boolean isLocked() {
        return sInstance != null && !sInstance.isFinishing();
    }

    public static boolean isLockedOrRecentlyUnlocked() {
        return isLocked() || (System.currentTimeMillis() - sUnlockFinishTime < 900);
    }

    public static void notifyHardwareVolumeDownClick() {
        PocketLockActivity instance = sInstance;
        if (instance != null && !instance.isFinishing()) {
            instance.onUnlockClick();
        }
    }

    public static void dismiss() {
        if (sInstance != null && !sInstance.isFinishing()) {
            sUnlockFinishTime = System.currentTimeMillis();
            sInstance.finish();
            sInstance = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
        sLockStartTime = System.currentTimeMillis();
        mInitialKeyReleaseSeen = false;

        // 綁定音量控制串流，防止音量鍵彈出系統面板或發出系統音效
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // 全螢幕沉浸式純黑，不再常駐 FLAG_KEEP_SCREEN_ON 以允許系統依預設時間真正滅屏休眠省電
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window win = getWindow();
        win.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        WindowManager.LayoutParams lp = win.getAttributes();
        lp.screenBrightness = 0.01f;
        win.setAttributes(lp);

        View blackView = new View(this);
        blackView.setBackgroundColor(Color.BLACK);
        blackView.setFocusable(true);
        blackView.setFocusableInTouchMode(true);
        blackView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(blackView);
        blackView.requestFocus();

        // 短暫持喚醒鎖 (2 秒) 反制 ROM 在 F4 長按時觸發的 Going to sleep，之後自動釋放讓螢幕自然滅屏進入省電休眠
        acquireWakeFocus(2000L);
        mHandler.postDelayed(mEnsureWakeFocusRunnable, 220);

        Log.i(TAG, "PocketLockActivity active (Display Blackout, Sleep Power-Save Enabled & Front Keys Blocked)");
    }

    @SuppressWarnings("deprecation")
    private void acquireWakeFocus(long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (mFocusWakeLock == null) {
                    mFocusWakeLock = pm.newWakeLock(
                            PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "Mumla:PocketLockFocus"
                    );
                    mFocusWakeLock.setReferenceCounted(false);
                }
                mFocusWakeLock.acquire(timeoutMs);
            }
        } catch (Exception ignored) {}
    }

    private void releaseWakeFocus() {
        if (mFocusWakeLock != null) {
            try {
                if (mFocusWakeLock.isHeld()) {
                    mFocusWakeLock.release();
                }
            } catch (Exception ignored) {}
            mFocusWakeLock = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 僅在螢幕亮起狀態下防禦系統對話框搶奪焦點，不再阻擋自然休眠滅屏
        if (!hasFocus && isLocked() && !mIsUnlocking) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isInteractive()) {
                closeSystemDialogsThrottled();
            }
        }
    }

    private void closeSystemDialogsThrottled() {
        long now = System.currentTimeMillis();
        if (now - mLastCloseDialogsTime >= 400) {
            mLastCloseDialogsTime = now;
            try {
                sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            } catch (Exception ignored) {}
        }
    }

    private boolean isVolumeDownPhysicalKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_F4;
    }

    private boolean isVolumeDownSyntheticKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // 若已進入解鎖過渡期間，100% 徹底吞噬所有按鍵與放開事件，嚴格防止放開時合成的 25 鍵碼或慣性多按的第 6 下洩漏給系統 AudioService
        if (mIsUnlocking) {
            sUnlockFinishTime = System.currentTimeMillis();
            if (action == KeyEvent.ACTION_UP && (isVolumeDownPhysicalKey(keyCode) || isVolumeDownSyntheticKey(keyCode))) {
                // 收到最後一次放開事件後，緩衝 380ms 確保伴隨注入的合成 25 鍵碼與餘波完全被吃掉，再關閉視窗
                mHandler.removeCallbacks(mFinishRunnable);
                mHandler.postDelayed(mFinishRunnable, 380);
            }
            return true;
        }

        // 1. 解鎖鍵：只響應實體「F4/音量下鍵」(134)
        if (isVolumeDownPhysicalKey(keyCode)) {
            long now = System.currentTimeMillis();
            // 過濾觸發上鎖那一下長按尚未放開的殘留事件
            if (!mInitialKeyReleaseSeen && (now - sLockStartTime < 600 || event.getRepeatCount() > 0)) {
                if (action == KeyEvent.ACTION_UP) {
                    mInitialKeyReleaseSeen = true;
                }
                return true;
            }
            mInitialKeyReleaseSeen = true;

            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                onUnlockClick();
            }
            return true;
        }

        // 吞噬放開時 ROM 注入的合成音量下鍵 (KEYCODE_VOLUME_DOWN 25)，不計數、不改變系統音量、不觸發安卓音量音效
        if (isVolumeDownSyntheticKey(keyCode)) {
            return true;
        }

        // 2. 音量鍵+ (KEYCODE_VOLUME_UP 24)：
        // 長按 500ms 觸發語音報時，短按吞噬不調整音量
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    mHandler.removeCallbacks(mVolUpLongPressRunnable);
                    mHandler.postDelayed(mVolUpLongPressRunnable, 500);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                mHandler.removeCallbacks(mVolUpLongPressRunnable);
            }
            return true;
        }

        // 3. 返回鍵 (KEYCODE_BACK 4)：
        // 防止長按返回鍵彈出系統關機選單，節流發送廣播秒殺對話框
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            closeSystemDialogsThrottled();
            return true;
        }

        // 4. 正面其餘所有按鍵（方向鍵、OK鍵、菜單鍵等）：
        // 100% 徹底阻擋吞噬，不發出聲音，不傳給底層 Mumla！
        return true;
    }

    public synchronized void onUnlockClick() {
        if (mIsUnlocking) {
            return;
        }
        long now = System.currentTimeMillis();

        // 忽略剛進入鎖屏 300ms 內的長按殘留
        if (now - sLockStartTime < 300) {
            return;
        }

        // 130ms 極限靈敏防抖 (消除機械開關觸點彈跳與按鍵抖動，支援快速連按)
        if (now - mLastClickTime < 130) {
            return;
        }

        if (now - mLastClickTime < 1200) {
            mUnlockClicks++;
        } else {
            mUnlockClicks = 1; // 間隔超過 1.2 秒重新計數
        }
        mLastClickTime = now;

        // 解鎖按鍵連按期間短暫喚醒視窗搶回焦點，確保吃掉 ROM 注入的合成音量下鍵 (25) 不外洩
        acquireWakeFocus(3500L);

        Log.d(TAG, "Volume-Down Click in lock mode: " + mUnlockClicks + "/5");

        MumlaService service = MumlaService.getInstance();
        if (mUnlockClicks >= 5) {
            mUnlockClicks = 0;
            unlockAndFinish(service);
        }
    }

    private void unlockAndFinish(MumlaService service) {
        if (mIsUnlocking) return;
        mIsUnlocking = true;
        sUnlockFinishTime = System.currentTimeMillis();
        Log.i(TAG, "Unlocked by 5 Volume-Down clicks! Playing ACK and buffering release event.");

        if (service != null) {
            service.playPocketUnlockAckTone();
        }

        // 保底 500ms 關閉（完整播放確認音並吃掉所有可能延遲的放開與合成 25 鍵碼注入事件）
        mHandler.removeCallbacks(mFinishRunnable);
        mHandler.postDelayed(mFinishRunnable, 500);
    }

    @Override
    public void onBackPressed() {
        // 嚴禁返回鍵退出
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        releaseWakeFocus();
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }
}
