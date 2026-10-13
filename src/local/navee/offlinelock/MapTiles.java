package local.navee.offlinelock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;

/** On-demand raster tiles only. No prefetch, accounts, analytics, or route uploads. */
final class MapTiles {
    static final String DEFAULT_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final File directory;
    private final String template;
    private final String userAgent;

    MapTiles(Context context, String url) {
        template = validUrl(url) ? url : DEFAULT_URL;
        userAgent = "NaveeOffline/" + AppVersion.name(context)
                + " (Android; https://github.com/JJRicks/navee-offline)";
        directory = new File(context.getCacheDir(), "map-tiles/" + digest(template));
        directory.mkdirs();
    }

    static boolean validUrl(String template) {
        if (template == null
                || !template.contains("{z}")
                || !template.contains("{x}")
                || !template.contains("{y}")) return false;
        try {
            URI url = new URI(template.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0"));
            return "https".equalsIgnoreCase(url.getScheme())
                    && url.getHost() != null
                    && url.getUserInfo() == null
                    && url.getFragment() == null;
        } catch (Exception e) {
            return false;
        }
    }

    Bitmap get(int z, int x, int y) {
        File image = new File(directory, z + "-" + x + "-" + y + ".png");
        File meta = new File(directory, image.getName() + ".meta");
        Properties info = new Properties();
        try (InputStream in = new FileInputStream(meta)) {
            info.load(in);
        } catch (IOException ignored) {
        }
        Bitmap cached = decode(image);
        long now = System.currentTimeMillis();
        if (cached != null
                && number(info.getProperty("expires"), image.lastModified() + WEEK) > now)
            return cached;
        HttpURLConnection connection = null;
        try {
            String address =
                    template.replace("{z}", Integer.toString(z))
                            .replace("{x}", Integer.toString(x))
                            .replace("{y}", Integer.toString(y));
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty(
                    "User-Agent",
                    userAgent);
            if (cached != null) {
                if (info.getProperty("etag") != null)
                    connection.setRequestProperty("If-None-Match", info.getProperty("etag"));
                if (info.getProperty("modified") != null)
                    connection.setRequestProperty(
                            "If-Modified-Since", info.getProperty("modified"));
            }
            int code = connection.getResponseCode();
            if (code != 200 && code != 304) return cached;
            String control = connection.getHeaderField("Cache-Control");
            boolean noStore =
                    control != null && control.toLowerCase(Locale.ROOT).contains("no-store");
            long expires = connection.getHeaderFieldDate("Expires", now + WEEK);
            if (control != null)
                for (String directive : control.split(",")) {
                    String item = directive.trim().toLowerCase(Locale.ROOT);
                    if (item.startsWith("max-age="))
                        expires =
                                now
                                        + Math.max(
                                                        0,
                                                        number(item.substring(8), WEEK / 1000)
                                                                - connection.getHeaderFieldInt(
                                                                        "Age", 0))
                                                * 1000;
                    if (item.equals("no-cache")) expires = now;
                }
            info.setProperty("expires", Long.toString(expires));
            String etag = connection.getHeaderField("ETag"),
                    modified = connection.getHeaderField("Last-Modified");
            if (etag != null) info.setProperty("etag", etag);
            if (modified != null) info.setProperty("modified", modified);
            if (code == 304 && cached != null) {
                if (!noStore) writeMeta(meta, info);
                else {
                    image.delete();
                    meta.delete();
                }
                return cached;
            }
            if (connection.getContentLengthLong() > MAX_BYTES) return cached;
            byte[] bytes;
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192];
                int read;
                while ((read = input.read(block)) != -1) {
                    if (output.size() + read > MAX_BYTES) return cached;
                    output.write(block, 0, read);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options size = new BitmapFactory.Options();
            size.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, size);
            if (size.outWidth < 1
                    || size.outWidth > 1024
                    || size.outHeight < 1
                    || size.outHeight > 1024) return cached;
            Bitmap result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (result == null) return cached;
            if (!noStore) {
                File temporary = new File(directory, image.getName() + ".part");
                try (OutputStream output = new FileOutputStream(temporary)) {
                    output.write(bytes);
                }
                if (!temporary.renameTo(image)) temporary.delete();
                writeMeta(meta, info);
                pruneOldCache(now);
            } else {
                image.delete();
                meta.delete();
            }
            return result;
        } catch (Exception ignored) {
            return cached;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Bitmap decode(File file) {
        if (!file.isFile() || file.length() > MAX_BYTES) return null;
        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), size);
        return size.outWidth > 0
                        && size.outWidth <= 1024
                        && size.outHeight > 0
                        && size.outHeight <= 1024
                ? BitmapFactory.decodeFile(file.getPath())
                : null;
    }

    private void pruneOldCache(long now) {
        // Retain at least a week; no cache churn within the provider's minimum TTL.
        File[] files = directory.listFiles();
        if (files == null || files.length < 1000) return;
        for (File file : files)
            if (now - file.lastModified() > 30L * 24 * 60 * 60 * 1000) file.delete();
    }

    private static void writeMeta(File file, Properties value) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            value.store(out, "Tile cache");
        }
    }

    private static long number(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String digest(String text) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++)
                out.append(String.format(Locale.ROOT, "%02x", bytes[i] & 255));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
