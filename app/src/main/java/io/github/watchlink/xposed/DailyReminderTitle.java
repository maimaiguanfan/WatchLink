package io.github.watchlink.xposed;

/** Display-only metadata for one native 8/9 OPEN. Null leaves the owner's default title intact. */
final class DailyReminderTitle {
    // The stock Clock permits 40 UTF-16 code units (at most 120 UTF-8 bytes). Keep other
    // sources within the short-form TLV length supported by the observed Huawei encoder.
    private static final int MAX_BYTES = 127;

    static String forWatch(String label) {
        if (label == null || label.isEmpty()) return null;
        StringBuilder displayed = new StringBuilder(Math.min(label.length(), MAX_BYTES));
        int bytes = 0;
        boolean hasText = false;
        for (int index = 0; index < label.length();) {
            int cp = label.codePointAt(index);
            index += Character.charCount(cp);
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) cp = 0xfffd;
            bytes += cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (bytes > MAX_BYTES) break;
            displayed.appendCodePoint(cp);
            hasText |= !Character.isWhitespace(cp) && !Character.isSpaceChar(cp);
        }
        return hasText ? displayed.toString() : null;
    }

    private DailyReminderTitle() {}
}
