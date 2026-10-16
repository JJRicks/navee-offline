package local.navee.offlinelock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Ride-only maintenance UI. A fresh process is required after transfer, even when cancelled. */
public final class RideBackupActivity extends Activity {
    private static final int PICK=421,SAVE=422;
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final AtomicInteger OPERATIONS=new AtomicInteger();
    private static boolean sessionStarted;
    private Ui ui;
    private LinearLayout content;
    private TextView status,action,close;
    private ProgressBar progress;
    private boolean busy,waiting,terminal,restore;
    private File exported;
    private RideArchive.Prepared prepared;
    private AlertDialog confirmation;
    private final LinkedHashMap<String,String> profiles=new LinkedHashMap<>(),mappings=new LinkedHashMap<>();
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private android.window.OnBackInvokedCallback backCallback;
    @Override public void onCreate(Bundle state) {
        android.content.SharedPreferences settings=getSharedPreferences("app_settings",MODE_PRIVATE);
        String theme=settings.getString("theme","dark");
        boolean dark=theme.equals("dark")||(theme.equals("system")&&(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark?R.style.VoltDark:R.style.VoltLight);super.onCreate(state);ui=new Ui(this,dark,settings.getFloat("text_scale",1f));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(ui.bg);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(ui.dp(24),ui.dp(24),ui.dp(24),ui.dp(24));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(i.left,i.top,i.right,i.bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        if(Build.VERSION.SDK_INT>=33){backCallback=this::requestClose;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}
        restore=getIntent().getBooleanExtra("restore",false);
        ArrayList<String> ids=getIntent().getStringArrayListExtra("profile_ids"),names=getIntent().getStringArrayListExtra("profile_names");
        if(ids!=null&&names!=null&&ids.size()==names.size())for(int i=0;i<ids.size();i++)profiles.put(ids.get(i),names.get(i));
        boolean previous=sessionStarted;sessionStarted=true;
        heading(restore?"Restore rides":"Back up rides");
        if(previous||state!=null||!DataMaintenance.active()) {
            DataMaintenance.enter();status=paragraph("Finishing the previous backup session…");progress=new ProgressBar(this);content.addView(progress);close=button("Close Volt",this::requestClose);terminal=true;
            handler.post(new Runnable(){public void run(){if(isDestroyed())return;if(OPERATIONS.get()>0){busy=true;close.setEnabled(false);handler.postDelayed(this,200);}else done("Close and reopen Volt to continue.");}});return;
        }
        paragraph(restore?"Add saved rides without replacing recordings already on this phone.":"Every saved ride, with its full scooter readings, route, phone sensors, name and notes.");
        paragraph("Ride files contain locations and are not encrypted. Pairing keys, app settings and charge recordings are not included.");
        status=paragraph("");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress=new ProgressBar(this);progress.setVisibility(View.GONE);content.addView(progress);
        action=button(restore?"Choose ride backup":"Create ride backup",restore?this::choose:this::export);
        action.setTag(restore?"ride-restore-choose":"ride-backup-create");close=button("Close Volt",this::requestClose);
        String error=getIntent().getStringExtra("preparation_error");if(error!=null)done(error);
    }
    private void heading(String title){content.addView(ui.text(title,28,true));}
    private TextView paragraph(String message){return paragraph(content,message);}
    private TextView paragraph(LinearLayout parent,String message){TextView view=ui.caption(message);view.setTextSize(15);view.setPadding(0,ui.dp(12),0,ui.dp(12));parent.addView(view);return view;}
    private TextView button(String label,Runnable callback){TextView view=ui.button(label,callback);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=ui.dp(12);content.addView(view,params);return view;}
    private static void submit(Runnable task){OPERATIONS.incrementAndGet();WORKER.execute(()->{try{task.run();}finally{OPERATIONS.decrementAndGet();}});}
    private void show(Runnable callback){runOnUiThread(()->{if(!isDestroyed()&&!isFinishing())callback.run();});}
    private void export(){
        if(busy||waiting||terminal)return;setBusy("Preparing saved rides…");
        submit(()->{File file=null;try{file=File.createTempFile("volt-rides-",".voltrides",getCacheDir());int count;
            try(OutputStream out=new FileOutputStream(file)){count=RideArchive.write(RideBackup.storage(this),out,profiles);}
            File ready=file;runOnUiThread(()->{if(isDestroyed()||isFinishing()){ready.delete();return;}exported=ready;retry(count+" saved rides ready");action.setText("Save ride backup");action.setOnClickListener(v->save());save();});
        }catch(Exception failure){if(file!=null)file.delete();show(()->retry(message(failure)));}});
    }
    private void choose(){if(busy||waiting||terminal)return;Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");launch(picker,PICK);}
    private void save(){if(busy||waiting||terminal)return;if(exported==null||!exported.isFile()){retry("Create the ride backup again.");return;}
        Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
        picker.putExtra(Intent.EXTRA_TITLE,"volt-rides-"+new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.ROOT).format(new Date())+".voltrides");launch(picker,SAVE);
    }
    private void launch(Intent picker,int request){try{waiting=true;startActivityForResult(picker,request);}catch(Exception missing){waiting=false;retry("A file picker could not open.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);waiting=false;
        if(terminal||isDestroyed()||isFinishing())return;
        if(result!=RESULT_OK||data==null||data.getData()==null){retry("No file selected");return;}Uri uri=data.getData();
        if(request==SAVE){File ready=exported;setBusy("Saving your ride backup…");submit(()->{try(InputStream in=new FileInputStream(ready);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
            if(out==null)throw new IOException("Cannot open the selected destination.");copy(in,out);out.flush();ready.delete();show(()->{exported=null;done("Ride backup saved.");});
        }catch(Exception failure){show(()->retry("The backup could not be saved. Try another destination."));}});}
        else if(request==PICK){setBusy("Checking your ride backup…");submit(()->{try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("The selected file could not be opened.");RideArchive.Prepared checked=RideArchive.stage(RideBackup.storage(this),in);
            runOnUiThread(()->{if(isDestroyed()||isFinishing()){submit(()->discard(checked));return;}prepared=checked;retry(checked.rides.size()+(checked.rides.size()==1?" saved ride found":" saved rides found"));selectScooters();});
        }catch(Exception failure){show(()->retry(message(failure)));}});}
    }
    private void selectScooters(){
        action.setVisibility(View.GONE);mappings.clear();boolean unknown=false;
        LinearLayout mappingBox=new LinearLayout(this);mappingBox.setOrientation(LinearLayout.VERTICAL);
        mappingBox.setTag("ride-restore-mappings");content.addView(mappingBox,content.indexOfChild(close));
        for(Map.Entry<String,String> source:prepared.profiles.entrySet()){
            if(profiles.containsKey(source.getKey())){mappings.put(source.getKey(),source.getKey());continue;}unknown=true;
            paragraph(mappingBox,"Restore rides from “"+source.getValue()+"” to:");
            ArrayList<String> ids=new ArrayList<>(profiles.keySet()),names=new ArrayList<>();names.add("Choose scooter");for(String id:ids)names.add(profiles.get(id));
            Spinner choice=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);choice.setAdapter(adapter);choice.setMinimumHeight(ui.dp(56));choice.setContentDescription("Destination scooter for "+source.getValue());mappingBox.addView(choice,new LinearLayout.LayoutParams(-1,-2));
            choice.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> parent){mappings.remove(source.getKey());}public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){if(position==0)mappings.remove(source.getKey());else mappings.put(source.getKey(),ids.get(position-1));}});
        }
        if(unknown)paragraph(mappingBox,"Choose the scooter each restored ride belongs to. Volt uses this association for ride-based estimates.");
        if(profiles.isEmpty()){done("Set up a scooter first, then restore these rides.");return;}
        TextView review=ui.button("Review restore",this::review);review.setTag("ride-restore-review");LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=ui.dp(12);content.addView(review,content.indexOfChild(close),rp);action=review;
    }
    private void review(){if(busy||prepared==null)return;if(mappings.size()!=prepared.profiles.size()){retry("Choose a destination scooter for each group of rides.");return;}
        Map<String,String> selected=new LinkedHashMap<>(mappings);setBusy("Comparing saved rides…");submit(()->{try{RideArchive.Plan plan=RideArchive.plan(RideBackup.storage(this),prepared,selected);show(()->{
            retry(plan.added+" new · "+plan.identical+" already saved · "+plan.conflicts+" conflicts kept here");
            StringBuilder explanation=new StringBuilder("New rides: ").append(plan.added).append("\nAlready saved: ").append(plan.identical).append("\nConflicts kept on this phone: ").append(plan.conflicts).append("\n\nNothing here is overwritten.");
            for(Map.Entry<String,String> source:prepared.profiles.entrySet())explanation.append("\n\n").append(source.getValue()).append(" → ").append(profiles.get(selected.get(source.getKey())));
            confirmation=new AlertDialog.Builder(this).setTitle("Restore saved rides?").setMessage(explanation.toString()).setNegativeButton("Cancel",null).setPositiveButton("Restore rides",(d,n)->apply(plan)).show();
        });}catch(Exception failure){show(()->retry(message(failure)));}});
    }
    private void apply(RideArchive.Plan plan){if(busy||prepared==null)return;setBusy("Restoring saved rides…");submit(()->{try{RideArchive.apply(RideBackup.storage(this),plan);show(()->{prepared=null;done(plan.added+" rides restored. Close and reopen Volt.");});}catch(Exception failure){show(()->done("Restore failed. Reopen Volt to recover; do not clear storage."));}});}
    private void setBusy(String message){busy=true;status.setText(message);progress.setVisibility(View.VISIBLE);action.setEnabled(false);close.setEnabled(false);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void retry(String message){busy=false;status.setText(message);progress.setVisibility(View.GONE);if(action!=null)action.setEnabled(true);if(close!=null)close.setEnabled(true);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void done(String message){retry(message);terminal=true;if(action!=null)action.setVisibility(View.GONE);}
    private static String message(Exception failure){return failure instanceof IOException&&failure.getMessage()!=null?failure.getMessage():"The ride backup could not be processed.";}
    private void discard(RideArchive.Prepared value){try{RideArchive.discard(RideBackup.storage(this),value);}catch(IOException ignored){/* Startup cleans abandoned staging. */}}
    private void requestClose(){if(busy||waiting||OPERATIONS.get()>0)return;if(!terminal){confirmation=new AlertDialog.Builder(this).setTitle("Close Volt?").setMessage("Your saved rides stay on this phone.").setNegativeButton("Stay here",null).setPositiveButton("Close",(d,n)->closeProcess()).show();}else closeProcess();}
    private void closeProcess(){if(busy||OPERATIONS.get()>0)return;busy=true;File file=exported;exported=null;RideArchive.Prepared old=prepared;prepared=null;submit(()->{if(file!=null)file.delete();if(old!=null)discard(old);runOnUiThread(()->{finishAffinity();android.os.Process.killProcess(android.os.Process.myPid());});});}
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);}
    @Override public void onBackPressed(){requestClose();}
    @Override protected void onDestroy(){if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);handler.removeCallbacksAndMessages(null);if(confirmation!=null)confirmation.dismiss();File file=exported;exported=null;RideArchive.Prepared old=prepared;prepared=null;submit(()->{if(file!=null)file.delete();if(old!=null)discard(old);});super.onDestroy();}
}
