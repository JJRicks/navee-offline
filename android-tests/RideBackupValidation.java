package com.jjricks.volt.tests;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.*;

/** Disposable empty emulator only: actual private-storage merge, reopen, and the unified restore flow for a .voltrides file. */
public final class RideBackupValidation extends Instrumentation {
    private String theme="dark";private boolean checkReopened;private int assertions;private Activity current;private Class<?> archive,storageType,preparedType,planType;
    @Override public void onCreate(Bundle args){if(args!=null){theme=args.getString("theme","dark");checkReopened="check-reopened-range".equals(args.getString("mode"));}start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();String emulator=Build.FINGERPRINT+" "+Build.MODEL+" "+Build.HARDWARE;
        require(emulator.toLowerCase(Locale.ROOT).matches(".*(generic|emulator|sdk_gphone|ranchu|goldfish).*"),"Refusing physical phone");
        BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        if(Build.VERSION.SDK_INT>=31){getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.BLUETOOTH_CONNECT);getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.BLUETOOTH_SCAN);}
        require(manager==null||manager.getAdapter()==null||!manager.getAdapter().isEnabled(),"Bluetooth must be off");
        if(checkReopened){reopenedRange(context);result.putString("status","PASS");result.putInt("assertions",assertions);finish(Activity.RESULT_OK,result);return;}
        File ridesFolder=new File(context.getFilesDir(),"rides");require(!ridesFolder.exists()||Objects.requireNonNull(ridesFolder.list()).length==0,"Requires empty app data");
        require(context.getSharedPreferences("profiles",0).getAll().isEmpty(),"Requires fresh profiles");
        archive=Class.forName("com.jjricks.volt.RideArchive");storageType=Class.forName("com.jjricks.volt.RideArchive$Storage");preparedType=Class.forName("com.jjricks.volt.RideArchive$Prepared");planType=Class.forName("com.jjricks.volt.RideArchive$Plan");
        Object storage=call(Class.forName("com.jjricks.volt.RideBackup"),null,"storage",new Class<?>[]{Context.class},context);
        RideStore store=new RideStore(ridesFolder);long rideWall=System.currentTimeMillis()-3600000;String id=store.start("old-profile","Synthetic ride",rideWall,0);store.setNotes("Synthetic backup note");
        rangeSamples(store,rideWall);
        store.stop(rideWall+700000,700000);store.rename(id,"Restored café ride");
        for(int n=1;n<=2;n++){long wall=rideWall+n*800000;store.start("old-profile","Synthetic range "+n,wall,0);rangeSamples(store,wall);store.stop(wall+700000,700000);}
        PhoneRideStore phone=new PhoneRideStore(new File(context.getFilesDir(),"phone-rides"));phone.start(id,rideWall,0,"pocket");phone.appendBatch(id,PhoneRideStore.row("GPS",rideWall+1000,1000,1,2,3,4,5,6,7),"1000,1,2,3,4,5,6,7,8,9,0,0,0,1,3\n");phone.stop(id,rideWall+700000,700000);
        byte[] originalPhone=Files.readAllBytes(phone.rawCsv(id).toPath()),originalMotion=Files.readAllBytes(phone.motionCsv(id).toPath());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();Object count=call(archive,null,"write",new Class<?>[]{storageType,OutputStream.class,Map.class},storage,bytes,Collections.singletonMap("old-profile","Original scooter"));require(count.equals(3),"Exports three complete rides");
        phone.delete(id);for(RideStore.Summary saved:store.list())store.delete(saved.id);
        Object prepared=call(archive,null,"stage",new Class<?>[]{storageType,InputStream.class},storage,new ByteArrayInputStream(bytes.toByteArray()));require(store.list().isEmpty(),"Stage leaves live rides empty");
        ScooterProfiles profiles=new ScooterProfiles(context);ScooterProfiles.Profile profile=profiles.create("Restored scooter","A1B2C3D4E5F6",0);Map<String,String> mapping=Collections.singletonMap("old-profile",profile.id);
        Object plan=call(archive,null,"plan",new Class<?>[]{storageType,preparedType,Map.class},storage,prepared,mapping);require((int)field(plan,"added")==3,"Preview reports three new rides");
        Object crashing=java.lang.reflect.Proxy.newProxyInstance(storageType.getClassLoader(),new Class<?>[]{storageType},(proxy,method,args)->{
            if(method.getName().equals("copied"))throw new AssertionError("Injected native interruption");
            method.setAccessible(true);try{return method.invoke(storage,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
        try{call(archive,null,"apply",new Class<?>[]{storageType,planType},crashing,plan);throw new AssertionError("Missing native interruption");}
        catch(AssertionError interruption){require("Injected native interruption".equals(interruption.getMessage()),"Simulated process death reached first native move");}
        require(new File(context.getNoBackupFilesDir(),"ride-transfer/transaction/state").isFile(),"Interrupted native merge leaves durable recovery");
        call(archive,null,"recover",new Class<?>[]{storageType},storage);
        require(new RideStore(ridesFolder).list().isEmpty()&&!phone.rawCsv(id).exists()&&!phone.motionCsv(id).exists(),"Native startup rollback removes only transaction-owned files");
        prepared=call(archive,null,"stage",new Class<?>[]{storageType,InputStream.class},storage,new ByteArrayInputStream(bytes.toByteArray()));
        plan=call(archive,null,"plan",new Class<?>[]{storageType,preparedType,Map.class},storage,prepared,mapping);
        call(archive,null,"apply",new Class<?>[]{storageType,planType},storage,plan);
        RideStore reopened=new RideStore(ridesFolder);RideStore.Summary restored=reopened.get(id);require(restored.sampleCount==700&&restored.scooterId.equals(profile.id)&&restored.label.equals("Restored café ride")&&restored.notes.equals("Synthetic backup note"),"Real app store reopens all samples and metadata");
        PhoneRideStore reopenedPhone=new PhoneRideStore(new File(context.getFilesDir(),"phone-rides"));require(Arrays.equals(originalPhone,Files.readAllBytes(reopenedPhone.rawCsv(id).toPath()))&&Arrays.equals(originalMotion,Files.readAllBytes(reopenedPhone.motionCsv(id).toPath())),"Phone and motion bytes preserved");require(reopenedPhone.load(id).locationCount==1,"Restored phone journal parses");
        Object duplicate=call(archive,null,"stage",new Class<?>[]{storageType,InputStream.class},storage,new ByteArrayInputStream(bytes.toByteArray()));Object second=call(archive,null,"plan",new Class<?>[]{storageType,preparedType,Map.class},storage,duplicate,mapping);require((int)field(second,"identical")==3&&(int)field(second,"added")==0,"Reimport after mapping is idempotent");call(archive,null,"apply",new Class<?>[]{storageType,planType},storage,second);
        context.getSharedPreferences("app_settings",0).edit().putString("theme",theme).putBoolean("connect_on_open",false).putBoolean("developer_simulate_scooter",false).commit();OnboardingScreen.markDone(context);
        ActivityMonitor mainMonitor=addMonitor(MainActivity.class.getName(),null,false);
        MainActivity main=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));current=main;
        long limit=SystemClock.elapsedRealtime()+10000;while(Boolean.TRUE.equals(field(main,"startupRedirect"))&&SystemClock.elapsedRealtime()<limit){Activity replacement=waitForMonitorWithTimeout(mainMonitor,500);if(replacement instanceof MainActivity){main=(MainActivity)replacement;current=main;}}
        removeMonitor(mainMonitor);final MainActivity activity=main;await(()->activity.service()!=null,"Service did not open");
        onMain(()->callUnchecked(activity,"select",new Class<?>[]{String.class},"rides"));require(root(activity).findViewWithTag("ride-backups-menu")!=null,"Rides exposes backup menu");
        onMain(()->root(activity).findViewWithTag("ride-backups-menu").performClick());AlertDialog menu=(AlertDialog)field(activity,"controlDialog");
        require(menu.isShowing()&&menu.getWindow().getDecorView().findViewWithTag("data-backup-create")!=null&&menu.getWindow().getDecorView().findViewWithTag("data-backup-restore")!=null,"Ride backups opens Back up and Restore");
        onMain(()->menu.getWindow().getDecorView().findViewWithTag("data-backup-restore").performClick());AlertDialog consent=(AlertDialog)field(activity,"controlDialog");require(consent.isShowing()&&!DataMaintenance.active(),"Consent leaves models active");onMain(()->consent.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());require(!DataMaintenance.active(),"Cancel does not pause data");
        onMain(()->{try{activity.service().rides.start(profile.id,"Busy test",2000000,100);}catch(IOException e){throw new RuntimeException(e);}activity.dataBackup(false,"rides");});AlertDialog busyConsent=(AlertDialog)field(activity,"controlDialog");onMain(()->busyConsent.getButton(AlertDialog.BUTTON_POSITIVE).performClick());require(!DataMaintenance.active(),"Active recording blocks backup maintenance");onMain(()->{try{activity.service().rides.stop(2001000,1100);}catch(IOException e){throw new RuntimeException(e);}});
        ActivityMonitor transferMonitor=addMonitor(BackupActivity.class.getName(),null,false);
        java.util.concurrent.CountDownLatch drainStarted=new java.util.concurrent.CountDownLatch(1),drainRelease=new java.util.concurrent.CountDownLatch(1);
        ((java.util.concurrent.ExecutorService)field(activity.service(),"capacityWorker")).execute(()->{drainStarted.countDown();try{drainRelease.await(30,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        require(drainStarted.await(3,java.util.concurrent.TimeUnit.SECONDS),"Synthetic writer started");
        MainActivity relaunched;
        try {
            onMain(()->activity.dataBackup(true,"rides"));AlertDialog restoreConsent=(AlertDialog)field(activity,"controlDialog");onMain(()->restoreConsent.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            require(DataMaintenance.active(),"Writer drain installs maintenance immediately");
            relaunched=(MainActivity)launch(activity,MainActivity.class);
            SystemClock.sleep(250);waitForIdleSync();
            require(transferMonitor.getHits()==0,"Repeated launcher entry waits for writers instead of exposing the transfer form");
        } finally {drainRelease.countDown();}
        Activity opened=waitForMonitorWithTimeout(transferMonitor,10000);require(opened instanceof BackupActivity,"Restore screen opens after draining writers");BackupActivity transfer=(BackupActivity)opened;current=transfer;
        await(relaunched::isFinishing,"Repeated launch did not return to the prepared transfer");
        require(DataMaintenance.active(),"Restore owns maintenance boundary");await(()->root(transfer).findViewWithTag("restore-choose")!=null,"Restore chooser visible");
        require("rides".equals(field(transfer,"preselect")),"Ride backups preselects Rides");
        repeatedLaunchDuringWork(transfer);
        File picked=new File(context.getCacheDir(),"synthetic-picked.voltrides");Files.write(picked.toPath(),bytes.toByteArray());IntentFilter filter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");ActivityMonitor picker=addMonitor(filter,new ActivityResult(Activity.RESULT_OK,new Intent().setData(Uri.fromFile(picked))),true);
        onMain(()->root(transfer).findViewWithTag("restore-choose").performClick());await(()->root(transfer).findViewWithTag("restore-next")!=null,"Picked ride file did not reach the category list");require(picker.getHits()==1,"Used document picker");removeMonitor(picker);
        require(field(transfer,"password")==null,"An unencrypted ride file asks for no password");
        CheckBox rides=(CheckBox)root(transfer).findViewWithTag("backup-category-rides");require(rides!=null&&rides.isChecked()&&root(transfer).findViewWithTag("restore-replace-all")==null,"A ride file offers Rides only, without Replace everything");
        onMain(()->root(transfer).findViewWithTag("restore-next").performClick());await(()->root(transfer).findViewWithTag("restore-review")!=null,"Unknown scooter did not reach the destination step");
        onMain(()->render(root(transfer),"ride-backup-mapping.png"));
        Spinner spinner=(Spinner)root(transfer).findViewWithTag("restore-destination");require(spinner!=null&&"Don't restore".equals(spinner.getSelectedItem()),"An unknown scooter defaults to Don't restore");
        onMain(()->spinner.setSelection(0));waitForIdleSync();onMain(()->root(transfer).findViewWithTag("restore-review").performClick());
        await(()->root(transfer).findViewWithTag("restore-apply")!=null,"Restore review did not appear");
        require(textContaining(root(transfer),"Rides: 0 new · 3 already here · 0 kept here")!=null,"Review reports the mapped rides as already here");
        onMain(()->render(root(transfer),"ride-backup-preview.png"));
        require(new RideStore(ridesFolder).list().size()==4,"Review changes nothing");
        onMain(()->root(transfer).findViewWithTag("restore-apply").performClick());await(()->Boolean.TRUE.equals(fieldQuiet(transfer,"terminal")),"Restore did not finish");
        require(((TextView)field(transfer,"status")).getText().toString().equals("Restored. Reopen Volt."),"Idempotent restore completes");require(new RideStore(ridesFolder).list().size()==4,"No ride duplicated");picked.delete();
        failedDestinationClose(transfer,bytes.toByteArray());
        result.putString("status","PASS");result.putInt("assertions",assertions);result.putString("summary","Ride journal merge/reopen, native file moves and identities, .voltrides import through Back up & restore with Rides preselected, no duplicate mapped rides, consent, busy refusal, document picker, destination choice, review/apply and save retry");finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("status","FAIL");result.putString("error",android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,result);}}
    private Activity launch(Activity from,Class<? extends Activity> type){
        ActivityMonitor monitor=addMonitor(type.getName(),null,false);
        try{onMain(()->from.startActivity(new Intent(getTargetContext(),type)));Activity opened=waitForMonitorWithTimeout(monitor,10000);require(opened!=null,"Explicit launch did not create "+type.getSimpleName());return opened;}
        finally{removeMonitor(monitor);}
    }
    private static void rangeSamples(RideStore store,long wall)throws IOException {
        for(int i=0;i<700;i++){long mono=i*1000;Map<String,RideStore.Sample> samples=new LinkedHashMap<>();
            samples.put("battery_percent",new RideStore.Sample(80-i/100d,mono));samples.put("odometer_km",new RideStore.Sample(100+i*.004,mono));
            samples.put("speed_kmh",new RideStore.Sample(14.4,mono));samples.put("charging_state",new RideStore.Sample(0,mono));store.sample(wall+mono,mono,samples);}
    }
    private void reopenedRange(Context context)throws Exception {
        require(!DataMaintenance.active(),"Cold process must not retain the previous maintenance session");
        ScooterProfiles profiles=new ScooterProfiles(context);require(profiles.all().size()==1&&"Restored scooter".equals(profiles.active().name),"Requires the prior synthetic restored fixture");
        require(new RideStore(new File(context.getFilesDir(),"rides")).list().size()==4,"Cold reopen retains restored and pre-existing rides");
        MainActivity main=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));current=main;
        await(()->main.service()!=null,"Cold restored service did not open");
        java.util.concurrent.atomic.AtomicReference<LearnedRangeEstimate.Result> estimate=new java.util.concurrent.atomic.AtomicReference<>();
        onMain(()->main.service().requestLearnedRangeEstimate(estimate::set));await(()->estimate.get()!=null,"Cold range analysis did not finish");
        require(estimate.get().rides==3&&estimate.get().fullRangeKm!=null&&Math.abs(estimate.get().fullRangeKm-40)<.001,"Cold process reanalyses the three full imported journals for the mapped scooter");
        onMain(()->{main.finish();context.stopService(new Intent(context,ScooterService.class));});
    }
    private void repeatedLaunchDuringWork(BackupActivity transfer)throws Exception {
        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        try {
            onMain(()->{callUnchecked(transfer,"setBusy",new Class<?>[]{String.class},"Synthetic in-flight save");callUnchecked(transfer,"submit",new Class<?>[]{Runnable.class},(Runnable)()->{started.countDown();try{release.await(30,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});});
            require(started.await(3,java.util.concurrent.TimeUnit.SECONDS),"Synthetic transfer worker started");
            MainActivity repeated=(MainActivity)launch(transfer,MainActivity.class);
            await(()->repeated.isFinishing()&&transfer.hasWindowFocus(),"Repeated launcher did not return to the active transfer");
            require(!((View)field(transfer,"closeAction")).isEnabled()&&!((View)field(transfer,"action")).isEnabled(),"Existing transfer remains busy after relaunch");
            onMain(()->callUnchecked(transfer,"requestClose",new Class<?>[]{}));require(!transfer.isFinishing(),"Relaunch cannot close the process during work");
        } finally {release.countDown();}
        await(()->{try{return ((java.util.concurrent.atomic.AtomicInteger)field(transfer,"OPERATIONS")).get()==0;}catch(Exception e){throw new RuntimeException(e);}},"Synthetic worker did not finish");
        onMain(()->callUnchecked(transfer,"retry",new Class<?>[]{String.class},""));
    }
    private void failedDestinationClose(BackupActivity transfer,byte[] archiveBytes)throws Exception {
        File ready=new File(getTargetContext().getCacheDir(),"synthetic-export-retry.voltbackup");Files.write(ready.toPath(),archiveBytes);
        onMain(()->{setField(transfer,"terminal",false);setField(transfer,"exported",ready);((View)fieldQuiet(transfer,"action")).setVisibility(View.VISIBLE);callUnchecked(transfer,"setBusy",new Class<?>[]{String.class},"Saving synthetic backup");});
        ByteArrayOutputStream broken=new ByteArrayOutputStream(){@Override public void close()throws IOException{throw new IOException("Injected document close failure");}};
        call(transfer.getClass(),transfer,"saveExport",new Class<?>[]{File.class,OutputStream.class},ready,broken);
        await(()->!Boolean.TRUE.equals(fieldQuiet(transfer,"busy")),"Save failure did not become retryable");
        require(Arrays.equals(archiveBytes,broken.toByteArray()),"Destination writes succeeded before injected close failure");
        require(ready.isFile()&&field(transfer,"exported")==ready,"Failed destination close retains the only prepared archive");
        // retry() runs on the main thread after busy clears; wait for it rather than racing it.
        await(()->!Boolean.TRUE.equals(fieldQuiet(transfer,"terminal"))&&((View)fieldQuiet(transfer,"action")).isEnabled()&&((View)fieldQuiet(transfer,"action")).getVisibility()==View.VISIBLE,"Close failure leaves retry visible and enabled");
        require(!((TextView)field(transfer,"status")).getText().toString().equals("Backup saved."),"Close failure is not reported as saved");
        ByteArrayOutputStream good=new ByteArrayOutputStream();onMain(()->callUnchecked(transfer,"setBusy",new Class<?>[]{String.class},"Retrying synthetic backup"));
        call(transfer.getClass(),transfer,"saveExport",new Class<?>[]{File.class,OutputStream.class},ready,good);
        await(()->Boolean.TRUE.equals(fieldQuiet(transfer,"terminal")),"Successful retry did not complete");
        require(!ready.exists()&&field(transfer,"exported")==null&&Arrays.equals(archiveBytes,good.toByteArray()),"Successful retry writes exact archive then removes staging");
    }
    private static void setField(Object target,String name,Object value){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new RuntimeException(e);}}
    private void render(View view,String name){
        try {android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(bitmap));File output=new File(getTargetContext().getExternalFilesDir(null),name);
            try(OutputStream out=new FileOutputStream(output)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        } catch(IOException failure){throw new RuntimeException(failure);}
    }
    private void require(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private void onMain(Runnable work){runOnMainSync(work);waitForIdleSync();}
    private void await(BooleanSupplier test,String why){long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){if(test.getAsBoolean())return;SystemClock.sleep(50);}throw new AssertionError(why);}
    private static View root(Activity activity){return activity.getWindow().getDecorView();}
    private static TextView text(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return(TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView result=text(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}return null;}
    private static TextView textContaining(View view,String label){if(view instanceof TextView&&((TextView)view).getText().toString().contains(label))return(TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView result=textContaining(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}return null;}
    private static <T> T find(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T result=find(((ViewGroup)view).getChildAt(i),type);if(result!=null)return result;}return null;}
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object fieldQuiet(Object object,String name){try{return field(object,name);}catch(Exception e){throw new RuntimeException(e);}}
    private static Object call(Class<?> type,Object object,String name,Class<?>[] types,Object...args)throws Exception{Method method=type.getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(object,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw(Exception)e.getCause();throw(Error)e.getCause();}}
    private static void callUnchecked(Object object,String name,Class<?>[] types,Object...args){try{call(object.getClass(),object,name,types,args);}catch(Exception e){throw new RuntimeException(e);}}
}
