package io.github.watchlink.diagnostics;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class DiagnosticShareTest {
    @Rule public final TemporaryFolder cache = new TemporaryFolder();

    @Test public void zipContainsOneReadableSanitizedReportWithAllSources() throws Exception {
        StringBuilder report = new StringBuilder();
        DiagnosticShare.section(report, "WatchLink", "0.50.0", "1700000000000 OWNER_STARTED", "");
        DiagnosticShare.section(report, "系统时钟", "17.6.100", null, "未收到宿主回复");
        DiagnosticShare.section(report, "华为运动健康（PhoneService）", "17.0.10.330",
                "1700000000001 DAILY_HEALTH_DISMISS run=private-id label=private-label", "");
        File file = DiagnosticShare.write(cache.getRoot(), report.toString());
        assertEquals("shared-diagnostics", file.getParentFile().getName());
        assertTrue(file.getName().endsWith(".zip"));
        try (ZipFile zip = new ZipFile(file)) {
            assertEquals(1, zip.size());
            String text = new String(zip.getInputStream(zip.getEntry("WatchLink.txt")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("[WatchLink] 0.50.0\n2023-11-14T22:13:20Z OWNER_STARTED"));
            assertTrue(text.contains("[系统时钟] 17.6.100\n未收到宿主回复"));
            assertTrue(text.contains("[华为运动健康（PhoneService）] 17.0.10.330\n2023-11-14T22:13:20.001Z DAILY_HEALTH_DISMISS"));
            assertFalse(text.contains("private"));
        }
    }
}
