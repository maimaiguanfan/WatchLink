package io.github.watchlink.xposed;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.live.LiveWindowPayload;
import io.github.watchlink.live.LiveWindowTracker;
import io.github.watchlink.live.LiveWindowWire;
import io.github.watchlink.live.PantanalFluidParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Channel B Fluid Cloud observer inside com.oplus.pantanal.ums (ColorOS Pantanal UMS).
 * Captures active Pantanal Fluid Cloud cards via:
 *   1. Decision ContentProvider (content://com.oplus.pantanal.ums.decision/systemui/services) + ContentObserver
 *   2. Real-time strategy push/remove hooks (dynamically resolved via UmsSymbolResolver)
 * Explicitly excludes Clock timer/stopwatch cards and never hooks SystemUI.
 */
@RequiresApi(34)
final class UmsFluidBridge {
    private static final Uri DECISION_SYSUI_URI =
            Uri.parse("content://com.oplus.pantanal.ums.decision/systemui/services");
    private static final String DECISION_KEY_PREFIX = "ums:";
    private static final int MAX_PROTO_BYTES = 65_536;
    private static final long DEBOUNCE_SYNC_MS = 280L;

    private final Context app;
    private final HostSettings settings;
    private final String[] sources;
    private final LiveWindowTracker tracker = new LiveWindowTracker();
    private final Map<Integer, String> cardTypeToStableKey = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "WatchLink-ums-fluid");
        t.setDaemon(true);
        return t;
    });

    private ContentObserver decisionObserver;
    private ScheduledFuture<?> pendingDecisionSync;
    private Method parseClientProto;
    private Method parseUiProto;
    private volatile boolean installed;

    UmsFluidBridge(Context app, HostSettings settings, String[] sources) {
        this.app = app;
        this.settings = settings;
        this.sources = sources;
        worker.setRemoveOnCancelPolicy(true);
    }

    void install(AlarmObserverInstaller installer) {
        resolveProtoParsers(installer);

        ApkSymbolCache cache = new ApkSymbolCache(app, "ums");
        UmsSymbolResolver.Symbols symbols = UmsSymbolResolver.resolve(installer, cache, sources);
        String resolverSource = symbols != null && symbols.complete() ? symbols.source() : "DEGRADED";
        ProbeLog.info("UMS_SYMBOLS_RESOLVED source=UMS resolver=" + resolverSource);

        boolean strategyHooked = symbols != null && symbols.complete() && installStrategyHooks(installer, symbols);
        installPolicyUnobserveHook(installer);
        registerDecisionObserver();

        settings.onLiveWindowChanged(enabled -> {
            if (Boolean.TRUE.equals(enabled)) {
                scheduleDecisionSync(100L);
            } else {
                worker.execute(() -> {
                    tracker.closeAll();
                    cardTypeToStableKey.clear();
                });
            }
        });

        installed = true;
        ProbeLog.info("UMS_LIVE_READY source=UMS resolver=" + (strategyHooked ? resolverSource : "DEGRADED"));
        if (settings.isLiveWindowEnabled()) {
            scheduleDecisionSync(1_000L);
        }
    }

    void close() {
        installed = false;
        settings.onLiveWindowChanged(null);
        if (pendingDecisionSync != null) {
            pendingDecisionSync.cancel(false);
            pendingDecisionSync = null;
        }
        if (decisionObserver != null) {
            try { app.getContentResolver().unregisterContentObserver(decisionObserver); }
            catch (RuntimeException ignored) {}
            decisionObserver = null;
        }
        tracker.closeAll();
        cardTypeToStableKey.clear();
        worker.shutdownNow();
    }

    private void resolveProtoParsers(AlarmObserverInstaller installer) {
        try {
            Class<?> c = installer.target("com.oplus.pantanal.ums.cardservice.valueobject.model.UIDataClientProto");
            parseClientProto = c.getMethod("parseFrom", byte[].class);
        } catch (Throwable ignored) {}
        try {
            Class<?> c = installer.target("com.oplus.pantanal.ums.cardservice.valueobject.model.UIDataProto");
            parseUiProto = c.getMethod("parseFrom", byte[].class);
        } catch (Throwable ignored) {}
    }

    private boolean installStrategyHooks(AlarmObserverInstaller installer, UmsSymbolResolver.Symbols symbols) {
        try {
            Class<?> strategyCls = installer.target(symbols.strategyClass());
            Class<?> idCls = installer.target(UmsSymbolResolver.CARD_IDENTIFY);
            Method pushMethod = null;
            Method removeMethod = null;
            for (Method m : strategyCls.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (symbols.pushMethod().equals(m.getName()) && p.length == 3
                        && p[0] == idCls && p[1] == byte[].class) {
                    pushMethod = m;
                } else if (symbols.removeMethod().equals(m.getName()) && p.length == 1 && p[0] == idCls) {
                    removeMethod = m;
                }
            }
            if (pushMethod == null || removeMethod == null) return false;

            installer.observe("ums.fluid.push", pushMethod, (chain, result) -> {
                if (!installed || !settings.isLiveWindowEnabled()) return;
                Object cardIdObj = chain.getArg(0);
                Object rawArg = chain.getArg(1);
                if (cardIdObj == null || !(rawArg instanceof byte[] bytes) || bytes.length == 0 || bytes.length > MAX_PROTO_BYTES) {
                    return;
                }
                int cardType = callInt(cardIdObj, "getCardType", -1);
                String widgetCode = callStr(cardIdObj, "getWidgetCode");
                byte[] copy = bytes.clone();
                worker.execute(() -> handlePushOnWorker(cardType, widgetCode, copy));
            });

            installer.observe("ums.fluid.remove", removeMethod, (chain, result) -> {
                if (!installed || !settings.isLiveWindowEnabled()) return;
                Object cardIdObj = chain.getArg(0);
                int cardType = cardIdObj == null ? -1 : callInt(cardIdObj, "getCardType", -1);
                worker.execute(() -> handleRemoveOnWorker(cardType));
            });
            return true;
        } catch (Throwable err) {
            ProbeLog.failure("UMS_LIVE_READY", err);
            return false;
        }
    }

    private void installPolicyUnobserveHook(AlarmObserverInstaller installer) {
        try {
            Class<?> policyCls = installer.target("com.oplus.pantanal.ums.cardservice.valueobject.policy.UiDataChannelPolicy");
            Method unObserve = policyCls.getDeclaredMethod("unObserve", String.class);
            installer.observe("ums.fluid.unobserve", unObserve, (chain, result) -> {
                if (installed && settings.isLiveWindowEnabled()) {
                    scheduleDecisionSync(DEBOUNCE_SYNC_MS);
                }
            });
        } catch (Throwable ignored) {}
    }

    private void registerDecisionObserver() {
        try {
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) {
                    if (installed && settings.isLiveWindowEnabled()) {
                        scheduleDecisionSync(DEBOUNCE_SYNC_MS);
                    }
                }
            };
            app.getContentResolver().registerContentObserver(DECISION_SYSUI_URI, true, observer);
            decisionObserver = observer;
        } catch (RuntimeException err) {
            ProbeLog.failure("UMS_LIVE_READY", err);
        }
    }

    private void scheduleDecisionSync(long delayMs) {
        if (!installed) return;
        try {
            synchronized (this) {
                if (pendingDecisionSync != null) pendingDecisionSync.cancel(false);
                pendingDecisionSync = worker.schedule(this::syncDecisionSnapshotOnWorker, delayMs, TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException ignored) {}
    }

    private void syncDecisionSnapshotOnWorker() {
        if (!installed || !settings.isLiveWindowEnabled()) return;
        Set<String> presentKeys = new HashSet<>();
        long nowElapsed = SystemClock.elapsedRealtime();
        try (Cursor c = app.getContentResolver().query(DECISION_SYSUI_URI, null, null, null, null)) {
            if (c == null) return;
            int idxServiceId = colIndex(c, "serviceId", "service_id");
            int idxInstanceId = colIndex(c, "serviceInstanceId", "service_instance_id");
            int idxHostPkg = colIndex(c, "hostPackage", "host_package");
            int idxDomain = colIndex(c, "domain");
            int idxSubDomain = colIndex(c, "subDomain", "sub_domain");
            int idxBizData = colIndex(c, "businessData", "business_data");
            int idxParam = colIndex(c, "param");
            int idxAvailable = colIndex(c, "available");

            int rowLimit = 24;
            while (c.moveToNext() && rowLimit-- > 0) {
                if (idxAvailable >= 0 && c.getType(idxAvailable) == Cursor.FIELD_TYPE_INTEGER && c.getInt(idxAvailable) == 0) {
                    continue;
                }
                long serviceId = readLong(c, idxServiceId);
                String instanceId = readString(c, idxInstanceId);
                String hostPkg = readString(c, idxHostPkg);
                String domain = readString(c, idxDomain);
                String subDomain = readString(c, idxSubDomain);
                String bizData = readString(c, idxBizData);
                String param = readString(c, idxParam);

                LiveWindowPayload parsed = PantanalFluidParser.parseDecisionRow(
                        serviceId, instanceId, hostPkg, domain, subDomain, bizData, param);
                if (parsed == null) continue;
                presentKeys.add(parsed.stableKey());
                LiveWindowPayload outbound = tracker.onUpsert(parsed, nowElapsed);
                if (outbound != null) {
                    sendToHealth(outbound, false);
                }
            }
        } catch (Throwable err) {
            ProbeLog.failure("UMS_LIVE_FORWARD", err);
            return;
        }

        List<LiveWindowPayload> removed = tracker.reconcilePrefix(DECISION_KEY_PREFIX, presentKeys);
        for (LiveWindowPayload close : removed) {
            sendToHealth(close, true);
        }
    }

    private void handlePushOnWorker(int cardType, String widgetCode, byte[] raw) {
        if (!installed || !settings.isLiveWindowEnabled()) return;
        DecodedProto decoded = decodeUiBytes(raw);
        if (decoded == null || decoded.json == null || decoded.json.isEmpty()) return;

        String existingKey = cardType > 0 ? cardTypeToStableKey.get(cardType) : null;
        LiveWindowPayload parsed = PantanalFluidParser.parsePushData(
                cardType, decoded.cardId, widgetCode, decoded.layoutName, decoded.cardName,
                decoded.json, "", existingKey);
        if (parsed == null) return;
        if (cardType > 0) {
            cardTypeToStableKey.put(cardType, parsed.stableKey());
        }
        LiveWindowPayload outbound = tracker.onUpsert(parsed, SystemClock.elapsedRealtime());
        if (outbound != null) {
            sendToHealth(outbound, false);
        }
    }

    private void handleRemoveOnWorker(int cardType) {
        if (!installed) return;
        if (cardType > 0) {
            String key = cardTypeToStableKey.remove(cardType);
            if (key != null) {
                LiveWindowPayload close = tracker.onRemove(key);
                if (close != null) {
                    sendToHealth(close, true);
                }
            }
        }
        // Also reconcile decision rows quickly (captures milestone cards like pickup codes removed on tap).
        scheduleDecisionSync(DEBOUNCE_SYNC_MS);
    }

    private void sendToHealth(LiveWindowPayload payload, boolean removal) {
        try {
            LiveWindowWire.sendToHealth(app, payload);
            ProbeLog.info((removal ? "UMS_LIVE_REMOVE" : "UMS_LIVE_FORWARD")
                    + " source=UMS live_event=" + payload.eventType());
        } catch (RuntimeException err) {
            ProbeLog.failure("UMS_LIVE_FORWARD", err);
        }
    }

    private record DecodedProto(int cardId, String cardName, String layoutName, String json) {}

    private DecodedProto decodeUiBytes(byte[] raw) {
        DecodedProto res = tryDecodeProto(parseClientProto, raw);
        if (res != null) return res;
        res = tryDecodeProto(parseUiProto, raw);
        if (res != null) return res;
        String plain = decodeMaybeCompressed(raw);
        return plain != null && plain.trim().startsWith("{") ? new DecodedProto(-1, "", "", plain) : null;
    }

    private DecodedProto tryDecodeProto(Method parseMethod, byte[] raw) {
        if (parseMethod == null || raw == null || raw.length == 0) return null;
        try {
            Object proto = parseMethod.invoke(null, (Object) raw);
            if (proto == null) return null;
            int cardId = callInt(proto, "getCardId", -1);
            String name = callStr(proto, "getName");
            String layout = callStr(proto, "getLayoutName");
            String data = extractProtoData(proto);
            if (data == null || data.isEmpty()) data = extractProtoBytes(proto, "getValue");
            if (data == null || data.isEmpty()) return null;
            return new DecodedProto(cardId, name, layout, data);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractProtoData(Object proto) {
        try {
            Method m = proto.getClass().getMethod("getData");
            Object v = m.invoke(proto);
            if (v instanceof String s) return s;
            if (v != null) {
                byte[] bytes = (byte[]) v.getClass().getMethod("toByteArray").invoke(v);
                return decodeMaybeCompressed(bytes);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractProtoBytes(Object proto, String getter) {
        try {
            Method m = proto.getClass().getMethod(getter);
            Object v = m.invoke(proto);
            if (v == null) return null;
            byte[] bytes = (byte[]) v.getClass().getMethod("toByteArray").invoke(v);
            return decodeMaybeCompressed(bytes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String decodeMaybeCompressed(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        try {
            if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                    return new String(readLimited(in, MAX_PROTO_BYTES), StandardCharsets.UTF_8);
                }
            }
            if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x78) {
                try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
                    return new String(readLimited(in, MAX_PROTO_BYTES), StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {}
        return new String(bytes, 0, Math.min(bytes.length, MAX_PROTO_BYTES), StandardCharsets.UTF_8);
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < maxBytes) {
            out.write(buf, 0, Math.min(n, maxBytes - out.size()));
        }
        return out.toByteArray();
    }

    private static int colIndex(Cursor c, String... names) {
        for (String name : names) {
            int idx = c.getColumnIndex(name);
            if (idx >= 0) return idx;
        }
        return -1;
    }

    private static long readLong(Cursor c, int idx) {
        if (idx < 0 || c.isNull(idx)) return -1L;
        if (c.getType(idx) == Cursor.FIELD_TYPE_INTEGER) return c.getLong(idx);
        if (c.getType(idx) == Cursor.FIELD_TYPE_STRING) {
            try { return Long.parseLong(c.getString(idx).trim()); }
            catch (NumberFormatException ignored) { return -1L; }
        }
        return -1L;
    }

    private static String readString(Cursor c, int idx) {
        if (idx < 0 || c.isNull(idx)) return "";
        int t = c.getType(idx);
        if (t == Cursor.FIELD_TYPE_STRING) return c.getString(idx);
        if (t == Cursor.FIELD_TYPE_INTEGER) return Long.toString(c.getLong(idx));
        return "";
    }

    private static String callStr(Object obj, String method) {
        try {
            Object v = obj.getClass().getMethod(method).invoke(obj);
            return v instanceof String s ? s : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int callInt(Object obj, String method, int fallback) {
        try {
            Object v = obj.getClass().getMethod(method).invoke(obj);
            return v instanceof Integer n ? n : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
