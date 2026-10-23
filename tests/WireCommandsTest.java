package com.jjricks.volt;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Freezes every v1.3.1 command byte. FROZEN was generated once from the v1.3.1 (d3d61e8)
 * ScooterCommands builders: each scalar feature swept over -2..300, voice over a parameter grid,
 * and time builders over boundary cases. "x" = rejected. Regenerate only for a deliberate wire change.
 */
public final class WireCommandsTest {
    private static int assertions;
    private interface Build { byte[] get(); }
    private static final String[] FROZEN={
        "set lock 0:55aa0051010051fefd 1:55aa0051010152fefd",
        "set cruise_control 0:55aa0052010052fefd 1:55aa0052010153fefd",
        "set energy_recovery 0:55aa0053010053fefd 30:55aa0053011e71fefd 60:55aa0053013c8ffefd 90:55aa0053015aadfefd",
        "set acceleration 3:55aa005801035bfefd 5:55aa005801055dfefd",
        "set headlight 0:55aa0054010054fefd 1:55aa0054010155fefd",
        "set auto_sensor 0:55aa0057010057fefd 1:55aa0057010158fefd",
        "set traction_control 0:55aa005f01005ffefd 1:55aa005f010160fefd",
        "set turn_sound 0:55aa0060010060fefd 1:55aa0060010161fefd",
        "set display_units 0:55aa0055010055fefd 1:55aa0055010156fefd",
        "set range_algorithm 0:55aa0056010056fefd 1:55aa0056010157fefd 2:55aa0056010258fefd 3:55aa0056010359fefd 4:55aa005601045afefd",
        "set start_speed 0:55aa006a01006afefd 1:55aa006a01016bfefd 2:55aa006a01026cfefd 3:55aa006a01036dfefd 4:55aa006a01046efefd 5:55aa006a01056ffefd",
        "set speed_limit 0:55aa006b01006bfefd 8:55aa006b0188f3fefd 9:55aa006b0189f4fefd 10:55aa006b018af5fefd 11:55aa006b018bf6fefd 12:55aa006b018cf7fefd 13:55aa006b018df8fefd 14:55aa006b018ef9fefd 15:55aa006b018ffafefd 16:55aa006b0190fbfefd 17:55aa006b0191fcfefd 18:55aa006b0192fdfefd 19:55aa006b0193fefefd 20:55aa006b0194fffefd 21:55aa006b019500fefd 22:55aa006b019601fefd 23:55aa006b019702fefd 24:55aa006b019803fefd 25:55aa006b019904fefd 26:55aa006b019a05fefd 27:55aa006b019b06fefd 28:55aa006b019c07fefd 29:55aa006b019d08fefd 30:55aa006b019e09fefd 31:55aa006b019f0afefd 32:55aa006b01a00bfefd",
        "set maximum_speed 25:55aa006e02011989fefd 32:55aa006e02012090fefd 40:55aa006e02012898fefd 50:55aa006e020132a2fefd",
        "set dual_drive 0:55aa006e02020071fefd 1:55aa006e02020172fefd",
        "set drive_speed_limit 0:55aa006e02030072fefd 5:55aa006e020385f7fefd 6:55aa006e020386f8fefd 7:55aa006e020387f9fefd 8:55aa006e020388fafefd 9:55aa006e020389fbfefd 10:55aa006e02038afcfefd 11:55aa006e02038bfdfefd 12:55aa006e02038cfefefd 13:55aa006e02038dfffefd 14:55aa006e02038e00fefd 15:55aa006e02038f01fefd 16:55aa006e02039002fefd 17:55aa006e02039103fefd 18:55aa006e02039204fefd 19:55aa006e02039305fefd 20:55aa006e02039406fefd",
        "set charge_limit 80:55aa006f020450c4fefd 85:55aa006f020455c9fefd 90:55aa006f02045acefefd 95:55aa006f02045fd3fefd 100:55aa006f020464d8fefd",
        "set timed_charging 0:55aa006f02010071fefd 1:55aa006f02010172fefd",
        "set lock_expiry 1:55aa006f02020173fefd 2:55aa006f02020274fefd 3:55aa006f02020375fefd",
        "set low_power 0:55aa006f02050075fefd 1:55aa006f02050176fefd",
        "set long_range_mode 0:55aa006f02070077fefd 1:55aa006f02070178fefd",
        "set hill_start_assist 0:55aa008102010083fefd 1:55aa008102010184fefd",
        "set parking_assist 0:55aa008102020084fefd 1:55aa008102020185fefd",
        "set hill_descent_control 0:55aa008102030085fefd 1:55aa008102030186fefd",
        "set overspeed_control 0:55aa0082010082fefd 1:55aa0082010183fefd",
        "set prompt_tone 0:55aa006c01006cfefd 1:55aa006c01016dfefd 2:55aa006c01026efefd 3:55aa006c01036ffefd 4:55aa006c010470fefd",
        "set line_light 0:55aa006d0304000073fefd 1:55aa006d0304000174fefd",
        "set line_pattern 0:55aa006d0304010074fefd 1:55aa006d0304010175fefd 2:55aa006d0304010276fefd",
        "set line_color 0:55aa006d0304020075fefd 1:55aa006d0304020176fefd",
        "set line_boost_color 0:55aa006d0304030076fefd 1:55aa006d0304030177fefd",
        "set star_light 0:55aa006d0305000074fefd 1:55aa006d0305000175fefd",
        "set star_pattern 0:55aa006d0305010075fefd 1:55aa006d0305010176fefd 2:55aa006d0305010277fefd",
        "set star_color 0:55aa006d0305020076fefd 1:55aa006d0305020177fefd",
        "set star_boost_color 0:55aa006d0305030077fefd 1:55aa006d0305030178fefd",
        "set proximity_key 0:55aa0061010061fefd 1:55aa0061010162fefd",
        "voice -1 -1,-1:x -1,0:x -1,1:x -1,2:x 0,-1:x 0,0:x 0,1:x 0,2:x 1,-1:x 1,0:x 1,1:x 1,2:x 50,-1:x 50,0:x 50,1:x 50,2:x 99,-1:x 99,0:x 99,1:x 99,2:x 100,-1:x 100,0:x 100,1:x 100,2:x 101,-1:x 101,0:x 101,1:x 101,2:x 127,-1:x 127,0:x 127,1:x 127,2:x",
        "voice 0 -1,-1:x -1,0:x -1,1:x -1,2:x 0,-1:x 0,0:55aa006c0200006dfefd 0,1:55aa006c0200016efefd 0,2:x 1,-1:x 1,0:55aa006c0200006dfefd 1,1:55aa006c0200016efefd 1,2:x 50,-1:x 50,0:55aa006c0200006dfefd 50,1:55aa006c0200016efefd 50,2:x 99,-1:x 99,0:55aa006c0200006dfefd 99,1:55aa006c0200016efefd 99,2:x 100,-1:x 100,0:55aa006c0200006dfefd 100,1:55aa006c0200016efefd 100,2:x 101,-1:x 101,0:x 101,1:x 101,2:x 127,-1:x 127,0:x 127,1:x 127,2:x",
        "voice 1 -1,-1:x -1,0:x -1,1:x -1,2:x 0,-1:x 0,0:55aa006c028000edfefd 0,1:55aa006c028001eefefd 0,2:x 1,-1:x 1,0:55aa006c028100eefefd 1,1:55aa006c028101effefd 1,2:x 50,-1:x 50,0:55aa006c02b2001ffefd 50,1:55aa006c02b20120fefd 50,2:x 99,-1:x 99,0:55aa006c02e30050fefd 99,1:55aa006c02e30151fefd 99,2:x 100,-1:x 100,0:55aa006c02e40051fefd 100,1:55aa006c02e40152fefd 100,2:x 101,-1:x 101,0:x 101,1:x 101,2:x 127,-1:x 127,0:x 127,1:x 127,2:x",
        "voice 2 -1,-1:x -1,0:x -1,1:x -1,2:x 0,-1:x 0,0:x 0,1:x 0,2:x 1,-1:x 1,0:x 1,1:x 1,2:x 50,-1:x 50,0:x 50,1:x 50,2:x 99,-1:x 99,0:x 99,1:x 99,2:x 100,-1:x 100,0:x 100,1:x 100,2:x 101,-1:x 101,0:x 101,1:x 101,2:x 127,-1:x 127,0:x 127,1:x 127,2:x",
        "charge_start 16909060,0:55aa006f05030102030480fefd 16909060,3600:55aa006f0503010211149efefd 1700000000,-25200:55aa006f050365538e904cfefd 1,0:55aa006f05030000000177fefd 0,0:x 4294967295,0:55aa006f0503ffffffff72fefd 4294967295,1:x 1700000000,64800:55aa006f05036554ee203dfefd 1700000000,64801:x 1700000000,-64800:55aa006f05036552f3e000fefd 1700000000,-64801:x 3600,-3600:x 3601,-3600:55aa006f05030000000177fefd -5,10:x",
        "clock 16909060,0:55aa006f05060102030483fefd 16909060,3600:55aa006f050601021114a1fefd 1700000000,-25200:55aa006f050665538e904ffefd 1,0:55aa006f0506000000017afefd 0,0:x 4294967295,0:55aa006f0506ffffffff75fefd 4294967295,1:x 1700000000,64800:55aa006f05066554ee2040fefd 1700000000,64801:x 1700000000,-64800:55aa006f05066552f3e003fefd 1700000000,-64801:x 3600,-3600:x 3601,-3600:55aa006f0506000000017afefd -5,10:x",
        "proximity_off 55aa0061010061fefd",
        "pin 1234 55aa00630601010102030474fefd",
        "pin 0000 55aa0063060101000000006afefd",
        "pin_enable 0 55aa00630600000000000068fefd",
        "pin_enable 1 55aa00630601000000000069fefd"
    };

    public static void main(String[] args) {
        frozenCatalogue(); frozenBuilders(); encoders(); allowlistStillApplies();
        System.out.println("WireCommands tests passed: "+assertions+" assertions");
    }

    private static void frozenCatalogue() {
        Map<String,Map<Integer,String>> frozen=new HashMap<>();
        for(String line:FROZEN){
            if(!line.startsWith("set "))continue;String[] p=line.split(" ");Map<Integer,String> values=new HashMap<>();
            for(int i=2;i<p.length;i++){String[] kv=p[i].split(":");values.put(Integer.parseInt(kv[0]),kv[1]);}
            frozen.put(p[1],values);
        }
        Set<String> keys=new LinkedHashSet<>();
        for(ScooterCommands.Feature f:ScooterCommands.catalog()){
            if(f.sensitive||f.type==ScooterCommands.Type.TIME){rejects(()->ScooterCommands.set(f.key,0));continue;}
            keys.add(f.key);Map<Integer,String> values=frozen.get(f.key);check(values!=null,"Frozen table covers "+f.key);
            FeatureVariant ut5=FeatureVariant.ut5(f);
            for(int v=-2;v<=300;v++){
                final int value=v;String expected=values.get(v);
                if(expected==null){rejects(()->ScooterCommands.set(f.key,value));rejects(()->ut5.encode(value));rejects(()->ScooterCommands.setParams(f.key,value));continue;}
                equal(expected,ScooterCommands.set(f.key,v),f.key+" "+v);
                equal(expected,ScooterCommands.setParams(f.key,v),f.key+" params "+v);
                equal(expected,ut5.encode(v),f.key+" UT5 variant "+v);
            }
        }
        check(keys.equals(frozen.keySet()),"Catalogue keys are exactly the frozen v1.3.1 keys");
    }

    private static void frozenBuilders() {
        int checked=0;
        for(String line:FROZEN){
            String[] p=line.split(" ");
            if(p[0].equals("voice")){
                int enabled=Integer.parseInt(p[1]);
                for(int i=2;i<p.length;i++){String[] kv=p[i].split(":"),args=kv[0].split(",");int volume=Integer.parseInt(args[0]),language=Integer.parseInt(args[1]);
                    same(kv[1],()->ScooterCommands.setParams("voice",enabled,volume,language),"voice");checked++;}
            } else if(p[0].equals("charge_start")||p[0].equals("clock")){
                boolean clock=p[0].equals("clock");
                for(int i=1;i<p.length;i++){String[] kv=p[i].split(":"),args=kv[0].split(",");long seconds=Long.parseLong(args[0]);int offset=Integer.parseInt(args[1]);
                    same(kv[1],()->clock?ScooterCommands.setClock(seconds,offset):ScooterCommands.setChargeStart(seconds,offset),p[0]);checked++;}
            } else if(p[0].equals("proximity_off")){same(p[1],()->ScooterCommands.setProximity(false,true,false,null),"proximity off");checked++;}
            else if(p[0].equals("pin")){same(p[2],()->ScooterCommands.setPin(p[1],true),"pin");checked++;}
            else if(p[0].equals("pin_enable")){same(p[2],()->ScooterCommands.enablePin(p[1].equals("1"),true,p[1].equals("1")),"pin enable");checked++;}
        }
        check(checked==128+28+1+2+2,"Every frozen builder case ran");
    }

    private static void encoders() {
        equal("55aa0051010152fefd",WireCommands.scalar(0x51,1),"scalar");
        equal(Protocol.hex(ScooterCommands.set("maximum_speed",32)),WireCommands.selector(0x6e,1,32),"selector");
        equal(Protocol.hex(ScooterCommands.set("speed_limit",25)),WireCommands.enabledCap(0x6b,-1,25,true),"one-byte cap");
        equal(Protocol.hex(ScooterCommands.set("speed_limit",0)),WireCommands.enabledCap(0x6b,-1,25,false),"cap off sends 0");
        equal(Protocol.hex(ScooterCommands.set("drive_speed_limit",20)),WireCommands.enabledCap(0x6e,3,20,true),"selector cap");
        equal(Protocol.hex(ScooterCommands.set("star_pattern",2)),WireCommands.light(5,1,2),"light");
        equal(Protocol.hex(ScooterCommands.setClock(0x01020304L,0)),WireCommands.localTime(6,0x01020304L,0),"clock");
        equal(Protocol.hex(ScooterCommands.setParams("voice",1,80,1)),WireCommands.voice(true,80,1),"voice");
        // A non-UT5 variant encodes through the same single encoder per operation.
        FeatureVariant shared=ModelRegistry.SHARED_LOCK;
        equal(Protocol.hex(ScooterCommands.set("lock",1)),shared.encode(1),"shared lock");
        equal(Protocol.hex(ScooterCommands.set("lock",0)),shared.encode(0),"shared unlock");
        rejects(()->shared.encode(2));rejects(()->ModelRegistry.CLOCK.encode(0));
    }

    private static void allowlistStillApplies() {
        rejects(()->WireCommands.scalar(0x53,2));          // energy recovery 2-29 are not admitted (1 is group M's on)
        rejects(()->WireCommands.scalar(0x53,29));
        rejects(()->WireCommands.selector(0x6f,8,1));      // country config is never sent
        rejects(()->WireCommands.selector(0x6e,1,33));     // outside every OEM max-speed table
        rejects(()->WireCommands.selector(0x6e,1,71));
        rejects(()->WireCommands.enabledCap(0x6b,-1,33,true));
        rejects(()->WireCommands.light(4,2,6));          // outside every OEM colour table (0-5)
        rejects(()->WireCommands.light(5,3,6));
        rejects(()->WireCommands.scalar(0x80,0));          // weather is excluded
        rejects(()->WireCommands.localTime(8,1700000000L,0));
        rejects(()->WireCommands.voice(true,101,1));
    }

    private static void same(String expected,Build build,String what){
        if(expected.equals("x"))rejects(()->build.get());else equal(expected,build.get(),what);
    }
    private static void equal(String hex,byte[] bytes,String what){check(hex.equals(Protocol.hex(bytes)),"Frozen bytes for "+what+": expected "+hex+", got "+Protocol.hex(bytes));}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
    private static void rejects(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Invalid operation was rejected");}
}
