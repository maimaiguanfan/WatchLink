package io.github.watchlink.wifi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Immutable normalized Wi-Fi network record for Huawei wearable sync (ServiceID 1 / CommandID 47).
 * Never logs or exposes SSID or plaintext password in {@link #toString()}.
 */
public final class WifiNetworkRecord {
    /** Huawei Loan.b(ScanResult) encodeType constants. */
    public static final int ENCODE_OPEN = 0;
    public static final int ENCODE_WEP = 1;
    public static final int ENCODE_PSK = 2;
    public static final int ENCODE_EAP = 3;
    public static final int ENCODE_SAE = 7;

    public static final int MAX_SSID_BYTES = 32;
    public static final int MAX_PASSWORD_BYTES = 127;
    public static final int MAX_CAPABILITIES_BYTES = 127;

    private final String ssid;
    private final String password;
    private final int encodeType;
    private final String capabilities;
    private final boolean hidden;
    private final long lastConnectedMs;

    public WifiNetworkRecord(String ssid, String password, int encodeType, String capabilities,
                             boolean hidden, long lastConnectedMs) {
        if (ssid == null || ssid.isEmpty()) {
            throw new IllegalArgumentException("SSID must not be empty");
        }
        this.ssid = ssid;
        this.password = password == null ? "" : password;
        this.encodeType = normalizeEncodeType(encodeType);
        this.capabilities = sanitizeCapabilities(capabilities, this.encodeType);
        this.hidden = hidden;
        this.lastConnectedMs = Math.max(0L, lastConnectedMs);
    }

    public String ssid() { return ssid; }

    public String password() { return password; }

    public int encodeType() { return encodeType; }

    public String capabilities() { return capabilities; }

    public boolean hidden() { return hidden; }

    public long lastConnectedMs() { return lastConnectedMs; }

    public boolean isOpen() {
        return encodeType == ENCODE_OPEN;
    }

    public boolean isEnterprise() {
        return encodeType == ENCODE_EAP;
    }

    /**
     * Open passwordless Wi-Fi, WEP, WPA/WPA2-PSK, and WPA3-SAE are selectable for watch sync.
     * Enterprise EAP networks require user identities/certificates unsupported by Huawei 1/47 TLV.
     */
    public boolean isSupportedByWatch() {
        byte[] ssidBytes = ssid.getBytes(StandardCharsets.UTF_8);
        if (ssidBytes.length == 0 || ssidBytes.length > 127) return false;
        byte[] passBytes = password.getBytes(StandardCharsets.UTF_8);
        if (passBytes.length > MAX_PASSWORD_BYTES) return false;
        return switch (encodeType) {
            case ENCODE_OPEN -> true;
            case ENCODE_WEP, ENCODE_PSK, ENCODE_SAE -> passBytes.length > 0;
            default -> false;
        };
    }

    public String securityLabel() {
        return switch (encodeType) {
            case ENCODE_OPEN -> "开放无密码";
            case ENCODE_WEP -> "WEP";
            case ENCODE_PSK -> capabilities.contains("SAE") ? "WPA2 / WPA3" : "WPA / WPA2";
            case ENCODE_SAE -> "WPA3";
            case ENCODE_EAP -> "企业认证（不支持）";
            default -> "未知";
        };
    }

    /** Stable dedup key scoped to a watch device token. Contains no reversible plaintext. */
    public String syncFingerprint(String deviceToken) {
        String scope = deviceToken == null ? "" : deviceToken;
        String material = "WatchLink/wifi/v1/" + scope + '\0' + ssid + '\0' + password
                + '\0' + encodeType + '\0' + capabilities;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Stable UI/selection identifier (never exposes password). */
    public String selectionKey() {
        return encodeType + ":" + ssid;
    }

    public static int normalizeEncodeType(int encodeType) {
        return switch (encodeType) {
            case ENCODE_OPEN, ENCODE_WEP, ENCODE_PSK, ENCODE_EAP, ENCODE_SAE -> encodeType;
            default -> ENCODE_OPEN;
        };
    }

    /** Matches Huawei Health Loan.b(ScanResult) priority: WEP -> PSK -> SAE -> EAP -> OPEN. */
    public static int encodeTypeFromCapabilities(String capabilities) {
        if (capabilities == null || capabilities.isEmpty()) return ENCODE_OPEN;
        if (capabilities.contains("WEP") || capabilities.contains("wep")) return ENCODE_WEP;
        if (capabilities.contains("PSK")) return ENCODE_PSK;
        if (capabilities.contains("SAE")) return ENCODE_SAE;
        if (capabilities.contains("EAP")) return ENCODE_EAP;
        return ENCODE_OPEN;
    }

    public static String synthesizeCapabilities(int encodeType, boolean wpa3Transition, boolean owe) {
        return switch (encodeType) {
            case ENCODE_WEP -> "[WEP][ESS]";
            case ENCODE_PSK -> wpa3Transition
                    ? "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]"
                    : "[WPA2-PSK-CCMP][ESS]";
            case ENCODE_SAE -> "[RSN-SAE-CCMP][ESS]";
            case ENCODE_EAP -> "[WPA2-EAP-CCMP][ESS]";
            default -> owe ? "[OWE][ESS]" : "[ESS]";
        };
    }

    /**
     * Ensures non-empty capabilities string within Huawei Loai.d's 1-byte TLV length limit (<=127B).
     * When bit 241 is enabled on the watch, Loai.e drops packets with empty wifiModeCapabilities.
     */
    public static String sanitizeCapabilities(String raw, int encodeType) {
        if (raw == null) return synthesizeCapabilities(encodeType, false, false);
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return synthesizeCapabilities(encodeType, false, false);
        byte[] utf8 = trimmed.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= MAX_CAPABILITIES_BYTES) return trimmed;
        // Trim cleanly at the last complete '[...]' token within limit.
        int cut = Math.min(trimmed.length(), MAX_CAPABILITIES_BYTES);
        while (cut > 0) {
            int close = trimmed.lastIndexOf(']', cut - 1);
            if (close < 0) break;
            String candidate = trimmed.substring(0, close + 1);
            if (candidate.getBytes(StandardCharsets.UTF_8).length <= MAX_CAPABILITIES_BYTES) {
                return candidate;
            }
            cut = close;
        }
        return synthesizeCapabilities(encodeType, false, false);
    }

    /** Strips surrounding double quotes from Android WifiInfo/WifiConfiguration SSID or PSK values. */
    public static String unquote(String raw) {
        if (raw == null) return "";
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    /** Decodes quoted SSID or unquoted hex-encoded SSID (Android WifiConfigStore format). */
    public static String decodeSsid(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return raw.substring(1, raw.length() - 1);
        }
        if ((raw.length() & 1) == 0 && raw.length() <= MAX_SSID_BYTES * 2 && raw.matches("[0-9a-fA-F]+")) {
            byte[] bytes = new byte[raw.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                int hi = Character.digit(raw.charAt(i * 2), 16);
                int lo = Character.digit(raw.charAt(i * 2 + 1), 16);
                bytes[i] = (byte) ((hi << 4) | lo);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return raw;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "WifiNetworkRecord(encodeType=%d, supported=%b, open=%b)",
                encodeType, isSupportedByWatch(), isOpen());
    }
}
