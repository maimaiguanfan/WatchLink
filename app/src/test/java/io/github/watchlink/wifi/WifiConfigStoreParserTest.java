package io.github.watchlink.wifi;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WifiConfigStoreParserTest {
    private static final String SAMPLE_XML = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <WifiConfigStoreData>
              <NetworkList>
                <Network>
                  <WifiConfiguration>
                    <string name="SSID">&quot;Home_WPA2&quot;</string>
                    <string name="PreSharedKey">&quot;supersecret2&quot;</string>
                    <boolean name="HiddenSSID" value="false" />
                    <byte-array name="AllowedKeyMgmt" num="1">02</byte-array>
                    <long name="LastConnectedMillIs" value="1700000002000" />
                    <SecurityParamsList>
                      <SecurityParams>
                        <int name="SecurityType" value="2" />
                      </SecurityParams>
                    </SecurityParamsList>
                  </WifiConfiguration>
                </Network>
                <Network>
                  <WifiConfiguration>
                    <string name="SSID">&quot;Cafe_Free_Open&quot;</string>
                    <null name="PreSharedKey" />
                    <boolean name="HiddenSSID" value="false" />
                    <byte-array name="AllowedKeyMgmt" num="1">01</byte-array>
                    <long name="LastConnectedMillIs" value="1700000005000" />
                    <SecurityParamsList>
                      <SecurityParams>
                        <int name="SecurityType" value="0" />
                      </SecurityParams>
                    </SecurityParamsList>
                  </WifiConfiguration>
                </Network>
                <Network>
                  <WifiConfiguration>
                    <string name="SSID">&quot;Studio_WPA3&quot;</string>
                    <string name="PreSharedKey">&quot;wpa3onlypass&quot;</string>
                    <boolean name="HiddenSSID" value="true" />
                    <byte-array name="AllowedKeyMgmt" num="2">0001</byte-array>
                    <long name="LastConnectedMillIs" value="1700000004000" />
                    <SecurityParamsList>
                      <SecurityParams>
                        <int name="SecurityType" value="4" />
                      </SecurityParams>
                    </SecurityParamsList>
                  </WifiConfiguration>
                </Network>
                <Network>
                  <WifiConfiguration>
                    <string name="SSID">&quot;DualMode_23&quot;</string>
                    <string name="PreSharedKey">&quot;transition99&quot;</string>
                    <byte-array name="AllowedKeyMgmt" num="2">0201</byte-array>
                    <long name="LastConnectedMillIs" value="1700000003000" />
                    <SecurityParamsList>
                      <SecurityParams><int name="SecurityType" value="2" /></SecurityParams>
                      <SecurityParams><int name="SecurityType" value="4" /></SecurityParams>
                    </SecurityParamsList>
                  </WifiConfiguration>
                </Network>
                <Network>
                  <WifiConfiguration>
                    <string name="SSID">&quot;Corp_EAP&quot;</string>
                    <null name="PreSharedKey" />
                    <byte-array name="AllowedKeyMgmt" num="1">04</byte-array>
                    <long name="LastConnectedMillIs" value="1700000009000" />
                    <SecurityParamsList>
                      <SecurityParams><int name="SecurityType" value="3" /></SecurityParams>
                    </SecurityParamsList>
                  </WifiConfiguration>
                </Network>
                <Network>
                  <WifiConfiguration>
                    <string name="SSID">E5AEA2E58E85</string>
                    <string name="PreSharedKey">&quot;chineseSsid8&quot;</string>
                    <byte-array name="AllowedKeyMgmt" num="1">02</byte-array>
                  </WifiConfiguration>
                  <NetworkStatus>
                    <long name="LastConnectedTime" value="1700000001000" />
                  </NetworkStatus>
                </Network>
                <Network>
                  <WifiConfiguration>
                    <string name="SSID">&quot;Legacy_WEP&quot;</string>
                    <null name="PreSharedKey" />
                    <string-array name="WEPKeys" num="4">
                      <item value="" />
                      <item value="&quot;12345&quot;" />
                      <item value="" />
                      <item value="" />
                    </string-array>
                    <int name="WEPTxKeyIndex" value="1" />
                    <SecurityParamsList>
                      <SecurityParams><int name="SecurityType" value="1" /></SecurityParams>
                    </SecurityParamsList>
                  </WifiConfiguration>
                </Network>
              </NetworkList>
            </WifiConfigStoreData>
            """;

    @Test public void parsesAllSecurityTypesAllowsOpenNetworksAndSortsSupportedByRecency() throws Exception {
        List<WifiNetworkRecord> list = WifiConfigStoreParser.parse(
                new ByteArrayInputStream(SAMPLE_XML.getBytes(StandardCharsets.UTF_8)));
        assertEquals(7, list.size());

        // Most recently connected supported network first: Open passwordless MUST be selectable.
        WifiNetworkRecord open = list.get(0);
        assertEquals("Cafe_Free_Open", open.ssid());
        assertEquals("", open.password());
        assertEquals(WifiNetworkRecord.ENCODE_OPEN, open.encodeType());
        assertTrue(open.isOpen());
        assertTrue(open.isSupportedByWatch());
        assertEquals("[ESS]", open.capabilities());

        WifiNetworkRecord wpa3 = list.get(1);
        assertEquals("Studio_WPA3", wpa3.ssid());
        assertEquals("wpa3onlypass", wpa3.password());
        assertEquals(WifiNetworkRecord.ENCODE_SAE, wpa3.encodeType());
        assertTrue(wpa3.hidden());
        assertTrue(wpa3.isSupportedByWatch());
        assertEquals("[RSN-SAE-CCMP][ESS]", wpa3.capabilities());

        WifiNetworkRecord dual = list.get(2);
        assertEquals("DualMode_23", dual.ssid());
        assertEquals(WifiNetworkRecord.ENCODE_PSK, dual.encodeType());
        assertTrue(dual.capabilities().contains("PSK"));
        assertTrue(dual.capabilities().contains("SAE"));

        WifiNetworkRecord wpa2 = list.get(3);
        assertEquals("Home_WPA2", wpa2.ssid());
        assertEquals("supersecret2", wpa2.password());
        assertEquals(WifiNetworkRecord.ENCODE_PSK, wpa2.encodeType());

        WifiNetworkRecord chinese = list.get(4);
        assertEquals("客厅", chinese.ssid());
        assertEquals(1700000001000L, chinese.lastConnectedMs());
        assertTrue(chinese.isSupportedByWatch());

        WifiNetworkRecord wep = list.get(5);
        assertEquals("Legacy_WEP", wep.ssid());
        assertEquals("12345", wep.password());
        assertEquals(WifiNetworkRecord.ENCODE_WEP, wep.encodeType());
        assertTrue(wep.isSupportedByWatch());

        // Enterprise EAP sorted at the end and marked unsupported.
        WifiNetworkRecord eap = list.get(6);
        assertEquals("Corp_EAP", eap.ssid());
        assertEquals(WifiNetworkRecord.ENCODE_EAP, eap.encodeType());
        assertTrue(eap.isEnterprise());
        assertFalse(eap.isSupportedByWatch());
    }

    @Test public void redactsSsidAndPasswordFromToStringAndScopesFingerprintsByDevice() {
        WifiNetworkRecord rec = new WifiNetworkRecord(
                "PrivateHome", "MySecretPass!", WifiNetworkRecord.ENCODE_PSK,
                "[WPA2-PSK-CCMP][ESS]", false, 100L);
        String repr = rec.toString();
        assertFalse(repr.contains("PrivateHome"));
        assertFalse(repr.contains("MySecretPass!"));

        String fp1 = rec.syncFingerprint("watch-token-a");
        String fp2 = rec.syncFingerprint("watch-token-b");
        assertEquals(64, fp1.length());
        assertTrue(fp1.matches("[0-9a-f]{64}"));
        assertNotEquals(fp1, fp2);
    }

    @Test public void toleratesAndroidHarmonyUnsupportedXIncludeFactory() {
        javax.xml.parsers.DocumentBuilderFactory stub = new javax.xml.parsers.DocumentBuilderFactory() {
            @Override public javax.xml.parsers.DocumentBuilder newDocumentBuilder() { return null; }
            @Override public void setAttribute(String name, Object value) {}
            @Override public Object getAttribute(String name) { return null; }
            @Override public void setFeature(String name, boolean value)
                    throws javax.xml.parsers.ParserConfigurationException {
                throw new javax.xml.parsers.ParserConfigurationException("unsupported");
            }
            @Override public boolean getFeature(String name) { return false; }
            // Inherits default DocumentBuilderFactory.setXIncludeAware which throws UnsupportedOperationException.
        };
        WifiConfigStoreParser.disableExternalEntities(stub);
    }
}
