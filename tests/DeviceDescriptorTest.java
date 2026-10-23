package com.jjricks.volt;

import java.util.Map;

/** Identity provenance, region/SKU derivation and the OEM's lexicographic firmware comparison. */
public final class DeviceDescriptorTest {
    private static int assertions;
    public static void main(String[] args) {
        serials(); advertised(); firmware(); copies();
        System.out.println("DeviceDescriptor tests passed: "+assertions+" assertions");
    }

    private static DeviceDescriptor of(String serial,String advertised){return DeviceDescriptor.of(serial,advertised,advertised.isEmpty()?0:1000,null,false,DeviceDescriptor.Transport.STANDARD,0);}

    private static void serials() {
        DeviceDescriptor us=of("Q2443ZZ9US7777777","");
        check("US".equals(us.region)&&"US".equals(us.oemArea())&&"USA".equals(us.sku()),"US region and SKU");
        check(us.serialPid.equals("2443")&&us.pidSource()==DeviceDescriptor.PidSource.SERIAL&&us.family().equals("2443"),"Serial PID adapter");
        check(us.rulePid().isEmpty()&&!us.fullPid()&&us.variantDigit()==-1&&!us.conflict(),"A serial PID is never an OEM rule PID");
        check("ITA".equals(of("Q2443ZZ9NE7777777","").sku()),"NE is ITA");
        check("EUR".equals(of("Q2443ZZ9NL7777777","").sku())&&"NL".equals(of("Q2443ZZ9NL7777777","").oemArea()),"NL is EUR, not NE");
        check("ITA".equals(of("Q2443ZZ9DE7777777","").sku())&&"USA".equals(of("Q2443ZZ9RU7777777","").sku())&&"USA".equals(of("Q2443ZZ9CN7777777","").sku()),"Other SKU buckets");
        DeviceDescriptor zz=of("Q2443ZZ9ZZ7777777","");check("ZZ".equals(zz.region)&&"NA".equals(zz.oemArea())&&"EUR".equals(zz.sku()),"Unknown code is NA, non-null");
        DeviceDescriptor lower=of("q2443zz9us7777777","");check("us".equals(lower.region)&&"NA".equals(lower.oemArea())&&"EUR".equals(lower.sku()),"Lowercase region is NA, as the OEM lookup is case-sensitive");
        DeviceDescriptor invalid=of("Q2443ZZ9US777777","");check(invalid.region==null&&invalid.oemArea()==null&&"EUR".equals(invalid.sku()),"Invalid serial: no region, EUR");
        check(invalid.serialPid.equals("2443"),"Serial PID uses the same characters as Profile.pid()");
        DeviceDescriptor fixture=of("X2538-SYNTHETIC","");check(fixture.serialPid.equals("2538")&&fixture.region==null,"Android fixture serial stays 2538");
        check(!DeviceDescriptor.validSerial("12443ZZ9US7777777")&&!DeviceDescriptor.validSerial("Q2443ZZ9US77777-7")&&DeviceDescriptor.validSerial("Q2443ZZ9US7777777"),"Serial pattern");
        for(String bad:new String[]{null,"","Q","Q24","Qab12ZZ9US7777777","Q-538ZZ9US7777777","Q+538ZZ9US7777777"})check(of(bad,"").serialPid.isEmpty(),"No serial PID from "+bad);
        check(of(null,"").pidSource()==DeviceDescriptor.PidSource.NONE&&of(null,"").family().isEmpty(),"No PID at all");
    }

    private static void advertised() {
        DeviceDescriptor match=of("Q2443ZZ9US7777777","24431");
        check(!match.conflict()&&match.fullPid()&&match.variantDigit()==1&&match.family().equals("2443")&&match.rulePid().equals("24431"),"Advertised + matching serial");
        check(match.pidSource()==DeviceDescriptor.PidSource.ADVERTISED,"Advertised wins provenance");
        check(of("Q2401ZZ9US7777777","24431").conflict(),"Advertised/serial disagreement is a conflict");
        check(!of("","24431").conflict()&&!of("Q2443ZZ9US7777777","2443").conflict(),"No conflict without both, or on a prefix");
        for(String bad:new String[]{"2443a","123456","70000","-1","+2443","02443"," 2443",null}){DeviceDescriptor d=of("Q2443ZZ9US7777777",bad==null?"":bad);check(d.advertisedPid.isEmpty()&&d.advertisedWallMs==0,"Malformed advertised "+bad+" stored as empty");}
        check(of("","65535").advertisedPid.equals("65535")&&of("","0").advertisedPid.equals("0"),"Bounds of a 16-bit ID");
        check(of("","123").family().equals("123")&&!of("","123").fullPid(),"Short advertised ID");
        Map<String,String> stored=match.storedFields();
        check(stored.size()==2&&"24431".equals(stored.get("pid_advertised"))&&"1000".equals(stored.get("pid_advertised_wall")),"Stored fields are advertised PID and time only");
        DeviceDescriptor sim=DeviceDescriptor.simulated("",0);check(sim.simulated&&sim.serialPid.equals("2538")&&sim.region==null,"Default simulator is serial-like 2538");
        DeviceDescriptor invalid=DeviceDescriptor.simulated("99991",0);check(invalid.rulePid().isEmpty()&&invalid.serialPid.isEmpty(),"An invalid simulated ID is unidentified, not UT5 MAX");
        check(DeviceDescriptor.simulated("",0,"US").region==null&&DeviceDescriptor.simulated("25851",0,"US").sku().equals("USA")
            &&DeviceDescriptor.simulated("25851",0,"us").region==null&&DeviceDescriptor.simulated("25851",0,"DE").sku().equals("ITA"),"Simulated region: other models only, two capitals");
        DeviceDescriptor mapped=DeviceDescriptor.simulated("24431",1);check(mapped.rulePid().equals("24431")&&mapped.serialPid.isEmpty()&&mapped.role==1,"Simulated advertised model");
    }

    private static void firmware() {
        check(DeviceDescriptor.compareOem("2.0.0.4","2.0.0.4")<=0,"Equal versions compare equal");
        check(DeviceDescriptor.compareOem("2.0.1.1","2.0.1.2")<0,"Lexicographic ordering");
        check(DeviceDescriptor.compareOem("2.0.10.0","2.0.2.0")<0,"OEM quirk: 2.0.10.0 sorts before 2.0.2.0");
        DeviceDescriptor.Firmware raw=DeviceDescriptor.Firmware.fromTelemetry("32 30 31 32 (raw)","2.0.1.2","",null,"1.0.0.1",5);
        check(raw.meter==null&&"2.0.1.2".equals(raw.controller)&&raw.bms==null&&raw.screen==null&&"1.0.0.1".equals(raw.uwb)&&raw.any(),"Raw or empty telemetry is unknown");
        check(!DeviceDescriptor.Firmware.NONE.any()&&DeviceDescriptor.Firmware.fromTelemetry("01234567890123456",null,null,null,null,1).meter==null,"Over-long version is unknown");
        check(DeviceDescriptor.Firmware.fromTelemetry("2 0",null,null,null,null,1).meter==null,"Non-printable or spaced version is unknown");
    }

    private static void copies() {
        DeviceDescriptor base=of("Q2443ZZ9US7777777","");
        DeviceDescriptor.Firmware fw=DeviceDescriptor.Firmware.fromTelemetry("2.0.1.2",null,null,null,null,10);
        DeviceDescriptor read=base.withFirmware(fw,true);check(read.firmwareThisSession&&"2.0.1.2".equals(read.firmware.meter)&&!base.firmwareThisSession,"Session firmware copy");
        check(!base.withFirmware(DeviceDescriptor.Firmware.NONE,true).firmwareThisSession,"No firmware is never 'read this session'");
        DeviceDescriptor captured=read.withAdvertised("24431",99);check(captured.rulePid().equals("24431")&&captured.advertisedWallMs==99&&captured.firmwareThisSession&&"US".equals(captured.region),"Advertised copy keeps other evidence");
        check(base.firmware==DeviceDescriptor.Firmware.NONE,"Firmware is never null");
        check(!base.toString().contains("7777777"),"toString never prints the serial");
    }

    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
