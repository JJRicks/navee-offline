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
    public enum Stop { STOPPED, COMPLETE, CHARGING_ENDED, DISCONNECTED, PROFILE_CHANGED, READINGS_STOPPED, LIMIT, ERROR, PROCESS_ENDED, INTERRUPTED }
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
        public final String profile; public final Charger charger; public final int qualifiedSessions;
        private final double[] minutes; private final int[] counts;
        Curve(String profile, Charger charger, List<double[]> sessions) {
            this.profile=profile;this.charger=charger;qualifiedSessions=sessions.size();minutes=new double[100];counts=new int[100];
            Arrays.fill(minutes,Double.NaN);
            for(int bin=0;bin<100;bin++) {
                List<Double> values=new ArrayList<>();for(double[] session:sessions)if(Double.isFinite(session[bin]))values.add(session[bin]);
                Collections.sort(values);if(values.size()<2)continue;
                double median=values.size()%2==1?values.get(values.size()/2):(values.get(values.size()/2-1)+values.get(values.size()/2))/2;
                // With just two strongly disagreeing sessions there is no reliable majority.
                if(values.size()==2&&values.get(1)>values.get(0)*2.5)continue;
                double sum=0;for(double value:values)if(value>=median*.4&&value<=median*2.5){sum+=value;counts[bin]++;}
                if(counts[bin]>=2)minutes[bin]=sum/counts[bin];
            }
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
    }
    public static final class Snapshot {
        public final Map<String,Curve> curves; public final int sessions; public final long bytes;
        Snapshot(Map<String,Curve> curves,int sessions,long bytes){this.curves=Collections.unmodifiableMap(curves);this.sessions=sessions;this.bytes=bytes;}
    }
    private final File directory;
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
    public synchronized void markGap(){gap=true;}
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
            if(targetProfile==null||session!=null&&targetProfile.equals(session.profile))if(!file.delete())throw new IOException("Charge history could not be cleared");
        }
    }
    /** Run outside the UI thread. Only closed, complete files are considered for learning. */
    public Snapshot snapshot() throws IOException {
        final List<File> source;final File current;
        synchronized(this){source=files();current=active;}
        Map<String,List<double[]>> eligible=new LinkedHashMap<>();Map<String,Session> subjects=new LinkedHashMap<>();int sessions=0;long bytes=0;
        for(File file:source) {
            bytes+=file.length();if(file.equals(current)||!file.getName().endsWith(".csv"))continue;
            Session session=read(file);if(session==null)continue;sessions++;
            double[] bins=session.bins();if(bins==null)continue;
            String key=key(session.profile,session.charger);eligible.computeIfAbsent(key,ignored->new ArrayList<>()).add(bins);subjects.put(key,session);
        }
        Map<String,Curve> curves=new LinkedHashMap<>();for(Map.Entry<String,List<double[]>> item:eligible.entrySet()){
            Session session=subjects.get(item.getKey());curves.put(item.getKey(),new Curve(session.profile,session.charger,item.getValue()));
        }
        return new Snapshot(curves,sessions,bytes);
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
        List<File> entries=files();int remove=entries.size()-limit;
        for(File file:entries)if(remove>0&&!file.equals(active)){if(!file.delete())throw new IOException("Old charge recordings could not be removed");remove--;}
    }
    private static final class Point {
        final long wall,mono;final double soc;final boolean gap;
        Point(long wall,long mono,double soc,boolean gap){this.wall=wall;this.mono=mono;this.soc=soc;this.gap=gap;}
    }
    private static final class Session {
        String profile;Charger charger;Stop stop;List<Point> points=new ArrayList<>();
        double[] bins(){
            if(stop==null||stop==Stop.INTERRUPTED||stop==Stop.PROCESS_ENDED||stop==Stop.ERROR||points.size()<61)return null;
            double[] result=new double[100];Arrays.fill(result,Double.NaN);boolean[] repeated=new boolean[100];
            Point previous=null;double anchorSoc=Double.NaN;long anchorTime=0,covered=0;int bins=0;
            for(Point point:points){
                boolean continuous=previous!=null&&!point.gap&&point.mono>previous.mono&&point.mono-previous.mono<=MAX_GAP_MS
                        &&Math.abs((point.wall-previous.wall)-(point.mono-previous.mono))<=2000;
                if(!continuous||previous!=null&&(point.soc<previous.soc||point.soc-previous.soc>1)){anchorSoc=Double.NaN;anchorTime=0;}
                else covered+=point.mono-previous.mono;
                if(continuous&&point.soc>previous.soc){
                    if(Double.isFinite(anchorSoc)&&point.soc-anchorSoc==1&&anchorSoc==Math.floor(anchorSoc)){
                        int bin=(int)anchorSoc;double minutes=(point.mono-anchorTime)/60000d;
                        if(bin>=0&&bin<100&&minutes>=.5&&minutes<=90&&!repeated[bin]){
                            if(Double.isFinite(result[bin])){result[bin]=Double.NaN;repeated[bin]=true;bins--;}
                            else{result[bin]=minutes;bins++;}
                        }
                    }
                    anchorSoc=point.soc;anchorTime=point.mono;
                }
                previous=point;
            }
            long elapsed=points.get(points.size()-1).mono-points.get(0).mono;
            return bins>=MIN_BINS&&elapsed>=600000&&covered>=elapsed*.9?result:null;
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
                    if(lines==1){if(parts.length!=5||!parts[0].equals("charge-v1")||!parts[1].matches("[A-Za-z0-9_-]{1,128}"))return null;session.profile=parts[1];session.charger=Charger.valueOf(parts[2]);}
                    else if(parts.length==2&&parts[0].equals("end")){if(session.stop!=null)return null;session.stop=Stop.valueOf(parts[1]);}
                    else if(parts.length==9&&parts[0].equals("s")&&session.stop==null){
                        long wall=Long.parseLong(parts[1]),mono=Long.parseLong(parts[2]);double soc=Double.parseDouble(parts[3]);
                        double volts=Double.parseDouble(parts[4]),amps=Double.parseDouble(parts[5]),power=Double.parseDouble(parts[6]);
                        if(wall<=0||mono<=0||!between(soc,0,100)||!between(volts,1,100)||!between(amps,-30,30)||!between(power,-3000,3000)
                                ||Math.abs(volts*amps-power)>.001||!(parts[8].equals("0")||parts[8].equals("1")))return null;
                        if(!parts[7].isEmpty()&&!between(Double.parseDouble(parts[7]),-40,100))return null;
                        session.points.add(new Point(wall,mono,soc,parts[8].equals("1")));
                    }else return null;
                }catch(IllegalArgumentException bad){return null;}
            }
        }
        return session.profile==null?null:session;
    }
}
