package local.navee.offlinelock.tests;

import android.app.Dialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
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
    @Override public void onCreate(Bundle args){this.args=args==null?new Bundle():args;start();}
    @Override public void onStart(){Bundle result=new Bundle();
        try{
            admit();
            require(settings().edit().putBoolean("auto_connect",false).putBoolean("background_connection",false)
                    .putBoolean("developer_options",true).putString("theme",args.getString("theme","dark"))
                    .putString("time_format","24h").commit(),"Settings fixture failed");
            main(()->{ScooterProfiles profiles=new ScooterProfiles(getTargetContext());ScooterProfiles.Profile p=profiles.create("Synthetic estimate fixture","000000000000",0);p.serial="X2538-SYNTHETIC";profiles.update(p);profile=p.id;});
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->activity.service()!=null,"Service missing");service=activity.service();
            await(()->!Boolean.TRUE.equals(field(service,"chargeModelLoading")),"Charge model load did not finish");
            verifyServiceSnapshot();seedRecordings();verifyAsyncHistory();
            main(()->{invoke(activity,"select",new Class<?>[]{String.class},"more");invoke(field(activity,"screen"),"selectSection",new Class<?>[]{String.class},"Developer");});settle();
            main(()->{View row=root().findViewWithTag("charge-history-action");require(row!=null&&row.isEnabled(),"Charge history unavailable offline");row.performClick();});
            await(()->dialog()!=null&&dialog().getWindow().getDecorView().findViewWithTag("charge-history-session:"+completeId)!=null,"History did not load");
            main(()->require(dialogRoot().findViewWithTag("charge-history-session:"+legacyId)!=null,"Legacy recording missing"));
            main(()->assertHeaderFits(dialog(),"Close charge recordings"));screenshot("history-list");
            open(completeId);
            main(()->{
                require(text(dialogRoot()).contains("100%"),"Completion note missing");
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains("fixture-v1"),"Saved estimator version missing");
                touch(dialogRoot().findViewWithTag("charge-history-chart"),.1f);
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains("3.00 min"),"Trace did not preserve first estimate");
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains("Error"),"Completed session has no error comparison");
                choice(dialogRoot().findViewWithTag("charge-history-mode"),1);
            });settle();screenshot("predicted-finish");
            main(()->{
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains("Estimated"),"Finish trace missing");
                require(((TextView)findTextPrefix(dialogRoot().findViewWithTag("charge-history-trace"),"Estimated")).getText().toString().contains(":"),"Finish estimate is not a clock");
                dialogRoot().findViewWithTag("charge-history-expand").performClick();
            });settle();
            main(()->{
                Dialog expanded=(Dialog)field(viewer(),"expanded");require(expanded!=null&&expanded.isShowing(),"Fullscreen chart missing");
                assertHeaderFits(expanded,"Close expanded charge graph");
                View decor=expanded.getWindow().getDecorView(), chart=decor.findViewWithTag("charge-history-fullscreen-chart");
                Rect visible=new Rect();require(chart.getGlobalVisibleRect(visible)&&visible.height()>=activity.ui().dp(70),"Fullscreen plot lost its usable height");
                require(visible.width()==chart.getWidth()&&visible.height()==chart.getHeight(),"Fullscreen plot clipped");
                touch(chart,.75f);require(!text(decor.findViewWithTag("charge-history-fullscreen-trace")).isEmpty(),"Fullscreen touch trace missing");
                choice(decor.findViewWithTag("charge-history-fullscreen-mode"),0);
                require(text(decor.findViewWithTag("charge-history-fullscreen-trace")).contains("min"),"Fullscreen remaining mode not applied");
            });settle();screenshot("fullscreen-remaining");
            main(()->((Dialog)field(viewer(),"expanded")).dismiss());settle();
            main(()->require(dialogRoot().findViewWithTag("charge-history-detail")!=null,"Closing fullscreen lost recording"));
            back();open(partialId);
            main(()->{
                String note=text(dialogRoot().findViewWithTag("charge-history-note"));require(note.toLowerCase(Locale.ROOT).contains("not observed"),"Stopped recording pretends completion");
                choice(dialogRoot().findViewWithTag("charge-history-target"),1);
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains("80%"),"Saved cutoff missing from trace");
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains("Observed  —"),"Lower cutoff was compared against full charge");
            });screenshot("unfinished-limit");
            back();open(legacyId);
            main(()->{
                require(text(dialogRoot().findViewWithTag("charge-history-note")).contains("Estimates weren't saved"),"Legacy recording explanation missing");
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains("Estimated  —"),"Legacy estimates were fabricated");
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains("No saved estimate"),"Missing estimate was labeled a connection gap");
            });screenshot("legacy-recording");
            back();open(extremeId);
            main(()->{
                View chart=dialogRoot().findViewWithTag("charge-history-chart");
                double low=(Double)field(chart,"low"),high=(Double)field(chart,"high");
                require(Double.isFinite(low)&&Double.isFinite(high)&&high>low,"Extreme finite ETA overflowed chart bounds");
                touch(chart,.1f);
                require(text(dialogRoot().findViewWithTag("charge-history-trace")).contains(Double.toString(Double.MAX_VALUE)),"Extreme raw ETA was changed");
            });
            back();open(tinyId);
            main(()->{
                View chart=dialogRoot().findViewWithTag("charge-history-chart");
                double low=(Double)field(chart,"low"),high=(Double)field(chart,"high");
                require(Double.isFinite(low)&&Double.isFinite(high)&&high>=1,"Tiny ETA lost the one-minute chart minimum");
                require(Float.isFinite((Float)invoke(chart,"y",new Class<?>[]{double.class},Double.MIN_VALUE)),"Tiny ETA rendered nonfinite geometry");
            });
            main(()->{
                Dialog old=dialog();service.profiles.create("Different synthetic scooter","111111111111",0);
                invoke(viewer(),"refresh",new Class<?>[]{});require(!old.isShowing(),"Profile switch left old charge history visible");
                service.profiles.select(profile);
                require(field(service.ble,"gatt")==null&&field(service.ble,"scanner")==null,"Fixture touched actual Bluetooth");
                require(!service.isChargeRecording()&&!service.isRecording()&&!service.phoneSaving(),"Viewing history started a recording");
            });
            result.putString("result","PASS");
        }catch(Throwable error){result.putString("result","FAIL");result.putString("error",error.toString());error.printStackTrace();try{if(activity!=null)screenshot("failure");}catch(Throwable ignored){}}
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
    private String recordingAt(long startedMono)throws IOException{for(ChargeSessionStore.HistorySession session:store().recordings(profile))if(session.startedMono==startedMono)return session.id;throw new IllegalStateException("Fixture recording missing");}
    private ChargeSessionStore.Frame frame(long wall,long mono,double soc){return new ChargeSessionStore.Frame(wall,mono,new ChargeSessionStore.Value(1d,mono,0x72),new ChargeSessionStore.Value(soc,mono,0x72),new ChargeSessionStore.Value(52d,mono,0x72),new ChargeSessionStore.Value(-2d,mono,0x72),null);}
    private void verifyAsyncHistory(){
        AtomicReference<List<ChargeSessionStore.HistorySession>> found=new AtomicReference<>();
        main(()->require(service.loadChargeRecordings(found::set),"Async history refused"));await(()->found.get()!=null,"Async history callback missing");
        require(found.get().size()==5,"Wrong profile history count");
        for(ChargeSessionStore.HistorySession session:found.get())require(profile.equals(session.profile),"History leaked profile");
    }
    private void open(String id){main(()->dialogRoot().findViewWithTag("charge-history-session:"+id).performClick());settle();}
    private void back(){main(()->findDescription(dialogRoot(),"Back to charge recordings").performClick());settle();}
    private void choice(View selector,int index){require(selector!=null,"Selector missing");@SuppressWarnings("unchecked") List<TextView> choices=(List<TextView>)field(field(selector,"track"),"choices");choices.get(index).performClick();}
    private void assertHeaderFits(Dialog opened,String description){
        View decor=opened.getWindow().getDecorView(),button=findDescription(decor,description);Rect visible=new Rect();
        require(button!=null&&button.getGlobalVisibleRect(visible),"Header action is not visible");
        android.view.WindowInsets insets=decor.getRootWindowInsets();
        if(insets!=null)require(visible.top>=insets.getSystemWindowInsetTop(),"Header action overlaps status bar");
        require(visible.width()>=activity.ui().dp(48)&&visible.height()>=activity.ui().dp(48),"Header touch target clipped");
    }
    private void touch(View chart,float fraction){require(chart!=null&&chart.getWidth()>0,"Chart not laid out");long t=SystemClock.uptimeMillis();for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(t,t+action,action,chart.getWidth()*fraction,chart.getHeight()/2f,0);chart.dispatchTouchEvent(event);event.recycle();}}
    private Object viewer(){return field(field(activity,"screen"),"chargeHistoryDialog");}
    private Dialog dialog(){Object viewer=viewer();return viewer==null?null:(Dialog)field(viewer,"dialog");}
    private View dialogRoot(){return dialog().getWindow().getDecorView();}
    private View findDescription(View root,String description){if(description.equals(String.valueOf(root.getContentDescription())))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=findDescription(((ViewGroup)root).getChildAt(i),description);if(found!=null)return found;}return null;}
    private View findTextPrefix(View root,String prefix){if(root instanceof TextView&&((TextView)root).getText().toString().startsWith(prefix))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=findTextPrefix(((ViewGroup)root).getChildAt(i),prefix);if(found!=null)return found;}return null;}
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
    private void main(Runnable action){AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new IllegalStateException("UI assertion failed",failure.get());}
    private void settle(){waitForIdleSync();SystemClock.sleep(350);waitForIdleSync();}
    private void await(BooleanSupplier test,String message){long until=SystemClock.uptimeMillis()+10000;while(!test.getAsBoolean()&&SystemClock.uptimeMillis()<until)SystemClock.sleep(40);require(test.getAsBoolean(),message);}
    private void require(boolean condition,String message){checks++;if(!condition)throw new IllegalStateException(message);}
    private Object field(Object target,String name){try{Class<?> type=target.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException missing){type=type.getSuperclass();}}throw new NoSuchFieldException(name);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private void setField(Object target,String name,Object value){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private Object invoke(Object target,String name,Class<?>[] types,Object... values){try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,values);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private void screenshot(String label)throws Exception{File directory=new File(getTargetContext().getExternalFilesDir(null),"charge-estimate-validation");require(directory.isDirectory()||directory.mkdirs(),"Screenshot folder unavailable");android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(directory,label+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}}
}
