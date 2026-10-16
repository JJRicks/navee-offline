package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.*;

/** Disposable empty emulator only. No real scooter, owner credentials, routes, or recordings. */
public final class LearnedRangeValidation extends Instrumentation {
    private MainActivity activity;
    private ScooterService service;
    private Bundle arguments;
    private String profile;
    private int checks;
    @Override public void onCreate(Bundle args) { arguments=args==null?new Bundle():args;start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            admit();
            require(settings().edit().putBoolean("auto_connect",false).putBoolean("background_connection",false)
                    .putBoolean("developer_options",true).putString("theme",arguments.getString("theme","dark")).commit(),"Fixture preferences failed");
            seedProfileAndCache();
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->activity.service()!=null,"Service absent"); service=activity.service();
            await(()->service.displayReadings().get("battery_percent").number!=null,"Cache absent");
            main(()->require(!service.usesLearnedRange(),"New profiles must default to scooter"));
            String a=ride(8,2), b=ride(8,1.2), c=ride(4,1.2);
            main(()->service.invalidateCapacityEstimate());
            LearnedRangeEstimate.Result estimate=estimate();
            StringBuilder audit=new StringBuilder();
            for(RideStore.Summary summary:service.rides.list())audit.append(" [").append(summary.batteryDropPercent).append(" pts, ").append(summary.distanceKm).append(" km: ").append(LearnedRangeEstimate.inspect(summary,service.rides.fullTrend(summary.id)).rejection).append("]");
            require(estimate.rides==3 && estimate.fullRangeKm!=null && Math.abs(estimate.fullRangeKm-22)<.001,"Worker did not learn full synthetic journals: rides="+estimate.rides+", rejected="+estimate.rejectedRides+", points="+estimate.batteryPoints+", range="+estimate.fullRangeKm+", reason="+estimate.reason+audit);
            main(()->{
                require(service.dashboardReadings().get("remaining_range_km").number==30,"Opt-out replaced reported range");
                service.useLearnedRange(true);
                TelemetryHistory.Reading learned=service.dashboardReadings().get("remaining_range_km");
                require(Math.abs(learned.number-11)<.001&&!learned.live,"Learned reading or stale state wrong");
                require(service.displayReadings().get("remaining_range_km").number==30,"Learning overwrote raw telemetry");
                require(settings().getString("range_source_"+profile,"").equals("learned"),"Range source not per-profile persisted");
                ScooterProfiles.Profile other=service.profiles.create("Second synthetic","111111111111",0);
                require(!service.usesLearnedRange(),"Second scooter inherited source selection");
                service.profiles.select(profile);
                require(service.usesLearnedRange(),"Original source choice lost");
                require(service.setSimulation(true),"Cannot enable sample mode");
            });
            require(estimate().reason.equals("Sample data"),"Simulation read real ride evidence");
            main(()->{
                require(service.dashboardReadings().get("remaining_range_km").number.equals(service.displayReadings().get("remaining_range_km").number),"Simulation applied real learned model");
                service.setSimulation(false);
            });
            require(estimate().rides==3,"Simulation exit lost genuine rides");
            service.rides.delete(c);main(()->service.invalidateCapacityEstimate());
            require(estimate().fullRangeKm==null,"Deleting a ride left learned evidence active");
            main(()->require(service.dashboardReadings().get("remaining_range_km").number==30,"Insufficient history did not fall back"));
            ride(4,1.2);main(()->service.invalidateCapacityEstimate());require(estimate().fullRangeKm!=null,"Completed/restored ride did not relearn");
            main(()->{
                invoke(activity,"select",new Class<?>[]{String.class},"more");
                invoke(field(activity,"screen"),"selectSection",new Class<?>[]{String.class},"App");
                View row=((View)field(activity,"root")).findViewWithTag("preference-range-estimate");require(row!=null,"Range preference missing");row.performClick();
            });waitForIdleSync();SystemClock.sleep(400);
            main(()->{
                @SuppressWarnings("unchecked") List<AlertDialog> dialogs=(List<AlertDialog>)field(field(activity,"screen"),"dialogs");
                AlertDialog dialog=dialogs.get(dialogs.size()-1);View root=dialog.getWindow().getDecorView();
                TextView evidence=root.findViewWithTag("range-learning-evidence");require(evidence!=null&&evidence.getText().toString().contains("3 usable rides"),"Preference evidence missing");
                View selector=root.findViewWithTag("range-source-selector");require(selector!=null,"Source selector missing");
                screenshot(root);
                @SuppressWarnings("unchecked") List<TextView> choices=(List<TextView>)field(field(selector,"track"),"choices");
                choices.get(0).performClick();require(!service.usesLearnedRange(),"Preference did not switch back to scooter");dialog.dismiss();
                require(field(service.ble,"gatt")==null&&field(service.ble,"scanner")==null,"Learning touched Bluetooth");
            });
            result.putString("result","PASS");
        } catch(Throwable error) { result.putString("result","FAIL");result.putString("error",error.toString());error.printStackTrace(); }
        finally {result.putInt("assertions",checks);if(activity!=null)main(()->{activity.finish();getTargetContext().stopService(new Intent(getTargetContext(),ScooterService.class));});finish("PASS".equals(result.getString("result"))?0:1,result);}
    }
    private LearnedRangeEstimate.Result estimate() { AtomicReference<LearnedRangeEstimate.Result> out=new AtomicReference<>();main(()->service.requestLearnedRangeEstimate(out::set));await(()->out.get()!=null,"Range worker timeout");return out.get(); }
    private String ride(double drop,double km) throws Exception {
        RideStore fixture=new RideStore(new File(getTargetContext().getCacheDir(),"range-"+UUID.randomUUID()));
        long start=System.currentTimeMillis()-1200000, mono=1000;String id=fixture.start(profile,"Synthetic range",start,mono);
        for(int sec=0;sec<=600;sec++) {long t=mono+sec*1000;Map<String,RideStore.Sample> values=new LinkedHashMap<>();
            values.put("battery_percent",new RideStore.Sample(80-drop*sec/600,t));values.put("odometer_km",new RideStore.Sample(100+km*sec/600,t));
            values.put("speed_kmh",new RideStore.Sample(km*6,t));values.put("charging_state",new RideStore.Sample(0,t));
            fixture.sample(start+sec*1000,t,values);
        }fixture.stop(start+600000,mono+600000);fixture.exportCsv(id,new File(new File(getTargetContext().getFilesDir(),"rides"),id+".csv"));return id;
    }
    private void seedProfileAndCache() {
        AtomicReference<TelemetryHistory> store=new AtomicReference<>();AtomicReference<TelemetryHistory.Snapshot> done=new AtomicReference<>();
        main(()->{ScooterProfiles profiles=new ScooterProfiles(getTargetContext());profile=profiles.create("Synthetic range fixture","000000000000",0).id;
            long now=System.currentTimeMillis()-60000;Map<String,TelemetryHistory.Reading> values=new LinkedHashMap<>();
            values.put("battery_percent",new TelemetryHistory.Reading("battery_percent","Battery","Battery","50 %","%",50d,now,true));
            values.put("remaining_range_km",new TelemetryHistory.Reading("remaining_range_km","Scooter range","Ride","30 km","km",30d,now,true));
            store.set(new TelemetryHistory(getTargetContext()));store.get().record(profile,new TelemetryHistory.Snapshot(values,Collections.emptyMap(),now),1,true);store.get().load(profile,done::set);
        });await(()->done.get()!=null,"History seed timeout");main(()->store.get().close());
    }
    private void admit() {
        require(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish")||Build.PRODUCT.startsWith("sdk_gphone"),"Refusing physical device");
        for(String p:new String[]{"app_settings","profiles","scooter","pending_documents","control_evidence"})require(getTargetContext().getSharedPreferences(p,Context.MODE_PRIVATE).getAll().isEmpty(),"Refusing existing preferences");
        require(getTargetContext().getFilesDir().list().length==0&&getTargetContext().databaseList().length==0,"Refusing existing data");
        for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})require(getTargetContext().checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED,"Nearby permission missing");
        BluetoothManager manager=getTargetContext().getSystemService(BluetoothManager.class);require(manager==null||manager.getAdapter()==null||!manager.getAdapter().isEnabled(),"Disable emulator Bluetooth");
    }
    private void screenshot(View view) {
        try {File dir=new File(getTargetContext().getExternalFilesDir(null),"learned-range-validation");require(dir.isDirectory()||dir.mkdirs(),"Screenshot directory");
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
            try(FileOutputStream out=new FileOutputStream(new File(dir,"range-preference.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
        }catch(IOException error){throw new IllegalStateException(error);}
    }
    private SharedPreferences settings(){return getTargetContext().getSharedPreferences("app_settings",Context.MODE_PRIVATE);}
    private void main(Runnable r){runOnMainSync(r);}
    private void require(boolean condition,String message){checks++;if(!condition)throw new IllegalStateException(message);}
    private void await(BooleanSupplier ready,String message){long end=SystemClock.uptimeMillis()+15000;while(!ready.getAsBoolean()&&SystemClock.uptimeMillis()<end)SystemClock.sleep(30);require(ready.getAsBoolean(),message);}
    private Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new IllegalStateException(e);}}
    private Object invoke(Object target,String name,Class<?>[] types,Object... args){try{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}catch(Exception e){throw new IllegalStateException(e);}}
}
