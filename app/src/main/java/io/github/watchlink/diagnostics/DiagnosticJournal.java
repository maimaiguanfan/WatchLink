package io.github.watchlink.diagnostics;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;
import io.github.watchlink.BuildConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Each participant writes only its own no-backup directory. Hook callbacks only enqueue allowlisted metadata. */
public final class DiagnosticJournal {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WatchLink-Diagnostics");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile DiagnosticJournal current;

    private final AtomicFile file;
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private boolean scheduled;

    private DiagnosticJournal(Context app) {
        file = new AtomicFile(new File(app.getNoBackupFilesDir(), BuildConfig.APPLICATION_ID + "-diagnostics-v1.txt"));
    }

    public static synchronized void install(Context app) {
        if (current != null) return;
        try { current = new DiagnosticJournal(app); }
        catch (RuntimeException unavailable) { Log.w("WatchLink", "DIAGNOSTIC_STORAGE_UNAVAILABLE"); }
    }

    public static void record(String message) {
        DiagnosticJournal store = current;
        if (store == null) return;
        String event = DiagnosticEvents.event(message);
        if (event != null) store.offer(System.currentTimeMillis() + " " + event);
    }

    private void offer(String row) {
        synchronized (pending) {
            DiagnosticEvents.append(pending, row);
            if (scheduled) return;
            scheduled = true;
        }
        IO.execute(() -> {
            synchronized (pending) { scheduled = false; }
            try { flush(); }
            catch (IOException | RuntimeException unavailable) { Log.w("WatchLink", "DIAGNOSTIC_STORAGE_UNAVAILABLE"); }
        });
    }

    static String snapshot() throws IOException {
        DiagnosticJournal store = current;
        if (store == null) throw new IOException("No diagnostic journal");
        return store.flush();
    }

    static void onWorker(Runnable task) { IO.execute(task); }

    private synchronized String flush() throws IOException {
        ArrayDeque<String> rows;
        try (java.io.FileInputStream input = file.openRead()) {
            long length = input.getChannel().size();
            if (length <= DiagnosticEvents.MAX_TEXT) {
                byte[] bytes = new byte[(int) length];
                new java.io.DataInputStream(input).readFully(bytes);
                rows = DiagnosticEvents.read(new String(bytes, StandardCharsets.UTF_8));
            } else rows = new ArrayDeque<>();
        } catch (java.io.FileNotFoundException absent) {
            rows = new ArrayDeque<>();
        }
        ArrayDeque<String> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return String.join("\n", rows);
            batch = new ArrayDeque<>(pending);
            pending.clear();
        }
        for (String row : batch) DiagnosticEvents.append(rows, row);
        String text = String.join("\n", rows);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(text.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException | RuntimeException failed) {
            file.failWrite(out);
            synchronized (pending) {
                for (String row : pending) DiagnosticEvents.append(batch, row);
                pending.clear();
                pending.addAll(batch);
            }
            throw failed;
        }
        return text;
    }
}
