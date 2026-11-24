package io.github.watchlink.xposed;

/** One native reminder, scoped to the selected watch. All access is serialized by its receiver. */
final class CooperativeSessionMatch {
    private final String device;
    private final int reminder;
    private final boolean closeOnly;
    private boolean outbound;
    private boolean answered;

    CooperativeSessionMatch(String device, int reminder, boolean closeOnly) {
        if (device == null || device.isEmpty() || reminder <= 0) throw new IllegalArgumentException();
        this.device = device;
        this.reminder = reminder;
        this.closeOnly = closeOnly;
    }

    String observe(boolean received, String source, byte[] frame) {
        if (answered || !device.equals(source) || frame == null || frame.length < 8
                || frame[0] != 8 || frame[2] != 1 || frame[3] != 4) return null;
        int identity = (frame[4] & 255) << 24 | (frame[5] & 255) << 16
                | (frame[6] & 255) << 8 | (frame[7] & 255);
        if (identity != reminder) return null;
        if (closeOnly) {
            // In the outbound type=1 command Health encodes the same two TLVs as a reply.
            if (received || WatchCooperativeReport.action(frame) != 1) return null;
            answered = true;
            return "CLOSE_OUTBOUND";
        }
        if (!received) {
            if (outbound || frame[1] != 9) return null;
            outbound = true;
            return "OUTBOUND";
        }
        if (!outbound) return null;
        int action = WatchCooperativeReport.action(frame);
        if (action == 0) return null;
        answered = true;
        return action == 1 ? "SNOOZE" : "DISMISS";
    }
}
