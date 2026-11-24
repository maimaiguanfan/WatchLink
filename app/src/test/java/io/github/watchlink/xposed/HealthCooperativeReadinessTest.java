package io.github.watchlink.xposed;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HealthCooperativeReadinessTest {
    @Test public void decodesOnlyCapability200InTheNativeOrder() {
        assertEquals(Boolean.TRUE, HealthCooperativeReadiness.capability200("FE ".repeat(25) + "a1"));
        assertEquals(Boolean.FALSE, HealthCooperativeReadiness.capability200("ff".repeat(25) + "fE" + "01"));
        assertEquals(Boolean.FALSE, HealthCooperativeReadiness.capability200("ff".repeat(25)));
        assertNull(HealthCooperativeReadiness.capability200(null));
        assertNull(HealthCooperativeReadiness.capability200(" "));
        assertNull(HealthCooperativeReadiness.capability200("fff"));
        assertNull(HealthCooperativeReadiness.capability200("Z0".repeat(25) + "01"));
    }

    @Test public void onlyExplicitBooleanRowsDescribeTheSwitch() {
        assertEquals("READY_SUPPORTED_ON", HealthCooperativeReadiness.switchState("true"));
        assertEquals("READY_SUPPORTED_OFF", HealthCooperativeReadiness.switchState("false"));
        assertEquals("READY_UNKNOWN", HealthCooperativeReadiness.switchState(null));
        assertEquals("READY_UNKNOWN", HealthCooperativeReadiness.switchState("1"));
        assertEquals("READY_SUPPORTED_ON", new HealthCooperativeReadiness.Result("READY_SUPPORTED_ON", "private").toString());
    }
}
