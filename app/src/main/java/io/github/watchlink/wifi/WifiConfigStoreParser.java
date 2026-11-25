package io.github.watchlink.wifi;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Pure-Java parser for Android 12-17 /apex/com.android.wifi WifiConfigStore.xml.
 * Runs in memory only; never writes files or logs credentials.
 */
public final class WifiConfigStoreParser {
    // Android WifiConfiguration.SECURITY_TYPE_* constants (API 30-37).
    private static final int SEC_OPEN = 0;
    private static final int SEC_WEP = 1;
    private static final int SEC_PSK = 2;
    private static final int SEC_EAP = 3;
    private static final int SEC_SAE = 4;
    private static final int SEC_EAP_WPA3_ENTERPRISE_192_BIT = 5;
    private static final int SEC_OWE = 6;
    private static final int SEC_WAPI_PSK = 7;
    private static final int SEC_WAPI_CERT = 8;
    private static final int SEC_EAP_WPA3_ENTERPRISE = 9;
    private static final int SEC_PASSPOINT_R1_R2 = 11;
    private static final int SEC_PASSPOINT_R3 = 12;

    // Android WifiConfiguration.KeyMgmt bit indices.
    private static final int KM_NONE = 0;
    private static final int KM_WPA_PSK = 1;
    private static final int KM_WPA_EAP = 2;
    private static final int KM_IEEE8021X = 3;
    private static final int KM_WPA2_PSK = 4;
    private static final int KM_SAE = 8;
    private static final int KM_OWE = 9;
    private static final int KM_SUITE_B_192 = 10;
    private static final int KM_FT_PSK = 11;
    private static final int KM_FT_EAP = 12;
    private static final int KM_WAPI_PSK = 13;
    private static final int KM_WAPI_CERT = 14;

    private WifiConfigStoreParser() {}

    public static List<WifiNetworkRecord> parse(InputStream xmlStream) throws IOException {
        if (xmlStream == null) throw new IOException("Null WifiConfigStore stream");
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            disableExternalEntities(factory);
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(xmlStream);
        } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("Malformed WifiConfigStore XML", error);
        }

        Map<String, WifiNetworkRecord> dedup = new LinkedHashMap<>();
        NodeList networkNodes = doc.getElementsByTagName("Network");
        for (int i = 0; i < networkNodes.getLength(); i++) {
            Node node = networkNodes.item(i);
            if (!(node instanceof Element networkEl)) continue;
            WifiNetworkRecord parsed = parseNetwork(networkEl);
            if (parsed == null) continue;
            String key = parsed.selectionKey();
            WifiNetworkRecord existing = dedup.get(key);
            if (existing == null || isBetterDuplicate(parsed, existing)) {
                dedup.put(key, parsed);
            }
        }

        List<WifiNetworkRecord> results = new ArrayList<>(dedup.values());
        results.sort(Comparator
                .comparing(WifiNetworkRecord::isSupportedByWatch, Comparator.reverseOrder())
                .thenComparing(WifiNetworkRecord::lastConnectedMs, Comparator.reverseOrder())
                .thenComparing(WifiNetworkRecord::ssid, String.CASE_INSENSITIVE_ORDER));
        return results;
    }

    private static boolean isBetterDuplicate(WifiNetworkRecord candidate, WifiNetworkRecord existing) {
        if (candidate.isSupportedByWatch() != existing.isSupportedByWatch()) {
            return candidate.isSupportedByWatch();
        }
        if (candidate.lastConnectedMs() != existing.lastConnectedMs()) {
            return candidate.lastConnectedMs() > existing.lastConnectedMs();
        }
        return !candidate.password().isEmpty() && existing.password().isEmpty();
    }

    private static WifiNetworkRecord parseNetwork(Element networkEl) {
        Element wifiConfig = firstDirectChild(networkEl, "WifiConfiguration");
        if (wifiConfig == null) return null;

        String rawSsid = readString(wifiConfig, "SSID");
        String ssid = WifiNetworkRecord.decodeSsid(rawSsid);
        if (ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid)) return null;

        String rawPsk = readString(wifiConfig, "PreSharedKey");
        String psk = rawPsk == null ? "" : WifiNetworkRecord.unquote(rawPsk);
        int wepIndex = readInt(wifiConfig, "WEPTxKeyIndex", 0);
        String wepKey = readWepKey(wifiConfig, wepIndex);
        boolean hidden = readBoolean(wifiConfig, "HiddenSSID", false);

        long lastConnected = readBestTimestamp(wifiConfig);
        Element statusEl = firstDirectChild(networkEl, "NetworkStatus");
        if (statusEl != null) {
            lastConnected = Math.max(lastConnected, readBestTimestamp(statusEl));
        }

        Set<Integer> securityTypes = readSecurityTypes(wifiConfig);
        BitSet keyMgmt = readBitSet(wifiConfig, "AllowedKeyMgmt");

        SecurityClassification cls = classify(securityTypes, keyMgmt, psk, wepKey);
        String password = switch (cls.encodeType) {
            case WifiNetworkRecord.ENCODE_OPEN -> "";
            case WifiNetworkRecord.ENCODE_WEP -> wepKey;
            default -> psk;
        };
        String capabilities = WifiNetworkRecord.synthesizeCapabilities(
                cls.encodeType, cls.wpa3Transition, cls.owe);
        return new WifiNetworkRecord(ssid, password, cls.encodeType, capabilities, hidden, lastConnected);
    }

    private record SecurityClassification(int encodeType, boolean wpa3Transition, boolean owe) {}

    private static SecurityClassification classify(Set<Integer> secTypes, BitSet keyMgmt,
                                                   String psk, String wepKey) {
        boolean hasEap = secTypes.contains(SEC_EAP)
                || secTypes.contains(SEC_EAP_WPA3_ENTERPRISE)
                || secTypes.contains(SEC_EAP_WPA3_ENTERPRISE_192_BIT)
                || secTypes.contains(SEC_WAPI_CERT)
                || secTypes.contains(SEC_PASSPOINT_R1_R2)
                || secTypes.contains(SEC_PASSPOINT_R3)
                || keyMgmt.get(KM_WPA_EAP)
                || keyMgmt.get(KM_IEEE8021X)
                || keyMgmt.get(KM_SUITE_B_192)
                || keyMgmt.get(KM_FT_EAP)
                || keyMgmt.get(KM_WAPI_CERT);
        boolean hasPsk = secTypes.contains(SEC_PSK)
                || secTypes.contains(SEC_WAPI_PSK)
                || keyMgmt.get(KM_WPA_PSK)
                || keyMgmt.get(KM_WPA2_PSK)
                || keyMgmt.get(KM_FT_PSK)
                || keyMgmt.get(KM_WAPI_PSK);
        boolean hasSae = secTypes.contains(SEC_SAE) || keyMgmt.get(KM_SAE);
        boolean hasWep = secTypes.contains(SEC_WEP) || !wepKey.isEmpty();
        boolean hasOwe = secTypes.contains(SEC_OWE) || keyMgmt.get(KM_OWE);
        boolean hasOpen = secTypes.contains(SEC_OPEN) || keyMgmt.get(KM_NONE);

        if (hasEap && !hasPsk && !hasSae) {
            return new SecurityClassification(WifiNetworkRecord.ENCODE_EAP, false, false);
        }
        if (hasPsk && hasSae) {
            // Matches Huawei Loan.b(ScanResult) priority: PSK encodeType with both capabilities.
            return new SecurityClassification(WifiNetworkRecord.ENCODE_PSK, true, false);
        }
        if (hasSae) {
            return new SecurityClassification(WifiNetworkRecord.ENCODE_SAE, false, false);
        }
        if (hasPsk) {
            return new SecurityClassification(WifiNetworkRecord.ENCODE_PSK, false, false);
        }
        if (hasWep) {
            return new SecurityClassification(WifiNetworkRecord.ENCODE_WEP, false, false);
        }
        if (hasOpen || hasOwe) {
            return new SecurityClassification(WifiNetworkRecord.ENCODE_OPEN, false, hasOwe && !hasOpen);
        }
        if (!psk.isEmpty()) {
            return new SecurityClassification(WifiNetworkRecord.ENCODE_PSK, false, false);
        }
        return new SecurityClassification(WifiNetworkRecord.ENCODE_OPEN, false, false);
    }

    private static Set<Integer> readSecurityTypes(Element wifiConfig) {
        Set<Integer> types = new LinkedHashSet<>();
        Element list = firstDirectChild(wifiConfig, "SecurityParamsList");
        if (list == null) return types;
        NodeList children = list.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element paramEl) || !"SecurityParams".equals(paramEl.getTagName())) continue;
            // Skip auto-upgraded optional entries when deciding if a network is purely one mode,
            // except keep SAE/OWE transition info so WPA2/WPA3 transition is captured accurately.
            int secType = readInt(paramEl, "SecurityType", -1);
            if (secType >= 0) types.add(secType);
        }
        return types;
    }

    private static long readBestTimestamp(Element parent) {
        long best = 0L;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element el) || !"long".equals(el.getTagName())) continue;
            String name = el.getAttribute("name");
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.US);
            if (lower.equals("lastconnectedmillis") || lower.equals("lastconnectedtime")
                    || lower.equals("lastconnected") || lower.equals("lastupdated")
                    || lower.equals("lastupdatetime")) {
                long val = parseLong(el.getAttribute("value"), 0L);
                if (val > best) best = val;
            }
        }
        return best;
    }

    private static String readWepKey(Element wifiConfig, int txIndex) {
        NodeList children = wifiConfig.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element el) || !"string-array".equals(el.getTagName())) continue;
            if (!"WEPKeys".equals(el.getAttribute("name"))) continue;
            List<String> keys = new ArrayList<>();
            NodeList items = el.getChildNodes();
            for (int j = 0; j < items.getLength(); j++) {
                Node item = items.item(j);
                if (item instanceof Element itemEl && "item".equals(itemEl.getTagName())) {
                    String value = itemEl.getAttribute("value");
                    keys.add(value == null ? "" : WifiNetworkRecord.unquote(value));
                }
            }
            if (txIndex >= 0 && txIndex < keys.size() && !keys.get(txIndex).isEmpty()) {
                return keys.get(txIndex);
            }
            for (String candidate : keys) {
                if (!candidate.isEmpty()) return candidate;
            }
        }
        return "";
    }

    private static BitSet readBitSet(Element parent, String attrName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element el) || !"byte-array".equals(el.getTagName())) continue;
            if (!attrName.equals(el.getAttribute("name"))) continue;
            String hex = el.getTextContent();
            if (hex == null) return new BitSet();
            hex = hex.trim();
            if (hex.isEmpty() || (hex.length() & 1) != 0 || !hex.matches("[0-9a-fA-F]+")) {
                return new BitSet();
            }
            byte[] bytes = new byte[hex.length() / 2];
            for (int b = 0; b < bytes.length; b++) {
                int hi = Character.digit(hex.charAt(b * 2), 16);
                int lo = Character.digit(hex.charAt(b * 2 + 1), 16);
                bytes[b] = (byte) ((hi << 4) | lo);
            }
            return BitSet.valueOf(bytes);
        }
        return new BitSet();
    }

    private static String readString(Element parent, String attrName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element el)) continue;
            if (!attrName.equals(el.getAttribute("name"))) continue;
            if ("null".equals(el.getTagName())) return null;
            if ("string".equals(el.getTagName())) {
                return el.getTextContent();
            }
        }
        return null;
    }

    private static int readInt(Element parent, String attrName, int fallback) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element el) || !"int".equals(el.getTagName())) continue;
            if (!attrName.equals(el.getAttribute("name"))) continue;
            try {
                return Integer.parseInt(el.getAttribute("value").trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean readBoolean(Element parent, String attrName, boolean fallback) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element el) || !"boolean".equals(el.getTagName())) continue;
            if (!attrName.equals(el.getAttribute("name"))) continue;
            String val = el.getAttribute("value");
            if ("true".equalsIgnoreCase(val)) return true;
            if ("false".equalsIgnoreCase(val)) return false;
            return fallback;
        }
        return fallback;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Element firstDirectChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el && tag.equals(el.getTagName())) return el;
        }
        return null;
    }

    static void disableExternalEntities(DocumentBuilderFactory factory) {
        trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            factory.setXIncludeAware(false);
        } catch (RuntimeException | AbstractMethodError ignored) {
            // Android's Harmony DocumentBuilderFactoryImpl throws UnsupportedOperationException.
        }
        try {
            factory.setExpandEntityReferences(false);
        } catch (RuntimeException | AbstractMethodError ignored) {
            // Ignore if unsupported by the runtime XML parser.
        }
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | RuntimeException | AbstractMethodError ignored) {
            // Android's Expat/XMLPull DOM implementation ignores desktop Xerces-only features.
        }
    }
}
