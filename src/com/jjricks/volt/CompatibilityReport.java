package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A sanitized, model-level compatibility report for review. Everything goes through one strict
 * whitelist: each value is typed, bounded and from a fixed vocabulary or pattern, and anything
 * that fails is left out. The builder takes no serial, MAC, owner identity, PIN, name, location,
 * ride or payload bytes, so none can be written; an imported report is revalidated the same way.
 * Packet evidence (what the scooter answered) and the owner's physical observation are kept in
 * separate objects. A report is evidence for a reviewed registry update, never configuration.
 * Pure and Android-free; JSON is written and read by hand (no org.json).
 */
final class CompatibilityReport {
    static final String FORMAT="volt-compat-report-v1";

    /**
     * Wire outcomes of a guided test step (the docs/03 vocabulary in snake case). "Effect observed"
     * is the owner's observation and is reported separately, never as packet evidence.
     */
    /** Steps whose step_values entry is set: a rejection's code, or the 0x70 value a readback showed. */
    static final Set<String> VALUED_OUTCOMES=Collections.unmodifiableSet(new HashSet<>(Arrays.asList("rejected","readback_unchanged","readback_different")));
    static final String[] TEST_OUTCOMES={"rejected","acknowledged_unconfirmed","readback_unchanged","readback_different","setting_confirmed","restoration_confirmed","interrupted"};
    /** What the owner saw on the scooter itself; never inferred from packets. */
    enum Observation { OBSERVED, NOT_OBSERVED, NOT_CHECKED }
    enum Restoration { CONFIRMED, OUTSTANDING, DECLINED }

    /** Every key the report may contain, at any depth. */
    static final Set<String> KEYS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "format","report_id","app_version","registry_version","simulated","device","reads","passive","status_length","fields","decisions","tests",
        "pid","pid_prefix","pid_source","pid_digits","pid_conflict","region","region_source","sku","firmware","meter","controller","bms","screen","uwb",
        "transport","auth","layer","role",
        "cmd","class","outcome","status","length","attempts","first_ms","late","seen",
        "offset","name","present","value",
        "feature","support","available","reason","evidence",
        "variant","baseline","target","packet_evidence","outcomes","elapsed_ms","step_values","owner_observation","effect","restoration")));
    /** 0x70 offsets whose value may be reported; 0, 14, 40 and 41 are presence only; 27–30 (time of day) never appear. */
    static boolean valueOffset(int o){return o>=1&&o<=13||o>=15&&o<=26||o>=31&&o<=39||o==42;}
    static boolean presenceOffset(int o){return o==0||o==14||o==40||o==41;}
    static final int LAST_OFFSET=42;
    private static final String[] AUTH={"challenge_free","aes","aes_extended","xor","failed"};
    private static final String[] READ_OUTCOMES={"pending","read_supported","rejected","no_response","malformed","empty_reply","interrupted"};

    private final Map<String,Object> tree;
    private CompatibilityReport(Map<String,Object> tree){this.tree=tree;}

    // ---- Builder --------------------------------------------------------------------------

    static final class Builder {
        private final Map<String,Object> raw=new LinkedHashMap<>();
        private ModelRegistry.Layer layer=ModelRegistry.Layer.SHARED;
        /** secure: SecureRandom in production, seeded in tests. */
        Builder(String appVersion,java.util.Random secure){
            raw.put("format",FORMAT);
            StringBuilder id=new StringBuilder();for(int i=0;i<16;i++)id.append(Character.forDigit(secure.nextInt(16),16));
            raw.put("report_id",id.toString());raw.put("app_version",appVersion);raw.put("registry_version",(long)ModelRegistry.VERSION);
        }
        /** authVariant: one of challenge_free, aes, aes_extended, xor, failed (see authVariant()). */
        Builder device(DeviceDescriptor d,ModelRegistry.Resolution r,String authVariant){
            if(d==null)return this;
            if(r==null)r=ModelRegistry.resolve(d);layer=r.layer;
            Map<String,Object> m=new LinkedHashMap<>();
            DeviceDescriptor.PidSource source=d.pidSource();
            if(source==DeviceDescriptor.PidSource.ADVERTISED)m.put("pid",d.advertisedPid);
            if(d.family().length()==4)m.put("pid_prefix",d.family());
            m.put("pid_source",lower(source));
            m.put("pid_digits",(long)(source==DeviceDescriptor.PidSource.ADVERTISED?d.advertisedPid.length():source==DeviceDescriptor.PidSource.SERIAL?d.serialPid.length():0));
            m.put("pid_conflict",d.conflict()||r.conflict);
            if(d.region!=null){m.put("region",d.region);m.put("region_source","serial");}
            m.put("sku",d.sku());
            Map<String,Object> fw=new LinkedHashMap<>();
            putIf(fw,"meter",d.firmware.meter);putIf(fw,"controller",d.firmware.controller);putIf(fw,"bms",d.firmware.bms);putIf(fw,"screen",d.firmware.screen);putIf(fw,"uwb",d.firmware.uwb);
            if(!fw.isEmpty()&&d.firmwareThisSession)m.put("firmware",fw);
            m.put("transport",lower(d.transport));m.put("auth",authVariant);m.put("layer",lower(r.layer));m.put("role",d.role==0?"owner":"shared");
            raw.put("device",m);
            if(d.simulated)raw.put("simulated",true);
            return this;
        }
        Builder reads(List<DiscoveryCoordinator.Read> reads,List<DiscoveryCoordinator.Read> passive,long startMs){
            List<Object> out=new ArrayList<>();
            if(reads!=null)for(DiscoveryCoordinator.Read r:reads){
                Map<String,Object> m=new LinkedHashMap<>();
                m.put("cmd",hex(r.cmd));m.put("class",r.cls==null?null:lower(r.cls));m.put("outcome",lower(r.outcome));
                if(r.status>=0){m.put("status",(long)r.status);m.put("length",(long)r.length);}
                m.put("attempts",(long)r.attempts);
                if(r.firstMs>=0)m.put("first_ms",r.firstMs-startMs);
                m.put("late",r.late);out.add(m);
            }
            raw.put("reads",out);
            List<Object> seen=new ArrayList<>();
            if(passive!=null)for(DiscoveryCoordinator.Read p:passive){
                Map<String,Object> m=new LinkedHashMap<>();boolean s=p.outcome==DiscoveryCoordinator.Outcome.READ_SUPPORTED;
                m.put("cmd",hex(p.cmd));m.put("seen",s);
                if(s){m.put("length",(long)p.length);if(p.cmd==0x91||p.cmd==0x92)m.put("format",p.cmd==0x91?"v0":"v1");}
                seen.add(m);
            }
            raw.put("passive",seen);
            return this;
        }
        /** Named, whitelisted 0x70 offsets only (call device() first so lighting bytes get the right names). */
        Builder fields(StatusFields s){
            if(s==null||!s.accepted())return this;
            raw.put("status_length",(long)s.length());
            List<Object> out=new ArrayList<>();
            for(int o=0;o<=LAST_OFFSET;o++){
                if(!valueOffset(o)&&!presenceOffset(o))continue;
                Map<String,Object> m=new LinkedHashMap<>();StatusFields.Field f=s.field(o);
                m.put("offset",(long)o);m.put("name",fieldName(o,layer));m.put("present",f.present);
                if(f.present&&valueOffset(o))m.put("value",(long)f.value);
                out.add(m);
            }
            raw.put("fields",out);
            return this;
        }
        Builder decisions(List<CapabilityResolver.Decision> ds){
            List<Object> out=new ArrayList<>();
            if(ds!=null)for(CapabilityResolver.Decision d:ds){
                Map<String,Object> m=new LinkedHashMap<>();
                m.put("feature",d.feature);m.put("support",lower(d.support));m.put("available",d.available());m.put("reason",lower(d.reason));m.put("evidence",lower(d.evidence));
                out.add(m);
            }
            raw.put("decisions",out);
            return this;
        }
        /**
         * Guided tests from the device-local journal, semantic values only: feature, variant,
         * baseline, target, wire outcomes with times relative to approval, the owner's
         * observation and the restoration state. No profile, test ID or wall time is copied.
         */
        Builder tests(List<TestJournal.Entry> entries){
            List<Object> out=new ArrayList<>();
            if(entries!=null)for(TestJournal.Entry t:entries){
                Map<String,Object> m=new LinkedHashMap<>();
                m.put("feature",t.feature);m.put("variant",t.variantId);m.put("baseline",(long)t.baseline);m.put("target",(long)t.target);
                Map<String,Object> packet=new LinkedHashMap<>();List<Object> outcomes=new ArrayList<>(),times=new ArrayList<>(),values=new ArrayList<>();
                for(int i=0;i<t.outcomes.size();i++){
                    GuidedTest.Outcome o=GuidedTest.Outcome.parse(t.outcomes.get(i));
                    if(o==null||o==GuidedTest.Outcome.EFFECT_OBSERVED)continue;
                    outcomes.add(o.reportToken());times.add(i<t.elapsedMs.size()?t.elapsedMs.get(i):null);
                    int code=GuidedTest.Outcome.code(t.outcomes.get(i));values.add(code>=0?(Object)(long)code:null);
                }
                packet.put("outcomes",outcomes);packet.put("elapsed_ms",times);packet.put("step_values",values);m.put("packet_evidence",packet);
                Map<String,Object> owner=new LinkedHashMap<>();owner.put("effect",t.effect);m.put("owner_observation",owner);
                m.put("restoration",t.restoration);
                out.add(m);
            }
            raw.put("tests",out);
            return this;
        }
        CompatibilityReport build(){Map<String,Object> clean=sanitize(raw);return clean==null?null:new CompatibilityReport(clean);}
        private static void putIf(Map<String,Object> m,String k,String v){if(v!=null)m.put(k,v);}
    }

    /** challenge_free, xor, aes_extended or aes, from the 0x30 reply's data length (status byte included) and method byte. */
    static String authVariant(int dataLength,int methodByte){
        if(dataLength<=1)return "challenge_free";
        if(dataLength>17)return methodByte==0?"xor":"aes_extended";
        return "aes";
    }
    /** Volt's report name for a 0x70 offset. Lighting bytes carry UT5 MAX meanings only on that layer. */
    static String fieldName(int offset,ModelRegistry.Layer layer){
        boolean ut5=layer==ModelRegistry.Layer.UT5_MAX;
        switch(offset){
            case 0: return "binding_status"; case 1: return "driving_mode"; case 2: return "lock_status"; case 3: return "cruise_control";
            case 4: return "tail_light"; case 5: return "energy_recovery"; case 6: return "range_algorithm"; case 7: return "display_units";
            case 8: return "auto_sensor"; case 9: return "tyre_reminder"; case 10: return ut5?"ambient_light":"lighting_byte_10";
            case 11: return "traction_control"; case 12: return "turn_sound"; case 13: return "proximity_key"; case 14: return "night_mode";
            case 15: return "range_algorithm_mode"; case 16: return ut5?"eco_light":"lighting_byte_16"; case 17: return ut5?"drive_light":"lighting_byte_17";
            case 18: return ut5?"sport_light":"lighting_byte_18"; case 19: return "start_speed"; case 20: return "speed_limit"; case 21: return "volume";
            case 22: return "voice_language"; case 23: return "logo_light"; case 24: return "daytime_light"; case 25: return "maximum_speed_setting";
            case 26: return "drive_mode_setting"; case 31: return "charge_limit_percent"; case 32: return "low_power"; case 33: return "timed_charging";
            case 34: return "auto_lock_time"; case 35: return "speed_limit_eligibility"; case 36: return "weather"; case 37: return "slope_assist";
            case 38: return "long_range_mode"; case 39: return "overspeed_control"; case 40: return "osa_flag_unmapped"; case 41: return "unread_41";
            case 42: return "drive_speed_limit";
            default: return null;
        }
    }
    /** Short UI copy for a field row: lighting bytes outside the UT5 MAX layer are raw bytes. */
    static String fieldLabel(int offset,ModelRegistry.Layer layer){
        String name=fieldName(offset,layer);if(name==null)return "";
        if(name.startsWith("lighting_byte_"))return "Lighting byte "+offset+" (raw)";
        if(offset==40)return "OSA flag (unmapped)";
        if(offset==41)return "Byte 41 (unread)";
        String words=name.replace('_',' ');
        return Character.toUpperCase(words.charAt(0))+words.substring(1);
    }

    // ---- Output ---------------------------------------------------------------------------

    String json(){StringBuilder out=new StringBuilder();write(out,tree,0);out.append('\n');return out.toString();}
    String reportId(){Object id=tree.get("report_id");return id instanceof String?(String)id:"";}
    /** Human-readable text built only from the sanitized report. */
    String summary(){
        StringBuilder s=new StringBuilder("Volt compatibility report\n");
        s.append("Report ").append(reportId()).append(" · Volt ").append(str(tree.get("app_version"),"?")).append(" · rules v").append(tree.get("registry_version")).append('\n');
        if(Boolean.TRUE.equals(tree.get("simulated")))s.append("Simulated scooter (not real evidence)\n");
        Map<String,Object> d=map(tree.get("device"));
        if(d!=null){
            String pid=str(d.get("pid"),str(d.get("pid_prefix"),"not captured"));
            s.append("Model ID ").append(pid).append(" (").append(str(d.get("pid_source"),"none")).append(")");
            if(Boolean.TRUE.equals(d.get("pid_conflict")))s.append(" · Model ID mismatch");
            s.append(" · layer ").append(str(d.get("layer"),"?"));
            if(d.get("region")!=null)s.append(" · region ").append(d.get("region"));
            s.append(" · SKU ").append(str(d.get("sku"),"?")).append(" · ").append(str(d.get("role"),"?")).append('\n');
            Map<String,Object> fw=map(d.get("firmware"));
            if(fw!=null){s.append("Firmware:");for(Map.Entry<String,Object> e:fw.entrySet())s.append(' ').append(e.getKey()).append(' ').append(e.getValue()).append(';');s.setLength(s.length()-1);s.append('\n');}
            s.append("Authentication: ").append(str(d.get("auth"),Boolean.TRUE.equals(tree.get("simulated"))?"not checked (simulated)":"not recorded").replace('_',' ')).append('\n');
        }
        List<Object> reads=list(tree.get("reads"));
        if(reads!=null&&!reads.isEmpty()){
            s.append("\nReads (packet evidence)\n");
            for(Object o:reads){Map<String,Object> r=map(o);s.append("  ").append(r.get("cmd")).append(' ').append(r.get("class")).append(": ").append(readText(r)).append('\n');}
        }
        List<Object> passive=list(tree.get("passive"));
        if(passive!=null&&!passive.isEmpty()){
            s.append("Reports seen:");
            for(Object o:passive){Map<String,Object> p=map(o);s.append(' ').append(p.get("cmd")).append(Boolean.TRUE.equals(p.get("seen"))?" yes ("+p.get("length")+" bytes)":" not seen").append(';');}
            s.setLength(s.length()-1);s.append(" (absence is not proof of no support)\n");
        }
        List<Object> decisions=list(tree.get("decisions"));
        if(decisions!=null&&!decisions.isEmpty()){
            int available=0;for(Object o:decisions)if(Boolean.TRUE.equals(map(o).get("available")))available++;
            s.append("\nControls: ").append(available).append(" available of ").append(decisions.size()).append('\n');
            for(Object o:decisions){Map<String,Object> m=map(o);
                s.append("  ").append(m.get("feature")).append(": ").append(Boolean.TRUE.equals(m.get("available"))?"available":str(m.get("reason"),"unavailable").replace('_',' '))
                 .append(" · ").append(str(m.get("support"),"?").replace('_',' ')).append(" · evidence ").append(str(m.get("evidence"),"none").replace('_',' ')).append('\n');}
        }
        List<Object> tests=list(tree.get("tests"));
        if(tests!=null&&!tests.isEmpty()){
            s.append("\nGuided tests\n");
            for(Object o:tests){Map<String,Object> t=map(o);
                s.append("  ").append(t.get("feature")).append(' ').append(t.get("baseline")).append(" → ").append(t.get("target")).append('\n');
                Map<String,Object> p=map(t.get("packet_evidence"));List<Object> steps=p==null?null:list(p.get("outcomes"));
                s.append("    Packet evidence: ");if(steps==null||steps.isEmpty())s.append("none");else{List<Object> values=list(p.get("step_values"));for(int i=0;i<steps.size();i++)s.append(i==0?"":", ").append(testText(String.valueOf(steps.get(i)),values!=null&&i<values.size()?values.get(i):null));}
                s.append('\n');
                Map<String,Object> owner=map(t.get("owner_observation"));String effect=owner==null?"not_checked":str(owner.get("effect"),"not_checked");
                s.append("    Owner observation: ").append(effect.equals("observed")?"Effect observed":effect.equals("not_observed")?"Effect not observed":"Not checked").append('\n');
                String restoration=str(t.get("restoration"),"outstanding");
                s.append("    Restoration: ").append(restoration.equals("confirmed")?"Restoration confirmed":restoration.equals("declined")?"Declined by the owner (not restored by Volt)":"Outstanding (not confirmed)").append('\n');
            }
        }
        s.append("\nNo serial, MAC, owner key, PIN, names, routes or raw payloads are included.\n");
        return s.toString();
    }
    /** docs/03 wording for a test step, with its code or the value read back. */
    static String testText(String token,Object code){
        switch(token){
            case "rejected": return "Rejected, code "+(code instanceof Long?code:"?");
            case "acknowledged_unconfirmed": return "Acknowledged, unconfirmed";
            case "readback_unchanged": return "Readback unchanged"+(code instanceof Long?", value "+code:"");
            case "readback_different": return "Readback different"+(code instanceof Long?", value "+code:"");
            case "setting_confirmed": return "Setting confirmed";
            case "restoration_confirmed": return "Restoration confirmed";
            case "interrupted": return "Interrupted/no response";
            default: return token.replace('_',' ');
        }
    }
    static String readText(Map<String,Object> r){
        String outcome=str(r.get("outcome"),"");String late=Boolean.TRUE.equals(r.get("late"))?" (late)":"";
        switch(outcome){
            case "read_supported": return "Read supported · "+r.get("length")+" bytes"+late;
            case "rejected": return "Rejected, code "+r.get("status")+late;
            case "malformed": return "Too short · "+r.get("length")+" bytes"+late;
            case "empty_reply": return "Replied with no data (not proof of support)"+late;
            case "no_response": return "No response ("+r.get("attempts")+(Long.valueOf(1).equals(r.get("attempts"))?" attempt)":" attempts)");
            case "interrupted": return "Interrupted";
            default: return "Pending";
        }
    }

    /** Revalidates an imported report as untrusted: unknown keys and invalid values are dropped. null when it is not a report. */
    static CompatibilityReport parse(String json){
        if(json==null||json.length()>262144)return null;
        Object value;try{value=new Parser(json).document();}catch(IllegalArgumentException e){return null;}
        Map<String,Object> clean=sanitize(map(value));
        return clean==null?null:new CompatibilityReport(clean);
    }

    // ---- The whitelist --------------------------------------------------------------------

    /** The single scrubber for built and imported reports; null unless the format matches. */
    static Map<String,Object> sanitize(Map<String,Object> in){
        if(in==null||!FORMAT.equals(in.get("format")))return null;
        Map<String,Object> out=new LinkedHashMap<>();out.put("format",FORMAT);
        putPattern(out,"report_id",in.get("report_id"),"[0-9a-f]{16}");
        putPattern(out,"app_version",in.get("app_version"),"[0-9A-Za-z.+-]{1,32}");
        putInt(out,"registry_version",in.get("registry_version"),0,9999);
        if(Boolean.TRUE.equals(in.get("simulated")))out.put("simulated",true);
        Map<String,Object> device=device(map(in.get("device")));if(device!=null)out.put("device",device);
        List<Object> reads=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object o:bounded(in.get("reads"),DiscoveryCoordinator.ORDER.length)){Map<String,Object> r=read(map(o));if(r!=null&&seen.add((String)r.get("cmd")))reads.add(r);}
        if(in.containsKey("reads"))out.put("reads",reads);
        List<Object> passive=new ArrayList<>();seen.clear();
        for(Object o:bounded(in.get("passive"),DiscoveryCoordinator.PASSIVE.length)){Map<String,Object> p=passive(map(o));if(p!=null&&seen.add((String)p.get("cmd")))passive.add(p);}
        if(in.containsKey("passive"))out.put("passive",passive);
        putInt(out,"status_length",in.get("status_length"),0,255);
        List<Object> fields=new ArrayList<>();seen.clear();
        for(Object o:bounded(in.get("fields"),LAST_OFFSET+1)){Map<String,Object> f=field(map(o));if(f!=null&&seen.add(String.valueOf(f.get("offset"))))fields.add(f);}
        if(in.containsKey("fields"))out.put("fields",fields);
        List<Object> decisions=new ArrayList<>();seen.clear();
        for(Object o:bounded(in.get("decisions"),96)){Map<String,Object> d=decision(map(o));if(d!=null&&seen.add((String)d.get("feature")))decisions.add(d);}
        if(in.containsKey("decisions"))out.put("decisions",decisions);
        List<Object> tests=new ArrayList<>();
        for(Object o:bounded(in.get("tests"),32)){Map<String,Object> t=test(map(o));if(t!=null)tests.add(t);}
        if(in.containsKey("tests"))out.put("tests",tests);
        return out;
    }
    private static Map<String,Object> device(Map<String,Object> in){
        if(in==null)return null;
        Map<String,Object> out=new LinkedHashMap<>();
        String source=oneOf(in.get("pid_source"),"advertised","serial","none");
        Object pid=in.get("pid");
        if("advertised".equals(source)&&pid instanceof String&&!DeviceDescriptor.pid((String)pid).isEmpty())out.put("pid",pid);
        putPattern(out,"pid_prefix",in.get("pid_prefix"),"[0-9]{4}");
        if(source!=null)out.put("pid_source",source);
        putInt(out,"pid_digits",in.get("pid_digits"),0,5);
        if(in.get("pid_conflict") instanceof Boolean)out.put("pid_conflict",in.get("pid_conflict"));
        if(putPattern(out,"region",in.get("region"),"[A-Z]{2}"))out.put("region_source","serial");
        put(out,"sku",oneOf(in.get("sku"),"ITA","USA","EUR"));
        Map<String,Object> fw=map(in.get("firmware"));
        if(fw!=null){Map<String,Object> f=new LinkedHashMap<>();for(String k:new String[]{"meter","controller","bms","screen","uwb"})putPattern(f,k,fw.get(k),"[0-9A-Za-z.]{1,16}");if(!f.isEmpty())out.put("firmware",f);}
        put(out,"transport",oneOf(in.get("transport"),lowerAll(DeviceDescriptor.Transport.values())));
        put(out,"auth",oneOf(in.get("auth"),AUTH));
        put(out,"layer",oneOf(in.get("layer"),lowerAll(ModelRegistry.Layer.values())));
        put(out,"role",oneOf(in.get("role"),"owner","shared"));
        return out;
    }
    private static Map<String,Object> read(Map<String,Object> in){
        if(in==null)return null;
        Integer cmd=cmd(in.get("cmd"),DiscoveryCoordinator.ORDER);if(cmd==null)return null;
        String outcome=oneOf(in.get("outcome"),READ_OUTCOMES);if(outcome==null)return null;
        Map<String,Object> out=new LinkedHashMap<>();out.put("cmd",hex(cmd));out.put("class",lower(ReadPolicy.cls(cmd)));out.put("outcome",outcome);
        boolean replied=outcome.equals("read_supported")||outcome.equals("rejected")||outcome.equals("malformed")||outcome.equals("empty_reply");
        if(replied){putInt(out,"status",in.get("status"),0,255);putInt(out,"length",in.get("length"),0,255);}
        putInt(out,"attempts",in.get("attempts"),0,3);
        if(replied)putInt(out,"first_ms",in.get("first_ms"),0,DiscoveryCoordinator.HARD_CAP_MS*2);
        out.put("late",Boolean.TRUE.equals(in.get("late"))&&replied);
        return out;
    }
    private static Map<String,Object> passive(Map<String,Object> in){
        if(in==null)return null;
        Integer cmd=cmd(in.get("cmd"),DiscoveryCoordinator.PASSIVE);if(cmd==null)return null;
        Map<String,Object> out=new LinkedHashMap<>();out.put("cmd",hex(cmd));boolean seen=Boolean.TRUE.equals(in.get("seen"));out.put("seen",seen);
        if(seen){putInt(out,"length",in.get("length"),0,255);if(cmd!=0x90)out.put("format",cmd==0x91?"v0":"v1");}
        return out;
    }
    private static Map<String,Object> field(Map<String,Object> in){
        if(in==null)return null;
        Long offset=integer(in.get("offset"),0,LAST_OFFSET);if(offset==null)return null;int o=(int)(long)offset;
        if(!valueOffset(o)&&!presenceOffset(o))return null;
        Object name=in.get("name");
        String ut5=fieldName(o,ModelRegistry.Layer.UT5_MAX),other=fieldName(o,ModelRegistry.Layer.SHARED);
        if(!(name instanceof String)||!(name.equals(ut5)||name.equals(other)))return null;
        if(!(in.get("present") instanceof Boolean))return null;
        boolean present=(Boolean)in.get("present");
        Map<String,Object> out=new LinkedHashMap<>();out.put("offset",offset);out.put("name",name);out.put("present",present);
        if(present&&valueOffset(o))putInt(out,"value",in.get("value"),0,255);
        return out;
    }
    private static Map<String,Object> decision(Map<String,Object> in){
        if(in==null||!knownFeature(in.get("feature")))return null;
        Map<String,Object> out=new LinkedHashMap<>();out.put("feature",in.get("feature"));
        put(out,"support",oneOf(in.get("support"),lowerAll(CapabilityResolver.Support.values())));
        out.put("available",Boolean.TRUE.equals(in.get("available")));
        put(out,"reason",oneOf(in.get("reason"),lowerAll(CapabilityResolver.Reason.values())));
        put(out,"evidence",oneOf(in.get("evidence"),lowerAll(CapabilityResolver.Evidence.values())));
        return out;
    }
    private static Map<String,Object> test(Map<String,Object> in){
        if(in==null||!knownFeature(in.get("feature"))||!testable((String)in.get("feature")))return null;
        Map<String,Object> out=new LinkedHashMap<>();out.put("feature",in.get("feature"));
        putPattern(out,"variant",in.get("variant"),"[a-z0-9_.]{1,48}");
        putInt(out,"baseline",in.get("baseline"),0,255);putInt(out,"target",in.get("target"),0,255);
        Map<String,Object> packet=map(in.get("packet_evidence")),p=new LinkedHashMap<>();
        List<Object> outcomes=new ArrayList<>(),times=new ArrayList<>(),values=new ArrayList<>();
        if(packet!=null){
            List<Object> steps=bounded(packet.get("outcomes"),16),at=bounded(packet.get("elapsed_ms"),16),codes=bounded(packet.get("step_values"),16);
            for(int i=0;i<steps.size();i++){String v=oneOf(steps.get(i),TEST_OUTCOMES);if(v!=null){outcomes.add(v);times.add(i<at.size()?integer(at.get(i),0,24L*3600*1000):null);
                values.add(VALUED_OUTCOMES.contains(v)&&i<codes.size()?integer(codes.get(i),0,255):null);}}
        }
        p.put("outcomes",outcomes);p.put("elapsed_ms",times);p.put("step_values",values);out.put("packet_evidence",p);
        Map<String,Object> owner=map(in.get("owner_observation")),ob=new LinkedHashMap<>();
        String effect=owner==null?null:oneOf(owner.get("effect"),lowerAll(Observation.values()));
        ob.put("effect",effect==null?"not_checked":effect);out.put("owner_observation",ob);
        String restoration=oneOf(in.get("restoration"),lowerAll(Restoration.values()));
        out.put("restoration",restoration==null?"outstanding":restoration); // never upgraded to a success
        return out;
    }
    private static boolean knownFeature(Object key){
        if(!(key instanceof String))return false;if(key.equals("clock_sync")||ScooterCommands.oemOnly((String)key))return true;
        for(ScooterCommands.Feature f:ScooterCommands.catalog())if(f.key.equals(key))return true;
        return false;
    }
    /** Only the guided-test table's features (never PIN, clock, time, speed or bond-dependent values). */
    private static boolean testable(String key){return ModelRegistry.testable(key);}

    // ---- Small helpers --------------------------------------------------------------------

    static String hex(int cmd){return String.format(Locale.ROOT,"0x%02X",cmd&255);}
    private static Integer cmd(Object value,int[] allowed){
        if(!(value instanceof String))return null;
        for(int c:allowed)if(hex(c).equals(value))return c;
        return null;
    }
    static String lower(Enum<?> e){return e==null?null:e.name().toLowerCase(Locale.ROOT);}
    private static String[] lowerAll(Enum<?>[] values){String[] out=new String[values.length];for(int i=0;i<values.length;i++)out[i]=lower(values[i]);return out;}
    private static String oneOf(Object value,String... allowed){if(value instanceof String)for(String a:allowed)if(a.equals(value))return a;return null;}
    private static void put(Map<String,Object> m,String k,Object v){if(v!=null)m.put(k,v);}
    private static boolean putPattern(Map<String,Object> m,String k,Object v,String pattern){
        if(v instanceof String&&((String)v).matches(pattern)){m.put(k,v);return true;}return false;
    }
    private static void putInt(Map<String,Object> m,String k,Object v,long min,long max){Long n=integer(v,min,max);if(n!=null)m.put(k,n);}
    private static Long integer(Object v,long min,long max){
        if(!(v instanceof Long))return null;long n=(Long)v;return n<min||n>max?null:n;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){return v instanceof Map?(Map<String,Object>)v:null;}
    @SuppressWarnings("unchecked") private static List<Object> list(Object v){return v instanceof List?(List<Object>)v:null;}
    private static List<Object> bounded(Object v,int max){List<Object> l=list(v);if(l==null)return Collections.emptyList();return l.size()>max?l.subList(0,max):l;}
    private static String str(Object v,String fallback){return v instanceof String?(String)v:fallback;}

    private static void write(StringBuilder out,Object v,int indent){
        if(v instanceof Map){
            Map<?,?> m=(Map<?,?>)v;if(m.isEmpty()){out.append("{}");return;}
            out.append("{\n");int i=0;
            for(Map.Entry<?,?> e:m.entrySet()){pad(out,indent+1);quote(out,String.valueOf(e.getKey()));out.append(": ");write(out,e.getValue(),indent+1);out.append(++i<m.size()?",\n":"\n");}
            pad(out,indent);out.append('}');
        }else if(v instanceof List){
            List<?> l=(List<?>)v;if(l.isEmpty()){out.append("[]");return;}
            boolean flat=true;for(Object o:l)flat&=!(o instanceof Map)&&!(o instanceof List);
            if(flat){out.append('[');for(int i=0;i<l.size();i++){if(i>0)out.append(", ");write(out,l.get(i),indent);}out.append(']');return;}
            out.append("[\n");for(int i=0;i<l.size();i++){pad(out,indent+1);write(out,l.get(i),indent+1);out.append(i+1<l.size()?",\n":"\n");}
            pad(out,indent);out.append(']');
        }else if(v instanceof String)quote(out,(String)v);
        else if(v instanceof Long||v instanceof Boolean)out.append(v);
        else out.append("null");
    }
    private static void pad(StringBuilder out,int indent){for(int i=0;i<indent;i++)out.append("  ");}
    private static void quote(StringBuilder out,String s){
        out.append('"');
        for(int i=0;i<s.length();i++){char c=s.charAt(i);
            if(c=='"'||c=='\\')out.append('\\').append(c);else if(c<0x20||c>0x7e)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}
        out.append('"');
    }

    /** Minimal strict JSON reader: objects, arrays, strings, integers, booleans, null; depth ≤ 8. Floats are read as null. */
    private static final class Parser {
        private final String s;private int i;
        Parser(String s){this.s=s;}
        Object document(){Object v=value(0);space();if(i!=s.length())throw new IllegalArgumentException("trailing");return v;}
        private void space(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
        private Object value(int depth){
            if(depth>8)throw new IllegalArgumentException("deep");
            space();if(i>=s.length())throw new IllegalArgumentException("end");
            char c=s.charAt(i);
            if(c=='{'){i++;Map<String,Object> m=new LinkedHashMap<>();space();if(peek('}'))return m;
                while(true){space();String k=string();space();expect(':');Object v=value(depth+1);if(!m.containsKey(k))m.put(k,v);space();if(peek('}'))return m;expect(',');}}
            if(c=='['){i++;List<Object> l=new ArrayList<>();space();if(peek(']'))return l;
                while(true){l.add(value(depth+1));space();if(peek(']'))return l;expect(',');if(l.size()>1024)throw new IllegalArgumentException("long");}}
            if(c=='"')return string();
            if(s.startsWith("true",i)){i+=4;return Boolean.TRUE;}
            if(s.startsWith("false",i)){i+=5;return Boolean.FALSE;}
            if(s.startsWith("null",i)){i+=4;return null;}
            int start=i;if(c=='-')i++;
            while(i<s.length()&&(Character.isDigit(s.charAt(i))||".eE+-".indexOf(s.charAt(i))>=0))i++;
            String n=s.substring(start,i);if(n.isEmpty()||n.equals("-"))throw new IllegalArgumentException("value");
            if(!n.matches("-?(0|[1-9][0-9]{0,17})")){if(n.matches("-?[0-9.eE+-]+"))return null;throw new IllegalArgumentException("number");}
            return Long.parseLong(n);
        }
        private boolean peek(char c){if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
        private void expect(char c){if(!peek(c))throw new IllegalArgumentException("expected "+c);}
        private String string(){
            expect('"');StringBuilder b=new StringBuilder();
            while(true){
                if(i>=s.length())throw new IllegalArgumentException("string");
                char c=s.charAt(i++);
                if(c=='"')return b.toString();
                if(c<0x20)throw new IllegalArgumentException("control");
                if(c!='\\'){b.append(c);continue;}
                if(i>=s.length())throw new IllegalArgumentException("escape");
                char e=s.charAt(i++);
                switch(e){
                    case '"': case '\\': case '/': b.append(e); break;
                    case 'b': b.append('\b'); break; case 'f': b.append('\f'); break; case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break; case 't': b.append('\t'); break;
                    case 'u': if(i+4>s.length())throw new IllegalArgumentException("escape");
                        try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException x){throw new IllegalArgumentException("escape");}
                        i+=4; break;
                    default: throw new IllegalArgumentException("escape");
                }
                if(b.length()>4096)throw new IllegalArgumentException("long");
            }
        }
    }
}
