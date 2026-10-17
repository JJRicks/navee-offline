package local.navee.offlinelock;

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
        roundTrip(); atomic(); corrupt(); unreadable(); bounds(); prune(); forbidden(); fileStorage();
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
