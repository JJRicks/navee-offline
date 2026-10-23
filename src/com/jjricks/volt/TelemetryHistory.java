package com.jjricks.volt;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Display-only observations. This database never supplies authentication or command readiness. */
public final class TelemetryHistory {
    public static final long LIMIT_BYTES = 50L * 1024 * 1024;
    // Leave room for a rollback journal as large as the main database.
    static final long DB_BYTES = 24L * 1024 * 1024;
    private static final long DAY = 86400000L;
    private static final String NAME = "telemetry-history.db";
    private static final Map<String, Telemetry.Metric> CATALOG = new LinkedHashMap<>();

    static {
        for (Telemetry.Metric metric : new Telemetry().metrics())
            if (safe(metric.key)) CATALOG.put(metric.key, metric);
    }

    public static final class Reading {
        public final String key, label, group, value, unit;
        public final Double number;
        public final long capturedWallMs;
        public final boolean live;

        public Reading(
                String key,
                String label,
                String group,
                String value,
                String unit,
                Double number,
                long capturedWallMs,
                boolean live) {
            this.key = key;
            this.label = label;
            this.group = group;
            this.value = value;
            this.unit = unit;
            this.number = number != null && Double.isFinite(number) ? number : null;
            this.capturedWallMs = capturedWallMs;
            this.live = live;
        }

        public Reading stale() {
            return new Reading(key, label, group, value, unit, number, capturedWallMs, false);
        }
    }

    public static final class Snapshot {
        public final Map<String, Reading> readings;
        public final Map<String, Integer> features;
        public final long capturedWallMs;

        public Snapshot(
                Map<String, Reading> readings, Map<String, Integer> features, long capturedWallMs) {
            this.readings = Collections.unmodifiableMap(new LinkedHashMap<>(readings));
            this.features = Collections.unmodifiableMap(new LinkedHashMap<>(features));
            this.capturedWallMs = capturedWallMs;
        }

        public static Snapshot empty() {
            return new Snapshot(Collections.emptyMap(), Collections.emptyMap(), 0);
        }
    }

    public static final class Point {
        public final long wallMs;
        public final double value;
        public final boolean gapBefore;

        public Point(long wallMs, double value, boolean gapBefore) {
            this.wallMs = wallMs;
            this.value = value;
            this.gapBefore = gapBefore;
        }
    }

    public static final class Storage {
        public final long bytes, limitBytes, detailedRows, minuteRows, dailyRows;
        public final String status;

        Storage(long bytes, long detailed, long minutes, long daily, String status) {
            this.bytes = bytes;
            limitBytes = LIMIT_BYTES;
            detailedRows = detailed;
            minuteRows = minutes;
            dailyRows = daily;
            this.status = status;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "telemetry-history"));
    private final Database helper;
    private final File file;
    private final Map<String, Snapshot> previous = new HashMap<>();
    private final Map<String, Long> sessions = new HashMap<>();
    private volatile String status = "Stored on this phone";
    private volatile boolean closed;
    private volatile boolean closeComplete;
    private long lastMaintenance;

    public TelemetryHistory(Context context) {
        Context app = context.getApplicationContext();
        file = app.getDatabasePath(NAME);
        helper = new Database(app);
    }

    private static boolean safe(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.ROOT);
        return !k.contains("identity")
                && !k.contains("password")
                && !k.contains("token")
                && !k.contains("secret")
                && !k.contains("pin")
                && !k.contains("auth");
    }

    private static boolean known(String key) {
        return CATALOG.containsKey(key);
    }

    private static boolean safeFeature(String key) {
        if (!safe(key)) return false;
        try {
            ScooterCommands.find(key);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String unitFor(String key) {
        if (key == null) return "";
        if (key.endsWith("_percent")) return "%";
        if (key.endsWith("_kmh")) return "km/h";
        if (key.endsWith("_km")) return "km";
        if (key.endsWith("_wh")) return "Wh";
        if (key.endsWith("_ah")) return "Ah";
        if (key.endsWith("_v")) return "V";
        if (key.endsWith("_a")) return "A";
        if (key.endsWith("_w")) return "W";
        if (key.endsWith("_c")) return "°C";
        if (key.endsWith("_minutes")) return "min";
        return "";
    }
    /**
     * Dynamic measurements expire quickly; configuration and identity reports change less often.
     */
    public static long freshnessMs(String key) {
        Telemetry.Metric m = CATALOG.get(key);
        if (m == null) return 15000;
        if (m.group.equals("Settings")) return 60000;
        if (m.group.equals("Device")
                || m.group.equals("Firmware")
                || key.contains("manufactured")
                || key.contains("rated_capacity")
                || key.contains("serial")
                || key.equals("battery_health_percent")
                || key.equals("health_adjusted_capacity_ah")
                || key.equals("charge_cycles")) return 300000;
        return 15000;
    }

    private static boolean average(String key) {
        return key.equals("battery_percent")
                || key.equals("voltage_v")
                || key.equals("current_a")
                || key.equals("power_w")
                || key.equals("temperature_c")
                || key.equals("speed_kmh")
                || key.equals("remaining_range_km")
                || key.equals("average_speed_kmh");
    }

    private synchronized void task(Runnable action) {
        if (!closed) worker.execute(action);
    }

    private void reply(Runnable action) {
        main.post(action);
    }

    public void load(String profile, Consumer<Snapshot> callback) {
        task(
                () -> {
                    Snapshot result = Snapshot.empty();
                    try {
                        result = loadNow(helper.getWritableDatabase(), profile);
                    } catch (Exception e) {
                        status = "History storage unavailable";
                    }
                    Snapshot delivered = result;
                    reply(() -> callback.accept(delivered));
                });
    }

    private Snapshot loadNow(SQLiteDatabase db, String profile) throws JSONException {
        try (Cursor c =
                db.rawQuery("SELECT data FROM latest WHERE profile=?", new String[] {profile})) {
            if (c.moveToFirst()) return decode(c.getString(0));
        }
        return Snapshot.empty();
    }
    /**
     * Submit at most once every five seconds from a real connected session; latest cache is
     * independent of enabled.
     */
    public void record(String profile, Snapshot snapshot, long session, boolean enabled) {
        if (profile == null || profile.isEmpty()) return;
        task(
                () -> {
                    try {
                        SQLiteDatabase db = helper.getWritableDatabase();
                        maintain(db, System.currentTimeMillis(), false);
                        write(db, profile, snapshot, session, enabled);
                        status = "Stored on this phone";
                    } catch (Exception e) {
                        status = "History storage unavailable; live readings still work";
                        try {
                            SQLiteDatabase db = helper.getWritableDatabase();
                            prunePressure(db);
                            write(db, profile, snapshot, session, enabled);
                            status = "Stored on this phone";
                        } catch (Exception ignored) {
                        }
                    }
                });
    }

    private void write(
            SQLiteDatabase db, String profile, Snapshot snapshot, long session, boolean enabled)
            throws JSONException {
        Snapshot prior = previous.get(profile);
        if (prior == null) prior = loadNow(db, profile);
        // A first report can race the async UI cache load; retain other saved metrics.
        Map<String, Reading> mergedReadings = new LinkedHashMap<>(prior.readings);
        mergedReadings.putAll(snapshot.readings);
        Map<String, Integer> mergedFeatures = new LinkedHashMap<>(prior.features);
        mergedFeatures.putAll(snapshot.features);
        Snapshot latest = new Snapshot(mergedReadings, mergedFeatures, snapshot.capturedWallMs);
        db.beginTransaction();
        try {
            ContentValues cache = new ContentValues();
            cache.put("profile", profile);
            cache.put("data", encode(latest));
            db.insertWithOnConflict("latest", null, cache, SQLiteDatabase.CONFLICT_REPLACE);
            if (enabled) {
                long pid = id(db, "profiles", "name", profile);
                boolean changedSession = !Long.valueOf(session).equals(sessions.get(profile));
                for (Reading r : snapshot.readings.values()) {
                    if (!known(r.key) || r.number == null || r.capturedWallMs <= 0 || !r.live)
                        continue;
                    Reading before = prior.readings.get(r.key);
                    if (!changedSession
                            && before != null
                            && before.capturedWallMs == r.capturedWallMs) continue;
                    long kid = id(db, "keys", "name", r.key);
                    ContentValues v = new ContentValues();
                    v.put("pid", pid);
                    v.put("kid", kid);
                    v.put("wall", r.capturedWallMs);
                    v.put("value", r.number);
                    v.put("session", session);
                    db.insertWithOnConflict("detail", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                    aggregate(
                            db,
                            "minute",
                            pid,
                            kid,
                            r.capturedWallMs / 60000 * 60000,
                            r.capturedWallMs,
                            r.number,
                            session);
                    aggregate(
                            db,
                            "daily",
                            pid,
                            kid,
                            r.capturedWallMs / DAY * DAY,
                            r.capturedWallMs,
                            r.number,
                            session);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        previous.put(profile, latest);
        sessions.put(profile, session);
    }

    private static long id(SQLiteDatabase db, String table, String column, String text) {
        try (Cursor c =
                db.rawQuery(
                        "SELECT id FROM " + table + " WHERE " + column + "=?",
                        new String[] {text})) {
            if (c.moveToFirst()) return c.getLong(0);
        }
        ContentValues values = new ContentValues();
        values.put(column, text);
        return db.insertOrThrow(table, null, values);
    }

    private static void aggregate(
            SQLiteDatabase db,
            String table,
            long pid,
            long kid,
            long bucket,
            long wall,
            double value,
            long session) {
        // UPDATE then INSERT works on Android 8's SQLite, without newer UPSERT syntax.
        db.execSQL(
                "UPDATE "
                        + table
                        + " SET total=total+?,samples=samples+1,last_value=?,last_wall=?,gaps=CASE"
                        + " WHEN last_session!=? THEN 1 ELSE gaps END,last_session=? WHERE pid=?"
                        + " AND kid=? AND bucket=?",
                new Object[] {value, value, wall, session, session, pid, kid, bucket});
        try (Cursor count = db.rawQuery("SELECT changes()", null)) {
            if (count.moveToFirst() && count.getInt(0) > 0) return;
        }
        ContentValues v = new ContentValues();
        v.put("pid", pid);
        v.put("kid", kid);
        v.put("bucket", bucket);
        v.put("total", value);
        v.put("samples", 1);
        v.put("last_value", value);
        v.put("first_wall", wall);
        v.put("last_wall", wall);
        v.put("first_session", session);
        v.put("last_session", session);
        v.put("gaps", 0);
        db.insertOrThrow(table, null, v);
    }
    /** Adaptive resolution and <=600 output points. Missing connection intervals remain gaps. */
    public void query(
            String profile,
            String key,
            long fromWall,
            long toWall,
            Consumer<List<Point>> callback) {
        task(
                () -> {
                    List<Point> result = Collections.emptyList();
                    try {
                        if (known(key) && profile != null && toWall > fromWall)
                            result =
                                    queryNow(
                                            helper.getWritableDatabase(),
                                            profile,
                                            key,
                                            Math.max(0, fromWall),
                                            toWall);
                    } catch (Exception e) {
                        status = "Could not read history";
                    }
                    List<Point> delivered = result;
                    reply(() -> callback.accept(delivered));
                });
    }

    /** Numerical keys with retained samples for this scooter, including keys absent from latest. */
    public void recordedKeys(String profile, Consumer<Set<String>> callback) {
        task(() -> {
            Set<String> keys = new LinkedHashSet<>();
            try {
                if (profile != null) {
                    SQLiteDatabase db = helper.getReadableDatabase();
                    String sql = "SELECT DISTINCT k.name FROM keys k JOIN ("
                            + "SELECT kid FROM detail WHERE pid=(SELECT id FROM profiles WHERE name=?) UNION "
                            + "SELECT kid FROM minute WHERE pid=(SELECT id FROM profiles WHERE name=?) UNION "
                            + "SELECT kid FROM daily WHERE pid=(SELECT id FROM profiles WHERE name=?)) d ON k.id=d.kid";
                    try (Cursor c = db.rawQuery(sql, new String[]{profile, profile, profile})) {
                        while (c.moveToNext()) if (known(c.getString(0))) keys.add(c.getString(0));
                    }
                }
            } catch (Exception e) { status = "Could not read history"; }
            Set<String> result = Collections.unmodifiableSet(keys);
            reply(() -> callback.accept(result));
        });
    }

    /** A zero window means all retained history, with resolution based on its actual age. */
    public void queryWindow(String profile, String key, long windowMs, long toWall,
            Consumer<List<Point>> callback) {
        task(() -> {
            List<Point> result = Collections.emptyList();
            try {
                if (known(key) && profile != null && toWall > 0 && windowMs >= 0) {
                    SQLiteDatabase db = helper.getReadableDatabase();
                    long from = windowMs > 0 ? Math.max(0, toWall - windowMs) : Long.MAX_VALUE;
                    if (windowMs == 0) for (String table : new String[]{"detail", "minute", "daily"})
                        from = Math.min(from, firstWall(db, table, profile, key, 0, toWall));
                    if (from <= toWall) result = queryNow(db, profile, key, Math.max(0, from - 1), toWall);
                }
            } catch (Exception e) { status = "Could not read history"; }
            List<Point> delivered = result;
            reply(() -> callback.accept(delivered));
        });
    }

    private List<Point> queryNow(
            SQLiteDatabase db, String profile, String key, long from, long to) {
        long span = to - from;
        long oldestAge = Math.max(0, System.currentTimeMillis() - from);
        String table =
                span <= 12 * 3600000L && oldestAge < 7 * DAY
                        ? "detail"
                        : span <= 30 * DAY && oldestAge < 30 * DAY ? "minute" : "daily";
        // Size pressure can evict a fine tier before its age limit. Prefer a surviving
        // coarser tier when it retains an earlier part of the requested interval.
        long first = firstWall(db, table, profile, key, from, to);
        if (table.equals("detail")) {
            long minuteFirst = firstWall(db, "minute", profile, key, from, to);
            if (minuteFirst < first) {
                table = "minute";
                first = minuteFirst;
            }
        }
        if (!table.equals("daily")) {
            long dailyFirst = firstWall(db, "daily", profile, key, from, to);
            if (dailyFirst < first) table = "daily";
        }
        long resolution = table.equals("detail") ? 5000 : table.equals("minute") ? 60000 : DAY;
        String sql =
                table.equals("detail")
                        ? "SELECT d.wall,d.value,1,d.value,d.session,d.session,0 FROM detail d"
                        : "SELECT"
                              + " d.last_wall,d.total,d.samples,d.last_value,d.first_session,d.last_session,d.gaps"
                              + " FROM "
                                + table
                                + " d";
        sql +=
                " JOIN profiles p ON p.id=d.pid JOIN keys k ON k.id=d.kid WHERE p.name=? AND"
                        + " k.name=? AND "
                        + (table.equals("detail") ? "d.wall" : "d.last_wall")
                        + ">=? AND "
                        + (table.equals("detail") ? "d.wall" : "d.last_wall")
                        + "<=? ORDER BY "
                        + (table.equals("detail") ? "d.wall" : "d.last_wall");
        ArrayList<Point> out = new ArrayList<>();
        long width = Math.max(resolution, (span + 600) / 600),
                bucket = -1,
                lastWall = 0,
                lastSession = 0,
                pointWall = 0;
        double total = 0, last = 0;
        long count = 0;
        boolean gap = true;
        try (Cursor c =
                db.rawQuery(
                        sql, new String[] {profile, key, Long.toString(from), Long.toString(to)})) {
            while (c.moveToNext()) {
                long wall = c.getLong(0),
                        nextBucket = (wall - from) / width,
                        firstSession = c.getLong(4),
                        endSession = c.getLong(5);
                boolean boundary =
                        lastWall == 0
                                || firstSession != lastSession
                                || wall - lastWall > resolution * 3
                                || c.getInt(6) != 0;
                if (bucket >= 0 && nextBucket != bucket) {
                    out.add(
                            new Point(
                                    pointWall,
                                    average(key) ? total / Math.max(1, count) : last,
                                    gap));
                    total = 0;
                    count = 0;
                    gap = boundary;
                } else gap |= boundary;
                bucket = nextBucket;
                pointWall = wall;
                total += c.getDouble(1);
                count += c.getLong(2);
                last = c.getDouble(3);
                lastWall = wall;
                lastSession = endSession;
            }
        }
        if (count > 0) out.add(new Point(pointWall, average(key) ? total / count : last, gap));
        return Collections.unmodifiableList(out);
    }

    private static long firstWall(
            SQLiteDatabase db, String table, String profile, String key, long from, long to) {
        String wall = table.equals("detail") ? "d.wall" : "d.last_wall";
        try (Cursor c =
                db.rawQuery(
                        "SELECT MIN("
                                + wall
                                + ") FROM "
                                + table
                                + " d JOIN profiles p ON p.id=d.pid JOIN keys k ON k.id=d.kid"
                                + " WHERE p.name=? AND k.name=? AND "
                                + wall
                                + ">=? AND "
                                + wall
                                + "<=?",
                        new String[] {profile, key, Long.toString(from), Long.toString(to)})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : Long.MAX_VALUE;
        }
    }

    public void storage(Consumer<Storage> callback) {
        task(
                () -> {
                    long detail = 0, minute = 0, daily = 0;
                    try {
                        SQLiteDatabase db = helper.getWritableDatabase();
                        maintain(db, System.currentTimeMillis(), false);
                        detail = count(db, "detail");
                        minute = count(db, "minute");
                        daily = count(db, "daily");
                    } catch (Exception e) {
                        status = "History storage unavailable";
                    }
                    Storage result = new Storage(bytes(), detail, minute, daily, status);
                    reply(() -> callback.accept(result));
                });
    }
    /** null clears all profiles. Last-known readings and timelines are both removed. */
    public void clear(String profile, Runnable completion) {
        task(
                () -> {
                    try {
                        SQLiteDatabase db = helper.getWritableDatabase();
                        if (profile == null) {
                            helper.close();
                            if (!SQLiteDatabase.deleteDatabase(file) && file.exists())
                                throw new IllegalStateException("Could not remove database");
                            previous.clear();
                            sessions.clear();
                            helper.getWritableDatabase();
                        } else {
                            for (String table : new String[] {"detail", "minute", "daily"}) {
                                int removed;
                                do {
                                    db.execSQL(
                                            "DELETE FROM "
                                                    + table
                                                    + " WHERE rowid IN (SELECT rowid FROM "
                                                    + table
                                                    + " WHERE pid IN (SELECT id FROM profiles WHERE"
                                                    + " name=?) LIMIT 1000)",
                                            new Object[] {profile});
                                    try (Cursor c = db.rawQuery("SELECT changes()", null)) {
                                        removed = c.moveToFirst() ? c.getInt(0) : 0;
                                    }
                                } while (removed > 0);
                            }
                            db.delete("latest", "profile=?", new String[] {profile});
                            db.delete("profiles", "name=?", new String[] {profile});
                            previous.remove(profile);
                            sessions.remove(profile);
                            db.execSQL("PRAGMA incremental_vacuum");
                        }
                        status = "History cleared";
                    } catch (Exception e) {
                        status = "Could not clear history";
                    }
                    reply(completion);
                });
    }

    private long bytes() {
        long total = 0;
        for (String suffix : new String[] {"", "-journal", "-wal", "-shm"}) {
            File f = new File(file.getPath() + suffix);
            if (f.isFile()) total += f.length();
        }
        return total;
    }

    private static long count(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    private void maintain(SQLiteDatabase db, long now, boolean force) {
        if (!force && now - lastMaintenance < 60000 && bytes() < 22L * 1024 * 1024) return;
        applyRetention(db, now);
        lastMaintenance = now;
    }

    /** The retention rules, also applied to a restore's merged copy before it replaces history. */
    static void applyRetention(SQLiteDatabase db, long now) {
        for (String[] rule :
                new String[][] {
                    {"detail", "wall", Long.toString(now - 7 * DAY)},
                    {"minute", "bucket", Long.toString(now - 30 * DAY)},
                    {"daily", "bucket", Long.toString(now - 730 * DAY)}
                }) {
            for (int i = 0; i < 32; i++) {
                db.execSQL(
                        "DELETE FROM "
                                + rule[0]
                                + " WHERE rowid IN (SELECT rowid FROM "
                                + rule[0]
                                + " WHERE "
                                + rule[1]
                                + "<? LIMIT 1000)",
                        new Object[] {rule[2]});
                try (Cursor c = db.rawQuery("SELECT changes()", null)) {
                    if (!c.moveToFirst() || c.getInt(0) == 0) break;
                }
            }
        }
        prunePressure(db);
        db.execSQL("PRAGMA incremental_vacuum(256)");
    }

    private static void prunePressure(SQLiteDatabase db) {
        for (int i = 0; i < 32 && usedBytes(db) > 20L * 1024 * 1024; i++) {
            boolean removed = false;
            for (String table : new String[] {"detail", "minute", "daily"}) {
                String time = table.equals("detail") ? "wall" : "bucket";
                db.execSQL(
                        "DELETE FROM "
                                + table
                                + " WHERE rowid IN (SELECT rowid FROM "
                                + table
                                + " ORDER BY "
                                + time
                                + " LIMIT 2000)");
                try (Cursor c = db.rawQuery("SELECT changes()", null)) {
                    removed = c.moveToFirst() && c.getLong(0) > 0;
                }
                if (removed) break;
            }
            if (!removed) break;
        }
    }

    private static long usedBytes(SQLiteDatabase db) {
        long pages = pragma(db, "page_count"),
                free = pragma(db, "freelist_count"),
                size = pragma(db, "page_size");
        return Math.max(0, pages - free) * size;
    }

    private static long pragma(SQLiteDatabase db, String key) {
        try (Cursor c = db.rawQuery("PRAGMA " + key, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    private static String encode(Snapshot snapshot) throws JSONException {
        JSONObject root = new JSONObject(),
                readings = new JSONObject(),
                features = new JSONObject();
        root.put("wall", snapshot.capturedWallMs);
        for (Reading r : snapshot.readings.values())
            if (known(r.key) && r.capturedWallMs > 0) {
                JSONObject item = new JSONObject();
                item.put("wall", r.capturedWallMs);
                item.put(
                        "value",
                        r.value == null
                                ? JSONObject.NULL
                                : r.value.substring(0, Math.min(2048, r.value.length())));
                item.put("number", r.number == null ? JSONObject.NULL : r.number);
                readings.put(r.key, item);
            }
        for (Map.Entry<String, Integer> e : snapshot.features.entrySet())
            if (safeFeature(e.getKey())) features.put(e.getKey(), e.getValue());
        root.put("readings", readings);
        root.put("features", features);
        return root.toString();
    }

    private static Snapshot decode(String source) throws JSONException {
        JSONObject root = new JSONObject(source),
                r = root.optJSONObject("readings"),
                f = root.optJSONObject("features");
        Map<String, Reading> readings = new LinkedHashMap<>();
        Map<String, Integer> features = new LinkedHashMap<>();
        if (r != null)
            for (String key : CATALOG.keySet()) {
                JSONObject item = r.optJSONObject(key);
                if (item == null) continue;
                Telemetry.Metric m = CATALOG.get(key);
                Double n = item.isNull("number") ? null : item.optDouble("number", Double.NaN);
                readings.put(
                        key,
                        new Reading(
                                key,
                                m.label,
                                m.group,
                                item.isNull("value") ? null : item.optString("value", null),
                                unitFor(key),
                                n,
                                item.optLong("wall", 0),
                                false));
            }
        if (f != null) {
            Iterator<String> keys = f.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (safeFeature(key)) features.put(key, f.optInt(key));
            }
        }
        return new Snapshot(readings, features, root.optLong("wall", 0));
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        worker.execute(() -> { helper.close(); closeComplete = true; });
        worker.shutdown();
    }

    /** Drain previously accepted reads/writes and close SQLite before copying or replacing it.
     * Called off the main thread; callbacks already posted to the UI must separately be gated. */
    public boolean closeAndAwait(long timeoutMillis) throws InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("History shutdown must not block the main thread");
        close();
        return worker.awaitTermination(Math.max(0, timeoutMillis), java.util.concurrent.TimeUnit.MILLISECONDS)
                && closeComplete;
    }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) {
            super(context, NAME, null, 1);
            // Configure Android's connection pool too: a SQL PRAGMA alone can be
            // undone by a newly opened pooled connection or compatibility WAL.
            setWriteAheadLoggingEnabled(false);
            if (Build.VERSION.SDK_INT >= 28) {
                setOpenParams(
                        new SQLiteDatabase.OpenParams.Builder()
                                .setJournalMode("DELETE")
                                .setSynchronousMode("NORMAL")
                                .build());
            }
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            db.disableWriteAheadLogging();
            try (Cursor c = db.rawQuery("PRAGMA journal_mode=DELETE", null)) {
                if (!c.moveToFirst() || !"delete".equalsIgnoreCase(c.getString(0)))
                    throw new IllegalStateException("History requires bounded rollback journaling");
            }
            db.execSQL("PRAGMA auto_vacuum=INCREMENTAL");
            db.execSQL("PRAGMA temp_store=MEMORY");
            db.execSQL("PRAGMA synchronous=NORMAL");
            db.setMaximumSize(DB_BYTES);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE latest(profile TEXT PRIMARY KEY,data TEXT NOT NULL)");
            db.execSQL("CREATE TABLE profiles(id INTEGER PRIMARY KEY,name TEXT UNIQUE NOT NULL)");
            db.execSQL("CREATE TABLE keys(id INTEGER PRIMARY KEY,name TEXT UNIQUE NOT NULL)");
            db.execSQL(
                    "CREATE TABLE detail(pid INTEGER,kid INTEGER,wall INTEGER,value REAL,session"
                            + " INTEGER,UNIQUE(pid,kid,wall))");
            db.execSQL("CREATE INDEX detail_time ON detail(wall)");
            for (String table : new String[] {"minute", "daily"}) {
                db.execSQL(
                        "CREATE TABLE "
                                + table
                                + "(pid INTEGER,kid INTEGER,bucket INTEGER,total REAL,samples"
                                + " INTEGER,last_value REAL,first_wall INTEGER,last_wall"
                                + " INTEGER,first_session INTEGER,last_session INTEGER,gaps"
                                + " INTEGER,UNIQUE(pid,kid,bucket))");
                db.execSQL("CREATE INDEX " + table + "_time ON " + table + "(bucket)");
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported history database version");
        }
    }
}
