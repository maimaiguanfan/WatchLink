package io.github.watchlink.xposed;

import io.github.watchlink.validation.DailyAlarmWire;
import io.github.watchlink.validation.HuaweiReminderService;
import io.github.watchlink.validation.PhoneRingWire;
import java.util.HashMap;
import java.util.Map;

/**
 * Health-side single active reminder state machine.
 * Correlates both the unobfuscated ExternalPhoneService Intent path and optional SDK wire observation,
 * guaranteeing at-most-once SNOOZE/DISMISS delivery and reporting whether both sources agreed.
 */
final class DailyAlarmObservation {
    static final long WINDOW = DailyAlarmWire.MAX_WINDOW;
    private static final long CLOSE_WAIT = 5_000;
    private static final int MAX_RECENT = 128;

    record Evidence(String run, int reminder, String kind, long wall, long elapsed, String actionSource) {
        Evidence(String run, int reminder, String kind, long wall, long elapsed) {
            this(run, reminder, kind, wall, elapsed, "");
        }

        @Override public String toString() { return kind; }
    }

    private record Seen(int reminder, long expires) {}

    private final Map<String, Seen> recent = new HashMap<>();
    private long lastArm = -1;
    private Session current;

    private static final class Session {
        final String run;
        final int reminder;
        final long wall, elapsed;
        final String openIdentifier;
        final CooperativeSessionMatch open, close;
        final boolean sdkAvailable;
        long end;
        boolean ending, closed, serviceReceived, directDispatched, outbound;
        String answeredAction;
        boolean sdkAnswered, intentAnswered;

        Session(String run, int reminder, String device, String openIdentifier,
                long wall, long elapsed, boolean sdkAvailable) {
            this.run = run;
            this.reminder = reminder;
            this.openIdentifier = openIdentifier;
            this.wall = wall;
            this.elapsed = elapsed;
            this.end = elapsed + WINDOW;
            this.sdkAvailable = sdkAvailable && device != null && !device.isEmpty();
            this.open = this.sdkAvailable ? new CooperativeSessionMatch(device, reminder, false) : null;
            this.close = this.sdkAvailable ? new CooperativeSessionMatch(device, reminder, true) : null;
        }

        boolean dispatched() {
            return serviceReceived || directDispatched;
        }

        boolean matches(String run, int reminder) {
            return this.reminder == reminder && this.run.equals(run);
        }
    }

    synchronized boolean active(long wall, long elapsed) {
        if (current != null && (elapsed < current.elapsed || elapsed >= current.end
                || wall < current.wall || wall - current.wall >= WINDOW)) {
            current = null;
        }
        return current != null;
    }

    synchronized String arm(String run, int reminder, long at, long start, String device,
                            String identifier, long wall, long elapsed, boolean sdkAvailable) {
        if (reminder <= 0 || !validOpenIdentifier(run, reminder, identifier)
                || at <= 0 || start < 0 || wall < at || wall - at >= PhoneRingWire.MAX_AGE
                || elapsed < start || elapsed - start >= PhoneRingWire.MAX_AGE) {
            return "UNAVAILABLE";
        }
        active(wall, elapsed);
        recent.values().removeIf(item -> item.expires <= elapsed);
        if (recent.containsKey(run)) return null;
        if (recent.size() >= MAX_RECENT || start <= lastArm) return "UNAVAILABLE";
        boolean usedId = recent.values().stream().anyMatch(item -> item.reminder == reminder);
        recent.put(run, new Seen(reminder, elapsed + WINDOW));
        lastArm = start;
        if (usedId || (sdkAvailable && (device == null || device.isEmpty()))
                || (current != null && !current.ending && !current.closed)) {
            return "UNAVAILABLE";
        }
        current = new Session(run, reminder, device, identifier, at, start, sdkAvailable);
        return "READY";
    }

    private static boolean validOpenIdentifier(String run, int reminder, String identifier) {
        if (identifier == null) return false;
        for (int variant = 0; variant <= 6; variant++) {
            if (identifier.equals(HuaweiReminderService.dailyIdentifier(run, reminder, variant))) return true;
        }
        return false;
    }

    synchronized void markDirectDispatched(String run, int reminder) {
        if (current != null && current.matches(run, reminder) && !current.ending && !current.closed) {
            current.directDispatched = true;
        }
    }

    synchronized void abort(String run, int reminder) {
        if (current != null && current.matches(run, reminder)) {
            current = null;
        }
    }

    synchronized boolean shouldClose(String run, int reminder, long wall, long elapsed) {
        if (!active(wall, elapsed) || !current.matches(run, reminder)) return false;
        return !current.closed;
    }

    synchronized void end(String run, int reminder, long wall, long elapsed) {
        recent.values().removeIf(item -> item.expires <= elapsed);
        if (!recent.containsKey(run) && recent.size() < MAX_RECENT) {
            recent.put(run, new Seen(reminder, elapsed + WINDOW));
        }
        if (!active(wall, elapsed) || !current.matches(run, reminder)) return;
        current.ending = true;
        current.end = Math.min(current.end, elapsed + CLOSE_WAIT);
    }

    synchronized Evidence serviceReceived(int reminder, String identifier, long wall, long elapsed) {
        if (!active(wall, elapsed) || current.ending || current.closed || current.serviceReceived
                || wall - current.wall >= PhoneRingWire.MAX_AGE || elapsed - current.elapsed >= PhoneRingWire.MAX_AGE
                || current.reminder != reminder || !current.openIdentifier.equals(identifier)) {
            return null;
        }
        current.serviceReceived = true;
        return new Evidence(current.run, reminder, "SERVICE_RECEIVED", wall, elapsed);
    }

    /** Called from ExternalPhoneService.onStartCommand when action == "alarm_clock_msg_from_device". */
    synchronized Evidence serviceAction(int reminder, int actionCode, long wall, long elapsed) {
        if (!active(wall, elapsed) || !current.dispatched() || current.ending || current.closed
                || current.reminder != reminder) {
            return null;
        }
        String action = actionCode == 1 ? "SNOOZE" : actionCode == 2 ? "DISMISS" : null;
        if (action == null) return null;
        if (current.answeredAction != null) {
            if (!current.intentAnswered && current.sdkAnswered && action.equals(current.answeredAction)) {
                current.intentAnswered = true;
                return new Evidence(current.run, reminder, "ACTION_DUAL_CONFIRMED", wall, elapsed, "SDK_AND_INTENT");
            }
            return null;
        }
        // When SDK hooks are active and haven't observed outbound 8/9 yet, wait for verified outbound.
        if (current.sdkAvailable && !current.outbound) return null;
        current.answeredAction = action;
        current.intentAnswered = true;
        return new Evidence(current.run, reminder, action, wall, elapsed, "INTENT_ONLY");
    }

    /** Called from optional SDK hooks (zcj.sendCommand outbound / DevicesManagementService$8 inbound). */
    synchronized Evidence observe(boolean received, String device, byte[] frame, long wall, long elapsed) {
        if (!active(wall, elapsed) || !current.dispatched() || !current.sdkAvailable) return null;
        String closeKind = current.close.observe(received, device, frame);
        if (closeKind != null) {
            current.closed = true;
            current.end = Math.min(current.end, elapsed + CLOSE_WAIT);
            return new Evidence(current.run, current.reminder, closeKind, wall, elapsed);
        }
        if (current.ending || current.closed) return null;
        String kind = current.open.observe(received, device, frame);
        if (kind == null) return null;
        if ("OUTBOUND".equals(kind)) {
            current.outbound = true;
            return new Evidence(current.run, current.reminder, kind, wall, elapsed);
        }
        if ("SNOOZE".equals(kind) || "DISMISS".equals(kind)) {
            if (current.answeredAction != null) {
                if (!current.sdkAnswered && current.intentAnswered && kind.equals(current.answeredAction)) {
                    current.sdkAnswered = true;
                    return new Evidence(current.run, current.reminder, "ACTION_DUAL_CONFIRMED", wall, elapsed, "SDK_AND_INTENT");
                }
                return null;
            }
            current.answeredAction = kind;
            current.sdkAnswered = true;
            return new Evidence(current.run, current.reminder, kind, wall, elapsed, "SDK_ONLY");
        }
        return null;
    }

    synchronized void stop() { current = null; }
}
