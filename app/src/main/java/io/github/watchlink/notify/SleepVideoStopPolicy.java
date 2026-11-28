package io.github.watchlink.notify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-Java policy and package catalog for fall-asleep auto-stopping short-video and live-streaming
 * apps (e.g., Douyin, Bilibili, Hongguo Short Drama, Kuaishou, Xiaohongshu, Huya, Douyu) plus
 * user-defined custom package names.
 */
public final class SleepVideoStopPolicy {
    public static final int MAX_CUSTOM_PACKAGES = 64;
    private static final Pattern PACKAGE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");

    public record PresetApp(String packageName, String label) {}

    public static final List<PresetApp> DEFAULT_PRESETS = List.of(
            new PresetApp("com.ss.android.ugc.aweme", "抖音"),
            new PresetApp("com.ss.android.ugc.aweme.lite", "抖音极速版"),
            new PresetApp("com.ss.android.ugc.live", "抖音火山版"),
            new PresetApp("tv.danmaku.bili", "哔哩哔哩"),
            new PresetApp("com.bilibili.app.blue", "B站概念版"),
            new PresetApp("com.phoenix.read", "红果免费短剧"),
            new PresetApp("com.smile.gifmaker", "快手"),
            new PresetApp("com.kuaishou.nebula", "快手极速版"),
            new PresetApp("com.xingin.xhs", "小红书"),
            new PresetApp("com.duowan.kiwi", "虎牙直播"),
            new PresetApp("air.tv.douyu.android", "斗鱼直播"),
            new PresetApp("com.tencent.qqlive", "腾讯视频"),
            new PresetApp("com.qiyi.video", "爱奇艺"),
            new PresetApp("com.youku.phone", "优酷"),
            new PresetApp("com.hunantv.imgo.activity", "芒果TV")
    );

    public static final Set<String> DEFAULT_PACKAGES;
    static {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (PresetApp app : DEFAULT_PRESETS) {
            set.add(app.packageName());
        }
        DEFAULT_PACKAGES = Collections.unmodifiableSet(set);
    }

    public enum Decision {
        DISABLED,
        IGNORE_ACTION,
        SKIP_IN_CALL,
        SKIP_NO_TARGET,
        STOP_MATCHED_SESSION,
        STOP_SCREEN_ON_VIDEO,
        STOP_ACTIVE_AUDIO_VIDEO
    }

    public static boolean isValidPackageName(String raw) {
        if (raw == null) return false;
        String trimmed = raw.trim();
        return trimmed.length() >= 3 && trimmed.length() <= 128
                && PACKAGE_NAME_PATTERN.matcher(trimmed).matches();
    }

    /** Parses newline/comma/semicolon/whitespace-separated custom package names into a deduplicated list. */
    public static List<String> parseCustomPackages(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String[] tokens = raw.split("[\\s,;]+");
        for (String token : tokens) {
            String trimmed = token == null ? "" : token.trim();
            if (isValidPackageName(trimmed)) {
                out.add(trimmed);
                if (out.size() >= MAX_CUSTOM_PACKAGES) break;
            }
        }
        return new ArrayList<>(out);
    }

    public static String serializeCustomPackages(List<String> packages) {
        if (packages == null || packages.isEmpty()) return "";
        LinkedHashSet<String> valid = new LinkedHashSet<>();
        for (String pkg : packages) {
            if (isValidPackageName(pkg)) {
                valid.add(pkg.trim());
                if (valid.size() >= MAX_CUSTOM_PACKAGES) break;
            }
        }
        return String.join("\n", valid);
    }

    public static String addCustomPackage(String existingRaw, String candidate) {
        if (!isValidPackageName(candidate)) return serializeCustomPackages(parseCustomPackages(existingRaw));
        List<String> list = new ArrayList<>(parseCustomPackages(existingRaw));
        String cleaned = candidate.trim();
        if (!list.contains(cleaned) && list.size() < MAX_CUSTOM_PACKAGES) {
            list.add(cleaned);
        }
        return serializeCustomPackages(list);
    }

    public static String removeCustomPackage(String existingRaw, String target) {
        if (target == null || target.isBlank()) return serializeCustomPackages(parseCustomPackages(existingRaw));
        List<String> list = new ArrayList<>(parseCustomPackages(existingRaw));
        list.remove(target.trim());
        return serializeCustomPackages(list);
    }

    public static Set<String> combinedTargetPackages(String customRaw) {
        LinkedHashSet<String> all = new LinkedHashSet<>(DEFAULT_PACKAGES);
        all.addAll(parseCustomPackages(customRaw));
        return Collections.unmodifiableSet(all);
    }

    /**
     * Evaluates whether fall-asleep should stop video/live playback and exit to Home.
     * Never interrupts voice/VoIP calls and avoids kicking unrelated music/podcast apps to Home.
     */
    public static Decision evaluate(
            boolean enabled,
            int sleepAction,
            boolean inCall,
            boolean matchedTargetMediaSession,
            boolean hasNonTargetActiveSession,
            boolean screenUnlocked,
            boolean musicActive,
            boolean hasInstalledTarget) {
        if (!enabled) return Decision.DISABLED;
        if (sleepAction != SleepDndCoordinator.ACTION_FALL_ASLEEP) return Decision.IGNORE_ACTION;
        if (inCall) return Decision.SKIP_IN_CALL;
        if (matchedTargetMediaSession) return Decision.STOP_MATCHED_SESSION;
        if (!hasInstalledTarget || hasNonTargetActiveSession) return Decision.SKIP_NO_TARGET;
        if (screenUnlocked) return Decision.STOP_SCREEN_ON_VIDEO;
        if (musicActive) return Decision.STOP_ACTIVE_AUDIO_VIDEO;
        return Decision.SKIP_NO_TARGET;
    }

    public static Map<String, String> defaultPresetMap() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (PresetApp app : DEFAULT_PRESETS) {
            map.put(app.packageName(), app.label());
        }
        return Collections.unmodifiableMap(map);
    }

    private SleepVideoStopPolicy() {}
}
