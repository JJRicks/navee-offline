package local.navee.offlinelock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Terminal maintenance screen. No scooter models are resumed after replacing their storage. */
public final class BackupActivity extends Activity {
    private static final int PICK_BACKUP=401, SAVE_BACKUP=402;
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final AtomicInteger OPERATIONS=new AtomicInteger();
    private static boolean sessionStarted;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private Ui ui;
    private LinearLayout content;
    private TextView status, action, closeAction;
    private EditText password, repeat;
    private ProgressBar progress;
    private boolean busy, terminal, restore, waitingForPicker;
    private char[] pendingPassword;
    private AppDataBackup.PreparedRestore staged;
    private File exported;
    private AlertDialog confirmation;
    private android.window.OnBackInvokedCallback backCallback;

    @Override public void onCreate(Bundle state) {
        setTheme(R.style.VoltDark); super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ui=new Ui(this,true,1f);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(ui.bg);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(24),ui.dp(32),ui.dp(24),ui.dp(32));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(i.left,i.top,i.right,i.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        if(Build.VERSION.SDK_INT>=33){backCallback=this::requestClose;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}
        restore=getIntent().getBooleanExtra("restore",false);
        boolean priorSession=sessionStarted;sessionStarted=true;
        if(priorSession || state!=null){
            DataMaintenance.enter();heading("Backup session interrupted");status=paragraph("");progress=new ProgressBar(this);content.addView(progress);
            terminal=true;closeAction=button("Close Volt",this::requestClose);
            // Passwords and staged approval are deliberately never carried through recreation.
            // Let an already approved operation finish, then require a fresh process/models.
            handler.post(new Runnable(){public void run(){if(isDestroyed())return;
                if(OPERATIONS.get()>0){busy=true;status.setText("Finishing the previous operation. Keep Volt open.");handler.postDelayed(this,200);}
                else done("Close and reopen Volt to continue. If a restore had started, the saved recovery copy will be checked before the app opens.");}});
            return;
        }
        if(getIntent().getBooleanExtra("recovery",false)){
            DataMaintenance.enter();heading("Recovering your app data");status=paragraph("Keep Volt open while the previous restore is rolled back.");
            progress=new ProgressBar(this);content.addView(progress);busy=true;
            submit(()->{try{AppDataBackup.recover(this);show(()->done("Your previous app data was recovered. Close and reopen Volt."));}
                catch(Exception failure){show(()->done("Recovery could not finish. Your recovery copy is kept. Free some storage, then close and reopen Volt to try again. Do not clear app storage."));}});
            return;
        }
        if(!DataMaintenance.active()){
            DataMaintenance.enter();heading("Backup session ended");status=paragraph("");progress=new ProgressBar(this);content.addView(progress);
            done("Close and reopen Volt to start a new backup session.");return;
        }
        String preparationError=getIntent().getStringExtra("preparation_error");
        if(preparationError!=null){heading("App data is paused");status=paragraph(preparationError);progress=new ProgressBar(this);content.addView(progress);done(preparationError+". Close and reopen Volt before trying again.");return;}
        form();
    }
    private void heading(String title){TextView text=ui.text(title,28,true);content.addView(text);}
    private TextView paragraph(String message){TextView text=ui.caption(message);text.setTextSize(15);text.setPadding(0,ui.dp(14),0,ui.dp(14));content.addView(text);return text;}
    private TextView button(String label,Runnable callback){TextView button=ui.button(label,callback);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=ui.dp(12);content.addView(button,p);return button;}
    private EditText passwordField(String hint){
        EditText field=new EditText(this);field.setHint(hint);field.setTextColor(ui.text);field.setHintTextColor(ui.muted);
        field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});field.setMinHeight(ui.dp(56));content.addView(field);return field;
    }
    private void form(){
        heading(restore?"Restore all app data":"Export all app data");
        paragraph(restore?"Choose your encrypted .voltbackup file and enter its password. Nothing is replaced until you approve the checked backup."
                :"Includes all scooter profiles and private setup keys, saved readings, telemetry history, rides and routes, charge recordings, preferences, widgets and saved layouts.");
        paragraph("Android permissions, Bluetooth pairing, Shizuku access, temporary captures and map caches are not included. Scooter firmware settings are not changed by restoring saved readings.");
        password=passwordField(restore?"Backup password":"New backup password (at least 8 characters)");
        if(!restore){repeat=passwordField("Repeat password");paragraph("Keep this password somewhere safe. It is not saved in Volt and cannot be recovered. Save the backup outside this app before clearing storage.");}
        status=paragraph("");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress=new ProgressBar(this);progress.setVisibility(View.GONE);content.addView(progress);
        action=button(restore?"Choose backup":"Create encrypted backup",this::start);
        action.setTag(restore?"full-restore-choose":"full-backup-create");
        closeAction=button("Close Volt",this::requestClose);
    }
    private void start(){
        if(busy||waitingForPicker||terminal)return;
        char[] secret=new char[password.length()];password.getText().getChars(0,password.length(),secret,0);
        if(secret.length<8){Arrays.fill(secret,'\0');password.setError("Use at least 8 characters.");return;}
        if(!restore){char[] repeated=new char[repeat.length()];repeat.getText().getChars(0,repeat.length(),repeated,0);
            boolean same=Arrays.equals(secret,repeated);Arrays.fill(repeated,'\0');
            if(!same){Arrays.fill(secret,'\0');repeat.setError("The passwords do not match.");return;}}
        password.setText("");if(repeat!=null)repeat.setText("");
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(password.getWindowToken(),0);
        if(restore){pendingPassword=secret;Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try{waitingForPicker=true;startActivityForResult(picker,PICK_BACKUP);}catch(Exception missing){waitingForPicker=false;erasePassword();status.setText("A file picker could not open.");}return;}
        setBusy("Encrypting your app data…");
        submit(()->{
            File file=null;
            try{file=File.createTempFile("volt-encrypted-",".voltbackup",getCacheDir());
                try(OutputStream out=new FileOutputStream(file)){AppDataBackup.write(this,out,secret);}
                File ready=file;runOnUiThread(()->{if(isDestroyed()||isFinishing()){ready.delete();return;}exported=ready;busy=false;progress.setVisibility(View.GONE);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);launchSave();});
            }catch(Exception failure){if(file!=null)file.delete();show(()->retry("The backup could not be created. Check free storage and try again. Backups support up to 2 GiB of data."));}
            finally{Arrays.fill(secret,'\0');}
        });
    }
    private void launchSave(){
        if(busy||waitingForPicker||terminal||isDestroyed()||isFinishing())return;
        if(exported==null||!exported.isFile()){retry("The encrypted backup is unavailable. Create it again.");return;}
        status.setText("Encrypted backup ready. Choose where to save it.");
        action.setText("Save encrypted backup");action.setEnabled(true);action.setOnClickListener(v->launchSave());
        Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream");
        picker.putExtra(Intent.EXTRA_TITLE,"volt-"+new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm",java.util.Locale.ROOT).format(new java.util.Date())+".voltbackup");
        try{waitingForPicker=true;startActivityForResult(picker,SAVE_BACKUP);}catch(Exception missing){waitingForPicker=false;status.setText("A file picker could not open. Tap Save to try again.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);waitingForPicker=false;
        if(terminal||isDestroyed()||isFinishing()){erasePassword();return;}
        if(result!=RESULT_OK||data==null||data.getData()==null){erasePassword();status.setText("No file selected. Your app data is unchanged.");return;}
        Uri uri=data.getData();
        if(request==PICK_BACKUP){char[] secret=pendingPassword;pendingPassword=null;if(secret==null){retry("Enter the password and choose the backup again.");return;}
            setBusy("Checking the encrypted backup…");
            submit(()->{try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new java.io.IOException();AppDataBackup.PreparedRestore checked=AppDataBackup.stage(this,in,secret);
                runOnUiThread(()->{if(isDestroyed()||isFinishing()){submit(()->discardQuietly(checked));return;}staged=checked;busy=false;progress.setVisibility(View.GONE);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);confirmRestore();});
            }catch(Exception failure){show(()->retry("The password is incorrect, or this backup is damaged, unsupported or too large. No app data was changed."));}
            finally{Arrays.fill(secret,'\0');}});
        }else if(request==SAVE_BACKUP){setBusy("Saving the encrypted backup…");
            File ready=exported;
            submit(()->{try(InputStream in=new FileInputStream(ready);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                if(out==null)throw new java.io.IOException();byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);out.flush();
            }catch(Exception failure){show(()->retry("Saving failed. The chosen file may be incomplete; tap Save encrypted backup to choose a destination again. Do not clear app storage yet."));return;}
            ready.delete();show(()->{exported=null;done("Encrypted backup saved. Keep the file and its password before clearing app storage. Restore it from the welcome screen or Developer options.");});});
        }
    }
    private void confirmRestore(){
        if(staged==null)return;
        String date=java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(staged.createdAtMs));
        status.setText("Backup checked · "+date+"\n"+staged.profileCount+" scooter profiles · "+staged.fileCount+" data files");
        confirmation=new AlertDialog.Builder(this).setTitle("Replace all saved app data?")
                .setMessage("Use the backup from "+date+"? This replaces all scooter profiles and setup keys, readings, rides, charge recordings, preferences and dashboards on this installation. It does not merge them.\n\nAndroid permissions must be granted again after clearing storage.")
                .setNegativeButton("Cancel",(d,n)->discardStaged())
                .setOnCancelListener(d->discardStaged())
                .setPositiveButton("Replace app data",(d,n)->applyRestore()).show();
    }
    private void applyRestore(){
        if(staged==null||busy)return;AppDataBackup.PreparedRestore approved=staged;staged=null;setBusy("Restoring your app data…");
        submit(()->{try{AppDataBackup.apply(this,approved);show(()->done("All saved app data restored. Close and reopen Volt. Android permissions and Shizuku access may need to be granted again."));}
            catch(Exception failure){show(()->done("The restore could not finish. Close and reopen Volt to recover the previous data before trying again. Do not clear app storage."));}
            finally{discardQuietly(approved);}});
    }
    private void discardStaged(){AppDataBackup.PreparedRestore old=staged;staged=null;if(old!=null)submit(()->discardQuietly(old));retry("Restore cancelled. Your app data is unchanged.");}
    private static void submit(Runnable work){OPERATIONS.incrementAndGet();WORKER.execute(()->{try{work.run();}finally{OPERATIONS.decrementAndGet();}});}
    private void show(Runnable update){runOnUiThread(()->{if(!isDestroyed()&&!isFinishing())update.run();});}
    private void discardQuietly(AppDataBackup.PreparedRestore value){try{AppDataBackup.discard(this,value);}catch(java.io.IOException ignored){/* Startup recovery removes abandoned staging; never remove a transaction here. */}}
    private void setBusy(String message){busy=true;status.setText(message);progress.setVisibility(View.VISIBLE);if(action!=null)action.setEnabled(false);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void retry(String message){busy=false;status.setText(message);progress.setVisibility(View.GONE);if(action!=null)action.setEnabled(true);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void done(String message){busy=false;terminal=true;status.setText(message);progress.setVisibility(View.GONE);if(action!=null)action.setVisibility(View.GONE);if(closeAction==null)closeAction=button("Close Volt",this::requestClose);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void erasePassword(){if(pendingPassword!=null){Arrays.fill(pendingPassword,'\0');pendingPassword=null;}}
    private void requestClose(){
        if(busy||OPERATIONS.get()>0){status.setText("Please wait until this operation finishes. Keep Volt open.");return;}
        if(terminal){closeProcess();return;}
        new AlertDialog.Builder(this).setTitle("Close Volt?").setMessage("The scooter connection is paused. Reopen Volt when you’re ready to continue.")
                .setNegativeButton("Stay here",null).setPositiveButton("Close",(d,n)->closeProcess()).show();
    }
    private void closeProcess(){
        if(busy||OPERATIONS.get()>0)return;
        busy=true;
        erasePassword();if(exported!=null)exported.delete();
        AppDataBackup.PreparedRestore old=staged;staged=null;
        submit(()->{if(old!=null)discardQuietly(old);runOnUiThread(()->{finishAffinity();android.os.Process.killProcess(android.os.Process.myPid());});});
    }
    @Override public void onBackPressed(){requestClose();}
    @Override protected void onDestroy(){
        if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        handler.removeCallbacksAndMessages(null);erasePassword();if(confirmation!=null)confirmation.dismiss();
        AppDataBackup.PreparedRestore old=staged;staged=null;File ready=exported;exported=null;
        // Queue cleanup behind any write already using these files.
        submit(()->{if(old!=null)discardQuietly(old);if(ready!=null)ready.delete();});super.onDestroy();
    }
}
