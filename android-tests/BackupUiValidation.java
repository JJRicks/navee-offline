package local.navee.offlinelock.tests;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.BackupActivity;
import local.navee.offlinelock.DataMaintenance;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.MoreScreen;
import local.navee.offlinelock.OnboardingScreen;

/** Disposable fresh emulator only. Exercises the Back up & restore entry, consent, category list and the paused lifecycle. */
public final class BackupUiValidation extends Instrumentation {
    private Activity current;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>();
    private final CountDownLatch releaseWork = new CountDownLatch(1);
    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            MainActivity activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            current = activity;
            long deadline=SystemClock.elapsedRealtime()+8000;
            while(Boolean.TRUE.equals(field(activity,"startupRedirect"))&&SystemClock.elapsedRealtime()<deadline){
                Activity replacement=waitForMonitorWithTimeout(monitor,500);
                if(replacement instanceof MainActivity){activity=(MainActivity)replacement;current=activity;}
            }
            removeMonitor(monitor);
            MainActivity main=activity;
            await(()->text(root(main),"Restore a backup")!=null,"Welcome backup link missing");
            onMain(()->clickText(root(main),"Restore a backup"));
            AlertDialog consent=(AlertDialog)field(main,"controlDialog");
            require(consent!=null&&consent.isShowing(),"Welcome restore did not open consent");
            require(!DataMaintenance.active(),"Opening consent started maintenance");
            onMain(()->consent.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            require(!DataMaintenance.active(),"Cancelling consent paused app data");
            checks.add("Restore is available directly from welcome without owner setup; cancel leaves app running and data unchanged");

            if(Build.VERSION.SDK_INT>=31){
                getUiAutomation().grantRuntimePermission(getTargetContext().getPackageName(),android.Manifest.permission.BLUETOOTH_SCAN);
                getUiAutomation().grantRuntimePermission(getTargetContext().getPackageName(),android.Manifest.permission.BLUETOOTH_CONNECT);
            }
            onMain(()->invoke(main,"ensureService",new Class<?>[]{}));
            await(()->main.service()!=null,"Local workspace did not initialize after emulator permissions");
            onMain(()->{OnboardingScreen.markDone(getTargetContext());main.rebuild();invoke(main,"select",new Class<?>[]{String.class},"more");});
            MoreScreen more=(MoreScreen)field(main,"screen");
            onMain(()->invoke(more,"selectSection",new Class<?>[]{String.class},"App"));
            require(text(root(main),"Dashboard backups")==null,"Settings no longer carry a separate dashboard backup");
            onMain(()->invoke(more,"selectSection",new Class<?>[]{String.class},"About"));
            await(()->root(main).findViewWithTag("data-backup")!=null,"Back up & restore missing from Your data");
            require(text(root(main),"Back up & restore")!=null,"Back up & restore label missing");
            onMain(()->{settings().edit().putBoolean("developer_options",true).putString("more_section","Developer").commit();main.rebuild();});
            await(()->root(main).findViewWithTag("developer-example-slot")!=null,"Developer section did not rebuild");
            require(root(main).findViewWithTag("developer-full-export")==null&&root(main).findViewWithTag("developer-full-restore")==null,"Developer options no longer duplicate the backup");
            checks.add("One Back up & restore entry in Your data; no settings dashboard backup and no developer full backup; slot 3 example kept");

            onMain(()->{settings().edit().putString("more_section","About").commit();main.rebuild();});
            await(()->root(main).findViewWithTag("data-backup")!=null,"Your data did not rebuild");
            onMain(()->root(main).findViewWithTag("data-backup").performClick());
            AlertDialog chooser=(AlertDialog)field(main,"controlDialog");
            require(chooser.isShowing()&&chooser.getWindow().getDecorView().findViewWithTag("data-backup-create")!=null&&chooser.getWindow().getDecorView().findViewWithTag("data-backup-restore")!=null,"Back up and Restore offered");
            require(!DataMaintenance.active(),"Choosing a direction does not pause data");
            ActivityMonitor backupMonitor=addMonitor(BackupActivity.class.getName(),null,false);
            onMain(()->chooser.getWindow().getDecorView().findViewWithTag("data-backup-create").performClick());
            AlertDialog full=(AlertDialog)field(main,"controlDialog");
            require(textContaining(full.getWindow().getDecorView(),"Volt pauses until you reopen it.")!=null,"Consent explains the pause");
            onMain(()->full.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            Activity opened=waitForMonitorWithTimeout(backupMonitor,10000);
            require(opened instanceof BackupActivity,"Paused backup screen did not open");
            BackupActivity backup=(BackupActivity)opened;current=backup;
            await(()->root(backup).findViewWithTag("backup-create")!=null,"Back up categories missing");
            require(DataMaintenance.active(),"Back up did not enter maintenance");
            require(root(backup).findViewWithTag("backup-select-all")!=null,"Select all missing");
            for(String category:local.navee.offlinelock.AppDataBackup.CATEGORIES){View box=root(backup).findViewWithTag("backup-category-"+category);
                require(box instanceof android.widget.CheckBox&&box.getMinimumHeight()>=dp(backup,48)&&box.getContentDescription()!=null,"Category row "+category+" is a 48 dp checkbox with a spoken label");}
            require(!((android.widget.CheckBox)root(backup).findViewWithTag("backup-category-rides")).isEnabled(),"An empty category is disabled");
            require(textContaining(root(backup),"Permissions and Bluetooth pairing aren't included.")!=null,"Exclusions are stated");
            render(root(backup),"backup-categories.png");
            EditText secret=(EditText)field(backup,"password");
            require(!secret.isSaveEnabled(),"Password would be copied into saved Activity state");
            require((backup.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0,"Backup screenshot protection missing");
            onMain(()->root(backup).findViewWithTag("backup-create").performClick());
            require(secret.getError()!=null&&!Boolean.TRUE.equals(field(backup,"waitingForPicker")),"Empty password opened picker");
            onMain(()->{((EditText)field(backup,"password")).setText("Synthetic backup UI test");((EditText)field(backup,"repeat")).setText("Different password");root(backup).findViewWithTag("backup-create").performClick();});
            require(((EditText)field(backup,"repeat")).getError()!=null&&!Boolean.TRUE.equals(field(backup,"waitingForPicker")),"Mismatched passwords opened picker");
            // Exercise the actual encryption-to-save-picker transition. Cancelling the picker
            // must leave Close Volt enabled; otherwise the new busy-state button strands export.
            IntentFilter saves=new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
            saves.addCategory(Intent.CATEGORY_OPENABLE);
            try{saves.addDataType("application/octet-stream");}catch(IntentFilter.MalformedMimeTypeException impossible){throw new AssertionError(impossible);}
            ActivityMonitor savePicker=addMonitor(saves,new ActivityResult(Activity.RESULT_CANCELED,null),true);
            onMain(()->{
                ((EditText)field(backup,"password")).setText("Synthetic backup UI test");
                ((EditText)field(backup,"repeat")).setText("Synthetic backup UI test");
                root(backup).findViewWithTag("backup-create").performClick();
                require(!((TextView)field(backup,"closeAction")).isEnabled(),"Close Volt stayed enabled while encrypting");
            });
            await(()->savePicker.getHits()>0&&!Boolean.TRUE.equals(field(backup,"waitingForPicker")),"Encrypted backup did not reach the cancelled save picker");
            require(((TextView)field(backup,"closeAction")).isEnabled(),"Close Volt stayed disabled after cancelling save picker");
            require("No file selected".contentEquals(((TextView)field(backup,"status")).getText()),"Picker cancellation status missing");
            require(((File)field(backup,"exported")).isFile(),"Cancelled picker discarded encrypted backup before retry");
            removeMonitor(savePicker);
            checks.add("Encryption disables Close only while busy; canceling the save picker re-enables Close and preserves the encrypted file for retry");
            CountDownLatch workStarted=new CountDownLatch(1);
            Runnable synthetic=()->{workStarted.countDown();try{releaseWork.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}};
            onMain(()->invoke(backup,"submit",new Class<?>[]{Runnable.class},synthetic));
            require(workStarted.await(3,TimeUnit.SECONDS),"Synthetic operation did not start");
            ActivityMonitor mainMonitor=addMonitor(MainActivity.class.getName(),null,false);
            onMain(()->backup.startActivity(new Intent(getTargetContext(),MainActivity.class)));
            Activity repeated=waitForMonitorWithTimeout(mainMonitor,6000);removeMonitor(mainMonitor);
            require(repeated!=null,"Repeated launch was not exercised");
            await(()->repeated.isFinishing()&&backup.hasWindowFocus(),"A repeated launch did not return to the backup session");
            require(!Boolean.TRUE.equals(field(backup,"terminal")),"A repeated launch ended the backup session");
            removeMonitor(backupMonitor);
            backupMonitor=addMonitor(BackupActivity.class.getName(),null,false);
            onMain(backup::recreate);
            Activity replacement=waitForMonitorWithTimeout(backupMonitor,6000);
            require(replacement instanceof BackupActivity&&replacement!=backup,"Backup recreation failed");
            BackupActivity resumed=(BackupActivity)replacement;current=resumed;
            await(()->text(root(resumed),"Backup session interrupted")!=null,"Recreation did not fence previous session");
            require(root(resumed).findViewWithTag("restore-choose")==null&&root(resumed).findViewWithTag("backup-create")==null,"Recreated session permits another operation");
            await(()->!((android.widget.TextView)field(resumed,"closeAction")).isEnabled(),"Close Volt stayed enabled during active work");
            onMain(()->invoke(resumed,"requestClose",new Class<?>[]{}));
            require(!resumed.isFinishing(),"Close killed an active backup operation");
            releaseWork.countDown();
            await(()->!Boolean.TRUE.equals(field(resumed,"busy")),"Interrupted session never became closable");
            require(Boolean.TRUE.equals(field(resumed,"terminal")),"Interrupted session resumed mutable forms");
            require(((android.widget.TextView)field(resumed,"closeAction")).isEnabled(),"Close Volt stayed disabled after work finished");
            removeMonitor(backupMonitor);
            checks.add("Password is not saved or screenshot-visible; recreation waits for active work, blocks a second operation and only permits closing/reopening afterward");
            result.putString("result","PASS");
        }catch(Throwable failure){result.putString("result","FAIL");result.putString("error",failure.toString());}
        finally{releaseWork.countDown();if(current!=null)try{onMain(current::finish);}catch(Throwable ignored){}
            result.putInt("assertions",assertions);result.putStringArrayList("checks",checks);result.putInt("scooter_mutations",0);
            finish("PASS".equals(result.getString("result"))?0:1,result);}
    }
    private void admit(){
        String hardware=Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu")||hardware.contains("goldfish")||Build.PRODUCT.startsWith("sdk_gphone"),"Refusing physical phone");
        for(String name:new String[]{"app_settings","profiles","scooter","onboarding","pending_documents","control_evidence"})
            require(getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE).getAll().isEmpty(),"Refusing existing app preferences");
        File[] files=getTargetContext().getFilesDir().listFiles();
        require(files!=null&&files.length==0&&getTargetContext().databaseList().length==0,"Refusing existing app data");
        BluetoothManager manager=getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        // A permission denial is also safe: no service or radio can start from this fresh fixture.
        try{require(adapter==null||!adapter.isEnabled(),"Disable emulator Bluetooth first");}catch(SecurityException denied){}
    }
    private SharedPreferences settings(){return getTargetContext().getSharedPreferences("app_settings",Context.MODE_PRIVATE);}
    private View root(Activity activity){return activity.getWindow().getDecorView();}
    private static int dp(Activity activity,float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private TextView textContaining(View view,String wanted){if(view instanceof TextView&&((TextView)view).getText().toString().contains(wanted))return(TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=textContaining(group.getChildAt(i),wanted);if(found!=null)return found;}}return null;}
    /** View.draw render for review: normal screenshots honour the screen's privacy flag. */
    private void render(View view,String name){AtomicReference<Throwable> error=new AtomicReference<>();runOnMainSync(()->{try{
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
        try(java.io.OutputStream out=new java.io.FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}catch(Throwable e){error.set(e);}});
        if(error.get()!=null)throw new RuntimeException(error.get());}
    private TextView text(View view,String wanted){if(view instanceof TextView&&wanted.contentEquals(((TextView)view).getText()))return(TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=text(group.getChildAt(i),wanted);if(found!=null)return found;}}return null;}
    private void clickText(View root,String wanted){View found=text(root,wanted);require(found!=null,"Missing text "+wanted);while(!found.isClickable()&&found.getParent()instanceof View)found=(View)found.getParent();require(found.performClick(),"Could not click "+wanted);}
    private Object field(Object object,String name){try{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(Exception e){throw new RuntimeException(e);}}
    private void setField(Object object,String name,Object value){try{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}catch(Exception e){throw new RuntimeException(e);}}
    private Object invoke(Object object,String name,Class<?>[] types,Object...args){try{Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,args);}catch(Exception e){throw new RuntimeException(e);}}
    private void onMain(Runnable action){AtomicReference<Throwable> error=new AtomicReference<>();runOnMainSync(()->{try{action.run();}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new RuntimeException(error.get());waitForIdleSync();}
    private void await(BooleanSupplier ready,String message){long limit=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<limit){if(ready.getAsBoolean())return;SystemClock.sleep(30);}throw new AssertionError(message);}
    private void require(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
