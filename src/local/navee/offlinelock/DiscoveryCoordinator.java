package local.navee.offlinelock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One bounded, read-only compatibility check. It asks for a fixed list of known read opcodes,
 * one at a time, and records how each was answered; it also notes which unsolicited reports
 * (0x90/0x91/0x92) arrived. It can never produce a write, a PIN read (0x7A) or an
 * authentication frame: the only opcodes it returns come from ORDER. Pure; times are
 * elapsed-clock milliseconds supplied by the caller.
 */
final class DiscoveryCoordinator {
    /** EMPTY_REPLY: status 0 with no data, an acknowledgement that is not proof the read is supported. */
    enum Outcome { PENDING, READ_SUPPORTED, REJECTED, NO_RESPONSE, MALFORMED, EMPTY_REPLY, INTERRUPTED }
    /** Every opcode a check may send, in order. Nothing else is reachable. */
    static final int[] ORDER={0x74,0x70,0x73,0x72,0x76,0x7b,0x71,0x75,0x79};
    static final int[] PASSIVE={0x90,0x91,0x92};
    static final long SPACING_MS=300,ATTEMPT_TIMEOUT_MS=2500,PASSIVE_WINDOW_MS=10000,HARD_CAP_MS=30000;

    static final class Read {
        final int cmd; final ReadPolicy.Cls cls;
        int attempts,status=-1,length=-1; long firstMs=-1; boolean late; Outcome outcome=Outcome.PENDING;
        private long requestedMs=-1;
        Read(int cmd,ReadPolicy.Cls cls){this.cmd=cmd;this.cls=cls;}
        boolean resolved(){return outcome!=Outcome.PENDING;}
        @Override public String toString(){return String.format(java.util.Locale.ROOT,"0x%02X %s %s",cmd,cls,outcome);}
    }

    private final long startMs;
    private final List<Read> reads=new ArrayList<>(),passive=new ArrayList<>();
    private Read outstanding;private long lastRequestMs=Long.MIN_VALUE/2;private boolean stopped,ended;

    DiscoveryCoordinator(long startMs){
        this.startMs=startMs;
        for(int cmd:ORDER)reads.add(new Read(cmd,ReadPolicy.cls(cmd)));
        for(int cmd:PASSIVE)passive.add(new Read(cmd,ReadPolicy.Cls.PASSIVE));
    }
    static int maxAttempts(ReadPolicy.Cls cls){return cls==ReadPolicy.Cls.CORE?3:cls==ReadPolicy.Cls.OPTIONAL?2:1;}
    /** Shortest OEM data length (after the status byte) Volt can use; a shorter success is MALFORMED. */
    static int minimumLength(int cmd){return cmd==0x70?13:cmd==0x74?17:0;}

    long startMs(){return startMs;}

    /**
     * The next read to send now, or null. At most one read is outstanding; requests are at least
     * SPACING_MS apart; an attempt times out after ATTEMPT_TIMEOUT_MS and is retried up to its
     * class's attempt count (core 3, optional 2, probe 1).
     */
    Integer nextRead(long nowMs){
        if(finished(nowMs))return null;
        if(outstanding!=null){
            if(nowMs-outstanding.requestedMs<ATTEMPT_TIMEOUT_MS)return null;
            if(outstanding.attempts>=maxAttempts(outstanding.cls))outstanding.outcome=Outcome.NO_RESPONSE;
            outstanding=null;
        }
        if(nowMs-lastRequestMs<SPACING_MS)return null;
        for(Read r:reads){
            if(r.resolved()||r.attempts>=maxAttempts(r.cls))continue;
            r.attempts++;r.requestedMs=nowMs;outstanding=r;lastRequestMs=nowMs;
            return r.cmd;
        }
        return null;
    }

    /**
     * A notification. dataLength is the OEM length after the status byte. Matched by opcode; a
     * reply to a read that was never requested is ignored, a duplicate is counted once, and a reply
     * after the current attempt timed out still proves the read (late = true). A reply within a
     * retry's own timeout is not late. A status-0 reply with no data is EMPTY_REPLY, never
     * READ_SUPPORTED.
     */
    void onFrame(int cmd,int status,int dataLength,long nowMs){
        for(Read p:passive)if(p.cmd==cmd){
            if(!p.resolved()&&!ended){p.outcome=Outcome.READ_SUPPORTED;p.status=status&255;p.length=Math.max(0,Math.min(255,dataLength));p.firstMs=nowMs;}
            return;
        }
        Read r=null;for(Read candidate:reads)if(candidate.cmd==cmd){r=candidate;break;}
        if(r==null||r.attempts==0||ended)return;
        if(r.resolved()&&r.outcome!=Outcome.NO_RESPONSE)return;
        boolean late=outstanding!=r||nowMs-r.requestedMs>=ATTEMPT_TIMEOUT_MS;
        r.status=status&255;r.length=Math.max(0,Math.min(255,dataLength));r.firstMs=nowMs;r.late=late;
        r.outcome=status!=0?Outcome.REJECTED:dataLength<=0?Outcome.EMPTY_REPLY:dataLength<minimumLength(cmd)?Outcome.MALFORMED:Outcome.READ_SUPPORTED;
        if(outstanding==r)outstanding=null;
    }

    /** The link dropped: every unresolved read becomes INTERRUPTED and the check ends. */
    void onDisconnect(long nowMs){interrupt(nowMs);}
    /** Stopped by the owner (Stop, or a lock change): the same as a disconnect. */
    void interrupt(long nowMs){
        if(ended)return;
        for(Read r:reads)if(!r.resolved())r.outcome=Outcome.INTERRUPTED;
        outstanding=null;stopped=ended=true;
    }
    /** Ended by interrupt() or onDisconnect(), not by finishing. */
    boolean interrupted(){return stopped;}

    /** All reads resolved and the passive window (10 s) elapsed, or 30 s passed, or stopped. */
    boolean finished(long nowMs){
        if(ended)return true;
        long elapsed=nowMs-startMs;
        if(elapsed>=HARD_CAP_MS){
            // Time is up: a read that was tried and never answered had no response; one that was never tried was cut short.
            for(Read r:reads)if(!r.resolved())r.outcome=r.attempts>0?Outcome.NO_RESPONSE:Outcome.INTERRUPTED;
            outstanding=null;ended=true;return true;
        }
        if(outstanding!=null&&nowMs-outstanding.requestedMs>=ATTEMPT_TIMEOUT_MS&&outstanding.attempts>=maxAttempts(outstanding.cls)){
            outstanding.outcome=Outcome.NO_RESPONSE;outstanding=null;
        }
        for(Read r:reads)if(!r.resolved())return false;
        if(elapsed<PASSIVE_WINDOW_MS)return false;
        ended=true;return true;
    }
    /** Reads resolved so far, for progress. */
    int resolved(){int n=0;for(Read r:reads)if(r.resolved())n++;return n;}
    /** The read being waited on, or null. */
    Integer current(){return outstanding==null?null:outstanding.cmd;}
    List<Read> reads(){return Collections.unmodifiableList(reads);}
    /** 0x90/0x91/0x92 seen + length; absence is not evidence of no support (they may only arrive while riding). */
    List<Read> passive(){return Collections.unmodifiableList(passive);}
}
