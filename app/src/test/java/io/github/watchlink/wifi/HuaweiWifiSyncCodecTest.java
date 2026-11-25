package io.github.watchlink.wifi;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class HuaweiWifiSyncCodecTest {
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toUpperCase();
    }

    @Test public void encodesPskPayloadWithAndWithoutTag4MatchingHuaweiLoai() {
        WifiNetworkRecord record = new WifiNetworkRecord(
                "MyWiFi", "pass1234", WifiNetworkRecord.ENCODE_PSK,
                "[WPA2-PSK-CCMP][ESS]", false, 0L);

        byte[] legacy = HuaweiWifiSyncCodec.encodePayload(record, false);
        // Tag 1 ("MyWiFi"=6B), Tag 2 ("pass1234"=8B), Tag 3 (1B=0x02)
        String expectedLegacy = "0106" + toHex("MyWiFi".getBytes(StandardCharsets.UTF_8))
                + "0208" + toHex("pass1234".getBytes(StandardCharsets.UTF_8))
                + "030102";
        assertEquals(expectedLegacy, toHex(legacy));

        byte[] modern = HuaweiWifiSyncCodec.encodePayload(record, true);
        String expectedModern = expectedLegacy
                + "0414" + toHex("[WPA2-PSK-CCMP][ESS]".getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedModern, toHex(modern));
    }

    @Test public void encodesOpenPasswordlessNetworkWithEmptyTag2AndEssTag4() {
        WifiNetworkRecord open = new WifiNetworkRecord(
                "Cafe", "", WifiNetworkRecord.ENCODE_OPEN, "[ESS]", false, 0L);

        byte[] payload = HuaweiWifiSyncCodec.encodePayload(open, true);
        String expected = "0104" + toHex("Cafe".getBytes(StandardCharsets.UTF_8))
                + "0200"
                + "030100"
                + "0405" + toHex("[ESS]".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, toHex(payload));
    }

    @Test public void encodesSaeNetworkWithEncodeType7AndSanitizesOverlongCapabilities() {
        String hugeCaps = "[WPA2-PSK+FT/PSK-CCMP][RSN-PSK+SAE+FT/SAE-CCMP][WPA-PSK-TKIP][ESS]"
                + "[BLE][UTF-8][EXTRA-VENDOR-TAG-0123456789][SECOND-VENDOR-TAG-0123456789][THIRD-TAG-0123456789]";
        WifiNetworkRecord sae = new WifiNetworkRecord(
                "SaeNet", "sae-pass-123", WifiNetworkRecord.ENCODE_SAE, hugeCaps, false, 0L);
        assertTrue(sae.capabilities().getBytes(StandardCharsets.UTF_8).length <= 127);
        assertTrue(sae.capabilities().endsWith("]"));

        byte[] payload = HuaweiWifiSyncCodec.encodePayload(sae, true);
        String hex = toHex(payload);
        assertTrue(hex.contains("030107"));
    }

    @Test public void decodesWatch147AckSuccessAndFailureFrames() {
        // 01 2F 7F 04 00 01 86 A0 => Service 1, Command 47, Tag 0x7F, Len 4, Value 100000
        byte[] okFrame = new byte[]{0x01, 0x2F, 0x7F, 0x04, 0x00, 0x01, (byte) 0x86, (byte) 0xA0};
        HuaweiWifiSyncCodec.Ack ok = HuaweiWifiSyncCodec.decodeAck(okFrame);
        assertTrue(ok.valid());
        assertTrue(ok.success());
        assertEquals(100_000, ok.code());

        byte[] failFrame = new byte[]{0x01, 0x2F, 0x7F, 0x04, 0x00, 0x01, (byte) 0x86, (byte) 0xA1};
        HuaweiWifiSyncCodec.Ack fail = HuaweiWifiSyncCodec.decodeAck(failFrame);
        assertTrue(fail.valid());
        assertFalse(fail.success());
        assertEquals(100_001, fail.code());

        byte[] otherService = new byte[]{0x08, 0x09, 0x01, 0x04, 0x00, 0x00, 0x00, 0x01};
        assertFalse(HuaweiWifiSyncCodec.decodeAck(otherService).valid());
        assertFalse(HuaweiWifiSyncCodec.decodeAck(new byte[]{0x01, 0x2F}).valid());
    }

    @Test public void decodesExpandCapabilityBit241Accurately() {
        // 30 zero bytes (60 hex chars) + byte 30
        String prefix = "00".repeat(30);
        assertEquals(Boolean.TRUE, HuaweiWifiSyncCodec.capability241(prefix + "02"));
        assertEquals(Boolean.TRUE, HuaweiWifiSyncCodec.capability241(prefix + "FF"));
        assertEquals(Boolean.FALSE, HuaweiWifiSyncCodec.capability241(prefix + "01")); // bit 240 only
        assertEquals(Boolean.FALSE, HuaweiWifiSyncCodec.capability241(prefix + "FD")); // bit 241 clear
        assertEquals(Boolean.FALSE, HuaweiWifiSyncCodec.capability241("00".repeat(30))); // too short
        assertNull(HuaweiWifiSyncCodec.capability241(""));
        assertNull(HuaweiWifiSyncCodec.capability241("ZZ"));
    }
}
