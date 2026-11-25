package io.github.watchlink.wifi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * On-demand Root reader for Route 1A bulk initial Wi-Fi sync.
 * Only invoked explicitly by the user inside WatchLink UI on a background IO thread.
 * Reads WifiConfigStore.xml directly into memory, never writes temp files, and never logs contents.
 */
public final class RootWifiConfigReader {
    public static final String WIFI_CONFIG_STORE_PATH =
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml";
    private static final long TIMEOUT_SECONDS = 15L;
    private static final int MAX_XML_BYTES = 4 * 1024 * 1024; // 4 MiB safety cap

    public enum Status {
        OK,
        EMPTY,
        ROOT_DENIED,
        FILE_UNAVAILABLE,
        PARSE_ERROR,
        TIMEOUT,
    }

    public record Result(Status status, List<WifiNetworkRecord> networks) {
        public static Result error(Status status) {
            return new Result(status, Collections.emptyList());
        }
    }

    private RootWifiConfigReader() {}

    public static Result readSavedNetworks() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "cat " + WIFI_CONFIG_STORE_PATH).start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream(65_536);
            ByteArrayOutputStream stderr = new ByteArrayOutputStream(1024);

            Process proc = process;
            Thread errReader = new Thread(() -> drainBounded(proc.getErrorStream(), stderr, 4096),
                    "WatchLink-root-wifi-err");
            errReader.setDaemon(true);
            errReader.start();

            boolean overflow = drainBounded(proc.getInputStream(), stdout, MAX_XML_BYTES);
            boolean exited = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                proc.destroyForcibly();
                return Result.error(Status.TIMEOUT);
            }
            errReader.join(1000L);
            int code = proc.exitValue();
            if (code != 0) {
                String errText = stderr.toString(StandardCharsets.UTF_8);
                if (errText.contains("No such file") || errText.contains("not found")) {
                    return Result.error(Status.FILE_UNAVAILABLE);
                }
                return Result.error(Status.ROOT_DENIED);
            }
            if (overflow || stdout.size() == 0) {
                return Result.error(stdout.size() == 0 ? Status.FILE_UNAVAILABLE : Status.PARSE_ERROR);
            }
            byte[] xmlBytes = stdout.toByteArray();
            List<WifiNetworkRecord> parsed;
            try (ByteArrayInputStream in = new ByteArrayInputStream(xmlBytes)) {
                parsed = WifiConfigStoreParser.parse(in);
            } catch (IOException | RuntimeException parseError) {
                return Result.error(Status.PARSE_ERROR);
            } finally {
                // Zero out the raw XML byte buffer immediately after parsing.
                java.util.Arrays.fill(xmlBytes, (byte) 0);
            }
            if (parsed.isEmpty()) {
                return Result.error(Status.EMPTY);
            }
            return new Result(Status.OK, Collections.unmodifiableList(parsed));
        } catch (IOException io) {
            return Result.error(Status.ROOT_DENIED);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.error(Status.TIMEOUT);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static boolean drainBounded(InputStream in, ByteArrayOutputStream out, int maxBytes) {
        byte[] buf = new byte[8192];
        int total = 0;
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (total + n > maxBytes) {
                    return true;
                }
                out.write(buf, 0, n);
                total += n;
            }
        } catch (IOException ignored) {
            // Stream closed on process exit.
        } finally {
            java.util.Arrays.fill(buf, (byte) 0);
        }
        return false;
    }
}
