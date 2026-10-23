package com.jjricks.volt.tests;

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
import com.jjricks.volt.BackupActivity;
import com.jjricks.volt.DataMaintenance;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.MoreScreen;
import com.jjricks.volt.OnboardingScreen;

/** Disposable fresh emulator only. Exercises the Back up & restore entry, consent, category list and the paused lifecycle. */
public final class BackupUiValidation extends Instrumentation {
    private Activity current;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>();
    private final CountDownLatch releaseWork = new CountDownLatch(1);
    private String theme = "dark";
    @Override public void onCreate(Bundle args) { if(args!=null)theme=args.getString("theme","dark");start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            require(settings().edit().putString("theme",theme).commit(),"Fixture theme did not save");
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
            await(()->root(main).findViewWithTag("onboarding-restore-backup")!=null,"Welcome backup link missing");
            require(main.service()==null,"Welcome started Bluetooth before permissions");
            verifySetupPath(main);
            require(text(root(main),"Restore Volt backup")!=null,"Welcome restore label is not distinct from owner setup");
            onMain(()->root(main).findViewWithTag("onboarding-restore-backup").performClick());
            AlertDialog consent=(AlertDialog)field(main,"controlDialog");
            require(consent!=null&&consent.isShowing(),"Welcome restore did not open consent");
            require(!DataMaintenance.active(),"Opening consent started maintenance");
            onMain(()->consent.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            require(!DataMaintenance.active(),"Cancelling consent paused app data");
            require(getTargetContext().getSharedPreferences("profiles",0).getAll().isEmpty()
                    &&!getTargetContext().getSharedPreferences("onboarding",0).getBoolean("onboarding_done",false),"Cancelled restore completed setup or wrote a scooter");
            checks.add("Restore is available directly from welcome without owner setup; cancel leaves app running and data unchanged");

            if(Build.VERSION.SDK_INT>=31){
                getUiAutomation().grantRuntimePermission(getTargetContext().getPackageName(),android.Manifest.permission.BLUETOOTH_SCAN);
                getUiAutomation().grantRuntimePermission(getTargetContext().getPackageName(),android.Manifest.permission.BLUETOOTH_CONNECT);
            }
            onMain(()->invoke(main,"ensureService",new Class<?>[]{}));
            await(()->main.service()!=null,"Local workspace did not initialize after emulator permissions");
            verifySetupSuccessBackup(main);
            onMain(()->{OnboardingScreen.markDone(getTargetContext());main.rebuild();invoke(main,"select",new Class<?>[]{String.class},"more");});
            MoreScreen more=(MoreScreen)field(main,"screen");
            onMain(()->invoke(more,"selectSection",new Class<?>[]{String.class},"App"));
            require(text(root(main),"Dashboard backups")==null,"Settings no longer carry a separate dashboard backup");
            onMain(()->invoke(more,"selectSection",new Class<?>[]{String.class},""));
            await(()->root(main).findViewWithTag("data-backup")!=null&&root(main).findViewWithTag("data-backup").isShown(),"Back up & restore missing from the More menu");
            require(text(root(main),"Save private owner backup")==null,"Confusing separate owner backup export remains");
            require(text(root(main),"Back up & restore")!=null,"Back up & restore label missing");
            onMain(()->{settings().edit().putBoolean("developer_options",true).putString("more_section","Developer").commit();main.rebuild();});
            await(()->root(main).findViewWithTag("developer-example-slot")!=null,"Developer section did not rebuild");
            require(root(main).findViewWithTag("developer-full-export")==null&&root(main).findViewWithTag("developer-full-restore")==null,"Developer options no longer duplicate the backup");
            checks.add("One obvious Back up & restore entry in More; no separate owner, settings dashboard or developer full export; slot 3 example kept");

            onMain(()->{settings().edit().putString("more_section","").commit();main.rebuild();});
            await(()->root(main).findViewWithTag("data-backup")!=null,"More backup menu did not rebuild");
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
            require(((TextView)root(backup).findViewWithTag("backup-scope")).getText().toString().startsWith("Full backup"),"Export does not default to a clearly labeled full backup");
            require(((java.util.Set<?>)invoke(backup,"selected",new Class<?>[]{})).containsAll(com.jjricks.volt.AppDataBackup.CATEGORIES),"Default export omits a category");
            for(String category:com.jjricks.volt.AppDataBackup.CATEGORIES){View box=root(backup).findViewWithTag("backup-category-"+category);
                if(box!=null)await(()->box.isLaidOut(),"Category row "+category+" was not laid out");
                require(box instanceof android.widget.CheckBox&&((android.widget.CheckBox)box).getMinHeight()>=dp(backup,48)&&box.getHeight()>=dp(backup,48)&&box.getContentDescription()!=null,"Category row "+category+" is a 48 dp checkbox with a spoken label");}
            require(!((android.widget.CheckBox)root(backup).findViewWithTag("backup-category-rides")).isEnabled(),"An empty category is disabled");
            onMain(()->root(backup).findViewWithTag("backup-category-preferences").performClick());
            require(((TextView)root(backup).findViewWithTag("backup-scope")).getText().toString().startsWith("Selected data only"),"Partial selection still claims a full backup");
            onMain(()->root(backup).findViewWithTag("backup-select-all").performClick());
            require(((java.util.Set<?>)invoke(backup,"selected",new Class<?>[]{})).containsAll(com.jjricks.volt.AppDataBackup.CATEGORIES),"Select all did not restore full scope");
            require(textContaining(root(backup),"Permissions and Bluetooth pairing aren't included.")!=null,"Exclusions are stated");
            render(root(backup),"backup-categories.png");
            EditText secret=(EditText)field(backup,"password");
            PasswordKeyboardChecks.verify(this,backup,secret,true);
            PasswordKeyboardChecks.verify(this,backup,(EditText)field(backup,"repeat"));
            PasswordKeyboardChecks.verify(this,backup,secret);
            checks.add("New and repeat passwords stay fully visible above the real keyboard while typing, changing focus and reopening the keyboard");
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
                require(!root(backup).findViewWithTag("backup-select-all").isEnabled(),"Select all stayed interactive after the export contents were fixed");
                require(((TextView)root(backup).findViewWithTag("backup-scope")).getText().toString().startsWith("Full backup"),"Encryption changed the selected full-backup description");
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
            try{await(()->repeated.isFinishing()&&backup.hasWindowFocus(),"");}catch(AssertionError e){throw new AssertionError("A repeated launch did not return to the backup session: finishing="+repeated.isFinishing()+" focus="+backup.hasWindowFocus()+" backupFinishing="+backup.isFinishing()+" newBackup="+(backupMonitor.getLastActivity()!=null&&backupMonitor.getLastActivity()!=backup)+" hits="+backupMonitor.getHits()+" top="+top());}
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
            // done() runs on the main thread; wait for it to finish rather than racing its field writes.
            await(()->Boolean.TRUE.equals(field(resumed,"terminal"))&&((android.widget.TextView)field(resumed,"closeAction")).isEnabled(),"Close Volt stayed disabled after work finished");
            removeMonitor(backupMonitor);
            checks.add("Password is not saved or screenshot-visible; recreation waits for active work, blocks a second operation and only permits closing/reopening afterward");
            result.putString("result","PASS");
        }catch(Throwable failure){result.putString("result","FAIL");result.putString("error",failure.toString());}
        finally{releaseWork.countDown();if(current!=null)try{onMain(current::finish);}catch(Throwable ignored){}
            result.putInt("assertions",assertions);result.putStringArrayList("checks",checks);result.putInt("scooter_mutations",0);
            finish("PASS".equals(result.getString("result"))?0:1,result);}
    }
    /** A fresh install recommends browser setup; a deliberately selected route survives rebuild. */
    private void verifySetupPath(MainActivity main){
        onMain(()->clickText(root(main),"Get started"));
        onMain(()->{
            require("webadb".equals(invoke(field(main,"onboarding"),"path",new Class<?>[]{})),"Fresh setup does not default to Chrome/USB");
            require(text(root(main),"Recommended")!=null&&text(root(main),"Preview")==null,"Chrome/USB recommendation missing or still a preview");
        });
        SystemClock.sleep(450);waitForIdleSync(); // Let the welcome-to-path crossfade finish before the review image.
        render(root(main),"setup-paths.png");
        onMain(()->clickText(root(main),"Continue"));
        require(text(root(main),"A computer with Chrome or Edge")!=null,"Default Continue did not enter browser preparation");
        onMain(main::rebuild);
        require(text(root(main),"A computer with Chrome or Edge")!=null,"New default route did not survive resuming setup");
        onMain(()->((OnboardingScreen)field(main,"onboarding")).back());
        onMain(()->clickText(root(main),"Capture it from the NAVEE app"));
        onMain(main::rebuild);
        require("capture".equals(invoke(field(main,"onboarding"),"path",new Class<?>[]{})),"Rebuild replaced the owner's selected setup route");
        onMain(()->{
            getTargetContext().getSharedPreferences("onboarding",0).edit().putInt("step",2).remove("path").commit();
            main.rebuild();
        });
        require(text(root(main),"A computer with ADB and Python 3")!=null,"Upgrading changed an unfinished legacy default ADB route");
        require("capture".equals(getTargetContext().getSharedPreferences("onboarding",0).getString("path","")),"Legacy resumed route was not preserved durably");
        onMain(()->((OnboardingScreen)field(main,"onboarding")).back());
        onMain(()->((OnboardingScreen)field(main,"onboarding")).back());
        checks.add("Fresh onboarding recommends Chrome/USB; the new default, a chosen alternative and an unfinished legacy default ADB route all survive resuming setup");
    }
    /** The success screen must route to the same encrypted, all-category backup, not plaintext pairing export. */
    private void verifySetupSuccessBackup(MainActivity main){
        final Object[][] requested={null};final int[] requests={0};final OnboardingScreen[] success={null};
        onMain(()->{
            settings().edit().putBoolean("auto_connect",false).putBoolean("background_connection",false).commit();
            main.service().profiles.create("Synthetic completed setup","AABBCCDDEEFF",0);
            com.jjricks.volt.ScreenHost host=(com.jjricks.volt.ScreenHost)java.lang.reflect.Proxy.newProxyInstance(
                    com.jjricks.volt.ScreenHost.class.getClassLoader(),new Class<?>[]{com.jjricks.volt.ScreenHost.class},(proxy,method,args)->{
                        if(method.getName().equals("saveText"))throw new AssertionError("Setup success still exports a plaintext owner file");
                        if(method.getName().equals("dataBackup")){requested[0]=args;requests[0]++;}
                        return method.invoke(main,args);
                    });
            success[0]=new OnboardingScreen(host,()->{});invoke(success[0],"done",new Class<?>[]{});
            View backup=success[0].view().findViewWithTag("onboarding-create-backup");
            require(backup instanceof TextView&&"Back up Volt".contentEquals(((TextView)backup).getText()),"Success screen does not offer a clearly named full backup");
            require(text(success[0].view(),"Save private owner backup")==null,"Success screen still offers separate owner export");
            require(backup.performClick(),"Success backup action did not respond");
        });
        require(requests[0]==1&&requested[0]!=null&&Boolean.FALSE.equals(requested[0][0])&&requested[0][1]==null,"Success backup did not request the default all-category export");
        AlertDialog consent=(AlertDialog)field(main,"controlDialog");
        require(consent!=null&&consent.isShowing()&&textContaining(consent.getWindow().getDecorView(),"Volt pauses until you reopen it.")!=null,"Success backup bypassed unified consent");
        require(!DataMaintenance.active(),"Success backup entered maintenance before consent");
        onMain(()->{consent.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();success[0].close();});
        require(!DataMaintenance.active(),"Cancelling success backup paused app data");
        checks.add("Setup success Back up Volt uses unified consent and full-default export; no plaintext owner export; cancel leaves the app usable");
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
    private String top(){try{android.app.ActivityManager am=(android.app.ActivityManager)getTargetContext().getSystemService(Context.ACTIVITY_SERVICE);return String.valueOf(am.getAppTasks().get(0).getTaskInfo().topActivity);}catch(Exception e){return e.toString();}}
    private void await(BooleanSupplier ready,String message){long limit=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<limit){if(ready.getAsBoolean())return;SystemClock.sleep(30);}throw new AssertionError(message);}
    private void require(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
