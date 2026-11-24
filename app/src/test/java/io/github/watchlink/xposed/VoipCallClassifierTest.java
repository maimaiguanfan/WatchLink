package io.github.watchlink.xposed;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoipCallClassifierTest {
    @Test public void excludesWeChatSystemDialersAndCoreHosts() {
        assertFalse(VoipCallClassifier.isEligibleVoipPackage(null));
        assertFalse(VoipCallClassifier.isEligibleVoipPackage(""));
        assertFalse(VoipCallClassifier.isEligibleVoipPackage("com.tencent.mm"));
        assertFalse(VoipCallClassifier.isEligibleVoipPackage("com.android.server.telecom"));
        assertFalse(VoipCallClassifier.isEligibleVoipPackage("com.android.incallui"));
        assertFalse(VoipCallClassifier.isEligibleVoipPackage("com.oneplus.dialer"));
        assertFalse(VoipCallClassifier.isEligibleVoipPackage("com.coloros.alarmclock"));
        assertFalse(VoipCallClassifier.isEligibleVoipPackage("com.coloros.calendar"));
        assertFalse(VoipCallClassifier.isEligibleVoipPackage("com.huawei.health"));

        assertTrue(VoipCallClassifier.isEligibleVoipPackage("org.telegram.messenger"));
        assertTrue(VoipCallClassifier.isEligibleVoipPackage("com.whatsapp"));
        assertTrue(VoipCallClassifier.isEligibleVoipPackage("com.tencent.mobileqq"));
        assertTrue(VoipCallClassifier.isEligibleVoipPackage("com.tencent.wework"));
        assertTrue(VoipCallClassifier.isEligibleVoipPackage("com.ss.android.lark"));
        assertTrue(VoipCallClassifier.isEligibleVoipPackage("com.alibaba.android.rimet"));
    }

    @Test public void classifiesCallStyleIncomingAndOngoingNotifications() {
        String decline = "DECLINE_PI";
        String hangUp = "HANGUP_PI";

        var incoming = VoipCallClassifier.classifyMetadata(
                "call", "android.app.Notification$CallStyle", 2, true, decline, null, List.of());
        assertEquals(VoipCallClassifier.Kind.INCOMING_CALL_STYLE, incoming.kind());
        assertTrue(incoming.isIncoming());
        assertEquals(decline, incoming.rejectIntent());

        var ongoing = VoipCallClassifier.classifyMetadata(
                "call", "android.app.Notification$CallStyle", 2, false, null, hangUp, List.of());
        assertEquals(VoipCallClassifier.Kind.ONGOING, ongoing.kind());
        assertFalse(ongoing.isIncoming());
        assertNull(ongoing.rejectIntent());
    }

    @Test public void classifiesCategoryCallFallbackForChineseAndEnglishActions() {
        String decline = "DECLINE_PI";
        String answer = "ANSWER_PI";

        var chineseIncoming = VoipCallClassifier.classifyMetadata(
                "call", null, 2, true, null, null,
                List.of(
                        new VoipCallClassifier.ActionSpec<>("接听", 0, answer),
                        new VoipCallClassifier.ActionSpec<>("拒绝", 0, decline)
                ));
        assertEquals(VoipCallClassifier.Kind.INCOMING_CATEGORY_CALL, chineseIncoming.kind());
        assertTrue(chineseIncoming.isIncoming());
        assertEquals(decline, chineseIncoming.rejectIntent());

        var englishIncoming = VoipCallClassifier.classifyMetadata(
                "call", null, 2, true, null, null,
                List.of(
                        new VoipCallClassifier.ActionSpec<>("Decline", 0, decline),
                        new VoipCallClassifier.ActionSpec<>("Answer", 0, answer)
                ));
        assertEquals(VoipCallClassifier.Kind.INCOMING_CATEGORY_CALL, englishIncoming.kind());
        assertEquals(decline, englishIncoming.rejectIntent());

        var singleHangupOngoing = VoipCallClassifier.classifyMetadata(
                "call", null, 2, false, null, null,
                List.of(new VoipCallClassifier.ActionSpec<>("挂断", 0, decline)));
        assertEquals(VoipCallClassifier.Kind.ONGOING, singleHangupOngoing.kind());
        assertFalse(singleHangupOngoing.isIncoming());

        var nonCallMsg = VoipCallClassifier.classifyMetadata(
                "msg", null, 0, false, null, null,
                List.of(new VoipCallClassifier.ActionSpec<>("拒绝", 0, decline),
                        new VoipCallClassifier.ActionSpec<>("接听", 0, answer)));
        assertEquals(VoipCallClassifier.Kind.NONE, nonCallMsg.kind());
    }

    @Test public void scaledDimensionsPreservesAspectRatioAndClampsLongestEdge() {
        assertArrayEquals(new int[]{320, 180}, HealthDaemonBridge.scaledDimensions(1920, 1080, 320));
        assertArrayEquals(new int[]{180, 320}, HealthDaemonBridge.scaledDimensions(1080, 1920, 320));
        assertArrayEquals(new int[]{200, 150}, HealthDaemonBridge.scaledDimensions(200, 150, 320));
    }
}
