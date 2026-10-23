package com.jjricks.volt;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Device-local write-ahead record of setting tries, so an interrupted one is never forgotten and
 * never replayed: Experimental controls' tries (kind "experimental" and "verify", Help verify) and
 * the older guided tests (kind "test", only recovered and reported now). Each entry is one versioned
 * key=value line with a checksum; a corrupt or unknown line is dropped and the valid entries are
 * kept. Only semantic values are stored (kind, feature, variant, layer, model prefix, meter firmware,
 * baseline, target, phase, outcomes, whether a sent report included it): no packets, serials, MACs,
 * owner identities, names, PIN data or times of day, and every value is pattern-checked so none can
 * be written. Not part of any backup. Pure Java; the Android file lives under getNoBackupFilesDir().
 * Version 2 adds kind, layer and reported; a version 1 file loads as guided tests.
 */
final class TestJournal {
    static final String HEADER="volt-compat-tests v2",HEADER_V1="volt-compat-tests v1";
    /** Entry kinds: an older guided test, an Experimental controls try, a Help verify try. */
    static final String TEST="test",EXPERIMENTAL="experimental",VERIFY="verify";
    static final int MAX_ENTRIES=20;
    static final long PRUNE_AFTER_MS=30L*24*60*60*1000;
    /** Outcome steps kept per test; later steps replace the middle of the history, never make the entry invalid. */
    static final int MAX_OUTCOMES=16;
    /** Restoration states, as in the compatibility report. */
    static final String CONFIRMED="confirmed",OUTSTANDING="outstanding",DECLINED="declined";

    interface Storage {
        /** The saved text, or null when nothing has been saved yet. */
        String read() throws IOException;
        void writeAtomic(String text) throws IOException;
    }

    static final class Entry {
        String testId="",profileId="",feature="",variantId="",pidPrefix="",pidSource="none",meterFw="",phase="",effect="not_checked";
        String restoration=OUTSTANDING,resolution="";
        /** TEST, EXPERIMENTAL or VERIFY; layer is the model layer at the time (lower case), "" for older entries. */
        String kind=TEST,layer="";
        /** A report the owner shared, saved or emailed included this entry as it is now; then it may be pruned like any other. */
        boolean reported;
        int registryVersion,baseline,target;
        /** The related byte captured with the baseline (another bit field of the same byte, or the range choice set), or -1. */
        int related=-1;
        /** Outcome tokens (GuidedTest.Outcome.token) and their times since the test was approved. */
        List<String> outcomes=new ArrayList<>(); List<Long> elapsedMs=new ArrayList<>();
        long createdWall,updatedWall; boolean resolved;
        Entry copy(){
            Entry e=new Entry();e.testId=testId;e.profileId=profileId;e.feature=feature;e.variantId=variantId;e.pidPrefix=pidPrefix;e.pidSource=pidSource;
            e.meterFw=meterFw;e.phase=phase;e.effect=effect;e.restoration=restoration;e.resolution=resolution;e.registryVersion=registryVersion;
            e.baseline=baseline;e.target=target;e.related=related;e.outcomes=new ArrayList<>(outcomes);e.elapsedMs=new ArrayList<>(elapsedMs);
            e.createdWall=createdWall;e.updatedWall=updatedWall;e.resolved=resolved;e.kind=kind;e.layer=layer;e.reported=reported;return e;
        }
        /** An Experimental controls try (either group), not an older guided test. */
        boolean isTry(){return !TEST.equals(kind);}
    }

    private final Storage storage;
    private final List<Entry> entries=new ArrayList<>();
    private final boolean readable;
    private int dropped;

    /** Loads tolerantly. If the file cannot be read at all, the journal refuses commits so nothing saved is overwritten. */
    TestJournal(Storage storage){
        this.storage=storage;boolean ok=true;
        try{String text=storage.read();if(text!=null)load(text);}
        catch(IOException|RuntimeException unreadable){ok=false;entries.clear();}
        readable=ok;
    }

    /** Shown wherever unfinished tests would be, connected or not, while the journal cannot be read. */
    static final String UNAVAILABLE_NOTE="Test records unavailable. A previous test may not have been set back; check the scooter's settings.";

    /** False when the saved journal could not be read; no test may start then, and earlier unfinished tests cannot be listed. */
    boolean healthy(){return readable;}
    /** Lines dropped on load (corrupt, unknown or invalid). */
    int dropped(){return dropped;}

    /**
     * Saves one entry (new or updated by testId) before the caller acts on it: the whole journal
     * is written to a temporary file, synced and renamed. On failure nothing in memory changes.
     */
    synchronized void commit(Entry e) throws IOException{
        if(!readable)throw new IOException("Test records are unavailable");
        if(!valid(e))throw new IllegalArgumentException("Test record is invalid");
        List<Entry> next=new ArrayList<>();boolean replaced=false;
        for(Entry old:entries){if(old.testId.equals(e.testId)){next.add(saved(old,e));replaced=true;}else next.add(old);}
        if(!replaced)next.add(saved(null,e));
        prune(next,e.updatedWall);
        if(next.size()>MAX_ENTRIES)throw new IOException("Too many unfinished tests");
        storage.writeAtomic(serialize(next));
        entries.clear();entries.addAll(next);
    }
    /**
     * The entry as committed: reported only while it records exactly what a sent report included. A new outcome, the
     * owner's answer, a restoration, a resolution or a reopening (Set back, review) makes it unreported again, so the
     * overflow keeps it until a report includes the new evidence. A commit never marks an entry reported.
     */
    private static Entry saved(Entry old,Entry e){Entry put=e.copy();put.reported=old!=null&&old.reported&&sameRecord(old,put);return put;}
    private static boolean sameRecord(Entry a,Entry b){return record(a).equals(record(b));}
    /** The entry's line without the reported flag and the update time: what a report built from it shows. */
    private static String record(Entry e){Entry x=e.copy();x.reported=false;x.updatedWall=0;return line(x);}
    /**
     * One entry as a report includes it: "test ID:digest of its record". A report carries these beside it (never in it),
     * and acknowledging the report marks only entries whose record still has that digest, so evidence added after the
     * snapshot (a restoration finished while the preview was open, or during a save) waits for the next report.
     */
    static String revision(Entry e){
        try{
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(record(e).getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(e.testId).append(':');for(int i=0;i<8;i++)out.append(String.format(Locale.ROOT,"%02x",hash[i]&255));
            return out.toString();
        }catch(NoSuchAlgorithmException missing){throw new IllegalStateException(missing);}
    }
    /** Revisions of one profile's entries now, as a report built from all(profileId) would include them. */
    synchronized List<String> revisions(String profileId){List<String> out=new ArrayList<>();for(Entry e:entries)if(e.profileId.equals(profileId))out.add(revision(e));return out;}
    /** Marks a test resolved: how is CONFIRMED (restoration verified) or DECLINED (owner kept the value or dismissed it). */
    synchronized void resolve(String testId,String how,String note,long wallMs) throws IOException{
        if(!CONFIRMED.equals(how)&&!DECLINED.equals(how))throw new IllegalArgumentException("Unknown resolution");
        Entry e=find(testId);if(e==null)return;
        e=e.copy();e.resolved=true;e.restoration=how;e.resolution=note==null?"":note;e.updatedWall=Math.max(e.createdWall,wallMs);
        commit(e);
    }
    /**
     * Adds one outcome step. At MAX_OUTCOMES the oldest step after the first half goes, so the
     * test's first steps and its latest ones (a final restoration among them) always fit.
     */
    static void append(Entry e,String token,long elapsedMs){
        while(e.outcomes.size()>=MAX_OUTCOMES){int drop=Math.min(MAX_OUTCOMES/2,e.outcomes.size()-1);e.outcomes.remove(drop);if(drop<e.elapsedMs.size())e.elapsedMs.remove(drop);}
        e.outcomes.add(token);e.elapsedMs.add(elapsedMs);
    }
    synchronized Entry find(String testId){for(Entry e:entries)if(e.testId.equals(testId))return e.copy();return null;}
    /** Unresolved tests of one scooter profile, oldest first. */
    synchronized List<Entry> unresolved(String profileId){
        List<Entry> out=new ArrayList<>();for(Entry e:entries)if(!e.resolved&&e.profileId.equals(profileId))out.add(e.copy());return out;
    }
    /** Unresolved tests of every profile. */
    synchronized List<Entry> unresolved(){List<Entry> out=new ArrayList<>();for(Entry e:entries)if(!e.resolved)out.add(e.copy());return out;}
    /** Every kept test of one profile (for the report), oldest first. */
    synchronized List<Entry> all(String profileId){List<Entry> out=new ArrayList<>();for(Entry e:entries)if(e.profileId.equals(profileId))out.add(e.copy());return out;}
    /** The latest try of one control on one profile, or null. */
    synchronized Entry latestTry(String profileId,String feature){
        Entry best=null;for(Entry e:entries)if(e.isTry()&&e.profileId.equals(profileId)&&e.feature.equals(feature)&&(best==null||e.createdWall>=best.createdWall))best=e;
        return best==null?null:best.copy();
    }
    /**
     * A sent report included these entry revisions (revision()): they keep their results but may now be pruned. An entry
     * whose record changed since the report was built is left unreported; a bare test ID matches nothing.
     */
    synchronized void markReported(List<String> revisions,long wallMs) throws IOException{
        if(!readable)throw new IOException("Test records are unavailable");
        List<Entry> next=new ArrayList<>();boolean changed=false;
        for(Entry old:entries){Entry e=old.copy();if(!e.reported&&revisions.contains(revision(e))){e.reported=true;changed=true;}next.add(e);}
        if(!changed)return;
        prune(next,wallMs);storage.writeAtomic(serialize(next));entries.clear();entries.addAll(next);
    }
    /** The owner clears one profile's finished tries. Unfinished ones stay: they still need a review. Returns how many went. */
    synchronized int clearTries(String profileId) throws IOException{
        if(!readable)throw new IOException("Test records are unavailable");
        List<Entry> next=new ArrayList<>();int removed=0;
        for(Entry e:entries){if(e.isTry()&&e.resolved&&e.profileId.equals(profileId))removed++;else next.add(e);}
        if(removed==0)return 0;
        storage.writeAtomic(serialize(next));entries.clear();entries.addAll(next);return removed;
    }
    /** Room for one more test: unresolved entries are never pruned to make space. */
    synchronized boolean canStart(){return readable&&unresolved().size()<MAX_ENTRIES;}

    /**
     * Resolved entries older than 30 days go, except tries no sent report has included yet; then, while
     * over the limit, the oldest resolved ones (reported ones first). Unresolved entries always stay.
     */
    private static void prune(List<Entry> list,long nowWall){
        for(int i=list.size()-1;i>=0;i--){Entry e=list.get(i);if(e.resolved&&(e.reported||!e.isTry())&&nowWall-e.updatedWall>PRUNE_AFTER_MS)list.remove(i);}
        while(list.size()>MAX_ENTRIES){
            int oldest=-1;
            for(int i=0;i<list.size();i++){
                Entry e=list.get(i);if(!e.resolved)continue;
                if(oldest<0)oldest=i;
                else{Entry o=list.get(oldest);if(e.reported!=o.reported?e.reported:e.updatedWall<o.updatedWall)oldest=i;}
            }
            if(oldest<0)return;list.remove(oldest);
        }
    }

    // ---- Format ------------------------------------------------------------------------------

    private static final String[] KEYS_V1={"id","profile","feature","variant","pid_prefix","pid_source","meter","registry","baseline","target","related",
        "phase","effect","restoration","resolution","outcomes","elapsed","created","updated","resolved"};
    private static final String[] KEYS={"id","profile","feature","variant","pid_prefix","pid_source","meter","registry","baseline","target","related",
        "phase","effect","restoration","resolution","outcomes","elapsed","created","updated","resolved","kind","layer","reported"};

    static String serialize(List<Entry> list){
        StringBuilder out=new StringBuilder(HEADER).append('\n');
        for(Entry e:list)out.append(line(e)).append('\n');
        return out.toString();
    }
    static String line(Entry e){
        StringBuilder b=new StringBuilder("entry");
        String[] values={e.testId,e.profileId,e.feature,e.variantId,e.pidPrefix,e.pidSource,e.meterFw,Integer.toString(e.registryVersion),
            Integer.toString(e.baseline),Integer.toString(e.target),Integer.toString(e.related),e.phase,e.effect,e.restoration,e.resolution,
            join(e.outcomes),joinLongs(e.elapsedMs),Long.toString(e.createdWall),Long.toString(e.updatedWall),e.resolved?"1":"0",e.kind,e.layer,e.reported?"1":"0"};
        for(int i=0;i<KEYS.length;i++)b.append('\t').append(KEYS[i]).append('=').append(values[i]);
        String body=b.toString();
        return body+"\tcrc="+crc(body);
    }
    private void load(String text){
        String[] lines=text.split("\n",-1);
        if(text.isEmpty())return;
        // Another format (a newer Volt) is never overwritten: the journal stays read-only and empty.
        boolean v1=lines[0].equals(HEADER_V1);
        if(!v1&&!lines[0].equals(HEADER))throw new IllegalStateException("Unknown test journal format");
        for(int i=1;i<lines.length;i++){
            if(lines[i].isEmpty())continue;
            Entry e=parse(lines[i],v1?KEYS_V1:KEYS);
            if(e==null||find(e.testId)!=null||entries.size()>=MAX_ENTRIES){dropped++;continue;}
            entries.add(e);
        }
    }
    static Entry parse(String line){return parse(line,KEYS);}
    /** One line in the current format, or (keys KEYS_V1) a version 1 line, which is an older guided test. */
    static Entry parse(String line,String[] keys){
        int c=line.lastIndexOf("\tcrc=");if(c<0||!line.startsWith("entry\t"))return null;
        if(!line.substring(c+5).equals(crc(line.substring(0,c))))return null;
        String[] parts=line.substring(6,c).split("\t",-1);if(parts.length!=keys.length)return null;
        String[] v=new String[keys.length];
        for(int i=0;i<keys.length;i++){String p=parts[i];if(!p.startsWith(keys[i]+"="))return null;v[i]=p.substring(keys[i].length()+1);}
        try{
            Entry e=new Entry();
            e.testId=v[0];e.profileId=v[1];e.feature=v[2];e.variantId=v[3];e.pidPrefix=v[4];e.pidSource=v[5];e.meterFw=v[6];
            e.registryVersion=Integer.parseInt(v[7]);e.baseline=Integer.parseInt(v[8]);e.target=Integer.parseInt(v[9]);e.related=Integer.parseInt(v[10]);
            e.phase=v[11];e.effect=v[12];e.restoration=v[13];e.resolution=v[14];
            e.outcomes=v[15].isEmpty()?new ArrayList<String>():new ArrayList<>(java.util.Arrays.asList(v[15].split(",",-1)));
            e.elapsedMs=new ArrayList<>();if(!v[16].isEmpty())for(String t:v[16].split(",",-1))e.elapsedMs.add(Long.parseLong(t));
            e.createdWall=Long.parseLong(v[17]);e.updatedWall=Long.parseLong(v[18]);
            if(!v[19].equals("0")&&!v[19].equals("1"))return null;e.resolved=v[19].equals("1");
            if(keys.length>20){e.kind=v[20];e.layer=v[21];if(!v[22].equals("0")&&!v[22].equals("1"))return null;e.reported=v[22].equals("1");}
            return valid(e)?e:null;
        }catch(NumberFormatException bad){return null;}
    }

    /** Every field typed and bounded; this is what keeps identities, packets and free text out. */
    static boolean valid(Entry e){
        if(e==null)return false;
        if(!e.testId.matches("[0-9a-f]{16}")||!e.profileId.matches("[0-9A-Za-z_-]{1,64}"))return false;
        if(!(TEST.equals(e.kind)||EXPERIMENTAL.equals(e.kind)||VERIFY.equals(e.kind)))return false;
        // Guided tests and Help verify tries only for the guided-test table; experimental tries only for Experimental controls' keys.
        if(!(EXPERIMENTAL.equals(e.kind)?ModelRegistry.experimentalKey(e.feature):ModelRegistry.testable(e.feature)))return false;
        if(!e.variantId.matches("[a-z0-9_.]{1,48}")||!e.variantId.startsWith(e.feature+"."))return false;
        if(!e.layer.isEmpty()){boolean known=false;for(ModelRegistry.Layer l:ModelRegistry.Layer.values())known|=l.name().toLowerCase(Locale.ROOT).equals(e.layer);if(!known)return false;}
        if(!e.pidPrefix.matches("[0-9]{0,4}")||!(e.pidSource.equals("advertised")||e.pidSource.equals("serial")||e.pidSource.equals("none")))return false;
        if(!e.meterFw.matches("[0-9A-Za-z.]{0,16}"))return false;
        if(e.registryVersion<0||e.registryVersion>9999||e.baseline<0||e.baseline>255||e.target<0||e.target>255||e.related<-1||e.related>255)return false;
        boolean phase=false;for(GuidedTest.Phase p:GuidedTest.Phase.values())phase|=p.name().equals(e.phase);if(!phase)return false;
        if(!(e.effect.equals("observed")||e.effect.equals("not_observed")||e.effect.equals("not_checked")))return false;
        if(!(e.restoration.equals(CONFIRMED)||e.restoration.equals(OUTSTANDING)||e.restoration.equals(DECLINED)))return false;
        if(!e.resolution.matches("[a-z_]{0,32}"))return false;
        if(e.outcomes==null||e.elapsedMs==null||e.outcomes.size()>MAX_OUTCOMES||e.outcomes.size()!=e.elapsedMs.size())return false;
        for(String o:e.outcomes)if(GuidedTest.Outcome.parse(o)==null)return false;
        for(Long t:e.elapsedMs)if(t==null||t<0||t>24L*3600*1000)return false;
        return e.createdWall>=0&&e.updatedWall>=e.createdWall;
    }

    private static String join(List<String> list){StringBuilder b=new StringBuilder();for(String s:list)b.append(b.length()==0?"":",").append(s);return b.toString();}
    private static String joinLongs(List<Long> list){StringBuilder b=new StringBuilder();for(Long s:list)b.append(b.length()==0?"":",").append(s);return b.toString();}
    private static String crc(String text){CRC32 c=new CRC32();c.update(text.getBytes(StandardCharsets.UTF_8));return String.format(Locale.ROOT,"%08x",c.getValue());}

    // ---- File storage -------------------------------------------------------------------------

    /** A file written to a temporary sibling, synced, then renamed over the old one (like ConnectionEventLog). */
    static Storage file(final File file){
        return new Storage(){
            @Override public String read() throws IOException{
                InputStream in;try{in=new FileInputStream(file);}catch(FileNotFoundException none){return file.exists()?fail():null;}
                try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                    while((n=in.read(buffer))>0){out.write(buffer,0,n);if(out.size()>256*1024)throw new IOException("Test journal is too large");}
                    return new String(out.toByteArray(),StandardCharsets.UTF_8);}
                finally{in.close();}
            }
            private String fail() throws IOException{throw new IOException("Test journal is unreadable");}
            @Override public void writeAtomic(String text) throws IOException{
                File parent=file.getParentFile();if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IOException("No test journal folder");
                File temporary=new File(parent,file.getName()+".tmp");
                try(FileOutputStream out=new FileOutputStream(temporary)){out.write(text.getBytes(StandardCharsets.UTF_8));out.flush();out.getFD().sync();}
                try{Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
            }
        };
    }

    /** For tests: an unmodifiable view of the kept entries' ids. */
    synchronized List<String> ids(){List<String> out=new ArrayList<>();for(Entry e:entries)out.add(e.testId);return Collections.unmodifiableList(out);}
}
