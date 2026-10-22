package local.navee.offlinelock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded private charge journals and conservative, charger-specific SOC curves. No Android API. */
public final class ChargeSessionStore {
    public static final long SAMPLE_INTERVAL_MS = 10000, MAX_FRAME_AGE_MS = 5000, MAX_GAP_MS = 25000;
    // Estimate metadata keeps the same sample/session limits; even maximum-length rows fit.
    public static final long MAX_SESSION_MS = 18 * 60 * 60 * 1000L, MAX_FILE_BYTES = 3_500_000;
    public static final int MAX_SESSIONS = 20, MAX_SAMPLES = 6500;
    private static final int MIN_BINS = 10;
    public enum Charger { STOCK, HIGH_SPEED }
    public enum Stop { STOPPED, COMPLETE, CHARGING_ENDED, DISCONNECTED, PROFILE_CHANGED, READINGS_STOPPED, LIMIT, ERROR, PROCESS_ENDED, INTERRUPTED, RECOVERY_TIMEOUT, CONNECTION_FAILED, BLUETOOTH_OFF, PERMISSION_LOST, IDENTITY_CHANGED }
    public enum Source { CURRENT_RATE, STOCK_REFERENCE, PERSONAL, FAST_REFERENCE }
    /** A prediction made at capture time, never recalculated when a recording is read. */
    public static final class Estimate {
        public final String version;
        public final Double remainingMinutesToFull, remainingMinutesToLimit, targetPercent;
        public final Source fullSource, limitSource;
        public final int learnedSessions;
        public Estimate(String version, Double full, Double limit, Double target, Source fullSource, Source limitSource, int learnedSessions) {
            if(version==null||!version.matches("[A-Za-z0-9_.-]{1,64}"))throw new IllegalArgumentException("Invalid estimator version");
            this.version=version;this.remainingMinutesToFull=duration(full);this.targetPercent=target!=null&&between(target,0,100)&&target>0?target:null;
            this.remainingMinutesToLimit=this.targetPercent==null?null:duration(limit);
            this.fullSource=this.remainingMinutesToFull==null?null:fullSource;
            this.limitSource=this.remainingMinutesToLimit==null?null:limitSource;
            this.learnedSessions=this.fullSource==Source.PERSONAL||this.limitSource==Source.PERSONAL?Math.max(0,Math.min(MAX_SESSIONS,learnedSessions)):0;
        }
        private static Double duration(Double value){return value!=null&&Double.isFinite(value)&&value>=0?value:null;}
    }
    public static final class HistoryPoint {
        public final long wall,mono;public final double batteryPercent,voltage,current,power;public final Double temperature;
        public final boolean gap;public final Estimate estimate;
        private HistoryPoint(Point point){wall=point.wall;mono=point.mono;batteryPercent=point.soc;voltage=point.voltage;current=point.current;power=point.signedPower;temperature=point.temperature;gap=point.gap;estimate=point.estimate;}
    }
    /** Immutable, profile-scoped history for charts. Completion means observed displayed 100%. */
    public static final class HistorySession {
        public final String id,profile;public final Charger charger;public final Stop stop;
        public final long startedWall,startedMono;public final List<HistoryPoint> points;
        public final Long observedCompleteMono,observedCompleteWall;
        private HistorySession(String id,Session session){
            this.id=id;profile=session.profile;charger=session.charger;stop=session.stop;startedWall=session.startedWall;startedMono=session.startedMono;
            List<HistoryPoint> copy=new ArrayList<>();for(Point point:session.points)copy.add(new HistoryPoint(point));points=Collections.unmodifiableList(copy);
            observedCompleteMono=session.observedCompletion();observedCompleteWall=observedCompleteMono==null?null:clockAt(observedCompleteMono);
        }
        /** One stable wall-clock anchor avoids artificial jumps when the phone clock changes. */
        public long clockAt(long mono){return startedWall+(mono-startedMono);}
    }
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
            return estimateFinishing(soc, liveWatts, null);
        }
        /** Paired voltage disambiguates a low-power bulk plateau from a later taper stage. */
        public Duration estimateFinishing(double soc, Double liveWatts, Double liveVolts) {
            if (!between(soc, 0, 100) || liveWatts == null || !Double.isFinite(liveWatts) || liveWatts < 1) return null;
            if (liveVolts != null && (!Double.isFinite(liveVolts) || liveVolts <= 0)) return null;
            List<Double> estimates = new ArrayList<>();
            for (FinishingCurve curve : finishing) {
                Double value = curve.estimate(soc, liveWatts, liveVolts);
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
        /** Per profile: charge accepted per gauge point in completed charges (any charger). */
        public final Map<String,BatteryCapacityEstimate.ChargeEvidence> gaugeCharge;
        Snapshot(Map<String,Curve> curves,int sessions,long bytes){this(curves,sessions,bytes,Collections.emptyMap());}
        Snapshot(Map<String,Curve> curves,int sessions,long bytes,Map<String,BatteryCapacityEstimate.ChargeEvidence> gaugeCharge){
            this.curves=Collections.unmodifiableMap(curves);this.sessions=sessions;this.bytes=bytes;
            this.gaugeCharge=Collections.unmodifiableMap(new LinkedHashMap<>(gaugeCharge));
        }
    }
    /** Longest sample spacing still integrated as continuous charging (recovery gaps keep charging). */
    static final long GAUGE_CHARGE_MAX_STEP_MS = 30*60000L;
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
    private double candidatePower,candidateVoltage;
    private long candidateMono,candidateElectricalMono;
    private int candidateSamples;

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
        if(profile==null||!profile.matches("[A-Za-z0-9_-]{1,128}")||charger==null||wall<=0||mono<=0||wall>Long.MAX_VALUE-MAX_SESSION_MS||mono>Long.MAX_VALUE-MAX_SESSION_MS)throw new IllegalArgumentException("Choose a scooter and charger");
        pruneTo(MAX_SESSIONS-1);
        File file=new File(directory,UUID.randomUUID().toString()+".active");
        append(file,"charge-v2,"+profile+","+charger.name()+","+wall+","+mono+"\n");
        this.active=file;this.profile=profile;this.charger=charger;startMono=mono;lastFreshMono=mono;
        lastWriteMono=lastElectricalMono=0;lastSoc=lastPower=lastVoltage=null;samples=0;gap=true;
        resetElectricalCandidate();
    }
    /** Returns true only for a newly committed, fresh paired sample. Rejected samples break continuity. */
    public synchronized boolean sample(Frame frame) throws IOException {
        return sample(frame,ChargingEstimate.recordingSnapshot(null));
    }
    public synchronized boolean sample(Frame frame,Estimate estimate) throws IOException {
        return sample(frame,false,estimate);
    }
    /** A final fresh 100% packet may arrive after charging flips off. It never starts a capture. */
    public synchronized boolean sampleCompletion(Frame frame) throws IOException {
        return sampleCompletion(frame,ChargingEstimate.recordingSnapshot(null));
    }
    public synchronized boolean sampleCompletion(Frame frame,Estimate estimate) throws IOException {
        return samples>0 && sample(frame,true,estimate);
    }
    private boolean sample(Frame frame,boolean completion,Estimate estimate) throws IOException {
        if(active==null)return false;
        if(frame==null||!(completion?frame.usableCompletion():frame.usable())){gap=true;resetElectricalCandidate();return false;}
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
        if(spike){gap=true;if(!confirmElectricalChange(frame,power))return false;}
        if(lastSoc!=null&&(frame.soc.value<lastSoc||frame.soc.value-lastSoc>1))gap=true;
        Double temperature=fresh(frame.temperature,frame.mono)&&between(frame.temperature.value,-40,100)?frame.temperature.value:null;
        Estimate saved=estimate==null?ChargingEstimate.recordingSnapshot(null):estimate;
        String line="s,"+frame.wall+","+frame.mono+","+frame.soc.value+","+frame.voltage.value+","+frame.current.value+","+power+","+(temperature==null?"":temperature)+","+(gap||discontinuity?1:0)
                +","+saved.version+","+csv(saved.remainingMinutesToFull)+","+csv(saved.remainingMinutesToLimit)+","+csv(saved.targetPercent)
                +","+csv(saved.fullSource)+","+csv(saved.limitSource)+","+saved.learnedSessions+"\n";
        append(active,line);samples++;lastWriteMono=frame.mono;lastElectricalMono=frame.voltage.mono;
        lastSoc=frame.soc.value;lastPower=power;lastVoltage=frame.voltage.value;gap=false;
        resetElectricalCandidate();
        return true;
    }
    /** An isolated spike stays excluded, but a sustained new charging level must not latch
     * against the old sample forever. Require three compatible, independent measurements
     * at the recording cadence, within 25% (or 25 W) and 1 V of the first candidate.
     * Only the confirming real sample starts the new segment. */
    private boolean confirmElectricalChange(Frame frame,double power){
        if(candidateSamples>0&&(frame.mono<candidateMono||frame.mono-candidateMono>MAX_GAP_MS
                ||frame.voltage.mono<candidateElectricalMono
                ||Math.abs(power-candidatePower)>Math.max(25,Math.abs(candidatePower)*.25)
                ||Math.abs(frame.voltage.value-candidateVoltage)>1))resetElectricalCandidate();
        if(candidateSamples==0){
            candidatePower=power;candidateVoltage=frame.voltage.value;candidateSamples=1;
            candidateMono=frame.mono;candidateElectricalMono=frame.voltage.mono;
            return false;
        }
        if(frame.voltage.mono==candidateElectricalMono||frame.mono-candidateMono<SAMPLE_INTERVAL_MS)return false;
        candidateMono=frame.mono;candidateElectricalMono=frame.voltage.mono;
        return ++candidateSamples>=3;
    }
    private void resetElectricalCandidate(){candidateSamples=0;candidateMono=candidateElectricalMono=0;}
    /** A new observed segment must not be compared with electrical values from before a loss. */
    public synchronized void markGap(){gap=true;lastPower=lastVoltage=null;resetElectricalCandidate();}
    public synchronized void stop(Stop reason) throws IOException {
        if(active==null)return;
        File file=active;active=null;profile=null;charger=null;
        resetElectricalCandidate();
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
        Map<String,double[]> gauge=new LinkedHashMap<>();
        for(File file:source) {
            bytes+=file.length();if(file.equals(current)||!file.getName().endsWith(".csv"))continue;
            Session session=read(file);Evidence bins=session==null?null:session.evidence();rememberRetention(file,session,bins);
            if(session==null)continue;sessions++;
            double[] accepted=gaugeCharge(session);
            if(accepted!=null){double[] sum=gauge.computeIfAbsent(session.profile,ignored->new double[3]);sum[0]+=accepted[0];sum[1]+=accepted[1];sum[2]++;}
            if(bins==null)continue;
            String key=key(session.profile,session.charger);eligible.computeIfAbsent(key,ignored->new ArrayList<>()).add(bins);subjects.put(key,session);
        }
        Map<String,Curve> curves=new LinkedHashMap<>();for(Map.Entry<String,List<Evidence>> item:eligible.entrySet()){
            Session session=subjects.get(item.getKey());curves.put(item.getKey(),new Curve(session.profile,session.charger,item.getValue()));
        }
        Map<String,BatteryCapacityEstimate.ChargeEvidence> charge=new LinkedHashMap<>();
        for(Map.Entry<String,double[]> item:gauge.entrySet())
            charge.put(item.getKey(),new BatteryCapacityEstimate.ChargeEvidence(item.getValue()[0],item.getValue()[1],(int)item.getValue()[2]));
        return new Snapshot(curves,sessions,bytes,charge);
    }
    /**
     * {Ah, gauge points} from a completed charge's first percentage change to its first displayed
     * 100%, including the gauge's late catch-up; null unless it starts below 80% and stays continuous.
     */
    static double[] gaugeCharge(Session session){
        if(session.stop!=Stop.COMPLETE||session.points.size()<2)return null;
        List<Point> points=session.points;int start=-1,full=-1;
        for(int i=1;i<points.size();i++){
            if(start<0&&points.get(i).soc>points.get(i-1).soc)start=i;
            if(start>=0&&points.get(i).soc>=100){full=i;break;}
        }
        if(start<0||full<0||points.get(start).soc>=80)return null;
        double ah=0;
        for(int i=start+1;i<=full;i++){
            Point a=points.get(i-1),b=points.get(i);long step=b.mono-a.mono;
            if(step<=0||step>GAUGE_CHARGE_MAX_STEP_MS||!Double.isFinite(a.current)||!Double.isFinite(b.current))return null;
            ah+=step/3600000d*(Math.abs(a.current)+Math.abs(b.current))/2;
        }
        double gaugePoints=100-points.get(start).soc;
        return ah>0&&gaugePoints>0?new double[]{ah,gaugePoints}:null;
    }
    /** Call on an analysis worker. Active files are excluded, so each returned session is stable. */
    public List<HistorySession> recordings(String targetProfile)throws IOException {
        if(targetProfile==null||!targetProfile.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Select a scooter");
        final List<File> source;synchronized(this){source=files();}
        List<HistorySession> result=new ArrayList<>();
        for(File file:source){
            if(!file.getName().endsWith(".csv"))continue;
            Session session;
            try{session=read(file);}catch(FileNotFoundException removed){if(!file.exists())continue;throw removed;}
            if(session!=null&&targetProfile.equals(session.profile))result.add(new HistorySession(file.getName().replace(".csv",""),session));
        }
        Collections.reverse(result);return Collections.unmodifiableList(result);
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
                    +",\"qualified_for_learning\":"+(session.evidence()!=null)+",\"observed_complete_elapsed_ms\":"+session.observedCompletion()+",\"samples\":[");
            boolean first=true;
            for(Point point:session.points) {
                if(!first)out.write(",");first=false;
                out.write("{\"wall_ms\":"+point.wall+",\"elapsed_ms\":"+point.mono+",\"battery_percent\":"+point.soc
                        +",\"voltage_v\":"+point.voltage+",\"current_a\":"+point.current+",\"power_w\":"+point.signedPower
                        +",\"temperature_c\":"+point.temperature+",\"gap_before\":"+point.gap+",\"estimate\":");
                writeEstimateJson(out,point.estimate);out.write("}");
            }
            out.write("]}");
        }
        out.write("]}");out.flush();return count;
    }
    public static String key(String profile,Charger charger){return profile+":"+charger.name();}
    private static String csv(Object value){return value==null?"":value.toString();}
    private static void writeEstimateJson(Writer out,Estimate value)throws IOException{
        if(value==null){out.write("null");return;}
        out.write("{\"estimator_version\":\""+value.version+"\",\"remaining_minutes_to_full\":"+value.remainingMinutesToFull
                +",\"remaining_minutes_to_limit\":"+value.remainingMinutesToLimit+",\"target_percent\":"+value.targetPercent
                +",\"full_source\":"+(value.fullSource==null?"null":"\""+value.fullSource.name()+"\"")
                +",\"limit_source\":"+(value.limitSource==null?"null":"\""+value.limitSource.name()+"\"")+",\"learned_sessions\":"+value.learnedSessions+"}");
    }
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
        final Estimate estimate;
        Point(long wall,long mono,double soc,double voltage,double current,double signedPower,Double temperature,boolean gap,Estimate estimate){
            this.wall=wall;this.mono=mono;this.soc=soc;this.voltage=voltage;this.current=current;this.signedPower=signedPower;
            this.power=Math.abs(signedPower);this.temperature=temperature;this.gap=gap;this.estimate=estimate;
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
        final boolean recoveredAfterBreak;
        FinishingCurve(List<Point> points, boolean recoveredAfterBreak) {
            this.points = new ArrayList<>(points); this.recoveredAfterBreak = recoveredAfterBreak;
        }

        static FinishingCurve from(Session session) {
            List<Point> points = session.points;
            if (session.stop != Stop.COMPLETE || points.size() < 61 || points.get(points.size()-1).soc != 100) return null;
            int start = -1;
            boolean recoveredAfterBreak = false;
            for (int i = 1; i < points.size(); i++) {
                Point before = points.get(i-1), point = points.get(i);
                if (point.gap || point.mono <= before.mono || point.mono - before.mono > MAX_GAP_MS
                        || Math.abs((point.wall-before.wall)-(point.mono-before.mono)) > 2000
                        || point.soc < before.soc || point.soc-before.soc > 1
                        || point.soc != Math.floor(point.soc) || before.soc != Math.floor(before.soc)
                        || before.soc >= 100 || before.power < 1
                        || (point.soc < 100 && point.power < 1)) {
                    start = -1;
                    recoveredAfterBreak = true;
                    continue;
                }
                // A break invalidates only the prefix. Wait for a later observed rise so the
                // first partially recorded SOC bin after a reconnect is never included.
                if (start < 0 && point.soc > before.soc) start = i;
            }
            if (start < 0 || points.size()-start < 61 || 100-points.get(start).soc < 10
                    || points.get(points.size()-1).mono-points.get(start).mono < 600000) return null;
            return new FinishingCurve(points.subList(start, points.size()), recoveredAfterBreak);
        }

        Double estimate(double soc, double liveWatts, Double liveVolts) {
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
            } else if (liveVolts != null) {
                double[][] samples = new double[stage.size()][3];
                long finish = points.get(points.size()-1).mono;
                for (int i = 0; i < stage.size(); i++) {
                    Point point = stage.get(i);
                    samples[i] = new double[] {point.power, point.voltage, (finish-point.mono)/60000d};
                }
                return ChargeTaperMatcher.estimate(liveWatts, liveVolts, samples);
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
        String profile;Charger charger;Stop stop;long startedWall,startedMono;int format;List<Point> points=new ArrayList<>();
        Long observedCompletion(){
            if(stop!=Stop.COMPLETE||points.size()<2)return null;
            long previous=startedMono-1;
            for(Point point:points){if(point.mono<startedMono||point.mono<=previous||point.mono-startedMono>MAX_SESSION_MS)return null;previous=point.mono;}
            Point end=points.get(points.size()-1),before=points.get(points.size()-2);
            // A reconnect already at 100% tells us it finished during the gap, not when.
            return end.soc==100&&before.soc<100&&!end.gap&&end.mono-before.mono<=MAX_GAP_MS?end.mono:null;
        }
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
            boolean binsQualified=bins>=MIN_BINS&&elapsed>=600000&&covered>=elapsed*.9
                    || result.finishing!=null&&!result.finishing.recoveredAfterBreak;
            if(!binsQualified){
                // A valid finishing suffix does not qualify the rest of a sparse recording
                // for the independent per-SOC model, including bins before a long gap.
                Arrays.fill(result.minutes,Double.NaN);Arrays.fill(result.watts,Double.NaN);
            }
            return result.finishing!=null || binsQualified?result:null;
        }
    }
    /** Header facts of a saved journal, read without recovery or any write to its folder. */
    static final class Journal {
        final String profile;final long startedWall,startedMono;final boolean finished;
        Journal(String profile,long wall,long mono,boolean finished){this.profile=profile;startedWall=wall;startedMono=mono;this.finished=finished;}
    }
    static Journal inspectJournal(File file)throws IOException{
        if(!file.isFile()||java.nio.file.Files.isSymbolicLink(file.toPath()))throw new IOException("Unexpected charge file.");
        Session session=read(file);if(session==null)throw new IOException("The backup contains an unreadable charge.");
        return new Journal(session.profile,session.startedWall,session.startedMono,session.stop!=null);
    }
    /** Private restore staging only: the header names the destination scooter, samples are unchanged. */
    static void reassignJournal(File source,String profile)throws IOException{
        if(profile==null||!profile.matches("[A-Za-z0-9_-]{1,128}"))throw new IOException("Choose a destination scooter.");
        inspectJournal(source);byte[] bytes=java.nio.file.Files.readAllBytes(source.toPath());int end=0;
        while(end<bytes.length&&end<=512&&bytes[end]!='\n')end++;
        if(end>=bytes.length||end>512)throw new IOException("The backup contains an unreadable charge.");
        String[] header=new String(bytes,0,end,StandardCharsets.UTF_8).split(",",-1);
        if(header.length!=5)throw new IOException("The backup contains an unreadable charge.");
        header[1]=profile;File temporary=new File(source.getPath()+".mapping");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(String.join(",",header).getBytes(StandardCharsets.UTF_8));out.write(bytes,end,bytes.length-end);out.getFD().sync();}
        try{temporary.setLastModified(source.lastModified());if(!profile.equals(inspectJournal(temporary).profile)||!temporary.renameTo(source))throw new IOException("Could not associate the restored charge.");}
        catch(IOException failure){temporary.delete();throw failure;}
    }
    private static Session read(File file)throws IOException{
        if(file.length()>MAX_FILE_BYTES+1024)return null;
        Session session=new Session();int lines=0;
        try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){
            String line;while((line=in.readLine())!=null){
                if(++lines>MAX_SAMPLES+3||line.length()>512)return null;
                String[] parts=line.split(",",-1);
                try{
                    if(lines==1){if(parts.length!=5||!(parts[0].equals("charge-v1")||parts[0].equals("charge-v2"))||!parts[1].matches("[A-Za-z0-9_-]{1,128}"))return null;session.format=parts[0].equals("charge-v1")?1:2;session.profile=parts[1];session.charger=Charger.valueOf(parts[2]);session.startedWall=Long.parseLong(parts[3]);session.startedMono=Long.parseLong(parts[4]);if(session.startedWall<=0||session.startedMono<=0||session.startedWall>Long.MAX_VALUE-MAX_SESSION_MS||session.startedMono>Long.MAX_VALUE-MAX_SESSION_MS)return null;}
                    else if(parts.length==2&&parts[0].equals("end")){if(session.stop!=null)return null;session.stop=Stop.valueOf(parts[1]);}
                    else if(parts.length==(session.format==1?9:16)&&parts[0].equals("s")&&session.stop==null){
                        if(session.points.size()>=MAX_SAMPLES)return null;
                        long wall=Long.parseLong(parts[1]),mono=Long.parseLong(parts[2]);double soc=Double.parseDouble(parts[3]);
                        double volts=Double.parseDouble(parts[4]),amps=Double.parseDouble(parts[5]),power=Double.parseDouble(parts[6]);
                        if(wall<=0||mono<=0||!between(soc,0,100)||!between(volts,1,100)||!between(amps,-30,30)||!between(power,-3000,3000)
                                ||Math.abs(volts*amps-power)>.001||!(parts[8].equals("0")||parts[8].equals("1")))return null;
                        if(!parts[7].isEmpty()&&!between(Double.parseDouble(parts[7]),-40,100))return null;
                        session.points.add(new Point(wall,mono,soc,volts,amps,power,parts[7].isEmpty()?null:Double.valueOf(parts[7]),parts[8].equals("1"),session.format==1?null:readEstimate(parts)));
                    }else return null;
                }catch(IllegalArgumentException bad){return null;}
            }
        }
        return session.profile==null?null:session;
    }
    /** A corrupt optional prediction must not erase sound raw measurements or learned evidence. */
    private static Estimate readEstimate(String[] parts){
        try{
            Double full=optionalNumber(parts[10]),limit=optionalNumber(parts[11]),target=optionalNumber(parts[12]);
            int sessions=Integer.parseInt(parts[15]);
            if(full!=null&&full<0||limit!=null&&limit<0||target!=null&&(target<=0||target>100)||sessions<0||sessions>MAX_SESSIONS)return null;
            return new Estimate(parts[9],full,limit,target,parts[13].isEmpty()?null:Source.valueOf(parts[13]),parts[14].isEmpty()?null:Source.valueOf(parts[14]),sessions);
        }catch(IllegalArgumentException malformed){return null;}
    }
    private static Double optionalNumber(String value){
        if(value.isEmpty())return null;double number=Double.parseDouble(value);if(!Double.isFinite(number))throw new IllegalArgumentException("Invalid estimate");return number;
    }
}
