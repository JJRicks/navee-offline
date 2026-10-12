package local.navee.offlinelock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanResult;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.json.JSONObject;

/** Native adaptive shell. The foreground service owns Bluetooth and recordings. */
public final class MainActivity extends Activity implements ScreenHost {
    private static final int BLUETOOTH_PERMISSION=101, NOTIFICATION_PERMISSION=102, ENABLE_BLUETOOTH=103,
            RIDE_LOCATION_PERMISSION=104, IMPORT_OWNER=201, SAVE_DOCUMENT=202;
    private static final long BODY_REFRESH_MS=1000;
    private static final java.util.Set<String> preparingExports=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final String[] pages={"home","controls","stats","rides","more"};
    private final String[] names={"Home","Scooter","Stats","Rides","More"};
    private final String[] icons={"home","scooter","stats","rides","settings"};
    private final LinkedHashMap<String,Integer> scrollPositions=new LinkedHashMap<>();
    private ScooterService scooterService;
    private SharedPreferences preferences,documents;
    private Ui design;
    private FoldMonitor fold;
    private FrameLayout root,pageFrame;
    private ScrollView scroll;
    private AppScreen screen;
    private TextView connectionTitle,connectionDetail,connectionAction,lockTitle,lockHint,profileTitle;
    private LockControl lockControl;
    private String selectedPage="home",scrollPage="home";
    private boolean bound,binding,resumed,destroyed,pendingConnect,pendingScan,expanded,rebuilding,applyingOwnerImport,importWaitNotified,bodyRefreshScheduled;
    private long lastBodyRefreshMs;
    private int topInset,bottomInset,leftInset,rightInset;
    private AlertDialog scanDialog, controlDialog;
    private Runnable pendingRideLocation;
    private boolean rideLocationResolved;
    private android.window.OnBackInvokedCallback systemBack;
    private ArrayAdapter<String> scanAdapter;
    private final ArrayList<ScanResult> scanResults=new ArrayList<>();
    private final Runnable serviceChanged=this::automaticRefresh;
    private final Runnable deferredBodyRefresh=()->{
        bodyRefreshScheduled=false;
        if(resumed&&!destroyed&&design!=null)refreshBodyNow();
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged=(p,key)->{
        if(key.equals("theme")||key.equals("units")||key.equals("text_scale"))handler.post(this::rebuild);
        else redraw();
    };
    private final ServiceConnection serviceConnection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            if(destroyed)return;
            scooterService=((ScooterService.LocalBinder)binder).service();bound=true;binding=false;
            scooterService.listen(serviceChanged);scooterService.visible(resumed);
            if(resumed){maybeApplyOwnerImport();if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}}
            rebuild();
        }
        @Override public void onServiceDisconnected(ComponentName name){
            scooterService=null;bound=false;binding=false;rebuild();
        }
    };
    @Override public void onCreate(Bundle saved){
        preferences=getSharedPreferences("app_settings",MODE_PRIVATE);
        setTheme(darkTheme()?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        if(Build.VERSION.SDK_INT>=33){systemBack=this::navigateBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,systemBack);}
        documents=getSharedPreferences("pending_documents",MODE_PRIVATE);
        String stagedExport=documents.getString("path",null);
        if(stagedExport!=null&&!documents.getBoolean("ready",false)&&!preparingExports.contains(stagedExport)){
            File orphan=new File(stagedExport);
            if(getFilesDir().equals(orphan.getParentFile())&&orphan.getName().startsWith("pending-export-"))orphan.delete();
            documents.edit().clear().commit();
            toast("An interrupted export was cleared. You can save it again.");
        }
        if(saved==null&&documents.getBoolean("ready",false)&&documents.getBoolean("picker",false))documents.edit().putBoolean("picker",false).apply();
        if(saved!=null){selectedPage=saved.getString("page","home");for(String page:pages)scrollPositions.put(page,saved.getInt("scroll_"+page,0));pendingConnect=saved.getBoolean("pending_connect",false);pendingScan=saved.getBoolean("pending_scan",false);}
        else selectedPage="home";
        if(!knownPage(selectedPage))selectedPage="home";
        fold=new FoldMonitor(this,()->{if(!destroyed)rebuild();});
        preferences.registerOnSharedPreferenceChangeListener(settingsChanged);
        rebuild();
    }
    @Override public void onResume(){
        super.onResume();resumed=true;ScooterService.setUiVisible(true);fold.start();
        if(hasBluetoothPermission()){ensureService();if(bound&&scooterService!=null)startService(new Intent(this,ScooterService.class));}
        if(scooterService!=null){maybeApplyOwnerImport();scooterService.visible(true);if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}if(scooterService.ble.connected())scooterService.ble.refresh(false);}
        handler.removeCallbacks(pulse);handler.post(pulse);
        finishRidePermission();
    }
    @Override public void onPause(){
        resumed=false;ScooterService.setUiVisible(false);captureScroll();fold.stop();handler.removeCallbacks(pulse);cancelDeferredBodyRefresh();
        if(scooterService!=null)scooterService.visible(false);
        super.onPause();
    }
    @Override public void onDestroy(){
        if(Build.VERSION.SDK_INT>=33&&systemBack!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBack);
        destroyed=true;pendingRideLocation=null;if(controlDialog!=null)controlDialog.dismiss();bodyRefreshScheduled=false;handler.removeCallbacksAndMessages(null);fold.stop();preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        if(screen!=null)screen.close();if(scanDialog!=null)scanDialog.dismiss();
        if(scooterService!=null)scooterService.unlisten(serviceChanged);
        if(bound||binding)try{unbindService(serviceConnection);}catch(IllegalArgumentException ignored){}
        scooterService=null;bound=binding=false;super.onDestroy();
    }
    @Override public void onSaveInstanceState(Bundle saved){
        captureScroll();saved.putString("page",selectedPage);saved.putBoolean("pending_connect",pendingConnect);saved.putBoolean("pending_scan",pendingScan);
        for(String page:pages)saved.putInt("scroll_"+page,scrollPositions.containsKey(page)?scrollPositions.get(page):0);
        super.onSaveInstanceState(saved);
    }
    @Override public void onBackPressed(){navigateBack();}
    private void navigateBack(){
        if(screen instanceof DashboardScreen&&((DashboardScreen)screen).back())return;
        if(screen instanceof ControlsScreen&&((ControlsScreen)screen).back())return;
        if(screen instanceof MoreScreen&&((MoreScreen)screen).back())return;
        if(!selectedPage.equals("home")){select("home");return;}
        finish();
    }
    @Override public void onConfigurationChanged(Configuration next){super.onConfigurationChanged(next);rebuild();}
    private final Runnable pulse=new Runnable(){@Override public void run(){
        if(!resumed||destroyed)return;maybeApplyOwnerImport();automaticRefresh();maybeLaunchSave();handler.postDelayed(this,1000);
    }};
    private boolean darkTheme(){String theme=preferences.getString("theme","dark");return theme.equals("dark")||(theme.equals("system")&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    private boolean knownPage(String value){for(String page:pages)if(page.equals(value))return true;return false;}
    private Context dialogContext(){return new ContextThemeWrapper(this,design.dark?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);}
    private void ensureService(){
        if(bound||binding||!hasBluetoothPermission()||destroyed)return;
        try{
            Intent intent=new Intent(this,ScooterService.class);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            binding=bindService(intent,serviceConnection,Context.BIND_AUTO_CREATE);
            if(!binding)toast("Could not open the local scooter service.");
        }catch(Exception e){binding=false;toast("The scooter service could not start. Reopen the app to try again.");}
    }
    @Override public Activity activity(){return this;}
    @Override public Ui ui(){return design;}
    @Override public ScooterService service(){return scooterService;}
    @Override public void toast(String text){if(text!=null&&!text.isEmpty())Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override public void redraw(){
        if(destroyed||design==null)return;
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(this::redraw);return;}
        cancelDeferredBodyRefresh();updateHeader();refreshBodyNow();
    }
    /** BLE packets update lock/pending state immediately; heavy screen refreshes share the 1 Hz pulse. */
    private void automaticRefresh(){
        if(destroyed||design==null)return;
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(this::automaticRefresh);return;}
        updateHeader();if(!resumed)return;
        long delay=Math.max(0,BODY_REFRESH_MS-(SystemClock.elapsedRealtime()-lastBodyRefreshMs));
        if(delay==0){cancelDeferredBodyRefresh();refreshBodyNow();}
        else if(!bodyRefreshScheduled){bodyRefreshScheduled=true;handler.postDelayed(deferredBodyRefresh,delay);}
    }
    private void cancelDeferredBodyRefresh(){handler.removeCallbacks(deferredBodyRefresh);bodyRefreshScheduled=false;}
    private void refreshBodyNow(){
        lastBodyRefreshMs=SystemClock.elapsedRealtime();if(screen!=null)screen.refresh();updateScan();
        if(resumed&&scooterService!=null&&scooterService.isRecording()&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!preferences.getBoolean("notification_prompted",false)){preferences.edit().putBoolean("notification_prompted",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);}
        boolean awake=resumed&&preferences.getBoolean("keep_screen_awake",true)&&scooterService!=null&&scooterService.isRecording();
        if(awake)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void rebuild(){
        if(destroyed||rebuilding)return;rebuilding=true;if(controlDialog!=null){controlDialog.dismiss();controlDialog=null;}
        captureScroll();if(screen!=null)screen.close();
        design=new Ui(this,darkTheme(),preferences.getFloat("text_scale",1f));
        setTheme(design.dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        expanded=getResources().getConfiguration().screenWidthDp>=650;
        root=new FrameLayout(this);root.setBackgroundColor(design.bg);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|(design.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        getWindow().setStatusBarColor(design.bg);getWindow().setNavigationBarColor(design.bg);
        root.setPadding(leftInset,topInset,rightInset,bottomInset);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int l,t,r,b;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());l=i.left;t=i.top;r=i.right;b=i.bottom;}
            else{l=insets.getSystemWindowInsetLeft();t=insets.getSystemWindowInsetTop();r=insets.getSystemWindowInsetRight();b=insets.getSystemWindowInsetBottom();}
            boolean changed=l!=leftInset||t!=topInset||r!=rightInset||b!=bottomInset;leftInset=l;topInset=t;rightInset=r;bottomInset=b;v.setPadding(l,t,r,b);
            if(changed&&horizontalHinge())handler.post(this::rebuild);return insets;
        });
        LinearLayout shell=column();root.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout appBody=expanded?row():column();shell.addView(appBody,new LinearLayout.LayoutParams(-1,0,1));
        if(expanded)appBody.addView(navigation(true),new LinearLayout.LayoutParams(design.dp(92),-1));
        LinearLayout content=column();appBody.addView(content,new LinearLayout.LayoutParams(expanded?0:-1,expanded?-1:0,1));
        LinearLayout header=makeHeader();
        if(horizontalHinge()){
            ScrollView upper=new ScrollView(this);upper.setFillViewport(false);upper.setClipToPadding(false);upper.addView(header);
            int upperHeight=Math.max(1,fold.hinge.top-topInset);
            content.addView(upper,new LinearLayout.LayoutParams(-1,upperHeight));
            View spacer=new View(this);spacer.setBackgroundColor(design.bg);content.addView(spacer,new LinearLayout.LayoutParams(-1,Math.max(design.dp(12),fold.hinge.height())));
        }else content.addView(header);
        pageFrame=new FrameLayout(this);content.addView(pageFrame,new LinearLayout.LayoutParams(-1,0,1));
        screen=selectedPage.equals("home")?new DashboardScreen(this,new DashboardScreen.Actions(){public View lock(){return makeDashboardLock();}public void clearLock(){clearDashboardLock();}public void navigate(String page){select(page);}public void rename(){renameScooter();}public void startRide(){select("rides");if(screen instanceof RidesScreen)((RidesScreen)screen).beginRecording();}}):selectedPage.equals("controls")?new ControlsScreen(false):scooterService==null?new LoadingScreen():selectedPage.equals("stats")?new StatsScreen(this):selectedPage.equals("rides")?new RidesScreen(this):new MoreScreen(this);
        View screenView=screen.view();if(screenView.getParent() instanceof ViewGroup)((ViewGroup)screenView.getParent()).removeView(screenView);
        if(screenView instanceof ScrollView){scroll=(ScrollView)screenView;}
        else{scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setPadding(design.dp(expanded?24:18),design.dp(6),design.dp(expanded?24:18),design.dp(16));scroll.addView(screenView,new ScrollView.LayoutParams(-1,-2));}
        scrollPage=selectedPage;pageFrame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        if(!expanded)shell.addView(navigation(false));
        setContentView(root);root.requestApplyInsets();
        // Samsung's Window getter can dereference a missing decor during onCreate.
        // Post through this root so styling runs only after it has been attached.
        if(Build.VERSION.SDK_INT>=30){
            FrameLayout currentRoot=root;boolean dark=design.dark;
            currentRoot.post(()->{
                if(destroyed||root!=currentRoot)return;
                WindowInsetsController controller=currentRoot.getWindowInsetsController();
                if(controller!=null){int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;controller.setSystemBarsAppearance(dark?0:mask,mask);}
            });
        }
        int restore=scrollPositions.containsKey(selectedPage)?scrollPositions.get(selectedPage):0;ScrollView target=scroll;target.post(()->{if(scroll==target)target.scrollTo(0,restore);});
        rebuilding=false;redraw();
    }
    private void clearDashboardLock(){lockTitle=null;lockControl=null;lockHint=null;connectionTitle=null;connectionDetail=null;connectionAction=null;}
    private LinearLayout makeHeader(){
        if(!selectedPage.equals("home"))return makeCompactHeader();
        clearDashboardLock();LinearLayout header=column();header.setPadding(design.dp(expanded?24:18),design.dp(12),design.dp(expanded?24:18),design.dp(8));
        LinearLayout brand=row();brand.setGravity(Gravity.CENTER_VERTICAL);LinearLayout title=column();title.addView(design.text("Navee Offline",24,true));profileTitle=design.text("Your scooter",14,false);profileTitle.setTextColor(design.muted);profileTitle.setPadding(0,design.dp(5),0,0);profileTitle.setOnClickListener(v->renameScooter());title.addView(profileTitle);brand.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        View more=design.icon("bluetooth",24,design.accent);more.setContentDescription("Choose a nearby scooter");more.setFocusable(true);more.setOnClickListener(v->showScanner(true));brand.addView(more,new LinearLayout.LayoutParams(design.dp(48),design.dp(48)));header.addView(brand);return header;
    }
    private View makeDashboardLock(){
        LinearLayout content=column();LinearLayout lockRow=row();lockRow.setGravity(Gravity.CENTER_VERTICAL);lockTitle=design.text("Waiting for scooter",24,true);lockRow.addView(lockTitle,new LinearLayout.LayoutParams(0,-2,1));
        lockControl=new LockControl();LinearLayout.LayoutParams switchParams=new LinearLayout.LayoutParams(design.dp(106),design.dp(68));switchParams.leftMargin=design.dp(8);lockRow.addView(lockControl,switchParams);content.addView(lockRow);
        lockHint=design.caption("Connect to see the confirmed lock state.");content.addView(lockHint);
        LinearLayout connection=row();connection.setGravity(Gravity.CENTER_VERTICAL);connectionTitle=design.text("Disconnected",13,true);connection.addView(connectionTitle,new LinearLayout.LayoutParams(0,-2,1));connectionAction=design.secondaryButton("Connect",this::connect);connection.addView(connectionAction,new LinearLayout.LayoutParams(-2,-2));content.addView(connection);connectionDetail=design.caption("");content.addView(connectionDetail);return content;
    }
    private void renameScooter(){
        if(scooterService==null||scooterService.profiles.active()==null){toast("Set up a scooter profile first.");return;}ScooterProfiles.Profile profile=scooterService.profiles.active();EditText input=new EditText(this);input.setSingleLine(true);input.setText(profile.name);input.setSelectAllOnFocus(true);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(48)});
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle("Scooter name").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();controlDialog=dialog;dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String name=input.getText().toString().trim();if(name.isEmpty()){input.setError("Enter a name.");return;}profile.name=name;scooterService.profiles.update(profile);dialog.dismiss();rebuild();});
    }
    private LinearLayout makeCompactHeader(){
        LinearLayout header=column();header.setPadding(design.dp(expanded?24:18),design.dp(8),design.dp(expanded?24:18),design.dp(4));
        LinearLayout toolbar=row();toolbar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words=column();String pageName="";for(int i=0;i<pages.length;i++)if(pages[i].equals(selectedPage))pageName=names[i];
        words.addView(design.text(pageName,22,true));profileTitle=design.caption("Your scooter");profileTitle.setPadding(0,design.dp(3),0,0);profileTitle.setMaxLines(1);profileTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(profileTitle);
        toolbar.addView(words,new LinearLayout.LayoutParams(0,-2,1));lockTitle=design.text("—",12,true);lockTitle.setGravity(Gravity.RIGHT);toolbar.addView(lockTitle);
        lockControl=new LockControl();LinearLayout.LayoutParams lockParams=new LinearLayout.LayoutParams(design.dp(80),design.dp(52));lockParams.leftMargin=design.dp(8);toolbar.addView(lockControl,lockParams);header.addView(toolbar);
        lockHint=design.caption("");lockHint.setVisibility(View.GONE);header.addView(lockHint);
        LinearLayout connection=row();connection.setGravity(Gravity.CENTER_VERTICAL);
        connectionTitle=design.caption("Bluetooth only");connectionTitle.setMaxLines(1);connectionTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);connection.addView(connectionTitle,new LinearLayout.LayoutParams(0,-2,1));
        connectionDetail=design.caption("");connectionDetail.setMaxLines(2);connectionDetail.setPadding(0,0,0,design.dp(4));
        connectionAction=design.secondaryButton("Connect",this::connect);connectionAction.setTextSize(12*design.textScale);connectionAction.setPadding(design.dp(12),design.dp(5),design.dp(12),design.dp(5));connectionAction.setMinHeight(design.dp(48));
        connection.addView(connectionAction,new LinearLayout.LayoutParams(-2,design.dp(48)));header.addView(connection);header.addView(connectionDetail);
        return header;
    }
    private View navigation(boolean rail){
        LinearLayout nav=rail?column():row();nav.setGravity(rail?Gravity.TOP|Gravity.CENTER_HORIZONTAL:Gravity.CENTER);nav.setBackgroundColor(design.surface);nav.setPadding(design.dp(rail?8:4),design.dp(rail?20:6),design.dp(rail?8:4),design.dp(rail?12:6));
        for(int i=0;i<pages.length;i++){final String page=pages[i];View item=design.nav(names[i],icons[i],selectedPage.equals(page),()->select(page));LinearLayout.LayoutParams p=rail?new LinearLayout.LayoutParams(-1,design.dp(78)):new LinearLayout.LayoutParams(0,-2,1);if(rail)p.bottomMargin=design.dp(8);nav.addView(item,p);}
        return nav;
    }
    private void select(String page){if(page.equals("lights")){preferences.edit().putString("scooter_section","Lights").apply();page="controls";if(page.equals(selectedPage)){rebuild();return;}}if(page.equals(selectedPage)){if(scroll!=null)scroll.smoothScrollTo(0,0);return;}captureScroll();selectedPage=page;preferences.edit().putString("last_page",page).apply();rebuild();}
    private void captureScroll(){if(scroll!=null)scrollPositions.put(scrollPage,scroll.getScrollY());}
    private boolean horizontalHinge(){return fold!=null&&fold.hinge.width()>fold.hinge.height()&&fold.hinge.top>0&&fold.hinge.width()>0;}
    private boolean verticalHinge(){return fold!=null&&fold.hinge.height()>fold.hinge.width()&&fold.hinge.left>0&&fold.hinge.height()>0;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private void heading(LinearLayout parent,String title,String detail){TextView h=design.text(title,22,true);h.setPadding(0,design.dp(12),0,design.dp(6));parent.addView(h);if(detail!=null){TextView d=design.caption(detail);d.setPadding(0,0,0,design.dp(16));parent.addView(d);}}
    private void updateHeader(){
        BleController ble=scooterService==null?null:scooterService.ble;Boolean locked=ble==null?null:ble.locked();String pending=ble==null?null:ble.pending();boolean ready=ble!=null&&ble.ready(),home=selectedPage.equals("home");Boolean shownLock=locked;if(shownLock==null&&scooterService!=null){Integer saved=scooterService.displayFeatures().get("lock");if(saved!=null)shownLock=saved!=0;}
        if(lockTitle!=null){lockTitle.setVisibility(home||shownLock!=null||pending!=null?View.VISIBLE:View.GONE);lockControl.setVisibility(home||shownLock!=null||ready?View.VISIBLE:View.GONE);lockTitle.setText(shownLock==null?"State unavailable":shownLock?"Locked":"Unlocked");lockTitle.setTextColor(!ready?design.muted:Boolean.TRUE.equals(shownLock)?design.orange:design.accent);boolean editing=screen instanceof DashboardScreen&&((DashboardScreen)screen).isEditing();lockControl.update(shownLock,ready&&pending==null&&!editing);lockHint.setVisibility(pending!=null||(home&&!ready)?View.VISIBLE:View.GONE);lockHint.setText(pending!=null?(pending.equals("lock")?"Waiting for the scooter to confirm…":"Applying a setting…"):shownLock!=null?"Last known lock state · connect to verify":"Connect for a fresh lock state.");}
        if(connectionTitle!=null){String state=ble==null?(hasBluetoothPermission()?"Opening local connection…":"Nearby devices permission needed"):ready?"Connected":ble.state()==BleController.State.WAITING?"Waiting for your scooter":ble.state()==BleController.State.SCANNING?"Finding nearby scooters":ble.state()==BleController.State.CONNECTING?"Connecting…":ble.state()==BleController.State.AUTHENTICATING?"Authenticating locally…":"Disconnected";connectionTitle.setText(state);connectionTitle.setTextColor(ready?design.accent:design.muted);String detail=ble==null?"":ble.message();connectionDetail.setText(detail);connectionDetail.setVisibility(!ready&&!detail.equals("Ready when your scooter is.")&&!detail.equals(state)?View.VISIBLE:View.GONE);boolean active=ble!=null&&(ble.connected()||ble.state()==BleController.State.CONNECTING||ble.state()==BleController.State.AUTHENTICATING);connectionAction.setText(active?"Disconnect":"Connect");connectionAction.setOnClickListener(v->{if(active){ble.disconnect();redraw();}else connect();});if(screen instanceof DashboardScreen&&((DashboardScreen)screen).isEditing())connectionAction.setEnabled(false);else connectionAction.setEnabled(true);}
        ScooterProfiles.Profile p=scooterService==null?null:scooterService.profiles.active();if(profileTitle!=null){profileTitle.setText(p==null?"Your scooter":p.name);profileTitle.setVisibility(home?View.VISIBLE:View.GONE);}
    }
    private final class LockControl extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private Boolean value;private boolean available,dragging;private float downX,downY,previewX;
        LockControl(){super(MainActivity.this);setClickable(true);setFocusable(true);setContentDescription("Electronic lock switch");setOnClickListener(v->apply(!Boolean.TRUE.equals(value)));}
        private void apply(boolean target){if(available&&value!=null&&scooterService!=null&&target!=value){scooterService.ble.set("lock",target?1:0);redraw();}}
        void update(Boolean locked,boolean enabled){value=locked;available=enabled;setEnabled(enabled);setContentDescription(locked==null?"Electronic lock state unavailable":"Electronic lock, "+(locked?"locked":"unlocked")+", tap to "+(locked?"unlock":"lock"));if(Build.VERSION.SDK_INT>=30)setStateDescription(locked==null?"Unavailable":locked?"Locked":"Unlocked");invalidate();}
        @Override public CharSequence getAccessibilityClassName(){return Switch.class.getName();}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setCheckable(value!=null);info.setChecked(Boolean.TRUE.equals(value));info.setEnabled(available);}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(!available)return false;
            if(event.getAction()==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();previewX=downX;dragging=false;setPressed(true);return true;}
            if(event.getAction()==MotionEvent.ACTION_MOVE){float delta=event.getX()-downX;if(Math.abs(delta)>ViewConfiguration.get(MainActivity.this).getScaledTouchSlop()&&Math.abs(delta)>Math.abs(event.getY()-downY)){dragging=true;getParent().requestDisallowInterceptTouchEvent(true);}if(dragging){previewX=event.getX();invalidate();}return true;}
            if(event.getAction()==MotionEvent.ACTION_UP){boolean dragged=dragging;boolean target=previewX>getWidth()/2f;dragging=false;setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);if(dragged)apply(target);else performClick();invalidate();return true;}
            if(event.getAction()==MotionEvent.ACTION_CANCEL){dragging=false;setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);invalidate();return true;}return true;
        }
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float x=design.dp(4),y=design.dp(8),w=getWidth()-design.dp(8),h=getHeight()-design.dp(16);int color=!available?design.outline:Boolean.TRUE.equals(value)?design.orange:design.accent;paint.setColor(Ui.withAlpha(color,available?80:180));paint.setStyle(Paint.Style.FILL);canvas.drawRoundRect(new RectF(x,y,x+w,y+h),h/2,h/2,paint);paint.setColor(available?color:design.muted);float radius=h/2-design.dp(5);float cx=dragging?Math.max(x+h/2,Math.min(x+w-h/2,previewX)):Boolean.TRUE.equals(value)?x+w-h/2:x+h/2;canvas.drawCircle(cx,y+h/2,radius,paint);paint.setColor(available?design.bg:design.surface);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(design.dp(2));canvas.drawRoundRect(new RectF(cx-design.dp(6),y+h/2-design.dp(1),cx+design.dp(6),y+h/2+design.dp(9)),design.dp(2),design.dp(2),paint);canvas.drawArc(new RectF(cx-design.dp(4),y+h/2-design.dp(9),cx+design.dp(4),y+h/2+design.dp(3)),Boolean.TRUE.equals(value)?180:210,Boolean.TRUE.equals(value)?180:140,false,paint);paint.setStyle(Paint.Style.FILL);}
    }
    @Override public void connect(){
        if(!resumed){pendingConnect=true;return;}
        if(!hasBluetoothPermission()){pendingConnect=true;requestBluetooth();return;}
        android.bluetooth.BluetoothManager manager=getSystemService(android.bluetooth.BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null){toast("This phone does not support Bluetooth.");return;}if(!adapter.isEnabled()){pendingConnect=true;startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BLUETOOTH);return;}
        if(scooterService==null){pendingConnect=true;ensureService();return;}
        ScooterProfiles.Profile profile=scooterService.profiles.active();if(profile==null||!profile.configured()){select("more");toast("Add your owner identity to set up a scooter.");return;}
        BleController.State state=scooterService.ble.state();if(state==BleController.State.CONNECTING||state==BleController.State.AUTHENTICATING||scooterService.ble.ready())return;
        scooterService.ble.connectLast();if(profile.mac.isEmpty())showScanner(false);redraw();
    }
    private boolean hasBluetoothPermission(){return Build.VERSION.SDK_INT>=31?checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED:checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    @Override public void requestBluetooth(){if(!resumed)return;if(hasBluetoothPermission()){ensureService();if(scooterService!=null){if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}}return;}requestPermissions(Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION},BLUETOOTH_PERMISSION);}
    @Override public void requestRideLocation(Runnable ready){
        if(destroyed||!resumed||ready==null)return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                ||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED){ready.run();return;}
        if(pendingRideLocation!=null)return;
        pendingRideLocation=ready;rideLocationResolved=false;
        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},RIDE_LOCATION_PERMISSION);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==RIDE_LOCATION_PERMISSION){
            // A denied optional GPS permission still permits a scooter-only recording.
            // Android can deliver this before onResume. Wait until visible before
            // promoting the recorder to a location foreground service.
            rideLocationResolved=true;finishRidePermission();
            return;
        }
        if(request==BLUETOOTH_PERMISSION){if(hasBluetoothPermission())requestBluetooth();else{pendingConnect=pendingScan=false;toast("Allow Nearby devices when you are ready to connect.");}redraw();}
    }
    private void finishRidePermission(){
        if(destroyed||!resumed||!rideLocationResolved||pendingRideLocation==null)return;
        Runnable ready=pendingRideLocation;pendingRideLocation=null;rideLocationResolved=false;ready.run();
    }

    private void showScanner(boolean start){
        if(!resumed){pendingScan=true;return;}
        if(!hasBluetoothPermission()){pendingScan=true;requestBluetooth();return;}if(scooterService==null){pendingScan=true;ensureService();return;}
        if(scooterService.isRecording()){toast("Stop the ride recording before choosing another scooter.");return;}
        if(scooterService.ble.pending()!=null){toast("Wait for the current scooter change before opening discovery.");return;}
        android.bluetooth.BluetoothManager manager=getSystemService(android.bluetooth.BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();if(adapter==null){toast("This phone does not support Bluetooth.");return;}if(!adapter.isEnabled()){pendingScan=true;startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BLUETOOTH);return;}
        if(scooterService.profiles.active()==null){select("more");toast("Set up an owner profile before choosing a scooter.");return;}
        if(start){scooterService.ble.disconnect();scooterService.ble.scan();}
        if(scanDialog!=null&&scanDialog.isShowing()){updateScan();return;}
        ListView list=new ListView(dialogContext());list.setPadding(design.dp(8),design.dp(8),design.dp(8),design.dp(8));
        scanAdapter=new ArrayAdapter<String>(dialogContext(),android.R.layout.simple_list_item_1,new ArrayList<String>()){@Override public boolean isEnabled(int position){return position<scanResults.size();}};list.setAdapter(scanAdapter);
        scanDialog=new AlertDialog.Builder(dialogContext()).setTitle("Choose your scooter").setView(list).setNegativeButton("Close",(d,w)->{}).setPositiveButton("Scan again",null).create();
        list.setOnItemClickListener((parent,v,position,id)->{if(position<scanResults.size()){scooterService.ble.connectDiscovered(scanResults.get(position));scanDialog.dismiss();redraw();}});
        scanDialog.setOnShowListener(d->scanDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{scooterService.ble.scan();updateScan();}));scanDialog.setOnDismissListener(d->{scanDialog=null;scanAdapter=null;if(scooterService!=null&&scooterService.ble.state()==BleController.State.SCANNING)scooterService.ble.disconnect();});scanDialog.show();updateScan();
    }
    private void updateScan(){
        if(scanAdapter==null||scooterService==null)return;scanResults.clear();scanResults.addAll(scooterService.ble.discoveries());scanAdapter.clear();
        for(ScanResult result:scanResults){String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();String mac=result.getDevice().getAddress();scanAdapter.add((name==null||name.isEmpty()?"Navee scooter":name)+"  ·  "+mac.substring(Math.max(0,mac.length()-5)));}
        if(scanResults.isEmpty())scanAdapter.add(scooterService.ble.state()==BleController.State.SCANNING?"Searching nearby…":"No scooter found. Turn it on and scan again.");scanAdapter.notifyDataSetChanged();
    }
    private Map<String,Telemetry.Metric> metrics(){LinkedHashMap<String,Telemetry.Metric> values=new LinkedHashMap<>();if(scooterService!=null)for(Telemetry.Metric metric:scooterService.ble.telemetry.metrics())values.put(metric.key,metric);return values;}
    private Double fresh(Map<String,Telemetry.Metric> metrics,String key,long age){Telemetry.Metric metric=metrics.get(key);return scooterService!=null&&scooterService.ble.connected()&&metric!=null&&metric.numericValue!=null&&metric.updatedMs>=0&&SystemClock.elapsedRealtime()-metric.updatedMs<=age?metric.numericValue:null;}
    private String number(Double value,int digits,String unit){return value==null?"—":String.format(Locale.US,"%."+digits+"f",value)+(unit.isEmpty()?"":" "+unit);}
    private String distance(Double km){if(km==null)return "—";String units=preferences.getString("units","mi");if(units.equals("km"))return number(km,1,"km");if(units.equals("dual"))return number(km/1.609344,1,"mi")+" / "+number(km,1,"km");return number(km/1.609344,1,"mi");}
    private String speed(Double kmh){return kmh==null?"—":DisplayUnits.speed(kmh,preferences.getString("units","mi"));}
    private String featureLabel(ScooterCommands.Feature feature,int value){return DisplayUnits.featureValue(feature,value,preferences.getString("units","mi"));}
    private TextView metricLine(LinearLayout parent,String label){LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);TextView labelView=design.caption(label);line.addView(labelView,new LinearLayout.LayoutParams(0,-2,1));TextView value=design.text("—",16,true);value.setGravity(Gravity.RIGHT);line.addView(value);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=design.dp(8);p.bottomMargin=design.dp(8);parent.addView(line,p);return value;}
    private final class LoadingScreen implements AppScreen {
        private final LinearLayout view=column();
        LoadingScreen(){heading(view,"Your offline workspace",hasBluetoothPermission()?"Preparing local profiles and measurements…":"Allow Nearby devices to set up the local connection.");LinearLayout card=design.card();card.addView(design.text("Everything stays on your phone",18,true));TextView detail=design.caption("Scooter controls, owner profiles and ride recordings work through the local Bluetooth connection.");detail.setPadding(0,design.dp(8),0,design.dp(12));card.addView(detail);card.addView(design.button(hasBluetoothPermission()?"Open local workspace":"Allow Nearby devices",()->{if(hasBluetoothPermission())ensureService();else requestBluetooth();}));view.addView(card);}
        @Override public View view(){return view;}
        @Override public void refresh(){}
    }
    /** One compact grouped list per category, including all lighting controls. */
    private final class ControlsScreen implements AppScreen {
        private final LinearLayout view=column(), menu=column();
        private final ArrayList<ControlRow> rows=new ArrayList<>();
        private final LinkedHashMap<String,LinearLayout> sections=new LinkedHashMap<>(), bodies=new LinkedHashMap<>();
        private final LinkedHashMap<View,String[]> extras=new LinkedHashMap<>();
        private final boolean lights;
        private final TextView results,back;
        private final EditText search;
        private String category,query="";
        private TextView clockButton;
        ControlsScreen(boolean lights){
            this.lights=lights;
            category=lights?"":preferences.getString("scooter_section","");
            String[] categories=lights?new String[]{"Headlights","Line lights","Star lights"}:new String[]{"Ride","Lights","Battery","Dashboard & sound","Security"};
            if(!java.util.Arrays.asList(categories).contains(category))category="";
            search=new EditText(MainActivity.this);search.setSingleLine(true);search.setHint(lights?"Search lights":"Search scooter settings");search.setTextSize(14*design.textScale);search.setTextColor(design.text);search.setHintTextColor(design.muted);search.setPadding(design.dp(14),design.dp(10),design.dp(14),design.dp(10));search.setMinimumHeight(design.dp(48));search.setBackground(design.shape(design.raised,16,0));search.setContentDescription(lights?"Search light settings":"Search scooter settings");view.addView(search,new LinearLayout.LayoutParams(-1,-2));
            back=design.secondaryButton("‹  All scooter settings",()->{category="";preferences.edit().putString("scooter_section","").apply();applyFilter();if(scroll!=null)scroll.scrollTo(0,0);});view.addView(back);
            results=design.caption("");results.setPadding(design.dp(3),design.dp(10),0,design.dp(10));view.addView(results);view.addView(menu);
            LinearLayout identity=design.card();identity.addView(design.settingRow("Scooter name",scooterService==null||scooterService.profiles.active()==null?"Not set":scooterService.profiles.active().name,this::renameFromSettings));menu.addView(identity);
            LinearLayout menuCard=design.card();menuCard.setPadding(design.dp(18),design.dp(3),design.dp(18),design.dp(3));menu.addView(menuCard);
            for(int i=0;i<categories.length;i++){
                String name=categories[i];if(i>0)design.divider(menuCard);
                String hint=name.equals("Lights")?"Tail, automatic, line & star":name.equals("Ride")?"Speed, recovery, assist":name.equals("Battery")?"Charging, range":name.equals("Security")?"Lock, PIN, proximity":"Units, sounds";
                menuCard.addView(design.settingRow(name,hint,()->{category=name;preferences.edit().putString("scooter_section",name).apply();search.setText("");search.clearFocus();android.view.inputmethod.InputMethodManager keyboard=getSystemService(android.view.inputmethod.InputMethodManager.class);if(keyboard!=null)keyboard.hideSoftInputFromWindow(search.getWindowToken(),0);applyFilter();if(scroll!=null)scroll.scrollTo(0,0);}));
                LinearLayout section=column();TextView heading=design.text(name,18,true);heading.setPadding(design.dp(3),design.dp(18),0,design.dp(10));section.addView(heading);
                LinearLayout body=design.card();body.setPadding(design.dp(18),0,design.dp(18),0);section.addView(body);sections.put(name,section);bodies.put(name,body);view.addView(section);
            }
            TextView lightHelp=design.caption("Front headlight: press the scooter’s power button for manual control. Automatic lights use ambient light; the separate switch below controls the tail light. A confirmed setting does not measure the lamp’s output.");lightHelp.setPadding(0,design.dp(12),0,design.dp(12));addRow("Lights",lightHelp);extras.put(lightHelp,new String[]{"Lights","headlight front manual automatic tail light power button"});
            ArrayList<ScooterCommands.Feature> ordered=new ArrayList<>();String[] priority={"maximum_speed","start_speed","energy_recovery","traction_control","cruise_control"};
            for(String key:priority)ordered.add(ScooterCommands.find(key));for(ScooterCommands.Feature f:ScooterCommands.catalog())if(!java.util.Arrays.asList(priority).contains(f.key))ordered.add(f);
            for(ScooterCommands.Feature f:ordered){
                if(f.experimental||f.key.equals("lock")||f.key.equals("pin")||f.key.equals("proximity_key"))continue;
                ControlRow control=new ControlRow(f);rows.add(control);addRow(controlCategory(f),control.card);
            }
            if(!lights){
                LinearLayout clock=design.settingRow("Sync scooter clock","",()->{if(scooterService!=null){scooterService.ble.syncClock();redraw();}});clockButton=(TextView)clock.getChildAt(0);addRow("Battery",clock);extras.put(clock,new String[]{"Battery","scooter clock scheduled charging time timezone"});
                LinearLayout security=design.settingRow("PIN & proximity unlock","",()->{preferences.edit().putString("more_section","Security").apply();select("more");});addRow("Security",security);extras.put(security,new String[]{"Security","pin password proximity unlock pairing bluetooth bond security"});
            }
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();applyFilter();}public void afterTextChanged(Editable text){}});
            applyFilter();
        }
        private void renameFromSettings(){renameScooter();}
        private boolean isLighting(ScooterCommands.Feature f){return f.key.equals("headlight")||f.key.equals("auto_sensor")||f.key.startsWith("line_")||f.key.startsWith("star_");}
        private String controlCategory(ScooterCommands.Feature f){if(!lights&&isLighting(f))return "Lights";if(f.key.startsWith("line_"))return "Line lights";if(f.key.startsWith("star_"))return "Star lights";if(isLighting(f))return "Headlights";return f.group.equals("Ride")?"Ride":f.group.equals("Battery")?"Battery":f.group.equals("Security")?"Security":"Dashboard & sound";}
        private void addRow(String category,View child){LinearLayout body=bodies.get(category);if(body.getChildCount()>0){View line=new View(MainActivity.this);line.setBackgroundColor(Ui.withAlpha(design.outline,125));line.setTag(child);body.addView(line,new LinearLayout.LayoutParams(-1,design.dp(1)));}body.addView(child);}
        private void applyFilter(){
            String needle=query.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+","");boolean searching=!needle.isEmpty();int count=0;
            java.util.HashSet<String> visible=new java.util.HashSet<>();
            for(ControlRow row:rows){String group=controlCategory(row.feature);boolean match=searching?(row.feature.label+" "+row.feature.description+" "+row.feature.key).toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+","").contains(needle):(lights||group.equals(category));row.card.setVisibility(match?View.VISIBLE:View.GONE);if(match){count++;visible.add(group);}}
            for(Map.Entry<View,String[]> e:extras.entrySet()){String[] metadata=e.getValue();boolean match=searching?metadata[1].replace(" ","").contains(needle):metadata[0].equals(category);e.getKey().setVisibility(match?View.VISIBLE:View.GONE);if(match){count++;visible.add(metadata[0]);}}
            for(Map.Entry<String,LinearLayout> e:sections.entrySet()){e.getValue().setVisibility(visible.contains(e.getKey())?View.VISIBLE:View.GONE);LinearLayout body=bodies.get(e.getKey());boolean hasVisible=false;for(int i=0;i<body.getChildCount();i++){View child=body.getChildAt(i);if(child.getTag() instanceof View)child.setVisibility(hasVisible&&((View)child.getTag()).getVisibility()==View.VISIBLE?View.VISIBLE:View.GONE);else if(child.getVisibility()==View.VISIBLE)hasVisible=true;}}
            menu.setVisibility(!lights&&!searching&&category.isEmpty()?View.VISIBLE:View.GONE);
            back.setVisibility(!lights&&!searching&&!category.isEmpty()?View.VISIBLE:View.GONE);
            results.setVisibility(searching?View.VISIBLE:View.GONE);results.setText(count==0?"No matching settings":count+" settings");
        }
        boolean back(){
            if(!query.isEmpty()){search.setText("");return true;}
            if(!lights&&!category.isEmpty()){category="";preferences.edit().putString("scooter_section","").apply();applyFilter();if(scroll!=null)scroll.scrollTo(0,0);return true;}
            return false;
        }
        @Override public View view(){return view;}
        @Override public void close(){if(controlDialog!=null){controlDialog.dismiss();controlDialog=null;}}
        @Override public void refresh(){for(ControlRow row:rows)row.refresh();if(clockButton!=null){boolean available=scooterService!=null&&scooterService.ble.ready()&&scooterService.ble.pending()==null;((View)clockButton.getParent()).setEnabled(available);clockButton.setTextColor(available?design.text:design.muted);}}
    }
    private final class ControlRow {
        final ScooterCommands.Feature feature;final LinearLayout card;final TextView value,name,result;final Switch toggle;final View action;
        ControlRow(ScooterCommands.Feature feature){
            this.feature=feature;card=column();card.setPadding(design.dp(2),0,design.dp(2),0);
            LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.setMinimumHeight(design.dp(66));line.setPadding(0,design.dp(9),0,design.dp(9));
            String label=feature.label;
            name=design.text(label,15,false);design.decorate(name,design.featureIcon(feature.key));line.addView(name,new LinearLayout.LayoutParams(0,-2,1));value=design.text("—",14,false);value.setTextColor(design.muted);value.setGravity(Gravity.END);value.setMaxWidth(design.dp(138));
            LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);vp.leftMargin=design.dp(12);line.addView(value,vp);
            if(feature.type==ScooterCommands.Type.TOGGLE){
                value.setVisibility(View.GONE);toggle=new Switch(MainActivity.this);toggle.setShowText(false);toggle.setTextOn("");toggle.setTextOff("");toggle.setMinHeight(design.dp(48));toggle.setMinimumWidth(design.dp(54));toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{design.accent,design.muted}));toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Ui.withAlpha(design.accent,80),design.outline}));toggle.setContentDescription(feature.label);toggle.setOnClickListener(v->{if(scooterService!=null){int target=toggle.isChecked()?1:0;Integer old=scooterService.ble.featureValue(feature.key);toggle.setChecked(old!=null&&old==1);scooterService.ble.set(feature.key,target);redraw();}});line.addView(toggle);line.setOnClickListener(v->{if(toggle.isEnabled())toggle.performClick();});
            }else{toggle=null;View chevron=design.icon("chevron",15,design.muted);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(design.dp(15),design.dp(15));cp.leftMargin=design.dp(10);line.addView(chevron,cp);line.setOnClickListener(v->editFeature(feature));}
            line.setFocusable(true);line.setContentDescription(feature.label+". "+feature.description);line.setOnLongClickListener(v->{controlDialog=new AlertDialog.Builder(dialogContext()).setTitle(feature.label).setMessage(feature.description).setPositiveButton("Close",null).create();controlDialog.show();return true;});card.addView(line);action=line;
            result=design.caption("");result.setPadding(0,0,0,design.dp(10));result.setVisibility(View.GONE);card.addView(result);
        }
        void refresh(){
            BleController ble=scooterService==null?null:scooterService.ble;Integer liveActual=ble==null?null:ble.featureValue(feature.key);Integer actual=liveActual!=null?liveActual:scooterService==null?null:scooterService.displayFeatures().get(feature.key);boolean available=ble!=null&&ble.ready()&&ble.pending()==null&&liveActual!=null&&scooterService.featureFresh(feature.key);
            if(feature.type==ScooterCommands.Type.TIME){Double seconds=ble==null?null:ble.telemetry.number("scheduled_charge_time_raw");if(seconds==null&&scooterService!=null){TelemetryHistory.Reading cached=scooterService.displayReadings().get("scheduled_charge_time_raw");if(cached!=null)seconds=cached.number;}available=ble!=null&&ble.ready()&&ble.pending()==null&&seconds!=null&&scooterService.readingFresh("scheduled_charge_time_raw");value.setText(seconds==null?"Not reported":seconds<=0?"Not scheduled":new SimpleDateFormat("EEE h:mm a",Locale.US).format(new java.util.Date((long)(seconds*1000)-TimeZone.getDefault().getOffset(System.currentTimeMillis()))));}
            else value.setText(actual==null?"Not reported":featureLabel(feature,actual));
            name.setTextColor(available?design.text:design.muted);action.setEnabled(available&&(feature.type!=ScooterCommands.Type.CHOICE||ble.featureChoices(feature.key).length>0));
            action.setContentDescription(feature.label+", "+value.getText()+". "+feature.description);
            if(toggle!=null){toggle.setEnabled(available);toggle.setChecked(actual!=null&&actual==1);if(Build.VERSION.SDK_INT>=30)toggle.setStateDescription(actual==null?"Not reported":actual==1?"On":"Off");}
            String feedback=ble==null?null:ble.commandResults().get(feature.key);if(feedback!=null&&(feedback.equals("Confirmed")||feedback.startsWith("Setting confirmed")))feedback=null;if(actual!=null&&!available&&(ble==null||!ble.ready()))feedback="Last known setting · connect to change";if(ble!=null&&feature.key.equals(ble.pending()))feedback="Waiting for scooter…";result.setVisibility(feedback==null?View.GONE:View.VISIBLE);result.setText(feedback==null?"":feedback);
        }
    }
    private void editFeature(ScooterCommands.Feature feature){
        if(feature.type==ScooterCommands.Type.TIME){showChargingTime();return;}
        if(feature.type!=ScooterCommands.Type.SLIDER){showChoice(feature);return;}
        if(scooterService==null||!scooterService.ble.ready()||scooterService.ble.pending()!=null)return;
        Integer current=scooterService.ble.featureValue(feature.key);if(current==null)return;
        LinearLayout body=column();body.setPadding(design.dp(24),design.dp(12),design.dp(24),design.dp(12));body.addView(design.caption(feature.description));
        TextView amount=design.text(featureLabel(feature,current),26,true);amount.setPadding(0,design.dp(20),0,design.dp(12));body.addView(amount);
        SeekBar slider=new SeekBar(this);slider.setMax((feature.max-feature.min)/feature.step);slider.setProgress(Math.max(0,(current-feature.min)/feature.step));slider.setProgressTintList(ColorStateList.valueOf(design.accent));slider.setThumbTintList(ColorStateList.valueOf(design.accent));slider.setContentDescription(feature.label);body.addView(slider,new LinearLayout.LayoutParams(-1,design.dp(48)));
        final int[] selected={current};slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int n,boolean user){if(user){selected[0]=feature.min+n*feature.step;amount.setText(featureLabel(feature,selected[0]));}}public void onStopTrackingTouch(SeekBar s){}});
        TextView error=design.caption("");error.setTextColor(design.danger);body.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(feature.label).setView(body).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();controlDialog=dialog;dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{Integer latest=scooterService.ble.featureValue(feature.key);if(!scooterService.ble.ready()||latest==null||!latest.equals(current)){error.setText("Scooter state changed. Close and reopen this setting.");return;}if(scooterService.ble.set(feature.key,selected[0]))dialog.dismiss();else error.setText(scooterService.ble.message());redraw();});
    }
    private void showChoice(ScooterCommands.Feature feature){
        if(scooterService==null||!scooterService.ble.ready()||scooterService.ble.pending()!=null)return;
        int[] choices=scooterService.ble.featureChoices(feature.key);if(choices.length==0){toast("This choice is not available on the current firmware.");return;}String[] labels=new String[choices.length];Integer actual=scooterService.ble.featureValue(feature.key);int current=-1;for(int i=0;i<choices.length;i++){labels[i]=featureLabel(feature,choices[i]);if(actual!=null&&actual==choices[i])current=i;}
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(feature.label).setSingleChoiceItems(labels,current,(d,which)->{if(scooterService.ble.set(feature.key,choices[which]))d.dismiss();else toast(scooterService.ble.message());redraw();}).setNegativeButton("Close",null).create();controlDialog=dialog;dialog.show();
    }
    private void showChargingTime(){
        if(scooterService==null||!scooterService.ble.ready())return;Double stored=scooterService.ble.telemetry.number("scheduled_charge_time_raw");Calendar initial=Calendar.getInstance();if(stored!=null&&stored>0)initial.setTimeInMillis((long)(stored*1000)-TimeZone.getDefault().getOffset(System.currentTimeMillis()));
        TimePickerDialog picker=new TimePickerDialog(dialogContext(),(view,hour,minute)->{Calendar chosen=Calendar.getInstance();chosen.set(Calendar.HOUR_OF_DAY,hour);chosen.set(Calendar.MINUTE,minute);chosen.set(Calendar.SECOND,0);chosen.set(Calendar.MILLISECOND,0);if(chosen.getTimeInMillis()<=System.currentTimeMillis())chosen.add(Calendar.DAY_OF_YEAR,1);long timestamp=chosen.getTimeInMillis();scooterService.ble.setChargeStart(timestamp/1000,TimeZone.getDefault().getOffset(System.currentTimeMillis())/1000);redraw();},initial.get(Calendar.HOUR_OF_DAY),initial.get(Calendar.MINUTE),android.text.format.DateFormat.is24HourFormat(this));picker.setTitle("Next charging start");controlDialog=picker;picker.show();
    }
    /** The gutter follows the actual fold bounds within this content pane. */
    private final class TwoPane extends LinearLayout {
        final View first,second,gutter;
        TwoPane(View left,View right){super(MainActivity.this);setOrientation(HORIZONTAL);first=left;second=right;gutter=new View(MainActivity.this);addView(first,new LinearLayout.LayoutParams(0,-2,1));addView(gutter,new LinearLayout.LayoutParams(design.dp(20),1));addView(second,new LinearLayout.LayoutParams(0,-2,1));setBaselineAligned(false);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(this::requestLayout);}
        @Override protected void onMeasure(int width,int height){
            int size=View.MeasureSpec.getSize(width);int gap=design.dp(20);int firstWidth=(size-gap)/2;
            if(verticalHinge()){int[] position=new int[2];getLocationInWindow(position);int relative=fold.hinge.left-position[0];int hingeGap=Math.max(design.dp(20),fold.hinge.width()+design.dp(16));int proposed=relative-design.dp(8);if(proposed>=design.dp(220)&&size-proposed-hingeGap>=design.dp(220)){firstWidth=proposed;gap=hingeGap;}}
            LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)first.getLayoutParams();p.width=Math.max(0,firstWidth);p.weight=0;LinearLayout.LayoutParams g=(LinearLayout.LayoutParams)gutter.getLayoutParams();g.width=gap;LinearLayout.LayoutParams s=(LinearLayout.LayoutParams)second.getLayoutParams();s.width=Math.max(0,size-firstWidth-gap);s.weight=0;super.onMeasure(width,height);
        }
    }
    @Override public void importOwner(){
        if(pendingOwnerFile().exists()){maybeApplyOwnerImport();if(pendingOwnerFile().exists())toast("Your selected owner setup is waiting for the current operation to finish.");return;}
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});startActivityForResult(intent,IMPORT_OWNER);
    }
    @Override public void saveText(String name,String mime,String content){prepareSave(name,mime,null,content==null?"":content);}
    @Override public void saveFile(String name,String mime,File content){if(content==null||!content.isFile()){toast("The export file is unavailable.");return;}prepareSave(name,mime,content,null);}
    private void prepareSave(String name,String mime,File source,String text){
        if(documents.contains("path")){toast("Finish the current save before starting another export.");return;}
        File staging=new File(getFilesDir(),"pending-export-"+System.currentTimeMillis());preparingExports.add(staging.getAbsolutePath());documents.edit().putString("path",staging.getAbsolutePath()).putString("name",name).putString("mime",mime).putBoolean("ready",false).putBoolean("picker",false).commit();
        new Thread(()->{try(OutputStream out=new FileOutputStream(staging)){if(source==null)out.write(text.getBytes(StandardCharsets.UTF_8));else try(InputStream in=new FileInputStream(source)){copy(in,out);}documents.edit().putBoolean("ready",true).commit();handler.post(this::maybeLaunchSave);}catch(Exception e){staging.delete();documents.edit().clear().commit();handler.post(()->toast("Could not prepare the export."));}finally{preparingExports.remove(staging.getAbsolutePath());}},"Navee-export").start();
    }
    private void maybeLaunchSave(){
        if(!resumed||destroyed||!documents.getBoolean("ready",false)||documents.getBoolean("picker",false))return;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(documents.getString("mime","application/octet-stream"));intent.putExtra(Intent.EXTRA_TITLE,documents.getString("name","navee-export"));documents.edit().putBoolean("picker",true).commit();
        try{startActivityForResult(intent,SAVE_DOCUMENT);}catch(Exception e){documents.edit().putBoolean("picker",false).apply();toast("A document picker could not open.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==ENABLE_BLUETOOTH){if(result==RESULT_OK){if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}}else pendingConnect=pendingScan=false;return;}
        if(request==SAVE_DOCUMENT){File staging=new File(documents.getString("path",new File(getFilesDir(),"missing-export").getAbsolutePath()));if(result!=RESULT_OK||data==null||data.getData()==null){staging.delete();documents.edit().clear().apply();return;}Uri destination=data.getData();new Thread(()->{try(InputStream in=new FileInputStream(staging);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new java.io.IOException();copy(in,out);handler.post(()->toast("Saved."));}catch(Exception e){handler.post(()->toast("The export could not be saved."));}finally{staging.delete();documents.edit().clear().commit();}},"Navee-save").start();return;}
        if(request==IMPORT_OWNER&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new java.io.IOException();stageOwnerImport(readLimited(in));maybeApplyOwnerImport();}catch(Exception e){toast(e instanceof IllegalArgumentException?e.getMessage():"The setup file could not be read.");}}
    }
    /** Only the normalized owner setup is staged, never raw Bluetooth capture logs. */
    private void stageOwnerImport(String text)throws Exception{
        OwnerIdentityParser.Result parsed=OwnerIdentityParser.parse(text);String name="Imported UT5 MAX",mac="",serial="";
        try{JSONObject json=new JSONObject(text);name=json.optString("name",name);mac=json.optString("scooter_address","");serial=json.optString("scooter_serial","");}catch(Exception ignored){}
        name=name.trim();if(name.isEmpty())name="Imported UT5 MAX";if(name.length()>80)name=name.substring(0,80);
        if(!mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))mac="";else mac=mac.toUpperCase(Locale.ROOT);if(!serial.matches("[A-Za-z][A-Za-z0-9]{16}"))serial="";
        JSONObject normalized=new JSONObject();normalized.put("format","navee-private-pending-owner-v1");normalized.put("import_id",UUID.randomUUID().toString());normalized.put("owner_identity",parsed.identity);normalized.put("role",parsed.role);normalized.put("name",name);normalized.put("scooter_address",mac);normalized.put("scooter_serial",serial);
        File temporary=new File(getFilesDir(),"pending-owner-import.tmp");try(FileOutputStream out=new FileOutputStream(temporary)){out.write(normalized.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!temporary.renameTo(pendingOwnerFile())){temporary.delete();throw new java.io.IOException("Could not stage owner setup");}importWaitNotified=false;
    }
    private File pendingOwnerFile(){return new File(getFilesDir(),"pending-owner-import.json");}
    private void maybeApplyOwnerImport(){
        File pending=pendingOwnerFile();if(destroyed||!resumed||applyingOwnerImport||!pending.isFile())return;
        if(scooterService==null){if(hasBluetoothPermission())ensureService();return;}
        if(scooterService.isRecording()||scooterService.ble.pending()!=null){if(!importWaitNotified){toast(scooterService.isRecording()?"Your owner setup is saved privately. Stop recording before importing it.":"Your owner setup is saved privately. Waiting for the current scooter change.");importWaitNotified=true;}return;}
        applyingOwnerImport=true;
        try(InputStream in=new FileInputStream(pending)){
            String text=readLimited(in);OwnerIdentityParser.Result parsed=OwnerIdentityParser.parse(text);JSONObject json=new JSONObject(text);String importId=json.getString("import_id");
            if(!"navee-private-pending-owner-v1".equals(json.optString("format"))||!importId.matches("[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("The pending setup is invalid. Import the original file again.");
            if(importId.equals(preferences.getString("owner_import_applied",""))){pending.delete();return;}
            String name=json.optString("name","Imported UT5 MAX"),mac=json.optString("scooter_address",""),serial=json.optString("scooter_serial","");
            // A picker return can trigger automatic reconnect. Cancel that session before
            // selecting a profile, after checking recording and pending-command state.
            scooterService.ble.disconnect();
            ScooterProfiles.Profile match=null;for(ScooterProfiles.Profile profile:scooterService.profiles.all())if(profile.identity.equalsIgnoreCase(parsed.identity)&&profile.role==parsed.role&&((!serial.isEmpty()&&serial.equals(profile.serial))||(!mac.isEmpty()&&mac.equalsIgnoreCase(profile.mac))||(profile.mac.isEmpty()&&profile.serial.isEmpty()))){match=profile;break;}
            if(match==null)match=scooterService.profiles.create(name,parsed.identity,parsed.role);else scooterService.profiles.select(match.id);
            if(!mac.isEmpty())match.mac=mac;if(!serial.isEmpty())match.serial=serial;scooterService.profiles.update(match);
            // Commit the existing profile snapshot before deleting its recovery staging file.
            SharedPreferences profilePrefs=getSharedPreferences("profiles",MODE_PRIVATE);String savedProfiles=profilePrefs.getString("items","[]");if(!profilePrefs.edit().putString("items",savedProfiles).commit())throw new java.io.IOException();
            if(!preferences.edit().putString("owner_import_applied",importId).commit())throw new java.io.IOException();pending.delete();importWaitNotified=false;
            selectedPage="home";scrollPositions.put("home",0);scrollPage="home";if(scroll!=null)scroll.scrollTo(0,0);rebuild();toast("Owner setup imported privately. Connecting locally…");scooterService.ble.connectLast();if(match.mac.isEmpty())showScanner(false);
        }catch(IllegalArgumentException e){pending.delete();toast(e.getMessage());}
        catch(Exception e){if(!importWaitNotified){toast("The private owner setup could not be applied. It is saved for retry.");importWaitNotified=true;}}
        finally{applyingOwnerImport=false;}
    }
    private static String readLimited(InputStream in)throws java.io.IOException{ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;while((count=in.read(chunk))!=-1){if(buffer.size()+count>1048576)throw new IllegalArgumentException("Setup files must be smaller than 1 MiB.");buffer.write(chunk,0,count);}return new String(buffer.toByteArray(),StandardCharsets.UTF_8);}
    private static void copy(InputStream in,OutputStream out)throws java.io.IOException{byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);}
}
