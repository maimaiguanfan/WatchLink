package io.github.watchlink.live;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java parser turning ColorOS Pantanal UMS Fluid Cloud cards (Channel B) and
 * Android 16/17 promoted live notifications (Channel A) into normalized {@link LiveWindowPayload}.
 *
 * Explicitly excludes Clock timer and stopwatch cards per user preference.
 */
public final class PantanalFluidParser {
    private static final int FLAG_GROUP_SUMMARY = 0x00000200;

    private static final Set<String> EXCLUDED_PACKAGES = Set.of(
            "com.coloros.alarmclock",
            "com.android.deskclock",
            "com.oneplus.deskclock",
            "com.huawei.health",
            "io.github.maimaiguanfan.watchlink"
    );

    // Verified ColorOS 17 Clock Timer / Stopwatch Pantanal serviceIds from Clock.apk & live capture.
    private static final Set<Long> EXCLUDED_SERVICE_IDS = Set.of(
            268451854L,
            268451943L,
            268451930L,
            268451950L
    );

    private static final Set<String> EXCLUDED_CLOCK_KEYS = Set.of(
            "timer_time", "timer_runing", "timer_running", "timer_status", "timer_label",
            "stopwatch_time", "stopwatch_runing", "stopwatch_running", "stopwatch_status",
            "countdown_time", "countdown_status"
    );

    private static final Set<String> GENERIC_HOST_PACKAGES = Set.of(
            "com.oplus.pantanal.ums",
            "com.oplus.exsystemservice",
            "com.oplus.sceneservice",
            "com.oplus.cardservice",
            "com.heytap.speechassist"
    );

    private PantanalFluidParser() {}

    /** Parses a row from content://com.oplus.pantanal.ums.decision/systemui/services. */
    public static LiveWindowPayload parseDecisionRow(
            long serviceId,
            String serviceInstanceId,
            String hostPackage,
            String domain,
            String subDomain,
            String businessDataJson,
            String paramJson) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flattenJson(paramJson, flat, 0);
        flattenJson(businessDataJson, flat, 0);

        String launchPkg = firstNonEmpty(flat, "launchPackage", "targetPackage", "appPackage", "pkgName", "packageName");
        if (isExcludedClock(serviceId, hostPackage, launchPkg, subDomain, domain, "", flat)) {
            return null;
        }
        String resolvedPkg = pickDisplayPackage(launchPkg, hostPackage);
        String stableKey = buildDecisionStableKey(serviceId, serviceInstanceId, resolvedPkg);
        return extractFromFields(
                LiveWindowPayload.SOURCE_UMS,
                stableKey,
                resolvedPkg,
                domain,
                subDomain,
                flat);
    }

    /** Parses a decoded UIDataClientProto / UIDataProto JSON payload from UMS strategy push. */
    public static LiveWindowPayload parsePushData(
            int cardType,
            int cardId,
            String widgetCode,
            String layoutName,
            String cardName,
            String uiDataJson,
            String fallbackPackage,
            String fallbackStableKey) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flattenJson(uiDataJson, flat, 0);

        long serviceId = parseLongField(flat, "serviceId", "service_id");
        String hostPkg = firstNonEmpty(flat, "hostPackage", "host_package", "packageName", "pkgName");
        String launchPkg = firstNonEmpty(flat, "launchPackage", "targetPackage", "appPackage");
        String domain = firstNonEmpty(flat, "domain");
        String subDomain = firstNonEmpty(flat, "subDomain", "sub_domain");
        String hints = safe(widgetCode) + " " + safe(layoutName) + " " + safe(cardName);

        if (isExcludedClock(serviceId, nonEmpty(hostPkg) ? hostPkg : fallbackPackage, launchPkg, subDomain, domain, hints, flat)) {
            return null;
        }
        String resolvedPkg = pickDisplayPackage(launchPkg, nonEmpty(hostPkg) ? hostPkg : fallbackPackage);
        String stableKey;
        if (nonEmpty(fallbackStableKey)) {
            stableKey = fallbackStableKey;
        } else if (serviceId > 0) {
            String inst = firstNonEmpty(flat, "serviceInstanceId", "instanceId", "orderId");
            stableKey = buildDecisionStableKey(serviceId, inst, resolvedPkg);
        } else if (cardId > 0) {
            stableKey = "ums-cid:" + cardId;
        } else if (cardType > 0) {
            stableKey = "ums-ctype:" + cardType;
        } else if (nonEmpty(widgetCode)) {
            stableKey = "ums-widget:" + widgetCode.trim();
        } else {
            return null;
        }
        return extractFromFields(
                LiveWindowPayload.SOURCE_UMS,
                stableKey,
                resolvedPkg,
                domain,
                subDomain,
                flat);
    }

    /** Parses an Android 16/17 promoted live notification captured in Health :DaemonService (Channel A). */
    public static LiveWindowPayload parsePromotedNotification(
            String sbnKey,
            String packageName,
            String appLabel,
            int flags,
            boolean promotedOngoing,
            String templateClass,
            String oplusLiveAlertConfig,
            String title,
            String text,
            String bigText,
            String subText,
            String shortCriticalText,
            int progress,
            int progressMax,
            boolean progressIndeterminate) {
        if (!nonEmpty(sbnKey) || !nonEmpty(packageName)) return null;
        if ((flags & FLAG_GROUP_SUMMARY) != 0) return null;
        if (EXCLUDED_PACKAGES.contains(packageName.trim().toLowerCase(Locale.US))) return null;

        boolean isProgressStyle = templateClass != null && templateClass.endsWith("ProgressStyle");
        boolean hasOplusLive = oplusLiveAlertConfig != null && oplusLiveAlertConfig.trim().startsWith("{");
        if (!promotedOngoing && !isProgressStyle && !hasOplusLive) {
            return null;
        }

        String cleanTitle = firstNonEmptyStr(title, appLabel);
        String cleanText = firstNonEmptyStr(text, bigText, subText);
        if (cleanTitle.isEmpty() && cleanText.isEmpty()) return null;
        if (cleanText.isEmpty()) cleanText = cleanTitle;

        String capsuleTitle;
        String capsuleContent;
        if (nonEmpty(shortCriticalText)) {
            capsuleTitle = firstNonEmptyStr(appLabel, cleanTitle);
            capsuleContent = shortCriticalText.trim();
        } else {
            capsuleTitle = cleanTitle;
            capsuleContent = cleanText;
        }

        int pct = -1;
        if (!progressIndeterminate && progressMax > 0 && progress >= 0) {
            pct = Math.max(0, Math.min(100, (int) Math.round((progress * 100.0) / progressMax)));
        }

        String eventType = classifyEvent(packageName, "", "", cleanTitle + " " + cleanText, Map.of());
        String stableKey = "daemon:" + sbnKey.trim();
        LiveWindowPayload payload = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_DAEMON,
                stableKey,
                packageName,
                cleanTitle,
                cleanText,
                capsuleTitle,
                capsuleContent,
                eventType,
                pct,
                LiveWindowPayload.OP_CREATE);
        return payload.isValidForOpenOrUpdate() ? payload : null;
    }

    /** Returns true when a card or notification belongs to Clock timer or stopwatch (explicitly excluded). */
    public static boolean isExcludedClock(
            long serviceId,
            String hostPackage,
            String launchPackage,
            String subDomain,
            String domain,
            String extraHints,
            Map<String, Object> flatKeys) {
        if (serviceId > 0 && EXCLUDED_SERVICE_IDS.contains(serviceId)) return true;
        if (hostPackage != null && EXCLUDED_PACKAGES.contains(hostPackage.trim().toLowerCase(Locale.US))) return true;
        if (launchPackage != null && EXCLUDED_PACKAGES.contains(launchPackage.trim().toLowerCase(Locale.US))) return true;

        String combined = (safe(subDomain) + " " + safe(domain) + " " + safe(extraHints)).toLowerCase(Locale.US);
        if (combined.contains("alarmclock") || combined.contains("timer_card")
                || combined.contains("stopwatch_card") || combined.contains("clock_timer")
                || combined.contains("clock_stopwatch") || combined.equals("timer")
                || combined.equals("stopwatch")) {
            return true;
        }
        if (flatKeys != null) {
            for (String key : flatKeys.keySet()) {
                String low = key.toLowerCase(Locale.US);
                if (EXCLUDED_CLOCK_KEYS.contains(low)
                        || low.startsWith("timer_") || low.startsWith("stopwatch_")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static LiveWindowPayload extractFromFields(
            String source,
            String stableKey,
            String packageName,
            String domain,
            String subDomain,
            Map<String, Object> flat) {
        if (flat.isEmpty()) return null;

        // 1. MiniProgram / Store Pickup Code (verified live: Cotti Coffee 268451931 -> appName, pickupCode, waitTime)
        String pickupCode = firstNonEmpty(flat, "pickupCode", "takeCode", "mealCode", "orderCode", "pickUpCode");
        if (!pickupCode.isEmpty()) {
            String appName = firstNonEmpty(flat, "appName", "merchantName", "shopName", "brandName", "title", "fluidTitle");
            String waitTime = firstNonEmpty(flat, "waitTime", "waitText", "statusText", "subTitle", "fluidContent", "subContent");
            String title = appName.isEmpty() ? "取餐提醒" : appName;
            String codePhrase = pickupCode.contains("取餐") ? pickupCode : ("取餐码 " + pickupCode);
            String text = waitTime.isEmpty() ? codePhrase : (codePhrase + " · " + waitTime);
            String capsuleTitle = title;
            String capsuleContent = codePhrase;
            LiveWindowPayload p = new LiveWindowPayload(
                    source, stableKey, packageName, title, text,
                    capsuleTitle, capsuleContent, LiveWindowPayload.EVENT_PICK_UP, -1, LiveWindowPayload.OP_CREATE);
            return p.isValidForOpenOrUpdate() ? p : null;
        }

        // 2. Eleme takeout schema (fluidTitle, fluidContent, capsuleTitle, capsuleContent, currentProgress)
        String fluidTitle = firstNonEmpty(flat, "fluidTitle");
        String fluidContent = firstNonEmpty(flat, "fluidContent");
        if (!fluidTitle.isEmpty() || !fluidContent.isEmpty()) {
            String capTitle = firstNonEmpty(flat, "capsuleTitle", "capsuleTextLeft");
            String capContent = firstNonEmpty(flat, "capsuleContent", "capsuleTextRight");
            int pct = extractProgress(flat, "currentProgress", "totalProgress");
            if (pct < 0) pct = extractProgress(flat, "progress", "progressCount");
            String event = classifyEvent(packageName, domain, subDomain, fluidTitle + " " + fluidContent, flat);
            LiveWindowPayload p = new LiveWindowPayload(
                    source, stableKey, packageName, fluidTitle, fluidContent,
                    capTitle, capContent, event, pct, LiveWindowPayload.OP_CREATE);
            return p.isValidForOpenOrUpdate() ? p : null;
        }

        // 3. Meituan takeout schema (mainContent, subContent, capsuleContent1, capsuleContent2, progress, progressCount)
        String mainContent = firstNonEmpty(flat, "mainContent");
        String subContent = firstNonEmpty(flat, "subContent");
        if (!mainContent.isEmpty() || !subContent.isEmpty()) {
            String cap1 = firstNonEmpty(flat, "capsuleContent1", "capsuleTitle");
            String cap2 = firstNonEmpty(flat, "capsuleContent2", "capsuleContent");
            int pct = extractProgress(flat, "progress", "progressCount");
            String event = classifyEvent(packageName, domain, subDomain, mainContent + " " + subContent, flat);
            LiveWindowPayload p = new LiveWindowPayload(
                    source, stableKey, packageName, mainContent, subContent,
                    cap1, cap2, event, pct, LiveWindowPayload.OP_CREATE);
            return p.isValidForOpenOrUpdate() ? p : null;
        }

        // 4. PinHaoFan / Card title-description schema (smallCardTitle/bigCardTitle + Description + capsuleTextLeft/Right)
        String cardTitle = firstNonEmpty(flat, "bigCardTitle", "smallCardTitle", "cardTitle", "mainTitle");
        String cardDesc = firstNonEmpty(flat, "bigCardDescription", "smallCardDescription", "cardDescription", "subTitle");
        if (!cardTitle.isEmpty() || !cardDesc.isEmpty()) {
            String capLeft = firstNonEmpty(flat, "capsuleTextLeft", "capsuleTitle");
            String capRight = firstNonEmpty(flat, "capsuleTextRight", "capsuleContent");
            int pct = extractProgress(flat, "bigCardProgress", "bigCardProgressMax");
            if (pct < 0) pct = extractProgress(flat, "smallCardProgress", "smallCardProgressMax");
            if (pct < 0) pct = extractProgress(flat, "progress", "progressCount");
            String event = classifyEvent(packageName, domain, subDomain, cardTitle + " " + cardDesc, flat);
            LiveWindowPayload p = new LiveWindowPayload(
                    source, stableKey, packageName, cardTitle, cardDesc,
                    capLeft, capRight, event, pct, LiveWindowPayload.OP_CREATE);
            return p.isValidForOpenOrUpdate() ? p : null;
        }

        // 5. Ride-hailing / Taxi specific fields (plateNumber / licensePlate / carNo)
        String plate = firstNonEmpty(flat, "plateNumber", "licensePlate", "carNo", "carNumber", "plateNo");
        if (!plate.isEmpty()) {
            String status = firstNonEmpty(flat, "statusText", "etaText", "title", "text", "content", "desc", "carInfo");
            String title = status.isEmpty() ? ("网约车 " + plate) : status;
            String text = status.isEmpty() ? plate : (plate + " · " + status);
            String capTitle = firstNonEmpty(flat, "capsuleTitle");
            String capContent = firstNonEmpty(flat, "capsuleContent");
            if (capTitle.isEmpty()) capTitle = plate;
            if (capContent.isEmpty()) capContent = status.isEmpty() ? plate : status;
            int pct = extractProgress(flat, "progress", "progressCount");
            LiveWindowPayload p = new LiveWindowPayload(
                    source, stableKey, packageName, title, text,
                    capTitle, capContent, LiveWindowPayload.EVENT_TAXI, pct, LiveWindowPayload.OP_CREATE);
            return p.isValidForOpenOrUpdate() ? p : null;
        }

        // 6. Queue number schema (queueNo / waitNo / tableNo)
        String queueNo = firstNonEmpty(flat, "queueNo", "waitNo", "tableNo", "queueNumber");
        if (!queueNo.isEmpty()) {
            String shop = firstNonEmpty(flat, "shopName", "merchantName", "appName", "title");
            String waitInfo = firstNonEmpty(flat, "waitInfo", "waitTime", "waitCount", "content", "desc");
            String title = shop.isEmpty() ? "排队进度" : shop;
            String text = waitInfo.isEmpty() ? ("排号 " + queueNo) : ("排号 " + queueNo + " · " + waitInfo);
            LiveWindowPayload p = new LiveWindowPayload(
                    source, stableKey, packageName, title, text,
                    title, queueNo, LiveWindowPayload.EVENT_QUEUE, -1, LiveWindowPayload.OP_CREATE);
            return p.isValidForOpenOrUpdate() ? p : null;
        }

        // 7. Generic fallback for any remaining Pantanal Fluid Cloud card with title/content/capsule
        String genTitle = firstNonEmpty(flat, "title", "appName", "name", "capsuleTitle", "primaryText");
        String genText = firstNonEmpty(flat, "content", "text", "desc", "description", "statusText", "capsuleContent", "secondaryText");
        if (genTitle.isEmpty() && genText.isEmpty()) return null;
        String capTitle = firstNonEmpty(flat, "capsuleTitle", "capsuleTextLeft");
        String capContent = firstNonEmpty(flat, "capsuleContent", "capsuleTextRight");
        int pct = extractProgress(flat, "progress", "progressCount");
        if (pct < 0) pct = extractProgress(flat, "currentProgress", "totalProgress");
        if (pct < 0) pct = extractProgress(flat, "progressPercent", "");
        String event = classifyEvent(packageName, domain, subDomain, genTitle + " " + genText, flat);
        LiveWindowPayload p = new LiveWindowPayload(
                source, stableKey, packageName, genTitle, genText,
                capTitle, capContent, event, pct, LiveWindowPayload.OP_CREATE);
        return p.isValidForOpenOrUpdate() ? p : null;
    }

    static String classifyEvent(String pkg, String domain, String subDomain, String textHints, Map<String, Object> flat) {
        String lowPkg = safe(pkg).toLowerCase(Locale.US);
        String lowMeta = (safe(domain) + " " + safe(subDomain)).toLowerCase(Locale.US);
        String lowText = safe(textHints).toLowerCase(Locale.US);

        if (lowPkg.contains("didi") || lowPkg.contains("autonavi") || lowPkg.contains("baidumap")
                || lowPkg.contains("t3go") || lowPkg.contains("caocao") || lowPkg.contains("uber")
                || lowMeta.contains("taxi") || lowMeta.contains("ride") || lowMeta.contains("cab")
                || lowText.contains("打车") || lowText.contains("网约车") || lowText.contains("司机")
                || lowText.contains("上车点") || lowText.contains("接驾")) {
            return LiveWindowPayload.EVENT_TAXI;
        }
        if (lowMeta.contains("queue") || lowText.contains("排队") || lowText.contains("排号") || lowText.contains("等位")) {
            return LiveWindowPayload.EVENT_QUEUE;
        }
        if (lowPkg.contains("meituan") || lowPkg.contains("ele") || lowPkg.contains("takeout")
                || lowPkg.contains("cainiao") || lowPkg.contains("sf-express") || lowPkg.contains("jd.")
                || lowPkg.contains("jingdong") || lowPkg.contains("dada") || lowPkg.contains("shansong")
                || lowMeta.contains("takeout") || lowMeta.contains("delivery") || lowMeta.contains("express")
                || lowMeta.contains("logistics")
                || lowText.contains("骑手") || lowText.contains("配送") || lowText.contains("外卖")
                || lowText.contains("送达") || lowText.contains("快递") || lowText.contains("派送")) {
            return LiveWindowPayload.EVENT_DELIVERY;
        }
        if (flat != null && (flat.containsKey("mainContent") || flat.containsKey("fluidTitle")
                || flat.containsKey("smallCardTitle") || flat.containsKey("progressCount"))) {
            return LiveWindowPayload.EVENT_DELIVERY;
        }
        return LiveWindowPayload.EVENT_PICK_UP;
    }

    private static String buildDecisionStableKey(long serviceId, String serviceInstanceId, String pkg) {
        String inst = serviceInstanceId == null ? "" : serviceInstanceId.trim();
        if (serviceId > 0) {
            return "ums:" + serviceId + ":" + (inst.isEmpty() ? "default" : inst);
        }
        return "ums-pkg:" + pkg + ":" + (inst.isEmpty() ? "default" : inst);
    }

    private static String pickDisplayPackage(String launchPkg, String hostPkg) {
        if (isConcretePackage(launchPkg)) return launchPkg.trim();
        if (isConcretePackage(hostPkg)) return hostPkg.trim();
        if (isValidPackage(launchPkg)) return launchPkg.trim();
        if (isValidPackage(hostPkg)) return hostPkg.trim();
        return LiveWindowPayload.DEFAULT_PACKAGE;
    }

    private static boolean isConcretePackage(String pkg) {
        return isValidPackage(pkg) && !GENERIC_HOST_PACKAGES.contains(pkg.trim().toLowerCase(Locale.US));
    }

    private static boolean isValidPackage(String pkg) {
        if (pkg == null) return false;
        String trimmed = pkg.trim();
        return trimmed.length() <= 96 && trimmed.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    }

    private static int extractProgress(Map<String, Object> flat, String valueKey, String maxKey) {
        Double val = readNumber(flat, valueKey);
        if (val == null || val < 0) return -1;
        Double max = nonEmpty(maxKey) ? readNumber(flat, maxKey) : null;
        if (max != null && max > 0) {
            if (max <= 1.0 && val <= 1.0) {
                return clampPct((int) Math.round(val * 100.0));
            }
            if (val <= max) {
                return clampPct((int) Math.round((val * 100.0) / max));
            }
        }
        if (val <= 1.0 && valueKey.toLowerCase(Locale.US).contains("ratio")) {
            return clampPct((int) Math.round(val * 100.0));
        }
        if (val <= 100.0) {
            return clampPct((int) Math.round(val));
        }
        return -1;
    }

    private static int clampPct(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static Double readNumber(Map<String, Object> flat, String key) {
        if (!nonEmpty(key)) return null;
        Object raw = flat.get(key);
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.endsWith("%")) trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            if (trimmed.isEmpty()) return null;
            try { return Double.parseDouble(trimmed); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static long parseLongField(Map<String, Object> flat, String... keys) {
        for (String k : keys) {
            Double d = readNumber(flat, k);
            if (d != null && d > 0) return d.longValue();
        }
        return -1L;
    }

    private static String firstNonEmpty(Map<String, Object> flat, String... keys) {
        for (String k : keys) {
            Object raw = flat.get(k);
            if (raw instanceof String s) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty() && !looksLikeJsonContainer(trimmed) && !"null".equalsIgnoreCase(trimmed)) {
                    return trimmed;
                }
            }
        }
        return "";
    }

    private static String firstNonEmptyStr(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static boolean looksLikeJsonContainer(String s) {
        return (s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"));
    }

    private static void flattenJson(String raw, Map<String, Object> out, int depth) {
        if (raw == null || raw.isEmpty() || depth > 4) return;
        String trimmed = raw.trim();
        if (!looksLikeJsonContainer(trimmed)) return;
        Object parsed = SimpleJson.parse(trimmed);
        flattenObject(parsed, out, depth);
    }

    @SuppressWarnings("unchecked")
    private static void flattenObject(Object node, Map<String, Object> out, int depth) {
        if (node == null || depth > 5) return;
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) continue;
                Object val = entry.getValue();
                if (val == null) continue;
                if (val instanceof String s) {
                    String st = s.trim();
                    if (depth < 4 && looksLikeJsonContainer(st)) {
                        flattenJson(st, out, depth + 1);
                    } else if (!st.isEmpty()) {
                        out.putIfAbsent(key, st);
                    }
                } else if (val instanceof Map<?, ?> || val instanceof List<?>) {
                    flattenObject(val, out, depth + 1);
                } else {
                    out.putIfAbsent(key, val);
                }
            }
        } else if (node instanceof List<?> list) {
            int limit = Math.min(list.size(), 8);
            for (int i = 0; i < limit; i++) {
                flattenObject(list.get(i), out, depth + 1);
            }
        }
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
