package io.github.watchlink.xposed;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class HealthWatchImageResolverTest {
    @Test public void prefersExactColorwayFromHomeBackgroundImageSkuArray() throws Exception {
        String json = """
                {
                  "wear_device_info": {
                    "device_type": 95,
                    "hi_device_type": "02E4",
                    "pair_model_bt_name": ["HUAWEI WATCH 6 Pro"],
                    "home_img_new": "ic_watch6_pro_default",
                    "home_background_img": "ic_watch6_pro_bg_default",
                    "home_background_image_sku": [
                      "ic_watch6_pro_home_02E4-01",
                      "ic_watch6_pro_home_02E4-02",
                      "ic_watch6_pro_home_02E4"
                    ],
                    "entry_image_sku": [
                      "ic_watch6_pro_entry_02E4-02"
                    ]
                  }
                }
                """;
        assertTrue(HealthWatchImageResolver.matchScore(json, 95, "02E4", "WATCH 6 Pro", "HUAWEI WATCH 6 Pro-4A3") >= 100);
        assertEquals(0, HealthWatchImageResolver.matchScore(json, 12, "9999", "Band 9", "HUAWEI Band 9-111"));

        // Exact colorway: hiLinkId + "-" + deviceSubModelId ("02E4-02") must rank FIRST ahead of default home_img_new.
        List<String> ordered = HealthWatchImageResolver.selectOrderedImageNames(json, "02E4", "02", "WATCH 6 Pro");
        assertEquals("ic_watch6_pro_home_02E4-02", ordered.get(0));
        assertEquals("ic_watch6_pro_entry_02E4-02", ordered.get(1));
        assertEquals("ic_watch6_pro_bg_default", ordered.get(2));
        assertEquals("ic_watch6_pro_default", ordered.get(3));

        // When subModelId is absent, hiLinkId without "subId" matches "ic_watch6_pro_home_02E4".
        List<String> noSub = HealthWatchImageResolver.selectOrderedImageNames(json, "02E4", "", "");
        assertEquals("ic_watch6_pro_home_02E4-01", noSub.get(0));
    }

    @Test public void skipsSubIdPlaceholderAndMatchesModelNameInSkuArray() {
        List<String> skus = List.of(
                "home_02E4_subId_titanium",
                "home_WATCH6PRO_GREEN",
                "home_02E4_base"
        );
        assertEquals("home_WATCH6PRO_GREEN",
                HealthWatchImageResolver.matchSkuImage(skus, "02E4", "", "WATCH6PRO_GREEN"));
        assertEquals("home_02E4_base",
                HealthWatchImageResolver.matchSkuImage(skus, "02E4", "", ""));
    }

    @Test public void resolvesNestedUuidImgDirFirst() throws Exception {
        File root = Files.createTempDirectory("wl_plugin_nested").toFile();
        File uuidDir = new File(root, "uuid-1234");
        File nestedImg = new File(new File(uuidDir, "uuid-1234"), "img");
        assertTrue(nestedImg.mkdirs());
        File png = new File(nestedImg, "ic_watch6_pro_home_02E4-02.png");
        Files.write(png.toPath(), new byte[128]);
        try {
            File found = HealthWatchImageResolver.resolveImageInPluginDir(uuidDir, List.of("ic_watch6_pro_home_02E4-02"));
            assertNotNull(found);
            assertEquals(png.getAbsolutePath(), found.getAbsolutePath());
        } finally {
            png.delete();
            nestedImg.delete();
            new File(uuidDir, "uuid-1234").delete();
            uuidDir.delete();
            root.delete();
        }
    }

    @Test public void fallsBackToBestHomePngInImgDirWhenKeyMissing() throws Exception {
        File dir = Files.createTempDirectory("wl_plugin_fallback").toFile();
        File imgDir = new File(dir, "img");
        assertTrue(imgDir.mkdirs());
        File guide = new File(imgDir, "pair_guide_step1.png");
        File home = new File(imgDir, "device_home_img.png");
        Files.write(guide.toPath(), new byte[4096]);
        Files.write(home.toPath(), new byte[1024]);
        try {
            File found = HealthWatchImageResolver.resolveImageInPluginDir(dir, List.of("missing_name"));
            assertNotNull(found);
            assertEquals(home.getAbsolutePath(), found.getAbsolutePath());
        } finally {
            guide.delete();
            home.delete();
            imgDir.delete();
            dir.delete();
        }
    }
}
