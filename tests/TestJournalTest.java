package com.jjricks.volt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** The guided-test journal: atomic commits, tolerant loading, bounds, pruning and no forbidden fields. */
public final class TestJournalTest {
    private static int assertions;
    private static final long WALL=1700000000000L,DAY=24L*3600*1000;
    public static void main(String[] args) throws Exception {
        roundTrip(); atomic(); corrupt(); unreadable(); bounds(); prune(); forbidden(); fileStorage(); versionOne(); kinds(); retention();
        System.out.println("TestJournal tests passed: "+assertions+" assertions");
    }

    static TestJournal.Entry entry(String id,boolean resolved,long wall){
        TestJournal.Entry e=new TestJournal.Entry();
        e.testId=id;e.profileId="owner-1";e.feature="display_units";e.variantId="display_units.ut5";e.pidPrefix="2538";e.pidSource="serial";e.meterFw="2.0.3.1";
        e.registryVersion=ModelRegistry.VERSION;e.baseline=1;e.target=0;e.phase=resolved?"DONE":"OUTSTANDING";e.effect="observed";
        e.restoration=resolved?"confirmed":"outstanding";e.outcomes=new ArrayList<>(Arrays.asList("setting_confirmed","rejected_3"));e.elapsedMs=new ArrayList<>(Arrays.asList(400L,900L));
        e.createdWall=wall;e.updatedWall=wall;e.resolved=resolved;return e;
    }
    static String id(int n){return String.format(Locale.ROOT,"%016x",n+1);}

    private static void roundTrip() throws IOException {
        GuidedTestTest.Memory m=new GuidedTestTest.Memory();TestJournal j=new TestJournal(m);
        check(j.healthy()&&j.unresolved().isEmpty()&&j.canStart(),"Empty journal");
        j.commit(entry(id(1),false,WALL));j.commit(entry(id(2),true,WALL+1));
        check(m.text.startsWith(TestJournal.HEADER+"\n")&&m.text.split("\n").length==3,"Header plus one line per entry\n"+m.text);
        TestJournal back=new TestJournal(m);
        check(back.unresolved("owner-1").size()==1&&back.all("owner-1").size()==2&&back.dropped()==0,"Reloaded");
        TestJournal.Entry e=back.find(id(1));
        check(e.baseline==1&&e.target==0&&e.outcomes.equals(Arrays.asList("setting_confirmed","rejected_3"))&&e.elapsedMs.equals(Arrays.asList(400L,900L))&&e.meterFw.equals("2.0.3.1")&&!e.resolved,"Fields survive");
        check(TestJournal.serialize(Arrays.asList(e,back.find(id(2)))).equals(m.text),"Stable serialization");
        // Updating replaces by test id.
        e.phase="CONFLICT";back.commit(e);check(back.all("owner-1").size()==2&&back.find(id(1)).phase.equals("CONFLICT"),"Update in place");
        back.resolve(id(1),TestJournal.DECLINED,"kept",WALL+5);
        check(back.find(id(1)).resolved&&back.find(id(1)).restoration.equals("declined")&&back.unresolved().isEmpty(),"Resolve");
        try{back.resolve(id(2),"restored",null,WALL);check(false,"Only confirmed or declined");}catch(IllegalArgumentException expected){check(true,"Only confirmed or declined");}
        check(back.unresolved("someone-else").isEmpty(),"Per profile");
    }

    private static void atomic() throws IOException {
        GuidedTestTest.Memory m=new GuidedTestTest.Memory();TestJournal j=new TestJournal(m);j.commit(entry(id(1),false,WALL));
        String before=m.text;m.failWrites=true;
        try{j.commit(entry(id(2),false,WALL));check(false,"Failed write throws");}catch(IOException expected){check(true,"Failed write throws");}
        check(j.find(id(2))==null&&j.all("owner-1").size()==1&&m.text.equals(before),"A failed commit changes nothing");
        TestJournal.Entry e=j.find(id(1));e.phase="CONFLICT";
        try{j.commit(e);}catch(IOException expected){}
        check(j.find(id(1)).phase.equals("OUTSTANDING"),"A failed update keeps the old phase");
        m.failWrites=false;
        TestJournal.Entry bad=entry(id(3),false,WALL);bad.feature="maximum_speed";bad.variantId="maximum_speed.ut5";
        try{j.commit(bad);check(false,"Invalid entry refused");}catch(IllegalArgumentException expected){check(true,"Invalid entry refused");}
        check(m.text.equals(before),"Nothing written for an invalid entry");
    }

    private static void corrupt() throws IOException {
        GuidedTestTest.Memory m=new GuidedTestTest.Memory();TestJournal j=new TestJournal(m);
        j.commit(entry(id(1),false,WALL));j.commit(entry(id(2),false,WALL));
        String good=m.text;String[] lines=good.split("\n");
        String flipped=lines[1].replace("baseline=1","baseline=0");
        String truncated=lines[2].substring(0,lines[2].length()/2);
        m.text=lines[0]+"\n"+flipped+"\n"+lines[2]+"\n"+"garbage line\n"+truncated;
        TestJournal back=new TestJournal(m);
        check(back.healthy()&&back.all("owner-1").size()==1&&back.find(id(2))!=null&&back.dropped()==3,"Corrupt lines dropped, valid entries kept: dropped "+back.dropped());
        m.text=lines[0]+"\n"+lines[1]+"\n"+lines[1]+"\n";
        check(new TestJournal(m).all("owner-1").size()==1,"Duplicate test id dropped");
        m.text="volt-compat-tests v9\n"+lines[1]+"\n";
        TestJournal future=new TestJournal(m);check(future.all("owner-1").isEmpty()&&!future.healthy()&&!future.canStart(),"Unknown version: read-only, never overwritten");
        try{future.commit(entry(id(5),false,WALL));check(false,"Unknown version not overwritten");}catch(IOException expected){check(m.text.startsWith("volt-compat-tests v9"),"Unknown version not overwritten");}
        // A line whose checksum is right but whose values are out of range is still dropped.
        TestJournal.Entry e=entry(id(4),false,WALL);e.baseline=300;
        m.text=TestJournal.HEADER+"\n"+TestJournal.line(e)+"\n";
        check(new TestJournal(m).all("owner-1").isEmpty(),"Out-of-range value dropped");
        m.text="";check(new TestJournal(m).healthy(),"Empty file is an empty journal");
    }

    private static void unreadable() {
        GuidedTestTest.Memory m=new GuidedTestTest.Memory();m.failReads=true;TestJournal j=new TestJournal(m);
        check(!j.healthy()&&!j.canStart(),"Unreadable journal: no tests");
        // It can list nothing, so the app shows the unavailable note in place of unfinished tests; the note must say one may be outstanding.
        check(j.unresolved().isEmpty()&&TestJournal.UNAVAILABLE_NOTE.contains("may not have been set back"),"Unreadable journal: possible unfinished test reported");
        try{j.commit(entry(id(1),false,WALL));check(false,"No overwrite of an unreadable journal");}catch(IOException expected){check(m.writes==0,"No overwrite of an unreadable journal");}
    }

    private static void bounds() throws IOException {
        GuidedTestTest.Memory m=new GuidedTestTest.Memory();TestJournal j=new TestJournal(m);
        for(int i=0;i<TestJournal.MAX_ENTRIES;i++)j.commit(entry(id(i),false,WALL+i));
        check(!j.canStart(),"Twenty unresolved: no new test");
        try{j.commit(entry(id(99),false,WALL+99));check(false,"Unresolved entries are never evicted");}catch(IOException expected){check(j.find(id(99))==null&&j.all("owner-1").size()==20,"Unresolved entries are never evicted");}
        TestJournal.Entry e=j.find(id(5));e.phase="CONFLICT";j.commit(e);check(j.find(id(5)).phase.equals("CONFLICT"),"An existing entry can always be updated");
        j.resolve(id(0),TestJournal.CONFIRMED,"restored",WALL+100);j.resolve(id(1),TestJournal.CONFIRMED,"restored",WALL+101);
        j.commit(entry(id(50),false,WALL+200));
        check(j.all("owner-1").size()==20&&j.find(id(0))==null&&j.find(id(1))!=null&&j.find(id(50))!=null,"The oldest resolved entry makes room");
        check(m.text.split("\n").length==21,"Bounded on disk");
    }

    private static void prune() throws IOException {
        GuidedTestTest.Memory m=new GuidedTestTest.Memory();TestJournal j=new TestJournal(m);
        j.commit(entry(id(1),true,WALL));j.commit(entry(id(2),false,WALL));j.commit(entry(id(3),true,WALL+20*DAY));
        j.commit(entry(id(4),false,WALL+31*DAY));
        check(j.find(id(1))==null&&j.find(id(2))!=null&&j.find(id(3))!=null&&j.find(id(4))!=null,"Resolved entries older than 30 days pruned; unresolved kept");
    }

    private static void forbidden() throws IOException {
        String serial="Q2443ZZ9US7777777",mac="C0:FF:EE:12:34:56",identity="A1B2C3D4E5F6",name="Alice's scooter";
        GuidedTestTest.Memory m=new GuidedTestTest.Memory();TestJournal j=new TestJournal(m);
        String[][] attempts={{"meterFw",serial},{"meterFw",mac},{"variantId",mac},{"profileId",name},{"resolution",identity},{"pidPrefix","24431"},{"pidSource","mac"},
            {"testId",identity},{"effect","seen it"},{"feature","pin"},{"variantId","turn_sound.oem"},{"profileId","owner\tx=1"},{"resolution","kept\nentry"}};
        for(String[] a:attempts){
            TestJournal.Entry e=entry(id(7),false,WALL);
            switch(a[0]){
                case "meterFw": e.meterFw=a[1]; break; case "variantId": e.variantId=a[1]; break; case "profileId": e.profileId=a[1]; break;
                case "resolution": e.resolution=a[1]; break; case "pidPrefix": e.pidPrefix=a[1]; break; case "pidSource": e.pidSource=a[1]; break;
                case "testId": e.testId=a[1]; break; case "effect": e.effect=a[1]; break; default: e.feature=a[1]; e.variantId=a[1]+".x";
            }
            try{j.commit(e);check(false,"Refused "+a[0]+"="+a[1]);}catch(IllegalArgumentException expected){check(true,"Refused "+a[0]);}
        }
        TestJournal.Entry outcome=entry(id(8),false,WALL);outcome.outcomes=new ArrayList<>(Arrays.asList("deadbeef"));outcome.elapsedMs=new ArrayList<>(Arrays.asList(1L));
        try{j.commit(outcome);check(false,"Raw bytes refused as outcomes");}catch(IllegalArgumentException expected){check(true,"Raw bytes refused as outcomes");}
        j.commit(entry(id(9),false,WALL));
        String low=m.text.toLowerCase(Locale.ROOT),compact=low.replaceAll("[^0-9a-z]","");
        for(String secret:new String[]{serial,"7777777","c0ffee123456",identity,"alice","4321","deadbeef"})
            check(!low.contains(secret.toLowerCase(Locale.ROOT))&&!compact.contains(secret.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z]","")),"No "+secret+" in the journal");
        check(m.writes==1,"Only the valid entry was written");
    }

    static TestJournal.Entry tryEntry(String id,String kind,String feature,String variant,boolean resolved,long wall){
        TestJournal.Entry e=entry(id,resolved,wall);e.kind=kind;e.feature=feature;e.variantId=variant;e.layer="oem_group";e.pidPrefix="2213";e.pidSource="advertised";
        e.restoration=resolved?"declined":"outstanding";e.resolution=resolved?"kept":"";return e;
    }
    /** A journal an older Volt wrote (version 1) loads as guided tests and is never stranded; the next commit writes version 2. */
    private static void versionOne() throws IOException {
        TestJournal.Entry old=entry(id(1),false,WALL);
        String body=TestJournal.line(old);
        // Rebuild the line as version 1 wrote it: the same keys without kind, layer and reported, and its own checksum.
        String v1=body.substring(0,body.indexOf("\tkind="));
        java.util.zip.CRC32 c=new java.util.zip.CRC32();c.update(v1.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        v1=v1+"\tcrc="+String.format(Locale.ROOT,"%08x",c.getValue());
        GuidedTestTest.Memory m=new GuidedTestTest.Memory();m.text=TestJournal.HEADER_V1+"\n"+v1+"\n";
        TestJournal j=new TestJournal(m);
        TestJournal.Entry back=j.find(id(1));
        check(j.healthy()&&back!=null&&back.kind.equals(TestJournal.TEST)&&!back.isTry()&&back.layer.isEmpty()&&!back.reported&&j.unresolved("owner-1").size()==1,"Version 1 entry loads as an unfinished guided test");
        check(TestJournal.parse(v1)==null&&TestJournal.parse(v1,new String[0])==null,"A version 1 line is read only under its own header");
        j.commit(entry(id(2),true,WALL));
        check(m.text.startsWith(TestJournal.HEADER+"\n")&&new TestJournal(m).find(id(1))!=null,"Rewritten as version 2 with the old entry kept");
    }
    /** Kinds: tries of Experimental controls' keys, Help verify only for the guided-test table, guided tests as before. */
    private static void kinds() throws IOException {
        GuidedTestTest.Memory m=new GuidedTestTest.Memory();TestJournal j=new TestJournal(m);
        j.commit(tryEntry(id(1),TestJournal.EXPERIMENTAL,"start_speed","start_speed.0_5.lab",true,WALL));
        j.commit(tryEntry(id(2),TestJournal.VERIFY,"range_algorithm","range_algorithm.oem_personal",true,WALL+1));
        j.commit(tryEntry(id(3),TestJournal.EXPERIMENTAL,"speed_limit","speed_limit.ut5",false,WALL+2));
        TestJournal back=new TestJournal(m);
        check(back.find(id(1)).isTry()&&back.find(id(1)).kind.equals("experimental")&&back.find(id(1)).layer.equals("oem_group")&&back.find(id(2)).kind.equals("verify"),"Kinds and layer survive");
        check(back.latestTry("owner-1","start_speed").testId.equals(id(1))&&back.latestTry("owner-1","turn_sound")==null&&back.latestTry("other","start_speed")==null,"Latest try per profile and control");
        String[][] invalid={{TestJournal.TEST,"start_speed","start_speed.0_5.lab"},{TestJournal.VERIFY,"start_speed","start_speed.0_5.lab"},{TestJournal.EXPERIMENTAL,"display_units","display_units.oem"},
            {TestJournal.EXPERIMENTAL,"maximum_speed","maximum_speed.ut5"},{TestJournal.EXPERIMENTAL,"charge_limit","charge_limit.oem"},{TestJournal.EXPERIMENTAL,"pin","pin.oem"},
            {TestJournal.EXPERIMENTAL,"lock","lock.shared"},{"guess","turn_sound","turn_sound.oem"}};
        for(String[] bad:invalid){
            try{j.commit(tryEntry(id(9),bad[0],bad[1],bad[2],true,WALL));check(false,"Refused "+java.util.Arrays.toString(bad));}catch(IllegalArgumentException expected){check(true,"Refused "+bad[0]+" "+bad[1]);}
        }
        TestJournal.Entry layer=tryEntry(id(8),TestJournal.EXPERIMENTAL,"turn_sound","turn_sound.oem.lab",true,WALL);layer.layer="Q2213ZZ9US";
        try{j.commit(layer);check(false,"Layer is a known name");}catch(IllegalArgumentException expected){check(true,"Layer is a known name");}
        check(j.all("owner-1").size()==3,"Only the valid tries were kept");
    }
    /** Results stay until a sent report includes them or the owner clears them; unfinished tries always stay. */
    private static void retention() throws IOException {
        GuidedTestTest.Memory m=new GuidedTestTest.Memory();TestJournal j=new TestJournal(m);
        j.commit(tryEntry(id(1),TestJournal.EXPERIMENTAL,"turn_sound","turn_sound.oem.lab",true,WALL));
        j.commit(tryEntry(id(2),TestJournal.EXPERIMENTAL,"traction_control","traction_control.oem.lab",true,WALL));
        j.commit(tryEntry(id(3),TestJournal.EXPERIMENTAL,"start_speed","start_speed.0_5.lab",false,WALL));
        j.commit(entry(id(4),true,WALL));
        j.markReported(Arrays.asList(TestJournal.revision(j.find(id(2)))),WALL+1);
        check(j.find(id(2)).reported&&!j.find(id(1)).reported&&new TestJournal(m).find(id(2)).reported,"Reported is saved");
        // A report acknowledges the revision it included: a bare ID or an older record's revision marks nothing.
        String old=TestJournal.revision(j.find(id(1)));TestJournal.Entry moved=j.find(id(1));TestJournal.append(moved,"restoration_confirmed",700);j.commit(moved);
        j.markReported(Arrays.asList(id(1),old),WALL+1);
        check(!j.find(id(1)).reported,"A bare ID or an outdated revision is not acknowledged");
        check(j.revisions("owner-1").contains(TestJournal.revision(j.find(id(1))))&&!j.revisions("owner-1").contains(old)&&j.revisions("owner-1").size()==4,"Current revisions per profile");
        // Reported means "as a sent report saw it": the same record keeps the flag, new evidence clears it, a commit never sets it.
        TestJournal.Entry same=j.find(id(2));same.updatedWall=WALL+2;j.commit(same);
        check(j.find(id(2)).reported,"An unchanged record stays reported");
        TestJournal.Entry later=j.find(id(2));TestJournal.append(later,"restoration_confirmed",5000);later.resolution="restored";later.restoration="confirmed";j.commit(later);
        check(!j.find(id(2)).reported&&!new TestJournal(m).find(id(2)).reported,"New evidence is unreported again");
        TestJournal.Entry claimed=j.find(id(1));claimed.reported=true;j.commit(claimed);
        check(!j.find(id(1)).reported,"Only markReported marks an entry reported");
        j.markReported(Arrays.asList(TestJournal.revision(j.find(id(2)))),WALL+3);
        j.commit(entry(id(5),false,WALL+31*DAY));
        check(j.find(id(1))!=null&&j.find(id(2))==null&&j.find(id(3))!=null&&j.find(id(4))==null,"After 30 days: unreported tries stay, reported ones and old tests go, unfinished stay");
        // Over the limit the oldest reported resolved entry goes before an unreported one.
        GuidedTestTest.Memory m2=new GuidedTestTest.Memory();TestJournal full=new TestJournal(m2);
        for(int i=0;i<TestJournal.MAX_ENTRIES;i++)full.commit(tryEntry(id(10+i),TestJournal.EXPERIMENTAL,"turn_sound","turn_sound.oem.lab",true,WALL+i));
        full.markReported(Arrays.asList(TestJournal.revision(full.find(id(15)))),WALL+100);
        full.commit(tryEntry(id(60),TestJournal.EXPERIMENTAL,"turn_sound","turn_sound.oem.lab",true,WALL+200));
        check(full.find(id(15))==null&&full.find(id(10))!=null&&full.find(id(60))!=null&&full.all("owner-1").size()==TestJournal.MAX_ENTRIES,"A reported try makes room first");
        full.commit(tryEntry(id(61),TestJournal.EXPERIMENTAL,"turn_sound","turn_sound.oem.lab",true,WALL+300));
        check(full.find(id(10))==null&&full.find(id(61))!=null,"Then the oldest unreported one");
        // Clearing: finished tries of this profile go, unfinished tries and guided tests stay.
        check(j.clearTries("owner-1")==1&&j.find(id(1))==null&&j.find(id(3))!=null&&j.find(id(5))!=null&&j.clearTries("owner-1")==0,"Clear keeps unfinished tries and tests");
        check(!m.text.contains(id(1)),"Cleared from disk");
    }

    private static void fileStorage() throws IOException {
        File dir=Files.createTempDirectory("volt-journal").toFile();
        try{
            File f=new File(dir,"compat-tests");TestJournal.Storage s=TestJournal.file(f);
            check(s.read()==null,"Missing file reads as nothing");
            TestJournal j=new TestJournal(s);j.commit(entry(id(1),false,WALL));
            check(f.isFile()&&!new File(dir,"compat-tests.tmp").exists(),"Renamed into place, no temporary left");
            check(new TestJournal(TestJournal.file(f)).find(id(1))!=null,"Read back from disk");
            j.commit(entry(id(2),false,WALL));check(new TestJournal(TestJournal.file(f)).all("owner-1").size()==2,"Replaced atomically");
        }finally{for(File c:dir.listFiles())c.delete();dir.delete();}
    }

    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
