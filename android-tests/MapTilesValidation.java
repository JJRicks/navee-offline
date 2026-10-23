package com.jjricks.volt;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Isolated cache + fake HTTP responses only; never contacts providers or a scooter. */
public final class MapTilesValidation extends Instrumentation {
    private final ArrayList<String> checks = new ArrayList<>();
    private File directory;
    private String preferencesName;
    private byte[] png;

    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hardware.contains("ranchu") || hardware.contains("goldfish")
                    || Build.PRODUCT.startsWith("sdk_gphone") || Build.MODEL.startsWith("sdk_gphone"),
                    "Map tile validation refuses physical owner phones");
            directory = new File(getTargetContext().getCacheDir(), "map-validation-" + System.nanoTime());
            require(directory.mkdir(), "Cannot create isolated cache");
            Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLUE);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Cannot create synthetic tile");
            png = output.toByteArray(); bitmap.recycle();
            sourceSettings();
            successAndConditionalCache();
            legacyMigration();
            blockedImagesAndPersistence();
            deniedAndRateLimited();
            offlineAndInvalidResponses();
            cacheDirectivesAndIsolation();
            result.putString("result", "PASS");
            result.putInt("check_count", checks.size());
            result.putStringArrayList("checks", checks);
            result.putString("fixture", "Synthetic HTTP, clock, tile PNGs and isolated cache; no external requests");
            cleanup(); finish(0, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL");
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            result.putString("error", trace.toString());
            result.putStringArrayList("completed_checks", checks);
            cleanup(); finish(1, result);
        }
    }

    private void sourceSettings() throws Exception {
        preferencesName = "map-validation-" + System.nanoTime();
        SharedPreferences preferences = getTargetContext().getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        require(MapSource.load(preferences).url.equals(MapSource.USGS_URL), "Fresh default is not USGS");
        require(!preferences.getBoolean("map_tiles_enabled", false), "Choosing default enabled maps");
        preferences.edit().putString("map_tile_url", MapSource.OSM_URL).commit();
        require(MapSource.load(preferences).url.equals(MapSource.OSM_URL), "Explicit OSM choice lost");
        String custom = "https://maps.example.test/{z}/{x}/{y}.png";
        preferences.edit().putString("map_tile_url", custom).putString("map_tile_attribution", "Test maps").commit();
        require(MapSource.load(preferences).url.equals(custom)
                && MapSource.load(preferences).attribution.equals("Test maps"), "Custom choice lost");
        preferences.edit().putString("map_tile_url", "http://maps.example.test/{z}/{x}/{y}.png").commit();
        require(MapSource.load(preferences).url.equals(MapSource.USGS_URL), "Unsafe source accepted");
        require(MapSource.maximumTileZoom(MapSource.USGS_URL) == 16
                && MapSource.maximumTileZoom(custom) == 19, "Source zoom limits wrong");
        Fixture fixture = new Fixture("source");
        fixture.enqueue(200).header("Cache-Control", "max-age=60");
        require(fixture.tiles.get(16, 12000, 24000) != null, "USGS fixture failed");
        require(fixture.address.endsWith("/tile/16/24000/12000"), "USGS row/column order wrong");
        checks.add("USGS default and row/column order, explicit/custom choice preservation, HTTPS and zoom limits");
    }

    private void successAndConditionalCache() throws Exception {
        Fixture fixture = new Fixture("success");
        Fake first = fixture.enqueue(200).header("Cache-Control", "max-age=60").header("ETag", "\"tile-v1\"")
                .header("Last-Modified", "Mon, 12 Oct 2026 12:00:00 GMT");
        require(fixture.tiles.get(3, 1, 2) != null && fixture.tiles.status().isEmpty(), "200 failed");
        String ua = first.getRequestProperty("User-Agent");
        require(ua.equals("Volt/" + AppVersion.name(getTargetContext())
                + " (Android; https://github.com/JJRicks/volt)"), "Identity changed or hidden");
        require(first.getRequestProperty("Referer") == null && first.getRequestProperty("Cache-Control") == null
                && first.getRequestProperty("Pragma") == null && !first.getInstanceFollowRedirects(),
                "Unexpected impersonation/cache bypass/redirect");
        require(fixture.tiles.get(3, 1, 2) != null && fixture.calls == 1, "Fresh tile redownloaded");
        fixture.now += 61_000;
        Fake second = fixture.enqueue(304).header("Cache-Control", "max-age=120");
        require(fixture.tiles.get(3, 1, 2) != null, "304 lost image");
        require("\"tile-v1\"".equals(second.getRequestProperty("If-None-Match"))
                && second.getRequestProperty("If-Modified-Since") != null && second.bodyReads == 0,
                "Conditional cache validation wrong");
        require(fixture.tiles.get(3, 1, 2) != null && fixture.calls == 2, "304 expiry not saved");
        require("2".equals(read(fixture.meta()).getProperty("schema")), "Verified metadata absent");
        checks.add("honest stable identification, fresh cache reuse and validator-based 304 refresh");
    }

    private void legacyMigration() throws Exception {
        Fixture fixture = new Fixture("legacy");
        write(fixture.image(), png);
        Properties old = new Properties(); old.setProperty("expires", Long.toString(fixture.now + 999999));
        old.setProperty("etag", "\"old-error-or-map\""); save(fixture.meta(), old);
        Fake response = fixture.enqueue(200).header("Cache-Control", "max-age=60");
        require(fixture.tiles.get(3, 1, 2) != null && fixture.calls == 1, "Legacy tile not refreshed");
        require(response.getRequestProperty("If-None-Match") == null, "Legacy bytes trusted via 304");
        require(fixture.tiles.get(3, 1, 2) != null && fixture.calls == 1, "Migrated cache not reused");
        Fixture offline = new Fixture("legacy-offline");
        write(offline.image(), png); save(offline.meta(), old); offline.offline = true;
        require(offline.tiles.get(3, 1, 2) == null && offline.image().isFile(),
                "Legacy error bytes displayed offline or legacy cache broadly removed");
        checks.add("legacy cache preserved on disk but refreshed unconditionally before trusting its pixels");
    }

    private void blockedImagesAndPersistence() throws Exception {
        Fixture fixture = new Fixture("blocked-png");
        Fake denied = fixture.enqueue(200).header("X-Blocked", "accessdenied").header("Cache-Control", "no-cache");
        require(fixture.tiles.get(3, 1, 2) == null && denied.bodyReads == 0, "Blocked PNG decoded");
        require(!fixture.image().exists() && fixture.tiles.blocked() && fixture.tiles.networkPaused()
                && fixture.tiles.status().contains("blocked"), "Block not surfaced/stopped");
        fixture.tiles.get(3, 2, 2); require(fixture.calls == 1, "Blocked viewport kept requesting tiles");
        MapTiles reopened = fixture.create(MapSource.USGS_URL);
        reopened.get(3, 3, 2); require(fixture.calls == 1 && reopened.blocked(), "New view bypassed hold");
        fixture.now += 16 * 60_000;
        fixture.tiles.get(3, 4, 2); require(fixture.calls == 1, "Denied instance resumed itself");
        fixture.enqueue(200);
        MapTiles later = fixture.create(MapSource.USGS_URL);
        require(later.get(3, 1, 2) != null && !later.blocked(), "Later new view could not retry after hold");
        checks.add("HTTP 200 X-Blocked error art rejected before decode, viewport stopped and hold survives reopening");
    }

    private void deniedAndRateLimited() throws Exception {
        for (int code : new int[]{401, 403}) {
            Fixture fixture = new Fixture("denied-" + code);
            fixture.enqueue(200).header("Cache-Control", "max-age=0");
            require(fixture.tiles.get(3, 1, 2) != null, "Could not seed legitimate map");
            Fake denied = fixture.enqueue(code);
            require(fixture.tiles.get(3, 1, 2) != null && fixture.tiles.blocked() && denied.bodyReads == 0,
                    "Denied response lost valid stale map or read error art");
            fixture.tiles.get(3, 2, 2); require(fixture.calls == 2, "Denied request loop");
        }
        for (boolean date : new boolean[]{false, true}) {
            Fixture fixture = new Fixture("limited-" + date);
            String retry = "120";
            if (date) {
                SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("GMT")); retry = format.format(new Date(fixture.now + 120_000));
            }
            fixture.enqueue(429).header("Retry-After", retry);
            require(fixture.tiles.get(3, 1, 2) == null && fixture.tiles.networkPaused() && !fixture.tiles.blocked(),
                    "Rate limit not surfaced");
            fixture.now += 61_000; fixture.tiles.get(3, 2, 2);
            require(fixture.calls == 1, "Retry-After ignored");
            fixture.now += 60_000; fixture.enqueue(200);
            require(fixture.tiles.get(3, 2, 2) != null && !fixture.tiles.networkPaused()
                    && fixture.tiles.status().isEmpty(), "Rate limit did not recover");
        }
        checks.add("401/403 preserve verified cache while stopping requests; 429 respects seconds/date Retry-After");
    }

    private void offlineAndInvalidResponses() throws Exception {
        Fixture fixture = new Fixture("offline"); fixture.enqueue(200).header("Cache-Control", "max-age=0");
        require(fixture.tiles.get(3, 1, 2) != null, "Cannot seed stale fallback"); fixture.offline = true;
        require(fixture.tiles.get(3, 1, 2) != null && fixture.tiles.networkPaused()
                && !fixture.tiles.status().isEmpty(), "Offline verified fallback missing");
        fixture.tiles.get(3, 2, 2); require(fixture.calls == 2, "Offline tight retry loop");
        Fixture invalid = new Fixture("invalid"); invalid.enqueue(200).body = "not an image".getBytes("UTF-8");
        require(invalid.tiles.get(3, 1, 2) == null && !invalid.image().exists()
                && !invalid.tiles.status().isEmpty(), "Invalid body cached or hidden");
        Fixture unchanged = new Fixture("bad-304"); unchanged.enqueue(304);
        require(unchanged.tiles.get(3, 1, 2) == null && unchanged.tiles.networkPaused(), "Unexpected 304 accepted");
        checks.add("offline verified fallback, transient backoff, invalid bodies and unexpected 304 rejection");
    }

    private void cacheDirectivesAndIsolation() throws Exception {
        Fixture fixture = new Fixture("no-store"); fixture.enqueue(200).header("Cache-Control", "no-store");
        require(fixture.tiles.get(3, 1, 2) != null && !fixture.image().exists() && !fixture.meta().exists(),
                "no-store response persisted");
        Fixture noCache = new Fixture("no-cache");
        noCache.enqueue(200).header("Cache-Control", "no-cache, max-age=1000").header("ETag", "\"fresh\"");
        require(noCache.tiles.get(3, 1, 2) != null, "no-cache response hidden on successful validation");
        noCache.offline = true;
        require(noCache.tiles.get(3, 1, 2) == null && noCache.calls == 2, "no-cache served without revalidation");
        Fixture separate = new Fixture("provider-isolation"); separate.enqueue(200);
        require(separate.tiles.get(3, 1, 2) != null, "Cannot seed USGS cache");
        MapTiles custom = separate.create("https://maps.example.test/{z}/{x}/{y}.png"); separate.offline = true;
        require(custom.get(3, 1, 2) == null, "One provider displayed another provider's cache");
        checks.add("no-store/no-cache honored and provider caches isolated");
    }

    private final class Fixture implements MapTiles.ConnectionFactory {
        final File cache;
        final Context context;
        final Queue<Fake> responses = new ArrayDeque<>();
        final MapTiles tiles;
        long now = 1791806400000L;
        int calls;
        boolean offline;
        String address;
        Fixture(String name) {
            cache = new File(directory, name); cache.mkdirs();
            context = new ContextWrapper(getTargetContext()) { @Override public File getCacheDir() { return cache; } };
            tiles = create(MapSource.USGS_URL);
        }
        MapTiles create(String url) { return new MapTiles(context, url, this, () -> now); }
        Fake enqueue(int code) throws Exception {
            Fake response = new Fake(code, png); responses.add(response); return response;
        }
        public HttpURLConnection open(URL url) throws IOException {
            calls++; address = url.toString();
            if (offline) throw new IOException("Synthetic offline");
            Fake response = responses.poll();
            if (response == null) throw new AssertionError("Unexpected request " + address);
            return response;
        }
        File provider() {
            File[] providers = new File(cache, "map-tiles").listFiles();
            require(providers != null && providers.length == 1, "Fixture provider directory missing");
            return providers[0];
        }
        File image() { return new File(provider(), "3-1-2.png"); }
        File meta() { return new File(provider(), "3-1-2.png.meta"); }
    }

    private static final class Fake extends HttpURLConnection {
        final int code;
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byte[] body;
        int bodyReads;
        Fake(int code, byte[] body) throws Exception { super(new URL("https://synthetic.invalid/tile")); this.code = code; this.body = body; }
        Fake header(String key, String value) { headers.put(key, value); return this; }
        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String key) { return headers.get(key); }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public InputStream getInputStream() { bodyReads++; return new ByteArrayInputStream(body); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }

    private static Properties read(File file) throws Exception {
        Properties result = new Properties(); try (InputStream in = new FileInputStream(file)) { result.load(in); } return result;
    }
    private static void save(File file, Properties properties) throws Exception {
        try (OutputStream out = new FileOutputStream(file)) { properties.store(out, "Synthetic legacy fixture"); }
    }
    private static void write(File file, byte[] bytes) throws Exception {
        try (OutputStream out = new FileOutputStream(file)) { out.write(bytes); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void cleanup() {
        if (preferencesName != null) getTargetContext().deleteSharedPreferences(preferencesName);
        remove(directory);
    }
    private static void remove(File file) {
        if (file == null) return;
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        file.delete();
    }
}
