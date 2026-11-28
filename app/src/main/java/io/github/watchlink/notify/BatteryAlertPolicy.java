package io.github.watchlink.notify;

/**
 * Pure-Java state machine for bidirectional phone <-> watch battery and charging alerts.
 *
 * <ul>
 *   <li>Phone -> Watch full alert: fires once per plug session on 100%/FULL, ColorOS charging
 *       limit ({@code regular_charge_protection_switch_state=1} +
 *       {@code regular_charge_protection_open_power_level}), or sustained generic {@code NOT_CHARGING}
 *       cap (>= 80%). Explicitly excludes ColorOS smart-charging overnight pause
 *       ({@code smart_charge_protection_status > 0}), bypass charging ({@code bypass_charge_state > 0}),
 *       and battery overheat.</li>
 *   <li>Phone -> Watch low alert: fires once when unplugged battery drops to {@code <= threshold}
 *       (1..80%, default 20%), resetting on plug-in or recovery to {@code min(100, threshold + 5)}.</li>
 *   <li>Watch -> Phone full alert: fires once per watch charging session when connected &amp; charging
 *       reaches 100%, with native Health full-battery deduplication and cold-start suppression.</li>
 *   <li>Watch -> Phone low alert: fires once when connected &amp; not charging drops to
 *       {@code <= threshold} (1..80%, default 20%), resetting on charge or recovery to
 *       {@code min(100, threshold + 5)}.</li>
 * </ul>
 */
public final class BatteryAlertPolicy {
    public static final int STATUS_UNKNOWN = 1;
    public static final int STATUS_CHARGING = 2;
    public static final int STATUS_DISCHARGING = 3;
    public static final int STATUS_NOT_CHARGING = 4;
    public static final int STATUS_FULL = 5;

    public static final int LOW_HYSTERESIS_DELTA = 5;
    public static final int MIN_REGULAR_LIMIT_LEVEL = 50;
    public static final int MAX_REGULAR_LIMIT_LEVEL = 99;
    public static final int GENERIC_CAP_MIN_LEVEL = 80;
    public static final long COLOROS_LIMIT_SUSTAIN_MS = 15_000L;
    public static final long GENERIC_NOT_CHARGING_SUSTAIN_MS = 20_000L;
    public static final long NATIVE_WATCH_FULL_DEDUP_MS = 60_000L;

    public record PhoneSample(
            int level,
            int plugged,
            int status,
            boolean overheat,
            boolean bypassCharging,
            boolean smartChargeProtecting,
            boolean regularLimitEnabled,
            int regularLimitLevel,
            boolean regularLimitActive,
            boolean chargeProtectionActive,
            long elapsedMs) {}

    public enum PhoneFullKind {
        STANDARD_100,
        COLOROS_CHARGE_LIMIT,
        GENERIC_CHARGE_LIMIT
    }

    public record PhoneAlert(boolean fullAlert, PhoneFullKind fullKind, int level) {
        public static PhoneAlert full(PhoneFullKind kind, int level) {
            return new PhoneAlert(true, kind, level);
        }

        public static PhoneAlert low(int level) {
            return new PhoneAlert(false, null, level);
        }
    }

    public record WatchAlert(boolean fullAlert, int level) {
        public static WatchAlert full(int level) {
            return new WatchAlert(true, level);
        }

        public static WatchAlert low(int level) {
            return new WatchAlert(false, level);
        }
    }

    // Phone state
    private boolean phoneSeeded;
    private boolean phoneWasPlugged;
    private boolean phoneSawNotFullInPlugSession;
    private boolean phoneFullNotifiedForPlug;
    private long colorosLimitReachedSinceMs = -1L;
    private long genericNotChargingSinceMs = -1L;
    private boolean phoneLowNotified;

    // Watch state
    private boolean watchSeeded;
    private boolean watchWasCharging;
    private boolean watchSawBelowFullInChargeSession;
    private boolean watchFullNotifiedForCharge;
    private long lastNativeWatchFullElapsedMs = -1L;
    private boolean watchLowNotified;

    public synchronized void resetAll() {
        phoneSeeded = false;
        phoneWasPlugged = false;
        phoneSawNotFullInPlugSession = false;
        phoneFullNotifiedForPlug = false;
        colorosLimitReachedSinceMs = -1L;
        genericNotChargingSinceMs = -1L;
        phoneLowNotified = false;
        watchSeeded = false;
        watchWasCharging = false;
        watchSawBelowFullInChargeSession = false;
        watchFullNotifiedForCharge = false;
        lastNativeWatchFullElapsedMs = -1L;
        watchLowNotified = false;
    }

    public synchronized void markNativeWatchFullNotified(long nowElapsedMs) {
        lastNativeWatchFullElapsedMs = nowElapsedMs;
        watchFullNotifiedForCharge = true;
    }

    public static int hysteresisResetLevel(int threshold) {
        return Math.min(100, Math.max(1, threshold) + LOW_HYSTERESIS_DELTA);
    }

    /**
     * Evaluates a phone battery sample against the enabled phone-full and phone-low switches.
     * Returns null when no alert should fire.
     */
    public synchronized PhoneAlert onPhoneSample(
            PhoneSample sample,
            boolean fullAlertEnabled,
            boolean lowAlertEnabled,
            int lowThreshold) {
        if (sample == null || sample.level() < 0 || sample.level() > 100) {
            return null;
        }
        int level = sample.level();
        boolean plugged = sample.plugged() != 0;
        long now = sample.elapsedMs();
        int resetLowAt = hysteresisResetLevel(lowThreshold);

        if (!phoneSeeded) {
            phoneSeeded = true;
            phoneWasPlugged = plugged;
            PhoneFullKind initialFull = evaluateFullKindLocked(sample, now);
            if (plugged) {
                phoneFullNotifiedForPlug = initialFull != null;
                phoneSawNotFullInPlugSession = initialFull == null;
            } else {
                phoneFullNotifiedForPlug = false;
                phoneSawNotFullInPlugSession = false;
            }
            phoneLowNotified = !plugged && level <= lowThreshold;
            return null;
        }

        if (plugged) {
            if (!phoneWasPlugged) {
                // Fresh plug-in session.
                phoneWasPlugged = true;
                phoneFullNotifiedForPlug = false;
                phoneSawNotFullInPlugSession = false;
                colorosLimitReachedSinceMs = -1L;
                genericNotChargingSinceMs = -1L;
            }
            phoneLowNotified = false;

            PhoneFullKind fullKind = evaluateFullKindLocked(sample, now);
            if (fullKind == null) {
                phoneSawNotFullInPlugSession = true;
                return null;
            }
            if (!phoneFullNotifiedForPlug && phoneSawNotFullInPlugSession) {
                phoneFullNotifiedForPlug = true;
                if (fullAlertEnabled) {
                    return PhoneAlert.full(fullKind, level);
                }
            }
            return null;
        } else {
            // Unplugged.
            phoneWasPlugged = false;
            phoneFullNotifiedForPlug = false;
            phoneSawNotFullInPlugSession = false;
            colorosLimitReachedSinceMs = -1L;
            genericNotChargingSinceMs = -1L;

            if (level >= resetLowAt) {
                phoneLowNotified = false;
                return null;
            }
            if (level >= 1 && level <= lowThreshold && !phoneLowNotified) {
                phoneLowNotified = true;
                if (lowAlertEnabled) {
                    return PhoneAlert.low(level);
                }
            }
            return null;
        }
    }

    /**
     * Evaluates a watch status update against the enabled watch-full and watch-low switches.
     * Returns null when no alert should fire.
     */
    public synchronized WatchAlert onWatchSample(
            boolean connected,
            int level,
            boolean charging,
            long nowElapsedMs,
            boolean fullAlertEnabled,
            boolean lowAlertEnabled,
            int lowThreshold) {
        if (!connected || level < 0 || level > 100) {
            return null;
        }
        int resetLowAt = hysteresisResetLevel(lowThreshold);

        if (!watchSeeded) {
            watchSeeded = true;
            watchWasCharging = charging;
            watchFullNotifiedForCharge = charging && level >= 100;
            watchSawBelowFullInChargeSession = charging && level < 100;
            watchLowNotified = !charging && level <= lowThreshold;
            return null;
        }

        if (charging) {
            if (!watchWasCharging) {
                watchWasCharging = true;
                watchFullNotifiedForCharge = false;
                watchSawBelowFullInChargeSession = level < 100;
            }
            watchLowNotified = false;
            if (level < 100) {
                watchSawBelowFullInChargeSession = true;
                if (level <= 98) {
                    watchFullNotifiedForCharge = false;
                }
                return null;
            }
            // level >= 100 while charging
            boolean suppressedByNative = lastNativeWatchFullElapsedMs >= 0L
                    && nowElapsedMs >= lastNativeWatchFullElapsedMs
                    && (nowElapsedMs - lastNativeWatchFullElapsedMs) < NATIVE_WATCH_FULL_DEDUP_MS;
            if (!watchFullNotifiedForCharge && watchSawBelowFullInChargeSession && !suppressedByNative) {
                watchFullNotifiedForCharge = true;
                if (fullAlertEnabled) {
                    return WatchAlert.full(level);
                }
            }
            return null;
        } else {
            watchWasCharging = false;
            watchSawBelowFullInChargeSession = false;
            if (level <= 98) {
                watchFullNotifiedForCharge = false;
            }
            if (level >= resetLowAt) {
                watchLowNotified = false;
                return null;
            }
            if (level >= 1 && level <= lowThreshold && !watchLowNotified) {
                watchLowNotified = true;
                if (lowAlertEnabled) {
                    return WatchAlert.low(level);
                }
            }
            return null;
        }
    }

    /**
     * Returns a pending delay in ms if a sustained-cap timer is active and waiting to mature,
     * or -1 if no delayed re-check is needed.
     */
    public synchronized long pendingSustainCheckDelayMs(long nowElapsedMs) {
        long best = -1L;
        if (colorosLimitReachedSinceMs >= 0L) {
            long rem = COLOROS_LIMIT_SUSTAIN_MS - Math.max(0L, nowElapsedMs - colorosLimitReachedSinceMs);
            if (rem > 0L) best = rem;
        }
        if (genericNotChargingSinceMs >= 0L) {
            long rem = GENERIC_NOT_CHARGING_SUSTAIN_MS - Math.max(0L, nowElapsedMs - genericNotChargingSinceMs);
            if (rem > 0L && (best < 0L || rem < best)) best = rem;
        }
        return best;
    }

    private PhoneFullKind evaluateFullKindLocked(PhoneSample sample, long now) {
        if (sample.overheat() || sample.bypassCharging() || sample.smartChargeProtecting()) {
            colorosLimitReachedSinceMs = -1L;
            genericNotChargingSinceMs = -1L;
            return null;
        }
        int level = sample.level();
        int status = sample.status();
        boolean validRegularLimit = sample.regularLimitEnabled()
                && sample.regularLimitLevel() >= MIN_REGULAR_LIMIT_LEVEL
                && sample.regularLimitLevel() <= MAX_REGULAR_LIMIT_LEVEL;

        // 1) ColorOS explicit charging limit (e.g. 80/85/90/95%).
        if (validRegularLimit && level >= sample.regularLimitLevel()) {
            boolean explicitStop = status == STATUS_FULL
                    || status == STATUS_NOT_CHARGING
                    || sample.regularLimitActive()
                    || sample.chargeProtectionActive();
            if (explicitStop) {
                colorosLimitReachedSinceMs = -1L;
                genericNotChargingSinceMs = -1L;
                return PhoneFullKind.COLOROS_CHARGE_LIMIT;
            }
            if (colorosLimitReachedSinceMs < 0L) {
                colorosLimitReachedSinceMs = now;
            } else if (now >= colorosLimitReachedSinceMs
                    && (now - colorosLimitReachedSinceMs) >= COLOROS_LIMIT_SUSTAIN_MS) {
                colorosLimitReachedSinceMs = -1L;
                genericNotChargingSinceMs = -1L;
                return PhoneFullKind.COLOROS_CHARGE_LIMIT;
            }
            return null;
        } else {
            colorosLimitReachedSinceMs = -1L;
        }

        // 2) Standard 100% / BATTERY_STATUS_FULL.
        if (level >= 100 || status == STATUS_FULL) {
            genericNotChargingSinceMs = -1L;
            return PhoneFullKind.STANDARD_100;
        }

        // 3) Generic third-party / kernel charge-cap fallback: NOT_CHARGING at >= 80% sustained.
        if (status == STATUS_NOT_CHARGING && level >= GENERIC_CAP_MIN_LEVEL) {
            if (genericNotChargingSinceMs < 0L) {
                genericNotChargingSinceMs = now;
            } else if (now >= genericNotChargingSinceMs
                    && (now - genericNotChargingSinceMs) >= GENERIC_NOT_CHARGING_SUSTAIN_MS) {
                genericNotChargingSinceMs = -1L;
                return PhoneFullKind.GENERIC_CHARGE_LIMIT;
            }
        } else {
            genericNotChargingSinceMs = -1L;
        }
        return null;
    }
}
