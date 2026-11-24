package io.github.watchlink.alarm.probe;

import android.util.Log;
import io.github.watchlink.diagnostics.DiagnosticJournal;

/** Never pass exception messages, alarm data, callback objects, URIs or payloads here. */
public final class ProbeLog {
    private ProbeLog() { }
    public static void info(String message) {
        Log.i("WatchLink", message);
        DiagnosticJournal.record(message);
    }
    public static void failure(String operation, Throwable error) {
        String message = operation + " type=" + error.getClass().getSimpleName();
        Log.w("WatchLink", message);
        DiagnosticJournal.record(message);
    }
}
