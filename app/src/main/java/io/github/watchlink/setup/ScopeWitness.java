package io.github.watchlink.setup;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.RequiresApi;
import io.github.watchlink.BuildConfig;
import io.github.watchlink.alarm.probe.SenderVerifier;
import io.github.watchlink.alarm.probe.TargetProfile;
import io.github.watchlink.validation.ClockActionWire;
import io.github.watchlink.validation.DailyAlarmWire;
import java.util.function.Consumer;

/** The result belongs to this specific ordered broadcast to an already running, signed host. */
@RequiresApi(34)
public final class ScopeWitness {
    private static final String QUERY = BuildConfig.APPLICATION_ID + ".setup.CHECK_REMINDER_HOOK";

    public static BroadcastReceiver register(Context host, int ownerUid) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (isOrderedBroadcast() && QUERY.equals(intent.getAction())
                        && host.getPackageName().equals(intent.getPackage())
                        && getSentFromUid() == ownerUid && BuildConfig.APPLICATION_ID.equals(getSentFromPackage())) {
                    setResultCode(BuildConfig.VERSION_CODE);
                }
            }
        };
        host.registerReceiver(receiver, new IntentFilter(QUERY), ClockActionWire.PERMISSION, null, Context.RECEIVER_EXPORTED);
        return receiver;
    }

    static void query(Context owner, TargetProfile target, Consumer<Boolean> reply) {
        if (!SenderVerifier.trustedReminderTarget(owner, target)) {
            reply.accept(false);
            return;
        }
        owner.sendOrderedBroadcast(DailyAlarmWire.route(QUERY, target.packageName), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(), new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        reply.accept(getResultCode() == BuildConfig.VERSION_CODE);
                    }
                }, new Handler(Looper.getMainLooper()), 0, null, null);
    }

    private ScopeWitness() {}
}
