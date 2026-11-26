package io.github.watchlink.live;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-Java parser turning ColorOS Pantanal UMS Fluid Cloud cards (Channel B) and
 * Android 16/17 promoted live notifications (Channel A) into normalized {@link LiveWindowPayload}.
 *
 * Only ongoing external service events qualify for a watch live window.
 */
public final class PantanalFluidParser {
    private static final int FLAG_ONGOING_EVENT = 0x00000002;
    private static final int FLAG_GROUP_SUMMARY = 0x00000200;

    private static final Set<String> EXCLUDED_PACKAGES = Set.of(
            "com.coloros.alarmclock",
            "com.android.deskclock",
            "com.oneplus.deskclock",
            "com.heytap.speechassist",
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
            "com.oplus.cardservice"
    );

    private static final Set<String> GAME_FLASHBACK_PACKAGES = Set.of(
            "com.oplus.games",
            "com.coloros.gamespace",
            "com.coloros.gamespaceui",
            "com.nearme.gamecenter",
            "com.tencent.tmgp.sgame",
            "com.tencent.tmgp.pubgmhd",
            "com.tencent.af"
    );

    // Match complete scene tokens, including names such as "eleme_takeout_card".
    private static final Pattern PICKUP_SCENE = scenePattern("pickup|pick_up|meal_pickup|parcel_pickup");
    private static final Pattern TAXI_SCENE = scenePattern("taxi|ride_hailing|ridehailing");
    private static final Pattern DELIVERY_SCENE = scenePattern("takeout|takeaway|delivery|express|parcel|logistics");
    private static final Pattern QUEUE_SCENE = scenePattern("queue|queuing");
    private static final Pattern FLIGHT_SCENE = scenePattern("flight|aviation|airport|boarding|baggage");
    private static final Pattern TRAIN_SCENE = scenePattern("train|railway|highspeed|high_speed_rail");
    private static final Pattern MOVIE_SCENE = scenePattern("movie|cinema|film");
    private static final Pattern GAME_FLASHBACK_SCENE = scenePattern(
            "game_flashback|game_revive|game_assistant|gamespace|flashback_game");
    private static final Pattern LOCAL_SCENE = scenePattern(
            "ringer|silent_mode|vibrate_mode|do_not_disturb|dnd|bookmark|favorite|collection");

    private static final Pattern FLIGHT_NO_PATTERN = Pattern.compile("\\b([A-Z0-9]{2}\\d{3,4})\\b");
    private static final Pattern TRAIN_NO_PATTERN = Pattern.compile("\\b([GDCZTKSY]\\d{1,4})\\b");
    private static final Pattern GATE_PATTERN = Pattern.compile(
            "(?:登机口|检票口|行李转盘|行李盘)\\s*[:：]?\\s*([A-Za-z0-9\\u4e00-\\u9fa5]{1,6})");

    private enum SceneKind {
        PICK_UP, TAXI, QUEUE, DELIVERY, FLIGHT, TRAIN, MOVIE, GAME_FLASHBACK
    }

    private static Pattern scenePattern(String terms) {
        return Pattern.compile("(?:^|[^a-z0-9])(?:" + terms + ")(?:$|[^a-z0-9])", Pattern.CASE_INSENSITIVE);
    }

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
        if (isExcludedSource(serviceId, hostPackage, launchPkg, subDomain, domain, "", flat)) {
            return null;
        }
        String resolvedPkg = pickDisplayPackage(launchPkg, hostPackage);
        String stableKey = buildDecisionStableKey(serviceId, serviceInstanceId, resolvedPkg);
        return extractFromFields(
                LiveWindowPayload.SOURCE_UMS,
                stableKey,
                resolvedPkg,
                safe(domain) + " " + safe(subDomain),
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

        if (isExcludedSource(serviceId, nonEmpty(hostPkg) ? hostPkg : fallbackPackage, launchPkg, subDomain, domain, hints, flat)) {
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
                domain + " " + subDomain + " " + safe(layoutName) + " " + safe(cardName),
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
        boolean isGamePkg = GAME_FLASHBACK_PACKAGES.contains(packageName.trim().toLowerCase(Locale.US));
        boolean ongoing = (flags & FLAG_ONGOING_EVENT) != 0;
        if (!promotedOngoing && (!ongoing || (!isProgressStyle && !hasOplusLive && !isGamePkg))) {
            return null;
        }

        String cleanTitle = firstNonEmptyStr(title, appLabel);
        String cleanText = firstNonEmptyStr(text, bigText, subText, shortCriticalText);
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

        // App labels and progress bars describe the presentation, not the event.
        String evidence = String.join(" ", safe(title), safe(text), safe(bigText), safe(subText), safe(shortCriticalText));
        SceneKind kind = classifyScene("", packageName, evidence, Map.of());
        if (kind == null) return null;
        if (kind != SceneKind.GAME_FLASHBACK && !promotedOngoing && !isProgressStyle && !hasOplusLive) {
            return null;
        }
        String baseKey = "daemon:" + sbnKey.trim();
        if (kind == SceneKind.GAME_FLASHBACK) {
            return buildGameFlashbackPayload(
                    LiveWindowPayload.SOURCE_DAEMON,
                    withGameFlashbackPrefix(baseKey),
                    packageName,
                    cleanTitle,
                    cleanText,
                    capsuleTitle,
                    capsuleContent,
                    pct);
        }
        if (kind == SceneKind.FLIGHT) {
            return buildFlightPayload(
                    LiveWindowPayload.SOURCE_DAEMON, baseKey, packageName,
                    Map.of(), cleanTitle, cleanText, pct);
        }
        if (kind == SceneKind.TRAIN) {
            return buildTrainPayload(
                    LiveWindowPayload.SOURCE_DAEMON, baseKey, packageName,
                    Map.of(), cleanTitle, cleanText, pct);
        }
        if (kind == SceneKind.MOVIE) {
            return buildMoviePayload(
                    LiveWindowPayload.SOURCE_DAEMON, baseKey, packageName,
                    Map.of(), cleanTitle, cleanText);
        }
        LiveWindowPayload payload = new LiveWindowPayload(
                LiveWindowPayload.SOURCE_DAEMON,
                baseKey,
                packageName,
                cleanTitle,
                cleanText,
                capsuleTitle,
                capsuleContent,
                toSmartEdgeEvent(kind),
                pct,
                LiveWindowPayload.OP_CREATE);
        return payload.isValidForOpenOrUpdate() ? payload : null;
    }

    private static boolean isExcludedSource(
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
            String scene,
            Map<String, Object> flat) {
        if (flat.isEmpty()) return null;
        StringBuilder evidence = new StringBuilder();
        for (String key : DISPLAY_FIELDS) {
            if (flat.get(key) instanceof String value) evidence.append(value).append(' ');
        }
        SceneKind kind = classifyScene(scene, packageName, evidence.toString(), flat);
        if (kind == null) return null;
        String event = toSmartEdgeEvent(kind);

        // 0. Game Flashback (King of Glory revive / Peacekeeper Elite takeoff countdowns)
        if (kind == SceneKind.GAME_FLASHBACK) {
            String gTitle = firstNonEmpty(flat, "extra_title", "fluidTitle", "bigCardTitle", "smallCardTitle", "title", "appName");
            String gText = firstNonEmpty(flat, "extra_content", "fluidContent", "bigCardDescription", "smallCardDescription",
                    "subContent", "content", "text", "statusText", "desc");
            String gCapTitle = firstNonEmpty(flat, "capsuleTitle", "capsuleTextLeft", "capsuleContent1", "extra_title");
            String gCapContent = firstNonEmpty(flat, "capsuleContent", "capsuleTextRight", "capsuleContent2", "extra_content");
            int secFromField = extractCountdownSecondsField(flat);
            if (secFromField >= 0 && GameReviveCoordinator.parseRemainingSeconds(gText, gCapContent, gTitle) < 0) {
                String action = GameReviveCoordinator.inferActionLabel(gTitle, gText, gCapTitle, gCapContent);
                gText = WatchLiveTextFormatter.compactGameFlashback(action, secFromField, gText);
                if (gCapContent.isEmpty()) gCapContent = secFromField > 0 ? (secFromField + "秒") : ("已" + action);
            }
            return buildGameFlashbackPayload(
                    source, withGameFlashbackPrefix(stableKey), packageName,
                    gTitle, gText, gCapTitle, gCapContent, -1);
        }

        // 0b. Flight / Train / Movie structured travel & ticket cards (10-cell watch compaction)
        if (kind == SceneKind.FLIGHT) {
            String fTitle = firstNonEmpty(flat, "fluidTitle", "bigCardTitle", "smallCardTitle", "mainTitle", "title", "mainContent");
            String fText = firstNonEmpty(flat, "fluidContent", "bigCardDescription", "smallCardDescription", "subTitle", "subContent", "statusText", "content", "text");
            int pct = extractProgress(flat, "currentProgress", "totalProgress");
            if (pct < 0) pct = extractProgress(flat, "progress", "progressCount");
            return buildFlightPayload(source, stableKey, packageName, flat, fTitle, fText, pct);
        }
        if (kind == SceneKind.TRAIN) {
            String tTitle = firstNonEmpty(flat, "fluidTitle", "bigCardTitle", "smallCardTitle", "mainTitle", "title", "mainContent");
            String tText = firstNonEmpty(flat, "fluidContent", "bigCardDescription", "smallCardDescription", "subTitle", "subContent", "statusText", "content", "text");
            int pct = extractProgress(flat, "currentProgress", "totalProgress");
            if (pct < 0) pct = extractProgress(flat, "progress", "progressCount");
            return buildTrainPayload(source, stableKey, packageName, flat, tTitle, tText, pct);
        }
        if (kind == SceneKind.MOVIE) {
            String mTitle = firstNonEmpty(flat, "movieName", "filmName", "fluidTitle", "bigCardTitle", "smallCardTitle", "title", "mainContent");
            String mText = firstNonEmpty(flat, "fluidContent", "bigCardDescription", "smallCardDescription", "subTitle", "subContent", "content", "text");
            return buildMoviePayload(source, stableKey, packageName, flat, mTitle, mText);
        }

        // 1. MiniProgram / Store / Parcel Pickup Code (verified live: Cotti Coffee 268451931 -> appName, pickupCode, waitTime)
        String pickupCode = identifier(flat, "pickupCode", "pickUpCode", "takeCode", "mealCode", "orderCode", "parcelCode", "verifyCode");
        if (LiveWindowPayload.EVENT_PICK_UP.equals(event) && !pickupCode.isEmpty()) {
            String appName = firstNonEmpty(flat, "appName", "merchantName", "shopName", "brandName", "title", "fluidTitle");
            String itemName = firstNonEmpty(flat, "goodsName", "productName", "dishName", "itemName");
            String waitTime = firstNonEmpty(flat, "waitTime", "waitText", "statusText", "subTitle", "fluidContent", "subContent");
            String cleanCode = WatchLiveTextFormatter.cleanPickupCode(pickupCode);
            String title = !itemName.isEmpty() ? itemName : (appName.isEmpty() ? "取餐提醒" : appName);
            String text = WatchLiveTextFormatter.formatPickupWithWait("", cleanCode, waitTime);
            String capsuleTitle = title;
            String capsuleContent = cleanCode;
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
            LiveWindowPayload p = new LiveWindowPayload(
                    source, stableKey, packageName, cardTitle, cardDesc,
                    capLeft, capRight, event, pct, LiveWindowPayload.OP_CREATE);
            return p.isValidForOpenOrUpdate() ? p : null;
        }

        // 5. Ride-hailing / Taxi specific fields (plateNumber / licensePlate / carNo)
        String plate = firstNonEmpty(flat, "plateNumber", "licensePlate", "carNo", "carNumber", "plateNo");
        if (LiveWindowPayload.EVENT_TAXI.equals(event) && !plate.isEmpty()) {
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
        String queueNo = identifier(flat, "queueNo", "waitNo", "queueNumber", "tableNo");
        if (LiveWindowPayload.EVENT_QUEUE.equals(event) && !queueNo.isEmpty()) {
            String shop = firstNonEmpty(flat, "shopName", "merchantName", "appName", "title");
            String waitInfo = firstNonEmpty(flat, "waitInfo", "waitTime", "waitCount", "content", "desc");
            String title = shop.isEmpty() ? "排队进度" : shop;
            String text = waitInfo.isEmpty() ? ("排号 " + queueNo) : ("排号 " + queueNo + " · " + waitInfo);
            LiveWindowPayload p = new LiveWindowPayload(
                    source, stableKey, packageName, title, text,
                    title, queueNo, LiveWindowPayload.EVENT_QUEUE, -1, LiveWindowPayload.OP_CREATE);
            return p.isValidForOpenOrUpdate() ? p : null;
        }

        // 7. Generic layout for a card whose external event was already established.
        String genTitle = firstNonEmpty(flat, "title", "appName", "name", "capsuleTitle", "primaryText");
        String genText = firstNonEmpty(flat, "content", "text", "desc", "description", "statusText", "capsuleContent", "secondaryText");
        if (genTitle.isEmpty() && genText.isEmpty()) return null;
        String capTitle = firstNonEmpty(flat, "capsuleTitle", "capsuleTextLeft");
        String capContent = firstNonEmpty(flat, "capsuleContent", "capsuleTextRight");
        int pct = extractProgress(flat, "progress", "progressCount");
        if (pct < 0) pct = extractProgress(flat, "currentProgress", "totalProgress");
        if (pct < 0) pct = extractProgress(flat, "progressPercent", "");
        LiveWindowPayload p = new LiveWindowPayload(
                source, stableKey, packageName, genTitle, genText,
                capTitle, capContent, event, pct, LiveWindowPayload.OP_CREATE);
        return p.isValidForOpenOrUpdate() ? p : null;
    }

    private static final List<String> DISPLAY_FIELDS = List.of(
            "title", "text", "content", "desc", "description", "statusText", "name", "appName",
            "mainTitle", "subTitle", "mainContent", "subContent", "primaryText", "secondaryText",
            "fluidTitle", "fluidContent", "bigCardTitle", "smallCardTitle", "cardTitle",
            "bigCardDescription", "smallCardDescription", "cardDescription", "capsuleTitle",
            "capsuleContent", "capsuleContent1", "capsuleContent2", "capsuleTextLeft", "capsuleTextRight",
            "extra_title", "extra_content", "flightNo", "flightNumber", "trainNo", "trainNumber",
            "gate", "boardingGate", "checkPort", "ticketGate", "baggageBelt", "movieName", "filmName");

    /** Returns null for an unrecognized scene, before the SmartEdge encoder normalizes its event. */
    private static SceneKind classifyScene(String scene, String packageName, String text, Map<String, Object> flat) {
        String context = (scene + " " + text).toLowerCase(Locale.ROOT);
        if (LOCAL_SCENE.matcher(scene).find() || containsAny(context,
                "响铃模式", "振动模式", "震动模式", "静音模式", "免打扰", "勿扰模式",
                "收藏", "摘录", "剪藏", "识屏", "小布助手", "优惠券", "领券", "红包活动",
                "do not disturb", "ringer mode", "saved post", "bookmark")) return null;

        String pkgLow = packageName == null ? "" : packageName.trim().toLowerCase(Locale.US);
        boolean isGamePkg = GAME_FLASHBACK_PACKAGES.contains(pkgLow);
        if (GAME_FLASHBACK_SCENE.matcher(scene).find()
                || containsAny(context, "复活倒计时", "即将复活", "已复活", "复活完成", "复活还有",
                        "起飞倒计时", "航线准备", "准备跳伞")
                || (isGamePkg && (containsAny(context, "复活", "起飞", "跳伞", "匹配成功", "英雄选择", "倒计时")
                        || extractCountdownSecondsField(flat) >= 0))) {
            return SceneKind.GAME_FLASHBACK;
        }

        boolean hasFlightId = !firstNonEmpty(flat, "flightNo", "flightNumber").isEmpty()
                || FLIGHT_NO_PATTERN.matcher(text).find();
        if (FLIGHT_SCENE.matcher(scene).find()
                || (hasFlightId && containsAny(context, "航班", "登机", "登机口", "值机", "航站楼", "行李转盘", "行李盘", "起飞"))
                || containsAny(context, "登机口", "行李转盘", "正在登机", "即将登机", "航班值机", "航班延误", "航班起飞")) {
            return SceneKind.FLIGHT;
        }

        boolean hasTrainId = !firstNonEmpty(flat, "trainNo", "trainNumber").isEmpty()
                || TRAIN_NO_PATTERN.matcher(text).find();
        if (TRAIN_SCENE.matcher(scene).find()
                || (hasTrainId && containsAny(context, "检票", "检票口", "候车", "发车", "站台", "高铁", "动车", "列车", "车次"))
                || containsAny(context, "检票口", "正在检票", "停止检票", "候车室", "列车发车")) {
            return SceneKind.TRAIN;
        }

        if (MOVIE_SCENE.matcher(scene).find()
                || !firstNonEmpty(flat, "movieName", "filmName", "ticketCode").isEmpty()
                || containsAny(context, "取票码", "电影票", "影院取票", "即将开场", "放映厅")) {
            return SceneKind.MOVIE;
        }

        if (!identifier(flat, "pickupCode", "pickUpCode", "takeCode", "mealCode", "parcelCode").isEmpty()
                || PICKUP_SCENE.matcher(scene).find()
                || containsAny(context, "取餐码", "取餐号", "请取餐", "等待取餐", "待取餐", "可取餐",
                        "取件码", "驿站取件", "快递柜", "待取件", "请取件", "凭码取件", "丰巢",
                        "pickup code", "pickup number", "ready for pickup")) {
            return SceneKind.PICK_UP;
        }
        boolean hasJourneyProgress = containsAny(context, "分钟", "公里", "已接单", "即将到达", "已到达");
        if (TAXI_SCENE.matcher(scene).find()
                || (hasJourneyProgress && containsAny(context, "打车", "网约车", "专车", "司机"))
                || containsAny(context, "司机已", "司机距", "司机正", "司机即将", "司机还有", "司机接单",
                        "正在叫车", "等待上车", "请上车", "上车点", "正在接驾", "接驾中", "打车行程",
                        "driver is arriving", "driver has arrived", "driver is on the way")
                || (!firstNonEmpty(flat, "plateNumber", "licensePlate", "carNo", "carNumber", "plateNo").isEmpty()
                        && containsAny(context, "司机", "接驾", "上车", "网约车"))) {
            return SceneKind.TAXI;
        }
        if (QUEUE_SCENE.matcher(scene).find() || !identifier(flat, "queueNo", "waitNo", "queueNumber").isEmpty()
                || containsAny(context, "排队中", "排队进度", "排队号", "排号", "等位", "等待叫号",
                        "queue number", "in the queue")) {
            return SceneKind.QUEUE;
        }
        if (DELIVERY_SCENE.matcher(scene).find()
                || (hasJourneyProgress && containsAny(context, "外卖", "骑手", "配送", "快递", "派件"))
                || containsAny(context, "骑手已", "骑手正", "骑手距", "骑手即将", "骑手接单", "骑手取餐",
                        "配送中", "正在配送", "外卖配送", "预计送达", "已送达", "正在派送", "正在备餐", "商家已接单",
                        "正在派件", "派件中", "快递派送", "快件正在派送", "快递员正", "快递员已", "快递即将送达",
                        "out for delivery", "order is on the way", "order is being prepared")) {
            return SceneKind.DELIVERY;
        }
        return null;
    }

    private static String toSmartEdgeEvent(SceneKind kind) {
        return switch (kind) {
            case DELIVERY -> LiveWindowPayload.EVENT_DELIVERY;
            case TAXI, FLIGHT, TRAIN -> LiveWindowPayload.EVENT_TAXI;
            case QUEUE, GAME_FLASHBACK -> LiveWindowPayload.EVENT_QUEUE;
            case PICK_UP, MOVIE -> LiveWindowPayload.EVENT_PICK_UP;
        };
    }

    private static String withGameFlashbackPrefix(String stableKey) {
        if (stableKey == null || stableKey.isEmpty()) return "";
        return stableKey.startsWith(LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX)
                ? stableKey
                : LiveWindowPayload.GAME_FLASHBACK_KEY_PREFIX + stableKey;
    }

    private static int extractCountdownSecondsField(Map<String, Object> flat) {
        if (flat == null || flat.isEmpty()) return -1;
        for (String key : List.of("reviveTime", "reviveSeconds", "countdown", "countdownSeconds", "remainTime", "leftTime", "remainSec")) {
            Double val = readNumber(flat, key);
            if (val != null && val >= 0 && val <= 600) return (int) Math.round(val);
        }
        return -1;
    }

    private static LiveWindowPayload buildGameFlashbackPayload(
            String source, String stableKey, String packageName,
            String rawTitle, String rawText, String rawCapTitle, String rawCapContent, int progress) {
        int remainingSec = GameReviveCoordinator.parseRemainingSeconds(rawCapContent, rawText, rawTitle);
        boolean completed = GameReviveCoordinator.isCompletionText(rawCapContent, rawText, rawTitle);
        String action = GameReviveCoordinator.inferActionLabel(rawTitle, rawText, rawCapTitle, rawCapContent);
        String fallback = firstNonEmptyStr(rawText, rawTitle, rawCapContent, rawCapTitle);
        String line = completed
                ? WatchLiveTextFormatter.compactGameFlashback(action, 0, fallback)
                : WatchLiveTextFormatter.compactGameFlashback(action, remainingSec, fallback);
        String title = line;
        String text = line;
        String capTitle = remainingSec > 0 ? action : WatchLiveTextFormatter.fitWithEllipsis(firstNonEmptyStr(rawCapTitle, action), 10);
        String capContent = remainingSec > 0 ? (remainingSec + "秒") : (completed ? ("已" + action) : line);
        LiveWindowPayload payload = new LiveWindowPayload(
                source, stableKey, packageName, title, text, capTitle, capContent,
                LiveWindowPayload.EVENT_QUEUE, progress, LiveWindowPayload.OP_CREATE);
        return payload.isValidForOpenOrUpdate() ? payload : null;
    }

    private static LiveWindowPayload buildFlightPayload(
            String source, String stableKey, String packageName,
            Map<String, Object> flat, String rawTitle, String rawText, int progress) {
        String combined = safe(rawTitle) + " " + safe(rawText);
        String flightNo = firstNonEmpty(flat, "flightNo", "flightNumber");
        if (flightNo.isEmpty()) {
            var m = FLIGHT_NO_PATTERN.matcher(combined.toUpperCase(Locale.US));
            if (m.find()) flightNo = m.group(1);
        }
        String gate = firstNonEmpty(flat, "boardingGate", "gate", "baggageBelt", "carousel", "luggageCarousel");
        if (gate.isEmpty()) {
            var m = GATE_PATTERN.matcher(combined);
            if (m.find()) gate = m.group(1);
        }
        String status = firstNonEmpty(flat, "statusText", "subTitle", "fluidContent", "subContent");
        if (status.isEmpty()) status = firstNonEmptyStr(rawText, rawTitle);
        String compact = WatchLiveTextFormatter.compactFlightLine(flightNo, gate, status);
        String title = !flightNo.isEmpty() ? flightNo : compact;
        String capTitle = !flightNo.isEmpty() ? flightNo : WatchLiveTextFormatter.fitWithEllipsis(compact, 10);
        String capContent = !gate.isEmpty() ? WatchLiveTextFormatter.fitWithEllipsis(gate, 12) : compact;
        LiveWindowPayload payload = new LiveWindowPayload(
                source, stableKey, packageName, title, compact, capTitle, capContent,
                LiveWindowPayload.EVENT_TAXI, progress, LiveWindowPayload.OP_CREATE);
        return payload.isValidForOpenOrUpdate() ? payload : null;
    }

    private static LiveWindowPayload buildTrainPayload(
            String source, String stableKey, String packageName,
            Map<String, Object> flat, String rawTitle, String rawText, int progress) {
        String combined = safe(rawTitle) + " " + safe(rawText);
        String trainNo = firstNonEmpty(flat, "trainNo", "trainNumber");
        if (trainNo.isEmpty()) {
            var m = TRAIN_NO_PATTERN.matcher(combined.toUpperCase(Locale.US));
            if (m.find()) trainNo = m.group(1);
        }
        String gate = firstNonEmpty(flat, "checkPort", "ticketGate", "gate", "platform");
        if (gate.isEmpty()) {
            var m = GATE_PATTERN.matcher(combined);
            if (m.find()) gate = m.group(1);
        }
        String status = firstNonEmpty(flat, "statusText", "subTitle", "fluidContent", "subContent");
        if (status.isEmpty()) status = firstNonEmptyStr(rawText, rawTitle);
        String compact = WatchLiveTextFormatter.compactTrainLine(trainNo, gate, status);
        String title = !trainNo.isEmpty() ? trainNo : compact;
        String capTitle = !trainNo.isEmpty() ? trainNo : WatchLiveTextFormatter.fitWithEllipsis(compact, 10);
        String capContent = !gate.isEmpty() ? WatchLiveTextFormatter.fitWithEllipsis(gate, 12) : compact;
        LiveWindowPayload payload = new LiveWindowPayload(
                source, stableKey, packageName, title, compact, capTitle, capContent,
                LiveWindowPayload.EVENT_TAXI, progress, LiveWindowPayload.OP_CREATE);
        return payload.isValidForOpenOrUpdate() ? payload : null;
    }

    private static LiveWindowPayload buildMoviePayload(
            String source, String stableKey, String packageName,
            Map<String, Object> flat, String rawTitle, String rawText) {
        String movieName = firstNonEmpty(flat, "movieName", "filmName", "title");
        if (movieName.isEmpty()) movieName = rawTitle;
        String ticketCode = identifier(flat, "ticketCode", "verifyCode", "pickupCode", "pickUpCode");
        String showTime = firstNonEmpty(flat, "showTime", "startTime", "statusText", "subTitle");
        if (ticketCode.isEmpty() && showTime.isEmpty()) {
            String compact = WatchLiveTextFormatter.compactPickupLine(firstNonEmptyStr(rawText, rawTitle));
            LiveWindowPayload p = new LiveWindowPayload(
                    source, stableKey, packageName,
                    firstNonEmptyStr(movieName, compact), compact,
                    firstNonEmptyStr(movieName, compact), compact,
                    LiveWindowPayload.EVENT_PICK_UP, -1, LiveWindowPayload.OP_CREATE);
            return p.isValidForOpenOrUpdate() ? p : null;
        }
        String compact = WatchLiveTextFormatter.compactMovieLine(movieName, ticketCode, showTime);
        String capTitle = WatchLiveTextFormatter.fitWithEllipsis(firstNonEmptyStr(movieName, "电影票"), 10);
        String capContent = !ticketCode.isEmpty()
                ? WatchLiveTextFormatter.cleanPickupCode(ticketCode)
                : WatchLiveTextFormatter.fitWithEllipsis(firstNonEmptyStr(showTime, compact), 12);
        LiveWindowPayload p = new LiveWindowPayload(
                source, stableKey, packageName,
                firstNonEmptyStr(movieName, compact), compact, capTitle, capContent,
                LiveWindowPayload.EVENT_PICK_UP, -1, LiveWindowPayload.OP_CREATE);
        return p.isValidForOpenOrUpdate() ? p : null;
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    private static String identifier(Map<String, Object> flat, String... keys) {
        for (String key : keys) {
            Object value = flat.get(key);
            if (value instanceof Number number) return number.toString();
            String id = firstNonEmpty(flat, key);
            if (!id.isEmpty()) return id;
        }
        return "";
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
