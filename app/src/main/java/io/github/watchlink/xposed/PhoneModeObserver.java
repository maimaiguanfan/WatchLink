package io.github.watchlink.xposed;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import io.github.watchlink.notify.NotificationDndPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Zero-blocking phone mode observer shared by Health :PhoneService and :DaemonService.
 * Tracks phone DND, unlocked screen state, ColorOS ZenSpace (deep + light zen) / Focus Mode,
 * and ColorOS Game Space foreground state via BroadcastReceiver + ContentObserver into volatile
 * memory fields so hook callbacks never perform Binder or ContentResolver I/O.
 */
final class PhoneModeObserver {
    private static final String KEY_ZEN_MODE = "zen_mode";
    private static final String KEY_BREATH_MODE = "op_breath_mode_status";
    private static final String KEY_LIGHT_BREATH_MODE = "op_light_breath_mode_status";
    private static final String KEY_FOCUS_SWITCH = "focusmode_switch";
    private static final String KEY_FOCUS_SWITCH_NEW = "focusmode_switch_new";
    private static final String KEY_GAME_MODE_VALUE = "debug_gamemode_value";
    private static final String KEY_GAME_MODE_DELAYED = "gamemode_delayed";

    private final Context app;
    private final Executor worker;
    private final List<BroadcastReceiver> receivers = new ArrayList<>();
    private final List<ContentObserver> observers = new ArrayList<>();

    private volatile boolean active;
    private volatile boolean dndActive;
    private volatile boolean screenUnlocked;
    private volatile boolean focusActive;
    private volatile boolean gameForeground;

    PhoneModeObserver(Context app, Executor worker) {
        this.app = app;
        this.worker = worker;
    }

    void start() {
        active = true;
        refreshAll();
        registerDndAndScreenReceivers();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        observeUri(Settings.Global.getUriFor(KEY_ZEN_MODE), mainHandler, this::refreshDnd);
        observeUri(Settings.Secure.getUriFor(KEY_BREATH_MODE), mainHandler, this::refreshFocus);
        observeUri(Settings.Global.getUriFor(KEY_BREATH_MODE), mainHandler, this::refreshFocus);
        observeUri(Settings.Secure.getUriFor(KEY_LIGHT_BREATH_MODE), mainHandler, this::refreshFocus);
        observeUri(Settings.Secure.getUriFor(KEY_FOCUS_SWITCH), mainHandler, this::refreshFocus);
        observeUri(Settings.Secure.getUriFor(KEY_FOCUS_SWITCH_NEW), mainHandler, this::refreshFocus);
        observeUri(Settings.Global.getUriFor(KEY_GAME_MODE_VALUE), mainHandler, this::refreshGame);
        observeUri(Settings.Global.getUriFor(KEY_GAME_MODE_DELAYED), mainHandler, this::refreshGame);
    }

    void close() {
        active = false;
        for (BroadcastReceiver r : receivers) {
            try { app.unregisterReceiver(r); } catch (RuntimeException ignored) {}
        }
        receivers.clear();
        ContentResolver cr = app.getContentResolver();
        for (ContentObserver obs : observers) {
            try { cr.unregisterContentObserver(obs); } catch (RuntimeException ignored) {}
        }
        observers.clear();
    }

    boolean isDndActive() { return dndActive; }

    boolean isScreenUnlocked() { return screenUnlocked; }

    boolean isFocusActive() { return focusActive; }

    boolean isGameForeground() { return gameForeground; }

    void refreshAll() {
        refreshDnd();
        refreshScreen();
        refreshFocus();
        refreshGame();
    }

    void refreshDnd() {
        int filter = NotificationDndPolicy.INTERRUPTION_FILTER_UNKNOWN;
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm != null) filter = nm.getCurrentInterruptionFilter();
        } catch (RuntimeException ignored) {}
        int zenMode = readGlobalInt(KEY_ZEN_MODE);
        dndActive = NotificationDndPolicy.isPhoneDndActive(filter, zenMode);
    }

    private void refreshScreen() {
        boolean interactive = false;
        boolean keyguardLocked = true;
        try {
            PowerManager pm = app.getSystemService(PowerManager.class);
            interactive = pm != null && pm.isInteractive();
        } catch (RuntimeException ignored) {}
        try {
            KeyguardManager km = app.getSystemService(KeyguardManager.class);
            keyguardLocked = km != null && km.isKeyguardLocked();
        } catch (RuntimeException ignored) {}
        screenUnlocked = NotificationDndPolicy.isScreenUnlocked(interactive, keyguardLocked);
    }

    private void refreshFocus() {
        focusActive = NotificationDndPolicy.isFocusOrZenSpaceActive(
                readSecureInt(KEY_BREATH_MODE),
                readGlobalInt(KEY_BREATH_MODE),
                readSecureInt(KEY_LIGHT_BREATH_MODE),
                readSecureInt(KEY_FOCUS_SWITCH),
                readSecureInt(KEY_FOCUS_SWITCH_NEW));
    }

    private void refreshGame() {
        gameForeground = NotificationDndPolicy.isGameForegroundActive(
                readGlobalInt(KEY_GAME_MODE_VALUE),
                readGlobalInt(KEY_GAME_MODE_DELAYED));
    }

    private void registerDndAndScreenReceivers() {
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!active || intent == null) return;
                    String action = intent.getAction();
                    try {
                        if (NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(action)) {
                            worker.execute(PhoneModeObserver.this::refreshDnd);
                        } else if (Intent.ACTION_SCREEN_ON.equals(action)
                                || Intent.ACTION_SCREEN_OFF.equals(action)
                                || Intent.ACTION_USER_PRESENT.equals(action)) {
                            worker.execute(PhoneModeObserver.this::refreshScreen);
                        }
                    } catch (RuntimeException ignored) {}
                }
            };
            IntentFilter filter = new IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            app.registerReceiver(r, filter, Context.RECEIVER_EXPORTED);
            receivers.add(r);
        } catch (RuntimeException ignored) {}
    }

    private void observeUri(Uri uri, Handler handler, Runnable task) {
        if (uri == null) return;
        try {
            ContentObserver obs = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange) {
                    if (!active) return;
                    try { worker.execute(task); } catch (RuntimeException ignored) {}
                }
            };
            app.getContentResolver().registerContentObserver(uri, false, obs);
            observers.add(obs);
        } catch (RuntimeException ignored) {}
    }

    private int readGlobalInt(String key) {
        try {
            return Settings.Global.getInt(app.getContentResolver(), key, 0);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private int readSecureInt(String key) {
        try {
            return Settings.Secure.getInt(app.getContentResolver(), key, 0);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
