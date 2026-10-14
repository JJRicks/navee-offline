package local.navee.offlinelock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Offline ride journal and conservative analysis. No Android or network dependency.
 * Every sample is a committed CSV frame: interrupted writes are ignored on recovery.
 * V/A must be from the same report and <=3 seconds old. Missing data is never zero.
 * UNCONFIRMED integrates absolute electrical throughput, not battery consumption.
 */
public final class RideStore {
    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_SENSOR_AGE_MS = 3000;
    public static final long MAX_INTEGRATION_INTERVAL_MS = 3000;
    public static final double KM_PER_MILE = 1.609344;
    public static final int MAX_TREND_POINTS = 600;
    public static final double MIN_EFFICIENCY_DISTANCE_KM = 0.2;
    public static final double MIN_EFFICIENCY_COVERAGE = 0.8;
    private static final int MAX_METRICS = 160;
    private static final String HEADER = "schema_version,event,recorded_utc,wall_ms,monotonic_ms,frame,key,numeric,text,unit,source_command,sensor_updated_monotonic_ms,sensor_age_ms,current_direction,details\n";

    public enum CurrentDirection { UNCONFIRMED, POSITIVE_IS_DRAW, NEGATIVE_IS_DRAW }

    public static final class Sample {
        public final Double number;
        public final String text, unit;
        public final int sourceCmd;
        public final long updatedMonoMs;
        public Sample(Double number, String text, String unit, int sourceCmd, long updatedMonoMs) {
            this.number = finite(number) ? number : null;
            this.text = trim(text, 4096);
            this.unit = trim(unit, 80);
            this.sourceCmd = sourceCmd;
            this.updatedMonoMs = updatedMonoMs;
        }
        public Sample(double number, long updatedMonoMs) {
            this(number, null, null, -1, updatedMonoMs);
        }
    }

    public static final class Point {
        public final long wallMs, monotonicMs, elapsedMs;
        public final int segment;
        public final Double voltageV, powerW, speedKmh, batteryPercent, odometerKm;
        private Point(long wallMs, long monotonicMs, long elapsedMs, int segment, Double voltageV, Double powerW,
                Double speedKmh, Double batteryPercent, Double odometerKm) {
            this.wallMs = wallMs; this.monotonicMs = monotonicMs; this.elapsedMs = elapsedMs; this.segment = segment;
            this.voltageV = voltageV; this.powerW = powerW; this.speedKmh = speedKmh;
            this.batteryPercent = batteryPercent; this.odometerKm = odometerKm;
        }
        public Map<String, Object> fields() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("wallMs", wallMs); result.put("monotonicMs", monotonicMs); result.put("elapsedMs", elapsedMs); result.put("segment", segment);
            result.put("voltageV", voltageV); result.put("powerW", powerW);
            result.put("speedKmh", speedKmh); result.put("batteryPercent", batteryPercent);
            result.put("odometerKm", odometerKm); return Collections.unmodifiableMap(result);
        }
        public String toJson() { return json(fields()); }
    }

    /** Immutable, unit-explicit values; JSON null means insufficient evidence. */
    public static final class Summary {
        public final String id, scooterId, label, notes, currentDirection, energyInterpretation;
        public final long startWallMs, endWallMs, elapsedMs, electricalCoverageMs,
                skippedElectricalMs, speedCoverageMs, movingMs, stationaryMs;
        public final long sampleCount, gapCount, clockDiscontinuities, directionChanges;
        public final boolean active, recovered, odometerReset, directionConfirmed;
        public final Double startBatteryPercent, endBatteryPercent, batteryDropPercent,
                startVoltageV, minimumVoltageV, maximumVoltageV, observedVoltageDropV,
                minimumTemperatureC, maximumTemperatureC, startOdometerKm, endOdometerKm,
                distanceKm, distanceMiles, speedIntegratedDistanceKm, averageSpeedKmh,
                movingAverageSpeedKmh, maximumSpeedKmh, averageAbsolutePowerW,
                peakAbsolutePowerW, maximumAbsoluteCurrentA, measuredThroughputWh,
                drawWh, regeneratedWh, returnedWh, netConsumptionWh, throughputWhPerKm,
                throughputWhPerMile, netWhPerKm, netWhPerMile;
        public final double electricalCoverage, speedCoverage;
        public final long confirmedDirectionCoverageMs, unconfirmedDirectionCoverageMs;
        public final long movingElectricalCoverageMs, stationaryElectricalCoverageMs,
                unknownMotionElectricalCoverageMs;
        public final Double movingThroughputWh, stationaryThroughputWh, unknownMotionThroughputWh;
        public final String efficiencyStatus;
        private Summary(Accumulator a) {
            id = a.id; scooterId = a.scooterId; label = a.label; notes = a.notes;
            currentDirection = a.direction.name(); startWallMs = a.startWall;
            endWallMs = a.lastWall; elapsedMs = a.elapsed;
            electricalCoverageMs = a.electricalMs;
            skippedElectricalMs = Math.max(0, a.elapsed - a.electricalMs);
            speedCoverageMs = a.speedMs; movingMs = a.movingMs;
            stationaryMs = a.stationaryMs; sampleCount = a.samples;
            gapCount = a.gaps; clockDiscontinuities = a.clockDiscontinuities; directionChanges = a.directionChanges;
            active = !a.stopped; recovered = a.recovered; odometerReset = a.odometerReset;
            confirmedDirectionCoverageMs = a.confirmedMs;
            unconfirmedDirectionCoverageMs = a.unconfirmedMs;
            movingElectricalCoverageMs = a.movingElectricalMs;
            stationaryElectricalCoverageMs = a.stationaryElectricalMs;
            unknownMotionElectricalCoverageMs = a.unknownMotionElectricalMs;
            movingThroughputWh = a.movingElectricalMs == 0 ? null : a.movingThroughputWh;
            stationaryThroughputWh = a.stationaryElectricalMs == 0 ? null : a.stationaryThroughputWh;
            unknownMotionThroughputWh = a.unknownMotionElectricalMs == 0 ? null : a.unknownMotionThroughputWh;
            directionConfirmed = a.confirmedMs > 0 && a.unconfirmedMs == 0;
            startBatteryPercent = a.startSoc; endBatteryPercent = a.endSoc;
            batteryDropPercent = subtract(a.startSoc, a.endSoc);
            startVoltageV = a.startVoltage; minimumVoltageV = a.minVoltage;
            maximumVoltageV = a.maxVoltage;
            Double voltageDrop = subtract(a.startVoltage, a.minVoltage);
            observedVoltageDropV = voltageDrop == null ? null : Math.max(0, voltageDrop);
            minimumTemperatureC = a.minTemperature; maximumTemperatureC = a.maxTemperature;
            startOdometerKm = a.startOdo; endOdometerKm = a.endOdo;
            Double odoDelta = subtract(a.endOdo, a.startOdo);
            distanceKm = !a.odometerReset && odoDelta != null && odoDelta >= 0 ? odoDelta : null;
            distanceMiles = distanceKm == null ? null : distanceKm / KM_PER_MILE;
            speedIntegratedDistanceKm = a.speedMs == 0 ? null : a.speedDistance;
            averageSpeedKmh = a.speedMs == 0 ? null : a.speedDistance * 3600000 / a.speedMs;
            movingAverageSpeedKmh = a.movingMs == 0 ? null : a.movingDistance * 3600000 / a.movingMs;
            maximumSpeedKmh = a.maxSpeed;
            averageAbsolutePowerW = a.electricalMs == 0 ? null : a.throughputWh * 3600000 / a.electricalMs;
            peakAbsolutePowerW = a.peakPower; maximumAbsoluteCurrentA = a.maxCurrent;
            measuredThroughputWh = a.electricalMs == 0 ? null : a.throughputWh;
            drawWh = a.confirmedMs == 0 ? null : a.drawWh;
            regeneratedWh = a.confirmedMs == 0 ? null : a.regeneratedWh;
            returnedWh = regeneratedWh;
            netConsumptionWh = directionConfirmed ? a.drawWh - a.regeneratedWh : null;
            energyInterpretation = !directionConfirmed
                    ? "Absolute measured electrical throughput. Current direction is not confirmed for the full recording."
                    : "Configured current direction. Battery input can include charger input; it is not proof of regenerative braking.";
            electricalCoverage = a.elapsed == 0 ? 0 : Math.min(1, a.electricalMs / (double) a.elapsed);
            speedCoverage = a.elapsed == 0 ? 0 : Math.min(1, a.speedMs / (double) a.elapsed);
            boolean adequateDistance = distanceKm != null && distanceKm + 1e-9 >= MIN_EFFICIENCY_DISTANCE_KM;
            boolean adequateCoverage = electricalCoverage >= MIN_EFFICIENCY_COVERAGE;
            throughputWhPerKm = adequateDistance && adequateCoverage && measuredThroughputWh != null
                    ? measuredThroughputWh / distanceKm : null;
            throughputWhPerMile = throughputWhPerKm == null ? null : throughputWhPerKm * KM_PER_MILE;
            netWhPerKm = adequateDistance && adequateCoverage && netConsumptionWh != null
                    ? netConsumptionWh / distanceKm : null;
            netWhPerMile = netWhPerKm == null ? null : netWhPerKm * KM_PER_MILE;
            efficiencyStatus = odometerReset ? "Odometer changed backwards; distance unavailable"
                    : !adequateDistance ? "Record at least 0.2 km of odometer distance"
                    : !adequateCoverage ? "Electrical coverage below 80%; efficiency unavailable"
                    : !directionConfirmed ? "Measured throughput per distance; current direction unconfirmed"
                    : "Measured net energy per distance; coverage is reported separately";
        }
        public Map<String, Object> fields() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("schemaVersion", SCHEMA_VERSION);
            m.put("id", id); m.put("scooterId", scooterId); m.put("label", label); m.put("notes", notes);
            m.put("currentDirection", currentDirection); m.put("directionConfirmed", directionConfirmed);
            m.put("active", active); m.put("recovered", recovered); m.put("odometerReset", odometerReset);
            m.put("startWallMs", startWallMs); m.put("endWallMs", endWallMs); m.put("elapsedMs", elapsedMs);
            m.put("sampleCount", sampleCount); m.put("gapCount", gapCount); m.put("directionChanges", directionChanges);
            m.put("clockDiscontinuities", clockDiscontinuities);
            m.put("electricalCoverageMs", electricalCoverageMs); m.put("skippedElectricalMs", skippedElectricalMs);
            m.put("electricalCoverage", electricalCoverage); m.put("speedCoverageMs", speedCoverageMs);
            m.put("speedCoverage", speedCoverage); m.put("movingMs", movingMs); m.put("stationaryMs", stationaryMs);
            m.put("confirmedDirectionCoverageMs", confirmedDirectionCoverageMs);
            m.put("unconfirmedDirectionCoverageMs", unconfirmedDirectionCoverageMs);
            m.put("movingElectricalCoverageMs", movingElectricalCoverageMs);
            m.put("stationaryElectricalCoverageMs", stationaryElectricalCoverageMs);
            m.put("unknownMotionElectricalCoverageMs", unknownMotionElectricalCoverageMs);
            m.put("movingThroughputWh", movingThroughputWh);
            m.put("stationaryThroughputWh", stationaryThroughputWh);
            m.put("unknownMotionThroughputWh", unknownMotionThroughputWh);
            m.put("startBatteryPercent", startBatteryPercent); m.put("endBatteryPercent", endBatteryPercent);
            m.put("batteryDropPercent", batteryDropPercent); m.put("startVoltageV", startVoltageV);
            m.put("minimumVoltageV", minimumVoltageV); m.put("maximumVoltageV", maximumVoltageV);
            m.put("observedVoltageDropV", observedVoltageDropV);
            m.put("minimumTemperatureC", minimumTemperatureC); m.put("maximumTemperatureC", maximumTemperatureC);
            m.put("startOdometerKm", startOdometerKm); m.put("endOdometerKm", endOdometerKm);
            m.put("distanceKm", distanceKm); m.put("distanceMiles", distanceMiles);
            m.put("speedIntegratedDistanceKm", speedIntegratedDistanceKm);
            m.put("averageSpeedKmh", averageSpeedKmh); m.put("movingAverageSpeedKmh", movingAverageSpeedKmh);
            m.put("maximumSpeedKmh", maximumSpeedKmh); m.put("averageAbsolutePowerW", averageAbsolutePowerW);
            m.put("peakAbsolutePowerW", peakAbsolutePowerW); m.put("maximumAbsoluteCurrentA", maximumAbsoluteCurrentA);
            m.put("measuredThroughputWh", measuredThroughputWh); m.put("drawWh", drawWh);
            m.put("regeneratedWh", regeneratedWh); m.put("netConsumptionWh", netConsumptionWh);
            m.put("returnedWh", returnedWh); m.put("energyInterpretation", energyInterpretation);
            m.put("throughputWhPerKm", throughputWhPerKm); m.put("throughputWhPerMile", throughputWhPerMile);
            m.put("netWhPerKm", netWhPerKm); m.put("netWhPerMile", netWhPerMile);
            m.put("efficiencyStatus", efficiencyStatus);
            return Collections.unmodifiableMap(m);
        }
        public String toJson() { return json(fields()); }
    }

    private final File directory;
    private Accumulator active;
    private CurrentDirection defaultDirection = CurrentDirection.UNCONFIRMED;
    private final LinkedHashMap<String, CachedSummary> summaryCache = new LinkedHashMap<String, CachedSummary>();
    /** Consistent immutable summary/trend pair for off-thread display analysis. */
    public static final class AnalysisSnapshot {
        public final Summary summary;
        public final List<Point> points;
        private AnalysisSnapshot(Accumulator accumulator) {
            summary = new Summary(accumulator);
            points = trendPoints(accumulator, MAX_TREND_POINTS);
        }
    }
    private final LinkedHashMap<String, CachedAnalysis> analysisCache = new LinkedHashMap<>();
    private static final class CachedAnalysis {
        final long length, modified;
        final AnalysisSnapshot snapshot;
        CachedAnalysis(long length, long modified, AnalysisSnapshot snapshot) {
            this.length = length; this.modified = modified; this.snapshot = snapshot;
        }
    }

    private static final class CachedSummary {
        final long length, modified;
        final Summary summary;
        CachedSummary(File file, Summary summary) {
            this.length = file.length(); this.modified = file.lastModified(); this.summary = summary;
        }
        boolean matches(File file) { return length == file.length() && modified == file.lastModified(); }
    }

    public RideStore(File directory) throws IOException {
        if (directory == null) throw new IllegalArgumentException("Ride directory required");
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create ride directory");
        // A process restart never bridges a lost interval with stale measurements.
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot list ride directory");
        for (File file : files) if (isJournal(file)) {
            Accumulator candidate = read(file);
            if (candidate != null && !candidate.stopped
                    && (active == null || candidate.startWall > active.startWall)) active = candidate;
        }
        if (active != null) {
            repairTail(file(active.id));
            append(file(active.id), row("RECOVER", active.lastWall, active.lastMono, "", "", "", "", "", "", "", "", "", "Recorder process restarted"));
            active.recover();
        }
    }

    public synchronized String start(String scooterId, String label, long wallMillis,
            long monotonicMillis) throws IOException {
        if (active != null) throw new IllegalStateException("A ride is already recording");
        if (wallMillis < 0 || monotonicMillis < 0) throw new IllegalArgumentException("Invalid clock");
        String id = UUID.randomUUID().toString();
        Accumulator candidate = new Accumulator(id, trim(scooterId, 160), trim(label, 200), wallMillis, monotonicMillis);
        candidate.direction = defaultDirection;
        String start = row("START", wallMillis, monotonicMillis, "", candidate.scooterId, "", candidate.label,
                "", "", "", "", candidate.direction.name(), "");
        writeNew(file(id), HEADER + start);
        active = candidate;
        return id;
    }

    /** The map is a current snapshot, including unavailable/stale fields if desired. */
    public synchronized void sample(long wallMillis, long monotonicMillis,
            Map<String, Sample> values) throws IOException {
        if (active == null) return;
        Map<String, Sample> bounded = new LinkedHashMap<String, Sample>();
        if (values != null) for (Map.Entry<String, Sample> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.length() > 120 || isSecret(key) || entry.getValue() == null) continue;
            bounded.put(key, entry.getValue());
            if (bounded.size() >= MAX_METRICS) break;
        }
        String frame = Long.toString(active.samples + 1);
        StringBuilder rows = new StringBuilder(row("BEGIN", wallMillis, monotonicMillis, frame,
                "", Integer.toString(bounded.size()), "", "", "", "", "", "", ""));
        for (Map.Entry<String, Sample> entry : bounded.entrySet()) {
            Sample s = entry.getValue();
            String age = s.updatedMonoMs < 0 || monotonicMillis < s.updatedMonoMs ? ""
                    : Long.toString(monotonicMillis - s.updatedMonoMs);
            rows.append(row("SENSOR", wallMillis, monotonicMillis, frame, entry.getKey(),
                    s.number == null ? "" : Double.toString(s.number), s.text, s.unit,
                    Integer.toString(s.sourceCmd), Long.toString(s.updatedMonoMs), age, "", ""));
        }
        rows.append(row("COMMIT", wallMillis, monotonicMillis, frame, "", Integer.toString(bounded.size()), "", "", "", "", "", "", ""));
        append(file(active.id), rows.toString());
        active.accept(wallMillis, monotonicMillis, bounded);
    }

    public synchronized void markGap(long wallMillis, long monotonicMillis, String reason) throws IOException {
        if (active == null) return;
        append(file(active.id), row("GAP", wallMillis, monotonicMillis, "", "", "", "", "", "", "", "", "", trim(reason, 500)));
        active.gap(wallMillis, monotonicMillis);
    }

    /** Advance missing exposure during one ongoing gap without inventing samples or new gaps. */
    public synchronized void heartbeat(long wallMillis, long monotonicMillis) throws IOException {
        if (active == null) return;
        append(file(active.id), row("HEARTBEAT", wallMillis, monotonicMillis, "", "", "", "", "", "", "", "", "", ""));
        active.heartbeat(wallMillis, monotonicMillis);
    }

    public synchronized Summary stop(long wallMillis, long monotonicMillis) throws IOException {
        if (active == null) return null;
        append(file(active.id), row("STOP", wallMillis, monotonicMillis, "", "", "", "", "", "", "", "", "", ""));
        active.stop(wallMillis, monotonicMillis);
        Summary result = new Summary(active); active = null; return result;
    }

    /** Direction changes affect future intervals only; do not reinterpret earlier data. */
    public synchronized void setCurrentDirection(CurrentDirection direction) throws IOException {
        if (direction == null) direction = CurrentDirection.UNCONFIRMED;
        if (active != null && direction != active.direction) {
            append(file(active.id), row("DIRECTION", active.lastWall, active.lastMono, "", "", "", "", "", "", "", "", direction.name(), ""));
            if (active.samples > 0) active.directionChanges++; active.direction = direction; active.breakAnchor();
        }
        defaultDirection = direction;
    }

    public synchronized void setNotes(String notes) throws IOException {
        if (active == null) return;
        String clean = trim(notes, 2000);
        append(file(active.id), row("NOTE", active.lastWall, active.lastMono, "", "", "", "", "", "", "", "", "", clean));
        active.notes = clean;
    }

    /** Append-only rename: original measurements and their clock/energy interpretation stay intact. */
    public synchronized void rename(String id, String name) throws IOException {
        String clean = trim(name == null ? "" : name.trim(), 200);
        if (clean.isEmpty()) throw new IllegalArgumentException("A ride needs a name");
        Accumulator ride = active != null && active.id.equals(id) ? active : read(file(id));
        if (ride == null) throw new IOException("Ride does not exist");
        repairTail(file(id));
        append(file(id), row("RENAME", ride.lastWall, ride.lastMono, "", "", "", "", "", "", "", "", "", clean));
        ride.label = clean;
        summaryCache.remove(id); analysisCache.remove(id);
    }

    public synchronized String activeId() { return active == null ? null : active.id; }
    public synchronized Summary activeSummary() { return active == null ? null : new Summary(active); }
    public synchronized Summary get(String id) throws IOException {
        if (active != null && active.id.equals(id)) return new Summary(active);
        File source = file(id);
        if (!source.isFile()) { summaryCache.remove(id); return null; }
        CachedSummary cached = summaryCache.get(id);
        if (cached != null && cached.matches(source)) return cached.summary;
        Accumulator a = read(source);
        Summary summary = a == null ? null : new Summary(a);
        if (summary != null) {
            summaryCache.put(id, new CachedSummary(source, summary));
            if (summaryCache.size() > 256) summaryCache.remove(summaryCache.keySet().iterator().next());
        }
        return summary;
    }
    public synchronized List<Summary> list() throws IOException {
        ArrayList<Summary> result = new ArrayList<Summary>();
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot list rides");
        for (File f : files) if (isJournal(f)) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            Summary summary = get(id); if (summary != null) result.add(summary);
        }
        Collections.sort(result, new Comparator<Summary>() { public int compare(Summary a, Summary b) {
            return a.startWallMs < b.startWallMs ? 1 : a.startWallMs == b.startWallMs ? a.id.compareTo(b.id) : -1;
        }});
        return Collections.unmodifiableList(result);
    }
    /**
     * Call from a worker. Active state is copied atomically without disk I/O; completed
     * journals are immutable and replay outside the recorder monitor so sampling can continue.
     * Unlike constructing a second RideStore, this operation never recovers or modifies a journal.
     */
    public List<AnalysisSnapshot> analysisSnapshots(String scooterId, long sinceWallMs) throws IOException {
        ArrayList<AnalysisSnapshot> result = new ArrayList<>();
        String copiedActiveId;
        synchronized (this) {
            copiedActiveId = active == null ? null : active.id;
            if (active != null && active.scooterId.equals(scooterId) && active.lastWall >= sinceWallMs)
                result.add(new AnalysisSnapshot(active));
        }
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot list rides");
        java.util.HashSet<String> existing = new java.util.HashSet<>();
        for (File source : files) if (isJournal(source)) {
            String id = source.getName().substring(0, source.getName().length() - 4);
            existing.add(id);
            if (id.equals(copiedActiveId)) continue;
            CachedAnalysis cached;
            synchronized (this) {
                if (active != null && active.id.equals(id)) continue;
                cached = analysisCache.get(id);
            }
            long length = source.length(), modified = source.lastModified();
            if (cached == null || cached.length != length || cached.modified != modified) {
                Accumulator parsed;
                try { parsed = read(source); }
                catch (IOException e) { if (!source.exists()) continue; throw e; }
                // A new active journal or an incomplete tail is not a completed snapshot.
                if (parsed == null || !parsed.stopped) continue;
                cached = new CachedAnalysis(length, modified, new AnalysisSnapshot(parsed));
                synchronized (this) {
                    analysisCache.put(id, cached);
                    while (analysisCache.size() > 256) analysisCache.remove(analysisCache.keySet().iterator().next());
                }
            }
            if (cached.snapshot.summary.scooterId.equals(scooterId)
                    && cached.snapshot.summary.endWallMs >= sinceWallMs) result.add(cached.snapshot);
        }
        synchronized (this) { analysisCache.keySet().retainAll(existing); }
        return Collections.unmodifiableList(result);
    }

    public synchronized boolean delete(String id) throws IOException {
        if (active != null && active.id.equals(id)) throw new IllegalStateException("Stop recording before deleting");
        File f = file(id); if (!f.exists()) return false;
        if (!f.delete()) throw new IOException("Could not delete ride"); summaryCache.remove(id); return true;
    }
    public synchronized void exportCsv(String id, File destination) throws IOException {
        File source = file(id);
        if (!source.isFile()) throw new IOException("Ride does not exist");
        if (source.getCanonicalFile().equals(destination.getCanonicalFile())) throw new IOException("Export destination is source");
        FileInputStream input = new FileInputStream(source);
        try {
            FileOutputStream output = new FileOutputStream(destination);
            try { byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) >= 0) output.write(buffer, 0, n); }
            finally { output.close(); }
        } finally { input.close(); }
    }
    public synchronized List<Point> trend(String id, int maxPoints) throws IOException {
        Accumulator a = active != null && active.id.equals(id) ? active : read(file(id));
        return trendPoints(a, maxPoints);
    }
    private static List<Point> trendPoints(Accumulator a, int maxPoints) {
        if (a == null || maxPoints <= 0) return Collections.emptyList();
        List<Point> source = new ArrayList<Point>(a.points);
        if (a.lastPoint != null && (source.isEmpty() || source.get(source.size() - 1) != a.lastPoint))
            source.add(a.lastPoint);
        ArrayList<Point> result = new ArrayList<Point>();
        int count = Math.min(Math.min(maxPoints, MAX_TREND_POINTS), source.size());
        for (int i = 0; i < count; i++) {
            int index = count <= 1 ? source.size() - 1 : (int) ((long) i * (source.size() - 1) / (count - 1));
            result.add(source.get(index));
        }
        return Collections.unmodifiableList(result);
    }

    private static final class Accumulator {
        final String id, scooterId;
        String label;
        String notes = "";
        final long startWall;
        long lastWall, lastMono, elapsed, electricalMs, speedMs, movingMs, stationaryMs;
        long samples, gaps, clockDiscontinuities, directionChanges, confirmedMs, unconfirmedMs, pointStride = 1;
        long movingElectricalMs, stationaryElectricalMs, unknownMotionElectricalMs;
        boolean stopped, recovered, odometerReset;
        CurrentDirection direction = CurrentDirection.UNCONFIRMED;
        Double startSoc, endSoc, startVoltage, minVoltage, maxVoltage, minTemperature, maxTemperature;
        Double startOdo, endOdo, maxSpeed, peakPower, maxCurrent;
        Double previousPower, previousSpeed;
        Point lastPoint;
        double throughputWh, drawWh, regeneratedWh, speedDistance, movingDistance;
        double movingThroughputWh, stationaryThroughputWh, unknownMotionThroughputWh;
        final ArrayList<Point> points = new ArrayList<Point>();
        Accumulator(String id, String scooterId, String label, long wall, long mono) {
            this.id = id; this.scooterId = scooterId; this.label = label;
            startWall = lastWall = wall; lastMono = mono;
        }
        void breakAnchor() { previousPower = null; previousSpeed = null; }
        void recover() { recovered = true; gaps++; breakAnchor(); }
        long advance(long wall, long mono) {
            long dt = mono >= lastMono ? mono - lastMono : 0;
            if (mono < lastMono) { clockDiscontinuities++; gaps++; breakAnchor(); }
            // On a reboot use wall time only for the missing coverage, never for energy.
            if (mono < lastMono && wall > lastWall) dt = wall - lastWall;
            elapsed += dt; lastWall = wall; lastMono = mono; return dt;
        }
        void gap(long wall, long mono) { advance(wall, mono); gaps++; breakAnchor(); }
        void heartbeat(long wall, long mono) { advance(wall, mono); breakAnchor(); }
        void stop(long wall, long mono) { advance(wall, mono); stopped = true; breakAnchor(); }
        void accept(long wall, long mono, Map<String, Sample> m) {
            long previousMono = lastMono;
            long dt = advance(wall, mono);
            boolean sameClock = mono >= previousMono;
            Double voltage = number(m, "voltage_v", mono), current = number(m, "current_a", mono);
            if (voltage != null && (voltage <= 0 || voltage > 200)) voltage = null;
            if (current != null && Math.abs(current) > 500) current = null;
            Sample vs = m.get("voltage_v"), cs = m.get("current_a");
            Double power = voltage != null && current != null && vs.updatedMonoMs == cs.updatedMonoMs
                    && vs.sourceCmd == cs.sourceCmd ? voltage * current : null;
            Double speed = number(m, "speed_kmh", mono);
            if (speed != null && (speed < 0 || speed > 200)) speed = null;
            Double soc = number(m, "battery_percent", mono);
            if (soc != null && (soc < 0 || soc > 100)) soc = null;
            Double odo = number(m, "odometer_km", mono);
            if (odo != null && odo < 0) odo = null;
            Double temperature = number(m, "temperature_c", mono);
            if (temperature != null && (temperature < -60 || temperature > 150)) temperature = null;
            if (soc != null) { if (startSoc == null) startSoc = soc; endSoc = soc; }
            if (voltage != null) { if (startVoltage == null) startVoltage = voltage;
                minVoltage = min(minVoltage, voltage); maxVoltage = max(maxVoltage, voltage); }
            if (temperature != null) { minTemperature = min(minTemperature, temperature); maxTemperature = max(maxTemperature, temperature); }
            if (odo != null) { if (startOdo == null) startOdo = odo;
                if (endOdo != null && odo + 0.000001 < endOdo) odometerReset = true; endOdo = odo; }
            if (speed != null) maxSpeed = max(maxSpeed, speed);
            if (power != null) peakPower = max(peakPower, Math.abs(power));
            if (current != null) maxCurrent = max(maxCurrent, Math.abs(current));
            boolean shortInterval = sameClock && dt > 0 && dt <= MAX_INTEGRATION_INTERVAL_MS;
            if (shortInterval && previousPower != null && power != null) {
                double[] energy = segmentEnergy(previousPower, power, dt);
                double throughput = energy[0] + energy[1];
                throughputWh += throughput; electricalMs += dt;
                if (previousSpeed == null || speed == null) {
                    unknownMotionThroughputWh += throughput; unknownMotionElectricalMs += dt;
                } else if ((previousSpeed + speed) / 2 > 0.5) {
                    movingThroughputWh += throughput; movingElectricalMs += dt;
                } else { stationaryThroughputWh += throughput; stationaryElectricalMs += dt; }
                if (direction == CurrentDirection.UNCONFIRMED) unconfirmedMs += dt;
                else { confirmedMs += dt;
                    drawWh += direction == CurrentDirection.POSITIVE_IS_DRAW ? energy[0] : energy[1];
                    regeneratedWh += direction == CurrentDirection.POSITIVE_IS_DRAW ? energy[1] : energy[0]; }
            }
            if (shortInterval && previousSpeed != null && speed != null) {
                double distance = (previousSpeed + speed) / 2 * dt / 3600000.0;
                speedDistance += distance; speedMs += dt;
                if ((previousSpeed + speed) / 2 > 0.5) { movingMs += dt; movingDistance += distance; }
                else stationaryMs += dt;
            }
            if (dt > MAX_INTEGRATION_INTERVAL_MS && samples > 0) gaps++;
            previousPower = power; previousSpeed = speed; samples++;
            Point point = new Point(wall, mono, elapsed, (int)Math.min(Integer.MAX_VALUE, gaps), voltage, power, speed, soc, odo);
            lastPoint = point;
            if ((samples - 1) % pointStride == 0) {
                if (points.size() >= MAX_TREND_POINTS) {
                    for (int i = points.size() - 2; i > 0; i -= 2) points.remove(i);
                    pointStride *= 2;
                }
                points.add(point);
            }
        }
    }

    // Exact trapezoidal signed energy, split at zero when direction reverses.
    private static double[] segmentEnergy(double a, double b, long dt) {
        double factor = dt / 3600000.0;
        if (a >= 0 && b >= 0) return new double[] {(a + b) * factor / 2, 0};
        if (a <= 0 && b <= 0) return new double[] {0, -(a + b) * factor / 2};
        double magnitude = Math.abs(a) + Math.abs(b);
        double first = a * a / magnitude * factor / 2;
        double second = b * b / magnitude * factor / 2;
        return a > 0 ? new double[] {first, second} : new double[] {second, first};
    }
    private static Double number(Map<String, Sample> m, String key, long mono) {
        Sample sample = m.get(key);
        return sample != null && finite(sample.number) && sample.updatedMonoMs >= 0
                && mono >= sample.updatedMonoMs && mono - sample.updatedMonoMs <= MAX_SENSOR_AGE_MS
                ? sample.number : null;
    }

    private Accumulator read(File file) throws IOException {
        if (!file.isFile()) return null;
        String id = file.getName().substring(0, file.getName().length() - 4);
        Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        Accumulator a = null;
        Map<String, Sample> pending = null;
        String pendingFrame = null;
        long pendingWall = 0, pendingMono = 0;
        int expectedSensors = -1;
        try {
            List<String> r;
            while ((r = csvRecord(reader)) != null) {
                if (r.size() != 15 || !"1".equals(r.get(0))) { pending = null; continue; }
                String event = r.get(1);
                try {
                    long wall = Long.parseLong(r.get(3)), mono = Long.parseLong(r.get(4));
                    if ("START".equals(event) && a == null) {
                        a = new Accumulator(id, r.get(6), r.get(8), wall, mono);
                        a.direction = direction(r.get(13));
                    } else if (a != null && "RENAME".equals(event)) {
                        a.label = trim(r.get(14), 200);
                    } else if (a != null && !a.stopped) {
                        if ("BEGIN".equals(event)) {
                            pending = new LinkedHashMap<String, Sample>(); pendingFrame = r.get(5);
                            pendingWall = wall; pendingMono = mono;
                            expectedSensors = r.get(7).length() == 0 ? -1 : Integer.parseInt(r.get(7));
                        } else if ("SENSOR".equals(event) && pending != null && pendingFrame.equals(r.get(5))
                                && pendingWall == wall && pendingMono == mono && pending.size() < MAX_METRICS) {
                            Double value = r.get(7).length() == 0 ? null : Double.valueOf(r.get(7));
                            pending.put(r.get(6), new Sample(value, r.get(8), r.get(9), Integer.parseInt(r.get(10)), Long.parseLong(r.get(11))));
                        } else if ("COMMIT".equals(event) && pending != null && pendingFrame.equals(r.get(5))
                                && pendingWall == wall && pendingMono == mono
                                && (expectedSensors < 0 || expectedSensors == pending.size())
                                && (r.get(7).length() == 0 || Integer.parseInt(r.get(7)) == pending.size())) {
                            a.accept(wall, mono, pending); pending = null;
                        } else if ("GAP".equals(event)) { a.gap(wall, mono); pending = null; }
                        else if ("HEARTBEAT".equals(event)) { a.heartbeat(wall, mono); pending = null; }
                        else if ("RECOVER".equals(event)) { a.recover(); pending = null; }
                        else if ("STOP".equals(event)) { a.stop(wall, mono); pending = null; }
                        else if ("NOTE".equals(event)) a.notes = r.get(14);
                        else if ("DIRECTION".equals(event)) { CurrentDirection changed = direction(r.get(13)); if (changed != a.direction && a.samples > 0) a.directionChanges++; a.direction = changed; a.breakAnchor(); }
                    }
                } catch (NumberFormatException ignored) { pending = null; }
            }
        } finally { reader.close(); }
        return a;
    }
    private static CurrentDirection direction(String value) {
        try { return CurrentDirection.valueOf(value); }
        catch (IllegalArgumentException ignored) { return CurrentDirection.UNCONFIRMED; }
    }
    private File file(String id) {
        if (id == null || !id.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new IllegalArgumentException("Invalid ride ID");
        return new File(directory, id + ".csv");
    }
    private static boolean isJournal(File file) {
        return file.isFile() && file.getName().matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}\\.csv");
    }
    /** Remove an incomplete trailing frame before appending after a crash. */
    private static void repairTail(File file) throws IOException {
        File replacement = new File(file.getParentFile(), file.getName() + ".repair");
        Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(replacement), "UTF-8"));
        List<List<String>> pending = null;
        try {
            List<String> row;
            while ((row = csvRecord(reader)) != null) {
                if (row.size() != 15) continue;
                if ("BEGIN".equals(row.get(1))) { pending = new ArrayList<List<String>>(); pending.add(row); }
                else if ("SENSOR".equals(row.get(1))) { if (pending != null && pending.size() <= MAX_METRICS) pending.add(row); }
                else if ("COMMIT".equals(row.get(1))) {
                    if (pending != null && pending.get(0).get(5).equals(row.get(5))) {
                        for (List<String> r : pending) writer.write(csvRow(r)); writer.write(csvRow(row));
                    }
                    pending = null;
                } else { pending = null; writer.write(csvRow(row)); }
            }
        } finally { reader.close(); writer.close(); }
        if (!replacement.renameTo(file)) throw new IOException("Could not recover interrupted ride journal");
    }
    private static void writeNew(File file, String text) throws IOException {
        if (file.exists()) throw new IOException("Ride ID already exists");
        write(file, text, false);
    }
    private static void append(File file, String text) throws IOException { write(file, text, true); }
    private static void write(File file, String text, boolean append) throws IOException {
        FileOutputStream stream = new FileOutputStream(file, append);
        try { stream.write(text.getBytes("UTF-8")); stream.flush(); }
        finally { stream.close(); }
    }
    private static String row(String event, long wall, long mono, String frame, String key,
            String value, String text, String unit, String cmd, String updated, String age,
            String direction, String details) {
        ArrayList<String> columns = new ArrayList<String>();
        Collections.addAll(columns, Integer.toString(SCHEMA_VERSION), event, utc(wall),
                Long.toString(wall), Long.toString(mono), frame, key, value, text, unit,
                cmd, updated, age, direction, details);
        return csvRow(columns);
    }
    private static String utc(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC")); return format.format(new Date(millis));
    }
    private static String csvRow(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(',');
            String v = value == null ? "" : value;
            if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0)
                out.append('"').append(v.replace("\"", "\"\"")).append('"');
            else out.append(v);
        }
        return out.append('\n').toString();
    }
    /** Streaming RFC4180 reader. A partial final row or quoted field is discarded. */
    private static List<String> csvRecord(Reader reader) throws IOException {
        ArrayList<String> result = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, afterQuote = false, began = false;
        int size = 0, c;
        while ((c = reader.read()) != -1) {
            began = true;
            if (++size > 65536) throw new IOException("Ride journal record too large");
            if (quoted) {
                if (c == '"') { quoted = false; afterQuote = true; }
                else field.append((char) c);
            } else if (afterQuote && c == '"') { field.append('"'); quoted = true; afterQuote = false; }
            else if (c == ',' ) { result.add(field.toString()); field.setLength(0); afterQuote = false; }
            else if (c == '\n') { result.add(field.toString()); return result; }
            else if (c == '\r') { /* CRLF; CR inside quotes was retained above. */ }
            else if (c == '"' && field.length() == 0 && !afterQuote) quoted = true;
            else { field.append((char) c); afterQuote = false; }
        }
        return null; // Journals always end each committed row with a newline.
    }
    private static boolean isSecret(String key) {
        String lower = key.toLowerCase(Locale.US);
        return lower.contains("password") || lower.contains("token") || lower.contains("credential")
                || lower.contains("owner_identity") || lower.contains("auth_identity") || lower.equals("pin");
    }
    private static String trim(String s, int max) {
        if (s == null) return ""; return s.length() <= max ? s : s.substring(0, max);
    }
    private static boolean finite(Double value) { return value != null && !value.isNaN() && !value.isInfinite(); }
    private static Double subtract(Double a, Double b) { return a == null || b == null ? null : a - b; }
    private static Double min(Double a, double b) { return a == null ? b : Math.min(a, b); }
    private static Double max(Double a, double b) { return a == null ? b : Math.max(a, b); }
    private static String json(Map<String, Object> values) {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (out.length() > 1) out.append(','); out.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            out.append(v == null ? "null" : v instanceof Number || v instanceof Boolean ? v.toString() : quote(v.toString()));
        }
        return out.append('}').toString();
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 32) out.append(String.format(Locale.US, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
