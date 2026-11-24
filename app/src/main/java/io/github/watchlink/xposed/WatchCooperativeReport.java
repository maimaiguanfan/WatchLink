package io.github.watchlink.xposed;

/** Classifies the narrow two-field cooperative report, without copying its session identifier. */
final class WatchCooperativeReport {
    static int action(byte[] frame) {
        if (frame == null || frame.length != 11 || frame[0] != 8 || frame[1] != 10
                || frame[2] != 1 || frame[3] != 4 || frame[4] < 0
                || frame[8] != 2 || frame[9] != 1) return 0;
        int action = frame[10];
        return action == 1 || action == 2 ? action : 0;
    }

    private WatchCooperativeReport() {}
}
