package io.github.watchlink.xposed;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import androidx.annotation.RequiresApi;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.validation.HealthDaemonControl;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Huawei Health :DaemonService bridge for Priority 1A Notification Enhancement Suite:
 * 1) Notification Pictures: unlocks non-Huawei gate and extends support to standard BigPictureStyle
 *    and any app's MessagingStyle images via Health's native RLE RGB24 .bin pipeline.
 * 2) VoIP Call Ringing & Watch Reject: detects incoming VoIP calls via public Android CallStyle /
 *    CATEGORY_CALL APIs (bypassing Health's Android 17-broken PendingIntent.getIntent() reflection)
 *    and invokes the cached decline PendingIntent when the watch sends Reject.
 */
@RequiresApi(34)
final class HealthDaemonBridge {
    private static final String SP_NOTIFY_PICTURE = "NOTIFY_PICTURE";
    private static final String KEY_PIC_CAPABILITY = "SUPPORT_NOTIFY_PICTURE_CAPABILITY";
    private static final String KEY_PIC_WIDTH = "NOTIFY_PICTURE_WIDTH";
    private static final String KEY_PIC_NUMBER = "NOTIFY_PICTURE_NUMBER";
    private static final String SP_VOIP_TYPE = "SUPPORT_NOTIFY_VOIP_TYPE";

    private static final int MIN_IMAGE_EDGE_PX = 48;
    private static final int DEFAULT_TARGET_WIDTH_PX = 320;
    private static final int MIN_TARGET_WIDTH_PX = 120;
    private static final int MAX_TARGET_WIDTH_PX = 640;

    private static final long VOIP_RING_DEDUP_MS = 6_000L;
    private static final long VOIP_REJECT_TTL_MS = 120_000L;
    private static final int MAX_REJECT_CACHE = 32;

    private final Context app;
    private final int ownerUid;
    private final HostSettings settings;
    private final String[] sources;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "WatchLink-health-daemon");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, CachedReject> rejectCache = new ConcurrentHashMap<>();

    private BroadcastReceiver controlReceiver;
    private Method spGetBoolMethod;
    private Method spGetIntMethod;
    private Method pictureNextFileMethod;
    private Method pictureSaveBitmapMethod;
    private Field receiverOuterField;

    private volatile boolean pictureHookReady;
    private volatile boolean voipHookReady;
    private volatile String symbolSource = "DEGRADED";
    private volatile long lastPictureAt;
    private volatile String lastPictureResult = "NONE";
    private volatile long lastVoipAt;
    private volatile String lastVoipResult = "NONE";

    private static final class CachedReject {
        final PendingIntent intent;
        final long createdElapsed;
        final long lastPushedElapsed;

        CachedReject(PendingIntent intent, long createdElapsed, long lastPushedElapsed) {
            this.intent = intent;
            this.createdElapsed = createdElapsed;
            this.lastPushedElapsed = lastPushedElapsed;
        }
    }

    private record ExtractedBitmap(Bitmap bitmap, String sourceTag, String resultTag) {}

    HealthDaemonBridge(Context app, int ownerUid, HostSettings settings, String[] sources) {
        this.app = app;
        this.ownerUid = ownerUid;
        this.settings = settings;
        this.sources = sources;
    }

    void install(AlarmObserverInstaller installer) {
        HealthDaemonSymbolResolver.Symbols symbols = HealthDaemonSymbolResolver.resolve(
                new ApkSymbolCache(app, "health_daemon"), sources);
        if (symbols != null && symbols.anyReady()) {
            symbolSource = symbols.source();
            bindSharedPrefs(installer, symbols);
            pictureHookReady = symbols.pictureReady() && installPictureHook(installer, symbols);
            voipHookReady = symbols.voipReady() && installVoipHooks(installer, symbols);
        } else {
            symbolSource = "DEGRADED";
        }
        ProbeLog.info("HEALTH_DAEMON_SYMBOLS_RESOLVED source=HEALTH_DAEMON resolver=" + symbolSource);
        settings.onVoipDisabled(rejectCache::clear);
        controlReceiver = HealthDaemonControl.register(app, ownerUid, worker, this::handleControl);
    }

    boolean pictureHookReady() { return pictureHookReady; }

    boolean voipHookReady() { return voipHookReady; }

    void close() {
        settings.onVoipDisabled(null);
        rejectCache.clear();
        if (controlReceiver != null) {
            try { app.unregisterReceiver(controlReceiver); } catch (RuntimeException ignored) {}
            controlReceiver = null;
        }
        worker.shutdownNow();
    }

    private void bindSharedPrefs(AlarmObserverInstaller installer, HealthDaemonSymbolResolver.Symbols symbols) {
        try {
            Class<?> spClass = installer.target(HealthDaemonSymbolResolver.SHARED_PREF_MANAGER);
            if (!symbols.spGetBoolMethod().isEmpty()) {
                Method m = spClass.getDeclaredMethod(symbols.spGetBoolMethod(), String.class, String.class, boolean.class);
                m.setAccessible(true);
                spGetBoolMethod = m;
            }
            if (!symbols.spGetIntMethod().isEmpty()) {
                Method m = spClass.getDeclaredMethod(symbols.spGetIntMethod(), String.class, String.class, int.class);
                m.setAccessible(true);
                spGetIntMethod = m;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            spGetBoolMethod = null;
            spGetIntMethod = null;
        }
    }

    private boolean installPictureHook(AlarmObserverInstaller installer, HealthDaemonSymbolResolver.Symbols symbols) {
        if (spGetBoolMethod == null || spGetIntMethod == null) return false;
        try {
            Class<?> listenerClass = installer.target(HealthDaemonSymbolResolver.NOTIFICATION_PUSH_LISTENER);
            Class<?> helperClass = installer.target(symbols.pictureHelperClass());

            Method styleHook = listenerClass.getDeclaredMethod(symbols.pictureStyleMethod(),
                    Bundle.class, String.class, Notification.class, String.class);
            Method nextFile = helperClass.getDeclaredMethod(symbols.pictureNextFileMethod());
            Method saveBitmap = helperClass.getDeclaredMethod(symbols.pictureSaveBitmapMethod(),
                    Context.class, Bitmap.class, String.class, Bundle.class);
            nextFile.setAccessible(true);
            saveBitmap.setAccessible(true);
            pictureNextFileMethod = nextFile;
            pictureSaveBitmapMethod = saveBitmap;

            installer.observe("health.daemon.notif.picture", styleHook, (chain, result) -> {
                if (!settings.isNotifPictureEnabled()) return;
                Bundle targetBundle = (Bundle) chain.getArg(0);
                String pkg = (String) chain.getArg(1);
                Notification notification = (Notification) chain.getArg(2);
                if (targetBundle == null || notification == null) return;
                if (!TextUtils.isEmpty(targetBundle.getString("imageName"))) return;
                handleNotificationPicture(targetBundle, pkg, notification);
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            pictureNextFileMethod = null;
            pictureSaveBitmapMethod = null;
            return false;
        }
    }

    private boolean installVoipHooks(AlarmObserverInstaller installer, HealthDaemonSymbolResolver.Symbols symbols) {
        if (spGetBoolMethod == null) return false;
        try {
            Class<?> listenerClass = installer.target(HealthDaemonSymbolResolver.NOTIFICATION_PUSH_LISTENER);
            Class<?> receiverClass = installer.target(symbols.voipReceiverClass());

            Method voipGate = listenerClass.getDeclaredMethod(symbols.voipGateMethod(),
                    Bundle.class, Notification.class, boolean.class);
            Method rejectIntentMethod = receiverClass.getDeclaredMethod(symbols.voipRejectIntentMethod(), Intent.class);

            for (Field field : receiverClass.getDeclaredFields()) {
                if (NotificationListenerService.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    receiverOuterField = field;
                    break;
                }
            }

            installer.guard("health.daemon.voip.gate", voipGate, chain -> {
                if (!settings.isVoipCallEnabled()) return chain.proceed();
                Bundle bundle = (Bundle) chain.getArg(0);
                Notification notification = (Notification) chain.getArg(1);
                if (bundle == null || notification == null) return chain.proceed();
                String pkg = bundle.getString("data_package_name");
                if (!VoipCallClassifier.isEligibleVoipPackage(pkg)) return chain.proceed();
                if (!readVoipSupported()) return chain.proceed();

                Boolean decision = handleVoipCall(bundle, notification);
                return decision != null ? decision : chain.proceed();
            });

            installer.guard("health.daemon.voip.reject", rejectIntentMethod, chain -> {
                if (!settings.isVoipCallEnabled()) return chain.proceed();
                Intent intent = (Intent) chain.getArg(0);
                if (intent == null || !VoipCallClassifier.REJECT_TOKEN.equals(intent.getStringExtra("key_replay_content"))) {
                    return chain.proceed();
                }
                handleVoipReject(chain.getThisObject(), intent.getStringExtra("key_replay_destination"));
                return null;
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private void handleNotificationPicture(Bundle targetBundle, String pkg, Notification notification) {
        if ((notification.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY)) != 0) return;
        Bundle extras = notification.extras;
        if (extras == null) return;
        Uri messagingUri = extractMessagingImageUri(extras);
        Icon pictureIcon = messagingUri == null ? extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon.class) : null;
        @SuppressWarnings("deprecation")
        Bitmap legacyPicture = (messagingUri == null && pictureIcon == null)
                ? extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap.class) : null;
        if (messagingUri == null && pictureIcon == null && legacyPicture == null) {
            return;
        }

        if (!readPictureSupported()) {
            lastPictureAt = System.currentTimeMillis();
            lastPictureResult = "SKIPPED_UNSUPPORTED";
            ProbeLog.info("HEALTH_NOTIF_PIC_SKIPPED reason=UNSUPPORTED");
            return;
        }

        int watchWidth = readPictureWidth();
        int targetMax = (watchWidth >= MIN_TARGET_WIDTH_PX && watchWidth <= MAX_TARGET_WIDTH_PX)
                ? watchWidth : DEFAULT_TARGET_WIDTH_PX;

        ExtractedBitmap extracted = null;
        try {
            if (messagingUri != null) {
                Bitmap bmp = decodeScaledUri(pkg, messagingUri, targetMax);
                if (bmp != null) {
                    extracted = new ExtractedBitmap(bmp, "MESSAGING_URI", "ENCODED_MESSAGING");
                }
            }
            if (extracted == null && pictureIcon != null) {
                Bitmap bmp = decodeScaledIcon(pictureIcon, targetMax);
                if (bmp != null) {
                    extracted = new ExtractedBitmap(bmp, "BIG_PICTURE_ICON", "ENCODED_BIG_PICTURE");
                }
            }
            if (extracted == null && legacyPicture != null) {
                Bitmap bmp = toSoftwareScaledCopy(legacyPicture, targetMax);
                if (bmp != null) {
                    extracted = new ExtractedBitmap(bmp, "BIG_PICTURE", "ENCODED_BIG_PICTURE");
                }
            }
            if (extracted == null) {
                ProbeLog.info("HEALTH_NOTIF_PIC_SKIPPED reason=DECODE_FAILED");
                return;
            }

            String fileName = (String) pictureNextFileMethod.invoke(null);
            if (TextUtils.isEmpty(fileName)) {
                recycleQuietly(extracted.bitmap());
                lastPictureAt = System.currentTimeMillis();
                lastPictureResult = "FAILED";
                ProbeLog.info("HEALTH_NOTIF_PIC_FAILED reason=DECODE_FAILED");
                return;
            }
            pictureSaveBitmapMethod.invoke(null, app, extracted.bitmap(), fileName, targetBundle);
            if (fileName.equals(targetBundle.getString("imageName"))) {
                lastPictureAt = System.currentTimeMillis();
                lastPictureResult = extracted.resultTag();
                ProbeLog.info("HEALTH_NOTIF_PIC_ENCODED pic_source=" + extracted.sourceTag() + " result=VERIFIED");
            } else {
                lastPictureAt = System.currentTimeMillis();
                lastPictureResult = "FAILED";
                ProbeLog.info("HEALTH_NOTIF_PIC_FAILED reason=DECODE_FAILED");
            }
        } catch (OutOfMemoryError oom) {
            if (extracted != null) recycleQuietly(extracted.bitmap());
            lastPictureAt = System.currentTimeMillis();
            lastPictureResult = "FAILED";
            ProbeLog.info("HEALTH_NOTIF_PIC_FAILED type=OutOfMemoryError");
        } catch (Exception | LinkageError failure) {
            if (extracted != null) recycleQuietly(extracted.bitmap());
            lastPictureAt = System.currentTimeMillis();
            lastPictureResult = "FAILED";
            ProbeLog.failure("HEALTH_NOTIF_PIC_FAILED", failure);
        }
    }

    private Uri extractMessagingImageUri(Bundle extras) {
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class);
        if (messages == null || messages.length == 0) return null;
        Parcelable last = messages[messages.length - 1];
        if (!(last instanceof Bundle msg)) return null;
        String mime = msg.getString("type");
        if (mime == null || !mime.startsWith("image/")) return null;
        return msg.getParcelable("uri", Uri.class);
    }

    private Bitmap decodeScaledUri(String pkg, Uri uri, int targetMax) {
        if (pkg != null && !pkg.isEmpty()) {
            try { app.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (RuntimeException ignored) { /* Listener already holds NMS grant in standard cases. */ }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        if (bounds.outWidth < MIN_IMAGE_EDGE_PX || bounds.outHeight < MIN_IMAGE_EDGE_PX) {
            return null;
        }
        int sample = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / (sample * 2) >= targetMax) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inMutable = false;
        Bitmap decoded = null;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            decoded = BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        if (decoded == null) return null;
        try {
            return toSoftwareScaledCopy(decoded, targetMax);
        } finally {
            recycleQuietly(decoded);
        }
    }

    private Bitmap decodeScaledIcon(Icon icon, int targetMax) {
        Drawable drawable;
        try {
            drawable = icon.loadDrawable(app);
        } catch (RuntimeException | OutOfMemoryError failed) {
            return null;
        }
        if (drawable == null) return null;
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        if (w < MIN_IMAGE_EDGE_PX || h < MIN_IMAGE_EDGE_PX) return null;
        int[] dst = scaledDimensions(w, h, targetMax);
        Bitmap out = Bitmap.createBitmap(dst[0], dst[1], Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        drawable.setBounds(0, 0, dst[0], dst[1]);
        drawable.draw(canvas);
        return out;
    }

    /** Produces a fresh ARGB_8888 software bitmap scaled to targetMax without mutating or recycling src. */
    private Bitmap toSoftwareScaledCopy(Bitmap src, int targetMax) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < MIN_IMAGE_EDGE_PX || h < MIN_IMAGE_EDGE_PX) return null;
        Bitmap software = src;
        boolean ownsSoftware = false;
        if (src.getConfig() == Bitmap.Config.HARDWARE || src.getConfig() != Bitmap.Config.ARGB_8888) {
            software = src.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) return null;
            ownsSoftware = true;
        }
        try {
            int[] dst = scaledDimensions(w, h, targetMax);
            if (dst[0] == w && dst[1] == h) {
                if (ownsSoftware) {
                    ownsSoftware = false;
                    return software;
                }
                return software.copy(Bitmap.Config.ARGB_8888, false);
            }
            Bitmap scaled = Bitmap.createScaledBitmap(software, dst[0], dst[1], true);
            if (scaled == software) {
                if (ownsSoftware) {
                    ownsSoftware = false;
                    return software;
                }
                return software.copy(Bitmap.Config.ARGB_8888, false);
            }
            return scaled;
        } finally {
            if (ownsSoftware) recycleQuietly(software);
        }
    }

    static int[] scaledDimensions(int width, int height, int maxEdge) {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return new int[]{width, height};
        if (width <= maxEdge && height <= maxEdge) return new int[]{width, height};
        if (width >= height) {
            int scaledH = Math.max(1, Math.round((float) height * maxEdge / width));
            return new int[]{maxEdge, scaledH};
        }
        int scaledW = Math.max(1, Math.round((float) width * maxEdge / height));
        return new int[]{scaledW, maxEdge};
    }

    private Boolean handleVoipCall(Bundle bundle, Notification notification) {
        VoipCallClassifier.Decision decision = VoipCallClassifier.classify(notification);
        if (decision.kind() == VoipCallClassifier.Kind.NONE) return null;

        String sbnKey = bundle.getString("data_extra_noty_key");
        if (decision.kind() == VoipCallClassifier.Kind.ONGOING) {
            if (sbnKey != null) rejectCache.remove(sbnKey);
            bundle.putInt("voip_type", 2);
            lastVoipAt = System.currentTimeMillis();
            lastVoipResult = "ONGOING_SUPPRESSED";
            ProbeLog.info("HEALTH_VOIP_ONGOING_SUPPRESSED");
            return Boolean.FALSE;
        }

        if (!decision.isIncoming()) return null;
        long nowElapsed = SystemClock.elapsedRealtime();
        pruneRejectCache(nowElapsed);

        if (sbnKey != null && !sbnKey.isEmpty()) {
            CachedReject previous = rejectCache.get(sbnKey);
            if (previous != null && nowElapsed - previous.lastPushedElapsed < VOIP_RING_DEDUP_MS) {
                // Keep reject PendingIntent fresh while suppressing repetitive ringing updates.
                rejectCache.put(sbnKey, new CachedReject(decision.rejectIntent(), previous.createdElapsed, previous.lastPushedElapsed));
                return Boolean.FALSE;
            }
            rejectCache.put(sbnKey, new CachedReject(decision.rejectIntent(), nowElapsed, nowElapsed));
        }

        ensureCallerTitle(bundle, notification);
        bundle.putInt("voip_type", 1);
        bundle.putInt("incoming_type", 8);
        bundle.putString("reject_button_key", VoipCallClassifier.REJECT_TOKEN);

        boolean callStyle = decision.kind() == VoipCallClassifier.Kind.INCOMING_CALL_STYLE;
        lastVoipAt = System.currentTimeMillis();
        lastVoipResult = callStyle ? "DETECTED_CALL_STYLE" : "DETECTED_CATEGORY_CALL";
        ProbeLog.info("HEALTH_VOIP_CALL_DETECTED voip_source=" + (callStyle ? "CALL_STYLE" : "CATEGORY_CALL"));
        return Boolean.TRUE;
    }

    private void ensureCallerTitle(Bundle bundle, Notification notification) {
        if (!TextUtils.isEmpty(bundle.getString("data_extra_title"))) return;
        Bundle extras = notification.extras;
        if (extras == null) return;
        Person caller = extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person.class);
        if (caller != null && !TextUtils.isEmpty(caller.getName())) {
            bundle.putString("data_extra_title", caller.getName().toString());
        }
    }

    private void handleVoipReject(Object receiverInstance, String sbnKey) {
        long nowElapsed = SystemClock.elapsedRealtime();
        pruneRejectCache(nowElapsed);
        PendingIntent rejectIntent = null;
        if (sbnKey != null && !sbnKey.isEmpty()) {
            CachedReject cached = rejectCache.remove(sbnKey);
            if (cached != null && nowElapsed - cached.createdElapsed <= VOIP_REJECT_TTL_MS) {
                rejectIntent = cached.intent;
            }
        }
        if (rejectIntent == null && sbnKey != null && !sbnKey.isEmpty()) {
            rejectIntent = findActiveRejectIntent(receiverInstance, sbnKey);
        }
        if (rejectIntent == null) {
            lastVoipAt = System.currentTimeMillis();
            lastVoipResult = "REJECT_FAILED";
            ProbeLog.info("HEALTH_VOIP_REJECT_FAILED reason=MISSING_INTENT");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34 && rejectIntent.isActivity()) {
                Bundle opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle();
                rejectIntent.send(app, 0, null, null, null, null, opts);
            } else {
                rejectIntent.send();
            }
            lastVoipAt = System.currentTimeMillis();
            lastVoipResult = "REJECTED_BY_WATCH";
            ProbeLog.info("HEALTH_VOIP_REJECT_SENT result=VERIFIED");
        } catch (PendingIntent.CanceledException canceled) {
            lastVoipAt = System.currentTimeMillis();
            lastVoipResult = "REJECT_FAILED";
            ProbeLog.info("HEALTH_VOIP_REJECT_FAILED type=CanceledException");
        } catch (RuntimeException failure) {
            lastVoipAt = System.currentTimeMillis();
            lastVoipResult = "REJECT_FAILED";
            ProbeLog.failure("HEALTH_VOIP_REJECT_FAILED", failure);
        }
    }

    private PendingIntent findActiveRejectIntent(Object receiverInstance, String sbnKey) {
        if (receiverInstance == null || receiverOuterField == null) return null;
        try {
            Object outer = receiverOuterField.get(receiverInstance);
            if (!(outer instanceof NotificationListenerService listener)) return null;
            StatusBarNotification[] active = listener.getActiveNotifications();
            if (active == null) return null;
            for (StatusBarNotification sbn : active) {
                if (sbn != null && sbnKey.equals(sbn.getKey())) {
                    VoipCallClassifier.Decision decision = VoipCallClassifier.classify(sbn.getNotification());
                    if (decision.isIncoming()) return decision.rejectIntent();
                    return null;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        return null;
    }

    private void pruneRejectCache(long nowElapsed) {
        Iterator<Map.Entry<String, CachedReject>> it = rejectCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CachedReject> entry = it.next();
            if (nowElapsed - entry.getValue().createdElapsed > VOIP_REJECT_TTL_MS) {
                it.remove();
            }
        }
        if (rejectCache.size() > MAX_REJECT_CACHE) {
            rejectCache.clear();
        }
    }

    private boolean readPictureSupported() {
        return readSpBool(SP_NOTIFY_PICTURE, KEY_PIC_CAPABILITY);
    }

    private int readPictureWidth() {
        return readSpInt(SP_NOTIFY_PICTURE, KEY_PIC_WIDTH);
    }

    private int readPictureNumber() {
        return readSpInt(SP_NOTIFY_PICTURE, KEY_PIC_NUMBER);
    }

    private boolean readVoipSupported() {
        return readSpBool(SP_VOIP_TYPE, SP_VOIP_TYPE);
    }

    private boolean readSpBool(String module, String key) {
        Method getter = spGetBoolMethod;
        if (getter == null) return false;
        try {
            Object res = getter.invoke(null, module, key, false);
            return Boolean.TRUE.equals(res);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private int readSpInt(String module, String key) {
        Method getter = spGetIntMethod;
        if (getter == null) return 0;
        try {
            Object res = getter.invoke(null, module, key, 0);
            return res instanceof Integer i ? i : 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return 0;
        }
    }

    private HealthDaemonControl.Reply handleControl(String command) {
        switch (command) {
            case HealthDaemonControl.ENABLE_PICTURE -> settings.setLocalNotifPictureFallback(true);
            case HealthDaemonControl.DISABLE_PICTURE -> settings.setLocalNotifPictureFallback(false);
            case HealthDaemonControl.ENABLE_VOIP -> settings.setLocalVoipCallFallback(true);
            case HealthDaemonControl.DISABLE_VOIP -> {
                settings.setLocalVoipCallFallback(false);
                rejectCache.clear();
            }
            default -> {}
        }
        return new HealthDaemonControl.Reply(
                true,
                settings.isNotifPictureEnabled(),
                settings.isVoipCallEnabled(),
                settings.remoteAvailable(),
                pictureHookReady,
                voipHookReady,
                symbolSource,
                readPictureSupported(),
                readPictureWidth(),
                readPictureNumber(),
                readVoipSupported(),
                lastPictureAt,
                lastPictureResult,
                lastVoipAt,
                lastVoipResult);
    }

    private static void recycleQuietly(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            try { bitmap.recycle(); } catch (RuntimeException ignored) {}
        }
    }
}
