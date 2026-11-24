package io.github.watchlink.alarm.probe;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** Local pseudonym, not anonymization. Never log the identifier or digest. Worker-only. */
public final class DeviceToken {
    private DeviceToken() { }
    public static String of(String identifier) throws Exception {
        if (identifier == null || identifier.isEmpty() || identifier.length() > 1024)
            throw new IllegalArgumentException("Invalid device identifier");
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(("WatchLink/device/v1/"+identifier).getBytes(StandardCharsets.UTF_8));
        StringBuilder s=new StringBuilder(64);
        for(byte b:hash) { s.append(Character.forDigit((b>>>4)&15,16));s.append(Character.forDigit(b&15,16)); }
        return s.toString();
    }
}
