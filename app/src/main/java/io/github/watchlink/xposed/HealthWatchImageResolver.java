package io.github.watchlink.xposed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import io.github.watchlink.alarm.probe.ProbeLog;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the connected Huawei wearable's official HD same-colorway device-page image directly
 * from Huawei Health's local EzPlugin cache (filesDir/plugins/<uuid>/description.json + img/).
 *
 * Implements the exact SKU colorway selection algorithm verified against Huawei Health 17.0.10.330
 * (DeviceDisplayViewModel$5 -> Lesa.c(eqv, 2, deviceInfo) -> Lesa.b):
 * 1) home_background_image_sku JSON array matched by:
 *    a) hiLinkDeviceId + "-" + deviceSubModelId
 *    b) deviceModel
 *    c) hiLinkDeviceId (excluding "subId")
 * 2) entry_image_sku JSON array with the same 3 rules
 * 3) home_background_img -> home_img_new -> entry_img -> des_img -> update_img
 *
 * Encodes at crisp 512px HD resolution using alpha-preserving WebP (API 30+) so full-detail watch
 * renders stay ~30-60 KB for fast, lossless-looking dual-host IPC.
 */
final class HealthWatchImageResolver {
    static final int MAX_ICON_EDGE_PX = 512;
    static final int MAX_ICON_BYTES = 200 * 1024;
    private static final int MAX_SCAN_DEPTH = 5;
    private static final int MAX_JSON_BYTES = 512 * 1024;
    private static final int MAX_VISITED_DIRS = 2000;

    private static final String[] SCALAR_IMAGE_KEYS = {
            "home_background_img",
            "home_img_new",
            "entry_img",
            "des_img",
            "update_img",
            "background_img"
    };

    private static final String[] SKU_ARRAY_KEYS = {
            "home_background_image_sku",
            "entry_image_sku"
    };

    record WatchIcon(byte[] pngBytes, String hash) {
        boolean valid() {
            return pngBytes != null && pngBytes.length > 0 && pngBytes.length <= MAX_ICON_BYTES
                    && hash != null && !hash.isEmpty();
        }
    }

    private final Context app;
    private String lastLookupKey = "";
    private WatchIcon cachedIcon;
    private long lastAttemptElapsedMs;

    HealthWatchImageResolver(Context app) {
        this.app = app.getApplicationContext();
    }

    /** Resolves (and caches) the connected watch's HD same-colorway icon on a background worker thread. */
    synchronized WatchIcon resolveForWatch(int productType, String hiLinkId, String deviceModel,
                                           String deviceSubModelId, String watchName) {
        String lookupKey = productType + "|" + safe(hiLinkId) + "|" + safe(deviceModel)
                + "|" + safe(deviceSubModelId) + "|" + safe(watchName);
        if (lookupKey.equals(lastLookupKey)) {
            if (cachedIcon != null && cachedIcon.valid()) return cachedIcon;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastAttemptElapsedMs < 30_000L) return null;
        }
        lastLookupKey = lookupKey;
        lastAttemptElapsedMs = android.os.SystemClock.elapsedRealtime();

        File imageFile = findWatchImageFile(productType, hiLinkId, deviceModel, deviceSubModelId, watchName);
        if (imageFile == null || !imageFile.isFile()) {
            ProbeLog.info("HEALTH_WATCH_ICON_MISS source=HEALTH");
            return null;
        }
        WatchIcon encoded = loadAndEncodeHdIcon(imageFile);
        if (encoded != null && encoded.valid()) {
            cachedIcon = encoded;
            ProbeLog.info("HEALTH_WATCH_ICON_RESOLVED source=HEALTH");
            return encoded;
        }
        ProbeLog.info("HEALTH_WATCH_ICON_MISS source=HEALTH");
        return null;
    }

    private File findWatchImageFile(int productType, String hiLinkId, String deviceModel,
                                    String deviceSubModelId, String watchName) {
        File filesDir = app.getFilesDir();
        if (filesDir == null || !filesDir.isDirectory()) return null;

        // Verified against Health Lqrn.<clinit>: Lqrn.a = filesDir/plugins, Lqrn.c = filesDir/healthdevice/products.
        List<File> roots = new ArrayList<>();
        addIfDir(roots, new File(filesDir, "plugins"));
        addIfDir(roots, new File(new File(filesDir, "healthdevice"), "products"));
        addIfDir(roots, new File(filesDir, "lightcloud"));
        addIfDir(roots, new File(filesDir, "ezplugin"));
        roots.add(filesDir);

        int[] budget = new int[]{MAX_VISITED_DIRS};
        File bestFallback = null;
        int bestScore = 0;

        for (File root : roots) {
            if (budget[0] <= 0) break;
            File[] matchHolder = new File[1];
            int[] scoreHolder = new int[]{bestScore};
            scanForDescription(root, 0, budget, productType, hiLinkId, deviceModel, deviceSubModelId, watchName,
                    matchHolder, scoreHolder);
            if (matchHolder[0] != null && scoreHolder[0] > bestScore) {
                bestScore = scoreHolder[0];
                bestFallback = matchHolder[0];
                if (bestScore >= 100) return bestFallback;
            }
        }
        return bestFallback;
    }

    private static void addIfDir(List<File> roots, File dir) {
        if (dir != null && dir.isDirectory() && !roots.contains(dir)) {
            roots.add(dir);
        }
    }

    private static void scanForDescription(
            File dir, int depth, int[] budget,
            int productType, String hiLinkId, String deviceModel, String deviceSubModelId, String watchName,
            File[] bestFile, int[] bestScore) {
        if (dir == null || !dir.isDirectory() || depth > MAX_SCAN_DEPTH || budget[0] <= 0) return;
        budget[0]--;

        File desc = new File(dir, "description.json");
        if (desc.isFile() && desc.length() > 0 && desc.length() <= MAX_JSON_BYTES) {
            String json = readUtf8(desc);
            int score = matchScore(json, productType, hiLinkId, deviceModel, watchName);
            if (score > bestScore[0]) {
                List<String> candidates = selectOrderedImageNames(json, hiLinkId, deviceSubModelId, deviceModel);
                File img = resolveImageInPluginDir(dir, candidates);
                if (img != null) {
                    bestScore[0] = score;
                    bestFile[0] = img;
                    if (score >= 100) return;
                }
            }
            return;
        }

        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (budget[0] <= 0 || bestScore[0] >= 100) return;
            if (!child.isDirectory()) continue;
            String n = child.getName();
            if ("cache".equals(n) || "code_cache".equals(n) || "lib".equals(n)
                    || "oat".equals(n) || "shared_prefs".equals(n) || "databases".equals(n)
                    || "index".equals(n)) {
                continue;
            }
            scanForDescription(child, depth + 1, budget, productType, hiLinkId, deviceModel, deviceSubModelId,
                    watchName, bestFile, bestScore);
        }
    }

    /** Scores how strongly a Huawei Health plugin description.json matches the connected watch (0 = no match). */
    static int matchScore(String json, int productType, String hiLinkId, String deviceModel, String watchName) {
        if (json == null || json.isEmpty()) return 0;
        boolean hasAnyImgKey = false;
        for (String k : SCALAR_IMAGE_KEYS) {
            if (json.contains(k)) { hasAnyImgKey = true; break; }
        }
        if (!hasAnyImgKey) {
            for (String k : SKU_ARRAY_KEYS) {
                if (json.contains(k)) { hasAnyImgKey = true; break; }
            }
        }
        if (!hasAnyImgKey && !json.contains("wear_device_info")) {
            return 0;
        }
        int score = 0;
        if (productType > 0) {
            Pattern p = Pattern.compile("\"(?:device_type|hi_device_type)\"\\s*:\\s*\"?" + productType + "\"?\\b");
            if (p.matcher(json).find()) score += 100;
        }
        String hi = safe(hiLinkId);
        if (hi.length() >= 3) {
            if (containsIgnoreCase(json, "\"" + hi + "\"")) score += 90;
        }
        String model = safe(deviceModel);
        if (model.length() >= 3 && containsIgnoreCase(json, model)) {
            score += 60;
        }
        String name = safe(watchName);
        if (name.length() >= 4) {
            if (containsIgnoreCase(json, name)) {
                score += 50;
            } else {
                int dash = name.lastIndexOf('-');
                String prefix = dash >= 4 ? name.substring(0, dash).trim() : name;
                if (prefix.length() >= 4 && containsIgnoreCase(json, prefix)) {
                    score += 45;
                } else if (prefix.toUpperCase(Locale.ROOT).startsWith("HUAWEI ") && prefix.length() > 10) {
                    String withoutBrand = prefix.substring(7).trim();
                    if (withoutBrand.length() >= 4 && containsIgnoreCase(json, withoutBrand)) {
                        score += 35;
                    }
                }
            }
        }
        return score;
    }

    /**
     * Selects candidate image filenames in exact Huawei Health DeviceDisplayViewModel / Lesa.b priority:
     * matched SKU from home_background_image_sku -> matched SKU from entry_image_sku ->
     * home_background_img -> home_img_new -> entry_img -> des_img -> update_img.
     */
    static List<String> selectOrderedImageNames(String json, String hiLinkId,
                                                String deviceSubModelId, String deviceModel) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        List<String> ordered = new ArrayList<>();

        // 1. Exact colorway SKU match from home_background_image_sku & entry_image_sku (JSON arrays).
        for (String skuKey : SKU_ARRAY_KEYS) {
            List<String> skuItems = extractStringArray(json, skuKey);
            String matched = matchSkuImage(skuItems, hiLinkId, deviceSubModelId, deviceModel);
            if (matched != null && !matched.isEmpty() && !ordered.contains(matched)) {
                ordered.add(matched);
            }
        }

        // 2. Scalar device-page images (home_background_img > home_img_new > entry_img > ...).
        for (String key : SCALAR_IMAGE_KEYS) {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]{1,160})\"");
            Matcher m = p.matcher(json);
            while (m.find()) {
                String val = m.group(1).trim();
                if (!val.isEmpty() && !ordered.contains(val)) {
                    ordered.add(val);
                }
            }
        }
        return ordered;
    }

    /** Exact Lesa.b(defaultImage, skuList, deviceInfo) colorway matcher verified from Health classes7.dex. */
    static String matchSkuImage(List<String> skuItems, String hiLinkId,
                                String deviceSubModelId, String deviceModel) {
        if (skuItems == null || skuItems.isEmpty()) return null;
        String hi = safe(hiLinkId);
        String sub = safe(deviceSubModelId);
        String model = safe(deviceModel);

        // Pass 1: exact hiLinkDeviceId + "-" + deviceSubModelId (exact case first, then ignore-case).
        if (!hi.isEmpty() && !sub.isEmpty()) {
            String exactKey = hi + "-" + sub;
            for (String item : skuItems) {
                if (item.contains(exactKey)) return item;
            }
            for (String item : skuItems) {
                if (containsIgnoreCase(item, exactKey)) return item;
            }
        }
        // Pass 2: deviceModel match (exact case first, then ignore-case).
        if (!model.isEmpty()) {
            for (String item : skuItems) {
                if (item.contains(model)) return item;
            }
            for (String item : skuItems) {
                if (containsIgnoreCase(item, model)) return item;
            }
        }
        // Pass 3: hiLinkDeviceId without "subId" suffix.
        if (!hi.isEmpty()) {
            for (String item : skuItems) {
                if (item.contains(hi) && !item.contains("subId")) return item;
            }
            for (String item : skuItems) {
                if (containsIgnoreCase(item, hi) && !containsIgnoreCase(item, "subId")) return item;
            }
        }
        return null;
    }

    static List<String> extractStringArray(String json, String key) {
        if (json == null || json.isEmpty() || key == null || key.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> items = new ArrayList<>();
        Pattern arr = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]{0,8192})\\]");
        Matcher am = arr.matcher(json);
        while (am.find()) {
            Pattern elem = Pattern.compile("\"([^\"]{1,200})\"");
            Matcher em = elem.matcher(am.group(1));
            while (em.find()) {
                String v = em.group(1).trim();
                if (!v.isEmpty() && !items.contains(v)) {
                    items.add(v);
                }
            }
        }
        return items;
    }

    static File resolveImageInPluginDir(File pluginDir, List<String> candidates) {
        if (pluginDir == null || !pluginDir.isDirectory()) return null;
        File imgDir = new File(pluginDir, "img");
        // EzPluginManager.b / DeviceDisplayViewModel$5 check <uuid>/<uuid>/img/ inside extracted plugin folders.
        List<File> searchDirs = new ArrayList<>();
        File nestedUuidDir = new File(pluginDir, pluginDir.getName());
        File nestedImgDir = new File(nestedUuidDir, "img");
        if (nestedImgDir.isDirectory()) searchDirs.add(nestedImgDir);
        if (imgDir.isDirectory()) searchDirs.add(imgDir);
        searchDirs.add(pluginDir);

        if (candidates != null) {
            for (String raw : candidates) {
                String clean = raw.replace('\\', '/');
                int slash = clean.lastIndexOf('/');
                String base = slash >= 0 ? clean.substring(slash + 1) : clean;
                if (base.isEmpty() || base.contains("..")) continue;
                for (File d : searchDirs) {
                    File direct = new File(d, base);
                    if (direct.isFile() && direct.length() > 64) return direct;
                    if (!base.endsWith(".png")) {
                        File png = new File(d, base + ".png");
                        if (png.isFile() && png.length() > 64) return png;
                    }
                    if (!base.endsWith(".webp")) {
                        File webp = new File(d, base + ".webp");
                        if (webp.isFile() && webp.length() > 64) return webp;
                    }
                }
            }
        }
        return pickBestFallbackImage(searchDirs);
    }

    private static File pickBestFallbackImage(List<File> dirs) {
        File best = null;
        int bestRank = -1;
        long bestSize = 0L;
        for (File dir : dirs) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || f.length() <= 512) continue;
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".png") && !lower.endsWith(".webp")) continue;
                if (lower.contains("guide") || lower.contains("step") || lower.contains("operator")
                        || lower.contains("cmcc") || lower.contains("ctcc") || lower.contains("cucc")
                        || lower.contains("ear_muff") || lower.contains("esim")) {
                    continue;
                }
                int rank = 1;
                if (lower.contains("home_background") || lower.contains("home_img") || lower.contains("home_new")) rank = 5;
                else if (lower.contains("home")) rank = 4;
                else if (lower.contains("entry")) rank = 3;
                else if (lower.contains("device") || lower.contains("watch")) rank = 2;
                if (rank > bestRank || (rank == bestRank && f.length() > bestSize)) {
                    bestRank = rank;
                    bestSize = f.length();
                    best = f;
                }
            }
        }
        return best;
    }

    private static WatchIcon loadAndEncodeHdIcon(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / (sample * 2) >= MAX_ICON_EDGE_PX) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (decoded == null) return null;

            Bitmap scaled = decoded;
            int w = decoded.getWidth();
            int h = decoded.getHeight();
            if (Math.max(w, h) > MAX_ICON_EDGE_PX) {
                float ratio = (float) MAX_ICON_EDGE_PX / (float) Math.max(w, h);
                int nw = Math.max(1, Math.round(w * ratio));
                int nh = Math.max(1, Math.round(h * ratio));
                scaled = Bitmap.createScaledBitmap(decoded, nw, nh, true);
                if (scaled != decoded) decoded.recycle();
            }

            byte[] bytes = compressHd(scaled, 92);
            if (bytes.length > MAX_ICON_BYTES) {
                bytes = compressHd(scaled, 84);
            }
            if (bytes.length > MAX_ICON_BYTES) {
                int maxEdge = Math.max(scaled.getWidth(), scaled.getHeight());
                if (maxEdge > 384) {
                    Bitmap medium = Bitmap.createScaledBitmap(scaled,
                            Math.max(1, scaled.getWidth() * 384 / maxEdge),
                            Math.max(1, scaled.getHeight() * 384 / maxEdge),
                            true);
                    if (medium != scaled) scaled.recycle();
                    scaled = medium;
                    bytes = compressHd(scaled, 88);
                }
            }
            scaled.recycle();
            if (bytes.length == 0 || bytes.length > MAX_ICON_BYTES) return null;
            return new WatchIcon(bytes, shortSha256(bytes));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] compressHd(Bitmap bitmap, int webpQuality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        if (Build.VERSION.SDK_INT >= 30) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, webpQuality, out);
        } else {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return out.toByteArray();
    }

    private static String readUtf8(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = in.readAllBytes();
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String shortSha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
