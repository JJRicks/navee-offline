package local.navee.offlinelock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** The backup registry must classify every preference key, namespace and storage root the app writes. */
public final class BackupRegistryTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action,String message)throws Exception{checks++;try{action.run();throw new AssertionError(message);}catch(IOException expected){}}
    /** Reviewed non-preference keys: Activity Bundle state, JSON fields read with typed getters,
     * and the two private namespaces' keys (never read by backups). */
    private static final Set<String> IGNORED=new HashSet<>(Arrays.asList(
                "path","name","mime","ready","picker",                                     // pending_documents
        "pending","token","original"));                                            // owner_capture_cleanup
    private static final Pattern CALL=Pattern.compile("\\b(?:get|put)(String|Int|Boolean|Long|Float|StringSet)\\(\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"\\s*(\\+)?|([A-Za-z_][A-Za-z0-9_]*\\.)?([A-Z][A-Z0-9_]+)\\s*(\\+)?)");
    private static final Pattern CONSTANT=Pattern.compile("([A-Z][A-Z0-9_]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern NAMESPACE=Pattern.compile("getSharedPreferences\\(\\s*(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_.]*))");
    private static final Pattern ROOT=Pattern.compile("get(Files|NoBackupFiles|Cache)Dir\\(\\)\\s*,\\s*\"([^\"]+)\"\\s*(\\+)?|getDatabasePath\\(\\s*(?:\"([^\"]+)\"|([A-Z][A-Z0-9_.]*))");
    private static final Pattern JSON_RECEIVER=Pattern.compile("\\b(?:o|data|snapshot|saved|json|widget|slot|item|object|root)\\.$");

    public static void main(String[] args)throws Exception {
        rules();
        File source=new File(args.length>0?args[0]:"src/local/navee/offlinelock");
        Map<String,String> files=new TreeMap<>();
        File[] list=source.listFiles((dir,name)->name.endsWith(".java"));check(list!=null&&list.length>40,"application source is available for the registry scan");
        for(File file:list)files.put(file.getName(),new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        List<String> problems=scan(files);
        check(problems.isEmpty(),"every preference key, namespace and storage root is classified; "+String.join("; ",problems));
        // A registered key the current source never writes (another branch's key) is not a failure.
        check(BackupRegistry.rule("app_settings","ride_auto_stop")!=null&&BackupRegistry.PREFERENCES.equals(BackupRegistry.category("app_settings","ride_auto_stop")),"ride_auto_stop is registered with App preferences");
        Map<String,String> planted=new TreeMap<>(files);
        planted.put("Planted.java","class Planted{void x(android.content.SharedPreferences p){p.edit().putString(\"brand_new_key\",\"x\").putBoolean(NEW_FLAG,true).apply();p.getInt(\"fresh_prefix_\"+3,0);}static final String NEW_FLAG = \"another_new_flag\";android.content.SharedPreferences q(android.content.Context c){return c.getSharedPreferences(\"new_namespace\",0);}java.io.File r(android.content.Context c){return new java.io.File(c.getFilesDir(),\"new-folder\");}}");
        List<String> found=scan(planted);
        for(String expected:new String[]{"brand_new_key","another_new_flag","fresh_prefix_","new_namespace","files/new-folder"}){
            boolean named=false;for(String problem:found)named|=problem.contains(expected)&&problem.contains("assign it in BackupRegistry");
            check(named,"an unassigned "+expected+" in source fails, naming the key");
        }
        types();
        System.out.println("BackupRegistryTest: "+checks+" checks passed (rule overlap, source scan of keys/namespaces/roots, types)");
    }

    private static void rules()throws Exception {
        List<BackupRegistry.Rule> rules=BackupRegistry.rules();
        for(BackupRegistry.Rule rule:rules) {
            check(BackupRegistry.namespace(rule.namespace),"rule namespace is a backup namespace: "+rule.namespace);
            check(rule.type!=null,"rule has a type: "+rule.key);
            check(rule.excluded()?rule.reason!=null&&!rule.reason.isEmpty():BackupRegistry.known(rule.category),"rule has one category or an excluded reason: "+rule.key);
            check(!rule.slot||rule.perProfile,"slot rules are per scooter");
            for(BackupRegistry.Rule other:rules)if(other!=rule&&other.namespace.equals(rule.namespace)) {
                boolean overlap=rule.prefix&&other.prefix?(rule.key.startsWith(other.key)||other.key.startsWith(rule.key)):rule.prefix?other.key.startsWith(rule.key)&&other.key.length()>rule.key.length():other.prefix?rule.key.startsWith(other.key)&&rule.key.length()>other.key.length():rule.key.equals(other.key);
                check(!overlap,"no key matches two rules: "+rule.namespace+"/"+rule.key+" and "+other.key);
            }
        }
        for(String category:BackupRegistry.CATEGORIES){boolean used=false;for(BackupRegistry.Rule rule:rules)used|=category.equals(rule.category);
            check(used||category.equals(BackupRegistry.CHARGES)||category.equals(BackupRegistry.HISTORY),"preference category has rules: "+category);}
        check(BackupRegistry.CATEGORIES.length==BackupRegistry.LABELS.length&&new HashSet<>(Arrays.asList(BackupRegistry.CATEGORIES)).size()==7,"seven stable category IDs with labels");
        BackupRegistry.Rule slot=BackupRegistry.rule("app_settings","dashboard_slot_v1_abc-1_2");
        check(slot!=null&&"abc-1".equals(slot.profile("dashboard_slot_v1_abc-1_2"))&&slot.rename("dashboard_slot_v1_abc-1_2","new").equals("dashboard_slot_v1_new_2"),"slot keys carry the profile and keep the slot number on rename");
        check(BackupRegistry.rule("app_settings","dashboard_v1_p").rename("dashboard_v1_p","q").equals("dashboard_v1_q"),"per-scooter dashboard key renames");
        check("x".equals(BackupRegistry.rule("control_evidence","x").profile("x")),"control evidence keys are profile IDs");
        check(BackupRegistry.rule("app_settings","legacy_removed_key")==null,"unknown keys are unassigned");
        check(BackupRegistry.rule("app_settings","dashboard_restore_undo_v1_p").excluded()&&BackupRegistry.rule("onboarding","tick_x").excluded()&&BackupRegistry.rule("onboarding","onboarding_done").category.equals(BackupRegistry.SCOOTERS),"pending and wizard keys are excluded; setup completion travels with Scooters");
        check(BackupRegistry.rule("scooter","identity").replaceOnly&&!BackupRegistry.rule("profiles","items").replaceOnly,"legacy setup restores only by Replace everything");
        for(String key:"text_scale background_connection keep_screen_awake poll_interval_ms map_basemap_enabled map_basemap_explained".split(" "))check(BackupRegistry.PREFERENCES.equals(BackupRegistry.category("app_settings",key)),"device-sensitive and map choices stay in App preferences: "+key);
        check(BackupRegistry.SCOOTER_PREFERENCES.equals(BackupRegistry.category("control_evidence","p")),"control evidence travels with Scooter preferences");
        check(BackupRegistry.RIDES.equals(BackupRegistry.root("files/rides"))&&BackupRegistry.HISTORY.equals(BackupRegistry.root("databases/telemetry-history.db"))&&BackupRegistry.root("files/pending-export-123")==null&&BackupRegistry.root("cache/map-tiles")==null,"storage roots are classified");
        checks++;try{BackupRegistry.root("files/surprise");throw new AssertionError("unclassified root accepted");}catch(IllegalArgumentException expected){}
    }

    private static void types()throws Exception {
        Map<String,Map<String,Object>> prefs=new LinkedHashMap<>();for(String n:BackupRegistry.NAMESPACES)prefs.put(n,new LinkedHashMap<>());
        prefs.get("app_settings").put("theme",3);rejects(()->BackupRegistry.validateTypes(prefs),"wrong startup theme type rejected");
        prefs.get("app_settings").clear();prefs.get("app_settings").put("dashboard_slot_v1_p_0",true);rejects(()->BackupRegistry.validateTypes(prefs),"wrong slot type rejected");
        prefs.get("app_settings").clear();prefs.get("app_settings").put("text_scale",9f);rejects(()->BackupRegistry.validateTypes(prefs),"text scale outside its range rejected");
        prefs.get("app_settings").clear();prefs.get("app_settings").put("ride_auto_stop","yes");rejects(()->BackupRegistry.validateTypes(prefs),"sibling ride_auto_stop flag is type-checked");
        prefs.get("app_settings").clear();prefs.get("onboarding").put("tick_usb","x");rejects(()->BackupRegistry.validateTypes(prefs),"excluded keys are still type-checked");
        prefs.get("onboarding").clear();prefs.get("app_settings").put("anything_new",new LinkedHashSet<>(Arrays.asList("a")));prefs.get("app_settings").put("ride_auto_stop",true);
        BackupRegistry.validateTypes(prefs);check(true,"unknown keys of any type stay portable");
        Map<String,Map<String,Object>> extra=new LinkedHashMap<>(prefs);extra.put("pending_documents",new LinkedHashMap<>());
        rejects(()->BackupRegistry.validateTypes(extra),"private namespaces are never accepted from a backup");
        String items="[{\"id\":\"a\",\"name\":\"One\",\"identity\":\"ABCDEF012345\",\"role\":0,\"mac\":\"02:00\",\"serial\":\"S1\",\"lastConnected\":5,\"pid_advertised\":2222,\"pid_advertised_wall\":17,\"name_default\":true,\"fw_meter\":\"1.0\",\"fw_controller\":\"2\",\"fw_bms\":\"3\",\"fw_screen\":\"4\",\"fw_uwb\":\"5\",\"fw_wall\":6,\"future\":{\"nested\":[1,2]}} , {\"id\":\"b\"}]";
        List<BackupProfiles.Profile> parsed=BackupProfiles.parse(items);
        check(parsed.size()==2&&parsed.get(0).configured()&&parsed.get(0).raw.contains("fw_wall")&&!parsed.get(1).configured()&&parsed.get(1).name.equals("UT5 MAX"),"additive compat fields are tolerated and kept in the exact item text");
        check(BackupProfiles.join(parsed.subList(0,1),parsed.subList(1,2)).equals("["+parsed.get(0).raw+","+parsed.get(1).raw+"]"),"items are spliced without rewriting");
        for(String bad:new String[]{"[{\"id\":\"a\",\"role\":2}]","[{\"id\":\"a\",\"name\":7}]","[{\"id\":\"a\"},{\"id\":\"a\"}]","[{\"id\":\"\"}]","[{\"id\":\"a\",\"identity\":\"XYZ\"}]","[{\"id\":\"a\",\"lastConnected\":\"x\"}]","[[[[[[[[[[1]]]]]]]]]]","[{\"id\":\"a\"}","[{\"id\":\"a\",\"id\":\"b\"}]","{\"id\":\"a\"}","[{\"id\":\"a\"}] x"})
            rejects(()->BackupProfiles.parse(bad),"malformed profile list rejected: "+bad);
        rejects(()->BackupProfiles.parse(7),"profile list must be text");
        check(BackupProfiles.parse(null).isEmpty(),"absent profile list is empty");
        BackupProfiles.Profile x=new BackupProfiles.Profile("x","","A",0,"M","S1",""),y=new BackupProfiles.Profile("y","","B",0,"N","s1",""),z=new BackupProfiles.Profile("z","","a",0,"m","","");
        check(x.sameScooter(y)&&!x.sameScooter(z)&&z.sameScooter(new BackupProfiles.Profile("w","","A",0,"M","","")),"scooters match by serial, else by identity and address");
    }

    /** Problems for every key, namespace or root used in source and absent from the registry. */
    static List<String> scan(Map<String,String> files) {
        Map<String,String> constants=new HashMap<>();
        for(Map.Entry<String,String> file:files.entrySet()) {
            String text=file.getValue();Matcher declarations=Pattern.compile("static\\s+final\\s+String\\s+([^;]+);").matcher(text);
            while(declarations.find()){Matcher pair=CONSTANT.matcher(declarations.group(1));while(pair.find()){String clazz=file.getKey().replace(".java","");constants.put(clazz+"."+pair.group(1),pair.group(2));constants.putIfAbsent(file.getKey()+":"+pair.group(1),pair.group(2));}}
        }
        Set<String> problems=new TreeSet<>();
        for(Map.Entry<String,String> file:files.entrySet()) {
            String text=file.getValue(),clazz=file.getKey().replace(".java","");
            Matcher ns=NAMESPACE.matcher(text);
            while(ns.find()){String name=ns.group(1)!=null?ns.group(1):resolve(constants,file.getKey(),ns.group(2));
                if(name==null){if(!ns.group(2).equals("name"))problems.add(file.getKey()+": unresolved preference namespace "+ns.group(2)+"; assign it in BackupRegistry");continue;}
                if(!BackupRegistry.namespace(name)&&!Arrays.asList(BackupRegistry.PRIVATE_NAMESPACES).contains(name))problems.add(file.getKey()+": preference namespace "+name+" is unclassified; assign it in BackupRegistry");}
            Matcher call=CALL.matcher(text);
            while(call.find()) {
                String before=text.substring(Math.max(0,call.start()-24),call.start());
                if(JSON_RECEIVER.matcher(before).find())continue; // JSON or Bundle reader, not a preference
                String key;boolean prefix;
                if(call.group(3)!=null){key=call.group(3);prefix=call.group(4)!=null;}
                else {key=resolve(constants,file.getKey(),(call.group(5)==null?"":call.group(5))+call.group(6));prefix=call.group(7)!=null;if(key==null)continue;}
                if(key.isEmpty()||!key.matches("[a-z][a-z0-9_]*"))continue;
                if(IGNORED.contains(key)||registered(key,prefix))continue;
                problems.add(file.getKey()+": preference key "+key+(prefix?"<suffix>":"")+" is unclassified; assign it in BackupRegistry");
            }
            Matcher root=ROOT.matcher(text);
            while(root.find()) {
                String path;
                if(root.group(2)!=null)path=(root.group(1).equals("Files")?"files/":root.group(1).equals("Cache")?"cache/":"no_backup/")+root.group(2)+(root.group(3)!=null?"":"");
                else {String name=root.group(4)!=null?root.group(4):resolve(constants,file.getKey(),root.group(5));if(name==null)continue;path="databases/"+name;}
                try{BackupRegistry.root(path);}catch(IllegalArgumentException unclassified){problems.add(file.getKey()+": storage root "+path+" is unclassified; assign it in BackupRegistry");}
            }
        }
        return new ArrayList<>(problems);
    }
    private static String resolve(Map<String,String> constants,String file,String name) {
        if(name==null)return null;
        if(name.contains("."))return constants.get(name);
        return constants.get(file+":"+name);
    }
    private static boolean registered(String key,boolean prefix) {
        for(BackupRegistry.Rule rule:BackupRegistry.rules()) {
            if(rule.prefix&&!rule.key.isEmpty()&&(key.startsWith(rule.key)&&(prefix||key.length()>rule.key.length())))return true;
            if(!rule.prefix&&!prefix&&rule.key.equals(key))return true;
        }
        return false;
    }
}
