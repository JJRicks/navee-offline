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
        public final long typicalIntervalMs; private final boolean hasData, ordered;
        Series(int index, String unit, List<Value> values) {
            this.index = index; key = KEYS[index]; label = LABELS[index]; this.unit = unit; color = COLORS[index]; this.values = Collections.unmodifiableList(values);
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (Value p : values) if (p.value != null && Double.isFinite(p.value)) { low = Math.min(low, p.value); high = Math.max(high, p.value); }
            hasData=Double.isFinite(low);boolean sorted=true;List<Long> intervals=new ArrayList<>();
            for(int i=1;i<values.size();i++){long delta=values.get(i).time-values.get(i-1).time;if(delta<0)sorted=false;if(delta>0)intervals.add(delta);}
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
        private int bound(double time,boolean after){int low=0,high=values.size();while(low<high){int middle=(low+high)>>>1;long value=values.get(middle).time;if(value<time||(after&&value==time))low=middle+1;else high=middle;}return low;}
    }
    public static Set<String> defaults() { return new LinkedHashSet<String>(Arrays.asList("power_w", "speed_kmh")); }
    public static Double energyPerDistance(Double powerW, Double speedKmh, boolean miles) {
        // W / (km/h) = Wh/km. Near a stop the ratio is not useful and tends to infinity.
        if (powerW == null || speedKmh == null || !Double.isFinite(powerW) || !Double.isFinite(speedKmh) || speedKmh < 3) return null;
        return powerW / speedKmh * (miles ? RideStore.KM_PER_MILE : 1);
    }
    private static Long alignTime(PhoneRideStore.Point phone, List<RideStore.Point> scooter) {
        if (scooter.isEmpty()) return phone.elapsedMs;
        RideStore.Point nearest = null; long delta = Long.MAX_VALUE;
        for (RideStore.Point p : scooter) {
            long monoDelta = phone.monoMs - p.monotonicMs;
            // Matching wall/source deltas identify the same boot, including recovered recordings.
            if (Math.abs((phone.wallMs - p.wallMs) - monoDelta) > 3000) continue;
            if (Math.abs(monoDelta) < delta) { delta = Math.abs(monoDelta); nearest = p; }
        }
        return nearest == null || delta > 120000 ? null : Math.max(0, nearest.elapsedMs + phone.monoMs - nearest.monotonicMs);
    }
    public static List<Series> build(List<RideStore.Point> scooter, PhoneRideStore.Track phone, boolean miles) {
        List<Series> result = new ArrayList<>();
        for (int index = 0; index < KEYS.length; index++) {
            List<Value> values = new ArrayList<>();
            if (index == 2 || index == 5 || index == 6) {
                if (phone != null) for (PhoneRideStore.Point p : phone.points) {
                    Double v = index == 2 ? p.speedMps : index == 5 ? p.relativeAltitudeM : p.gpsAccelerationMps2;
                    if (v != null) v *= index == 2 ? (miles ? 3.6 / RideStore.KM_PER_MILE : 3.6) : index == 5 && miles ? 3.280839895 : 1;
                    // Phone capture begins asynchronously. Align source monotonic clocks to the
                    // scooter timeline, rather than pretending both recorders started at once.
                    Long aligned = alignTime(p, scooter);
                    if (aligned != null) values.add(new Value(aligned, v, p.segment));
                }
            } else for (RideStore.Point p : scooter) {
                Double v = index == 0 ? p.powerW : index == 1 ? p.speedKmh : index == 3 ? p.voltageV : index == 4 ? p.batteryPercent : energyPerDistance(p.powerW, p.speedKmh, miles);
                if (index == 1 && v != null && miles) v /= RideStore.KM_PER_MILE;
                values.add(new Value(p.elapsedMs, v, p.segment));
            }
            String unit = index == 0 ? "W" : index == 1 || index == 2 ? miles ? "mph" : "km/h" : index == 3 ? "V" : index == 4 ? "%" : index == 5 ? miles ? "ft" : "m" : index == 6 ? "m/s²" : miles ? "Wh/mi" : "Wh/km";
            result.add(new Series(index, unit, values));
        }
        return Collections.unmodifiableList(result);
    }
}
