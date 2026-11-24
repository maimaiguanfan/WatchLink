package io.github.watchlink.xposed;

import android.app.Application;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostApplicationReadyTest {
    public static class ClockApp extends Application {
        @Override public void onCreate() {}
    }
    public static class InheritedApp extends ClockApp {}

    @Test public void acceptsDeclaredApplicationLifecycleInHostLoader() throws Exception {
        assertEquals(ClockApp.class.getDeclaredMethod("onCreate"),
                HostApplicationReady.onCreate(ClockApp.class, ClockApp.class.getClassLoader()));
    }

    @Test public void excludesFrameworkOtherLoadersAndUnrelatedClasses() throws Exception {
        assertNull(HostApplicationReady.onCreate(Application.class, Application.class.getClassLoader()));
        assertNull(HostApplicationReady.onCreate(ClockApp.class, new ClassLoader() {}));
        assertNull(HostApplicationReady.onCreate(getClass(), getClass().getClassLoader()));
        assertThrows(NoSuchMethodException.class, () ->
                HostApplicationReady.onCreate(InheritedApp.class, InheritedApp.class.getClassLoader()));
    }
}
