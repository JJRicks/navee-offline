package local.navee.offlinelock.tests;

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
import local.navee.offlinelock.*;

/** Disposable empty emulator only: actual private-storage merge, reopen, consent and picker flow. */
public final class RideBackupValidation extends Instrumentation {
    private String theme="dark";private int assertions;private Activity current;private Class<?> archive,storageType,preparedType,planType;
    @Override public void onCreate(Bundle args){if(args!=null)theme=args.getString("theme","dark");start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();String emulator=Build.FINGERPRINT+" "+Build.MODEL+" "+Build.HARDWARE;
        require(emulator.toLowerCase(Locale.ROOT).matches(".*(generic|emulator|sdk_gphone|ranchu|goldfish).*"),"Refusing physical phone");
        File ridesFolder=new File(context.getFilesDir(),"rides");require(!ridesFolder.exists()||Objects.requireNonNull(ridesFolder.list()).length==0,"Requires empty app data");
        require(context.getSharedPreferences("profiles",0).getAll().isEmpty(),"Requires fresh profiles");
        BluetoothManager manager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        if(Build.VERSION.SDK_INT>=31){getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.BLUETOOTH_CONNECT);getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.BLUETOOTH_SCAN);}
        require(manager==null||manager.getAdapter()==null||!manager.getAdapter().isEnabled(),"Bluetooth must be off");
        archive=Class.forName("local.navee.offlinelock.RideArchive");storageType=Class.forName("local.navee.offlinelock.RideArchive$Storage");preparedType=Class.forName("local.navee.offlinelock.RideArchive$Prepared");planType=Class.forName("local.navee.offlinelock.RideArchive$Plan");
        Object storage=call(Class.forName("local.navee.offlinelock.RideBackup"),null,"storage",new Class<?>[]{Context.class},context);
        RideStore store=new RideStore(ridesFolder);String id=store.start("old-profile","Synthetic ride",1000000,0);store.setNotes("Synthetic backup note");
        for(int i=0;i<700;i++){Map<String,RideStore.Sample> samples=new LinkedHashMap<>();samples.put("battery_percent",new RideStore.Sample(80-i/100d,i*1000));store.sample(1000000+i*1000,i*1000,samples);}
        store.stop(1700000,700000);store.rename(id,"Restored café ride");
        PhoneRideStore phone=new PhoneRideStore(new File(context.getFilesDir(),"phone-rides"));phone.start(id,1000000,0,"pocket");phone.appendBatch(id,PhoneRideStore.row("GPS",1001000,1000,1,2,3,4,5,6,7),"1000,1,2,3,4,5,6,7,8,9,0,0,0,1,3\n");phone.stop(id,1700000,700000);
        byte[] originalPhone=Files.readAllBytes(phone.rawCsv(id).toPath()),originalMotion=Files.readAllBytes(phone.motionCsv(id).toPath());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();Object count=call(archive,null,"write",new Class<?>[]{storageType,OutputStream.class,Map.class},storage,bytes,Collections.singletonMap("old-profile","Original scooter"));require(count.equals(1),"Exports one complete ride");
        phone.delete(id);store.delete(id);
        Object prepared=call(archive,null,"stage",new Class<?>[]{storageType,InputStream.class},storage,new ByteArrayInputStream(bytes.toByteArray()));require(store.list().isEmpty(),"Stage leaves live rides empty");
        ScooterProfiles profiles=new ScooterProfiles(context);ScooterProfiles.Profile profile=profiles.create("Restored scooter","A1B2C3D4E5F6",0);Map<String,String> mapping=Collections.singletonMap("old-profile",profile.id);
        Object plan=call(archive,null,"plan",new Class<?>[]{storageType,preparedType,Map.class},storage,prepared,mapping);require((int)field(plan,"added")==1,"Preview reports one new ride");
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
        Object duplicate=call(archive,null,"stage",new Class<?>[]{storageType,InputStream.class},storage,new ByteArrayInputStream(bytes.toByteArray()));Object second=call(archive,null,"plan",new Class<?>[]{storageType,preparedType,Map.class},storage,duplicate,mapping);require((int)field(second,"identical")==1&&(int)field(second,"added")==0,"Reimport after mapping is idempotent");call(archive,null,"apply",new Class<?>[]{storageType,planType},storage,second);
        context.getSharedPreferences("app_settings",0).edit().putString("theme",theme).putBoolean("connect_on_open",false).putBoolean("developer_simulate_scooter",false).commit();OnboardingScreen.markDone(context);
        ActivityMonitor mainMonitor=addMonitor(MainActivity.class.getName(),null,false);
        MainActivity main=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));current=main;
        long limit=SystemClock.elapsedRealtime()+10000;while(Boolean.TRUE.equals(field(main,"startupRedirect"))&&SystemClock.elapsedRealtime()<limit){Activity replacement=waitForMonitorWithTimeout(mainMonitor,500);if(replacement instanceof MainActivity){main=(MainActivity)replacement;current=main;}}
        removeMonitor(mainMonitor);final MainActivity activity=main;await(()->activity.service()!=null,"Service did not open");
        onMain(()->callUnchecked(activity,"select",new Class<?>[]{String.class},"rides"));require(root(activity).findViewWithTag("ride-backups-menu")!=null,"Rides exposes backup menu");
        onMain(()->root(activity).findViewWithTag("ride-backups-menu").performClick());Object screen=field(activity,"screen");@SuppressWarnings("unchecked") List<AlertDialog> dialogs=(List<AlertDialog>)field(screen,"dialogs");AlertDialog menu=dialogs.get(dialogs.size()-1);require(text(menu.getWindow().getDecorView(),"Back up saved rides")!=null&&text(menu.getWindow().getDecorView(),"Restore rides")!=null,"Backup and restore both discoverable");
        onMain(()->text(menu.getWindow().getDecorView(),"Restore rides").performClick());AlertDialog consent=(AlertDialog)field(activity,"controlDialog");require(consent.isShowing()&&!DataMaintenance.active(),"Consent leaves models active");onMain(()->consent.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());require(!DataMaintenance.active(),"Cancel does not pause data");
        onMain(()->{try{activity.service().rides.start(profile.id,"Busy test",2000000,100);}catch(IOException e){throw new RuntimeException(e);}activity.rideBackup(false);});AlertDialog busyConsent=(AlertDialog)field(activity,"controlDialog");onMain(()->busyConsent.getButton(AlertDialog.BUTTON_POSITIVE).performClick());require(!DataMaintenance.active(),"Active recording blocks backup maintenance");onMain(()->{try{activity.service().rides.stop(2001000,1100);}catch(IOException e){throw new RuntimeException(e);}});
        ActivityMonitor transferMonitor=addMonitor(RideBackupActivity.class.getName(),null,false);onMain(()->activity.rideBackup(true));AlertDialog restoreConsent=(AlertDialog)field(activity,"controlDialog");onMain(()->restoreConsent.getButton(AlertDialog.BUTTON_POSITIVE).performClick());Activity opened=waitForMonitorWithTimeout(transferMonitor,10000);require(opened instanceof RideBackupActivity,"Ride restore screen opens after draining writers");RideBackupActivity transfer=(RideBackupActivity)opened;current=transfer;
        require(DataMaintenance.active(),"Restore owns maintenance boundary");require(root(transfer).findViewWithTag("ride-restore-choose")!=null,"Restore chooser visible");
        File picked=new File(context.getCacheDir(),"synthetic-picked.voltrides");Files.write(picked.toPath(),bytes.toByteArray());IntentFilter filter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");ActivityMonitor picker=addMonitor(filter,new ActivityResult(Activity.RESULT_OK,new Intent().setData(Uri.fromFile(picked))),true);
        onMain(()->root(transfer).findViewWithTag("ride-restore-choose").performClick());await(()->root(transfer).findViewWithTag("ride-restore-review")!=null,"Picked archive did not reach mapping UI");require(picker.getHits()==1,"Used document picker");removeMonitor(picker);
        onMain(()->render(root(transfer),"ride-backup-mapping.png"));
        onMain(()->root(transfer).findViewWithTag("ride-restore-review").performClick());require(((TextView)field(transfer,"status")).getText().toString().contains("Choose a destination"),"Unknown profile requires explicit selection");Spinner spinner=find(root(transfer),Spinner.class);require(spinner!=null,"Unknown profile offers scooter selection");onMain(()->spinner.setSelection(1));waitForIdleSync();onMain(()->root(transfer).findViewWithTag("ride-restore-review").performClick());await(()->fieldQuiet(transfer,"confirmation") instanceof AlertDialog&&((AlertDialog)fieldQuiet(transfer,"confirmation")).isShowing(),"Restore review did not appear");AlertDialog review=(AlertDialog)field(transfer,"confirmation");require(textContaining(review.getWindow().getDecorView(),"Nothing here is overwritten.")!=null,"Review explains merge semantics");onMain(()->render(review.getWindow().getDecorView(),"ride-backup-preview.png"));
        onMain(()->review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());require(new RideStore(ridesFolder).list().size()==2,"Cancel preserves existing recordings");
        onMain(()->root(transfer).findViewWithTag("ride-restore-review").performClick());await(()->((AlertDialog)fieldQuiet(transfer,"confirmation")).isShowing(),"Second review did not appear");AlertDialog finish=(AlertDialog)field(transfer,"confirmation");onMain(()->finish.getButton(AlertDialog.BUTTON_POSITIVE).performClick());await(()->Boolean.TRUE.equals(fieldQuiet(transfer,"terminal")),"Restore did not finish");require(((TextView)field(transfer,"status")).getText().toString().contains("0 rides restored"),"Idempotent restore completes without duplication");require(new RideStore(ridesFolder).list().size()==2,"Reopened recordings still retained");picked.delete();
        result.putString("status","PASS");result.putInt("assertions",assertions);result.putString("summary","Full journal merge/reopen, native file moves and identities, no duplicate mapped rides, consent, busy refusal, document picker, profile mapping and preview/apply");finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("status","FAIL");result.putString("error",android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,result);}}
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
