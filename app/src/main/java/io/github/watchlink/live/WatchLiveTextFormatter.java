package io.github.watchlink.live;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watch-width text formatter calibrated to HUAWEI WATCH 6 Pro SmartEdge / Crescent window:
 * maximum 10.0 cells (20 half-cells), where whitespace is 0.5 cell (1 half-cell) and
 * CJK / Latin / digits / punctuation are 1.0 cell (2 half-cells).
 *
 * For pickup cards (EVENT_PICK_UP), strips redundant boilerplate such as "取餐码" and
 * prioritizes preserving the actual pickup code intact while ellipsizing long item names.
 */
public final class WatchLiveTextFormatter {
    /** 10.0 watch display cells expressed in half-cell units (1 cell = 2 half-cells). */
    public static final int MAX_WATCH_HALF_CELLS = 20;
    private static final int SPACE_HALF_CELLS = 1;
    private static final int CHAR_HALF_CELLS = 2;

    private static final Pattern LABELED_CODE = Pattern.compile(
            "(?:驿站取件码|快递柜取件码|凭码取餐|凭码取件|取餐凭证|取餐码|取餐号|取单码|取货码|取件码|取票码|提取码)\\s*[:：#]?\\s*([A-Za-z0-9]{1,4}(?:[-·][A-Za-z0-9]{1,6}){1,3}|[A-Za-z]{0,4}[-·]?\\d{1,8}[A-Za-z]?)");
    private static final Pattern BARE_LABEL = Pattern.compile(
            "(?:驿站取件码|快递柜取件码|凭码取餐|凭码取件|取餐凭证|取餐码|取餐号|取单码|取货码|取件码|取票码|提取码)\\s*[:：#]?");
    private static final Pattern STANDALONE_CODE = Pattern.compile(
            "^(?:[A-Za-z0-9]{1,4}(?:[-·][A-Za-z0-9]{1,6}){1,3}|[A-Za-z]{0,4}[-·]?\\d{1,8}[A-Za-z]?)$");
    private static final Pattern TRAILING_CODE = Pattern.compile(
            "^(.*?)\\s+([A-Za-z0-9]{1,4}(?:[-·][A-Za-z0-9]{1,6}){1,3}|[A-Za-z]{0,4}[-·]?\\d{1,8}[A-Za-z]?)$");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");

    public record Pair(String title, String content) {}

    private WatchLiveTextFormatter() {}

    /** Measures display width in half-cells: whitespace = 1 (0.5 cell), other code points = 2 (1.0 cell). */
    public static int halfCells(String text) {
        if (text == null || text.isEmpty()) return 0;
        int units = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            units += Character.isWhitespace(cp) ? SPACE_HALF_CELLS : CHAR_HALF_CELLS;
            i += Character.charCount(cp);
        }
        return units;
    }

    /** Strips boilerplate pickup labels like "取餐码" from a raw code value. */
    public static String cleanPickupCode(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String cleaned = BARE_LABEL.matcher(raw).replaceAll(" ");
        return collapseSpaces(cleaned);
    }

    /**
     * Compacts a single pickup line to fit within {@value #MAX_WATCH_HALF_CELLS} half-cells,
     * removing "取餐码" and keeping the actual pickup code intact even when the item name must be ellipsized.
     */
    public static String compactPickupLine(String raw) {
        return compactPickupLine(raw, MAX_WATCH_HALF_CELLS);
    }

    public static String compactPickupLine(String raw, int maxHalfCells) {
        String s = collapseSpaces(raw);
        if (s.isEmpty()) return "";

        Matcher m = LABELED_CODE.matcher(s);
        if (m.find()) {
            String prefix = trimEdgeSeparators(s.substring(0, m.start()));
            String code = m.group(1).trim();
            String suffix = trimEdgeSeparators(s.substring(m.end()));
            return assembleWithProtectedCode(prefix, code, suffix, maxHalfCells);
        }

        String stripped = trimEdgeSeparators(BARE_LABEL.matcher(s).replaceAll(" "));
        if (stripped.isEmpty()) return "";

        Matcher trailing = TRAILING_CODE.matcher(stripped);
        if (trailing.matches()) {
            String prefix = trimEdgeSeparators(trailing.group(1));
            String code = trailing.group(2).trim();
            if (!prefix.isEmpty() && !code.isEmpty() && HAS_DIGIT.matcher(code).matches()) {
                return assembleWithProtectedCode(prefix, code, "", maxHalfCells);
            }
        }
        return fitWithEllipsis(stripped, maxHalfCells);
    }

    /**
     * Compacts a watch capsule pair (capsuleTitle + capsuleContent) for pickup cards so that:
     * 1) "取餐码" is removed from both fields;
     * 2) Even if the watch concatenates {@code title + " " + content} on one 10-cell Crescent line,
     *    the combined width stays within 10.0 cells and the pickup code is NEVER truncated.
     */
    public static Pair compactPickupCapsule(String rawTitle, String rawContent) {
        return compactPickupCapsule(rawTitle, rawContent, MAX_WATCH_HALF_CELLS);
    }

    public static Pair compactPickupCapsule(String rawTitle, String rawContent, int maxHalfCells) {
        String title = collapseSpaces(rawTitle);
        String content = collapseSpaces(rawContent);

        // Extract code from content first; if content has an item prefix + code, prefer that item over generic app title.
        String extractedItem = "";
        String code = "";
        String suffix = "";

        Matcher contentLabeled = LABELED_CODE.matcher(content);
        if (contentLabeled.find()) {
            extractedItem = trimEdgeSeparators(content.substring(0, contentLabeled.start()));
            code = contentLabeled.group(1).trim();
            suffix = trimEdgeSeparators(content.substring(contentLabeled.end()));
        } else {
            String cleanedContent = trimEdgeSeparators(BARE_LABEL.matcher(content).replaceAll(" "));
            if (STANDALONE_CODE.matcher(cleanedContent).matches() && HAS_DIGIT.matcher(cleanedContent).matches()) {
                code = cleanedContent;
            } else {
                Matcher contentTrailing = TRAILING_CODE.matcher(cleanedContent);
                if (contentTrailing.matches() && HAS_DIGIT.matcher(contentTrailing.group(2)).matches()) {
                    extractedItem = trimEdgeSeparators(contentTrailing.group(1));
                    code = contentTrailing.group(2).trim();
                } else {
                    content = cleanedContent;
                }
            }
        }

        // Also check if the pickup code appeared inside rawTitle (e.g. title="卡布奇诺 取餐码 A633").
        if (code.isEmpty()) {
            Matcher titleLabeled = LABELED_CODE.matcher(title);
            if (titleLabeled.find()) {
                extractedItem = trimEdgeSeparators(title.substring(0, titleLabeled.start()));
                code = titleLabeled.group(1).trim();
                suffix = trimEdgeSeparators(title.substring(titleLabeled.end()));
            }
        }

        String cleanedTitle = trimEdgeSeparators(BARE_LABEL.matcher(title).replaceAll(" "));

        if (!code.isEmpty()) {
            String label = !extractedItem.isEmpty() ? extractedItem : cleanedTitle;
            if (label.isEmpty() || label.equals(code)) {
                String right = suffix.isEmpty() ? code : assembleWithProtectedCode("", code, suffix, maxHalfCells);
                return new Pair("", right);
            }
            int codeWidth = halfCells(code);
            int labelBudget = maxHalfCells - SPACE_HALF_CELLS - codeWidth;
            if (labelBudget < CHAR_HALF_CELLS * 2) {
                // Code alone fills almost the entire 10-cell bar; drop label to protect the code.
                return new Pair("", code);
            }
            String fittedLabel = fitWithEllipsis(label, labelBudget);
            if (fittedLabel.isEmpty()) {
                return new Pair("", code);
            }
            return new Pair(fittedLabel, code);
        }

        // Fallback when no structured pickup code token was detected: still strip "取餐码" and enforce 10-cell total.
        if (cleanedTitle.isEmpty()) {
            return new Pair("", fitWithEllipsis(content, maxHalfCells));
        }
        if (content.isEmpty() || content.equals(cleanedTitle)) {
            String fitted = fitWithEllipsis(cleanedTitle, maxHalfCells);
            return new Pair(fitted, fitted);
        }
        if (halfCells(cleanedTitle) + SPACE_HALF_CELLS + halfCells(content) <= maxHalfCells) {
            return new Pair(cleanedTitle, content);
        }
        int contentWidth = Math.min(halfCells(content), maxHalfCells - SPACE_HALF_CELLS - CHAR_HALF_CELLS * 2);
        String fittedContent = fitWithEllipsis(content, Math.max(CHAR_HALF_CELLS * 2, contentWidth));
        int titleBudget = maxHalfCells - SPACE_HALF_CELLS - halfCells(fittedContent);
        String fittedTitle = titleBudget >= CHAR_HALF_CELLS * 2 ? fitWithEllipsis(cleanedTitle, titleBudget) : "";
        return new Pair(fittedTitle, fittedContent);
    }

    /** Formats a structured pickup item + code + wait time into a concise line within 10 cells. */
    public static String formatPickupWithWait(String item, String rawCode, String waitTime) {
        String code = cleanPickupCode(rawCode);
        if (code.isEmpty()) return compactPickupLine(item);
        return assembleWithProtectedCode(trimEdgeSeparators(item), code, trimEdgeSeparators(waitTime), MAX_WATCH_HALF_CELLS);
    }

    /** Formats a flight line prioritizing flight number + gate/baggage belt within 10.0 watch cells. */
    public static String compactFlightLine(String flightNo, String gateOrBelt, String status) {
        String fn = trimEdgeSeparators(flightNo);
        String detail = trimEdgeSeparators(gateOrBelt);
        if (detail.isEmpty()) detail = trimEdgeSeparators(status);
        if (fn.isEmpty()) return fitWithEllipsis(detail, MAX_WATCH_HALF_CELLS);
        if (detail.isEmpty()) return fitWithEllipsis(fn, MAX_WATCH_HALF_CELLS);
        return assembleWithProtectedCode(detail, fn, "", MAX_WATCH_HALF_CELLS, true);
    }

    /** Formats a train line prioritizing train number + check-in gate within 10.0 watch cells. */
    public static String compactTrainLine(String trainNo, String gate, String status) {
        String tn = trimEdgeSeparators(trainNo);
        String detail = trimEdgeSeparators(gate);
        if (detail.isEmpty()) detail = trimEdgeSeparators(status);
        if (tn.isEmpty()) return fitWithEllipsis(detail, MAX_WATCH_HALF_CELLS);
        if (detail.isEmpty()) return fitWithEllipsis(tn, MAX_WATCH_HALF_CELLS);
        return assembleWithProtectedCode(detail, tn, "", MAX_WATCH_HALF_CELLS, true);
    }

    /** Formats a movie ticket line stripping boilerplate and protecting ticket code or show time within 10.0 cells. */
    public static String compactMovieLine(String movieName, String ticketCode, String showTime) {
        String code = cleanPickupCode(ticketCode);
        String title = trimEdgeSeparators(BARE_LABEL.matcher(collapseSpaces(movieName)).replaceAll(" "));
        if (!code.isEmpty()) {
            return assembleWithProtectedCode(title, code, trimEdgeSeparators(showTime), MAX_WATCH_HALF_CELLS);
        }
        String time = trimEdgeSeparators(showTime);
        if (!time.isEmpty() && !title.isEmpty()) {
            return assembleWithProtectedCode(title, time, "", MAX_WATCH_HALF_CELLS);
        }
        return fitWithEllipsis(!title.isEmpty() ? title : time, MAX_WATCH_HALF_CELLS);
    }

    /** Formats a game flashback countdown line prioritizing action + remaining seconds within 10.0 cells. */
    public static String compactGameFlashback(String actionLabel, int remainingSec, String fallbackText) {
        String label = trimEdgeSeparators(actionLabel);
        if (remainingSec > 0) {
            String secText = remainingSec + "秒";
            if (label.isEmpty()) label = "复活";
            return assembleWithProtectedCode(label, secText, "", MAX_WATCH_HALF_CELLS);
        }
        if (remainingSec == 0) {
            if (label.isEmpty()) return "已复活";
            return fitWithEllipsis(label.startsWith("已") ? label : ("已" + label), MAX_WATCH_HALF_CELLS);
        }
        String cleanFallback = trimEdgeSeparators(fallbackText);
        return fitWithEllipsis(!cleanFallback.isEmpty() ? cleanFallback : label, MAX_WATCH_HALF_CELLS);
    }

    private static String assembleWithProtectedCode(String prefix, String code, String suffix, int maxHalfCells) {
        return assembleWithProtectedCode(prefix, code, suffix, maxHalfCells, false);
    }

    private static String assembleWithProtectedCode(
            String other, String protectedToken, String suffix, int maxHalfCells, boolean tokenFirst) {
        if (protectedToken.isEmpty()) {
            String combined = !other.isEmpty() ? other : suffix;
            return fitWithEllipsis(combined, maxHalfCells);
        }
        int tokenWidth = halfCells(protectedToken);
        if (tokenWidth >= maxHalfCells) return protectedToken;

        if (!other.isEmpty()) {
            int budget = maxHalfCells - SPACE_HALF_CELLS - tokenWidth;
            if (budget >= CHAR_HALF_CELLS * 2) {
                String fittedOther = fitWithEllipsis(other, budget);
                if (!fittedOther.isEmpty()) {
                    return tokenFirst ? (protectedToken + " " + fittedOther) : (fittedOther + " " + protectedToken);
                }
            }
            return protectedToken;
        }

        if (!suffix.isEmpty()) {
            int budget = maxHalfCells - SPACE_HALF_CELLS - tokenWidth;
            if (budget >= CHAR_HALF_CELLS * 2) {
                String fittedSuffix = fitWithEllipsis(suffix, budget);
                if (!fittedSuffix.isEmpty()) {
                    return protectedToken + " " + fittedSuffix;
                }
            }
        }
        return protectedToken;
    }

    static String fitWithEllipsis(String text, int maxHalfCells) {
        String s = collapseSpaces(text);
        if (s.isEmpty() || maxHalfCells <= 0) return "";
        if (halfCells(s) <= maxHalfCells) return s;
        if (maxHalfCells < CHAR_HALF_CELLS) return "";
        if (maxHalfCells == CHAR_HALF_CELLS) return "…";

        int target = maxHalfCells - CHAR_HALF_CELLS; // reserve 1.0 cell for '…'
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int w = Character.isWhitespace(cp) ? SPACE_HALF_CELLS : CHAR_HALF_CELLS;
            if (used + w > target) break;
            sb.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        while (sb.length() > 0 && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.setLength(sb.length() - 1);
        }
        if (sb.length() == 0) return "";
        return sb.append('…').toString();
    }

    private static String collapseSpaces(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        boolean prevSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c == '\u00A0' || c == '\u3000') c = ' ';
            if (c < 0x20 || c == 0x7F) continue;
            if (c == ' ') {
                if (sb.length() == 0 || prevSpace) continue;
                prevSpace = true;
            } else {
                prevSpace = false;
            }
            sb.append(c);
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    private static String trimEdgeSeparators(String raw) {
        String s = collapseSpaces(raw);
        int start = 0;
        int end = s.length();
        while (start < end && isEdgeSep(s.charAt(start))) start++;
        while (end > start && isEdgeSep(s.charAt(end - 1))) end--;
        return s.substring(start, end).trim();
    }

    private static boolean isEdgeSep(char c) {
        return c == ' ' || c == '·' || c == '•' || c == '|' || c == '/' || c == '-'
                || c == ':' || c == '：' || c == ',' || c == '，' || c == ';' || c == '；';
    }
}
