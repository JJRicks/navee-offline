package local.navee.offlinelock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Back up & restore: choose categories, review, then a fresh process. Terminal maintenance
 * screen; no scooter models are resumed after replacing their storage, even after cancel. */
public final class BackupActivity extends Activity {
    private static final int PICK_BACKUP=401, SAVE_BACKUP=402;
    private static final String NO_SPACE="Not enough free storage", CHANGED="Saved data changed. Review again.";
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final AtomicInteger OPERATIONS=new AtomicInteger();
    private static boolean sessionStarted;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private Ui ui;
    private float textScale=1f;
    private LinearLayout content;
    private TextView status, action, closeAction;
    private EditText password, repeat;
    private ProgressBar progress;
    private boolean busy, terminal, restore, waitingForPicker, hasData;
    private String preselect;
    private final LinkedHashMap<String,CheckBox> boxes=new LinkedHashMap<>();
    private Map<String,long[]> inventory;
    private Uri picked;
    private AppDataBackup.PreparedRestore staged;
    private Switch replaceAll;
    private File exported;
    private AlertDialog confirmation;
    private android.window.OnBackInvokedCallback backCallback;

    @Override public void onCreate(Bundle state) {
        Intent active=DataMaintenance.preparedTransfer();
        if(DataMaintenance.active()&&(active==null||!getComponentName().equals(active.getComponent()))){
            super.onCreate(state);startActivity((active==null?new Intent(this,MainActivity.class):active).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));finish();return;
        }
        // Follow the rider's theme and text size; preferences are only read here.
        boolean dark=true;
        try{android.content.SharedPreferences settings=getSharedPreferences("app_settings",MODE_PRIVATE);String theme=settings.getString("theme","dark");
            dark=theme.equals("dark")||(theme.equals("system")&&(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);
            float scale=settings.getFloat("text_scale",1f);if(Float.isFinite(scale)&&scale>=.5f&&scale<=3f)textScale=scale;}catch(RuntimeException unreadable){/* Defaults during recovery. */}
        setTheme(dark?R.style.VoltDark:R.style.VoltLight);super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ui=new Ui(this,dark,textScale);
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
        preselect=getIntent().getStringExtra("preselect");if(preselect!=null&&!AppDataBackup.CATEGORIES.contains(preselect))preselect=null;
        boolean priorSession=sessionStarted;sessionStarted=true;
        if(priorSession || state!=null){
            DataMaintenance.enter();DataMaintenance.preparedTransfer(new Intent(this,BackupActivity.class));heading("Backup session interrupted");status=paragraph("");progress=new ProgressBar(this);content.addView(progress);
            terminal=true;closeAction=button("Close Volt",this::requestClose);
            // Passwords and staged approval are deliberately never carried through recreation.
            // Let an already approved operation finish, then require a fresh process/models.
            handler.post(new Runnable(){public void run(){if(isDestroyed())return;
                if(OPERATIONS.get()>0){busy=true;closeAction.setEnabled(false);status.setText("Finishing the previous operation. Keep Volt open.");handler.postDelayed(this,200);}
                else done("Close and reopen Volt.");}});
            return;
        }
        if(getIntent().getBooleanExtra("recovery",false)){
            DataMaintenance.enter();DataMaintenance.preparedTransfer(new Intent(this,BackupActivity.class));heading("Recovering your app data");status=paragraph("Keep Volt open while the previous restore is rolled back.");
            progress=new ProgressBar(this);content.addView(progress);busy=true;
            submit(()->{try{AppDataBackup.recover(this);RideBackup.recover(this);show(()->done("Your previous app data was recovered. Close and reopen Volt."));}
                catch(Exception failure){show(()->done("Recovery failed. Free space, reopen. Don't clear storage."));}});
            return;
        }
        if(!DataMaintenance.active()){
            DataMaintenance.enter();DataMaintenance.preparedTransfer(new Intent(this,BackupActivity.class));heading("Backup session ended");status=paragraph("");progress=new ProgressBar(this);content.addView(progress);
            done("Reopen Volt to continue.");return;
        }
        String preparationError=getIntent().getStringExtra("preparation_error");
        if(preparationError!=null){heading("App data is paused");status=paragraph("");progress=new ProgressBar(this);content.addView(progress);done(preparationError);return;}
        if(restore)restoreForm();else backupForm();
    }

    // ---- Building blocks ---------------------------------------------------------------------

    private void reset(String title){content.removeAllViews();boxes.clear();replaceAll=null;password=repeat=null;action=closeAction=null;heading(title);}
    private void heading(String title){TextView text=ui.text(title,28,true);if(Build.VERSION.SDK_INT>=28)text.setAccessibilityHeading(true);content.addView(text);}
    private TextView paragraph(String message){return paragraph(content,message);}
    private TextView paragraph(LinearLayout parent,String message){TextView text=ui.caption(message);text.setTextSize(TypedValue.COMPLEX_UNIT_SP,15*textScale);text.setPadding(0,ui.dp(12),0,ui.dp(12));parent.addView(text);return text;}
    private TextView button(String label,Runnable callback){TextView button=ui.button(label,callback);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=ui.dp(12);content.addView(button,p);return button;}
    private void footer(String label,String tag,Runnable callback){
        status=paragraph("");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress=new ProgressBar(this);progress.setVisibility(View.GONE);content.addView(progress);
        action=button(label,callback);action.setTag(tag);
        closeAction=button("Close Volt",this::requestClose);closeAction.setTag("backup-close");
    }
    private void label(TextView button,String text){button.setText(text);button.setContentDescription(text);}
    private EditText passwordField(String hint){
        EditText field=new EditText(this);field.setHint(hint);field.setTextColor(ui.text);field.setHintTextColor(ui.muted);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP,16*textScale);
        field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});field.setMinHeight(ui.dp(56));content.addView(field);return field;
    }
    /** Disabled (empty) categories read as unavailable, not as unchecked choices. */
    private ColorStateList enabledColors(int color){return new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{ui.muted,color});}
    /** One 48 dp checkbox row; its count is spoken with the label. */
    private CheckBox checkRow(LinearLayout list,String id,String label,String detail,boolean enabled,boolean checked){
        if(list.getChildCount()>0)ui.divider(list);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(ui.dp(48));
        CheckBox box=new CheckBox(this);box.setText(label);box.setTextColor(enabledColors(ui.text));box.setTextSize(TypedValue.COMPLEX_UNIT_SP,15*textScale);
        box.setButtonTintList(enabledColors(ui.accent));box.setMinHeight(ui.dp(48));box.setEnabled(enabled);box.setChecked(enabled&&checked);
        box.setContentDescription(detail==null||detail.isEmpty()?label:label+", "+detail.replace(" · ",", "));if(id!=null)box.setTag("backup-category-"+id);
        row.addView(box,new LinearLayout.LayoutParams(0,-2,1));
        if(detail!=null&&!detail.isEmpty()){TextView value=ui.caption(detail);value.setTextSize(TypedValue.COMPLEX_UNIT_SP,13*textScale);value.setGravity(Gravity.END);
            value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);if(detail.endsWith("Private"))value.setTextColor(ui.danger);
            LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);vp.leftMargin=ui.dp(8);row.addView(value,vp);}
        list.addView(row);return box;
    }
    private LinearLayout list(){
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(ui.shape(ui.surface,18,0));list.setPadding(ui.dp(14),ui.dp(2),ui.dp(14),ui.dp(2));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=ui.dp(14);content.addView(list,lp);return list;
    }
    /** Select all mirrors the category boxes and checks or clears every enabled one. */
    private void selectAll(LinearLayout list,Runnable changed){
        CheckBox all=checkRow(list,null,"Select all",null,true,true);all.setTag("backup-select-all");
        boolean[] syncing={false};
        Runnable sync=()->{boolean every=true;for(CheckBox b:boxes.values())if(b.isEnabled()&&!b.isChecked())every=false;syncing[0]=true;all.setChecked(every);syncing[0]=false;if(changed!=null)changed.run();};
        all.setOnCheckedChangeListener((v,on)->{if(syncing[0])return;for(CheckBox b:boxes.values())if(b.isEnabled())b.setChecked(on);sync.run();});
        for(CheckBox b:boxes.values())b.setOnCheckedChangeListener((v,on)->sync.run());
        sync.run();
    }
    private String size(long bytes){return android.text.format.Formatter.formatShortFileSize(this,bytes);}

    // ---- Back up -----------------------------------------------------------------------------

    private void backupForm(){
        reset("Back up");status=paragraph("Counting your data…");progress=new ProgressBar(this);content.addView(progress);busy=true;
        submit(()->{try{Map<String,long[]> counted=AppDataBackup.inventory(this);show(()->{busy=false;inventory=counted;backupCategories();});}
            catch(Exception failure){show(()->done("App data can't be read. Reopen Volt."));}});
    }
    private boolean hasItems(String id){return inventory.get(id)[0]>0||inventory.get(id)[1]>0;}
    private void backupCategories(){
        reset("Back up");
        LinearLayout list=list();LinearLayout categories=new LinearLayout(this);categories.setOrientation(LinearLayout.VERTICAL);
        for(String id:AppDataBackup.CATEGORIES){
            long items=inventory.get(id)[0],bytes=inventory.get(id)[1];String detail;
            if(!hasItems(id))detail="None";
            else if(id.equals("scooters"))detail=items+" · Private";
            else if(id.equals("dashboards")||id.equals("scooter-preferences"))detail=BackupArchive.plural((int)items,"scooter");
            else if(id.equals("preferences"))detail="";
            else if(id.equals("history"))detail=size(bytes);
            else detail=items+" · "+size(bytes);
            boxes.put(id,checkRow(categories,id,AppDataBackup.label(id),detail,hasItems(id),preselect==null||preselect.equals(id)));
        }
        TextView estimate=ui.caption("");estimate.setTextSize(TypedValue.COMPLEX_UNIT_SP,13*textScale);estimate.setPadding(0,ui.dp(10),0,0);
        selectAll(list,()->{long total=0;for(Map.Entry<String,CheckBox> e:boxes.entrySet())if(e.getValue().isChecked())total+=inventory.get(e.getKey())[1];estimate.setText("About "+size(total));});
        list.addView(categories);content.addView(estimate);
        paragraph("Permissions and Bluetooth pairing aren't included.");
        password=passwordField("New password");repeat=passwordField("Repeat password");paragraph("Password can't be recovered.");
        footer("Create backup","backup-create",this::createBackup);
    }
    /** Every category with data checked makes a complete backup, empty categories included. */
    private Set<String> selected(){
        Set<String> result=new LinkedHashSet<>();boolean every=true;
        for(Map.Entry<String,CheckBox> e:boxes.entrySet()){if(e.getValue().isChecked())result.add(e.getKey());else if(e.getValue().isEnabled())every=false;}
        if(every&&!result.isEmpty())result.addAll(boxes.keySet());
        return result;
    }
    private void createBackup(){
        if(busy||waitingForPicker||terminal)return;
        Set<String> categories=selected();
        if(categories.isEmpty()){status.setText("Choose what to back up.");return;}
        char[] secret=new char[password.length()];password.getText().getChars(0,password.length(),secret,0);
        if(secret.length<8){Arrays.fill(secret,'\0');password.setError("Use at least 8 characters.");return;}
        char[] repeated=new char[repeat.length()];repeat.getText().getChars(0,repeat.length(),repeated,0);
        boolean same=Arrays.equals(secret,repeated);Arrays.fill(repeated,'\0');
        if(!same){Arrays.fill(secret,'\0');repeat.setError("The passwords do not match.");return;}
        password.setText("");repeat.setText("");hideKeyboard();
        for(CheckBox box:boxes.values())box.setEnabled(false);
        setBusy("Encrypting your backup…");
        submit(()->{
            File file=null;
            try{file=File.createTempFile("volt-encrypted-",".voltbackup",getCacheDir());
                try(OutputStream out=new FileOutputStream(file)){AppDataBackup.write(this,out,secret,categories);}
                File ready=file;runOnUiThread(()->{if(isDestroyed()||isFinishing()){ready.delete();return;}exported=ready;busy=false;progress.setVisibility(View.GONE);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);launchSave();});
            }catch(Exception failure){if(file!=null)file.delete();show(()->{for(Map.Entry<String,CheckBox> e:boxes.entrySet())e.getValue().setEnabled(hasItems(e.getKey()));retry("Backup failed. Check free space.");});}
            finally{Arrays.fill(secret,'\0');}
        });
    }
    private void launchSave(){
        if(busy||waitingForPicker||terminal||isDestroyed()||isFinishing())return;
        if(exported==null||!exported.isFile()){retry("The backup is unavailable. Create it again.");return;}
        status.setText("");if(closeAction!=null)closeAction.setEnabled(true);
        label(action,"Save backup");action.setTag("backup-save");action.setEnabled(true);action.setOnClickListener(v->launchSave());
        Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(AppDataBackup.MIME_TYPE);
        picker.putExtra(Intent.EXTRA_TITLE,"volt-"+new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.ROOT).format(new Date())+AppDataBackup.FILE_EXTENSION);
        try{waitingForPicker=true;startActivityForResult(picker,SAVE_BACKUP);}catch(Exception missing){waitingForPicker=false;status.setText("A file picker could not open. Tap Save to try again.");}
    }

    // ---- Restore -----------------------------------------------------------------------------

    private void restoreForm(){
        reset("Restore");paragraph("Volt backups, ride backups or layout files.");paragraph("Permissions and Bluetooth pairing aren't included.");
        footer("Choose file","restore-choose",this::choose);
    }
    private void choose(){
        if(busy||waitingForPicker||terminal)return;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try{waitingForPicker=true;startActivityForResult(picker,PICK_BACKUP);}catch(Exception missing){waitingForPicker=false;status.setText("A file picker could not open.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);waitingForPicker=false;
        if(terminal||isDestroyed()||isFinishing())return;
        if(result!=RESULT_OK||data==null||data.getData()==null){status.setText("No file selected");return;}
        Uri uri=data.getData();
        if(request==PICK_BACKUP){picked=uri;setBusy("Checking the file…");
            submit(()->{int kind;
                try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();byte[] head=new byte[64];int n=0;for(int r;n<head.length&&(r=in.read(head,n,head.length-n))>0;)n+=r;kind=AppDataBackup.sniff(head,n);}
                catch(Exception failure){show(()->refuseFile("The file could not be opened."));return;}
                if(kind==AppDataBackup.UNSUPPORTED)show(()->refuseFile("Choose a Volt backup file."));
                else if(kind==AppDataBackup.ENCRYPTED)show(this::askPassword);
                else stageFile(null);});
        }else if(request==SAVE_BACKUP){setBusy("Saving the backup…");
            File ready=exported;
            submit(()->{try{saveExport(ready,getContentResolver().openOutputStream(uri,"wt"));}catch(Exception failure){show(()->retry("Save failed. Try another place."));}});
        }
    }
    /** On the worker. Providers can fail during close(): the prepared copy and Save stay until every resource closed. */
    private void saveExport(File ready,OutputStream destination){
        try(OutputStream out=destination;InputStream in=new FileInputStream(ready)){
            if(out==null)throw new IOException();byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);out.flush();
        }catch(Exception failure){show(()->retry("Save failed. Try another place."));return;}
        ready.delete();show(()->{exported=null;done("Backup saved.");});
    }
    /** A file refused before any password: a password step left from an earlier file would blame the password. */
    private void refuseFile(String message){if(password!=null)restoreForm();retry(message);}
    private void askPassword(){
        retry("");if(password==null){password=passwordField("Backup password");content.removeView(password);content.addView(password,content.indexOfChild(status));}
        password.requestFocus();label(action,"Open backup");action.setTag("restore-open");action.setOnClickListener(v->openEncrypted());
        // A wrong or damaged file must not strand the rider on it: another file can be chosen here.
        if(content.findViewWithTag("restore-choose-again")==null){TextView again=ui.secondaryButton("Choose another file",this::choose);again.setTag("restore-choose-again");again.setContentDescription("Choose another file");
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=ui.dp(12);content.addView(again,content.indexOfChild(closeAction),p);}
    }
    private void openEncrypted(){
        if(busy||terminal||picked==null)return;
        char[] secret=new char[password.length()];password.getText().getChars(0,password.length(),secret,0);
        if(secret.length<8){Arrays.fill(secret,'\0');password.setError("Use at least 8 characters.");return;}
        password.setText("");hideKeyboard();setBusy("Checking backup…");
        submit(()->stageFile(secret));
    }
    /** On the worker. */
    private void stageFile(char[] secret){
        try(InputStream in=getContentResolver().openInputStream(picked)){
            if(in==null)throw new IOException();
            AppDataBackup.PreparedRestore checked=AppDataBackup.stage(this,in,secret);boolean data=AppDataBackup.hasData(this);
            runOnUiThread(()->{if(isDestroyed()||isFinishing()){submit(()->discardQuietly(checked));return;}staged=checked;hasData=data;busy=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);restoreCategories();});
        }catch(Exception failure){show(()->{if(secret!=null)retry("Wrong password or bad file.");else refuseFile(message(failure));});}
        finally{if(secret!=null)Arrays.fill(secret,'\0');}
    }
    private void restoreCategories(){
        reset("Restore");
        String date=staged.createdAtMs>0?java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(new Date(staged.createdAtMs)):null;
        paragraph(staged.format.equals("layout")?"Layout file":(staged.format.equals("rides")?"Ride backup":"Backup")+(date==null?"":" from "+date));
        LinearLayout list=list();LinearLayout categories=new LinearLayout(this);categories.setOrientation(LinearLayout.VERTICAL);
        boolean preselected=preselect!=null&&staged.categories.containsKey(preselect);
        for(Map.Entry<String,Integer> category:staged.categories.entrySet()){
            String id=category.getKey();int count=category.getValue();String detail;
            if(count==0)detail="None";
            else if(id.equals("rides"))detail=BackupArchive.plural(count,"ride");
            else if(id.equals("charges"))detail=BackupArchive.plural(count,"charge");
            else if(id.equals("preferences"))detail=BackupArchive.plural(count,"setting");
            else detail=BackupArchive.plural(count,"scooter");
            boxes.put(id,checkRow(categories,id,AppDataBackup.label(id),detail,count>0,!preselected||preselect.equals(id)));
        }
        if(boxes.size()>1)selectAll(list,null);
        list.addView(categories);
        if(staged.complete){
            replaceAll=new Switch(this);replaceAll.setText("Replace everything on this phone");replaceAll.setTextColor(ui.text);replaceAll.setTextSize(TypedValue.COMPLEX_UNIT_SP,15*textScale);
            replaceAll.setMinHeight(ui.dp(48));ui.styleSwitch(replaceAll);replaceAll.setTag("restore-replace-all");
            replaceAll.setOnCheckedChangeListener((v,on)->{for(Map.Entry<String,CheckBox> e:boxes.entrySet()){boolean has=staged.categories.get(e.getKey())>0;if(on||!has)e.getValue().setChecked(on);e.getValue().setEnabled(!on&&has);}});
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=ui.dp(12);content.addView(replaceAll,sp);
            // A cleared or new phone gets an exact copy by default; a phone with data merges.
            replaceAll.setChecked(!hasData&&!preselected);
        }
        footer("Next","restore-next",this::next);
    }
    private boolean replacing(){return replaceAll!=null&&replaceAll.isChecked();}
    private void next(){
        if(busy||staged==null)return;
        Set<String> categories=new LinkedHashSet<>();
        if(replacing())categories.addAll(AppDataBackup.CATEGORIES);
        else for(Map.Entry<String,CheckBox> e:boxes.entrySet())if(e.getValue().isChecked())categories.add(e.getKey());
        if(categories.isEmpty()){status.setText("Choose what to restore.");return;}
        if(replacing()){review(categories,true,Collections.<String,String>emptyMap());return;}
        AppDataBackup.PreparedRestore current=staged;setBusy("Checking scooters…");
        submit(()->{try{List<AppDataBackup.Destination> needed=AppDataBackup.destinations(this,current,categories);Map<String,String> local=AppDataBackup.localScooters(this);
                show(()->{if(needed.isEmpty())review(categories,false,Collections.<String,String>emptyMap());else scooters(categories,needed,local);});}
            catch(Exception failure){show(()->retry(message(failure)));}});
    }
    private void scooters(Set<String> categories,List<AppDataBackup.Destination> needed,Map<String,String> local){
        busy=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        reset("Scooters");
        LinkedHashMap<String,Spinner> choices=new LinkedHashMap<>();LinkedHashMap<String,List<String>> targets=new LinkedHashMap<>();boolean onlySkip=true;
        for(AppDataBackup.Destination d:needed){
            paragraph(d.source.equals(BackupArchive.LAYOUT_SOURCE)?"Layout goes to:":"Data from “"+d.name+"” goes to:");
            List<String> ids=new ArrayList<>(),names=new ArrayList<>();
            for(Map.Entry<String,String> scooter:local.entrySet()){ids.add(scooter.getKey());names.add(scooter.getValue());}
            if(d.addable){ids.add(d.source);names.add("Add from backup");}
            ids.add(AppDataBackup.SKIP);names.add("Don't restore");
            onlySkip&=ids.size()==1;
            Spinner choice=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);choice.setAdapter(adapter);choice.setMinimumHeight(ui.dp(56));
            choice.setContentDescription("Destination for "+d.name);choice.setTag("restore-destination");
            choice.setSelection(d.suggested==null?ids.size()-1:Math.max(0,ids.indexOf(d.suggested)));
            // A description replaces the spoken value, so it carries the current choice too.
            final String source=d.name;
            choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                public void onItemSelected(AdapterView<?> parent,View view,int position,long id){parent.setContentDescription("Destination for "+source+": "+names.get(position));}
                public void onNothingSelected(AdapterView<?> parent){}});
            choice.setContentDescription("Destination for "+source+": "+names.get(choice.getSelectedItemPosition()));
            content.addView(choice,new LinearLayout.LayoutParams(-1,-2));choices.put(d.source,choice);targets.put(d.source,ids);
        }
        if(onlySkip)paragraph("Include Scooters or set up a scooter first.");
        footer("Review","restore-review",()->{
            Map<String,String> chosen=new LinkedHashMap<>();
            for(Map.Entry<String,Spinner> e:choices.entrySet())chosen.put(e.getKey(),targets.get(e.getKey()).get(e.getValue().getSelectedItemPosition()));
            review(categories,false,chosen);
        });
    }
    private void review(Set<String> categories,boolean replace,Map<String,String> chosen){review(categories,replace,chosen,null);}
    /** A non-null note means saved data changed after the last review: the new one replaces it, or the choices start over. */
    private void review(Set<String> categories,boolean replace,Map<String,String> chosen,String note){
        AppDataBackup.PreparedRestore current=staged;setBusy("Comparing with this phone…");
        submit(()->{try{AppDataBackup.Review checked=AppDataBackup.review(this,current,categories,replace,chosen);show(()->showReview(checked,()->review(categories,replace,chosen,CHANGED),note));}
            catch(Exception failure){show(()->{if(note!=null)restoreCategories();retry(message(failure));});}});
    }
    private void showReview(AppDataBackup.Review review,Runnable again,String note){
        busy=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        reset("Review");
        LinearLayout list=list();list.setTag("restore-preview");list.setPadding(ui.dp(14),ui.dp(6),ui.dp(14),ui.dp(6));
        for(String line:review.lines)paragraph(list,line).setTextColor(ui.text);
        paragraph("Volt closes when the restore is done.");
        footer(review.replace?"Replace everything":"Restore","restore-apply",()->applyRestore(review,again));
        if(note!=null)status.setText(note);
    }
    private void applyRestore(AppDataBackup.Review review,Runnable again){
        if(staged==null||busy)return;AppDataBackup.PreparedRestore approved=staged;setBusy("Restoring your data…");
        submit(()->{try{AppDataBackup.apply(this,review);show(()->{staged=null;done("Restored. Reopen Volt.");});}
            catch(Exception failure){
                String text=failure.getMessage();
                // Refused before any change: the reviewed file is still staged. A changed phone gets a fresh review, never the stale approval.
                if(CHANGED.equals(text)){show(again);return;}
                if(NO_SPACE.equals(text)){show(()->retry(text));return;}
                discardQuietly(approved);
                show(()->{staged=null;done("Restore failed. Reopen Volt. Don't clear storage.");});
            }});
    }

    // ---- Lifecycle ---------------------------------------------------------------------------

    private static String message(Exception failure){String text=failure.getMessage();return failure instanceof IOException&&text!=null&&text.length()<=80?text:"The file could not be read.";}
    private void hideKeyboard(){View focus=getCurrentFocus();if(focus!=null)((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);}
    private static void submit(Runnable work){OPERATIONS.incrementAndGet();WORKER.execute(()->{try{work.run();}finally{OPERATIONS.decrementAndGet();}});}
    private void show(Runnable update){runOnUiThread(()->{if(!isDestroyed()&&!isFinishing())update.run();});}
    private void discardQuietly(AppDataBackup.PreparedRestore value){try{AppDataBackup.discard(this,value);}catch(IOException ignored){/* Startup recovery removes abandoned staging; never remove a transaction here. */}}
    private void setBusy(String message){busy=true;status.setText(message);progress.setVisibility(View.VISIBLE);if(action!=null)action.setEnabled(false);if(closeAction!=null)closeAction.setEnabled(false);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void retry(String message){busy=false;status.setText(message);progress.setVisibility(View.GONE);if(action!=null)action.setEnabled(true);if(closeAction!=null)closeAction.setEnabled(true);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void done(String message){busy=false;terminal=true;status.setText(message);progress.setVisibility(View.GONE);if(action!=null)action.setVisibility(View.GONE);if(closeAction==null)closeAction=button("Close Volt",this::requestClose);closeAction.setEnabled(true);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void requestClose(){
        // Close Volt is disabled while busy; Back lands here too and simply waits.
        if(busy||OPERATIONS.get()>0)return;
        if(terminal){closeProcess();return;}
        confirmation=new AlertDialog.Builder(this).setTitle("Close Volt?").setMessage("Nothing has changed.")
                .setNegativeButton("Stay here",null).setPositiveButton("Close",(d,n)->closeProcess()).show();
    }
    private void closeProcess(){
        if(busy||OPERATIONS.get()>0)return;
        busy=true;
        if(exported!=null)exported.delete();
        AppDataBackup.PreparedRestore old=staged;staged=null;
        submit(()->{if(old!=null)discardQuietly(old);runOnUiThread(()->{finishAffinity();android.os.Process.killProcess(android.os.Process.myPid());});});
    }
    @Override public void onBackPressed(){requestClose();}
    @Override protected void onDestroy(){
        if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        handler.removeCallbacksAndMessages(null);if(confirmation!=null)confirmation.dismiss();
        AppDataBackup.PreparedRestore old=staged;staged=null;File ready=exported;exported=null;
        // Queue cleanup behind any write already using these files.
        submit(()->{if(old!=null)discardQuietly(old);if(ready!=null)ready.delete();});super.onDestroy();
    }
}
