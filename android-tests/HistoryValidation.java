package local.navee.offlinelock.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;

import local.navee.offlinelock.TelemetryHistory;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Isolated synthetic SQLite fixtures; never connects Bluetooth or touches real app databases. */
public final class HistoryValidation extends Instrumentation {
    private static final long DAY = 86400000L;
    private final ArrayList<String> checks = new ArrayList<>();
    private File directory;
    private Context isolated;
    private TelemetryHistory history;
    private long now;

    @Override
    public void onCreate(Bundle arguments) {
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(
                    hardware.contains("ranchu")
                            || hardware.contains("goldfish")
                            || Build.PRODUCT.startsWith("sdk_gphone")
                            || Build.MODEL.startsWith("sdk_gphone"),
                    "History validation refuses physical owner phones");
            require(Build.VERSION.SDK_INT >= 28, "History pressure fixture requires Android9+");
            directory =
                    new File(
                            getTargetContext().getCacheDir(),
                            "history-validation-" + System.nanoTime());
            require(directory.mkdir(), "Could not create isolated fixture directory");
            isolated = new DatabaseContext(getTargetContext(), directory);
            now = System.currentTimeMillis();
            history = new TelemetryHistory(isolated);
            persistenceAndPrivacy();
            connectedHistoryAndGaps();
            retentionAndOldWindows();
            boundedQuery();
            recordedCatalogueAndAllWindow();
            storageBudgetAndClear();
            result.putString("result", "PASS");
            result.putInt("check_count", checks.size());
            result.putStringArrayList("checks", checks);
            result.putString(
                    "fixture",
                    "Synthetic isolated SQLite database; no scooter connection or phone sensors");
            cleanup();
            finish(0, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL");
            result.putString(
                    "error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            result.putStringArrayList("completed_checks", checks);
            try {
                cleanup();
            } catch (Throwable ignored) {
            }
            finish(1, result);
        }
    }

    private void persistenceAndPrivacy() throws Exception {
        Map<String, TelemetryHistory.Reading> readings = new LinkedHashMap<>();
        readings.put(
                "battery_percent", reading("battery_percent", "81 %", 81.0, now - 30000, true));
        readings.put(
                "meter_version", reading("meter_version", "fixture-v1", null, now - 30000, true));
        readings.put(
                "owner_identity",
                reading("owner_identity", "synthetic secret refused", null, now, true));
        Map<String, Integer> features = new LinkedHashMap<>();
        features.put("traction_control", 1);
        features.put("pin", 1234);
        history.record("A", new TelemetryHistory.Snapshot(readings, features, now), 1, true);
        record("B", "battery_percent", 25, now - 25000, 2, true, true);
        TelemetryHistory.Snapshot a = load("A"), b = load("B");
        require(
                a.readings.get("battery_percent").number == 81.0
                        && b.readings.get("battery_percent").number == 25.0,
                "Profiles leaked into each other");
        require(
                "fixture-v1".equals(a.readings.get("meter_version").value),
                "Nonnumeric cache lost");
        require(
                !a.readings.containsKey("owner_identity") && !a.features.containsKey("pin"),
                "Sensitive fields persisted");
        require(!a.readings.get("battery_percent").live, "Restored measurement claimed live");
        record("A", "speed_kmh", 12, now - 20000, 1, false, true);
        require(
                load("A").readings.containsKey("battery_percent"),
                "Partial first report erased existing cache");
        closeHistory();
        history = new TelemetryHistory(isolated);
        a = load("A");
        require(
                a.readings.size() == 3 && !a.readings.get("speed_kmh").live,
                "Restart cache not restored stale");
        checks.add(
                "profile isolation, partial updates, nonnumeric persistence, restart-stale and"
                        + " secret exclusion");
    }

    private void connectedHistoryAndGaps() throws Exception {
        long before = storage().detailedRows;
        record("A", "speed_kmh", 14, now - 18000, 1, false, true);
        require(
                load("A").readings.get("speed_kmh").number == 14.0,
                "Disabled history lost latest cache");
        require(storage().detailedRows == before, "Disabled history saved timeline points");
        record("gap", "speed_kmh", 10, now - 20000, 10, true, true);
        record("gap", "speed_kmh", 11, now - 15000, 10, true, true);
        record("gap", "speed_kmh", 12, now - 10000, 11, true, true);
        record("gap", "speed_kmh", 99, now - 5000, 11, true, false);
        List<TelemetryHistory.Point> points = query("gap", "speed_kmh", now - 22000, now);
        require(points.size() == 3, "Stale observations entered history or fresh points lost");
        require(
                points.get(0).gapBefore && !points.get(1).gapBefore && points.get(2).gapBefore,
                "Short BLE reconnect was interpolated as continuous");
        checks.add(
                "history toggle preserves cache; stale readings excluded; reconnect gap retained");
    }

    private void retentionAndOldWindows() throws Exception {
        record("old", "battery_health_percent", 97, now - 8 * DAY, 20, true, true);
        record("old", "battery_health_percent", 96, now - 31 * DAY, 21, true, true);
        record("old", "battery_health_percent", 95, now - 731 * DAY, 22, true, true);
        load("old");
        closeHistory();
        history = new TelemetryHistory(isolated);
        storage(); // New instance performs retention maintenance before querying.
        List<TelemetryHistory.Point> eightDays =
                query(
                        "old",
                        "battery_health_percent",
                        now - 8 * DAY - 60000,
                        now - 8 * DAY + 60000);
        List<TelemetryHistory.Point> month =
                query(
                        "old",
                        "battery_health_percent",
                        now - 31 * DAY - 60000,
                        now - 31 * DAY + 60000);
        require(
                eightDays.size() == 1 && eightDays.get(0).value == 97,
                "Narrow old minute window became empty");
        require(
                month.size() == 1 && month.get(0).value == 96,
                "Narrow old daily window became empty");
        require(
                query("old", "battery_health_percent", now - 732 * DAY, now - 730 * DAY).isEmpty(),
                "Expired daily history retained");
        checks.add("7/30/730-day retention and narrow historical aggregate windows");
    }

    private void boundedQuery() throws Exception {
        long start = now - 3600000;
        for (int i = 0; i < 721; i++)
            record("dense", "speed_kmh", i % 30, start + i * 5000, 30, true, true);
        List<TelemetryHistory.Point> points = query("dense", "speed_kmh", start, now);
        require(!points.isEmpty() && points.size() <= 600, "History query exceeded chart budget");
        long prior = Long.MIN_VALUE;
        for (TelemetryHistory.Point p : points) {
            require(Double.isFinite(p.value) && p.wallMs >= prior, "Invalid/unsorted chart point");
            prior = p.wallMs;
        }
        checks.add("dense history query bounded to600 ordered finite points");
    }

    private void recordedCatalogueAndAllWindow() throws Exception {
        java.util.Set<String> a = receive(callback -> history.recordedKeys("A", callback));
        require(a.contains("battery_percent") && !a.contains("meter_version") && !a.contains("speed_kmh"),
                "Chart catalogue includes text/cache-only values or loses numerical recordings");
        java.util.Set<String> missing = receive(callback -> history.recordedKeys("missing", callback));
        require(missing.isEmpty(), "Chart catalogue leaked another scooter's keys");
        List<TelemetryHistory.Point> allRecent = receive(callback -> history.queryWindow("gap", "speed_kmh", 0, now, callback));
        require(allRecent.size() == 3 && allRecent.get(0).value == 10 && allRecent.get(1).value == 11,
                "All collapsed short history into a daily average");
        require(allRecent.get(2).gapBefore, "All lost reconnect gaps");
        List<TelemetryHistory.Point> allOld = receive(callback -> history.queryWindow("old", "battery_health_percent", 0, now, callback));
        require(allOld.size() == 2 && allOld.get(0).value == 96 && allOld.get(1).value == 97,
                "All failed to include the oldest retained tier");
        List<TelemetryHistory.Point> empty = receive(callback -> history.queryWindow("missing", "speed_kmh", 0, now, callback));
        require(empty.isEmpty(), "All leaked another profile's timeline");
        List<TelemetryHistory.Point> recent = receive(callback -> history.queryWindow("dense", "speed_kmh", 120000, now, callback));
        require(!recent.isEmpty() && recent.get(0).wallMs >= now - 120000, "Global window includes older samples");
        checks.add("numerical chart catalogue, profile isolation, all-history detail and retained tiers");
    }

    private void storageBudgetAndClear() throws Exception {
        load("dense");
        Field helperField = TelemetryHistory.class.getDeclaredField("helper");
        helperField.setAccessible(true);
        android.database.sqlite.SQLiteOpenHelper helper =
                (android.database.sqlite.SQLiteOpenHelper) helperField.get(history);
        require(
                helper.getReadableDatabase().getMaximumSize() <= 24L * 1024 * 1024,
                "Main database lacks journal headroom");
        requireDeleteJournal(helper.getReadableDatabase());
        closeHistory();
        File dbFile = isolated.getDatabasePath("telemetry-history.db");
        try (SQLiteDatabase db = openFixture(dbFile, false)) {
            db.setMaximumSize(24L * 1024 * 1024);
            requireDeleteJournal(db);
            long pid, kid;
            try (Cursor c = db.rawQuery("SELECT id FROM profiles WHERE name='dense'", null)) {
                require(c.moveToFirst(), "Missing fixture profile");
                pid = c.getLong(0);
            }
            try (Cursor c = db.rawQuery("SELECT id FROM keys WHERE name='speed_kmh'", null)) {
                require(c.moveToFirst(), "Missing fixture metric");
                kid = c.getLong(0);
            }
            // Adversarial wide values exercise real file bytes and pruning; normal numeric rows are
            // much smaller.
            int inserted = 0;
            while (usedBytes(db) < 21L * 1024 * 1024 && inserted < 6000) {
                db.beginTransaction();
                try {
                    for (int i = 0; i < 100; i++, inserted++)
                        db.execSQL(
                                "INSERT OR REPLACE INTO detail(pid,kid,wall,value,session)"
                                        + " VALUES(?,?,?,zeroblob(6000),40)",
                                new Object[] {pid, kid, now - 6 * DAY + inserted});
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            require(
                    usedBytes(db) >= 20L * 1024 * 1024,
                    "Pressure fixture did not reach pruning threshold");
        }
        history = new TelemetryHistory(isolated);
        TelemetryHistory.Storage pressure = storage();
        require(pressure.bytes <= pressure.limitBytes, "Storage exceeds total file budget");
        try (SQLiteDatabase db = openFixture(dbFile, true)) {
            require(usedBytes(db) <= 20L * 1024 * 1024, "Pressure pruning failed");
        }
        // If only aggregates survive the cap, a recent narrow range still has useful history.
        try (SQLiteDatabase db = openFixture(dbFile, false)) {
            db.delete(
                    "detail",
                    "pid IN (SELECT id FROM profiles WHERE name=?)",
                    new String[] {"dense"});
        }
        require(
                !query("dense", "speed_kmh", now - 3600000, now).isEmpty(),
                "Evicted detail masked retained minute history");
        clear("A");
        require(
                load("A").readings.isEmpty() && !load("B").readings.isEmpty(),
                "Profile clear affected wrong scooter");
        clear(null);
        TelemetryHistory.Storage cleared = storage();
        require(
                cleared.detailedRows == 0
                        && cleared.minuteRows == 0
                        && cleared.dailyRows == 0
                        && cleared.bytes < pressure.bytes
                        && cleared.bytes < 1024 * 1024,
                "Clear did not reclaim actual storage");
        checks.add(
                "SQLite cap/journal policy, pressure pruning, profile clear and full file"
                        + " reclamation");
    }

    private static SQLiteDatabase openFixture(File file, boolean readOnly) {
        return SQLiteDatabase.openDatabase(
                file,
                new SQLiteDatabase.OpenParams.Builder()
                        .addOpenFlags(
                                readOnly
                                        ? SQLiteDatabase.OPEN_READONLY
                                        : SQLiteDatabase.OPEN_READWRITE)
                        .setJournalMode("DELETE")
                        .setSynchronousMode("NORMAL")
                        .build());
    }

    private static void requireDeleteJournal(SQLiteDatabase db) {
        require(!db.isWriteAheadLoggingEnabled(), "Android connection pool still has WAL enabled");
        try (Cursor c = db.rawQuery("PRAGMA journal_mode", null)) {
            require(
                    c.moveToFirst() && "delete".equalsIgnoreCase(c.getString(0)),
                    "Actual history connection is not using bounded rollback journaling");
        }
    }

    private static long usedBytes(SQLiteDatabase db) {
        long pages = scalar(db, "PRAGMA page_count"), free = scalar(db, "PRAGMA freelist_count");
        return (pages - free) * scalar(db, "PRAGMA page_size");
    }

    private static long scalar(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    private static TelemetryHistory.Reading reading(
            String key, String value, Double number, long time, boolean live) {
        return new TelemetryHistory.Reading(
                key, key, "Fixture", value, TelemetryHistory.unitFor(key), number, time, live);
    }

    private void record(
            String profile,
            String key,
            double value,
            long time,
            long session,
            boolean enabled,
            boolean live) {
        history.record(
                profile,
                new TelemetryHistory.Snapshot(
                        Collections.singletonMap(
                                key, reading(key, Double.toString(value), value, time, live)),
                        Collections.emptyMap(),
                        time),
                session,
                enabled);
    }

    private TelemetryHistory.Snapshot load(String id) throws Exception {
        return receive(callback -> history.load(id, callback));
    }

    private TelemetryHistory.Storage storage() throws Exception {
        return receive(callback -> history.storage(callback));
    }

    private List<TelemetryHistory.Point> query(String id, String key, long from, long to)
            throws Exception {
        return receive(callback -> history.query(id, key, from, to, callback));
    }

    private void clear(String id) throws Exception {
        receive(callback -> history.clear(id, () -> callback.accept(true)));
    }

    private <T> T receive(Consumer<Consumer<T>> submit) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        submit.accept(
                value -> {
                    result.set(value);
                    latch.countDown();
                });
        require(latch.await(45, TimeUnit.SECONDS), "History worker/callback timed out");
        return result.get();
    }

    private void closeHistory() throws Exception {
        if (history == null) return;
        Field field = TelemetryHistory.class.getDeclaredField("worker");
        field.setAccessible(true);
        ExecutorService worker = (ExecutorService) field.get(history);
        history.close();
        require(worker.awaitTermination(10, TimeUnit.SECONDS), "History worker failed to close");
        history = null;
    }

    private void cleanup() throws Exception {
        closeHistory();
        if (directory != null) remove(directory);
    }

    private static void remove(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (!f.delete() && f.exists()) throw new IllegalStateException("Fixture cleanup failed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class DatabaseContext extends ContextWrapper {
        private final File root;

        DatabaseContext(Context base, File root) {
            super(base);
            this.root = root;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public File getDatabasePath(String name) {
            return new File(root, name);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(
                String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
        }

        @Override
        public SQLiteDatabase openOrCreateDatabase(
                String name,
                int mode,
                SQLiteDatabase.CursorFactory factory,
                DatabaseErrorHandler handler) {
            return SQLiteDatabase.openOrCreateDatabase(
                    getDatabasePath(name).getPath(), factory, handler);
        }
    }
}
