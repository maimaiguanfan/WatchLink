package io.github.watchlink;

import android.app.Application;
import android.os.UserManager;
import io.github.watchlink.diagnostics.DiagnosticJournal;

/** Settings and diagnostics owner. Never runs background services or boot hooks. */
public final class WatchLinkApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        UserManager users = getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()) return;
        DiagnosticJournal.install(this);
        DiagnosticJournal.record("OWNER_STARTED");
    }
}
