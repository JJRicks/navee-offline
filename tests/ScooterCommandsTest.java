package local.navee.offlinelock;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Android-free fixtures from installed OEM command formats, including negative safety boundaries. */
public final class ScooterCommandsTest {
    private static int assertions;
    public static void main(String[] args) {
        oemFixtures(); catalogDomains(); readbackMetadata(); lightingSemantics(); dynamicChoices(); sensitiveWorkflow(); rejectedCommands();
        System.out.println("ScooterCommands tests passed: "+assertions+" assertions");
    }

    private static void oemFixtures() {
        equal("55aa0053015aadfefd",ScooterCommands.set("energy_recovery",90));
        equal("55aa006f020450c4fefd",ScooterCommands.set("charge_limit",80));
        equal("55aa006e02012090fefd",ScooterCommands.set("maximum_speed",32));
        equal("55aa006d0304000174fefd",ScooterCommands.set("line_light",1));
        equal("55aa006d0305010277fefd",ScooterCommands.set("star_pattern",2));
        equal("55aa006f05030102030480fefd",ScooterCommands.setChargeStart(0x01020304L,0));
        equal("55aa006f05060102030483fefd",ScooterCommands.setClock(0x01020304L,0));
        equal("55aa006c02d0013efefd",ScooterCommands.setParams("voice",1,80,1));
        equal("55aa00630601010102030474fefd",ScooterCommands.setPin("1234",true));
        // OEM enabled bit is preserved by the cap builders.
        check((ScooterCommands.set("speed_limit",25)[5]&255)==153,"Custom cap enabled bit");
        check((ScooterCommands.set("speed_limit",0)[5]&255)==0,"Custom cap disabled");
        check((ScooterCommands.set("drive_speed_limit",20)[6]&255)==148,"Drive cap enabled bit");
        byte[] local=ScooterCommands.setClock(0x01020304L,3600);
        check(local[6]==1 && local[7]==2 && (local[8]&255)==17 && (local[9]&255)==20,"Local epoch uses BE and timezone offset");
    }

    private static void lightingSemantics() {
        // Independent OEM TailLight/AutoLight commands and status bytes. Neither is
        // evidence that the front lamp is physically emitting light.
        ScooterCommands.Feature tail=ScooterCommands.find("headlight");
        ScooterCommands.Feature automatic=ScooterCommands.find("auto_sensor");
        check(tail.label.equals("Tail light")&&tail.readbackKey.equals("tail_light"),"Legacy headlight key accurately names OEM tail-light control");
        check(tail.cmd==0x54&&tail.payloadOffset==4,"OEM TailLight command and tailIsOn status byte");
        check(automatic.cmd==0x57&&automatic.payloadOffset==8,"OEM AutoLight command and autoSensor status byte");
        byte[] on=ScooterCommands.set(tail.key,1),off=ScooterCommands.set(automatic.key,0);
        check((on[3]&255)==0x54&&on[5]==1,"Tail-light write uses only its scalar opcode");
        check((off[3]&255)==0x57&&off[5]==0,"Automatic-light write does not issue a manual headlight command");
        for(int tailValue=0;tailValue<=1;tailValue++)for(int autoValue=0;autoValue<=1;autoValue++){
            byte[] payload=new byte[15];payload[4]=(byte)tailValue;payload[8]=(byte)autoValue;payload[14]=1;
            Protocol.Frame status=notification(0x70,0,payload);
            check(tail.readback(status)==tailValue,"Tail preference is independent from automatic and night-mode settings");
            check(automatic.readback(status)==autoValue,"Automatic preference is independent from tail and night-mode settings");
        }
        check(tail.readback(notification(0x54,0,new byte[]{1}))==null,"Tail-light ACK is not status confirmation");
        check(automatic.readback(notification(0x57,0,new byte[]{1}))==null,"Automatic-light ACK is not status confirmation");
    }

    private static void catalogDomains() {
        Set<String> keys=new HashSet<String>();
        for(ScooterCommands.Feature f:ScooterCommands.catalog()) {
            check(keys.add(f.key),"Unique catalog key");
            // Captions are optional; search terms that left the copy live in keywords instead.
            check(!f.label.isEmpty()&&!f.group.isEmpty()&&f.description!=null&&f.keywords!=null,"Readable feature metadata");
            check(f.options.length==f.optionValues.length,"Choice labels match values");
            check(f.readbackCmd==0x70 || f.sensitive,"All ordinary controls read vehicle status");
            if(f.type==ScooterCommands.Type.TIME || f.type==ScooterCommands.Type.PIN) continue;
            for(int value:f.optionValues) {
                check(f.allows(value),"Choice is allowed "+f.key);
                inspect(ScooterCommands.set(f.key,value),f.cmd);
            }
            if(f.optionValues.length==0) {
                inspect(ScooterCommands.set(f.key,f.min),f.cmd);
                inspect(ScooterCommands.set(f.key,f.max),f.cmd);
            }
            final String key=f.key;final int low=f.min-1,high=f.max+1;
            rejects(()->ScooterCommands.set(key,low));
            rejects(()->ScooterCommands.set(key,high));
        }
        check(keys.size()>=30,"Substantial feature catalog");
        check(!ScooterCommands.find("maximum_speed").allows(60),"UT5 MAX is not sibling UT5 Pro");
        check(!ScooterCommands.find("speed_limit").allows(4),"Cap gap rejected by metadata");
        check(!ScooterCommands.find("drive_speed_limit").allows(4),"Drive cap gap rejected by metadata");
        check(ScooterCommands.find("dual_drive").experimental,"OEM-hidden candidate isn't called supported");
        check(ScooterCommands.find("long_range_mode").experimental,"Other model feature stays unverified");
        check(!ScooterCommands.find("charge_limit").experimental,"Known UT5 charge setting");
        check(ScooterCommands.find("parking_assist").searchText().toLowerCase().contains("boost"),"Power Assist stays findable by Boost");
        check(ScooterCommands.find("start_speed").searchText().contains("kickstart"),"Kick-start speed findable without its caption");
        check(!ScooterCommands.find("lock_expiry").description.isEmpty(),"Lock duration keeps its lock-out warning");
        check(!ScooterCommands.find("hill_start_assist").description.isEmpty(),"Walk Assist keeps how to stop it");
        check(ScooterCommands.find("speed_limit").valueLabel(0).equals("Off")&&ScooterCommands.find("drive_speed_limit").valueLabel(0).equals("Off"),"A zero cap reads as Off");
        check(ScooterCommands.find("speed_limit").valueLabel(25).equals("25 km/h"),"An enabled cap keeps its speed");
        rejects(()->ScooterCommands.catalog().clear());
    }

    private static void readbackMetadata() {
        byte[] payload=new byte[43];
        payload[1]=5;payload[2]=1;payload[5]=60;payload[10]=(byte)130;
        payload[16]=0x10;payload[17]=(byte)129;payload[18]=0x01;payload[31]=90;
        payload[37]=5;payload[42]=(byte)143;
        Protocol.Frame frame=notification(0x70,0,payload);
        check(ScooterCommands.find("lock").readback(frame)==1,"Lock offset includes response status");
        check(ScooterCommands.find("energy_recovery").readback(frame)==60,"Recovery payload offset");
        check(ScooterCommands.find("line_light").readback(frame)==1,"Line enabled bit");
        check(ScooterCommands.find("line_pattern").readback(frame)==2,"Line pattern excludes enabled bit");
        check(ScooterCommands.find("line_color").readback(frame)==0,"Line normal color low nibble");
        check(ScooterCommands.find("line_boost_color").readback(frame)==1,"Line Boost color high nibble");
        check(ScooterCommands.find("star_light").readback(frame)==1,"Star light offset");
        check(ScooterCommands.find("star_pattern").readback(frame)==1,"Star pattern offset");
        check(ScooterCommands.find("star_color").readback(frame)==1,"Star color offset");
        check(ScooterCommands.find("hill_start_assist").readback(frame)==1,"HAC bit0");
        check(ScooterCommands.find("parking_assist").readback(frame)==0,"PAS bit1");
        check(ScooterCommands.find("hill_descent_control").readback(frame)==1,"HDC bit2");
        check(ScooterCommands.find("drive_speed_limit").readback(frame)==15,"Drive cap strips enabled bit");
        check(ScooterCommands.find("charge_limit").readback(frame)==90,"Charge cap offset");
        payload[20]=25;payload[42]=15;
        frame=notification(0x70,0,payload);
        check(ScooterCommands.find("speed_limit").readback(frame)==0,"Custom cap disabled bit wins over a retained limit");
        check(ScooterCommands.find("drive_speed_limit").readback(frame)==0,"Drive cap disabled bit wins over a retained limit");
        payload[20]=(byte)153;payload[42]=(byte)143;
        frame=notification(0x70,0,payload);
        check(ScooterCommands.find("speed_limit").readback(frame)==25,"Enabled custom cap reads the actual limit");
        payload[27]=1;payload[28]=2;payload[29]=3;payload[30]=4;
        frame=notification(0x70,0,payload);
        check(ScooterCommands.find("charge_start").readback(frame)==null,"Time is not truncated to a byte");
        check(ScooterCommands.find("charge_start").timeReadback(frame)==0x01020304L,"Time readback BE32");
        for(ScooterCommands.Feature f:ScooterCommands.catalog()) {
            check(f.readback(notification(0x70,1,payload))==null,"Rejected response has no readback");
            check(f.readback(notification(0x72,0,payload))==null,"Wrong command has no readback");
            check(f.readback(notification(0x70,0,new byte[0]))==null,"Short response has no readback");
        }
        check(Arrays.equals(new int[]{0x70,0x72},ScooterCommands.followUpReads("charge_limit")),"Battery writes refresh battery and vehicle");
    }

    private static void sensitiveWorkflow() {
        byte[] pin=ScooterCommands.setPin("0123",true);
        check(Arrays.equals(Arrays.copyOfRange(pin,5,11),new byte[]{1,1,0,1,2,3}),"Leading zero retained as a digit");
        inspect(pin,0x63);
        check(ScooterCommands.enablePin(true,true,true)[5]==1,"Enable existing PIN");
        check(ScooterCommands.enablePin(false,true,false)[5]==0,"Disable PIN owner workflow");
        equal("55aa007a79fefd",ScooterCommands.readPin(true));
        ScooterCommands.PinState state=ScooterCommands.parsePin(notification(0x7a,0,new byte[]{1,0,1,2,3}),true);
        check(Boolean.TRUE.equals(state.enabled)&&state.configured,"PIN state parsed");
        check(!state.toString().contains("0123"),"PIN state textual representation has no digits");
        Protocol.Frame pinFrame=notification(0x7a,0,new byte[]{1,0,1,2,3});
        check(ScooterCommands.pinReadbackMatches(pinFrame,true,new char[]{'0','1','2','3'},true),"PIN selected digits verified privately");
        check(!ScooterCommands.pinReadbackMatches(pinFrame,true,new char[]{'0','1','2','4'},true),"Wrong PIN readback isn't a confirmation");
        check(!ScooterCommands.pinReadbackMatches(pinFrame,false,null,true),"Wrong enabled state isn't a confirmation");
        check(!ScooterCommands.pinReadbackMatches(notification(0x7a,0,new byte[]{1}),true,null,true),"Malformed PIN readback isn't a confirmation");
        check(!ScooterCommands.pinReadbackMatches(notification(0x7a,7,new byte[0]),true,null,true),"Rejected PIN readback isn't a confirmation");
        rejects(()->ScooterCommands.pinReadbackMatches(pinFrame,true,null,false));
        inspect(ScooterCommands.setProximity(true,true,true,state),0x61);
        inspect(ScooterCommands.setProximity(false,true,false,null),0x61);
        state=ScooterCommands.parsePin(notification(0x7a,0,new byte[]{0,(byte)255,(byte)255,(byte)255,(byte)255}),true);
        check(Boolean.FALSE.equals(state.enabled)&&!state.configured,"Unset PIN sentinel");
        state=ScooterCommands.parsePin(notification(0x7a,7,new byte[0]),true);
        check(state.status==7&&state.enabled==null,"PIN errors never invent state");
        rejects(()->ScooterCommands.readPin(false));
        rejects(()->ScooterCommands.setPin("1234",false));
        rejects(()->ScooterCommands.setPin("12x4",true));
        rejects(()->ScooterCommands.setPin("１２３４",true));
        rejects(()->ScooterCommands.setPin("123",true));
        rejects(()->ScooterCommands.enablePin(true,true,false));
        rejects(()->ScooterCommands.setProximity(true,true,false,null));
        rejects(()->ScooterCommands.setProximity(true,true,true,null));
        rejects(()->ScooterCommands.setProximity(false,false,true,null));
        rejects(()->ScooterCommands.parsePin(notification(0x7a,0,new byte[]{1,1}),true));
        rejects(()->ScooterCommands.parsePin(notification(0x7a,0,new byte[]{1,1,2,3,4}),false));
        for(int cmd:ScooterCommands.diagnosticReads()) check(cmd!=0x7a&&cmd!=0x63,"Diagnostics exclude credentials");
        for(int cmd:new int[]{0x30,0x31,0x63,0x7a}) check(ScooterCommands.sensitiveCommand(cmd),"Sensitive traffic marker");
        check(!ScooterCommands.sensitiveCommand(0x72),"Battery diagnostics aren't credentials");
    }

    private static void dynamicChoices() {
        byte[] data=new byte[38];data[15]=1;
        Protocol.Frame status=notification(0x70,0,data);
        check(Arrays.equals(ScooterCommands.choiceValues("range_algorithm",status),new int[]{0,2,3}),"Mode1 theory Drive Sport choices");
        inspect(ScooterCommands.set("range_algorithm",3,status),0x56);
        final Protocol.Frame mode1=status;
        rejects(()->ScooterCommands.set("range_algorithm",1,mode1));
        rejects(()->ScooterCommands.set("range_algorithm",4,mode1));
        data[15]=2;status=notification(0x70,0,data);
        check(Arrays.equals(ScooterCommands.choiceValues("range_algorithm",status),new int[]{4,2,3}),"Mode2 Eco Drive Sport choices");
        data[15]=0;status=notification(0x70,0,data);
        check(Arrays.equals(ScooterCommands.choiceValues("range_algorithm",status),new int[]{0}),"Mode0 UT5 offers theory only");
        final Protocol.Frame shortStatus=notification(0x70,0,data);
        rejects(()->ScooterCommands.set("long_range_mode",1,shortStatus));
        rejects(()->ScooterCommands.set("overspeed_control",1,shortStatus));
        rejects(()->ScooterCommands.set("drive_speed_limit",10,shortStatus));
        check(ScooterCommands.choiceValues("range_algorithm",null).length==0,"Missing capability mode offers no invented choice");
        check(ScooterCommands.find("hill_start_assist").label.equals("Walk Assist"),"HAC correct OEM meaning");
        check(ScooterCommands.find("parking_assist").label.equals("Power Assist"),"PAS correct OEM meaning");
        check(ScooterCommands.find("low_power").label.equals("Low Battery Mode"),"Low battery behavior meaning");
    }

    private static void rejectedCommands() {
        rejects(()->ScooterCommands.set("not_a_feature",1));
        rejects(()->ScooterCommands.set("pin",0));
        rejects(()->ScooterCommands.set("charge_start",1));
        rejects(()->ScooterCommands.set("energy_recovery",3));
        rejects(()->ScooterCommands.set("charge_limit",81));
        rejects(()->ScooterCommands.set("maximum_speed",60));
        rejects(()->ScooterCommands.setParams("0x50",1));
        rejects(()->ScooterCommands.setParams("voice",1,101,1));
        rejects(()->ScooterCommands.setParams("voice",1,80,2));
        rejects(()->ScooterCommands.setClock(0,0));
        rejects(()->ScooterCommands.setChargeStart(0xffffffffL,1));
        rejects(()->ScooterCommands.setClock(1,999999));
        for(final int cmd:new int[]{0x50,0x59,0x62,0x64,0x65,0x66,0x67,0x68,0x69,0x77,0x78,0x80,0xa0,0xb0,-1,256})
            rejects(()->Protocol.frame(cmd,new byte[]{1}));
        for(final byte[] payload:new byte[][]{ {0,1},{8,1},{9,1},{4,79},{4,101},{4,81},{3,0,0,0,0},{6,0,0},{1,2},{5,2},{7,2}})
            rejects(()->Protocol.frame(0x6f,payload));
        for(final byte[] payload:new byte[][]{{1,33},{1,71},{1,24},{1,31},{1,0},{1,75},{2,2},{3,(byte)128},{3,(byte)149},{4,1}})
            rejects(()->Protocol.frame(0x6e,payload));
        // The 0x6e selector 1 allowlist is the union of the OEM tables; the UT5 MAX choice set is unchanged.
        for(int kmh:new int[]{25,30,32,35,40,45,50,55,60,65,70})Protocol.frame(0x6e,new byte[]{1,(byte)kmh});
        for(int kmh:new int[]{30,35,45,55,60,65,70})rejects(()->ScooterCommands.set("maximum_speed",kmh));
        // Energy recovery 1 is group M's on/off; 2-29 stay refused and the UT5 MAX never offers 1.
        Protocol.frame(0x53,new byte[]{1});
        for(int level=2;level<30;level++){final int v=level;rejects(()->Protocol.frame(0x53,new byte[]{(byte)v}));}
        rejects(()->ScooterCommands.set("energy_recovery",1));
        rejects(()->Protocol.frame(0x63,new byte[]{0,1,1,2,3,4}));
        rejects(()->Protocol.frame(0x63,new byte[]{1,0,1,2,3,4}));
        rejects(()->Protocol.frame(0x6d,new byte[]{4,0,2}));
        rejects(()->Protocol.frame(0x6d,new byte[]{4,1,3}));
        rejects(()->Protocol.frame(0x6d,new byte[]{5,3,6}));
        rejects(()->Protocol.frame(0x6d,new byte[]{4,2,6}));
        Protocol.frame(0x6d,new byte[]{5,3,5});Protocol.frame(0x6d,new byte[]{4,2,5}); // 2585's six colours
        for(String key:new String[]{"line_color","line_boost_color","star_color","star_boost_color"})for(int c=2;c<=5;c++){final int v=c;rejects(()->ScooterCommands.set(key,v));} // UT5 MAX: two colours
        rejects(()->Protocol.frame(0x81,new byte[]{0,1}));
    }

    private static Protocol.Frame notification(int cmd,int status,byte[] payload) {
        byte[] d=new byte[payload.length+1];d[0]=(byte)status;System.arraycopy(payload,0,d,1,payload.length);
        byte[] p=new byte[d.length+8];p[0]=0x55;p[1]=(byte)0xaa;p[3]=(byte)cmd;p[4]=(byte)d.length;
        System.arraycopy(d,0,p,5,d.length);int sum=0;for(int i=0;i<p.length-3;i++)sum+=p[i]&255;
        p[p.length-3]=(byte)sum;p[p.length-2]=(byte)0xfe;p[p.length-1]=(byte)0xfd;return Protocol.parse(p);
    }
    private static void inspect(byte[] packet,int cmd) {
        check((packet[3]&255)==cmd&&packet[2]==0,"Plain opcode/flag");
        check(packet.length==(packet[4]&255)+8,"Outbound write length");
        int sum=0;for(int i=0;i<packet.length-3;i++)sum+=packet[i]&255;
        check((packet[packet.length-3]&255)==(sum&255),"Outbound checksum");
        check((packet[packet.length-2]&255)==254&&(packet[packet.length-1]&255)==253,"Outbound footer");
    }
    private static void equal(String hex,byte[] bytes){check(hex.equals(Protocol.hex(bytes)),"Frame fixture "+hex);}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    private static void rejects(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}catch(UnsupportedOperationException expected){rejected=true;}check(rejected,"Invalid operation was rejected");}
}
