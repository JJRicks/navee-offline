package local.navee.offlinelock;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What Volt knows about one scooter's identity, with provenance. Immutable and Android-free.
 * The serial-derived PID (serial chars 1..4, Profile.pid()) is Volt's own adapter; the OEM app
 * never derives a model from the serial, so OEM group rules test only the advertised PID.
 * Holds no serial, MAC, identity or name: region and the serial PID are derived once.
 */
public final class DeviceDescriptor {
    public enum PidSource { ADVERTISED, SERIAL, NONE }
    public enum Transport { STANDARD, LD_EBIKE, DIGITECH_EBIKE, EXO, UNVERIFIED }
    /** OEM 2.1.9 region enum; an unknown two-letter code maps to "NA" (non-null), an invalid serial to null. */
    public static final String[] OEM_AREAS={"NA","CN","UK","ID","IN","IT","NL","NZ","SG","ES","TH","SE","JP","EU","US","CA","KR","FR","DE","AT","AU","RU","NE","PL","WE","ME","DK"};

    /** Firmware strings from 0x73 (meter, controller, BMS, screen, UWB). null = group absent or not printable. */
    public static final class Firmware {
        public static final Firmware NONE=new Firmware(null,null,null,null,null,-1);
        public final String meter,controller,bms,screen,uwb; public final long observedMs;
        public Firmware(String meter,String controller,String bms,String screen,String uwb,long observedMs){
            this.meter=clean(meter);this.controller=clean(controller);this.bms=clean(bms);this.screen=clean(screen);this.uwb=clean(uwb);this.observedMs=observedMs;
        }
        /** Telemetry values: "(raw)" or empty means unknown. */
        public static Firmware fromTelemetry(String meter,String controller,String bms,String screen,String uwb,long observedMs){
            return new Firmware(meter,controller,bms,screen,uwb,observedMs);
        }
        public boolean any(){return meter!=null||controller!=null||bms!=null||screen!=null||uwb!=null;}
        /** At most 16 printable ASCII characters, else null. Telemetry marks undecodable groups "… (raw)". */
        static String clean(String value){
            if(value==null||value.isEmpty()||value.length()>16||value.endsWith("(raw)"))return null;
            for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<33||c>126)return null;}
            return value;
        }
    }

    public final String advertisedPid;   // "" or canonical decimal [0-9]{1,5} ≤ 65535
    public final long advertisedWallMs;  // 0 when not captured
    public final String serialPid;       // "" or the 4 ASCII digits at serial[1..5) (Profile.pid()'s input)
    public final String region;          // null when serial fails [A-Za-z][A-Za-z0-9]{16}; else serial[8..10) as written (OEM match is case-sensitive)
    public final Firmware firmware;      // never null
    public final boolean firmwareThisSession;
    public final Transport transport;
    public final int role;               // 0 owner, 1 shared
    public final boolean simulated;

    private DeviceDescriptor(String advertisedPid,long advertisedWallMs,String serialPid,String region,Firmware firmware,boolean fwThisSession,Transport transport,int role,boolean simulated){
        this.advertisedPid=pid(advertisedPid);this.advertisedWallMs=this.advertisedPid.isEmpty()?0:Math.max(0,advertisedWallMs);
        this.serialPid=serialPid;this.region=region;this.firmware=firmware==null?Firmware.NONE:firmware;
        this.firmwareThisSession=fwThisSession&&this.firmware.any();this.transport=transport==null?Transport.UNVERIFIED:transport;
        this.role=role;this.simulated=simulated;
    }

    /** Never throws: malformed input becomes "absent". */
    public static DeviceDescriptor of(String serial,String advertisedPid,long advertisedWallMs,Firmware fw,boolean fwThisSession,Transport t,int role){
        String s=serial==null?"":serial,digits="";
        if(s.length()>=5){String part=s.substring(1,5);boolean ok=true;for(int i=0;i<4;i++)ok&=part.charAt(i)>='0'&&part.charAt(i)<='9';if(ok)digits=part;}
        return new DeviceDescriptor(advertisedPid,advertisedWallMs,digits,validSerial(s)?s.substring(8,10):null,fw,fwThisSession,t,role,false);
    }
    /**
     * Simulator descriptor; "" stands in for the default UT5 MAX simulator (serial-like 2538).
     * An invalid ID (not 0–65535) simulates an unidentified model, never the UT5 MAX.
     */
    public static DeviceDescriptor simulated(String advertisedPid,int role){
        String pid=pid(advertisedPid);boolean ut5=advertisedPid==null||advertisedPid.isEmpty();
        return new DeviceDescriptor(pid,pid.isEmpty()?0:1,ut5?"2538":"",null,Firmware.NONE,false,Transport.STANDARD,role,true);
    }
    public DeviceDescriptor withFirmware(Firmware fw,boolean thisSession){return new DeviceDescriptor(advertisedPid,advertisedWallMs,serialPid,region,fw,thisSession,transport,role,simulated);}
    public DeviceDescriptor withAdvertised(String pid,long wallMs){return new DeviceDescriptor(pid,wallMs,serialPid,region,firmware,firmwareThisSession,transport,role,simulated);}

    /** The PID OEM rules may test: the advertised PID, or "" when none. */
    public String rulePid(){return advertisedPid;}
    public PidSource pidSource(){return !advertisedPid.isEmpty()?PidSource.ADVERTISED:!serialPid.isEmpty()?PidSource.SERIAL:PidSource.NONE;}
    /** First 4 digits of the advertised PID, else the serial PID, else "". */
    public String family(){return !advertisedPid.isEmpty()?advertisedPid.substring(0,Math.min(4,advertisedPid.length())):serialPid;}
    public boolean conflict(){return !advertisedPid.isEmpty()&&!serialPid.isEmpty()&&!advertisedPid.startsWith(serialPid);}
    public boolean fullPid(){return advertisedPid.length()==5;}
    public int variantDigit(){return fullPid()?advertisedPid.charAt(4)-'0':-1;}
    public String oemArea(){if(region==null)return null;for(String a:OEM_AREAS)if(a.equals(region))return a;return "NA";}
    /** OEM SKU bucket: ITA (IT, DE, NE), USA (US, CN, RU), EUR for everything else including no region. */
    public String sku(){String a=oemArea();if("IT".equals(a)||"DE".equals(a)||"NE".equals(a))return "ITA";if("US".equals(a)||"CN".equals(a)||"RU".equals(a))return "USA";return "EUR";}

    public static boolean validSerial(String s){
        if(s==null||s.length()!=17)return false;
        for(int i=0;i<17;i++){char c=s.charAt(i);boolean letter=(c>='A'&&c<='Z')||(c>='a'&&c<='z');if(!(letter||(i>0&&c>='0'&&c<='9')))return false;}
        return true;
    }
    /** The OEM's lexicographic String.compareTo, so "2.0.10.0" sorts before "2.0.2.0". Callers treat null as unknown. */
    public static int compareOem(String a,String b){return a.compareTo(b);}

    /** Profile fields this descriptor contributes (advertised PID and its capture time). Region is never stored. */
    public Map<String,String> storedFields(){
        Map<String,String> fields=new LinkedHashMap<>();fields.put("pid_advertised",advertisedPid);fields.put("pid_advertised_wall",Long.toString(advertisedWallMs));return fields;
    }

    /** Canonical unpadded decimal 0–65535 as rendered from the advertisement, else "". */
    static String pid(String value){
        if(value==null||value.isEmpty()||value.length()>5)return "";
        for(int i=0;i<value.length();i++)if(value.charAt(i)<'0'||value.charAt(i)>'9')return "";
        int n=Integer.parseInt(value);return n<=65535&&Integer.toString(n).equals(value)?value:"";
    }

    @Override public String toString(){
        return "DeviceDescriptor{pid="+(advertisedPid.isEmpty()?"-":advertisedPid)+",serialPid="+(serialPid.isEmpty()?"-":serialPid)+",region="+region
            +",meter="+firmware.meter+(firmwareThisSession?"":"(stored)")+",transport="+transport+",role="+role+(simulated?",simulated":"")+"}";
    }
}
