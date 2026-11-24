package io.github.watchlink.alarm.probe;
import java.util.Locale;
/** Exact format produced by this APK constructor. Other import formats stay unverified. */
public final class NativeUuid {
 private NativeUuid() { }
 public static String normalize(String value) {
  return value!=null && value.matches("[0-9a-fA-F]{32}") ? value.toLowerCase(Locale.ROOT) : null;
 }
 public static String digest(String value) throws Exception {
  String normalized=normalize(value);
  return normalized==null ? null : DeviceToken.of("clock-native-uuid/"+normalized);
 }
}
