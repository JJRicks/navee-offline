package local.navee.offlinelock;
import java.nio.file.*;
import java.util.*;
final class SecondScooterReplay {
 static ChargingEstimate.Value live(Double v,long t){return new ChargingEstimate.Value(v,true,t);}
 static String val(Double v){return v==null?"":v.toString();}
 public static void main(String[]args)throws Exception{
  List<String> lines=Files.readAllLines(Paths.get(args[0]));
  System.out.println(lines.get(0)+",estimate,limit80,limit95,source,voltage_match");
  ChargeReferenceGate[] gates={new ChargeReferenceGate(),new ChargeReferenceGate(),new ChargeReferenceGate()};
  for(int n=1;n<lines.size();n++){
   String[] p=lines.get(n).split(",",-1);long t=Long.parseLong(p[0])+1000000L;double soc=Double.parseDouble(p[1]),v=Double.parseDouble(p[2]),a=Double.parseDouble(p[3]);
   if(p[6].equals("1"))for(ChargeReferenceGate g:gates)g.reset();
   ChargingEstimate.Result[] r=new ChargingEstimate.Result[3];Double[] targets={100d,80d,95d};
   for(int k=0;k<3;k++){
    ChargingEstimate.Result base=ChargingEstimate.calculate(live(1d,t),live(v,t),live(a,t),live(soc,t),live(15.3,t),live(targets[k],t));
    r[k]=ChargingEstimate.forScooter(base,live(soc,t),live(15.3,t),2538,ChargeSessionStore.Charger.STOCK,null,gates[k],"independent-fixture",t,t);
   }
   System.out.println(lines.get(n)+","+val(r[0].remainingMinutesToFull)+","+val(r[1].remainingMinutesToLimit)+","+val(r[2].remainingMinutesToLimit)+","+(r[0].referenceToFull?"STOCK_REFERENCE":"CURRENT_RATE")+","+r[0].stockVoltageToFull);
  }
 }
}
