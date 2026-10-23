package com.jjricks.volt;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Full raw roundtrip and hostile input / interrupted merge checks, all synthetic. */
public final class RideArchiveTest {
    private static int checks;
    private static final class Crash extends Error { }
    private static final class Storage implements RideArchive.Storage {
        final File root;String failSync;int copyCount,failCopy,crashCopy;String collidePath;
        Storage(File root)throws IOException{this.root=root;files().mkdirs();work().mkdirs();}
        public File files(){return new File(root,"files");}public File work(){return new File(root,"no-backup/ride-transfer");}
        public void sync(File directory)throws IOException {
            String name=directory.getName();File state=new File(directory,"state");
            if(state.isFile())name="state:"+new String(Files.readAllBytes(state.toPath()),"UTF-8");
            if(name.equals(failSync)){failSync=null;throw new IOException("Injected sync failure");}
            AppDataArchive.syncDirectory(directory);
        }
        public void copied(String path)throws IOException {copyCount++;if(copyCount==1&&collidePath!=null){File file=new File(files(),collidePath);file.getParentFile().mkdirs();Files.write(file.toPath(),new byte[]{7,8,9});}if(copyCount==crashCopy)throw new Crash();if(copyCount==failCopy)throw new IOException("Injected disk failure");}
    }
    private interface Action{void run()throws Exception;}
    private static void check(boolean good,String why){checks++;if(!good)throw new AssertionError(why);}
    private static void rejects(Action action,String why)throws Exception{checks++;try{action.run();throw new AssertionError(why);}catch(IOException expected){}}
    private static Map<String,String> profile(String id){return Collections.singletonMap(id,"Synthetic scooter");}
    private static Map<String,String> mapping(String source,String target){return Collections.singletonMap(source,target);}
    private static String ride(Storage storage,String profile,boolean phone)throws Exception {
        RideStore rides=new RideStore(new File(storage.files(),"rides"));rides.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
        String id=rides.start(profile,"Café ride, \"quoted\" 🛴",1000000,0);
        rides.setNotes("Synthetic note\nsecond line");
        for(int i=0;i<1250;i++){Map<String,RideStore.Sample> samples=new LinkedHashMap<>();samples.put("speed_kmh",new RideStore.Sample(12,i*1000));samples.put("odometer_km",new RideStore.Sample(i/1000d,i*1000));samples.put("battery_percent",new RideStore.Sample(80-i/100d,i*1000));rides.sample(1000000+i*1000,i*1000,samples);}
        rides.stop(2250000,1250000);rides.rename(id,"Renamed ride 🛴");
        if(phone){PhoneRideStore sensors=new PhoneRideStore(new File(storage.files(),"phone-rides"));sensors.start(id,1000000,0,"pocket");
            sensors.appendBatch(id,PhoneRideStore.row("GPS",1001000,1000,1,2,3,4,5,6,7),"1000,1,2,3,4,5,6,7,8,9,0,0,0,1,3\n");sensors.stop(id,2250000,1250000);}
        return id;
    }
    private static byte[] backup(Storage source)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();RideArchive.write(source,out,profile("source"));return out.toByteArray();}
    private static RideArchive.Prepared stage(Storage target,byte[] bytes)throws Exception{return RideArchive.stage(target,new ByteArrayInputStream(bytes));}
    private static void sameFiles(Storage a,Storage b,String id)throws Exception{for(String path:paths(id))check(Arrays.equals(Files.readAllBytes(new File(a.files(),path).toPath()),Files.readAllBytes(new File(b.files(),path).toPath())),"raw bytes preserved "+path);}
    private static String[] paths(String id){return new String[]{"rides/"+id+".csv","phone-rides/"+id+"-phone.csv","phone-rides/"+id+"-motion.csv"};}
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("volt-rides-test-").toFile();
        try {
            Storage source=new Storage(new File(root,"source"));String id=ride(source,"source",true);
            new File(source.files(),"charge-recordings").mkdirs();Files.write(new File(source.files(),"charge-recordings/private").toPath(),"PRIVATE_KEY_SENTINEL".getBytes("UTF-8"));
            byte[] bytes=backup(source);LinkedHashMap<String,byte[]> inventory=unzip(bytes);check(inventory.size()==4&&inventory.keySet().stream().allMatch(path->path.equals("manifest.bin")||RideArchive.allowed(path)),"only manifest and ride journals exported");
            Storage target=new Storage(new File(root,"target"));RideArchive.Prepared first=stage(target,bytes);
            check(first.rides.size()==1&&first.rides.get(0).sampleCount==1250,"full samples survive past preview cap");
            check(first.rides.get(0).label.equals("Renamed ride 🛴")&&first.rides.get(0).notes.contains("second line"),"names and notes preserved");
            check(!new File(target.files(),"rides/"+id+".csv").exists(),"staging cannot mutate live data");
            RideArchive.Plan plan=RideArchive.plan(target,first,mapping("source","source"));check(plan.added==1&&plan.identical==0&&plan.conflicts==0,"preview identifies new ride");RideArchive.apply(target,plan);sameFiles(source,target,id);
            rejects(()->RideArchive.apply(target,plan),"consumed approval cannot replay");
            RideArchive.Prepared again=stage(target,bytes);RideArchive.Plan duplicate=RideArchive.plan(target,again,mapping("source","source"));check(duplicate.added==0&&duplicate.identical==1,"identical restore skipped");RideArchive.apply(target,duplicate);
            new RideStore(new File(target.files(),"rides")).rename(id,"Keep my local name");
            RideArchive.Prepared old=stage(target,bytes);RideArchive.Plan conflict=RideArchive.plan(target,old,mapping("source","source"));check(conflict.conflicts==1&&conflict.added==0,"same UUID changed locally retained");RideArchive.apply(target,conflict);check(new RideStore(new File(target.files(),"rides")).get(id).label.equals("Keep my local name"),"conflict not overwritten");
            Storage mapped=new Storage(new File(root,"mapped"));RideArchive.Prepared remap=stage(mapped,bytes);
            rejects(()->RideArchive.plan(mapped,remap,Collections.emptyMap()),"unknown source requires explicit mapping");
            RideArchive.apply(mapped,RideArchive.plan(mapped,remap,mapping("source","new-owner")));
            RideStore.Summary mappedRide=new RideStore(new File(mapped.files(),"rides")).get(id);check(mappedRide.scooterId.equals("new-owner")&&mappedRide.sampleCount==1250,"mapping preserves samples");
            RideArchive.Prepared mappedAgain=stage(mapped,bytes);RideArchive.Plan mappedDup=RideArchive.plan(mapped,mappedAgain,mapping("source","new-owner"));check(mappedDup.identical==1&&mappedDup.added==0,"same archive remap repeated without duplicate training rides");RideArchive.apply(mapped,mappedDup);
            RideArchive.Prepared differentlyMapped=stage(mapped,bytes);RideArchive.Plan mappedConflict=RideArchive.plan(mapped,differentlyMapped,mapping("source","other"));check(mappedConflict.conflicts==1&&mappedConflict.added==0,"different requested association cannot duplicate UUID");RideArchive.discard(mapped,differentlyMapped);
            // Checksums authenticate bytes, not their meaning. Earlier START-like records may
            // be ignored by the journal reader, so reassociation must find the same valid START.
            LinkedHashMap<String,byte[]> remapFiles=unzip(bytes);remapFiles.remove("manifest.bin");
            String originalJournal=new String(remapFiles.get("rides/"+id+".csv"),"UTF-8");
            String ignoredStarts=startRecord("2","1000000","0","unsupported-scooter")
                    +startRecord("1","not-a-clock","0","invalid-wall-scooter")
                    +startRecord("1","1000000","9223372036854775808","invalid-mono-scooter");
            int headerEnd=originalJournal.indexOf('\n')+1;
            remapFiles.put("rides/"+id+".csv",(originalJournal.substring(0,headerEnd)+ignoredStarts+originalJournal.substring(headerEnd)).getBytes("UTF-8"));
            byte[] ignoredStartArchive=crafted(remapFiles,profile("source"));
            Storage remapIgnored=new Storage(new File(root,"mapped-ignored-starts"));RideArchive.Prepared ignoredPrepared=stage(remapIgnored,ignoredStartArchive);
            check(ignoredPrepared.rides.get(0).scooterId.equals("source"),"reader skips unsupported schema and malformed-clock START records");
            RideArchive.Plan ignoredPlan=RideArchive.plan(remapIgnored,ignoredPrepared,mapping("source","new-owner"));
            File stagedMapped=new File(ignoredPrepared.directory,"rides/"+id+".csv");
            check(RideStore.inspectJournal(stagedMapped).scooterId.equals("new-owner"),"staged reassociation changes the reader-accepted START before installation");
            check(new String(Files.readAllBytes(stagedMapped.toPath()),"UTF-8").contains(ignoredStarts),"ignored START records remain unchanged");
            RideArchive.apply(remapIgnored,ignoredPlan);
            RideStore.Summary ignoredMapped=RideStore.inspectJournal(new File(remapIgnored.files(),"rides/"+id+".csv"));
            check(ignoredMapped.scooterId.equals("new-owner")&&ignoredMapped.sampleCount==1250&&ignoredMapped.label.equals("Renamed ride 🛴")&&ignoredMapped.notes.contains("second line"),"accepted START remap preserves full ride and metadata");
            RideArchive.Plan ignoredRepeat=RideArchive.plan(remapIgnored,stage(remapIgnored,ignoredStartArchive),mapping("source","new-owner"));
            check(ignoredRepeat.identical==1&&ignoredRepeat.added==0&&ignoredRepeat.conflicts==0,"ignored START remap is idempotent on repeated restore");RideArchive.apply(remapIgnored,ignoredRepeat);
            Storage kept=new Storage(new File(root,"kept"));String existing=ride(kept,"source",false);RideArchive.apply(kept,RideArchive.plan(kept,stage(kept,bytes),mapping("source","source")));check(new RideStore(new File(kept.files(),"rides")).list().size()==2&&new File(kept.files(),"rides/"+existing+".csv").exists(),"unrelated rides preserved by merge");
            for(int boundary=1;boundary<=3;boundary++) {
                Storage broken=new Storage(new File(root,"broken"+boundary));String existingId=ride(broken,"source",false);broken.failCopy=boundary;
                RideArchive.Plan failure=RideArchive.plan(broken,stage(broken,bytes),mapping("source","source"));rejects(()->RideArchive.apply(broken,failure),"disk failure rejected");
                check(!new File(broken.files(),"rides/"+id+".csv").exists()&&new File(broken.files(),"rides/"+existingId+".csv").exists(),"rollback removes only additions");RideArchive.recover(broken);
            }
            Storage crash=new Storage(new File(root,"crash"));crash.crashCopy=2;try{RideArchive.apply(crash,RideArchive.plan(crash,stage(crash,bytes),mapping("source","source")));throw new AssertionError("expected crash");}catch(Crash expected){}
            check(RideArchive.needsRecovery(crash),"process death leaves recovery marker");RideArchive.recover(crash);for(String path:paths(id))check(!new File(crash.files(),path).exists(),"startup removes partially restored group");RideArchive.recover(crash);
            for(String failure:new String[]{"state:active","rides","state:committed"}) {
                Storage sync=new Storage(new File(root,"sync"+UUID.randomUUID()));sync.failSync=failure;
                RideArchive.Plan p=RideArchive.plan(sync,stage(sync,bytes),mapping("source","source"));rejects(()->RideArchive.apply(sync,p),"sync failure propagates "+failure);RideArchive.recover(sync);check(!new File(sync.files(),"rides/"+id+".csv").exists(),"sync failure rolls back "+failure);
            }
            Storage collision=new Storage(new File(root,"collision"));collision.collidePath="rides/"+id+".csv";
            RideArchive.Plan colliding=RideArchive.plan(collision,stage(collision,bytes),mapping("source","source"));
            rejects(()->RideArchive.apply(collision,colliding),"atomically refuses a newly appeared target");
            check(Arrays.equals(Files.readAllBytes(new File(collision.files(),collision.collidePath).toPath()),new byte[]{7,8,9}),"rollback never deletes or overwrites an unrelated target");
            RideArchive.recover(collision);
            Storage collisionCrash=new Storage(new File(root,"collision-crash"));collisionCrash.collidePath="rides/"+id+".csv";collisionCrash.crashCopy=1;
            try{RideArchive.apply(collisionCrash,RideArchive.plan(collisionCrash,stage(collisionCrash,bytes),mapping("source","source")));throw new AssertionError("expected crash");}catch(Crash expected){}
            RideArchive.recover(collisionCrash);
            check(Arrays.equals(Files.readAllBytes(new File(collisionCrash.files(),collisionCrash.collidePath).toPath()),new byte[]{7,8,9}),"startup rollback preserves an unrelated target after crash");
            Storage cancel=new Storage(new File(root,"cancel"));RideArchive.Prepared canceled=stage(cancel,bytes);RideArchive.discard(cancel,canceled);check(!RideArchive.needsRecovery(cancel),"cancel clears private staging");
            stage(cancel,bytes);RideArchive.recover(cancel);check(!RideArchive.needsRecovery(cancel),"startup clears abandoned staging");
            for(String path:new String[]{"../outside","rides/../../secret","/rides/"+id+".csv","rides/"+id+".csv/child","phone-rides/x-phone.csv","shared_prefs/profiles.xml"})check(!RideArchive.allowed(path),"reject traversal/unknown paths");
            LinkedHashMap<String,byte[]> content=unzip(bytes);byte[] changed=content.get("rides/"+id+".csv").clone();changed[changed.length-5]^=1;content.put("rides/"+id+".csv",changed);final byte[] tampered=zip(content);rejects(()->stage(cancel,tampered),"valid ZIP with mismatched hash rejected");
            content=unzip(bytes);content.put("../outside",new byte[]{1});final byte[] hostile=zip(content);rejects(()->stage(cancel,hostile),"unlisted traversal entry rejected");
            content=unzip(bytes);content.remove("phone-rides/"+id+"-phone.csv");final byte[] missing=zip(content);rejects(()->stage(cancel,missing),"missing listed sidecar rejected");
            LinkedHashMap<String,byte[]> full=new LinkedHashMap<>();full.put("manifest.bin","volt-full-app-data".getBytes("UTF-8"));rejects(()->stage(cancel,zip(full)),"full app backup is not imported as ride archive");
            Storage active=new Storage(new File(root,"active"));RideStore recording=new RideStore(new File(active.files(),"rides"));recording.start("source","Active",1,1);rejects(()->backup(active),"unfinished ride refused");
            LinkedHashMap<String,byte[]> raw=unzip(bytes);raw.remove("manifest.bin");
            String orphan="ffffffff-ffff-ffff-ffff-ffffffffffff";
            raw.put("phone-rides/"+orphan+"-motion.csv",raw.get("phone-rides/"+id+"-motion.csv"));
            final byte[] orphaned=crafted(raw,profile("source"));rejects(()->stage(cancel,orphaned),"authenticated orphan sidecar rejected");
            raw=unzip(bytes);raw.remove("manifest.bin");raw.put("rides/"+id+".csv",("wrong header\n").getBytes("UTF-8"));
            final byte[] schema=crafted(raw,profile("source"));rejects(()->stage(cancel,schema),"unsupported journal schema rejected");
            raw=unzip(bytes);raw.remove("manifest.bin");byte[] journal=raw.get("rides/"+id+".csv");raw.put("rides/"+id+".csv",Arrays.copyOf(journal,journal.length-1));
            final byte[] partial=crafted(raw,profile("source"));rejects(()->stage(cancel,partial),"incomplete final record rejected");
            raw=unzip(bytes);raw.remove("manifest.bin");raw.put("rides/"+id+".csv",new byte[]{(byte)0xff,10});
            final byte[] invalidUtf8=crafted(raw,profile("source"));rejects(()->stage(cancel,invalidUtf8),"malformed UTF8 rejected");
            raw=unzip(bytes);raw.remove("manifest.bin");String live=new String(raw.get("rides/"+id+".csv"),"UTF-8");live=live.replaceAll("(?m)^1,STOP,.*\\n","");raw.put("rides/"+id+".csv",live.getBytes("UTF-8"));
            final byte[] unfinished=crafted(raw,profile("source"));rejects(()->stage(cancel,unfinished),"crafted unfinished ride cannot become active recording");
            check(!RideArchive.needsRecovery(cancel),"invalid archives leave no private staging");
            System.out.println("RideArchiveTest: "+checks+" checks passed (full journals, idempotent merge, mapping, corruption, rollback and recovery)");
        } finally {AppDataArchive.deleteTree(root);}
    }
    private static String startRecord(String schema,String wall,String mono,String profile) {
        String[] fields=new String[15];Arrays.fill(fields,"");fields[0]=schema;fields[1]="START";fields[3]=wall;fields[4]=mono;fields[6]=profile;
        return String.join(",",fields)+"\n";
    }
    private static byte[] crafted(LinkedHashMap<String,byte[]> files,Map<String,String> profiles)throws Exception {
        ByteArrayOutputStream raw=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(raw);
        out.writeUTF("volt-ride-backup");out.writeInt(1);out.writeLong(1);out.writeInt(profiles.size());
        for(Map.Entry<String,String> p:profiles.entrySet()){out.writeUTF(p.getKey());out.writeUTF(p.getValue());}
        out.writeInt(files.size());long size=0;for(byte[] bytes:files.values())size+=bytes.length;out.writeLong(size);
        for(Map.Entry<String,byte[]> file:files.entrySet()){out.writeUTF(file.getKey());out.writeLong(file.getValue().length);out.write(java.security.MessageDigest.getInstance("SHA-256").digest(file.getValue()));}
        out.flush();LinkedHashMap<String,byte[]> all=new LinkedHashMap<>();all.put("manifest.bin",raw.toByteArray());all.putAll(files);return zip(all);
    }
    private static LinkedHashMap<String,byte[]> unzip(byte[] bytes)throws Exception{LinkedHashMap<String,byte[]> result=new LinkedHashMap<>();try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=in.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);result.put(e.getName(),out.toByteArray());}}return result;}
    private static byte[] zip(Map<String,byte[]> files)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(Map.Entry<String,byte[]> e:files.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}return bytes.toByteArray();}
}
