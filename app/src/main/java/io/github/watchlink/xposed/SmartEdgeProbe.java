package io.github.watchlink.xposed;

import android.app.BackgroundServiceStartNotAllowedException;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.validation.ClockActionWire;
import io.github.watchlink.validation.DailyAlarmWire;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONObject;

/**
 * Temporary BuildConfig.DEBUG-only probe for Step 1 verification of WATCH 6 Pro SmartEdge capability
 * (ExpandCapability bit 84 / 219 / 176) and live window rendering via HandleIntentService / HwNotificationLiveMgr.
 */
public final class SmartEdgeProbe {
    private static final String TAG = "WatchLink";
    private static final String WIRE_ACTION = BuildConfig.APPLICATION_ID + ".debug.SMARTEDGE_PROBE_V1";
    private static final String HANDLE_INTENT_SERVICE = "com.huawei.hwdevice.phoneprocess.service.HandleIntentService";
    private static final String ACTION_NOTIFICATION_PUSH = "com.huawei.bone.ACTION_NOTIFICATION_PUSH";
    private static final String EXTRA_LIVE_TYPE = "LIVE_NOTIFICATION_PUSH_TYPE";
    private static final String EXTRA_LIVE_TYPE_VAL = "LIVE_NOTIFICATION_PUSH";
    private static final String EXTRA_LIVE_JSON = "LIVE_NOTIFICATION_PUSH_JSON_STRING";
    private static final String DATA_RECEIVE_CALLBACK = "com.huawei.health.devicemgr.business.callback.DataReceiveCallback";
    private static final long TIMEOUT_MS = 5_000L;
    private static final long OBSERVE_WINDOW_MS = 6_000L;
    private static final long SAFETY_AUTO_CLOSE_MS = 120_000L;
    private static final int ACK = 1;

    private final Context app;
    private final int ownerUid;
    private final ScheduledExecutorService worker;
    private final HealthCooperativeReadiness readiness;

    private BroadcastReceiver receiver;
    private Method ocaSingleton;
    private Method ocaSendJson;
    private boolean handleIntentReady;
    private volatile boolean installed;
    private volatile long observeUntilElapsed;
    private ScheduledFuture<?> safetyCloseFuture;
    private String lastActivePkg = BuildConfig.APPLICATION_ID;
    private boolean lastActiveDelivery = true;

    @RequiresApi(34)
    SmartEdgeProbe(Context app, int ownerUid, ScheduledExecutorService worker, HealthCooperativeReadiness readiness) {
        this.app = app;
        this.ownerUid = ownerUid;
        this.worker = worker;
        this.readiness = readiness;
    }

    @RequiresApi(34)
    void install(AlarmObserverInstaller installer) {
        if (!BuildConfig.DEBUG || ownerUid < 0) return;
        handleIntentReady = checkHandleIntentService();
        resolveLiveMgr(installer);
        registerHostReceiver();
        installed = true;
        Log.i(TAG, "SMARTEDGE_PROBE_INSTALLED handleIntentReady=" + handleIntentReady
                + " ocaReady=" + (ocaSingleton != null && ocaSendJson != null));
    }

    void close() {
        installed = false;
        cancelSafetyClose();
        if (receiver != null) {
            try { app.unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
            receiver = null;
        }
    }

    boolean isObserving() {
        return installed && SystemClock.elapsedRealtime() <= observeUntilElapsed;
    }

    void observeSdkFrame(boolean received, byte[] frame) {
        if (!isObserving() || frame == null || frame.length < 2) return;
        int svc = frame[0] & 0xFF;
        int cmd = frame[1] & 0xFF;
        // Log only protocol headers (serviceId/commandId/length); never log payload bytes.
        Log.i(TAG, "SMARTEDGE_PROBE_SDK_FRAME dir=" + (received ? "IN" : "OUT")
                + " svc=" + svc + " cmd=" + cmd + " len=" + frame.length);
    }

    private boolean checkHandleIntentService() {
        try {
            ComponentName cn = new ComponentName(TargetProfile.HEALTH.packageName, HANDLE_INTENT_SERVICE);
            ServiceInfo info = app.getPackageManager().getServiceInfo(cn, 0);
            return info != null && !info.exported && TargetProfile.HEALTH.isReminderProcess(info.processName);
        } catch (Exception unavailable) {
            return false;
        }
    }

    @RequiresApi(34)
    private void resolveLiveMgr(AlarmObserverInstaller installer) {
        try {
            Class<?> cb = installer.target(DATA_RECEIVE_CALLBACK);
            Class<?> oca = installer.target("oca");
            if (!cb.isAssignableFrom(oca)) return;
            Method singleton = null;
            Method send = null;
            Method onRecv = null;
            for (Method m : oca.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (!Modifier.isPublic(mod) || m.isSynthetic()) continue;
                if (Modifier.isStatic(mod) && m.getReturnType() == oca && m.getParameterCount() == 0) {
                    singleton = m;
                } else if (!Modifier.isStatic(mod) && m.getReturnType() == void.class
                        && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    send = m;
                } else if (!Modifier.isStatic(mod) && "onDataReceived".equals(m.getName())
                        && m.getParameterCount() == 3) {
                    onRecv = m;
                }
            }
            if (singleton == null || send == null) return;
            ocaSingleton = singleton;
            ocaSendJson = send;
            installer.observeAround("health.smartedge.probe.send", send, chain -> {
                String json = (String) chain.getArg(0);
                Log.i(TAG, "SMARTEDGE_PROBE_OCA_ENTER jsonLen=" + (json == null ? -1 : json.length()));
                return null;
            }, (chain, token, result) -> Log.i(TAG, "SMARTEDGE_PROBE_OCA_EXIT"));
            if (onRecv != null) {
                installer.observeAround("health.smartedge.probe.recv", onRecv, chain -> {
                    Object status = chain.getArg(0);
                    Log.i(TAG, "SMARTEDGE_PROBE_OCA_RECV status=" + status);
                    return null;
                }, (chain, token, result) -> {});
            }
        } catch (Throwable ignored) {
            ocaSingleton = null;
            ocaSendJson = null;
        }
    }

    @RequiresApi(34)
    private void registerHostReceiver() {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!installed || !isOrderedBroadcast() || getSentFromUid() != ownerUid
                        || !BuildConfig.APPLICATION_ID.equals(getSentFromPackage())
                        || !WIRE_ACTION.equals(intent.getAction()) || intent.getComponent() != null
                        || !TargetProfile.HEALTH.packageName.equals(intent.getPackage())
                        || (intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                    return;
                }
                String reqId = intent.getStringExtra("request");
                String action = intent.getStringExtra("action");
                String pkg = intent.getStringExtra("pkg");
                String event = intent.getStringExtra("event");
                int liveType = intent.getIntExtra("liveType", -1);
                String featMode = intent.getStringExtra("feat");
                long wall = intent.getLongExtra("at", 0L);
                long elapsed = intent.getLongExtra("elapsed", -1L);
                if (!DailyAlarmWire.isRun(reqId) || action == null || action.isEmpty()) return;
                PendingResult pending = goAsync();
                try {
                    worker.execute(() -> {
                        try {
                            if (!installed || !DailyAlarmWire.inWindow(wall, elapsed, TIMEOUT_MS)) return;
                            String summary = handleAction(action, pkg, event, liveType, featMode);
                            Bundle b = new Bundle();
                            b.putString("request", reqId);
                            b.putString("summary", summary);
                            pending.setResult(ACK, null, b);
                        } catch (Throwable err) {
                            Log.w(TAG, "SMARTEDGE_PROBE_ACTION_FAILED type=" + err.getClass().getSimpleName());
                        } finally {
                            pending.finish();
                        }
                    });
                } catch (RuntimeException rejected) {
                    pending.finish();
                }
            }
        };
        app.registerReceiver(r, new IntentFilter(WIRE_ACTION), ClockActionWire.PERMISSION, null, Context.RECEIVER_EXPORTED);
        receiver = r;
    }

    private String handleAction(String action, String pkgOverride, String eventOverride, int liveTypeOverride, String featMode) {
        String pkg = (pkgOverride != null && pkgOverride.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
                ? pkgOverride : BuildConfig.APPLICATION_ID;
        return switch (action) {
            case "inspect" -> runInspect();
            case "open_delivery" -> runSend(pkg, true, 0, 35, false, eventOverride, liveTypeOverride, featMode);
            case "open_delivery_direct" -> runSend(pkg, true, 0, 35, true, eventOverride, liveTypeOverride, featMode);
            case "update_delivery" -> runSend(pkg, true, 1, 80, false, eventOverride, liveTypeOverride, featMode);
            case "close_delivery" -> runSend(pkg, true, 2, 100, false, eventOverride, liveTypeOverride, featMode);
            case "open_other" -> runSend(pkg, false, 0, -1, false, eventOverride, liveTypeOverride, featMode);
            case "update_other" -> runSend(pkg, false, 1, -1, false, eventOverride, liveTypeOverride, featMode);
            case "close_other" -> runSend(pkg, false, 2, -1, false, eventOverride, liveTypeOverride, featMode);
            default -> "UNKNOWN_ACTION:" + action;
        };
    }

    private String runInspect() {
        String hex = readiness == null ? null : readiness.readRawExpandCapability();
        boolean connected = hex != null && !hex.isEmpty();
        int hexLen = hex == null ? 0 : hex.replace(" ", "").length();
        Boolean bit84 = capabilityBit(hex, 84);
        Boolean bit176 = capabilityBit(hex, 176);
        Boolean bit200 = capabilityBit(hex, 200);
        Boolean bit219 = capabilityBit(hex, 219);
        Boolean bit241 = capabilityBit(hex, 241);
        String spInfo = readLiveSpState();
        String summary = "connected=" + connected
                + " hexLen=" + hexLen
                + " bit84=" + bit84
                + " bit219=" + bit219
                + " bit176=" + bit176
                + " bit200=" + bit200
                + " bit241=" + bit241
                + " handleIntentReady=" + handleIntentReady
                + " ocaReady=" + (ocaSingleton != null && ocaSendJson != null)
                + " " + spInfo;
        Log.i(TAG, "SMARTEDGE_PROBE_INSPECT " + summary);
        return summary;
    }

    private String readLiveSpState() {
        try {
            ClassLoader cl = app.getClassLoader();
            Class<?> spm = Class.forName("health.compact.a.SharedPreferenceManager", false, cl);
            Method getBool = spm.getDeclaredMethod("d", String.class, String.class, boolean.class);
            Method getStr = spm.getDeclaredMethod("b", String.class, String.class, String.class);
            Object cap = getBool.invoke(null, "SUPPORT_NOTIFY_LIVE_LEVEL_CAPABILITY",
                    "SUPPORT_NOTIFY_LIVE_LEVEL_CAPABILITY", false);
            Object lvl = getStr.invoke(null, "NOTIFY_LIVE_LEVEL_VALUE", "NOTIFY_LIVE_LEVEL_VALUE", "");
            String lvlStr = lvl instanceof String s && s.matches("[A-Za-z0-9_.-]{0,32}") ? s : "UNSET";
            return "spLiveCap=" + cap + " spLiveLevel=" + (lvlStr.isEmpty() ? "EMPTY" : lvlStr);
        } catch (Throwable ignored) {
            return "spLiveCap=ERR spLiveLevel=ERR";
        }
    }

    private String runSend(String pkg, boolean delivery, int operation, int progress, boolean forceDirect,
                           String eventOverride, int liveTypeOverride, String featMode) {
        observeUntilElapsed = SystemClock.elapsedRealtime() + OBSERVE_WINDOW_MS;
        String event = (eventOverride != null && !eventOverride.isEmpty())
                ? eventOverride : (delivery ? "DELIVERY" : "OTHER");
        int liveType = liveTypeOverride >= 0 ? liveTypeOverride : (delivery && progress >= 0 ? 3 : 1);
        String resolvedFeat = (featMode != null && !featMode.isEmpty())
                ? featMode : (delivery && progress >= 0 ? "progress" : "empty");
        String json;
        try {
            json = buildTestJson(pkg, delivery, operation, progress, event, liveType, resolvedFeat);
        } catch (Exception e) {
            return "JSON_ERROR:" + e.getClass().getSimpleName();
        }
        String dispatchMode;
        if (forceDirect) {
            dispatchMode = sendDirectOca(json);
        } else {
            dispatchMode = sendViaHandleIntentService(json);
            if (!"START_SERVICE".equals(dispatchMode)) {
                String fallback = sendDirectOca(json);
                dispatchMode = dispatchMode + "->" + fallback;
            }
        }
        if (operation == 2) {
            cancelSafetyClose();
        } else {
            lastActivePkg = pkg;
            lastActiveDelivery = delivery;
            scheduleSafetyClose(event, liveType, resolvedFeat);
        }
        String result = "op=" + operation + " event=" + event + " type=" + liveType + " feat=" + resolvedFeat
                + " progress=" + progress + " pkg=" + pkg + " jsonLen=" + json.length() + " mode=" + dispatchMode;
        Log.i(TAG, "SMARTEDGE_PROBE_SENT " + result);
        return result;
    }

    private String sendViaHandleIntentService(String json) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(TargetProfile.HEALTH.packageName, HANDLE_INTENT_SERVICE));
            intent.setAction(ACTION_NOTIFICATION_PUSH);
            intent.putExtra(EXTRA_LIVE_TYPE, EXTRA_LIVE_TYPE_VAL);
            intent.putExtra(EXTRA_LIVE_JSON, json);
            ComponentName started = app.startService(intent);
            return started != null ? "START_SERVICE" : "START_SERVICE_NULL";
        } catch (RuntimeException err) {
            if (err instanceof BackgroundServiceStartNotAllowedException) return "BG_RESTRICTED";
            return "START_ERR_" + err.getClass().getSimpleName();
        }
    }

    private String sendDirectOca(String json) {
        if (ocaSingleton == null || ocaSendJson == null) return "OCA_UNAVAILABLE";
        try {
            Object mgr = ocaSingleton.invoke(null);
            if (mgr == null) return "OCA_NULL";
            ocaSendJson.invoke(mgr, json);
            return "DIRECT_OCA";
        } catch (Throwable err) {
            return "OCA_ERR_" + err.getClass().getSimpleName();
        }
    }

    private void scheduleSafetyClose(String event, int liveType, String featMode) {
        cancelSafetyClose();
        final String pkg = lastActivePkg;
        final boolean delivery = lastActiveDelivery;
        safetyCloseFuture = worker.schedule(() -> {
            if (!installed) return;
            Log.i(TAG, "SMARTEDGE_PROBE_SAFETY_AUTO_CLOSE");
            runSend(pkg, delivery, 2, delivery ? 100 : -1, false, event, liveType, featMode);
        }, SAFETY_AUTO_CLOSE_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelSafetyClose() {
        ScheduledFuture<?> f = safetyCloseFuture;
        safetyCloseFuture = null;
        if (f != null) f.cancel(false);
    }

    private static String buildTestJson(String pkg, boolean delivery, int operation, int progress,
                                        String event, int liveType, String featMode) throws Exception {
        String key = "0|" + pkg + "|" + (delivery ? 4178 : 4179) + "|watchlink-smartedge-test|0";
        int nativeId = stableNotificationId(key);
        String title = delivery ? "WatchLink 实况测试" : ("WatchLink " + event + " 测试");
        String text = delivery
                ? (progress >= 80 ? "模拟骑手即将到达，预计2分钟（测试）" : "模拟骑手正在配送，预计10分钟（测试）")
                : (operation == 1 ? "无进度实况已更新（" + event + "）" : "无进度实况测试（" + event + "/t" + liveType + "/" + featMode + "）");
        String capsuleTitle = delivery ? "模拟配送" : (event + "测试");
        String capsuleContent = delivery
                ? (progress >= 80 ? "预计2分钟" : "预计10分钟")
                : (operation == 1 ? "已更新" : "取餐码 A08");

        JSONObject feature = null;
        if ("progress".equals(featMode) && progress >= 0) {
            feature = new JSONObject();
            feature.put("notification.live.feature.progressType", 0);
            feature.put("notification.live.feature.indicatorType", 1);
            feature.put("notification.live.feature.progress", progress);
            feature.put("notification.live.feature.progressColor", 0xFF317AF7);
            feature.put("notification.live.feature.progressBgColor", 0x19000000);
        } else if ("text".equals(featMode)) {
            feature = new JSONObject();
            feature.put("notification.live.feature.titleText", capsuleTitle);
            feature.put("notification.live.feature.contentText", capsuleContent);
        } else if ("empty".equals(featMode)) {
            feature = new JSONObject();
        }
        // "omit" leaves feature == null so key is omitted just like Lcrt.d when liveFeature == null.

        JSONObject capsule = new JSONObject();
        capsule.put("notification.live.capsuleStatus", operation == 2 ? 0 : 1);
        capsule.put("notification.live.capsuleType", 1);
        capsule.put("notification.live.capsuleTitle", capsuleTitle);
        capsule.put("notification.live.capsuleContent", capsuleContent);
        capsule.put("notification.live.capsuleRemind", operation == 0);

        JSONObject root = new JSONObject();
        root.put("packageName", pkg);
        root.put("notificationId", nativeId);
        root.put("notificationKey", key);
        root.put("title", title);
        root.put("text", text);
        root.put("notification.live.operation", operation);
        root.put("notification.live.keepTime", 0L);
        root.put("notification.live.type", liveType);
        root.put("notification.live.event", event);
        root.put("notification.live.titleOverlay", title);
        root.put("notification.live.contentOverlay", text);
        root.put("notification.live.externalEnable", false);
        root.put("notification.live.externalTitle", "");
        root.put("notification.live.externalBody", "");
        if (feature != null) {
            root.put("notification.live.feature", feature);
        }
        root.put("notification.live.capsule", capsule);
        return root.toString();
    }

    static int stableNotificationId(String key) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        return 0x40000000
                | ((hash[0] & 0x3F) << 24)
                | ((hash[1] & 0xFF) << 16)
                | ((hash[2] & 0xFF) << 8)
                | (hash[3] & 0xFF);
    }

    static Boolean capabilityBit(String expandedHex, int bitIndex) {
        if (bitIndex < 0 || expandedHex == null || expandedHex.isEmpty() || expandedHex.length() > 65_536) {
            return null;
        }
        String hex = expandedHex.replace(" ", "");
        if (hex.isEmpty() || (hex.length() & 1) != 0 || !hex.matches("[a-fA-F0-9]+")) return null;
        int charPos = (bitIndex / 8) * 2;
        if (hex.length() < charPos + 2) return false;
        int value = (Character.digit(hex.charAt(charPos), 16) << 4)
                | Character.digit(hex.charAt(charPos + 1), 16);
        return (value & (1 << (bitIndex % 8))) != 0;
    }

    /** Owner UI side (BuildConfig.DEBUG only): sends authenticated command to Health :PhoneService. */
    public static void sendCommand(Context owner, String action, String pkgOverride,
                                   String eventOverride, int liveTypeOverride, String featMode,
                                   Consumer<String> callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 34
                || action == null || action.isEmpty()
                || !BuildConfig.APPLICATION_ID.equals(owner.getPackageName())
                || owner.getApplicationInfo().uid != Process.myUid()
                || !SenderVerifier.trustedReminderTarget(owner, TargetProfile.HEALTH)) {
            main.post(() -> callback.accept("UNAVAILABLE"));
            return;
        }
        sendApi34(owner, action, pkgOverride, eventOverride, liveTypeOverride, featMode, main, callback);
    }

    @RequiresApi(34)
    private static void sendApi34(Context owner, String action, String pkgOverride,
                                  String eventOverride, int liveTypeOverride, String featMode,
                                  Handler main, Consumer<String> callback) {
        AtomicBoolean finished = new AtomicBoolean();
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) callback.accept("TIMEOUT");
        };
        String id = UUID.randomUUID().toString();
        Intent req = DailyAlarmWire.route(WIRE_ACTION, TargetProfile.HEALTH.packageName)
                .putExtra("request", id)
                .putExtra("action", action)
                .putExtra("at", System.currentTimeMillis())
                .putExtra("elapsed", SystemClock.elapsedRealtime());
        if (pkgOverride != null && !pkgOverride.isEmpty()) req.putExtra("pkg", pkgOverride);
        if (eventOverride != null && !eventOverride.isEmpty()) req.putExtra("event", eventOverride);
        if (liveTypeOverride >= 0) req.putExtra("liveType", liveTypeOverride);
        if (featMode != null && !featMode.isEmpty()) req.putExtra("feat", featMode);
        main.postDelayed(timeout, TIMEOUT_MS);
        try {
            owner.sendOrderedBroadcast(req, null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent intent) {
                            if (!finished.compareAndSet(false, true)) return;
                            main.removeCallbacks(timeout);
                            Bundle data = getResultExtras(false);
                            if (getResultCode() == ACK && data != null && id.equals(data.getString("request"))) {
                                callback.accept(data.getString("summary", "OK"));
                            } else {
                                callback.accept("NO_REPLY");
                            }
                        }
                    }, main, 0, null, null);
        } catch (RuntimeException failed) {
            main.removeCallbacks(timeout);
            main.post(() -> callback.accept("SEND_ERR:" + failed.getClass().getSimpleName()));
        }
    }
}
