package local.navee.offlinelock;

import java.util.*;

/** Immutable ride plot data. Display conversion never changes stored sensor values. */
public final class RideChartData {
    public static final String[] KEYS = {"power_w", "speed_kmh", "gps_speed", "voltage_v", "battery_percent", "elevation", "gps_accel", "wh_per_distance"};
    public static final String[] LABELS = {"Power", "Wheel speed", "GPS speed", "Voltage", "Battery %", "Elevation", "GPS acceleration", "Energy / distance"};
    // Distinct Okabe–Ito-inspired hues plus line patterns; never color alone in the legend.
    public static final int[] COLORS = {0xffe69f00, 0xff56b4e9, 0xff009e73, 0xffcc79a7, 0xfff0e442, 0xff0072b2, 0xffd55e00, 0xff999999};
    public static final class Value {
        public final long time; public final Double value; public final int segment;
        public Value(long time, Double value, int segment) { this.time = time; this.value = value; this.segment = segment; }
    }
    public static final class Series {
        public final String key, label, unit; public final int color, index; public final List<Value> values;
        public final double min, max;
        public final long endTime;
        public final long typicalIntervalMs; private final boolean hasData, ordered;
        private double drawnFrom = Double.NaN, drawnTo; private int drawnColumns; private int[] drawn;
        Series(int index, String unit, List<Value> values) { this(index, unit, values, false); }
        Series(int index, String unit, List<Value> values, boolean fullDetail) {
            this.index = index; key = KEYS[index]; label = LABELS[index]; this.unit = unit; color = COLORS[index]; this.values = Collections.unmodifiableList(values);
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            long end = 0;
            for (Value p : values) { checkCanceled(); end = Math.max(end, p.time); if (p.value != null && Double.isFinite(p.value)) { low = Math.min(low, p.value); high = Math.max(high, p.value); } }
            endTime = end;
            hasData=Double.isFinite(low);boolean sorted=true;List<Long> intervals=new ArrayList<>();
            for(int i=1;i<values.size();i++){checkCanceled();if(fullDetail)RideStore.checkDetailMemory();long delta=values.get(i).time-values.get(i-1).time;if(delta<0)sorted=false;if(delta>0)intervals.add(delta);}
            ordered=sorted;Collections.sort(intervals);typicalIntervalMs=intervals.isEmpty()?1000:intervals.get(intervals.size()/2);
            if (!Double.isFinite(low)) { low = 0; high = 1; }
            if (index == 4) { low = 0; high = 100; }
            else if (high == low) { double pad = Math.max(1, Math.abs(high) * .05); low -= pad; high += pad; }
            else { double pad = (high - low) * .06; low -= pad; high += pad; }
            min = low; max = high;
        }
        public boolean available() { return hasData; }
        public boolean connects(Value left, Value right) {
            return left != null && right != null && left.value != null && right.value != null
                    && Double.isFinite(left.value) && Double.isFinite(right.value)
                    && left.segment == right.segment && right.time > left.time
                    && right.time - left.time <= Math.max(10000, typicalIntervalMs * 3);
        }
        /** A real plotted sample, never interpolation or a value carried across a gap. */
        public Value sampleAt(double time) {
            if (!Double.isFinite(time) || values.isEmpty()) return null;
            if (!ordered) {
                // Old recovered journals can have nonmonotonic source time. Preserve their
                // source order and only inspect a real point or a valid adjacent segment.
                for (Value value : values) if (value.time == time) return value;
                for (int i = 1; i < values.size(); i++) {
                    Value left = values.get(i - 1), right = values.get(i);
                    if (left.time < time && time < right.time && connects(left, right))
                        return time - left.time <= right.time - time ? left : right;
                }
                return null;
            }
            int next = bound(time, false);
            if (next < values.size() && values.get(next).time == time) return values.get(next);
            if (next == 0 || next == values.size()) return null;
            Value left = values.get(next - 1), right = values.get(next);
            return connects(left, right) ? time - left.time <= right.time - time ? left : right : null;
        }
        /** Half-open point range includes one neighbor on either side for clipped edge segments. */
        public int[] visibleRange(double from,double to){
            if(!ordered)return new int[]{0,values.size()};
            int first=bound(from,false),end=bound(to,true);
            return new int[]{Math.max(0,first-1),Math.min(values.size(),end+1)};
        }
        /** Pixel buckets retain first/last/min/max and every discontinuity. Negative entries
         * encode the first index of a new path as -(index+1); inspection still uses all values. */
        public int[] drawIndices(double from, double to, int columns) {
            columns = Math.max(1, Math.min(4096, columns));
            if (drawn != null && from == drawnFrom && to == drawnTo && columns == drawnColumns) return drawn;
            ArrayList<Integer> output = new ArrayList<>();
            int[] visible = visibleRange(from, to); int first = -1, last = -1, low = -1, high = -1, bucket = -1;
            boolean start = true; Value previous = null;
            for (int i = visible[0]; i < visible[1]; i++) {
                Value value = values.get(i); boolean valid = value.value != null && Double.isFinite(value.value);
                int nextBucket = (int)Math.floor((value.time - from) / Math.max(1, to - from) * columns);
                boolean gap = previous == null || !connects(previous, value);
                if (first >= 0 && (gap || nextBucket != bucket || !valid)) {
                    emitBucket(output, first, last, low, high, start); first = -1; start = gap || !valid;
                } else if (gap || !valid) start = true;
                if (valid) {
                    if (first < 0) { first = last = low = high = i; bucket = nextBucket; }
                    else { last = i; if (value.value < values.get(low).value) low = i; if (value.value > values.get(high).value) high = i; }
                }
                previous = value;
            }
            if (first >= 0) emitBucket(output, first, last, low, high, start);
            drawn = new int[output.size()]; for (int i = 0; i < drawn.length; i++) drawn[i] = output.get(i);
            drawnFrom = from; drawnTo = to; drawnColumns = columns; return drawn;
        }
        private static void emitBucket(List<Integer> out, int first, int last, int low, int high, boolean start) {
            int[] indices = {first, low, high, last}; Arrays.sort(indices); int previous = -1;
            for (int i : indices) if (i != previous) { out.add(start ? -i - 1 : i); start = false; previous = i; }
        }
        private int bound(double time,boolean after){int low=0,high=values.size();while(low<high){int middle=(low+high)>>>1;long value=values.get(middle).time;if(value<time||(after&&value==time))low=middle+1;else high=middle;}return low;}
    }
    public static Set<String> defaults() { return new LinkedHashSet<String>(Arrays.asList("power_w", "speed_kmh")); }
    public static Double energyPerDistance(Double powerW, Double speedKmh, boolean miles) {
        // W / (km/h) = Wh/km. Near a stop the ratio is not useful and tends to infinity.
        if (powerW == null || speedKmh == null || !Double.isFinite(powerW) || !Double.isFinite(speedKmh) || speedKmh < 3) return null;
        return powerW / speedKmh * (miles ? RideStore.KM_PER_MILE : 1);
    }
    private static final class ClockIndex {
        final List<RideStore.Point> points;
        ClockIndex(List<RideStore.Point> source) { points = new ArrayList<>(source); points.sort(Comparator.comparingLong(p -> p.monotonicMs)); }
        Long align(PhoneRideStore.Point phone) {
            if (points.isEmpty()) return phone.elapsedMs;
            int low = 0, high = points.size();
            while (low < high) { int mid = (low + high) >>> 1; if (points.get(mid).monotonicMs < phone.monoMs) low = mid + 1; else high = mid; }
            int left = low - 1, right = low;
            while (left >= 0 || right < points.size()) {
                checkCanceled();
                long ld = left < 0 ? Long.MAX_VALUE : Math.abs(phone.monoMs - points.get(left).monotonicMs);
                long rd = right >= points.size() ? Long.MAX_VALUE : Math.abs(phone.monoMs - points.get(right).monotonicMs);
                if (Math.min(ld, rd) > 120000) break;
                RideStore.Point p = ld <= rd ? points.get(left--) : points.get(right++);
                long monoDelta = phone.monoMs - p.monotonicMs;
                // Matching wall/source deltas identify the same boot, including recovered recordings.
                if (Math.abs((phone.wallMs - p.wallMs) - monoDelta) <= 3000)
                    return Math.max(0, p.elapsedMs + monoDelta);
            }
            return null;
        }
    }
    public static List<Series> build(List<RideStore.Point> scooter, PhoneRideStore.Track phone, boolean miles) {
        return build(scooter, phone, miles, false);
    }
    public static List<Series> build(List<RideStore.Point> scooter, PhoneRideStore.Track phone, boolean miles, boolean fullDetail) {
        if (fullDetail) RideStore.checkDetailMemory();
        List<Series> result = new ArrayList<>();
        List<PhoneRideStore.Point> phonePoints = phone == null ? Collections.emptyList() : fullDetail ? phone.fullPoints() : phone.points;
        ClockIndex clocks = new ClockIndex(scooter); List<Long> alignedTimes = new ArrayList<>();
        for (PhoneRideStore.Point p : phonePoints) { checkCanceled(); if (fullDetail) RideStore.checkDetailMemory(); alignedTimes.add(clocks.align(p)); }
        for (int index = 0; index < KEYS.length; index++) {
            List<Value> values = new ArrayList<>();
            if (index == 2 || index == 5 || index == 6) {
                for (int position = 0; position < phonePoints.size(); position++) {
                    checkCanceled(); PhoneRideStore.Point p = phonePoints.get(position);
                    if (fullDetail) RideStore.checkDetailMemory();
                    Double v = index == 2 ? p.speedMps : index == 5 ? p.relativeAltitudeM : p.gpsAccelerationMps2;
                    if (v != null) v *= index == 2 ? (miles ? 3.6 / RideStore.KM_PER_MILE : 3.6) : index == 5 && miles ? 3.280839895 : 1;
                    // Phone capture begins asynchronously. Align source monotonic clocks to the
                    // scooter timeline, rather than pretending both recorders started at once.
                    Long aligned = alignedTimes.get(position);
                    if (aligned != null) values.add(new Value(aligned, v, p.segment));
                }
            } else for (RideStore.Point p : scooter) {
                checkCanceled();
                if (fullDetail) RideStore.checkDetailMemory();
                Double v = index == 0 ? p.powerW : index == 1 ? p.speedKmh : index == 3 ? p.voltageV : index == 4 ? p.batteryPercent : energyPerDistance(p.powerW, p.speedKmh, miles);
                if (index == 1 && v != null && miles) v /= RideStore.KM_PER_MILE;
                values.add(new Value(p.elapsedMs, v, p.segment));
            }
            String unit = index == 0 ? "W" : index == 1 || index == 2 ? miles ? "mph" : "km/h" : index == 3 ? "V" : index == 4 ? "%" : index == 5 ? miles ? "ft" : "m" : index == 6 ? "m/s²" : miles ? "Wh/mi" : "Wh/km";
            result.add(new Series(index, unit, values, fullDetail));
        }
        return Collections.unmodifiableList(result);
    }
    private static void checkCanceled() { if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Ride chart loading canceled"); }
}
