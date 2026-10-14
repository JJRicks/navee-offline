package local.navee.offlinelock;

import java.io.*;
import java.util.*;

/** Independent phone journal. Never feeds frames into the electrical RideStore accumulator. */
public final class PhoneRideStore {
    private static final String HEADER = "event,wall_ms,monotonic_ms,a,b,c,d,e,f,g,h,i,j\n";
    private final File directory;
    private String cachedId;
    private long cachedLength, cachedModified, motionLength;
    private Track cached;

    public PhoneRideStore(File directory) throws IOException {
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create phone ride folder");
    }

    private File file(String id, String suffix) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,80}"))
            throw new IllegalArgumentException("Invalid ride ID");
        return new File(directory, id + suffix);
    }

    public File rawCsv(String id) {
        return file(id, "-phone.csv");
    }

    public File motionCsv(String id) {
        return file(id, "-motion.csv");
    }

    public synchronized void start(String id, long wall, long mono, String placement)
            throws IOException {
        File f = rawCsv(id);
        if (f.exists()) repairTail(f);
        if (!f.exists() || f.length() == 0)
            append(
                    f,
                    HEADER
                            + row(
                                    "START",
                                    wall,
                                    mono,
                                    placement.equals("mounted") ? "mounted" : "pocket"));
        else append(f, row("GAP", wall, mono));
        File motion = motionCsv(id);
        if (motion.exists()) repairTail(motion);
        if (!motion.exists() || motion.length() == 0)
            append(
                    motion,
                    "monotonic_ms,raw_x_mps2,raw_y_mps2,raw_z_mps2,linear_x_mps2,linear_y_mps2,linear_z_mps2,earth_east_mps2,earth_north_mps2,earth_up_mps2,rotation_x,rotation_y,rotation_z,rotation_w,accuracy\n");
    }

    public synchronized void appendBatch(String id, String journal, String motion)
            throws IOException {
        if (!journal.isEmpty()) append(rawCsv(id), journal);
        if (!motion.isEmpty()) append(motionCsv(id), motion);
    }

    public synchronized void stop(String id, long wall, long mono) throws IOException {
        append(rawCsv(id), row("STOP", wall, mono));
    }

    public synchronized void delete(String id) throws IOException {
        for (File f : new File[] {rawCsv(id), motionCsv(id)})
            if (f.exists() && !f.delete()) throw new IOException("Cannot delete phone ride");
        if (id.equals(cachedId)) cached = null;
    }

    private static void repairTail(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
            long end = f.length();
            if (end == 0) return;
            f.seek(end - 1);
            if (f.read() == '\n') return;
            long at = end - 1;
            while (at >= 0) {
                f.seek(at);
                if (f.read() == '\n') break;
                at--;
            }
            f.setLength(at + 1);
        }
    }

    private static void append(File f, String text) throws IOException {
        try (Writer out =
                new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8"))) {
            out.write(text);
        }
    }

    public static String row(String event, long wall, long mono, Object... values) {
        StringBuilder b =
                new StringBuilder(event).append(',').append(wall).append(',').append(mono);
        for (Object value : values) b.append(',').append(value == null ? "" : value);
        return b.append('\n').toString();
    }

    /** Location and all phone sensors retain source monotonic times, not callback receipt times. */
    public static final class Point {
        public final long monoMs, wallMs, elapsedMs;
        public final double latitude, longitude, accuracyM;
        public final int segment;
        public final boolean gapBefore;
        public final Double speedMps,
                speedAccuracyMps,
                altitudeM,
                verticalAccuracyM,
                relativeAltitudeM,
                gradePercent,
                gpsAccelerationMps2,
                scooterAccelerationMps2,
                powerW,
                batteryPercent,
                scooterSpeedKmh;
        public final String elevationSource;

        private Point(
                Fix f,
                long start,
                int segment,
                Double relative,
                String source,
                Double grade,
                Double gpsAccel,
                TelemetrySample t) {
            monoMs = f.mono;
            wallMs = f.wall;
            elapsedMs = Math.max(0, monoMs - start);
            latitude = f.lat;
            longitude = f.lon;
            accuracyM = f.accuracy;
            this.segment = segment;
            gapBefore = f.gap;
            speedMps = f.speed;
            speedAccuracyMps = f.speedAccuracy;
            altitudeM = f.alt;
            verticalAccuracyM = f.verticalAccuracy;
            relativeAltitudeM = relative;
            elevationSource = source;
            gradePercent = grade;
            gpsAccelerationMps2 = gpsAccel;
            boolean speedFresh = t != null && fresh(qTime(f), t.speedMono),
                    powerFresh = t != null && fresh(qTime(f), t.powerMono),
                    socFresh = t != null && fresh(qTime(f), t.socMono);
            scooterAccelerationMps2 = speedFresh ? t.acceleration : null;
            powerW = powerFresh ? t.power : null;
            batteryPercent = socFresh ? t.soc : null;
            scooterSpeedKmh = speedFresh ? t.speed : null;
        }
    }

    public static final class Track {
        public final String id, placement, status;
        public final List<Point> points;
        private final List<Point> allPoints;
        public final int locationCount, rejectedLocationCount, rejectedElevationCount;
        public final long motionSampleCount;
        public final Double distanceMeters,
                ascentMeters,
                descentMeters,
                maxGpsAccelerationMps2,
                minGpsAccelerationMps2,
                maxScooterAccelerationMps2,
                minScooterAccelerationMps2,
                maxLinearAccelerationMps2;

        private Track(
                String id,
                String placement,
                List<Point> all,
                int rejected,
                int rejectedElevation,
                long motionCount,
                Double distance,
                Double ascent,
                Double descent,
                Double motionPeak,
                List<TelemetrySample> telemetry) {
            this.id = id;
            this.placement = placement;
            allPoints = Collections.unmodifiableList(all);
            locationCount = all.size();
            rejectedLocationCount = rejected;
            rejectedElevationCount = rejectedElevation;
            motionSampleCount = motionCount;
            distanceMeters = distance;
            ascentMeters = ascent;
            descentMeters = descent;
            maxLinearAccelerationMps2 = motionPeak;
            Double maxG = null, minG = null, maxS = null, minS = null;
            for (Point p : all) {
                maxG = max(maxG, p.gpsAccelerationMps2);
                minG = min(minG, p.gpsAccelerationMps2);
            }
            for (TelemetrySample t : telemetry) {
                maxS = max(maxS, t.acceleration);
                minS = min(minS, t.acceleration);
            }
            maxGpsAccelerationMps2 = maxG;
            minGpsAccelerationMps2 = minG;
            maxScooterAccelerationMps2 = maxS;
            minScooterAccelerationMps2 = minS;
            int stride = Math.max(1, (all.size() + 2998) / 2999);
            ArrayList<Point> shown = new ArrayList<>();
            for (int i = 0; i < all.size(); i++)
                if (i % stride == 0 || i == all.size() - 1) shown.add(all.get(i));
            points = Collections.unmodifiableList(shown);
            status =
                    all.isEmpty()
                            ? "No usable GPS fixes recorded"
                            : all.size()
                                    + " GPS fixes"
                                    + (rejected > 0
                                            ? " · " + rejected + " low-quality fixes excluded"
                                            : "")
                                    + (rejectedElevation > 0 ? " · " + rejectedElevation + " elevation outliers excluded" : "");
        }

        public String toCsv() {
            StringBuilder b =
                    new StringBuilder(
                            "wall_ms,monotonic_ms,elapsed_ms,segment,latitude,longitude,accuracy_m,speed_mps,altitude_m,vertical_accuracy_m,relative_altitude_m,elevation_source,grade_percent,gps_acceleration_mps2,scooter_acceleration_mps2,power_w,battery_percent,scooter_speed_kmh\n");
            for (Point p : allPoints) {
                Object[] v = {
                    p.wallMs,
                    p.monoMs,
                    p.elapsedMs,
                    p.segment,
                    p.latitude,
                    p.longitude,
                    p.accuracyM,
                    p.speedMps,
                    p.altitudeM,
                    p.verticalAccuracyM,
                    p.relativeAltitudeM,
                    p.elevationSource,
                    p.gradePercent,
                    p.gpsAccelerationMps2,
                    p.scooterAccelerationMps2,
                    p.powerW,
                    p.batteryPercent,
                    p.scooterSpeedKmh
                };
                for (int i = 0; i < v.length; i++) {
                    if (i > 0) b.append(',');
                    if (v[i] != null) b.append(v[i]);
                }
                b.append('\n');
            }
            return b.toString();
        }

        public String toJson() {
            StringBuilder b =
                    new StringBuilder("{\"schemaVersion\":1,\"id\":\"")
                            .append(id)
                            .append("\",\"placement\":\"")
                            .append(placement)
                            .append("\",\"distanceMeters\":")
                            .append(distanceMeters)
                            .append(",\"ascentMeters\":")
                            .append(ascentMeters)
                            .append(",\"descentMeters\":")
                            .append(descentMeters)
                            .append(",\"rejectedLocationCount\":")
                            .append(rejectedLocationCount)
                            .append(",\"rejectedElevationCount\":").append(rejectedElevationCount)
                            .append(",\"motionSampleCount\":")
                            .append(motionSampleCount)
                            .append(",\"points\":[");
            boolean first = true;
            for (Point p : allPoints) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"monoMs\":")
                        .append(p.monoMs)
                        .append(",\"wallMs\":")
                        .append(p.wallMs)
                        .append(",\"segment\":")
                        .append(p.segment)
                        .append(",\"latitude\":")
                        .append(p.latitude)
                        .append(",\"longitude\":")
                        .append(p.longitude)
                        .append(",\"accuracyM\":")
                        .append(p.accuracyM)
                        .append(",\"speedMps\":")
                        .append(p.speedMps)
                        .append(",\"altitudeM\":")
                        .append(p.altitudeM)
                        .append(",\"verticalAccuracyM\":")
                        .append(p.verticalAccuracyM)
                        .append(",\"relativeAltitudeM\":")
                        .append(p.relativeAltitudeM)
                        .append(",\"gradePercent\":")
                        .append(p.gradePercent)
                        .append(",\"gpsAccelerationMps2\":")
                        .append(p.gpsAccelerationMps2)
                        .append(",\"scooterAccelerationMps2\":")
                        .append(p.scooterAccelerationMps2)
                        .append(",\"powerW\":")
                        .append(p.powerW)
                        .append(",\"batteryPercent\":")
                        .append(p.batteryPercent)
                        .append(",\"scooterSpeedKmh\":")
                        .append(p.scooterSpeedKmh)
                        .append('}');
            }
            return b.append("]}").toString();
        }

        public String toGpx() {
            StringBuilder b =
                    new StringBuilder(
                                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\""
                                        + " creator=\"Navee Offline\""
                                        + " xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Ride"
                                        + " ")
                            .append(id)
                            .append("</name>");
            int segment = -1;
            java.text.SimpleDateFormat date =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
            date.setTimeZone(TimeZone.getTimeZone("UTC"));
            for (Point p : allPoints) {
                if (segment != p.segment) {
                    if (segment >= 0) b.append("</trkseg>");
                    b.append("<trkseg>");
                    segment = p.segment;
                }
                b.append("<trkpt lat=\"")
                        .append(p.latitude)
                        .append("\" lon=\"")
                        .append(p.longitude)
                        .append("\">");
                b.append("<time>")
                        .append(date.format(new Date(p.wallMs)))
                        .append("</time></trkpt>");
            }
            if (segment >= 0) b.append("</trkseg>");
            return b.append("</trk></gpx>").toString();
        }
    }

    private static final class Fix {
        long mono, wall;
        double lat, lon, accuracy;
        Double speed, speedAccuracy, alt, verticalAccuracy;
        boolean gap;
        int epoch;
    }

    private static final class Pressure {
        long mono;
        double altitude;
        int epoch;

        Pressure(long m, double a, int e) {
            mono = m;
            altitude = a;
            epoch = e;
        }
    }

    private static final class TelemetrySample {
        long mono, speedMono, powerMono, socMono;
        Double speed, power, soc, acceleration;
        int epoch;
    }

    private static long qTime(Fix f) {
        return f.mono;
    }

    private static boolean fresh(long now, long source) {
        return source >= 0 && now >= source && now - source <= 3000;
    }

    private static Double max(Double a, Double b) {
        if (b == null) return a;
        if (a == null) return b;
        return Math.max(a, b);
    }

    private static Double min(Double a, Double b) {
        if (b == null) return a;
        if (a == null) return b;
        return Math.min(a, b);
    }

    private static Double number(String[] v, int i) {
        if (i >= v.length || v[i].isEmpty()) return null;
        try {
            double n = Double.parseDouble(v[i]);
            return Double.isFinite(n) ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double required(String[] v, int i) {
        Double n = number(v, i);
        if (n == null) throw new IllegalArgumentException();
        return n;
    }

    private static long clock(String[] v, int i) {
        long n = Long.parseLong(v[i]);
        if (n < 0) throw new IllegalArgumentException();
        return n;
    }
    /** Read only newline-committed rows; an interrupted final write is ignored. */
    public synchronized Track load(String id) throws IOException {
        File f = rawCsv(id), mf = motionCsv(id);
        if (cached != null
                && id.equals(cachedId)
                && cachedLength == f.length()
                && cachedModified == f.lastModified()
                && motionLength == mf.length()) return cached;
        if (f.length() > 128L * 1024 * 1024)
            throw new IOException("Phone journal is too large to preview; export the raw file");
        ArrayList<Fix> fixes = new ArrayList<>();
        ArrayList<Pressure> pressures = new ArrayList<>();
        ArrayList<TelemetrySample> telemetry = new ArrayList<>();
        String placement = "pocket";
        long start = 0, motionCount = 0;
        Double motionPeak = null, filteredPressure = null, baseline = null;
        long pressureTime = -1;
        int rejected = 0, epoch = 0;
        boolean gap = true;
        if (f.exists())
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int ch;
                while ((ch = in.read()) >= 0) {
                    if (ch != '\n') {
                        if (line.size() < 4096) line.write(ch);
                        continue;
                    }
                    String[] v = new String(line.toByteArray(), "UTF-8").split(",", -1);
                    line.reset();
                    if (v.length < 3) continue;
                    try {
                        long wall = clock(v, 1), mono = clock(v, 2);
                        String event = v[0];
                        if (event.equals("START")) {
                            start = mono;
                            placement =
                                    v.length > 3 && v[3].equals("mounted") ? "mounted" : "pocket";
                        } else if (event.equals("GAP")) {
                            gap = true;
                            epoch++;
                            filteredPressure = null;
                            baseline = null;
                            pressureTime = -1;
                        } else if (event.equals("GPS")) {
                            Fix q = new Fix();
                            q.wall = wall;
                            q.mono = mono;
                            q.lat = required(v, 3);
                            q.lon = required(v, 4);
                            q.accuracy = required(v, 5);
                            q.speed = number(v, 6);
                            q.speedAccuracy = number(v, 7);
                            q.alt = number(v, 8);
                            q.verticalAccuracy = number(v, 9);
                            q.gap = gap;
                            q.epoch = epoch;
                            gap = false;
                            if (Math.abs(q.lat) > 90
                                    || Math.abs(q.lon) > 180
                                    || q.accuracy < 0
                                    || q.accuracy > 35) {
                                rejected++;
                                gap = true;
                                continue;
                            }
                            if (q.speed != null && (q.speed < 0 || q.speed > 25)) q.speed = null;
                            if (q.speedAccuracy != null
                                    && (q.speedAccuracy < 0 || q.speedAccuracy > 1.5))
                                q.speed = null;
                            fixes.add(q);
                        } else if (event.equals("PRESSURE")) {
                            double p = required(v, 3);
                            if (p < 300 || p > 1100) continue;
                            if (pressureTime >= 0
                                    && (mono <= pressureTime || mono - pressureTime > 10000)) {
                                filteredPressure = null;
                                baseline = null;
                                epoch++;
                                gap = true;
                            }
                            double alpha =
                                    pressureTime < 0
                                            ? 1
                                            : Math.min(1, (mono - pressureTime) / 3000.0);
                            filteredPressure =
                                    filteredPressure == null
                                            ? p
                                            : filteredPressure + alpha * (p - filteredPressure);
                            pressureTime = mono;
                            if (baseline == null) baseline = filteredPressure;
                            double relative =
                                    44330
                                            * (1
                                                    - Math.pow(
                                                            filteredPressure / baseline,
                                                            0.190294957));
                            pressures.add(new Pressure(mono, relative, epoch));
                        } else if (event.equals("TELEMETRY")) {
                            TelemetrySample t = new TelemetrySample();
                            t.mono = mono;
                            t.epoch = epoch;
                            t.speed = number(v, 3);
                            t.power = number(v, 4);
                            t.soc = number(v, 5);
                            t.speedMono = v.length > 6 ? (long) required(v, 6) : mono;
                            t.powerMono = v.length > 7 ? (long) required(v, 7) : mono;
                            t.socMono = v.length > 8 ? (long) required(v, 8) : mono;
                            telemetry.add(t);
                        } else if (event.equals("MOTION")) {
                            motionCount += (long) required(v, 3);
                            motionPeak = max(motionPeak, number(v, 4));
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        // Derivatives use source-clock observations over >=2 s, never synthetic zero values.
        for (int i = 0; i < telemetry.size(); i++) {
            TelemetrySample t = telemetry.get(i);
            if (t.speed == null || !fresh(t.mono, t.speedMono)) continue;
            for (int j = i - 1; j >= 0; j--) {
                TelemetrySample old = telemetry.get(j);
                long dt = t.speedMono - old.speedMono;
                if (old.epoch != t.epoch || dt > 4000) break;
                if (dt >= 2000 && old.speed != null && fresh(old.mono, old.speedMono)) {
                    t.acceleration = (t.speed - old.speed) / 3.6 / (dt / 1000.0);
                    break;
                }
            }
        }
        ArrayList<Point> points = new ArrayList<>();
        ArrayDeque<Point> window = new ArrayDeque<>();
        Fix previous = null;
        int segment = 0, pressureIndex = 0, telemetryIndex = 0;
        double distance = 0, ascent = 0, descent = 0;
        boolean hasDistance = false, hasElevation = false, breakNext = false;
        Double elevationAnchor = null, gpsBaseline = null, pressureBaseline = null;
        String anchorSource = "", filterSource = "";
        Double filterElevation = null, filterCheckpoint = null; long filterMono = -1, filterCheckpointMono = -1; int rejectedElevation = 0;
        double segmentDistance = 0;
        Map<Point, Double> positions = new IdentityHashMap<>();
        for (Fix q : fixes) {
            if (breakNext) {
                q.gap = true;
                breakNext = false;
            }
            double step = 0;
            if (previous != null) {
                long dt = q.mono - previous.mono;
                step = distanceMeters(previous.lat, previous.lon, q.lat, q.lon);
                if (q.epoch != previous.epoch || dt <= 0 || dt > 10000) q.gap = true;
                if (!q.gap && step > 25 * (dt / 1000.0) + q.accuracy + previous.accuracy) {
                    rejected++;
                    breakNext = true;
                    continue;
                }
                if (dt <= 0 && q.epoch == previous.epoch) {
                    rejected++;
                    continue;
                }
            }
            if (q.gap || previous == null) {
                segment++;
                window.clear();
                positions.clear();
                segmentDistance = 0;
                elevationAnchor = null;
                gpsBaseline = null;
                pressureBaseline = null; filterElevation = null; filterSource = "";
            } else {
                boolean moving =
                        (q.speed != null && q.speed >= 1)
                                || (previous.speed != null && previous.speed >= 1);
                if (moving) {
                    distance += step;
                    segmentDistance += step;
                    hasDistance = true;
                }
            }
            while (pressureIndex + 1 < pressures.size()
                    && (pressures.get(pressureIndex + 1).epoch < q.epoch
                            || (pressures.get(pressureIndex + 1).epoch == q.epoch
                                    && pressures.get(pressureIndex + 1).mono <= q.mono)))
                pressureIndex++;
            Pressure pressure = pressures.isEmpty() ? null : pressures.get(pressureIndex);
            Double relative = null;
            String source = "";
            if (pressure != null
                    && pressure.epoch == q.epoch
                    && q.mono >= pressure.mono
                    && q.mono - pressure.mono <= 3000) {
                if (pressureBaseline == null) pressureBaseline = pressure.altitude;
                relative = pressure.altitude - pressureBaseline;
                source = "barometer";
            } else if (q.alt != null
                    && q.verticalAccuracy != null
                    && q.verticalAccuracy >= 0
                    && q.verticalAccuracy <= 10) {
                if (gpsBaseline == null) gpsBaseline = q.alt;
                relative = q.alt - gpsBaseline;
                source = "GPS";
            }
            // Reject implausible vertical excursions without deleting otherwise useful GPS fixes.
            // A parked/riding scooter cannot sustain 2.5 m/s vertical travel; allow 4 m of sensor
            // noise. Check against the last accepted height, so a ramp-shaped spike cannot pass
            // merely by arriving in small one-second steps. Rejected samples do not enlarge the
            // re-entry tolerance; a prolonged loss resets the reference after 20 seconds.
            // Missing elevation breaks ascent/grade.
            if (relative != null) {
                long dt = q.mono - filterMono;
                if (filterElevation == null || !source.equals(filterSource) || dt <= 0 || dt > 20000) {
                    filterElevation = relative; filterMono = q.mono; filterSource = source; filterCheckpoint = relative; filterCheckpointMono = q.mono;
                    elevationAnchor = null;
                } else if (Math.abs(relative - filterElevation) > Math.max(4, Math.min(dt, 3000) / 1000.0 * 2.5)
                        || Math.abs(relative - filterCheckpoint) > Math.max(4, (q.mono - filterCheckpointMono) / 1000.0 * 2.5)) {
                    relative = null; rejectedElevation++; elevationAnchor = null; window.clear(); positions.clear();
                } else {
                    filterElevation = relative; filterMono = q.mono;
                    if (q.mono - filterCheckpointMono >= 10000) { filterCheckpoint = relative; filterCheckpointMono = q.mono; }
                }
            }
            while (telemetryIndex + 1 < telemetry.size()
                    && (telemetry.get(telemetryIndex + 1).epoch < q.epoch
                            || (telemetry.get(telemetryIndex + 1).epoch == q.epoch
                                    && telemetry.get(telemetryIndex + 1).mono <= q.mono)))
                telemetryIndex++;
            TelemetrySample t = telemetry.isEmpty() ? null : telemetry.get(telemetryIndex);
            if (t != null && (t.epoch != q.epoch || q.mono < t.mono || q.mono - t.mono > 2500))
                t = null;
            Double gpsAccel = null;
            if (q.speed != null && q.speedAccuracy != null) {
                for (int j = points.size() - 1; j >= 0; j--) {
                    Point old = points.get(j);
                    long dt = q.mono - old.monoMs;
                    if (old.segment != segment || dt > 4000 || dt <= 0) break;
                    if (dt >= 2000
                            && old.speedMps != null
                            && old.speedAccuracyMps != null
                            && old.speedAccuracyMps <= 1.5) {
                        gpsAccel = (q.speed - old.speedMps) / (dt / 1000.0);
                        break;
                    }
                }
            }
            Double grade = null;
            if (relative != null && q.speed != null && q.speed >= 2) {
                for (Point old : window) {
                    double run = segmentDistance - positions.get(old);
                    if (run < 50
                            || run > 200
                            || old.relativeAltitudeM == null
                            || !source.equals(old.elevationSource)) continue;
                    if (source.equals("GPS")
                            && (run < 150
                                    || old.verticalAccuracyM == null
                                    || (q.verticalAccuracy + old.verticalAccuracyM) * 100 / run
                                            > 5)) continue;
                    double candidate = 100 * (relative - old.relativeAltitudeM) / run;
                    if (Math.abs(candidate) <= 45) grade = candidate;
                    break;
                }
            }
            if (relative != null && source.equals("barometer") && q.speed != null && q.speed >= 1) {
                if (elevationAnchor == null || !source.equals(anchorSource)) {
                    elevationAnchor = relative;
                    anchorSource = source;
                } else {
                    double delta = relative - elevationAnchor;
                    if (Math.abs(delta) >= 2) {
                        if (delta > 0) ascent += delta;
                        else descent -= delta;
                        elevationAnchor = relative;
                        hasElevation = true;
                    }
                }
            }
            Point point = new Point(q, start, segment, relative, source, grade, gpsAccel, t);
            points.add(point);
            window.add(point);
            positions.put(point, segmentDistance);
            while (!window.isEmpty()
                    && (segmentDistance - positions.get(window.peekFirst()) > 200
                            || q.mono - window.peekFirst().monoMs > 120000)) {
                positions.remove(window.removeFirst());
            }
            previous = q;
        }
        cached =
                new Track(
                        id,
                        placement,
                        points,
                        rejected,
                        rejectedElevation,
                        motionCount,
                        hasDistance ? distance : null,
                        hasElevation ? ascent : null,
                        hasElevation ? descent : null,
                        motionPeak,
                        telemetry);
        cachedId = id;
        cachedLength = f.length();
        cachedModified = f.lastModified();
        motionLength = mf.length();
        return cached;
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.toRadians(lat2 - lat1),
                b = Math.toRadians(lon2 - lon1),
                h =
                        Math.sin(a / 2) * Math.sin(a / 2)
                                + Math.cos(Math.toRadians(lat1))
                                        * Math.cos(Math.toRadians(lat2))
                                        * Math.sin(b / 2)
                                        * Math.sin(b / 2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
    }
}
