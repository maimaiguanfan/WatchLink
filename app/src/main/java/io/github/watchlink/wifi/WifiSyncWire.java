package io.github.watchlink.wifi;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.ProbeLog;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.validation.ClockActionWire;
import io.github.watchlink.validation.DailyAlarmWire;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Authenticated point-to-point IPC contracts for Wi-Fi sync:
 * - 1A: WatchLink UI -> Health :PhoneService (ordered broadcast per network, signature-protected).
 * - 1B: Health :PhoneService <-> com.oplus.wirelesssettings (explicit wake query & registered reply).
 * Never logs SSID or password.
 */
public final class WifiSyncWire {
    public static final String ACTION_SYNC_ONE = BuildConfig.APPLICATION_ID + ".wifi.SYNC_ONE_V1";
    public static final String ACTION_QUERY_CURRENT = BuildConfig.APPLICATION_ID + ".wifi.QUERY_CURRENT_V1";
    public static final String ACTION_CURRENT_REPLY = BuildConfig.APPLICATION_ID + ".wifi.CURRENT_REPLY_V1";

    public static final String WIRELESS_WAKE_RECEIVER =
            "com.android.settingslib.bluetooth.BluetoothDiscoverableTimeoutReceiver";

    public static final long SYNC_ONE_TIMEOUT_MS = 8_000L;
    public static final long QUERY_WINDOW_MS = 15_000L;
    private static final int ACK_CODE = 1;

    private static final Set<String> SYNC_STATUSES = Set.of(
            "ACK_OK", "SENT_NO_ACK_HOOK", "ACK_FAIL", "ACK_TIMEOUT", "SKIPPED_DUP",
            "WATCH_DISCONNECTED", "UNSUPPORTED_WATCH", "UNSUPPORTED_NETWORK",
            "DIRECT_UNAVAILABLE", "HEALTH_UNREACHABLE", "FAILED");

    private static final Set<String> QUERY_STATUSES = Set.of(
            "OK", "DISABLED", "NOT_CONNECTED", "UNSUPPORTED_EAP", "UNAVAILABLE");

    public record SyncResult(boolean reachable, String status, int ackCode) {
        public SyncResult {
            status = reachable && status != null && SYNC_STATUSES.contains(status)
                    ? status : (reachable ? "FAILED" : "HEALTH_UNREACHABLE");
        }

        public boolean succeeded() {
            return reachable && ("ACK_OK".equals(status) || "SENT_NO_ACK_HOOK".equals(status)
                    || "SKIPPED_DUP".equals(status));
        }

        public static SyncResult unreachable() {
            return new SyncResult(false, "HEALTH_UNREACHABLE", -1);
        }
    }

    public interface SyncHandler {
        SyncResult handle(WifiNetworkRecord record, boolean force);
    }

    private WifiSyncWire() {}

    public static boolean validQueryStatus(String status) {
        return status != null && QUERY_STATUSES.contains(status);
    }

    /** Builds the explicit component wake Intent from Health :PhoneService -> WirelessSettings. */
    public static Intent queryCurrentIntent(String requestId, long wall, long elapsed, String trigger) {
        return new Intent(ACTION_QUERY_CURRENT)
                .setComponent(new ComponentName(TargetProfile.WIRELESS_SETTINGS.packageName, WIRELESS_WAKE_RECEIVER))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("request", requestId)
                .putExtra("at", wall)
                .putExtra("elapsed", elapsed)
                .putExtra("trigger", trigger == null ? "AUTO" : trigger);
    }

    /** Builds the targeted registered-only reply from WirelessSettings -> Health :PhoneService. */
    public static Intent currentReplyIntent(String requestId, long wall, long elapsed,
                                            String status, WifiNetworkRecord record) {
        Intent reply = DailyAlarmWire.route(ACTION_CURRENT_REPLY, TargetProfile.HEALTH.packageName)
                .putExtra("request", requestId)
                .putExtra("at", wall)
                .putExtra("elapsed", elapsed)
                .putExtra("status", validQueryStatus(status) ? status : "UNAVAILABLE");
        if ("OK".equals(status) && record != null) {
            reply.putExtra("ssid", record.ssid())
                    .putExtra("password", record.password())
                    .putExtra("encodeType", record.encodeType())
                    .putExtra("capabilities", record.capabilities())
                    .putExtra("hidden", record.hidden());
        }
        return reply;
    }

    /** Registers Health :PhoneService receiver for 1A single-network sync requests from WatchLink UI. */
    @RequiresApi(34)
    public static BroadcastReceiver registerHealthSyncOne(Context host, int ownerUid,
                                                          Executor worker, SyncHandler handler) {
        if (ownerUid < 0 || !TargetProfile.HEALTH.packageName.equals(host.getPackageName())) return null;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!isOrderedBroadcast() || getSentFromUid() != ownerUid
                        || !BuildConfig.APPLICATION_ID.equals(getSentFromPackage())
                        || !ACTION_SYNC_ONE.equals(intent.getAction())
                        || intent.getComponent() != null
                        || !host.getPackageName().equals(intent.getPackage())
                        || (intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                    return;
                }
                String id = intent.getStringExtra("request");
                long wall = intent.getLongExtra("at", 0L);
                long elapsed = intent.getLongExtra("elapsed", -1L);
                String ssid = intent.getStringExtra("ssid");
                String password = intent.getStringExtra("password");
                int encodeType = intent.getIntExtra("encodeType", WifiNetworkRecord.ENCODE_OPEN);
                String capabilities = intent.getStringExtra("capabilities");
                boolean hidden = intent.getBooleanExtra("hidden", false);
                boolean force = intent.getBooleanExtra("force", false);
                if (!DailyAlarmWire.isRun(id) || ssid == null || ssid.isEmpty()) return;

                PendingResult pending = goAsync();
                try {
                    worker.execute(() -> {
                        try {
                            if (!DailyAlarmWire.inWindow(wall, elapsed, QUERY_WINDOW_MS)) return;
                            WifiNetworkRecord record = new WifiNetworkRecord(
                                    ssid, password, encodeType, capabilities, hidden, wall);
                            SyncResult result = handler.handle(record, force);
                            if (result == null) result = new SyncResult(true, "FAILED", -1);
                            Bundle extras = new Bundle();
                            extras.putString("request", id);
                            extras.putString("status", result.status());
                            extras.putInt("ackCode", result.ackCode());
                            pending.setResult(ACK_CODE, null, extras);
                        } catch (RuntimeException error) {
                            ProbeLog.failure("WIFI_SYNC_ONE_FAILED", error);
                            Bundle extras = new Bundle();
                            extras.putString("request", id);
                            extras.putString("status", "FAILED");
                            extras.putInt("ackCode", -1);
                            pending.setResult(ACK_CODE, null, extras);
                        } finally {
                            pending.finish();
                        }
                    });
                } catch (RejectedExecutionException unavailable) {
                    pending.finish();
                }
            }
        };
        host.registerReceiver(receiver, new IntentFilter(ACTION_SYNC_ONE),
                ClockActionWire.PERMISSION, null, Context.RECEIVER_EXPORTED);
        return receiver;
    }

    /** Sends one Wi-Fi record from WatchLink UI (1A) to Health :PhoneService. Always replies on main thread. */
    public static void sendSyncOne(Context owner, WifiNetworkRecord record, boolean force,
                                   Consumer<SyncResult> callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (Build.VERSION.SDK_INT < 34 || record == null || !record.isSupportedByWatch()
                || !BuildConfig.APPLICATION_ID.equals(owner.getPackageName())
                || owner.getApplicationInfo().uid != Process.myUid()
                || !SenderVerifier.trustedReminderTarget(owner, TargetProfile.HEALTH)) {
            main.post(() -> callback.accept(SyncResult.unreachable()));
            return;
        }
        sendSyncOneApi34(owner, record, force, main, callback);
    }

    @RequiresApi(34)
    private static void sendSyncOneApi34(Context owner, WifiNetworkRecord record, boolean force,
                                         Handler main, Consumer<SyncResult> callback) {
        AtomicBoolean finished = new AtomicBoolean();
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) callback.accept(SyncResult.unreachable());
        };
        String id = UUID.randomUUID().toString();
        Intent request = DailyAlarmWire.route(ACTION_SYNC_ONE, TargetProfile.HEALTH.packageName)
                .putExtra("request", id)
                .putExtra("at", System.currentTimeMillis())
                .putExtra("elapsed", SystemClock.elapsedRealtime())
                .putExtra("ssid", record.ssid())
                .putExtra("password", record.password())
                .putExtra("encodeType", record.encodeType())
                .putExtra("capabilities", record.capabilities())
                .putExtra("hidden", record.hidden())
                .putExtra("force", force);
        main.postDelayed(timeout, SYNC_ONE_TIMEOUT_MS);
        try {
            owner.sendOrderedBroadcast(request, null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent response) {
                            if (!finished.compareAndSet(false, true)) return;
                            main.removeCallbacks(timeout);
                            Bundle data = getResultExtras(false);
                            if (getResultCode() == ACK_CODE && data != null
                                    && id.equals(data.getString("request"))) {
                                callback.accept(new SyncResult(
                                        true,
                                        data.getString("status"),
                                        data.getInt("ackCode", -1)));
                            } else {
                                callback.accept(SyncResult.unreachable());
                            }
                        }
                    }, main, 0, null, null);
        } catch (RuntimeException failed) {
            main.removeCallbacks(timeout);
            main.post(timeout);
        }
    }
}
