package se.lublin.mumla.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import se.lublin.mumla.R;
import se.lublin.mumla.service.MumlaService;

/**
 * 口袋純淨防誤觸鎖定層：
 * - 關閉畫面顯示 (全螢幕純黑沉浸)
 * - 亮度管理遵循原廠系統預設，不干涉休眠
 * - 100% 阻擋吞噬正面按鍵 (OK、返回、方向鍵、菜單鍵) 與音量+鍵
 * - 連續點擊音量下鍵 5 次解鎖退出，附帶極速短促反饋音與解鎖確認音
 * - 長按音量上鍵 500ms 觸發語音報時
 */
public class PocketLockActivity extends Activity {
    private static final String TAG = "PocketLockActivity";
    private static volatile PocketLockActivity sInstance;

    private int mUnlockClicks = 0;
    private long mLastClickTime = 0;
    private ToneGenerator mToneGenerator;
    private SoundPool mSoundPool;
    private int mClickSoundId = 0;

    private final Handler mUnlockHandler = new Handler(Looper.getMainLooper());
    private boolean mIsUnlocking = false;
    private final Runnable mFinishRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "Unlocking transition complete. Finishing PocketLockActivity.");
            finish();
            sInstance = null;
        }
    };

    private final Handler mVolUpHandler = new Handler(Looper.getMainLooper());
    private boolean mVolUpLongPressTriggered = false;
    private final Runnable mVolUpLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            mVolUpLongPressTriggered = true;
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

    public static void dismiss() {
        if (sInstance != null && !sInstance.isFinishing()) {
            sInstance.finish();
            sInstance = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;

        try {
            mToneGenerator = new ToneGenerator(AudioManager.STREAM_SYSTEM, 90);
        } catch (Exception ignored) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                mSoundPool = new SoundPool.Builder().setMaxStreams(5).setAudioAttributes(attrs).build();
            } else {
                mSoundPool = new SoundPool(5, AudioManager.STREAM_SYSTEM, 0);
            }
            mClickSoundId = mSoundPool.load(this, R.raw.key_click, 1);
        } catch (Exception ignored) {}

        // 綁定音量控制串流，防止音量鍵彈出系統面板或發出系統音效
        setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);

        // 全螢幕沉浸式純黑，關閉內容顯示 (亮度與休眠遵循系統原廠設定)
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window win = getWindow();
        win.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        View blackView = new View(this);
        blackView.setBackgroundColor(Color.BLACK);
        blackView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(blackView);

        Log.i(TAG, "PocketLockActivity active (Display Blackout & Front Keys Blocked)");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 若系統關機選單或任何 System Dialog 嘗試搶奪焦點，立即強制關閉！
        if (!hasFocus && isLocked()) {
            try {
                sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            } catch (Exception ignored) {}
        }
    }

    private boolean isVolumeDownPhysicalKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_F4 || keyCode == 134;
    }

    private boolean isVolumeDownSyntheticKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == 25;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // 若已進入解鎖過渡期間，100% 徹底吞噬所有按鍵與放開事件，嚴格防止放開時合成的 25 鍵碼洩漏給系統 AudioService 下調音量
        if (mIsUnlocking) {
            if (action == KeyEvent.ACTION_UP && isVolumeDownPhysicalKey(keyCode)) {
                // 收到第 5 次按壓的實體放開事件後，緩衝 120ms 確保伴隨注入的合成 25 鍵碼也被完全吃掉，再關閉視窗
                mUnlockHandler.removeCallbacks(mFinishRunnable);
                mUnlockHandler.postDelayed(mFinishRunnable, 120);
            }
            return true;
        }

        // 1. 解鎖鍵：只響應實體「F4/音量下鍵」(134) 的 ACTION_DOWN 按下事件，且 repeatCount 必須為 0
        if (isVolumeDownPhysicalKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    onUnlockClick();
                }
            }
            return true;
        }

        // 吞噬放開時 ROM 注入的合成音量下鍵 (KEYCODE_VOLUME_DOWN 25)，不計數、不改變系統音量
        if (isVolumeDownSyntheticKey(keyCode)) {
            return true;
        }

        // 2. 音量鍵+ (KEYCODE_VOLUME_UP 24)：
        // 長按 500ms 觸發語音報時，短按吞噬不調整音量
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == 24) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    mVolUpLongPressTriggered = false;
                    mVolUpHandler.postDelayed(mVolUpLongPressRunnable, 500);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                mVolUpHandler.removeCallbacks(mVolUpLongPressRunnable);
            }
            return true;
        }

        // 3. 返回鍵 (KEYCODE_BACK 4)：
        // 防止長按返回鍵彈出系統關機選單，發送廣播秒殺對話框！
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 4) {
            try {
                sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            } catch (Exception ignored) {}
            return true;
        }

        // 4. 正面其餘所有按鍵（方向鍵、OK鍵、菜單鍵等）：
        // 100% 徹底阻擋吞噬，不發出聲音，不傳給底層 Mumla！
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return true;
    }

    private synchronized void onUnlockClick() {
        long now = System.currentTimeMillis();

        // 150ms 極限靈敏防抖 (消除微秒級機械開關觸點彈跳與按鍵抖動，支援快速連按)
        if (now - mLastClickTime < 150) {
            return;
        }

        if (now - mLastClickTime < 1100) {
            mUnlockClicks++;
        } else {
            mUnlockClicks = 1; // 間隔超過 1.1 秒重新計數
        }
        mLastClickTime = now;

        Log.d(TAG, "Volume-Down Click in lock mode: " + mUnlockClicks + "/5");

        // 口袋盲操極速短促滴聲反饋 (25ms SoundPool，記憶體常駐零延遲，支援多聲道混音)
        if (mSoundPool != null && mClickSoundId != 0) {
            mSoundPool.play(mClickSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        } else if (mToneGenerator != null) {
            try {
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 25);
            } catch (Exception ignored) {}
        }

        // 連續按下滿 5 次解鎖！
        if (mUnlockClicks >= 5) {
            mUnlockClicks = 0;
            unlockAndFinish();
        }
    }

    private void unlockAndFinish() {
        if (mIsUnlocking) return;
        mIsUnlocking = true;
        Log.i(TAG, "Unlocked by 5 Volume-Down clicks! Playing ACK and buffering release event.");
        try {
            if (mToneGenerator != null) {
                // 輕快兩聲確認音「嗶嗶」(250ms)
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 250);
            }
        } catch (Exception ignored) {}

        // 保底 380ms 關閉（完整播放 250ms 確認音並吃掉所有可能延遲的放開與注入事件）
        mUnlockHandler.removeCallbacks(mFinishRunnable);
        mUnlockHandler.postDelayed(mFinishRunnable, 380);
    }

    @Override
    public void onBackPressed() {
        // 嚴禁返回鍵退出
    }

    @Override
    protected void onDestroy() {
        mUnlockHandler.removeCallbacks(mFinishRunnable);
        mVolUpHandler.removeCallbacks(mVolUpLongPressRunnable);
        if (mSoundPool != null) {
            try { mSoundPool.release(); } catch (Exception ignored) {}
            mSoundPool = null;
        }
        if (mToneGenerator != null) {
            try { mToneGenerator.release(); } catch (Exception ignored) {}
            mToneGenerator = null;
        }
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }
}
