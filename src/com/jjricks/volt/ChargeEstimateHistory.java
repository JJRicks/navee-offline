package com.jjricks.volt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Saved predictions and observed completion on a stable elapsed-time axis. No estimation here. */
public final class ChargeEstimateHistory {
    private ChargeEstimateHistory() {}

    public static final class Point {
        public final double elapsedMinutes;
        public final long wallMs;
        public final Double remainingMinutes, actualRemainingMinutes;
        public final Long predictedFinishWallMs, actualFinishWallMs;
        public final boolean gap,actualGap;
        public final ChargeSessionStore.Estimate estimate;

        private Point(double elapsedMinutes,long wallMs,Double remainingMinutes,Double actualRemainingMinutes,
                Long predictedFinishWallMs,Long actualFinishWallMs,boolean gap,boolean actualGap,ChargeSessionStore.Estimate estimate) {
            this.elapsedMinutes=elapsedMinutes;this.wallMs=wallMs;this.remainingMinutes=remainingMinutes;
            this.actualRemainingMinutes=actualRemainingMinutes;this.predictedFinishWallMs=predictedFinishWallMs;
            this.actualFinishWallMs=actualFinishWallMs;this.gap=gap;this.actualGap=actualGap;this.estimate=estimate;
        }
    }

    /**
     * The full-charge comparison ends at the observed displayed 100%, not a guessed battery
     * balancing/charger shutoff time. A lower configured cutoff is never compared against 100%.
     * Every original sample is retained; missing estimates and recovery gaps break the trace.
     */
    public static List<Point> build(ChargeSessionStore.HistorySession session,boolean toLimit) {
        if(session==null)return Collections.emptyList();
        List<Point> result=new ArrayList<>();long previousMono=0;
        Double previousRemaining=null,previousTarget=null;String previousVersion=null;boolean skipped=false;
        for(ChargeSessionStore.HistoryPoint sample:session.points) {
            if(sample.mono<session.startedMono||sample.mono-session.startedMono>ChargeSessionStore.MAX_SESSION_MS
                    ||sample.mono<=previousMono){skipped=true;continue;}
            ChargeSessionStore.Estimate saved=sample.estimate;
            Double remaining=saved==null?null:toLimit?saved.remainingMinutesToLimit:saved.remainingMinutesToFull;
            String version=saved==null?null:saved.version;Double target=saved==null?null:saved.targetPercent;
            long wall=session.clockAt(sample.mono);
            Long predicted=null;
            if(remaining!=null&&remaining<=((Long.MAX_VALUE-wall)/60000d)){
                long duration=Math.round(remaining*60000d);
                if(duration>=0&&duration<=Long.MAX_VALUE-wall)predicted=wall+duration;
            }
            Long actualFinish=session.observedCompleteWall;
            if(toLimit&&(target==null||target!=100))actualFinish=null;
            Double actual=actualFinish==null?null:(session.observedCompleteMono-sample.mono)/60000d;
            boolean actualGap=sample.gap||previousMono==0||skipped||sample.mono-previousMono>ChargeSessionStore.MAX_GAP_MS;
            boolean gap=actualGap||previousRemaining==null||!Objects.equals(version,previousVersion)
                    ||toLimit&&!Objects.equals(target,previousTarget);
            result.add(new Point((sample.mono-session.startedMono)/60000d,wall,remaining,actual,predicted,actualFinish,gap,actualGap,saved));
            previousMono=sample.mono;previousRemaining=remaining;previousTarget=target;previousVersion=version;skipped=false;
        }
        return Collections.unmodifiableList(result);
    }
}
