package io.github.watchlink.wifi;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Pure TLV codec for Huawei Health Wi-Fi sync protocol (ServiceID=1, CommandID=47 / 0x2F).
 * Byte-for-byte compatible with Huawei Health 17.0.10.330 Loai.d / Loai.b / Loai.getResult.
 */
public final class HuaweiWifiSyncCodec {
    public static final int SERVICE_ID = 1;
    public static final int COMMAND_ID = 47;
    public static final int ACK_SUCCESS = 100_000; // 0x000186A0

    public record Ack(boolean valid, boolean success, int code) {
        public static final Ack INVALID = new Ack(false, false, -1);
    }

    private HuaweiWifiSyncCodec() {}

    /**
     * Encodes the 1/47 TLV payload matching Loai.d(deviceId, ssid, password, encodeType, capabilities):
     * - Tag 1: SSID UTF-8 bytes
     * - Tag 2: Password UTF-8 bytes (02 00 for open passwordless Wi-Fi)
     * - Tag 3: 1-byte encodeType (0=OPEN, 1=WEP, 2=PSK, 3=EAP, 7=SAE)
     * - Tag 4 (when ExpandCapability bit 241 is true): wifiModeCapabilities UTF-8 bytes
     */
    public static byte[] encodePayload(WifiNetworkRecord record, boolean includeCapabilitiesTag4) {
        if (record == null || !record.isSupportedByWatch()) {
            throw new IllegalArgumentException("Unsupported Wi-Fi record");
        }
        byte[] ssidBytes = record.ssid().getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes = record.password().getBytes(StandardCharsets.UTF_8);
        if (ssidBytes.length == 0 || ssidBytes.length > 127) {
            throw new IllegalArgumentException("SSID byte length out of bounds");
        }
        if (passwordBytes.length > 127) {
            throw new IllegalArgumentException("Password byte length out of bounds");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(96);
        writeTlv(out, 0x01, ssidBytes);
        writeTlv(out, 0x02, passwordBytes);
        out.write(0x03);
        out.write(0x01);
        out.write(record.encodeType() & 0xFF);

        if (includeCapabilitiesTag4) {
            String caps = WifiNetworkRecord.sanitizeCapabilities(record.capabilities(), record.encodeType());
            byte[] capBytes = caps.getBytes(StandardCharsets.UTF_8);
            if (capBytes.length == 0 || capBytes.length > 127) {
                throw new IllegalArgumentException("Capabilities byte length out of bounds");
            }
            writeTlv(out, 0x04, capBytes);
        }
        return out.toByteArray();
    }

    private static void writeTlv(ByteArrayOutputStream out, int tag, byte[] value) {
        out.write(tag & 0xFF);
        out.write(value.length & 0xFF);
        if (value.length > 0) {
            out.write(value, 0, value.length);
        }
    }

    /**
     * Decodes a watch response frame for ServiceID=1 / CommandID=47 (matching Loai.b([B) -> Loai.d(String)).
     * Expected format: [0x01, 0x2F, <tag (typically 0x7F)>, <len>, <big-endian status int>].
     */
    public static Ack decodeAck(byte[] frame) {
        if (frame == null || frame.length < 4) return Ack.INVALID;
        if ((frame[0] & 0xFF) != SERVICE_ID || (frame[1] & 0xFF) != COMMAND_ID) {
            return Ack.INVALID;
        }
        int pos = 2;
        int firstValue = -1;
        boolean hasFirst = false;
        while (pos < frame.length) {
            int tag = frame[pos++] & 0xFF;
            if (pos >= frame.length) return Ack.INVALID;
            int len = 0;
            int shift = 0;
            while (pos < frame.length) {
                int b = frame[pos++] & 0xFF;
                len = (len << 7) | (b & 0x7F);
                shift++;
                if ((b & 0x80) == 0) break;
                if (shift >= 3) return Ack.INVALID;
            }
            if (len < 0 || pos + len > frame.length) return Ack.INVALID;
            if (len >= 1 && len <= 4) {
                int value = 0;
                for (int i = 0; i < len; i++) {
                    value = (value << 8) | (frame[pos + i] & 0xFF);
                }
                if (tag == 0x7F) {
                    return new Ack(true, value == ACK_SUCCESS, value);
                }
                if (!hasFirst) {
                    firstValue = value;
                    hasFirst = true;
                }
            }
            pos += len;
        }
        if (hasFirst) {
            return new Ack(true, firstValue == ACK_SUCCESS, firstValue);
        }
        return Ack.INVALID;
    }

    /**
     * Checks Huawei DeviceInfo.getExpandCapability() bit 241 ("isSupportNewWifiCapability").
     * Byte index = 241 / 8 = 30 (hex chars 60..61), bit mask = 1 << (241 % 8) = 0x02.
     */
    public static Boolean capability241(String expandedHex) {
        if (expandedHex == null || expandedHex.isEmpty() || expandedHex.length() > 65_536) return null;
        String hex = expandedHex.replace(" ", "");
        if (hex.isEmpty() || (hex.length() & 1) != 0 || !hex.matches("[a-fA-F0-9]+")) return null;
        return hex.length() >= 62 && (Character.digit(hex.charAt(61), 16) & 2) != 0;
    }
}
