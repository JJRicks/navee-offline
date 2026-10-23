package local.navee.offlinelock;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline evidence replay: anonymous journals, prior physical charges only. */
final class StartupReplay {
    static ChargingEstimate.Value live(Double n,long t){return new ChargingEstimate.Value(n,true,t);}
    static String n(Double d){return d==null?"":d.toString();}
    static String source(ChargingEstimate.Result r){return r.learnedToFull?"PERSONAL":r.referenceToFull?(r.referenceCharger==ChargeSessionStore.Charger.STOCK?"STOCK_REFERENCE":"FAST_REFERENCE"):"CURRENT_RATE";}
    static ChargingEstimate.Result estimate(String[] p, Double target, ChargeSessionStore.Curve history, ChargeReferenceGate gate, ChargeSessionStore.Charger charger){
        long t=Long.parseLong(p[3])+1000000L;
        double soc=Double.parseDouble(p[5]),v=Double.parseDouble(p[6]),a=Double.parseDouble(p[7]);
        ChargingEstimate.Result base=ChargingEstimate.calculate(live(1d,t),live(v,t),live(a,t),live(soc,t),live(15.3,t),live(target,t));
        return ChargingEstimate.forScooter(base,live(soc,t),live(15.3,t),2538,charger,history,gate,"fixture",t,t);
    }
    public static void main(String[] args)throws Exception{
        Path fixtures=Paths.get(args[0]);
        String header=Files.readAllLines(fixtures.resolve("points-0.csv")).get(0);
        System.out.println(header+",mode,qualified_prior_journals,full,full_source,learned_sessions,limit100,full80,limit80,full95,limit95,fullnull,limitnull,voltage_match");
        for(int session=0;session<10;session++){
            Path historyPath=Files.createTempDirectory(Paths.get(args[1]),"anonymous-history-");
            try{
                for(int prior=0;prior<session;prior++){
                    if(session==8&&prior==7)continue; // both parts are one physical evaluation charge
                    Files.copy(fixtures.resolve(String.format("journal-%d.csv",prior)),historyPath.resolve(String.format("00000000-0000-0000-0000-%012d.csv",prior)));
                }
                ChargeSessionStore store=new ChargeSessionStore(historyPath.toFile());
                ChargeSessionStore.Snapshot snapshot=store.snapshot();
                List<String> lines=Files.readAllLines(fixtures.resolve("points-"+session+".csv"));
                ChargeSessionStore.Charger charger=ChargeSessionStore.Charger.valueOf(lines.get(1).split(",",-1)[16]);
                ChargeSessionStore.Curve history=snapshot.curves.get(ChargeSessionStore.key("fixture",charger));
                for(String mode:new String[]{"bundled","runtime"}){
                    ChargeReferenceGate[] gates={new ChargeReferenceGate(),new ChargeReferenceGate(),new ChargeReferenceGate(),new ChargeReferenceGate()};
                    for(int j=1;j<lines.size();j++){
                        String line=lines.get(j);String[] p=line.split(",",-1);
                        if(p[8].equals("1"))for(ChargeReferenceGate g:gates)g.reset();
                        ChargeSessionStore.Curve curve=mode.equals("runtime")?history:null;
                        ChargingEstimate.Result full=estimate(p,100d,curve,gates[0],charger),lower=estimate(p,80d,curve,gates[1],charger),upper=estimate(p,95d,curve,gates[2],charger),unknown=estimate(p,null,curve,gates[3],charger);
                        System.out.println(line+","+mode+","+(history==null?0:history.qualifiedSessions)+","+n(full.remainingMinutesToFull)+","+source(full)+","+full.learnedSessions+","+n(full.remainingMinutesToLimit)+","+n(lower.remainingMinutesToFull)+","+n(lower.remainingMinutesToLimit)+","+n(upper.remainingMinutesToFull)+","+n(upper.remainingMinutesToLimit)+","+n(unknown.remainingMinutesToFull)+","+n(unknown.remainingMinutesToLimit)+","+full.stockVoltageToFull);
                    }
                }
            }finally{
                try(java.util.stream.Stream<Path> files=Files.list(historyPath)){for(Path p:(Iterable<Path>)files::iterator)Files.delete(p);}
                Files.delete(historyPath);
            }
        }
    }
}
