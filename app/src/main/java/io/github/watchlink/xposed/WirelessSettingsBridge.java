package io.github.watchlink.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.libxposed.api.XposedInterface;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.validation.DailyAlarmWire;
import io.github.watchlink.wifi.WifiNetworkRecord;
import io.github.watchlink.wifi.WifiSyncWire;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Route 1B host adapter inside com.oplus.wirelesssettings (main process).
 * Hooks BluetoothDiscoverableTimeoutReceiver.onReceive synchronously at package-ready time so even
 * cold-start wake queries from Huawei Health :PhoneService never race. Uses WirelessSettings's own
 * NETWORK_SETTINGS + READ_WIFI_CREDENTIAL privileges to read ONLY the single currently-connected
 * Wi-Fi (works with Location Services completely OFF) and replies explicitly to Huawei Health.
 * Never logs or persists plaintext SSIDs or passwords.
 */
@RequiresApi(34)
final class WirelessSettingsBridge {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WatchLink-wireless-wifi");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final SharedPreferences remotePrefs;
    private volatile HostSettings hostSettings;
    private volatile XposedInterface.HookHandle wakeHook;

    private WirelessSettingsBridge(SharedPreferences remotePrefs) {
        this.remotePrefs = remotePrefs;
    }

    /**
     * Installs the unobfuscated wake receiver hook synchronously in onPackageReady (takes <1ms)
     * so cold-start explicit broadcasts never execute before the hook is armed.
     */
    static WirelessSettingsBridge installEarlyHook(XposedInterface module, ClassLoader loader,
                                                   SharedPreferences remotePrefs) {
        WirelessSettingsBridge bridge = new WirelessSettingsBridge(remotePrefs);
        try {
            Class<?> receiverClass = Class.forName(WifiSyncWire.WIRELESS_WAKE_RECEIVER, false, loader);
            if (receiverClass.getClassLoader() != loader
                    || !BroadcastReceiver.class.isAssignableFrom(receiverClass)) {
                return null;
            }
            Method onReceive = receiverClass.getDeclaredMethod("onReceive", Context.class, Intent.class);
            XposedInterface.HookBuilder builder = module.hook(onReceive)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH);
            if (module.getApiVersion() >= XposedInterface.API_102) {
                builder.setId("wireless.wifi.query");
            }
            bridge.wakeHook = builder.intercept(chain -> {
                if (bridge.closed.get()) return chain.proceed();
                Object arg1 = chain.getArg(1);
                if (!(arg1 instanceof Intent intent)
                        || !WifiSyncWire.ACTION_QUERY_CURRENT.equals(intent.getAction())) {
                    return chain.proceed();
                }
                Object self = chain.getThisObject();
                Object arg0 = chain.getArg(0);
                if (self instanceof BroadcastReceiver receiver && arg0 instanceof Context ctx) {
                    bridge.dispatchWakeQuery(receiver, ctx, intent);
                }
                return null;
            });
            return bridge;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            bridge.close();
            ProbeLog.failure("WIFI_1B_HOOK_UNAVAILABLE", unavailable);
            return null;
        }
    }

    void attachSettings(HostSettings settings) {
        this.hostSettings = settings;
    }

    boolean isHookActive() {
        return !closed.get() && wakeHook != null;
    }

    private void dispatchWakeQuery(BroadcastReceiver receiver, Context ctx, Intent intent) {
        int sentUid = receiver.getSentFromUid();
        String sentPkg = receiver.getSentFromPackage();
        if (sentUid < 0 || !TargetProfile.HEALTH.packageName.equals(sentPkg)) return;
        String requestId = intent.getStringExtra("request");
        long wall = intent.getLongExtra("at", 0L);
        long elapsed = intent.getLongExtra("elapsed", -1L);
        String trigger = intent.getStringExtra("trigger");
        if (!DailyAlarmWire.isRun(requestId)) return;

        Context app = ctx.getApplicationContext();
        BroadcastReceiver.PendingResult pending = receiver.goAsync();
        try {
            worker.execute(() -> {
                try {
                    if (closed.get()) return;
                    if (!SenderVerifier.verifyReminderSender(app, TargetProfile.HEALTH, sentUid, sentPkg)) {
                        return;
                    }
                    if (!DailyAlarmWire.inWindow(wall, elapsed, WifiSyncWire.QUERY_WINDOW_MS)) {
                        return;
                    }
                    handleVerifiedQuery(app, requestId, trigger);
                } catch (RuntimeException | LinkageError error) {
                    ProbeLog.failure("WIFI_1B_QUERY_FAILED", error);
                    sendReply(app, requestId, "UNAVAILABLE", null);
                } finally {
                    pending.finish();
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            pending.finish();
        }
    }

    private void handleVerifiedQuery(Context app, String requestId, String trigger) {
        boolean manual = "MANUAL".equals(trigger);
        if (!manual && !isAutoSyncAllowed(app)) {
            ProbeLog.info("WIFI_1B_QUERY_RESULT result=DISABLED");
            sendReply(app, requestId, "DISABLED", null);
            return;
        }

        WifiManager wm = app.getSystemService(WifiManager.class);
        if (wm == null || !wm.isWifiEnabled()) {
            ProbeLog.info("WIFI_1B_QUERY_RESULT result=UNAVAILABLE");
            sendReply(app, requestId, "NOT_CONNECTED", null);
            return;
        }

        WifiInfo info = currentWifiInfo(app, wm);
        if (info == null) {
            ProbeLog.info("WIFI_1B_QUERY_RESULT result=UNAVAILABLE");
            sendReply(app, requestId, "NOT_CONNECTED", null);
            return;
        }

        String ssid = WifiNetworkRecord.decodeSsid(info.getSSID());
        if (ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid)) {
            ProbeLog.info("WIFI_1B_QUERY_RESULT result=UNAVAILABLE");
            sendReply(app, requestId, "NOT_CONNECTED", null);
            return;
        }
        int networkId = info.getNetworkId();
        String bssid = info.getBSSID();
        int currentSecType = Build.VERSION.SDK_INT >= 31 ? info.getCurrentSecurityType() : -1;

        List<WifiConfiguration> configs = readPrivilegedConfigs(wm);
        WifiConfiguration matched = findConnectedConfig(configs, networkId, ssid, currentSecType);
        if (matched == null) {
            ProbeLog.info("WIFI_1B_QUERY_RESULT result=UNAVAILABLE");
            sendReply(app, requestId, "UNAVAILABLE", null);
            return;
        }

        String scanCapabilities = findScanCapabilities(wm, bssid, ssid);
        WifiNetworkRecord record = buildRecord(ssid, matched, currentSecType, scanCapabilities);
        if (record == null) {
            ProbeLog.info("WIFI_1B_QUERY_RESULT result=UNAVAILABLE");
            sendReply(app, requestId, "UNAVAILABLE", null);
            return;
        }
        if (record.isEnterprise() || !record.isSupportedByWatch()) {
            ProbeLog.info("WIFI_1B_QUERY_RESULT result=REJECTED");
            sendReply(app, requestId, "UNSUPPORTED_EAP", null);
            return;
        }

        ProbeLog.info("WIFI_1B_QUERY_RESULT result=VERIFIED");
        sendReply(app, requestId, "OK", record);
    }

    private boolean isAutoSyncAllowed(Context app) {
        HostSettings attached = hostSettings;
        if (attached != null) {
            return !attached.remoteAvailable() || attached.isWifiAutoSyncEnabled();
        }
        if (remotePrefs != null) {
            try {
                if (remotePrefs.contains(io.github.watchlink.setup.WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED)) {
                    return remotePrefs.getBoolean(
                            io.github.watchlink.setup.WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED, false);
                }
            } catch (RuntimeException ignored) {}
        }
        // If RemotePreferences is unavailable, Health already enforced the user toggle before sending.
        SharedPreferences local = app.getSharedPreferences(
                BuildConfig.APPLICATION_ID + ".wireless_settings_v1", Context.MODE_PRIVATE);
        return local.getBoolean(io.github.watchlink.setup.WatchLinkPrefs.KEY_WIFI_AUTO_SYNC_ENABLED, true);
    }

    @SuppressWarnings("deprecation")
    private static WifiInfo currentWifiInfo(Context app, WifiManager wm) {
        try {
            ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        TransportInfo ti = caps.getTransportInfo();
                        if (ti instanceof WifiInfo wi && isValidSsid(wi.getSSID())) {
                            return wi;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {}
        try {
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null && isValidSsid(wi.getSSID())) return wi;
        } catch (RuntimeException ignored) {}
        return null;
    }

    private static boolean isValidSsid(String raw) {
        String decoded = WifiNetworkRecord.decodeSsid(raw);
        return !decoded.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(decoded);
    }

    @SuppressWarnings("unchecked")
    private static List<WifiConfiguration> readPrivilegedConfigs(WifiManager wm) {
        try {
            Method method = WifiManager.class.getMethod("getPrivilegedConfiguredNetworks");
            Object result = method.invoke(wm);
            if (result instanceof List<?> list) {
                return (List<WifiConfiguration>) list;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            ProbeLog.failure("WIFI_1B_QUERY_FAILED", error);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static WifiConfiguration findConnectedConfig(List<WifiConfiguration> configs,
                                                         int networkId, String ssid, int currentSecType) {
        if (configs == null || configs.isEmpty()) return null;
        WifiConfiguration byId = null;
        WifiConfiguration bySsidAndSec = null;
        WifiConfiguration bySsid = null;
        for (WifiConfiguration cfg : configs) {
            if (cfg == null) continue;
            String cfgSsid = WifiNetworkRecord.decodeSsid(cfg.SSID);
            if (networkId >= 0 && cfg.networkId == networkId && (cfgSsid.isEmpty() || ssid.equals(cfgSsid))) {
                byId = cfg;
                break;
            }
            if (ssid.equals(cfgSsid)) {
                if (currentSecType >= 0 && matchesSecurityType(cfg, currentSecType)) {
                    bySsidAndSec = cfg;
                } else if (bySsid == null) {
                    bySsid = cfg;
                }
            }
        }
        if (byId != null) return byId;
        if (bySsidAndSec != null) return bySsidAndSec;
        return bySsid;
    }

    private static boolean matchesSecurityType(WifiConfiguration cfg, int targetSecType) {
        try {
            Method getter = WifiConfiguration.class.getMethod("getSecurityParamsList");
            Object listObj = getter.invoke(cfg);
            if (listObj instanceof List<?> list) {
                for (Object params : list) {
                    if (params == null) continue;
                    Method getType = params.getClass().getMethod("getSecurityType");
                    Object val = getType.invoke(params);
                    if (val instanceof Integer sec && sec == targetSecType) return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        return false;
    }

    @SuppressWarnings("deprecation")
    private static String findScanCapabilities(WifiManager wm, String bssid, String ssid) {
        try {
            List<ScanResult> results = wm.getScanResults();
            if (results == null || results.isEmpty()) return null;
            String ssidMatch = null;
            for (ScanResult sr : results) {
                if (sr == null || sr.capabilities == null || sr.capabilities.isEmpty()) continue;
                if (bssid != null && !bssid.isEmpty() && bssid.equalsIgnoreCase(sr.BSSID)) {
                    return sr.capabilities;
                }
                String srSsid = WifiNetworkRecord.decodeSsid(sr.SSID);
                if (ssidMatch == null && ssid.equals(srSsid)) {
                    ssidMatch = sr.capabilities;
                }
            }
            return ssidMatch;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static WifiNetworkRecord buildRecord(String ssid, WifiConfiguration cfg,
                                                 int currentSecType, String scanCapabilities) {
        String psk = cfg.preSharedKey == null ? "" : WifiNetworkRecord.unquote(cfg.preSharedKey);
        String wep = readWepKey(cfg);
        BitSet km = cfg.allowedKeyManagement != null ? cfg.allowedKeyManagement : new BitSet();

        boolean kmEap = km.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || km.get(WifiConfiguration.KeyMgmt.IEEE8021X)
                || km.get(10) // SUITE_B_192
                || km.get(12); // FT_EAP
        boolean kmPsk = km.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                || km.get(4) // WPA2_PSK
                || km.get(11); // FT_PSK
        boolean kmSae = km.get(8); // SAE
        boolean kmOwe = km.get(9); // OWE

        int encodeType;
        boolean wpa3Transition = kmPsk && kmSae;
        if (currentSecType == 3 || currentSecType == 5 || currentSecType == 9
                || currentSecType == 11 || currentSecType == 12
                || (kmEap && !kmPsk && !kmSae)) {
            encodeType = WifiNetworkRecord.ENCODE_EAP;
        } else if (scanCapabilities != null && !scanCapabilities.isEmpty()) {
            encodeType = WifiNetworkRecord.encodeTypeFromCapabilities(scanCapabilities);
            // Respect current active WPA3-SAE if scan string lacked PSK or was pure SAE.
            if (encodeType == WifiNetworkRecord.ENCODE_OPEN && !psk.isEmpty()) {
                encodeType = (currentSecType == 4 || (kmSae && !kmPsk))
                        ? WifiNetworkRecord.ENCODE_SAE : WifiNetworkRecord.ENCODE_PSK;
            }
        } else if (currentSecType == 4 || (kmSae && !kmPsk)) {
            encodeType = WifiNetworkRecord.ENCODE_SAE;
        } else if (currentSecType == 2 || kmPsk) {
            encodeType = WifiNetworkRecord.ENCODE_PSK;
        } else if (currentSecType == 1 || !wep.isEmpty()) {
            encodeType = WifiNetworkRecord.ENCODE_WEP;
        } else if (currentSecType == 0 || currentSecType == 6 || km.get(WifiConfiguration.KeyMgmt.NONE) || kmOwe) {
            encodeType = WifiNetworkRecord.ENCODE_OPEN;
        } else {
            encodeType = !psk.isEmpty() ? WifiNetworkRecord.ENCODE_PSK : WifiNetworkRecord.ENCODE_OPEN;
        }

        String password = switch (encodeType) {
            case WifiNetworkRecord.ENCODE_OPEN -> "";
            case WifiNetworkRecord.ENCODE_WEP -> wep;
            default -> psk;
        };
        String capabilities = (scanCapabilities != null && !scanCapabilities.isEmpty())
                ? WifiNetworkRecord.sanitizeCapabilities(scanCapabilities, encodeType)
                : WifiNetworkRecord.synthesizeCapabilities(encodeType, wpa3Transition, currentSecType == 6 || kmOwe);
        return new WifiNetworkRecord(ssid, password, encodeType, capabilities, cfg.hiddenSSID,
                System.currentTimeMillis());
    }

    @SuppressWarnings("deprecation")
    private static String readWepKey(WifiConfiguration cfg) {
        if (cfg.wepKeys == null || cfg.wepKeys.length == 0) return "";
        int idx = cfg.wepTxKeyIndex;
        if (idx >= 0 && idx < cfg.wepKeys.length && cfg.wepKeys[idx] != null && !cfg.wepKeys[idx].isEmpty()) {
            return WifiNetworkRecord.unquote(cfg.wepKeys[idx]);
        }
        for (String key : cfg.wepKeys) {
            if (key != null && !key.isEmpty()) return WifiNetworkRecord.unquote(key);
        }
        return "";
    }

    private static void sendReply(Context app, String requestId, String status, WifiNetworkRecord record) {
        try {
            Intent reply = WifiSyncWire.currentReplyIntent(
                    requestId, System.currentTimeMillis(), SystemClock.elapsedRealtime(), status, record);
            DailyAlarmWire.send(app, reply);
        } catch (RuntimeException failed) {
            ProbeLog.failure("WIFI_1B_REPLY_FAILED", failed);
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
        XposedInterface.HookHandle hook = wakeHook;
        wakeHook = null;
        if (hook != null) {
            try { hook.unhook(); } catch (RuntimeException ignored) {}
        }
        worker.shutdownNow();
    }
}
