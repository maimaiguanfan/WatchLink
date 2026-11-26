package io.github.watchlink.xposed;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Pure-JVM parser and validator for ADB-triggered custom watch protocol debug commands.
 * Supports classic DeviceCommand (e.g. 5.49.5 / 5.1.12) and WearEngine SampleEvent payloads.
 */
final class RawCommandRequest {
    static final String MODE_DEVICE_COMMAND = "device_command";
    static final String MODE_SAMPLE_EVENT = "sample_event";
    static final int DEFAULT_WAIT_MS = 1_500;
    static final int MAX_WAIT_MS = 5_000;
    static final int MAX_PAYLOAD_BYTES = 2_048;
    static final int MAX_JSON_BYTES = 4_096;

    record Parsed(
            String mode,
            int serviceId,
            int commandId,
            String srcPkg,
            String wearPkg,
            long eventId,
            int eventLevel,
            byte[] payload,
            int waitMs,
            String error) {

        boolean valid() {
            return error == null || error.isEmpty();
        }

        static Parsed fail(String reason) {
            return new Parsed("", 0, 0, "", "", 0L, 0, new byte[0], 0, reason);
        }

        String descriptor() {
            if (MODE_SAMPLE_EVENT.equals(mode)) {
                return "sample_event:" + srcPkg + "->" + wearPkg + "/cmd=" + commandId + "/eid=" + eventId;
            }
            return "5." + serviceId + "." + commandId;
        }
    }

    static Parsed parse(
            String rawMode,
            String code,
            int serviceId,
            int commandId,
            String hex,
            String json,
            String srcPkg,
            String wearPkg,
            long eventId,
            int eventLevel,
            int waitMs) {
        String mode = rawMode == null || rawMode.trim().isEmpty()
                ? MODE_DEVICE_COMMAND : rawMode.trim().toLowerCase(Locale.ROOT);
        int boundedWait = Math.max(0, Math.min(MAX_WAIT_MS, waitMs < 0 ? DEFAULT_WAIT_MS : waitMs));

        if (MODE_DEVICE_COMMAND.equals(mode)) {
            int sid = serviceId;
            int cid = commandId;
            if (code != null && !code.trim().isEmpty()) {
                int[] sc = parseServiceCommandCode(code);
                if (sc == null) return Parsed.fail("INVALID_CODE");
                sid = sc[0];
                cid = sc[1];
            }
            if (sid < 1 || sid > 255 || cid < 1 || cid > 255) {
                return Parsed.fail("INVALID_SERVICE_OR_COMMAND");
            }
            byte[] data = decodeHex(hex);
            if (data == null) return Parsed.fail("INVALID_HEX");
            if (data.length > MAX_PAYLOAD_BYTES) return Parsed.fail("PAYLOAD_TOO_LARGE");
            return new Parsed(MODE_DEVICE_COMMAND, sid, cid, "", "", 0L, 0, data, boundedWait, "");
        }

        if (MODE_SAMPLE_EVENT.equals(mode)) {
            String src = srcPkg == null ? "" : srcPkg.trim();
            String wear = wearPkg == null ? "" : wearPkg.trim();
            if (src.isEmpty() || wear.isEmpty() || src.length() > 128 || wear.length() > 128) {
                return Parsed.fail("INVALID_PACKAGE");
            }
            int cid = commandId <= 0 ? 2 : commandId;
            if (cid < 1 || cid > 255) return Parsed.fail("INVALID_COMMAND");
            byte[] data;
            if (hex != null && !hex.trim().isEmpty()) {
                data = decodeHex(hex);
                if (data == null) return Parsed.fail("INVALID_HEX");
                if (data.length > MAX_PAYLOAD_BYTES) return Parsed.fail("PAYLOAD_TOO_LARGE");
            } else if (json != null && !json.isEmpty()) {
                data = json.getBytes(StandardCharsets.UTF_8);
                if (data.length > MAX_JSON_BYTES) return Parsed.fail("PAYLOAD_TOO_LARGE");
            } else {
                data = new byte[0];
            }
            return new Parsed(MODE_SAMPLE_EVENT, 0, cid, src, wear, eventId, eventLevel, data, boundedWait, "");
        }

        return Parsed.fail("UNSUPPORTED_MODE");
    }

    /** Parses "5.49.5" or "49.5" into {serviceId, commandId}. Returns null if malformed. */
    static int[] parseServiceCommandCode(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        String[] parts = trimmed.split("\\.");
        int offset;
        if (parts.length == 2) {
            offset = 0;
        } else if (parts.length == 3 && "5".equals(parts[0])) {
            offset = 1;
        } else {
            return null;
        }
        try {
            int service = Integer.parseInt(parts[offset]);
            int command = Integer.parseInt(parts[offset + 1]);
            if (service < 1 || service > 255 || command < 1 || command > 255) return null;
            return new int[]{service, command};
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** Decodes hex string allowing spaces/colons/hyphens and optional 0x prefix. Empty -> byte[0], invalid -> null. */
    static byte[] decodeHex(String raw) {
        if (raw == null) return new byte[0];
        StringBuilder clean = new StringBuilder(raw.length());
        String trimmed = raw.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            trimmed = trimmed.substring(2);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == ' ' || c == ':' || c == '-' || c == '\t' || c == '\n' || c == '\r') continue;
            if (Character.digit(c, 16) < 0) return null;
            clean.append(c);
        }
        if ((clean.length() & 1) != 0) return null;
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static String toHex(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0) return "";
        int limit = Math.min(bytes.length, Math.max(0, maxBytes));
        StringBuilder sb = new StringBuilder(limit * 2 + 8);
        for (int i = 0; i < limit; i++) {
            int b = bytes[i] & 0xff;
            sb.append(Character.forDigit(b >>> 4, 16));
            sb.append(Character.forDigit(b & 0x0f, 16));
        }
        if (bytes.length > limit) {
            sb.append("...(len=").append(bytes.length).append(')');
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private RawCommandRequest() {}
}
