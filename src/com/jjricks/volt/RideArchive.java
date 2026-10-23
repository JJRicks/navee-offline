package com.jjricks.volt;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Complete, portable ride journals. No Android dependencies, credentials, or network access.
 * Caller freezes all journal writers before write/plan/apply and recovers before opening stores. */
final class RideArchive {
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024, MAX_FILE_BYTES = 512L * 1024 * 1024;
    static final int MAX_RIDES = 5000, MAX_MANIFEST = 4 * 1024 * 1024;
    private static final String UUID_PATTERN = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}";
    private static final String FORMAT = "volt-ride-backup", MANIFEST = "manifest.bin";
    interface Storage {
        File files(); File work();
        default void sync(File directory) throws IOException { AppDataArchive.syncDirectory(directory); }
        default void copied(String path) throws IOException { } // Fault injection at a durable file boundary.
    }
    static final class Prepared {
        final File directory;
        final long createdAtMs, bytes;
        final LinkedHashMap<String,String> profiles;
        final List<RideStore.Summary> rides;
        boolean consumed;
        Prepared(File directory,long created,long bytes,LinkedHashMap<String,String> profiles,List<RideStore.Summary> rides) {
            this.directory=directory;createdAtMs=created;this.bytes=bytes;this.profiles=profiles;this.rides=Collections.unmodifiableList(rides);
        }
    }
    static final class Plan {
        final Prepared prepared;
        final List<String> paths;
        final Map<String,String> mappings;
        final int added, identical, conflicts;
        Plan(Prepared p,List<String> paths,Map<String,String> mappings,int added,int identical,int conflicts) {
            prepared=p;this.paths=paths;this.mappings=new LinkedHashMap<>(mappings);this.added=added;this.identical=identical;this.conflicts=conflicts;
        }
    }
    private static final class Entry {
        final String path;final long size;final byte[] hash;
        Entry(String p,long s,byte[] h){path=p;size=s;hash=h;}
    }
    private RideArchive() { }

    static int write(Storage storage,OutputStream destination,Map<String,String> profileNames) throws IOException {
        ensureIdle(storage);
        List<Entry> entries=new ArrayList<>();LinkedHashMap<String,String> profiles=new LinkedHashMap<>();
        File folder=new File(storage.files(),"rides");File[] files=folder.listFiles();
        if(folder.exists()&&files==null)throw new IOException("Ride storage cannot be read.");
        long total=0;int rides=0;
        if(files!=null)for(File file:files) {
            if(file.getName().endsWith(".repair"))continue;
            if(!file.getName().matches(UUID_PATTERN+"\\.csv"))throw new IOException("Unsupported ride file.");
            regular(file);validateText(file,true,false);RideStore.Summary summary=RideStore.inspectJournal(file);
            if(++rides>MAX_RIDES)throw new IOException("Too many rides for one backup.");
            String profile=summary.scooterId;
            if(profile.isEmpty()||profile.length()>160)throw new IOException("Invalid scooter association.");
            String name=profileNames.get(profile);profiles.put(profile,name==null?"Saved scooter":bounded(name,200));
            for(String path:paths(summary.id)) {
                File source=contained(storage.files(),path);
                if(source.exists()) {regular(source);if(path.startsWith("phone-rides/"))validateText(source,false,path.endsWith("-motion.csv"));long size=source.length();total=add(total,size,MAX_BYTES);
                    if(size>MAX_FILE_BYTES)throw new IOException("A ride file is too large.");
                    entries.add(new Entry(path,size,digest(source)));
                }
            }
        }
        if(rides==0)throw new IOException("There are no saved rides to back up.");
        entries.sort(Comparator.comparing(e->e.path));
        ByteArrayOutputStream manifestBytes=new ByteArrayOutputStream();DataOutputStream manifest=new DataOutputStream(manifestBytes);
        manifest.writeUTF(FORMAT);manifest.writeInt(1);manifest.writeLong(System.currentTimeMillis());
        manifest.writeInt(profiles.size());for(Map.Entry<String,String> profile:profiles.entrySet()){manifest.writeUTF(profile.getKey());manifest.writeUTF(profile.getValue());}
        manifest.writeInt(entries.size());manifest.writeLong(total);
        for(Entry e:entries){manifest.writeUTF(e.path);manifest.writeLong(e.size);manifest.write(e.hash);}
        manifest.flush();if(manifestBytes.size()>MAX_MANIFEST)throw new IOException("Ride manifest is too large.");
        ZipOutputStream zip=new ZipOutputStream(destination);
        zip.putNextEntry(new ZipEntry(MANIFEST));zip.write(manifestBytes.toByteArray());zip.closeEntry();
        for(Entry entry:entries) {
            File source=contained(storage.files(),entry.path);zip.putNextEntry(new ZipEntry(entry.path));
            MessageDigest hash=sha();long copied;
            try(InputStream in=new FileInputStream(source)){copied=copy(in,zip,MAX_FILE_BYTES,hash);}
            if(copied!=entry.size||!Arrays.equals(hash.digest(),entry.hash))throw new IOException("A ride changed during backup. Try again.");
            zip.closeEntry();
        }
        zip.finish();zip.flush();return rides;
    }

    static Prepared stage(Storage storage,InputStream source) throws IOException {
        ensureIdle(storage);mkdir(storage.work());File stage=new File(storage.work(),"stage-"+UUID.randomUUID());mkdir(stage);
        try {
            LinkedHashMap<String,Entry> expected=new LinkedHashMap<>();LinkedHashMap<String,String> profiles=new LinkedHashMap<>();
            long created,total,loaded=0;
            try(ZipInputStream zip=new ZipInputStream(source)) {
                ZipEntry first=zip.getNextEntry();if(first==null||first.isDirectory()||!MANIFEST.equals(first.getName()))throw new IOException("Choose a Volt ride backup.");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(zip,bytes,MAX_MANIFEST,null);
                DataInputStream manifest=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
                if(!FORMAT.equals(manifest.readUTF())||manifest.readInt()!=1)throw new IOException("Unsupported ride backup version.");
                created=manifest.readLong();if(created<=0)throw new IOException("Invalid backup date.");
                int count=manifest.readInt();if(count<1||count>MAX_RIDES)throw new IOException("Invalid scooter count.");
                for(int i=0;i<count;i++){String id=manifest.readUTF(),name=manifest.readUTF();if(id.isEmpty()||id.length()>160||name.length()>200||profiles.put(id,name)!=null)throw new IOException("Invalid scooter list.");}
                count=manifest.readInt();total=manifest.readLong();
                if(count<1||count>MAX_RIDES*3||total<1||total>MAX_BYTES)throw new IOException("Ride backup is too large or empty.");
                for(int i=0;i<count;i++){String path=manifest.readUTF();long size=manifest.readLong();byte[] hash=new byte[32];manifest.readFully(hash);
                    if(!allowed(path)||size<=0||size>MAX_FILE_BYTES||expected.put(path,new Entry(path,size,hash))!=null)throw new IOException("Invalid or duplicate recording path.");}
                if(manifest.read()!=-1)throw new IOException("Unexpected backup metadata.");zip.closeEntry();
                HashSet<String> seen=new HashSet<>();ZipEntry next;
                while((next=zip.getNextEntry())!=null) {
                    Entry entry=expected.get(next.getName());if(next.isDirectory()||entry==null||!seen.add(entry.path))throw new IOException("Unexpected or duplicate recording file.");
                    File target=contained(stage,entry.path);mkdir(target.getParentFile());MessageDigest hash=sha();long size;
                    try(FileOutputStream out=new FileOutputStream(target)){size=copy(zip,out,Math.min(entry.size,MAX_BYTES-loaded),hash);out.getFD().sync();}
                    if(size!=entry.size||!Arrays.equals(hash.digest(),entry.hash))throw new IOException("The backup is incomplete or damaged.");
                    loaded=add(loaded,size,MAX_BYTES);zip.closeEntry();
                }
                if(seen.size()!=expected.size()||loaded!=total)throw new IOException("The backup is missing recording files.");
            }
            ArrayList<RideStore.Summary> rides=new ArrayList<>();HashSet<String> rideIds=new HashSet<>(),usedProfiles=new HashSet<>();
            for(String path:expected.keySet())if(path.startsWith("rides/")) {
                File file=contained(stage,path);validateText(file,true,false);RideStore.Summary ride=RideStore.inspectJournal(file);
                if(!profiles.containsKey(ride.scooterId))throw new IOException("Missing scooter association.");
                rides.add(ride);rideIds.add(ride.id);usedProfiles.add(ride.scooterId);
            }
            if(rides.isEmpty()||rides.size()>MAX_RIDES||usedProfiles.size()!=profiles.size())throw new IOException("The ride list is inconsistent.");
            for(String path:expected.keySet())if(path.startsWith("phone-rides/")) {
                String id=path.substring("phone-rides/".length(),"phone-rides/".length()+36);
                if(!rideIds.contains(id))throw new IOException("Phone samples are missing their ride.");
                validateText(contained(stage,path),false,path.endsWith("-motion.csv"));
            }
            return new Prepared(stage,created,loaded,profiles,rides);
        } catch(Exception failure) {try{AppDataArchive.deleteTree(stage);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw asIo(failure);}
    }

    /** Mappings must include each source scooter and are chosen explicitly by the rider. */
    static Plan plan(Storage storage,Prepared prepared,Map<String,String> mappings) throws IOException {
        checkStage(storage,prepared);ensureIdle(storage);List<String> additions=new ArrayList<>();int added=0,identical=0,conflicts=0;
        for(RideStore.Summary ride:prepared.rides) {
            String mapped=mappings.get(ride.scooterId);
            if(mapped==null||mapped.isEmpty()||mapped.length()>160)throw new IOException("Choose a destination for every scooter.");
            File journal=contained(prepared.directory,"rides/"+ride.id+".csv");
            if(!RideStore.inspectJournal(journal).scooterId.equals(mapped))RideStore.reassignJournal(journal,mapped);
            boolean exists=false,equal=true;List<String> group=paths(ride.id);
            for(String path:group) {
                File local=contained(storage.files(),path),incoming=contained(prepared.directory,path);
                if(local.exists()){exists=true;regular(local);}
                if(local.exists()!=incoming.exists()||(local.exists()&&(local.length()!=incoming.length()||!Arrays.equals(digest(local),digest(incoming)))))equal=false;
            }
            if(exists){if(equal)identical++;else conflicts++;continue;}
            added++;for(String path:group)if(contained(prepared.directory,path).isFile())additions.add(path);
        }
        // A group's sidecars are copied first; the journal makes a complete group visible last.
        return new Plan(prepared,additions,mappings,added,identical,conflicts);
    }

    static void apply(Storage storage,Plan approved) throws IOException {
        if(approved==null)throw new IOException("Review the backup first.");
        Plan current=plan(storage,approved.prepared,approved.mappings);
        if(!current.paths.equals(approved.paths)||current.identical!=approved.identical||current.conflicts!=approved.conflicts)throw new IOException("Saved rides changed. Review the backup again.");
        if(current.paths.isEmpty()){discard(storage,current.prepared);return;}
        File transaction=new File(storage.work(),"transaction");mkdir(transaction);boolean active=false;
        try {
            File list=new File(transaction,"paths.bin");
            try(FileOutputStream bytes=new FileOutputStream(list);DataOutputStream out=new DataOutputStream(bytes)) {
                out.writeInt(current.paths.size());for(String path:current.paths){out.writeUTF(path);out.writeUTF(identity(contained(current.prepared.directory,path)));}out.flush();bytes.getFD().sync();
            }
            File incoming=new File(transaction,"incoming");
            if(!current.prepared.directory.renameTo(incoming))throw new IOException("Could not prepare the ride restore.");
            current.prepared.consumed=true;
            for(String folder:new String[]{"rides","phone-rides"}){File directory=new File(incoming,folder);if(directory.exists())storage.sync(directory);}
            storage.sync(incoming);storage.sync(transaction);storage.sync(storage.work());storage.sync(storage.work().getParentFile());
            state(storage,transaction,"active");active=true;
            for(String path:current.paths) {
                File target=contained(storage.files(),path);mkdir(target.getParentFile());
                File source=contained(incoming,path);String owned=identity(source);
                // The private data directories share a filesystem. A complete staged file moves
                // into place without replacing an existing name; its durable identity survives.
                Files.move(source.toPath(),target.toPath());
                if(!owned.equals(identity(target)))throw new IOException("Recording storage changed during restore.");
                storage.copied(path);
            }
            syncLive(storage);state(storage,transaction,"committed");
        } catch(Exception failure) {
            if(active){try{rollback(storage,transaction);}catch(Exception rollback){failure.addSuppressed(rollback);throw new IOException("Restore interrupted. Reopen Volt; do not clear storage.",failure);}}
            else try{AppDataArchive.deleteTree(transaction);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw asIo(failure);
        }
        current.prepared.consumed=true;
        try{AppDataArchive.deleteTree(transaction);AppDataArchive.deleteTree(current.prepared.directory);}catch(IOException ignored){/* Startup removes committed work. */}
    }
    static boolean needsRecovery(Storage storage) {File work=storage.work();File[] files=work.listFiles();return work.exists()&&(files==null||files.length>0);}
    static void recover(Storage storage) throws IOException {
        File work=storage.work();if(!work.exists())return;regularDirectory(work);
        File transaction=new File(work,"transaction");
        if(transaction.exists()) {
            regularDirectory(transaction);File marker=new File(transaction,"state");
            if(!marker.exists())AppDataArchive.deleteTree(transaction);
            else {regular(marker);String state=new String(read(marker,32),StandardCharsets.UTF_8);
                if("committed".equals(state))AppDataArchive.deleteTree(transaction);
                else if("active".equals(state))rollback(storage,transaction);
                else throw new IOException("Ride restore recovery needs attention. Keep app storage intact.");}
        }
        File[] children=work.listFiles();if(children==null)throw new IOException("Ride restore storage is unavailable.");
        for(File file:children){if(!file.getName().matches("stage-[a-f0-9-]{36}"))throw new IOException("Unexpected ride restore recovery data.");AppDataArchive.deleteTree(file);}
    }
    static void discard(Storage storage,Prepared prepared) throws IOException {if(prepared!=null&&!prepared.consumed){checkStage(storage,prepared);AppDataArchive.deleteTree(prepared.directory);prepared.consumed=true;}}
    private static void rollback(Storage storage,File transaction) throws IOException {
        LinkedHashMap<String,String> paths=new LinkedHashMap<>();File record=new File(transaction,"paths.bin");regular(record);
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(read(record,MAX_MANIFEST)))) {
            int count=in.readInt();if(count<1||count>MAX_RIDES*3)throw new IOException("Invalid ride recovery list.");
            for(int i=0;i<count;i++){String path=in.readUTF(),owned=in.readUTF();if(!allowed(path)||owned.isEmpty()||owned.length()>512||paths.put(path,owned)!=null)throw new IOException("Invalid ride recovery path.");}
            if(in.read()!=-1)throw new IOException("Invalid ride recovery list.");
        }
        // If committed fsync failed, re-arm the marker before deleting any newly installed files.
        state(storage,transaction,"active");
        for(Map.Entry<String,String> entry:paths.entrySet()){File file=contained(storage.files(),entry.getKey());
            if(file.exists()&&entry.getValue().equals(identity(file))&&!file.delete())throw new IOException("Could not roll back an interrupted ride restore.");}
        syncLive(storage);state(storage,transaction,"committed");AppDataArchive.deleteTree(transaction);
    }
    private static void syncLive(Storage storage) throws IOException {
        for(String folder:new String[]{"rides","phone-rides"}){File file=new File(storage.files(),folder);if(file.exists())storage.sync(file);}
        storage.sync(storage.files());
    }
    private static void state(Storage storage,File transaction,String value) throws IOException {
        File temporary=new File(transaction,"state.tmp");try(FileOutputStream out=new FileOutputStream(temporary)){out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!temporary.renameTo(new File(transaction,"state")))throw new IOException("Could not save ride restore recovery.");storage.sync(transaction);
    }
    private static void checkStage(Storage storage,Prepared prepared) throws IOException {
        if(prepared==null||prepared.consumed||!prepared.directory.getName().matches("stage-[a-f0-9-]{36}")||!prepared.directory.getCanonicalFile().getParentFile().equals(storage.work().getCanonicalFile()))throw new IOException("Choose the ride backup again.");regularDirectory(prepared.directory);
    }
    private static void ensureIdle(Storage storage)throws IOException{if(new File(storage.work(),"transaction").exists())throw new IOException("Reopen Volt to finish ride recovery first.");}
    static List<String> paths(String id){return Arrays.asList("phone-rides/"+id+"-phone.csv","phone-rides/"+id+"-motion.csv","rides/"+id+".csv");}
    static boolean allowed(String path){return path!=null&&(path.matches("rides/"+UUID_PATTERN+"\\.csv")||path.matches("phone-rides/"+UUID_PATTERN+"-(phone|motion)\\.csv"));}
    private static File contained(File root,String path)throws IOException {
        if(!allowed(path))throw new IOException("Invalid ride path.");File file=new File(root,path);
        if(!file.getCanonicalFile().getParentFile().equals(new File(root,path.substring(0,path.indexOf('/'))).getCanonicalFile())||!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("Invalid ride path.");
        if(Files.isSymbolicLink(file.toPath())||Files.isSymbolicLink(file.getParentFile().toPath()))throw new IOException("Unexpected ride storage link.");return file;
    }
    static void validateText(File file,boolean scooter,boolean motion)throws IOException {
        regular(file);if(file.length()>MAX_FILE_BYTES)throw new IOException("A ride file is too large.");
        CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(Reader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),decoder))) {
            StringBuilder first=new StringBuilder(),phoneRecord=new StringBuilder();int count=0,c;boolean header=true,quoted=false,afterQuote=false;long rows=0;
            while((c=in.read())!=-1) {
                if(++count>(scooter?65536:4096))throw new IOException("A ride record is too large.");
                if(header)first.append((char)c);
                else if(!scooter)phoneRecord.append((char)c);
                if(scooter&&c=='"'){if(quoted){quoted=false;afterQuote=true;}else if(afterQuote){quoted=true;afterQuote=false;}else quoted=true;}
                else {afterQuote=false;if(c=='\n'&&!quoted){rows++;count=0;if(header){header=false;String prefix=scooter?"schema_version,event,recorded_utc,wall_ms,monotonic_ms,frame,key,numeric,text,unit,source_command,sensor_updated_monotonic_ms,sensor_age_ms,current_direction,details":motion?"monotonic_ms,raw_x_mps2,raw_y_mps2,raw_z_mps2,linear_x_mps2,linear_y_mps2,linear_z_mps2,earth_east_mps2,earth_north_mps2,earth_up_mps2,rotation_x,rotation_y,rotation_z,rotation_w,accuracy":"event,wall_ms,monotonic_ms,a,b,c,d,e,f,g,h,i,j";
                    if(!first.toString().trim().equals(prefix))throw new IOException("Unsupported ride journal format.");}
                    else if(!scooter){validatePhoneRecord(phoneRecord.toString().trim(),motion);phoneRecord.setLength(0);}}}
            }
            if(header||count!=0||quoted||rows<1)throw new IOException("An incomplete ride record was found.");
        }
    }
    private static void validatePhoneRecord(String line,boolean motion)throws IOException {
        if(line.isEmpty())return; // Recorder recovery can append a blank line before GAP.
        String[] fields=line.split(",",-1);
        try {
            int firstNumber;
            if(motion){if(fields.length!=15)throw new IllegalArgumentException();clock(fields[0]);firstNumber=1;}
            else {
                if(fields.length<3)throw new IllegalArgumentException();clock(fields[1]);clock(fields[2]);String event=fields[0];
                if("START".equals(event)){if(fields.length!=4||!("pocket".equals(fields[3])||"mounted".equals(fields[3])))throw new IllegalArgumentException();return;}
                int expected="GPS".equals(event)?10:"TELEMETRY".equals(event)?9:"MOTION".equals(event)?5:"PRESSURE".equals(event)?4:("GAP".equals(event)||"STOP".equals(event))?3:-1;
                if(fields.length!=expected&&!("TELEMETRY".equals(event)&&fields.length==6))throw new IllegalArgumentException();firstNumber=3;
            }
            for(int i=firstNumber;i<fields.length;i++)if(!fields[i].isEmpty()&&!Double.isFinite(Double.parseDouble(fields[i])))throw new IllegalArgumentException();
        }catch(IllegalArgumentException failure){throw new IOException("A phone sensor record is invalid.",failure);}
    }
    private static void clock(String value){long parsed=Long.parseLong(value);if(parsed<0||parsed>Long.MAX_VALUE/4)throw new IllegalArgumentException();}
    static byte[] digest(File file)throws IOException{MessageDigest hash=sha();try(InputStream in=new FileInputStream(file)){copy(in,new OutputStream(){public void write(int b){}public void write(byte[] b,int o,int n){}},MAX_FILE_BYTES,hash);}return hash.digest();}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static long copy(InputStream in,OutputStream out,long max,MessageDigest hash)throws IOException{byte[] buffer=new byte[65536];long total=0;int n;while((n=in.read(buffer))!=-1){total=add(total,n,max);if(hash!=null)hash.update(buffer,0,n);out.write(buffer,0,n);}return total;}
    private static long add(long a,long b,long max)throws IOException{if(b<0||a>max-b)throw new IOException("Ride backup exceeds its size limit.");return a+b;}
    private static byte[] read(File file,int max)throws IOException{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){copy(in,out,max,null);return out.toByteArray();}}
    private static String bounded(String value,int max){return value.length()<=max?value:value.substring(0,max);}
    static String identity(File file)throws IOException {
        Object value=Files.readAttributes(file.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        if(value==null||value.toString().isEmpty()||value.toString().length()>512)throw new IOException("Recording storage does not support safe restore recovery.");
        return value.toString();
    }
    private static void regular(File file)throws IOException{if(!file.isFile()||Files.isSymbolicLink(file.toPath()))throw new IOException("Unexpected recording file.");}
    private static void regularDirectory(File file)throws IOException{if(!file.isDirectory()||Files.isSymbolicLink(file.toPath()))throw new IOException("Ride backup storage is unavailable.");}
    private static void mkdir(File file)throws IOException{if(!file.isDirectory()&&!file.mkdirs())throw new IOException("Not enough available storage.");regularDirectory(file);}
    private static IOException asIo(Exception failure){return failure instanceof IOException?(IOException)failure:new IOException("The ride backup could not be processed.",failure);}
}
