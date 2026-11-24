package io.github.watchlink.validation;

import io.github.watchlink.BuildConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class HuaweiReminderServiceTest {
    @Test public void endpointRequiresTrustedUnstoppedHealthWithExportedService() {
        assertTrue(new HuaweiReminderService.HealthEndpoint(true, true, false).ready());
        assertFalse(new HuaweiReminderService.HealthEndpoint(false, true, false).ready());
        assertFalse(new HuaweiReminderService.HealthEndpoint(true, false, false).ready());
        assertFalse(new HuaweiReminderService.HealthEndpoint(true, true, true).ready());
    }

    @Test public void dailyIdentifierIsScopedToUnifiedApplicationId() {
        assertEquals("io.github.maimaiguanfan.watchlink", BuildConfig.APPLICATION_ID);
        assertEquals("io.github.maimaiguanfan.watchlink.daily-run-42-1",
                HuaweiReminderService.dailyIdentifier("run", 42, 1));
    }

    @Test public void wireWindowAndRunValidationRejectInvalidValues() {
        assertTrue(DailyAlarmWire.isRun("596ac455-8d9b-4d99-a180-99fa57c8da46"));
        assertFalse(DailyAlarmWire.isRun("not-a-uuid"));
        assertFalse(DailyAlarmWire.isRun(null));
        assertTrue(DailyAlarmWire.inWindow(10_000, 5_000, 15_000, 24_999, 19_999));
        assertFalse(DailyAlarmWire.inWindow(10_000, 5_000, 15_000, 25_000, 19_999));
        assertFalse(DailyAlarmWire.inWindow(10_000, 5_000, 15_000, 9_999, 5_000));
        assertFalse(DailyAlarmWire.inWindow(10_000, 5_000, 15_000, 10_000, 4_999));
    }
}
