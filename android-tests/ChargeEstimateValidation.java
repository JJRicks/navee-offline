package local.navee.offlinelock.tests;

import android.app.Dialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.CheckBox;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.*;

/** Disposable emulator only. Saved prediction UI and background calculation; no real Bluetooth. */
public final class ChargeEstimateValidation extends Instrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle args;
    private String profile,completeId,legacyId,partialId,extremeId,tinyId;
    private int checks;
    private long lastTouchTime;
    @Override public void onCreate(Bundle args){this.args=args==null?new Bundle():args;start();}
    @Override public void onStart(){Bundle result=new Bundle();
        try{
            admit();
            require(settings().edit().putBoolean("auto_connect",false).putBoolean("background_connection",false)
                    .putBoolean("developer_options",true).putString("theme",args.getString("theme","dark"))
                    .putString("time_format","24h").putString("temperature_unit","f").commit(),"Settings fixture failed");
            main(()->{ScooterProfiles profiles=new ScooterProfiles(getTargetContext());ScooterProfiles.Profile p=profiles.create("Synthetic estimate fixture","000000000000",0);p.serial="X2538-SYNTHETIC";profiles.update(p);profile=p.id;});
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->activity.service()!=null,"Service missing");service=activity.service();
            await(()->!Boolean.TRUE.equals(field(service,"chargeModelLoading")),"Charge model load did not finish");
            verifyServiceSnapshot();verifyChargerCaption();seedRecordings();verifyAsyncHistory();verifyRecordingsStayLabels();verifyDetectionWiring();
            main(()->{invoke(activity,"select",new Class<?>[]{String.class},"more");invoke(field(activity,"screen"),"selectSection",new Class<?>[]{String.class},"Developer");});settle();
            main(()->{View row=root().findViewWithTag("charge-history-action");require(row!=null&&row.isEnabled(),"Charge history unavailable offline");row.performClick();});
            await(()->dialog()!=null&&dialog().getWindow().getDecorView().findViewWithTag("charge-history-session:"+completeId)!=null,"History did not load");
            main(()->require(dialogRoot().findViewWithTag("charge-history-session:"+legacyId)!=null,"Legacy recording missing"));
            main(()->assertHeaderFits(dialog(),"Close charge recordings"));screenshot("history-list");
            open(completeId);
            main(()->{
                require(text(dialogRoot()).contains("100%"),"Completion note missing");
                RideChartView chart=chart(dialogRoot(),false);
                require(dialogRoot().findViewWithTag("charge-history-panel")!=null,"Combined chart panel missing");
                require(dialogRoot().findViewWithTag("charge-history-choices")!=null,"Series choices missing");
                require(keys(chart).equals(new LinkedHashSet<>(Arrays.asList("power_w","battery_percent","estimated_finish","estimated_countdown","observed_finish"))),"Wrong default charge categories");
                Set<Integer> colors=new HashSet<>();for(RideChartData.Series series:series(chart))colors.add(series.color);
                require(colors.size()==5,"Default charge categories share their line color");
                require(((CheckBox)dialogRoot().findViewWithTag("charge-series:observed_finish")).isChecked(),"Observed finish is not selected by default");
                RideChartData.Series estimatedFinish=findSeries(chart,"estimated_finish"),observedFinish=findSeries(chart,"observed_finish");
                require(estimatedFinish.min==observedFinish.min&&estimatedFinish.max==observedFinish.max,"Estimated and observed finish use different scales");
                assertSample(chart,"observed_finish",0,1735776010000d);
                assertSample(chart,"power_w",0,-104d);assertSample(chart,"battery_percent",0,99d);
                assertSample(chart,"estimated_countdown",0,3d);
                touch(chart,0);
                String trace=text(dialogRoot().findViewWithTag("charge-history-trace"));
                require(trace.contains("fixture-v1"),"Saved estimator version missing");
                require(trace.contains("3.00")&&trace.contains("min"),"Trace did not preserve first estimate");
                require(trace.contains("-104")&&trace.contains("99"),"Trace dropped raw electrical readings");
                require(trace.contains(":"),"Finish estimate is not a clock");
                require(chart.performAccessibilityAction(0x7e0a0005,null),"Next recorded sample accessibility action missing");
                require((Double)field(chart,"inspectedTime")==10000d,"Accessibility next did not select a real sample");
                require(chart.performAccessibilityAction(0x7e0a0004,null),"Previous recorded sample accessibility action missing");
                require((Double)field(chart,"inspectedTime")==0d,"Accessibility previous did not return to first sample");
                require(String.valueOf(chart.getContentDescription()).contains("Power"),"Accessible chart omits selected raw reading descriptions");
                verifyClockLabels(chart);
                setChoice(dialogRoot(),false,"estimated_finish",false);setChoice(dialogRoot(),false,"estimated_countdown",false);setChoice(dialogRoot(),false,"observed_finish",false);
                require(keys(chart).equals(new LinkedHashSet<>(Arrays.asList("power_w","battery_percent"))),"Toggles did not isolate power and battery");
                setChoice(dialogRoot(),false,"estimated_finish",true);setChoice(dialogRoot(),false,"estimated_countdown",true);
                setChoice(dialogRoot(),false,"observed_finish",true);setChoice(dialogRoot(),false,"observed_countdown",true);
                assertSample(chart,"observed_countdown",0,70d/60);
                setChoice(dialogRoot(),false,"temperature_c",true);assertSample(chart,"temperature_c",0,73.4d);
                require("°F".equals(findSeries(chart,"temperature_c").unit),"Temperature setting did not reach charge graph");
                setChoice(dialogRoot(),false,"temperature_c",false);
                setChoice(dialogRoot(),false,"voltage_v",true);setChoice(dialogRoot(),false,"current_a",true);
                assertSample(chart,"voltage_v",0,52d);assertSample(chart,"current_a",0,-2d);
                setChoice(dialogRoot(),false,"voltage_v",false);setChoice(dialogRoot(),false,"current_a",false);
            });settle();screenshot("combined-charge-graph");
            main(()->dialogRoot().findViewWithTag("charge-history-expand").performClick());settle();
            main(()->{
                Dialog expanded=(Dialog)field(viewer(),"expanded");
                if(expanded!=null){RideChartView chart=chart(expanded.getWindow().getDecorView(),true);chart.requestRectangleOnScreen(new Rect(0,0,chart.getWidth(),chart.getHeight()),true);}
            });settle();
            final double[] expandedState=new double[3];
            final Set<String> expandedSelection=new LinkedHashSet<>();
            main(()->{
                Dialog expanded=(Dialog)field(viewer(),"expanded");require(expanded!=null&&expanded.isShowing(),"Fullscreen chart missing");
                assertHeaderFits(expanded,"Close expanded charge graph");
                View decor=expanded.getWindow().getDecorView();RideChartView chart=chart(decor,true);
                require(decor.findViewWithTag("charge-history-fullscreen-panel")!=null,"Fullscreen combined panel missing");
                require(decor.findViewWithTag("charge-history-fullscreen-choices")!=null,"Fullscreen series choices missing");
                require(keys(chart).equals(keys(chart(dialogRoot(),false))),"Fullscreen lost selected categories");
                RectF plot=(RectF)field(chart,"plot");
                require(plot.height()>=activity.ui().dp(70),"Fullscreen plot lost its usable height: "+plot.height()+" px inside "+chart.getHeight()+" px chart");
                Rect visible=new Rect();require(chart.getGlobalVisibleRect(visible),"Fullscreen chart is not visible after scrolling");
                int[] location=new int[2];chart.getLocationOnScreen(location);
                RectF screenPlot=new RectF(plot);screenPlot.offset(location[0],location[1]);
                require(screenPlot.left>=visible.left&&screenPlot.right<=visible.right&&screenPlot.top>=visible.top&&screenPlot.bottom<=visible.bottom,
                        "Fullscreen data plot clipped: "+screenPlot+" inside visible "+visible);
                double originalRange=range(chart);
                decor.findViewWithTag("charge-history-fullscreen-zoom-in").performClick();
                require(range(chart)<originalRange,"Fullscreen zoom in did not narrow time range");
                decor.findViewWithTag("charge-history-fullscreen-zoom-out").performClick();
                require(Math.abs(range(chart)-originalRange)<.1,"Fullscreen zoom out did not restore range");
                pinch(chart);
                require(range(chart)<originalRange,"Fullscreen pinch did not zoom: "+range(chart)+" ms after pinch, initially "+originalRange+" ms, plot "+plot.width()+"×"+plot.height()+" px");
                decor.findViewWithTag("charge-history-fullscreen-fit").performClick();
                require(Math.abs(range(chart)-originalRange)<.1,"Fit did not restore whole charge");
                decor.findViewWithTag("charge-history-fullscreen-zoom-in").performClick();
                touch(chart,.75f);require(!text(decor.findViewWithTag("charge-history-fullscreen-trace")).isEmpty(),"Fullscreen touch trace missing");
                require(Double.isFinite((Double)field(chart,"inspectedTime")),"Fullscreen cursor missing");
                setChoice(decor,true,"observed_finish",false);
                setChoice(decor,true,"temperature_c",true);
                require(text(decor.findViewWithTag("charge-history-fullscreen-trace")).contains("°F"),"Temperature trace omitted display unit");
                expandedSelection.addAll(keys(chart));expandedState[0]=(Double)field(chart,"from");expandedState[1]=(Double)field(chart,"to");expandedState[2]=(Double)field(chart,"inspectedTime");
            });settle();screenshot("fullscreen-combined");
            main(()->((Dialog)field(viewer(),"expanded")).dismiss());settle();
            main(()->{
                require(dialogRoot().findViewWithTag("charge-history-detail")!=null,"Closing fullscreen lost recording");
                RideChartView chart=chart(dialogRoot(),false);
                require(keys(chart).equals(expandedSelection),"Closing fullscreen lost category choices");
                require(Math.abs((Double)field(chart,"from")-expandedState[0])<.1&&Math.abs((Double)field(chart,"to")-expandedState[1])<.1,"Closing fullscreen lost zoom position");
                require(Math.abs((Double)field(chart,"inspectedTime")-expandedState[2])<.1,"Closing fullscreen lost inspected sample");
                require(((CheckBox)dialogRoot().findViewWithTag("charge-series:temperature_c")).isChecked(),"Normal category checkbox did not sync from fullscreen");
                dialogRoot().findViewWithTag("charge-history-fit").performClick();
                double originalRange=range(chart);dialogRoot().findViewWithTag("charge-history-zoom-in").performClick();
                require(range(chart)<originalRange,"Inline zoom did not narrow time range");
                dialogRoot().findViewWithTag("charge-history-zoom-out").performClick();
                require(Math.abs(range(chart)-originalRange)<.1,"Inline zoom out did not restore range");
            });
            back();open(partialId);
            main(()->{
                String note=text(dialogRoot().findViewWithTag("charge-history-note"));require(note.toLowerCase(Locale.ROOT).contains("not observed"),"Stopped recording pretends completion");
                RideChartView chart=chart(dialogRoot(),false);
                assertNoDataChoice(dialogRoot(),false,"observed_finish");assertNoDataChoice(dialogRoot(),false,"observed_countdown");
                setChoice(dialogRoot(),false,"limit_countdown",true);setChoice(dialogRoot(),false,"limit_finish",true);setChoice(dialogRoot(),false,"charge_limit",true);
                touch(chart,0);assertSample(chart,"limit_countdown",0,80d);assertSample(chart,"charge_limit",0,80d);
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains("80"),"Saved cutoff missing from trace");
            });screenshot("unfinished-limit");
            back();open(legacyId);
            main(()->{
                String note=text(dialogRoot().findViewWithTag("charge-history-note"));
                require(note.contains("Estimates weren't saved")||note.contains("Estimates weren’t saved"),"Legacy recording explanation missing");
                RideChartView chart=chart(dialogRoot(),false);touch(chart,0);
                assertSample(chart,"power_w",0,-104d);assertSample(chart,"battery_percent",0,99d);
                for(String key:new String[]{"estimated_finish","estimated_countdown"}){
                    RideChartData.Series candidate=findSeries(chart,key);require(candidate==null||!candidate.available(),"Legacy estimates were fabricated");
                }
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains("-104"),"Legacy physical reading disappeared");
            });screenshot("legacy-recording");
            back();open(extremeId);
            main(()->{
                RideChartView chart=chart(dialogRoot(),false);RideChartData.Series estimate=findSeries(chart,"estimated_countdown");
                require(estimate!=null&&Double.isFinite(estimate.min)&&Double.isFinite(estimate.max)&&estimate.max>estimate.min,"Extreme finite ETA overflowed chart bounds");
                require(Double.isFinite(estimate.scale.fraction(Double.MAX_VALUE)),"Extreme finite ETA overflowed chart geometry");
                assertSample(chart,"estimated_countdown",0,Double.MAX_VALUE);touch(chart,0);
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains(Double.toString(Double.MAX_VALUE)),"Extreme raw ETA was changed");
            });
            back();open(tinyId);
            main(()->{
                RideChartView chart=chart(dialogRoot(),false);RideChartData.Series estimate=findSeries(chart,"estimated_countdown");
                require(estimate!=null&&Double.isFinite(estimate.min)&&Double.isFinite(estimate.max)&&estimate.max>estimate.min,"Tiny ETA lost finite chart bounds");
                require(Double.isFinite(estimate.scale.fraction(Double.MIN_VALUE)),"Tiny ETA rendered nonfinite geometry");
                assertSample(chart,"estimated_countdown",0,Double.MIN_VALUE);
            });
            main(()->{
                Dialog old=dialog();service.profiles.create("Different synthetic scooter","111111111111",0);
                invoke(viewer(),"refresh",new Class<?>[]{});require(!old.isShowing(),"Profile switch left old charge history visible");
                service.profiles.select(profile);
                require(field(service.ble,"gatt")==null&&field(service.ble,"scanner")==null,"Fixture touched actual Bluetooth");
                require(!service.isChargeRecording()&&!service.isRecording()&&!service.phoneSaving(),"Viewing history started a recording");
            });
            result.putString("result","PASS");
        }catch(Throwable error){result.putString("result","FAIL");result.putString("error",errorChain(error));error.printStackTrace();try{if(activity!=null)screenshot("failure");}catch(Throwable ignored){}}
        finally{result.putInt("assertions",checks);result.putBoolean("real_bluetooth_capture_tested",false);if(activity!=null)main(()->{activity.finish();getTargetContext().stopService(new Intent(getTargetContext(),ScooterService.class));});finish("PASS".equals(result.getString("result"))?0:1,result);}
    }
    private void verifyServiceSnapshot(){main(()->{
        require(service.setSimulation(true),"Preview unavailable");service.ble.setSimulationCharging(true);
        setField(service,"visible",false);
        ChargingEstimate.Result expected=service.chargingEstimate(service.displayReadings());
        ChargeSessionStore.Estimate saved=(ChargeSessionStore.Estimate)invoke(service,"chargeRecordingEstimate",new Class<?>[]{});
        require(saved.version.equals(ChargingEstimate.ESTIMATOR_VERSION),"Service omitted estimator version");
        require(expected.remainingMinutesToFull!=null&&Objects.equals(saved.remainingMinutesToFull,expected.remainingMinutesToFull),"Background snapshot differs from displayed estimate");
        require(Objects.equals(saved.remainingMinutesToLimit,expected.remainingMinutesToLimit),"Snapshot dropped cutoff estimate");
        require(!store().active(),"Reading estimate started capture");
        setField(service,"visible",true);require(service.setSimulation(false),"Preview did not stop");
        ChargeSessionStore.Estimate missing=(ChargeSessionStore.Estimate)invoke(service,"chargeRecordingEstimate",new Class<?>[]{});
        require(missing.remainingMinutesToFull==null&&missing.version.equals(ChargingEstimate.ESTIMATOR_VERSION),"Unavailable estimate was fabricated or unversioned");
    });}
    /** The large-font, two-column Scooter page must retain the full charger label beside its value. */
    private void verifyChargerCaption()throws Exception{
        main(()->invoke(activity,"select",new Class<?>[]{String.class},"controls"));settle();
        for(ChargeSessionStore.Charger charger:new ChargeSessionStore.Charger[]{null,ChargeSessionStore.Charger.HIGH_SPEED}){
            main(()->{require(service.setChargeCharger(charger),"Charger preference unavailable");activity.redraw();});settle();
            main(()->{
                View row=root().findViewWithTag("estimate-charger-setting");require(row!=null,"Charger estimate row missing");
                row.requestRectangleOnScreen(new Rect(0,0,row.getWidth(),row.getHeight()),true);
            });settle();
            main(()->{
                Object row=field(field(activity,"screen"),"chargerLine");
                TextView name=(TextView)field(row,"name"),value=(TextView)field(row,"value");
                require("Charger for estimates".contentEquals(name.getText()),"Charger label changed");
                require((charger==null?"Automatic":"High-speed").contentEquals(value.getText()),"Charger row value missing");
                Layout layout=name.getLayout();require(layout!=null&&layout.getLineCount()>0,"Charger label not laid out");
                int last=layout.getLineCount()-1;
                require(layout.getLineEnd(last)==name.getText().length(),"Charger layout omits the end of the label");
                require(layout.getLineBottom(last)<=name.getHeight()-name.getCompoundPaddingTop()-name.getCompoundPaddingBottom(),"Charger label exceeds its displayed height");
                for(int line=0;line<layout.getLineCount();line++){
                    require(layout.getEllipsisCount(line)==0,"Charger label ellipsized");
                    require(layout.getLineLeft(line)>=-.5f&&layout.getLineRight(line)<=layout.getWidth()+.5f,"Charger label exceeds its text width");
                }
                int[] a=new int[2],b=new int[2];name.getLocationOnScreen(a);value.getLocationOnScreen(b);
                require(a[0]+name.getWidth()<=b[0],"Charger label overlaps the selected charger");
                Rect visible=new Rect();require(name.getGlobalVisibleRect(visible)&&visible.height()==name.getHeight(),"Charger label clipped by its row");
            });
            screenshot(charger==null?"charger-label-automatic":"charger-label-high-speed");
        }
        main(()->require(service.setChargeCharger(null),"Automatic charger preference did not restore"));settle();
    }
    private void seedRecordings()throws Exception{
        long wall=1735775940000L,mono=100000;
        store().start(profile,ChargeSessionStore.Charger.STOCK,wall,mono);
        for(int i=0;i<8;i++){
            ChargeSessionStore.Frame frame=frame(wall+i*10000,mono+i*10000,i==7?100:99);
            ChargeSessionStore.Estimate estimate=new ChargeSessionStore.Estimate("fixture-v1",3d-i*.3,3d-i*.3,100d,ChargeSessionStore.Source.STOCK_REFERENCE,ChargeSessionStore.Source.STOCK_REFERENCE,0);
            require(i==7?store().sampleCompletion(frame,estimate):store().sample(frame,estimate),"Fixture sample rejected");
        }
        store().stop(ChargeSessionStore.Stop.COMPLETE);completeId=recordingAt(mono);
        store().start(profile,ChargeSessionStore.Charger.STOCK,wall+3600000,mono+3600000);
        for(int i=0;i<4;i++)require(store().sample(frame(wall+3600000+i*10000,mono+3600000+i*10000,60),new ChargeSessionStore.Estimate("fixture-v2",200d,80d,80d,ChargeSessionStore.Source.CURRENT_RATE,ChargeSessionStore.Source.CURRENT_RATE,0)),"Partial sample rejected");
        store().stop(ChargeSessionStore.Stop.STOPPED);partialId=recordingAt(mono+3600000);
        legacyId=UUID.randomUUID().toString();
        File file=new File(getTargetContext().getFilesDir(),"charge-recordings/"+legacyId+".csv");
        try(Writer out=new OutputStreamWriter(new FileOutputStream(file),"UTF-8")){
            out.write("charge-v1,"+profile+",STOCK,"+wall+","+mono+"\n");
            out.write("s,"+wall+","+mono+",99.0,52.0,-2.0,-104.0,,1\n");
            out.write("s,"+(wall+10000)+","+(mono+10000)+",100.0,52.0,-2.0,-104.0,,0\nend,COMPLETE\n");
        }
        store().start(profile,ChargeSessionStore.Charger.STOCK,wall+7200000,mono+7200000);
        require(store().sample(frame(wall+7200000,mono+7200000,60),new ChargeSessionStore.Estimate("extreme-fixture",Double.MAX_VALUE,null,100d,ChargeSessionStore.Source.CURRENT_RATE,null,0)),"Extreme sample rejected");
        store().stop(ChargeSessionStore.Stop.STOPPED);extremeId=recordingAt(mono+7200000);
        store().start(profile,ChargeSessionStore.Charger.STOCK,wall+10800000,mono+10800000);
        require(store().sample(frame(wall+10800000,mono+10800000,99),new ChargeSessionStore.Estimate("tiny-fixture",Double.MIN_VALUE,null,100d,ChargeSessionStore.Source.CURRENT_RATE,null,0)),"Tiny sample rejected");
        store().stop(ChargeSessionStore.Stop.STOPPED);tinyId=recordingAt(mono+10800000);
    }
    /** Capture labels never drive Automatic: every model keeps the live rate without detection. */
    private void verifyRecordingsStayLabels()throws Exception{
        // One qualified stock bulk recording: twelve one-percent bins over twelve minutes.
        long wall=1735800000000L,mono=20000000;
        store().start(profile,ChargeSessionStore.Charger.STOCK,wall,mono);
        for(int i=0;i<=72;i++)require(store().sample(frame(wall+i*10000L,mono+i*10000L,40+i/6)),"Qualified fixture sample rejected");
        store().stop(ChargeSessionStore.Stop.STOPPED);
        File qualified=new File(getTargetContext().getFilesDir(),"charge-recordings/"+recordingAt(mono)+".csv");
        main(()->invoke(service,"refreshChargeCurves",new Class<?>[]{}));
        await(()->!Boolean.TRUE.equals(field(service,"chargeModelLoading")),"Curve refresh did not finish");
        main(()->{
            ScooterProfiles.Profile active=service.profiles.active();String serial=active.serial;
            try{
                require(service.chargeCharger()==null,"Recording fixture wrote the charger preference");
                require(service.effectiveChargeCharger()==null&&service.chargerBasis()==ScooterService.ChargerBasis.UNKNOWN&&service.qualifiedChargeCurves()==0,
                        "UT5 MAX Automatic used past labels instead of live detection");
                active.serial="X9999-SYNTHETIC";
                require(service.effectiveChargeCharger()==null&&service.chargerBasis()==ScooterService.ChargerBasis.UNKNOWN&&service.qualifiedChargeCurves()==0,
                        "Undetectable model's Automatic used capture labels instead of the live rate");
            }finally{active.serial=serial;}
        });
        require(qualified.delete(),"Qualified fixture cleanup failed");
        main(()->invoke(service,"refreshChargeCurves",new Class<?>[]{}));
        await(()->!Boolean.TRUE.equals(field(service,"chargeModelLoading")),"Curve cleanup refresh did not finish");
    }
    /** The service keeps a detected family across a lost link and flags the reconnect check. */
    private void verifyDetectionWiring(){main(()->{
        ChargerDetection detection=(ChargerDetection)field(service,"chargerDetection");
        ScooterProfiles.Profile active=service.profiles.active();long now=SystemClock.elapsedRealtime();
        try{
            for(long ago=4000;ago>=0;ago-=2000)detection.observe(active.id,active.pid(),true,50d,50d,-10d,now-ago,now-ago);
            require(service.chargerBasis()==ScooterService.ChargerBasis.DETECTED&&service.effectiveChargeCharger()==ChargeSessionStore.Charger.HIGH_SPEED,
                    "Service ignored the detector's confident family");
            require(service.ble.currentTransportSerial().isEmpty(),"Fixture has a live link");
            invoke(service,"changed",new Class<?>[]{});
            require(service.chargerBasis()==ScooterService.ChargerBasis.DETECTED&&service.effectiveChargeCharger()==ChargeSessionStore.Charger.HIGH_SPEED,
                    "A lost link discarded the detected family");
            require(Boolean.TRUE.equals(field(detection,"relinked")),"A lost link was not noted for the reconnect check");
            require(service.chargeCharger()==null,"Detection wrote the charger preference");
        }finally{detection.reset();}
        require(service.chargerBasis()==ScooterService.ChargerBasis.UNKNOWN,"Detection fixture leaked");
    });}
    private String recordingAt(long startedMono)throws IOException{for(ChargeSessionStore.HistorySession session:store().recordings(profile))if(session.startedMono==startedMono)return session.id;throw new IllegalStateException("Fixture recording missing");}
    private ChargeSessionStore.Frame frame(long wall,long mono,double soc){return new ChargeSessionStore.Frame(wall,mono,new ChargeSessionStore.Value(1d,mono,0x72),new ChargeSessionStore.Value(soc,mono,0x72),new ChargeSessionStore.Value(52d,mono,0x72),new ChargeSessionStore.Value(-2d,mono,0x72),new ChargeSessionStore.Value(23d,mono,0x72));}
    private void verifyAsyncHistory(){
        AtomicReference<List<ChargeSessionStore.HistorySession>> found=new AtomicReference<>();
        main(()->require(service.loadChargeRecordings(found::set),"Async history refused"));await(()->found.get()!=null,"Async history callback missing");
        require(found.get().size()==5,"Wrong profile history count");
        for(ChargeSessionStore.HistorySession session:found.get())require(profile.equals(session.profile),"History leaked profile");
    }
    private void open(String id){main(()->dialogRoot().findViewWithTag("charge-history-session:"+id).performClick());settle();}
    private void back(){main(()->findDescription(dialogRoot(),"Back to charge recordings").performClick());settle();}
    private RideChartView chart(View decor,boolean expanded){View chart=decor.findViewWithTag(expanded?"charge-history-fullscreen-chart":"charge-history-chart");require(chart instanceof RideChartView,"Charge graph does not use ride chart");return(RideChartView)chart;}
    @SuppressWarnings("unchecked") private List<RideChartData.Series> series(RideChartView chart){return(List<RideChartData.Series>)field(chart,"series");}
    private Set<String> keys(RideChartView chart){Set<String> keys=new LinkedHashSet<>();for(RideChartData.Series series:series(chart))keys.add(series.key);return keys;}
    private RideChartData.Series findSeries(RideChartView chart,String key){for(RideChartData.Series series:series(chart))if(key.equals(series.key))return series;return null;}
    private void assertSample(RideChartView chart,String key,double time,double expected){RideChartData.Series series=findSeries(chart,key);require(series!=null,"Missing selected series "+key);RideChartData.Value sample=series.sampleAt(time);require(sample!=null&&sample.value!=null,"Missing real sample "+key);require(Double.compare(sample.value,expected)==0||Math.abs(sample.value-expected)<=Math.abs(expected)*1e-12,"Wrong recorded value "+key+": "+sample.value);}
    private void setChoice(View decor,boolean expanded,String key,boolean checked){View choice=decor.findViewWithTag((expanded?"charge-fullscreen-series:":"charge-series:")+key);require(choice instanceof CheckBox,"Missing category toggle "+key);CheckBox box=(CheckBox)choice;require(box.isEnabled(),"Unavailable category "+key);if(box.isChecked()!=checked)box.performClick();require(box.isChecked()==checked,"Category did not change "+key);}
    private void assertNoDataChoice(View decor,boolean expanded,String key){View choice=decor.findViewWithTag((expanded?"charge-fullscreen-series:":"charge-series:")+key);require(choice==null||!choice.isEnabled(),"Unavailable actual finish category is selectable");}
    private double range(RideChartView chart){return(Double)field(chart,"to")-(Double)field(chart,"from");}
    private void verifyClockLabels(RideChartView chart){
        RideChartView.ValueFormatter formatter=(RideChartView.ValueFormatter)field(chart,"valueFormatter");
        Calendar clock=Calendar.getInstance();clock.set(2025,Calendar.JANUARY,1,23,59,50);clock.set(Calendar.MILLISECOND,0);
        double before=clock.getTimeInMillis(),after=before+20000;
        RideChartData.Series midnight=new RideChartData.Series("estimated_finish","Est. finish time","Time",0xff009e73,2,
                Arrays.asList(new RideChartData.Value(0,before,0),new RideChartData.Value(10000,after,0)),
                ChartScale.range("estimated_finish","Time",false,before,after));
        RideChartData.Series power=findSeries(chart,"power_w");
        require(!formatter.axis(power,power.scale.at(0)).equals(formatter.axis(power,power.scale.at(.25))),"Narrow power range repeats tick labels");
        String left=formatter.axis(midnight,before),right=formatter.axis(midnight,after);
        java.text.SimpleDateFormat day=new java.text.SimpleDateFormat("MMM d",Locale.getDefault());
        require(left.contains(day.format(new Date((long)before)))&&right.contains(day.format(new Date((long)after))),"Midnight finish axis omits distinguishing dates");
        require(!left.equals(right),"Midnight finish axis repeats its labels");
        clock.set(2025,Calendar.JANUARY,1,13,4,5);long wall=clock.getTimeInMillis();
        RideChartData.Value sample=new RideChartData.Value(0,(double)wall,0);
        String twentyFour=formatter.sample(midnight,sample);
        require(settings().edit().putString("time_format","12h").commit(),"Cannot select 12-hour test clock");
        try{
            String twelve=formatter.sample(midnight,sample);
            require(!twelve.equals(twentyFour),"Finish trace ignores the selected 12/24-hour format");
            String pm=java.text.DateFormatSymbols.getInstance().getAmPmStrings()[1];
            require(twelve.contains(pm)&&!twentyFour.contains(pm),"Finish trace does not distinguish 12-hour clock period");
        }finally{require(settings().edit().putString("time_format","24h").commit(),"Cannot restore 24-hour test clock");}
    }
    private void assertHeaderFits(Dialog opened,String description){
        View decor=opened.getWindow().getDecorView(),button=findDescription(decor,description);Rect visible=new Rect();
        require(button!=null&&button.getGlobalVisibleRect(visible),"Header action is not visible");
        android.view.WindowInsets insets=decor.getRootWindowInsets();
        if(insets!=null)require(visible.top>=insets.getSystemWindowInsetTop(),"Header action overlaps status bar");
        require(visible.width()>=activity.ui().dp(48)&&visible.height()>=activity.ui().dp(48),"Header touch target clipped");
    }
    private void touch(View chart,float fraction){require(chart!=null&&chart.getWidth()>0,"Chart not laid out");RectF plot=(RectF)field(chart,"plot");long t=gestureStart();int tick=0;for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(t,t+16L*tick++,action,plot.left+plot.width()*fraction,plot.centerY(),0);chart.dispatchTouchEvent(event);event.recycle();}lastTouchTime=t+32;}
    private long gestureStart(){return Math.max(SystemClock.uptimeMillis(),lastTouchTime+350);}
    private void pinch(RideChartView chart){
        RectF plot=(RectF)field(chart,"plot");long down=gestureStart();
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[2];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[2];
        for(int i=0;i<2;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].pressure=1;coords[i].size=1;}
        // Android's minimum scaling span can exceed a narrow plot's width. Use its longer
        // dimension so this models a real two-finger pinch in portrait and landscape.
        boolean vertical=plot.height()>plot.width();
        positionPinch(coords,plot,vertical,.08f);
        dispatchPointers(chart,down,down,MotionEvent.ACTION_DOWN,1,properties,coords);
        dispatchPointers(chart,down,down+20,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,coords);
        for(int step=1;step<=12;step++){positionPinch(coords,plot,vertical,.08f+step*.032f);dispatchPointers(chart,down,down+20+step*30,MotionEvent.ACTION_MOVE,2,properties,coords);}
        dispatchPointers(chart,down,down+400,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,coords);
        dispatchPointers(chart,down,down+420,MotionEvent.ACTION_UP,1,properties,coords);lastTouchTime=down+420;
    }
    private void positionPinch(MotionEvent.PointerCoords[] coords,RectF plot,boolean vertical,float spread){for(int i=0;i<2;i++){float direction=i==0?-1:1;coords[i].x=plot.centerX()+(vertical?0:direction*plot.width()*spread);coords[i].y=plot.centerY()+(vertical?direction*plot.height()*spread:0);}}
    private void dispatchPointers(View view,long down,long when,int action,int count,MotionEvent.PointerProperties[] properties,MotionEvent.PointerCoords[] coords){MotionEvent event=MotionEvent.obtain(down,when,action,count,properties,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);view.dispatchTouchEvent(event);event.recycle();}
    private Object viewer(){return field(field(activity,"screen"),"chargeHistoryDialog");}
    private Dialog dialog(){Object viewer=viewer();return viewer==null?null:(Dialog)field(viewer,"dialog");}
    private View dialogRoot(){return dialog().getWindow().getDecorView();}
    private View findDescription(View root,String description){if(description.equals(String.valueOf(root.getContentDescription())))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=findDescription(((ViewGroup)root).getChildAt(i),description);if(found!=null)return found;}return null;}
    private String text(View root){if(root==null)return "";StringBuilder result=new StringBuilder();if(root instanceof TextView)result.append(((TextView)root).getText()).append('\n');if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.append(text(((ViewGroup)root).getChildAt(i)));return result.toString();}
    private void admit(){
        require(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish")||Build.PRODUCT.startsWith("sdk_gphone"),"Refusing physical device");
        for(String name:new String[]{"app_settings","profiles","scooter","pending_documents","onboarding"})require(getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE).getAll().isEmpty(),"Refusing existing preferences");
        require(getTargetContext().getFilesDir().listFiles().length==0&&getTargetContext().databaseList().length==0,"Refusing existing data");
        BluetoothManager manager=getTargetContext().getSystemService(BluetoothManager.class);require(manager==null||manager.getAdapter()==null||!manager.getAdapter().isEnabled(),"Disable emulator Bluetooth");
    }
    private ChargeSessionStore store(){return(ChargeSessionStore)field(service,"chargeStore");}
    private SharedPreferences settings(){return getTargetContext().getSharedPreferences("app_settings",Context.MODE_PRIVATE);}
    private View root(){return(View)field(activity,"root");}
    private void main(Runnable action){AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new IllegalStateException("UI assertion failed: "+errorChain(failure.get()),failure.get());}
    private String errorChain(Throwable error){StringBuilder result=new StringBuilder();Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());while(error!=null&&seen.add(error)){if(result.length()>0)result.append(" → ");result.append(error.toString());error=error.getCause();}return result.toString();}
    private void settle(){waitForIdleSync();SystemClock.sleep(350);waitForIdleSync();}
    private void await(BooleanSupplier test,String message){long until=SystemClock.uptimeMillis()+10000;while(!test.getAsBoolean()&&SystemClock.uptimeMillis()<until)SystemClock.sleep(40);require(test.getAsBoolean(),message);}
    private void require(boolean condition,String message){checks++;if(!condition)throw new IllegalStateException(message);}
    private Object field(Object target,String name){try{Class<?> type=target.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException missing){type=type.getSuperclass();}}throw new NoSuchFieldException(name);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private void setField(Object target,String name,Object value){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private Object invoke(Object target,String name,Class<?>[] types,Object... values){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,values);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private void screenshot(String label)throws Exception{File directory=new File(getTargetContext().getExternalFilesDir(null),"charge-estimate-validation");require(directory.isDirectory()||directory.mkdirs(),"Screenshot folder unavailable");android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(directory,label+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}}
}
