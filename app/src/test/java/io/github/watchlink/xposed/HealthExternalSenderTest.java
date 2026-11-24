package io.github.watchlink.xposed;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class HealthExternalSenderTest {
    interface FakeMgr {}
    static class Provider330 {
        public static FakeMgr d(Context c) { return null; }
        public static void other(Context c) {}
    }
    static class Provider300 {
        public static FakeMgr e(Context c) { return null; }
    }
    static class AmbiguousProvider {
        public static FakeMgr d(Context c) { return null; }
        public static FakeMgr e(Context c) { return null; }
    }

    @Test public void openPayloadMatchesHuaweiService8Command9TlvLayout() {
        byte[] payload = HealthExternalSender.encodeOpenPayload(0x50000001, "早班", 5);
        byte[] title = "早班".getBytes(StandardCharsets.UTF_8);
        // Tag 1 (6B) + Tag 2 (2 + 6B) + Tag 4 (3B) = 17B
        assertEquals(6 + 2 + title.length + 3, payload.length);
        assertArrayEquals(new byte[]{0x01, 0x04, 0x50, 0x00, 0x00, 0x01},
                java.util.Arrays.copyOfRange(payload, 0, 6));
        assertEquals(0x02, payload[6]);
        assertEquals(title.length, payload[7]);
        assertArrayEquals(title, java.util.Arrays.copyOfRange(payload, 8, 8 + title.length));
        int snoozeOffset = 8 + title.length;
        assertArrayEquals(new byte[]{0x04, 0x01, 0x05},
                java.util.Arrays.copyOfRange(payload, snoozeOffset, snoozeOffset + 3));
    }

    @Test public void openPayloadOmitsInvalidSnoozeAndEmptyTitle() {
        byte[] payload = HealthExternalSender.encodeOpenPayload(7, "", 0);
        assertArrayEquals(new byte[]{0x01, 0x04, 0x00, 0x00, 0x00, 0x07}, payload);
    }

    @Test public void closePayloadMatchesService8Command10Type1WireShape() {
        byte[] payload = HealthExternalSender.encodeClosePayload(0x01020304);
        assertArrayEquals(new byte[]{0x01, 0x04, 0x01, 0x02, 0x03, 0x04, 0x02, 0x01, 0x01}, payload);
        // Prepending service=8, cmd=10 yields the exact 11-byte frame recognized by WatchCooperativeReport.
        byte[] frame = new byte[11];
        frame[0] = 8;
        frame[1] = 10;
        System.arraycopy(payload, 0, frame, 2, payload.length);
        assertEquals(1, WatchCooperativeReport.action(frame));
    }

    @Test public void resolvesManagerFactoryAcrossHealth300And330WithoutHardcodingName() throws Exception {
        assertEquals(Provider330.class.getDeclaredMethod("d", Context.class),
                HealthExternalSender.findManagerFactory(Provider330.class, FakeMgr.class));
        assertEquals(Provider300.class.getDeclaredMethod("e", Context.class),
                HealthExternalSender.findManagerFactory(Provider300.class, FakeMgr.class));
        assertNull(HealthExternalSender.findManagerFactory(AmbiguousProvider.class, FakeMgr.class));
    }
}
