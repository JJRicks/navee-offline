package local.navee.offlinelock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded private charge journals and conservative, charger-specific SOC curves. No Android API. */
public final class ChargeSessionStore {
    public static final long SAMPLE_INTERVAL_MS = 10000, MAX_FRAME_AGE_MS = 5000, MAX_GAP_MS = 25000;
    public static final long MAX_SESSION_MS = 18 * 60 * 60 * 1000L, MAX_FILE_BYTES = 1_000_000;
    public static final int MAX_SESSIONS = 20, MAX_SAMPLES = 6500;
    private static final int MIN_BINS = 10;
    public enum Charger { STOCK, HIGH_SPEED }
    public enum Stop { STOPPED, COMPLETE, CHARGING_ENDED, DISCONNECTED, PROFILE_CHANGED, READINGS_STOPPED, LIMIT, ERROR, PROCESS_ENDED, INTERRUPTED, RECOVERY_TIMEOUT, CONNECTION_FAILED, BLUETOOTH_OFF, PERMISSION_LOST, IDENTITY_CHANGED }
    public static final class Value {
        public final Double value; public final long mono; public final int source;
        public Value(Double value, long mono, int source) { this.value = value; this.mono = mono; this.source = source; }
    }
    public static final class Frame {
        public final long wall, mono; public final Value state, soc, voltage, current, temperature;
        public Frame(long wall, long mono, Value state, Value soc, Value voltage, Value current, Value temperature) {
            this.wall=wall;this.mono=mono;this.state=state;this.soc=soc;this.voltage=voltage;this.current=current;this.temperature=temperature;
        }
        public boolean usable() {
            return fresh(state,mono) && state.value==1 && measurementsUsable();
        }
        public boolean usableCompletion() {
            return fresh(state,mono) && (state.value==0 || state.value==1) && measurementsUsable() && soc.value==100;
        }
        public boolean usableChargingEnded() {
            return fresh(state,mono) && state.value==0 && measurementsUsable();
        }
        private boolean measurementsUsable() {
            return wall > 0 && mono > 0 && fresh(soc,mono) && between(soc.value,0,100)
                    && fresh(voltage,mono) && between(voltage.value,1,100) && fresh(current,mono) && Math.abs(current.value)<=30
                    && voltage.mono==current.mono && voltage.source==current.source && voltage.value*Math.abs(current.value)<=3000;
        }
    }
    public static final class Duration {
        public final double minutes; public final int sessions;
        Duration(double minutes,int sessions){this.minutes=minutes;this.sessions=sessions;}
    }
    public static final class Curve {
        public enum PowerMatch { MATCH, DIFFERENT, UNKNOWN }
        public final String profile; public final Charger charger; public final int qualifiedSessions;
        private final double[] minutes, watts; private final int[] counts;
        private final List<FinishingCurve> finishing = new ArrayList<>();
        Curve(String profile, Charger charger, List<Evidence> sessions) {
            this.profile=profile;this.charger=charger;qualifiedSessions=sessions.size();minutes=new double[100];watts=new double[100];counts=new int[100];
            for (Evidence session : sessions) if (session.finishing != null) finishing.add(session.finishing);
            Arrays.fill(minutes,Double.NaN);Arrays.fill(watts,Double.NaN);
            for(int bin=0;bin<100;bin++) {
                List<Double> values=new ArrayList<>();for(Evidence session:sessions)if(Double.isFinite(session.minutes[bin]))values.add(session.minutes[bin]);
                Collections.sort(values);if(values.size()<2)continue;
                double median=median(values);
                // With just two strongly disagreeing sessions there is no reliable majority.
                if(values.size()==2&&values.get(1)>values.get(0)*2.5)continue;
                double sum=0;List<Double> powers=new ArrayList<>();
                for(Evidence session:sessions)if(session.minutes[bin]>=median*.4&&session.minutes[bin]<=median*2.5){
                    sum+=session.minutes[bin];counts[bin]++;
                    if(Double.isFinite(session.watts[bin])&&session.watts[bin]>=1)powers.add(session.watts[bin]);
                }
                if(counts[bin]>=2)minutes[bin]=sum/counts[bin];
                // Use the same duration-qualified sessions, at this SOC, not a bulk-power guess
                // during taper. Two disagreeing records cannot identify compatible live power.
                if(powers.size()>=2){
                    double center=median(powers);List<Double> compatible=new ArrayList<>();
                    for(double power:powers)if(power>=center*.75&&power<=center*1.25)compatible.add(power);
                    if(compatible.size()>=2)watts[bin]=median(compatible);
                }
            }
        }
        /** A plausibility check, not charger identification; missing local power fails closed. */
        public PowerMatch powerMatch(double soc,Double liveWatts) {
            if(!between(soc,0,100)||liveWatts==null||!Double.isFinite(liveWatts)||liveWatts<1)return PowerMatch.UNKNOWN;
            int bin=Math.min(99,(int)Math.floor(soc));double expected=watts[bin];
            if(!Double.isFinite(expected))return PowerMatch.UNKNOWN;
            return liveWatts>=expected*.65&&liveWatts<=expected*1.35?PowerMatch.MATCH:PowerMatch.DIFFERENT;
        }
        /** Every remaining interval, including the final taper bin, needs two independent sessions. */
        public Duration estimate(double soc,double target) {
            if(!between(soc,0,100)||!between(target,0,100)||qualifiedSessions<2)return null;
            if(soc>=target)return new Duration(0,qualifiedSessions);
            double total=0;int supported=Integer.MAX_VALUE;
            for(int bin=(int)Math.floor(soc);bin<Math.ceil(target);bin++) {
                if(bin<0||bin>=100||!Double.isFinite(minutes[bin])||counts[bin]<2)return null;
                double fraction=Math.min(target,bin+1)-Math.max(soc,bin);
                if(fraction>0){total+=fraction*minutes[bin];supported=Math.min(supported,counts[bin]);}
            }
            return Double.isFinite(total)?new Duration(total,supported):null;
        }
        /** Complete observed finishes can provide a provisional estimate after just one charge. */
        public Duration estimateFinishing(double soc, Double liveWatts) {
            if (!between(soc, 0, 100) || liveWatts == null || !Double.isFinite(liveWatts) || liveWatts < 1) return null;
            List<Double> estimates = new ArrayList<>();
            for (FinishingCurve curve : finishing) {
                Double value = curve.estimate(soc, liveWatts);
                if (value != null) estimates.add(value);
            }
            if (estimates.isEmpty()) return null;
            Collections.sort(estimates);
            if (estimates.size() == 2 && estimates.get(1) > estimates.get(0) * 2.5) return null;
            double center = median(estimates), sum = 0; int count = 0;
            for (double value : estimates) if (value >= center * .4 && value <= center * 2.5) { sum += value; count++; }
            return count == 0 ? null : new Duration(sum / count, count);
        }
        public boolean hasFinishingCoverage(double soc) {
            for (FinishingCurve curve : finishing) if (soc >= curve.points.get(0).soc && soc < 100) return true;
            return false;
        }
    }
    public static final class Snapshot {
        public final Map<String,Curve> curves; public final int sessions; public final long bytes;
        Snapshot(Map<String,Curve> curves,int sessions,long bytes){this.curves=Collections.unmodifiableMap(curves);this.sessions=sessions;this.bytes=bytes;}
    }
    private final File directory;
    // snapshot() normally primes this on the analysis worker before capture is admitted,
    // avoiding reparsing the full retained history in start()'s bounded pruning step.
    private final Map<String,Retention> retentionCache=new HashMap<>();
    private File active;
    private String profile;
    private Charger charger;
    private long startMono,lastWriteMono,lastElectricalMono,lastFreshMono;
    private Double lastSoc,lastPower,lastVoltage;
    private int samples;
    private boolean gap;

    public ChargeSessionStore(File directory) throws IOException {
        this.directory=directory;
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Charge storage unavailable");
        // A process restart never silently resumes a capture. Interrupted journals remain inspectable,
        // but do not enter learned curves because their stop/continuity was not observed.
        for(File file:files())if(file.getName().endsWith(".active")){
            append(file,"end,INTERRUPTED\n");File recovered=new File(directory,file.getName().replace(".active",".csv"));
            if(!file.renameTo(recovered))throw new IOException("Could not finalize interrupted charge");
        }
        prune();
    }
    public synchronized boolean active(){return active!=null;}
    public synchronized String profile(){return profile;}
    public synchronized Charger charger(){return charger;}
    public synchronized int sampleCount(){return samples;}
    public synchronized long lastFreshMono(){return lastFreshMono;}
    public synchronized long startedMono(){return startMono;}
    public synchronized void start(String profile,Charger charger,long wall,long mono) throws IOException {
        if(active!=null)throw new IllegalStateException("A charge capture is already active");
        if(profile==null||!profile.matches("[A-Za-z0-9_-]{1,128}")||charger==null||wall<=0||mono<=0)throw new IllegalArgumentException("Choose a scooter and charger");
        pruneTo(MAX_SESSIONS-1);
        File file=new File(directory,UUID.randomUUID().toString()+".active");
        append(file,"charge-v1,"+profile+","+charger.name()+","+wall+","+mono+"\n");
        this.active=file;this.profile=profile;this.charger=charger;startMono=mono;lastFreshMono=mono;
        lastWriteMono=lastElectricalMono=0;lastSoc=lastPower=lastVoltage=null;samples=0;gap=true;
    }
    /** Returns true only for a newly committed, fresh paired sample. Rejected samples break continuity. */
    public synchronized boolean sample(Frame frame) throws IOException {
        return sample(frame,false);
    }
    /** A final fresh 100% packet may arrive after charging flips off. It never starts a capture. */
    public synchronized boolean sampleCompletion(Frame frame) throws IOException {
        return samples>0 && sample(frame,true);
    }
    private boolean sample(Frame frame,boolean completion) throws IOException {
        if(active==null)return false;
        if(frame==null||!(completion?frame.usableCompletion():frame.usable())){gap=true;return false;}
        if(frame.mono<startMono||frame.mono-startMono>MAX_SESSION_MS||samples>=MAX_SAMPLES||active.length()>=MAX_FILE_BYTES){stop(Stop.LIMIT);return false;}
        if(frame.voltage.mono<=lastElectricalMono)return false;
        lastFreshMono=frame.mono;
        if(lastWriteMono>0&&frame.mono-lastWriteMono<SAMPLE_INTERVAL_MS&&frame.soc.value<100)return false;
        double power=frame.voltage.value*frame.current.value;
        boolean discontinuity=lastWriteMono>0&&(frame.mono-lastWriteMono>MAX_GAP_MS||frame.mono<=lastWriteMono);
        boolean spike=lastPower!=null&&Math.max(Math.abs(power),Math.abs(lastPower))>3*Math.min(Math.abs(power),Math.abs(lastPower))
                &&Math.abs(power-lastPower)>50;
        // Current falling to zero at the cutoff is expected, unlike an upward electrical spike.
        if(completion&&lastPower!=null&&Math.abs(power)<=Math.abs(lastPower))spike=false;
        spike|=lastVoltage!=null&&Math.abs(frame.voltage.value-lastVoltage)>3;
        if(spike){gap=true;return false;}
        if(lastSoc!=null&&(frame.soc.value<lastSoc||frame.soc.value-lastSoc>1))gap=true;
        Double temperature=fresh(frame.temperature,frame.mono)&&between(frame.temperature.value,-40,100)?frame.temperature.value:null;
        String line="s,"+frame.wall+","+frame.mono+","+frame.soc.value+","+frame.voltage.value+","+frame.current.value+","+power+","+(temperature==null?"":temperature)+","+(gap||discontinuity?1:0)+"\n";
        append(active,line);samples++;lastWriteMono=frame.mono;lastElectricalMono=frame.voltage.mono;
        lastSoc=frame.soc.value;lastPower=power;lastVoltage=frame.voltage.value;gap=false;
        return true;
    }
    /** A new observed segment must not be compared with electrical values from before a loss. */
    public synchronized void markGap(){gap=true;lastPower=lastVoltage=null;}
    public synchronized void stop(Stop reason) throws IOException {
        if(active==null)return;
        File file=active;active=null;profile=null;charger=null;
        append(file,"end,"+reason.name()+"\n");
        if(!file.renameTo(new File(directory,file.getName().replace(".active",".csv"))))throw new IOException("Charge could not be finalized");
        prune();
    }
    public synchronized void clear(String targetProfile) throws IOException {
        if(active!=null&&(targetProfile==null||targetProfile.equals(profile)))throw new IllegalStateException("Stop charge recording first");
        for(File file:files()) {
            if(file.equals(active))continue;
            Session session=read(file);
            if(targetProfile==null||session!=null&&targetProfile.equals(session.profile)){
                if(!file.delete())throw new IOException("Charge history could not be cleared");retentionCache.remove(file.getName());
            }
        }
    }
    /** Run outside the UI thread. Only closed, complete files are considered for learning. */
    public Snapshot snapshot() throws IOException {
        final List<File> source;final File current;
        synchronized(this){source=files();current=active;}
        Map<String,List<Evidence>> eligible=new LinkedHashMap<>();Map<String,Session> subjects=new LinkedHashMap<>();int sessions=0;long bytes=0;
        for(File file:source) {
            bytes+=file.length();if(file.equals(current)||!file.getName().endsWith(".csv"))continue;
            Session session=read(file);Evidence bins=session==null?null:session.evidence();rememberRetention(file,session,bins);
            if(session==null)continue;sessions++;
            if(bins==null)continue;
            String key=key(session.profile,session.charger);eligible.computeIfAbsent(key,ignored->new ArrayList<>()).add(bins);subjects.put(key,session);
        }
        Map<String,Curve> curves=new LinkedHashMap<>();for(Map.Entry<String,List<Evidence>> item:eligible.entrySet()){
            Session session=subjects.get(item.getKey());curves.put(item.getKey(),new Curve(session.profile,session.charger,item.getValue()));
        }
        return new Snapshot(curves,sessions,bytes);
    }
    /** Stream saved journals for one scooter. No owner credentials, names, or radio identifiers. */
    public synchronized int exportJson(String targetProfile, Writer out) throws IOException {
        if(targetProfile==null||!targetProfile.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Select a scooter");
        if(active!=null)throw new IllegalStateException("Stop charge recording before exporting");
        out.write("{\"format\":\"navee-charge-recordings-v1\",\"charger_selection\":\"manual\",\"sessions\":[");
        int count=0;
        for(File file:files()) {
            if(!file.getName().endsWith(".csv"))continue;
            Session session=read(file);
            if(session==null||!targetProfile.equals(session.profile))continue;
            if(count++>0)out.write(",");
            out.write("{\"id\":\""+file.getName().replace(".csv","")+"\",\"profile_id\":\""+session.profile
                    +"\",\"charger\":\""+session.charger.name()+"\",\"started_wall_ms\":"+session.startedWall
                    +",\"started_elapsed_ms\":"+session.startedMono+",\"stop_reason\":"+(session.stop==null?"null":"\""+session.stop.name()+"\"")
                    +",\"qualified_for_learning\":"+(session.evidence()!=null)+",\"samples\":[");
            boolean first=true;
            for(Point point:session.points) {
                if(!first)out.write(",");first=false;
                out.write("{\"wall_ms\":"+point.wall+",\"elapsed_ms\":"+point.mono+",\"battery_percent\":"+point.soc
                        +",\"voltage_v\":"+point.voltage+",\"current_a\":"+point.current+",\"power_w\":"+point.signedPower
                        +",\"temperature_c\":"+point.temperature+",\"gap_before\":"+point.gap+"}");
            }
            out.write("]}");
        }
        out.write("]}");out.flush();return count;
    }
    public static String key(String profile,Charger charger){return profile+":"+charger.name();}
    public static boolean fresh(Value value,long now){return value!=null&&value.value!=null&&Double.isFinite(value.value)&&value.source>=0&&value.mono>0&&now>=value.mono&&now-value.mono<=MAX_FRAME_AGE_MS;}
    private static boolean between(double value,double low,double high){return Double.isFinite(value)&&value>=low&&value<=high;}
    private static void append(File file,String text)throws IOException{
        try(FileOutputStream out=new FileOutputStream(file,true)){out.write(text.getBytes(StandardCharsets.UTF_8));out.flush();}
    }
    private List<File> files() throws IOException {
        File[] items=directory.listFiles((dir,name)->name.matches("[a-f0-9-]{36}\\.(csv|active)"));
        if(items==null)throw new IOException("Charge recordings unavailable");
        List<File> result=new ArrayList<>(Arrays.asList(items));result.sort(Comparator.comparingLong(File::lastModified).thenComparing(File::getName));return result;
    }
    private void prune()throws IOException{pruneTo(MAX_SESSIONS);}
    private void pruneTo(int limit)throws IOException{
        List<File> entries=files();int remove=entries.size()-limit;if(remove<=0)return;
        Map<String,List<File>> qualified=new LinkedHashMap<>();List<File> rejected=new ArrayList<>();
        for(File file:entries){
            if(file.equals(active))continue;
            String bucket=retention(file).bucket;
            if(bucket==null)rejected.add(file);
            else qualified.computeIfAbsent(bucket,ignored->new ArrayList<>()).add(file);
        }
        // Failed/short captures must not evict the only usable curve for another charger.
        for(File file:rejected)if(remove>0){remove(file);remove--;}
        // Keep buckets balanced, preferring the oldest file in the most populated bucket.
        // Two per bucket are therefore protected while the global budget can accommodate them.
        while(remove>0){
            List<File> largest=null;
            for(List<File> bucket:qualified.values())if(!bucket.isEmpty()&&(largest==null||bucket.size()>largest.size()
                    ||bucket.size()==largest.size()&&bucket.get(0).lastModified()<largest.get(0).lastModified()))largest=bucket;
            if(largest==null)break;
            remove(largest.remove(0));remove--;
        }
    }
    private void remove(File file)throws IOException{
        if(!file.delete())throw new IOException("Old charge recordings could not be removed");retentionCache.remove(file.getName());
    }
    private static final class Retention {
        final long length,modified;final String bucket;
        Retention(File file,String bucket){length=file.length();modified=file.lastModified();this.bucket=bucket;}
    }
    private synchronized Retention rememberRetention(File file,Session session,Evidence evidence){
        Retention info=new Retention(file,evidence==null?null:key(session.profile,session.charger));
        retentionCache.put(file.getName(),info);return info;
    }
    private synchronized Retention retention(File file)throws IOException{
        Retention cached=retentionCache.get(file.getName());
        if(cached!=null&&cached.length==file.length()&&cached.modified==file.lastModified())return cached;
        Session session=file.getName().endsWith(".csv")?read(file):null;
        return rememberRetention(file,session,session==null?null:session.evidence());
    }
    private static double median(List<Double> values){
        Collections.sort(values);int middle=values.size()/2;
        return values.size()%2==1?values.get(middle):(values.get(middle-1)+values.get(middle))/2;
    }
    private static final class Point {
        final long wall,mono;final double soc,power,voltage,current,signedPower;final Double temperature;final boolean gap;
        Point(long wall,long mono,double soc,double voltage,double current,double signedPower,Double temperature,boolean gap){
            this.wall=wall;this.mono=mono;this.soc=soc;this.voltage=voltage;this.current=current;this.signedPower=signedPower;
            this.power=Math.abs(signedPower);this.temperature=temperature;this.gap=gap;
        }
    }
    private static final class Evidence {
        final double[] minutes=new double[100],watts=new double[100];
        FinishingCurve finishing;
        Evidence(){Arrays.fill(minutes,Double.NaN);Arrays.fill(watts,Double.NaN);}
    }
    /**
     * A complete terminal trajectory, not an inferred battery capacity. Retain real short SOC
     * transitions: a gauge can rush through several percentages then spend much longer at 99%.
     * Only the observed range is usable; no bulk-power or below-range extrapolation is made.
     */
    private static final class FinishingCurve {
        final List<Point> points;
        FinishingCurve(List<Point> points) { this.points = new ArrayList<>(points); }

        static FinishingCurve from(Session session) {
            List<Point> points = session.points;
            if (session.stop != Stop.COMPLETE || points.size() < 61 || points.get(points.size()-1).soc != 100) return null;
            int start = -1;
            for (int i = 1; i < points.size(); i++) {
                Point before = points.get(i-1), point = points.get(i);
                if (start < 0 && point.soc > before.soc) start = i;
                if (start < 0) continue;
                if (point.gap || point.mono <= before.mono || point.mono - before.mono > MAX_GAP_MS
                        || Math.abs((point.wall-before.wall)-(point.mono-before.mono)) > 2000
                        || point.soc < before.soc || point.soc-before.soc > 1
                        || point.soc != Math.floor(point.soc) || before.soc >= 100
                        || (point.soc < 100 && point.power < 1)) return null;
            }
            if (start < 0 || 100-points.get(start).soc < 10
                    || points.get(points.size()-1).mono-points.get(start).mono < 600000) return null;
            return new FinishingCurve(points.subList(start, points.size()));
        }

        Double estimate(double soc, double liveWatts) {
            if (soc != Math.floor(soc) || soc < points.get(0).soc || soc >= 100) return null;
            List<Point> stage = new ArrayList<>(); Point next = null;
            for (Point point : points) {
                if (point.soc == soc) stage.add(point);
                else if (!stage.isEmpty()) { next = point; break; }
            }
            if (stage.size() < 2 || next == null) return null;
            double low = Double.POSITIVE_INFINITY, high = 0, runningLow = Double.POSITIVE_INFINITY;
            for (Point point : stage) {
                // A substantial rebound makes power an ambiguous indicator of progress.
                if (point.power > runningLow + Math.max(5, runningLow*.1)) return null;
                runningLow = Math.min(runningLow, point.power);
                low = Math.min(low, point.power); high = Math.max(high, point.power);
            }
            // A plausibility gate only; the charger choice remains manual. Never substitute its
            // nominal wattage for a live reading, or map a different charger into this finish.
            if (liveWatts < low*.85 || liveWatts > high*1.15) return null;
            double at;
            if (low > high*.8) {
                // Nearly flat power cannot locate us within this SOC plateau. Use its midpoint
                // only for short intervals, without presenting a fabricated exact countdown.
                if (next.mono-stage.get(0).mono > 600000) return null;
                at = (stage.get(0).mono + next.mono) / 2d;
            } else {
                double closest = Double.POSITIVE_INFINITY;
                for (Point point : stage) closest = Math.min(closest, Math.abs(point.power-liveWatts));
                double tolerance = Math.max(1.5, liveWatts*.02), sum = 0;
                int count = 0; long first = 0, last = 0;
                for (Point point : stage) if (Math.abs(point.power-liveWatts) <= closest+tolerance) {
                    if (count == 0) first = point.mono;
                    last = point.mono; sum += point.mono; count++;
                }
                if (count == 0 || last-first > 600000) return null;
                at = sum/count;
            }
            return Math.max(0, (points.get(points.size()-1).mono-at)/60000d);
        }
    }
    private static final class Session {
        String profile;Charger charger;Stop stop;long startedWall,startedMono;List<Point> points=new ArrayList<>();
        Evidence evidence(){
            if(stop==null||stop==Stop.INTERRUPTED||stop==Stop.PROCESS_ENDED||stop==Stop.ERROR||points.size()<61)return null;
            Evidence result=new Evidence();result.finishing=FinishingCurve.from(this);boolean[] repeated=new boolean[100];List<Double> powerSamples=new ArrayList<>();
            Point previous=null;double anchorSoc=Double.NaN;long anchorTime=0,covered=0;int bins=0;
            for(Point point:points){
                boolean continuous=previous!=null&&!point.gap&&point.mono>previous.mono&&point.mono-previous.mono<=MAX_GAP_MS
                        &&Math.abs((point.wall-previous.wall)-(point.mono-previous.mono))<=2000;
                if(!continuous||previous!=null&&(point.soc<previous.soc||point.soc-previous.soc>1)){anchorSoc=Double.NaN;anchorTime=0;powerSamples.clear();}
                else covered+=point.mono-previous.mono;
                if(continuous&&point.soc>previous.soc){
                    if(Double.isFinite(anchorSoc)&&point.soc-anchorSoc==1&&anchorSoc==Math.floor(anchorSoc)){
                        int bin=(int)anchorSoc;double minutes=(point.mono-anchorTime)/60000d;
                        if(bin>=0&&bin<100&&minutes>=.5&&minutes<=90&&!repeated[bin]){
                            if(Double.isFinite(result.minutes[bin])){result.minutes[bin]=Double.NaN;result.watts[bin]=Double.NaN;repeated[bin]=true;bins--;}
                            else{result.minutes[bin]=minutes;if(powerSamples.size()>=3)result.watts[bin]=median(powerSamples);bins++;}
                        }
                    }
                    anchorSoc=point.soc;anchorTime=point.mono;powerSamples.clear();
                }
                // The next SOC's first sample (including a zero-current cutoff packet) belongs
                // to the next interval, so it cannot dilute power from the completed interval.
                if(point.soc==anchorSoc)powerSamples.add(point.power);
                previous=point;
            }
            long elapsed=points.get(points.size()-1).mono-points.get(0).mono;
            return result.finishing!=null || bins>=MIN_BINS&&elapsed>=600000&&covered>=elapsed*.9?result:null;
        }
    }
    private static Session read(File file)throws IOException{
        if(file.length()>MAX_FILE_BYTES+1024)return null;
        Session session=new Session();int lines=0;
        try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){
            String line;while((line=in.readLine())!=null){
                if(++lines>MAX_SAMPLES+3||line.length()>512)return null;
                String[] parts=line.split(",",-1);
                try{
                    if(lines==1){if(parts.length!=5||!parts[0].equals("charge-v1")||!parts[1].matches("[A-Za-z0-9_-]{1,128}"))return null;session.profile=parts[1];session.charger=Charger.valueOf(parts[2]);session.startedWall=Long.parseLong(parts[3]);session.startedMono=Long.parseLong(parts[4]);if(session.startedWall<=0||session.startedMono<=0)return null;}
                    else if(parts.length==2&&parts[0].equals("end")){if(session.stop!=null)return null;session.stop=Stop.valueOf(parts[1]);}
                    else if(parts.length==9&&parts[0].equals("s")&&session.stop==null){
                        long wall=Long.parseLong(parts[1]),mono=Long.parseLong(parts[2]);double soc=Double.parseDouble(parts[3]);
                        double volts=Double.parseDouble(parts[4]),amps=Double.parseDouble(parts[5]),power=Double.parseDouble(parts[6]);
                        if(wall<=0||mono<=0||!between(soc,0,100)||!between(volts,1,100)||!between(amps,-30,30)||!between(power,-3000,3000)
                                ||Math.abs(volts*amps-power)>.001||!(parts[8].equals("0")||parts[8].equals("1")))return null;
                        if(!parts[7].isEmpty()&&!between(Double.parseDouble(parts[7]),-40,100))return null;
                        session.points.add(new Point(wall,mono,soc,volts,amps,power,parts[7].isEmpty()?null:Double.valueOf(parts[7]),parts[8].equals("1")));
                    }else return null;
                }catch(IllegalArgumentException bad){return null;}
            }
        }
        return session.profile==null?null:session;
    }
}
