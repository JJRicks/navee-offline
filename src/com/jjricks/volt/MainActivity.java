package com.jjricks.volt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
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
import android.graphics.Rect;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
            RIDE_LOCATION_PERMISSION=104, IMPORT_OWNER=201, SAVE_DOCUMENT=202, IMPORT_LAYOUTS=203;
    private static final long BODY_REFRESH_MS=1000;
    private static final long MORE_RETENTION_MS=60_000;
    private static final String MORE_AWAY_AT="more_away_elapsed", MORE_AWAY_BOOT="more_away_boot";
    private static final java.util.Set<String> preparingExports=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ConnectionFeedbackState connectionFeedback=new ConnectionFeedbackState(SystemClock::elapsedRealtime);
    private final ConnectionHapticPlayer connectionHaptics=new ConnectionHapticPlayer();
    // Volt order: Scooter is the raised centre button of the floating tab bar.
    private final String[] pages={"home","stats","controls","rides","more"};
    private final String[] names={"Home","Live Stats","Scooter","Rides","More"};
    private LiveStatsTitle liveStatsTitle;
    private static final String STATS_AWAY_AT="stats_away_elapsed", STATS_AWAY_BOOT="stats_away_boot";
    private static final long STATS_RETENTION_MS=10000L;
    private final String[] icons={"home","stats","scooter","rides","more"};
    private static final int NAV_CENTER=2;
    private final LinkedHashMap<String,Integer> scrollPositions=new LinkedHashMap<>();
    private ScooterService scooterService;
    private SharedPreferences preferences,documents;
    private Ui design;
    private FoldMonitor fold;
    private FrameLayout root,pageFrame;
    private ScrollView scroll;
    private AppScreen screen;
    /** First-run setup; while it is showing there is no header or tab bar. */
    private OnboardingScreen onboarding;
    private TextView simulationNotice;
    private boolean displayedSimulation;
    private TextView connectionTitle,connectionAction,lockTitle,profileTitle,offlineTitle,offlineDetail;
    private View lockReady,lockOffline;
    private LockControl lockControl;
    private boolean showDashboardDisconnect=true,compactLock;
    /**
     * Every lock card on the current dashboard (normally one; the All widgets example and older
     * saved dashboards can hold the full lock and the lock bar together). The activity's lock
     * fields always point at the first card; updates load each card's views in turn.
     */
    private final java.util.ArrayList<DashboardLockViews> dashboardLocks=new java.util.ArrayList<>();
    private static final class DashboardLockViews{
        TextView connectionAction,lockTitle,offlineTitle,offlineDetail;View lockReady,lockOffline;LockControl lockControl;boolean showDisconnect,compact;
    }
    private DashboardLockViews currentLockViews(){
        DashboardLockViews v=new DashboardLockViews();
        v.connectionAction=connectionAction;v.lockTitle=lockTitle;v.offlineTitle=offlineTitle;v.offlineDetail=offlineDetail;
        v.lockReady=lockReady;v.lockOffline=lockOffline;v.lockControl=lockControl;v.showDisconnect=showDashboardDisconnect;v.compact=compactLock;return v;
    }
    private void loadLockViews(DashboardLockViews v){
        connectionAction=v.connectionAction;lockTitle=v.lockTitle;offlineTitle=v.offlineTitle;offlineDetail=v.offlineDetail;
        lockReady=v.lockReady;lockOffline=v.lockOffline;lockControl=v.lockControl;showDashboardDisconnect=v.showDisconnect;compactLock=v.compact;
    }
    /** Adds the card just built, points the fields back at the first card, then refreshes all of them. */
    private View registerDashboardLock(View content){
        dashboardLocks.add(currentLockViews());
        loadLockViews(dashboardLocks.get(0));
        updateHeader();return content;
    }
    private String selectedPage="home",scrollPage="home";
    private boolean bound,binding,resumed,destroyed,pendingConnect,pendingScan,expanded,rebuilding,applyingOwnerImport,importWaitNotified,bodyRefreshScheduled;
    private long lastBodyRefreshMs;
    private boolean startupRedirect, enteringMaintenance;
    private int topInset,bottomInset,leftInset,rightInset;
    private AlertDialog scanDialog, controlDialog;
    private AlertDialog quickMenuDialog;
    private MoreScreen quickMoreScreen;
    private final ArrayList<ControlRow> quickMenuRows = new ArrayList<>();
    private final ArrayList<ChargeDoneRow> quickChargeRows = new ArrayList<>();
    private Runnable pendingRideLocation;
    private boolean rideLocationResolved;
    private android.window.OnBackInvokedCallback systemBack;
    private LinearLayout scanList;
    private TextView scanStatus,scanConnect;
    private View scanSpinner;
    private String scanSignature="",scanSelected;
    private final ArrayList<ScanResult> scanResults=new ArrayList<>();
    private final Runnable serviceChanged=this::automaticRefresh;
    private final Runnable deferredBodyRefresh=()->{
        bodyRefreshScheduled=false;
        if(resumed&&!destroyed&&design!=null)refreshBodyNow();
    };
    private final Runnable settingsRebuild = () -> transitionRebuild(0);
    /** Tab order of the page being left, so the next rebuild can slide its nav dot from there. */
    private int navFrom = -1;
    private View pageHeader;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged=(p,key)->{
        if(key.equals("theme")||key.equals("units")||key.equals("temperature_unit")||key.equals("stats_density")) {
            handler.removeCallbacks(settingsRebuild);
            handler.postDelayed(settingsRebuild, Ui.motionEnabled() ? 210 : 0);
        } else if(key.equals("text_scale")) { handler.removeCallbacks(settingsRebuild); handler.post(settingsRebuild); }
        else redraw();
    };
    private final ServiceConnection serviceConnection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            if(destroyed || enteringMaintenance || startupRedirect)return;
            scooterService=((ScooterService.LocalBinder)binder).service();bound=true;binding=false;
            observeConnectionFeedback(true);
            scooterService.listen(serviceChanged);scooterService.visible(resumed);
            if(resumed){maybeApplyOwnerImport();if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}}
            rebuild();
        }
        @Override public void onServiceDisconnected(ComponentName name){
            scooterService=null;bound=false;binding=false;observeConnectionFeedback(true);rebuild();
        }
    };
    @Override public void onCreate(Bundle saved){
        if (DataMaintenance.active()) {
            startupRedirect = true; super.onCreate(saved);
            TextView preparing = new TextView(this); preparing.setText("Preparing your app data…");
            preparing.setGravity(Gravity.CENTER); setContentView(preparing);
            handler.post(new Runnable(){public void run(){
                if(isDestroyed()||isFinishing())return;
                Intent transfer=DataMaintenance.preparedTransfer();
                if(transfer==null){handler.postDelayed(this,100);return;}
                startActivity(transfer.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));finish();
            }});
            return;
        }
        if (!DataMaintenance.readyForStartup(this)) {
            startupRedirect = true; super.onCreate(saved);
            TextView opening = new TextView(this); opening.setText("Opening your saved data…");
            opening.setGravity(Gravity.CENTER); setContentView(opening);
            new Thread(() -> {
                try { DataMaintenance.ensureRecovered(this); runOnUiThread(() -> {if(!isDestroyed())recreate();}); }
                catch (java.io.IOException failure) { runOnUiThread(() -> {
                    if(isDestroyed())return;
                    startActivity(new Intent(this,BackupActivity.class).putExtra("recovery",true));finish();
                }); }
            },"Volt-data-recovery").start();
            return;
        }
        preferences=getSharedPreferences("app_settings",MODE_PRIVATE);
        setTheme(darkTheme()?R.style.VoltDark:R.style.VoltLight);
        super.onCreate(saved);
        if(Build.VERSION.SDK_INT>=33){systemBack=this::navigateBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,systemBack);}
        documents=getSharedPreferences("pending_documents",MODE_PRIVATE);
        String stagedExport=documents.getString("path",null);
        if(stagedExport!=null&&!documents.getBoolean("ready",false)&&!preparingExports.contains(stagedExport)){
            File orphan=new File(stagedExport);
            if(getFilesDir().equals(orphan.getParentFile())&&orphan.getName().startsWith("pending-export-"))orphan.delete();
            documents.edit().clear().commit();
            toast("Unfinished export cleared");
        }
        if(saved==null&&documents.getBoolean("ready",false)&&documents.getBoolean("picker",false))documents.edit().putBoolean("picker",false).apply();
        if(saved!=null){selectedPage=saved.getString("page","home");for(String page:pages)scrollPositions.put(page,saved.getInt("scroll_"+page,0));pendingConnect=saved.getBoolean("pending_connect",false);pendingScan=saved.getBoolean("pending_scan",false);}
        else selectedPage="home";
        if(!knownPage(selectedPage))selectedPage="home";
        expireMoreSection(saved!=null);
        expireStatsSection(saved!=null);
        fold=new FoldMonitor(this,()->{if(!destroyed)rebuild();});
        preferences.registerOnSharedPreferenceChangeListener(settingsChanged);
        rebuild();
    }
    @Override public void onResume(){
        super.onResume();if(startupRedirect || enteringMaintenance)return;resumed=true;ScooterService.setUiVisible(true);fold.start();
        observeConnectionFeedback(true);
        if(selectedPage.equals("more")){boolean expired=expireMoreSection(true);markMoreActive();if(expired)rebuild();}
        if(selectedPage.equals("stats")){boolean expired=expireStatsSection(true);markStatsActive();if(expired)rebuild();}
        if(hasBluetoothPermission()){ensureService();if(bound&&scooterService!=null)startService(new Intent(this,ScooterService.class));}
        if(scooterService!=null){maybeApplyOwnerImport();scooterService.visible(true);if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}if(scooterService.ble.connected())scooterService.ble.refresh(false);}
        handler.removeCallbacks(pulse);handler.post(pulse);
        finishRidePermission();
    }
    @Override public void onPause(){
        connectionHaptics.cancel();
        if(startupRedirect || enteringMaintenance){super.onPause();return;}
        if(selectedPage.equals("more"))markMoreAway();
        if(selectedPage.equals("stats"))markStatsAway();
        resumed=false;for(DashboardLockViews lock:dashboardLocks)if(lock.lockControl!=null)lock.lockControl.stopMotion();if(lockControl!=null)lockControl.stopMotion();ScooterService.setUiVisible(false);captureScroll();fold.stop();handler.removeCallbacks(pulse);cancelDeferredBodyRefresh();
        if(scooterService!=null)scooterService.visible(false);
        super.onPause();
    }
    @Override public void onDestroy(){
        connectionHaptics.cancel();
        if(startupRedirect){destroyed=true;super.onDestroy();return;}
        if(Build.VERSION.SDK_INT>=33&&systemBack!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBack);
        destroyed=true;closeQuickMenu();pendingRideLocation=null;if(controlDialog!=null)controlDialog.dismiss();if(reportChecking&&scooterService!=null)scooterService.ble.stopDiscovery();reportChecking=false;if(reportDialog!=null)reportDialog.dismiss();bodyRefreshScheduled=false;handler.removeCallbacksAndMessages(null);fold.stop();preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        if(screen!=null)screen.close();if(scanDialog!=null)scanDialog.dismiss();
        if(scooterService!=null)scooterService.unlisten(serviceChanged);
        if(bound||binding)try{unbindService(serviceConnection);}catch(IllegalArgumentException ignored){}
        scooterService=null;bound=binding=false;super.onDestroy();
    }
    @Override public void onSaveInstanceState(Bundle saved){
        if(startupRedirect || enteringMaintenance){super.onSaveInstanceState(saved);return;}
        captureScroll();saved.putString("page",selectedPage);saved.putBoolean("pending_connect",pendingConnect);saved.putBoolean("pending_scan",pendingScan);
        for(String page:pages)saved.putInt("scroll_"+page,scrollPositions.containsKey(page)?scrollPositions.get(page):0);
        super.onSaveInstanceState(saved);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && screen instanceof DashboardScreen)
            ((DashboardScreen) screen).onUserTouch();
        return super.dispatchTouchEvent(event);
    }
    @Override public void onBackPressed(){navigateBack();}
    private void navigateBack(){
        if(startupRedirect || enteringMaintenance)return;
        if(screen instanceof OnboardingScreen){if(!((OnboardingScreen)screen).back())finish();return;}
        if(screen instanceof DashboardScreen&&((DashboardScreen)screen).back())return;
        if(screen instanceof ControlsScreen&&((ControlsScreen)screen).back())return;
        if(screen instanceof MoreScreen&&((MoreScreen)screen).back())return;
        if(!selectedPage.equals("home")){select("home");return;}
        finish();
    }
    @Override public void onConfigurationChanged(Configuration next){super.onConfigurationChanged(next);rebuild();fitTallSheet();}
    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);
        // Returning to the app (or dismissing a dialog) is not a new scooter connection.
        observeConnectionFeedback(true);
    }
    private void observeConnectionFeedback(boolean baseline){
        if(Looper.myLooper()!=Looper.getMainLooper()){
            handler.post(()->observeConnectionFeedback(baseline));return;
        }
        BleController ble=scooterService==null||scooterService.simulating()?null:scooterService.ble;
        // READY means authentication, serial and first status succeeded. Unlike ready(),
        // it does not flicker when a telemetry reading becomes stale.
        boolean connected=ble!=null&&ble.state()==BleController.State.READY&&ble.connected();
        boolean focused=resumed&&!destroyed&&!startupRedirect&&!enteringMaintenance&&hasWindowFocus();
        if(baseline){connectionFeedback.baseline(ble,connected);connectionHaptics.cancel();return;}
        ConnectionFeedbackState.Event event=connectionFeedback.observe(ble,connected,focused);
        if(!focused||ble==null)connectionHaptics.cancel();
        else if(event!=ConnectionFeedbackState.Event.NONE)
            connectionHaptics.play(getWindow().getDecorView(),event==ConnectionFeedbackState.Event.CONNECTED);
    }
    private final Runnable pulse=new Runnable(){@Override public void run(){
        if(!resumed||destroyed||enteringMaintenance)return;maybeApplyOwnerImport();automaticRefresh();maybeLaunchSave();handler.postDelayed(this,1000);
    }};
    private boolean darkTheme(){String theme=preferences.getString("theme","dark");return theme.equals("dark")||(theme.equals("system")&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    private boolean knownPage(String value){for(String page:pages)if(page.equals(value))return true;return false;}
    private Context dialogContext(){return new ContextThemeWrapper(this,design.dark?R.style.VoltDialog:R.style.VoltDialogLight);}
    private void ensureService(){
        if(bound||binding||!hasBluetoothPermission()||destroyed||DataMaintenance.active()||enteringMaintenance)return;
        try{
            Intent intent=new Intent(this,ScooterService.class);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            binding=bindService(intent,serviceConnection,Context.BIND_AUTO_CREATE);
            if(!binding)toast("Service failed. Reopen Volt.");
        }catch(Exception e){binding=false;toast("Service failed. Reopen Volt.");}
    }
    @Override public Activity activity(){return this;}
    @Override public Ui ui(){return design;}
    @Override public ScooterService service(){return scooterService;}
    @Override public void toast(String text){if(text!=null&&!text.isEmpty())Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override public void redraw(){
        if(destroyed||design==null||enteringMaintenance||startupRedirect)return;
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(this::redraw);return;}
        cancelDeferredBodyRefresh();updateHeader();refreshBodyNow();
    }
    /** BLE packets update lock/pending state immediately; heavy screen refreshes share the 1 Hz pulse. */
    private void automaticRefresh(){
        if(destroyed||design==null)return;
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(this::automaticRefresh);return;}
        observeConnectionFeedback(false);maybeInviteReport();maybeOfferConnectionReport();
        if (displayedSimulation != (scooterService != null && scooterService.simulating())) {
            handler.removeCallbacks(settingsRebuild); handler.post(settingsRebuild); return;
        }
        updateHeader();if(!resumed)return;
        long delay=Math.max(0,BODY_REFRESH_MS-(SystemClock.elapsedRealtime()-lastBodyRefreshMs));
        if(delay==0){cancelDeferredBodyRefresh();refreshBodyNow();}
        else if(!bodyRefreshScheduled){bodyRefreshScheduled=true;handler.postDelayed(deferredBodyRefresh,delay);}
    }
    private void cancelDeferredBodyRefresh(){handler.removeCallbacks(deferredBodyRefresh);bodyRefreshScheduled=false;}
    private void refreshBodyNow(){
        lastBodyRefreshMs=SystemClock.elapsedRealtime();if(screen!=null)screen.refresh();updateScan();
        for (ControlRow row : quickMenuRows) row.refresh();
        for (ChargeDoneRow row : quickChargeRows) row.refresh();
        if (quickMoreScreen != null) quickMoreScreen.refresh();
        if (choiceSheet != null) choiceSheet.refresh();
        if (rangeSourceSheet != null) rangeSourceSheet.refresh();
        if(resumed&&scooterService!=null&&scooterService.isRecording()&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!preferences.getBoolean("notification_prompted",false)){preferences.edit().putBoolean("notification_prompted",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);}
        boolean awake=resumed&&preferences.getBoolean("keep_screen_awake",true)&&scooterService!=null&&scooterService.isRecording();
        if(awake)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    /** Rebuild under a crossfade from the current window; a direction (tab order) brings the new page in from that side. */
    private void transitionRebuild(int direction){
        FrameLayout before=root;
        android.graphics.Bitmap previous=!destroyed&&!rebuilding&&before!=null&&before.isAttachedToWindow()?Motion.capture(before):null;
        rebuild();
        if(previous!=null){if(root!=before)Motion.crossfade(root,previous,direction,pageHeader,scroll);else previous.recycle();}
    }
    @Override public void rebuild(){
        if(enteringMaintenance||startupRedirect||DataMaintenance.active())return;
        if(destroyed||rebuilding)return;handler.removeCallbacks(settingsRebuild);rebuilding=true;
        displayedSimulation = scooterService != null && scooterService.simulating();
        closeQuickMenu();
        if(controlDialog!=null){controlDialog.dismiss();controlDialog=null;}
        captureScroll();if(screen!=null)screen.close();
        design=new Ui(this,darkTheme(),preferences.getFloat("text_scale",1f));
        setTheme(design.dark?R.style.VoltDark:R.style.VoltLight);
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
        if(OnboardingScreen.needed(this,scooterService)){
            clearDashboardLock();profileTitle=null;scroll=null;pageFrame=null;pageHeader=null;
            onboarding=new OnboardingScreen(this,new OnboardingScreen.Listener(){public void onFinished(){onboarding=null;selectedPage="home";rebuild();}});
            screen=onboarding;root.addView(onboarding.view(),new FrameLayout.LayoutParams(-1,-1));
            Motion.quietScrollbars(root);setContentView(root);root.requestApplyInsets();styleSystemBars();
            rebuilding=false;redraw();return;
        }
        onboarding=null;
        LinearLayout shell=column();root.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        simulationNotice = design.text("Simulation · Exit", 12, true);
        simulationNotice.setTag("simulation-banner");
        simulationNotice.setTextColor(design.onAccent);
        simulationNotice.setBackgroundColor(design.accent);
        simulationNotice.setGravity(Gravity.CENTER);
        simulationNotice.setMinHeight(design.dp(48));
        simulationNotice.setPadding(design.dp(12), design.dp(6), design.dp(12), design.dp(6));
        simulationNotice.setContentDescription("Simulation, sample data. Tap to exit simulation.");
        simulationNotice.setOnClickListener(v -> {
            if (scooterService != null) { scooterService.setSimulation(false); transitionRebuild(0); }
        });
        simulationNotice.setVisibility(displayedSimulation ? View.VISIBLE : View.GONE);
        shell.addView(simulationNotice, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout appBody=expanded?row():column();shell.addView(appBody,new LinearLayout.LayoutParams(-1,0,1));
        if(expanded)appBody.addView(navigation(true),new LinearLayout.LayoutParams(design.dp(92),-1));
        LinearLayout content=column();appBody.addView(content,new LinearLayout.LayoutParams(expanded?0:-1,expanded?-1:0,1));
        LinearLayout header=makeHeader();
        if(horizontalHinge()){
            ScrollView upper=new ScrollView(this);upper.setFillViewport(false);upper.setClipToPadding(false);upper.addView(header);pageHeader=upper;
            int upperHeight=Math.max(1,fold.hinge.top-topInset);
            content.addView(upper,new LinearLayout.LayoutParams(-1,upperHeight));
            View spacer=new View(this);spacer.setBackgroundColor(design.bg);content.addView(spacer,new LinearLayout.LayoutParams(-1,Math.max(design.dp(12),fold.hinge.height())));
        }else{content.addView(header);pageHeader=header;}
        pageFrame=new FrameLayout(this);content.addView(pageFrame,new LinearLayout.LayoutParams(-1,0,1));
        screen=selectedPage.equals("home")?new DashboardScreen(this,new DashboardScreen.Actions(){public View lock(boolean showDisconnect){return makeDashboardLock(showDisconnect,false);}public View lock(boolean showDisconnect,boolean compact){return makeDashboardLock(showDisconnect,compact);}public void clearLock(){clearDashboardLock();}public void navigate(String page){select(page);}public void quickAction(String id){MainActivity.this.quickAction(id);}public void rename(){renameScooter();}public void connectionDetails(){showConnectionDetails();}public void startRide(){select("rides");if(screen instanceof RidesScreen)((RidesScreen)screen).beginRecording();}}):selectedPage.equals("controls")?new ControlsScreen(false):scooterService==null?new LoadingScreen():selectedPage.equals("stats")?new StatsScreen(this):selectedPage.equals("rides")?new RidesScreen(this):new MoreScreen(this);
        if(horizontalHinge()&&screen instanceof DashboardScreen){
            // Tabletop posture: Home's header sits in the upper pane, the widgets below the hinge.
            header.setPadding(design.dp(expanded?24:18),design.dp(8),design.dp(expanded?24:18),design.dp(8));
            header.addView(((DashboardScreen)screen).takeHeader(),new LinearLayout.LayoutParams(-1,-2));
        }
        View screenView=screen.view();if(screenView.getParent() instanceof ViewGroup)((ViewGroup)screenView.getParent()).removeView(screenView);
        if(screenView instanceof ScrollView){scroll=(ScrollView)screenView;}
        else{scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setPadding(design.dp(expanded?24:18),design.dp(6),design.dp(expanded?24:18),design.dp(16));scroll.addView(screenView,new ScrollView.LayoutParams(-1,-2));}
        scrollPage=selectedPage;pageFrame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        if(!expanded){
            // The tab bar floats over the page; pad the scroller so the last card can scroll clear of it.
            View bar=design.floatingNav(names,icons,java.util.Arrays.asList(pages).indexOf(selectedPage),navFrom,NAV_CENTER,i->select(pages[i]));
            FrameLayout.LayoutParams barParams=new FrameLayout.LayoutParams(-1,design.floatingNavHeight(),Gravity.BOTTOM);
            barParams.leftMargin=design.dp(14);barParams.rightMargin=design.dp(14);barParams.bottomMargin=design.dp(10);
            pageFrame.addView(bar,barParams);
            scroll.setClipToPadding(false);
            scroll.setPadding(scroll.getPaddingLeft(),scroll.getPaddingTop(),scroll.getPaddingRight(),scroll.getPaddingBottom()+design.floatingNavHeight()+design.dp(16));
        }
        Motion.quietScrollbars(root);setContentView(root);root.requestApplyInsets();
        styleSystemBars();
        int restore=scrollPositions.containsKey(selectedPage)?scrollPositions.get(selectedPage):0;Motion.scrollBeforeDraw(scroll,restore);
        rebuilding=false;redraw();
    }
    private void styleSystemBars(){
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
    }
    private void clearDashboardLock(){for(DashboardLockViews lock:dashboardLocks)if(lock.lockControl!=null)lock.lockControl.stopMotion();dashboardLocks.clear();if(lockControl!=null)lockControl.stopMotion();lockTitle=null;lockControl=null;connectionTitle=null;connectionAction=null;offlineTitle=null;offlineDetail=null;lockReady=null;lockOffline=null;compactLock=false;}
    private LinearLayout makeHeader(){
        if(!selectedPage.equals("home"))return makeCompactHeader();
        // Home's header (status line, scooter name and edit pencil) belongs to the dashboard toolbar.
        clearDashboardLock();profileTitle=null;return column();
    }
    /** Volt lock card: a full-width bar you tap anywhere on, or slide, to lock and unlock. */
    private View makeDashboardLock(boolean showDisconnect,boolean compact){
        if(compact)return makeCompactLock(showDisconnect);
        showDashboardDisconnect=showDisconnect;compactLock=false;
        LinearLayout content=column();
        lockReady=column();
        lockTitle=design.text("Lock",15,true);
        // Keep the state on one line; the slider and lock glyph already identify the control.
        lockTitle.setMaxLines(1);lockTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lockTitle.setAutoSizeTextTypeUniformWithConfiguration(Math.round(design.sp(12)),Math.round(design.sp(15)),1,android.util.TypedValue.COMPLEX_UNIT_PX);
        LinearLayout head=new LockHeading(lockTitle);
        ((LinearLayout)lockReady).addView(head);
        lockControl=new LockControl();
        LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,design.dp(58));bar.topMargin=design.dp(10);
        ((LinearLayout)lockReady).addView(lockControl,bar);
        content.addView(lockReady,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout offline=column();lockOffline=offline;
        View square=design.iconSquare("lock",design.dim);
        LinearLayout words=column();
        offlineTitle=design.text("Lock state unverified",14,true);words.addView(offlineTitle);
        offlineDetail=design.caption("");offlineDetail.setPadding(0,design.dp(2),0,0);offlineDetail.setVisibility(View.GONE);words.addView(offlineDetail);
        LinearLayout line=new LockOfflineStatus(square,words,offlineTitle,design.icon("chevron",15,design.muted));
        line.setMinimumHeight(design.dp(48));
        line.setOnClickListener(v->showConnectionDetails());
        offline.addView(line);
        content.addView(offline,new LinearLayout.LayoutParams(-1,-2));
        // Connect/Cancel are always available; Disconnect is a per-widget choice.
        connectionAction=design.button("Connect",this::connect);
        connectionAction.setMaxLines(1);connectionAction.setEllipsize(android.text.TextUtils.TruncateAt.END);
        connectionAction.setPadding(design.dp(6),connectionAction.getPaddingTop(),design.dp(6),connectionAction.getPaddingBottom());
        connectionAction.setAutoSizeTextTypeUniformWithConfiguration(Math.round(design.sp(12)),Math.round(design.sp(14)),1,android.util.TypedValue.COMPLEX_UNIT_PX);
        content.addView(connectionAction);
        return registerDashboardLock(content);
    }
    /**
     * Lock bar: one 48 dp row. Offline it shows the last known state with Connect (or Cancel);
     * connected, the same tap-or-slide switch fills the row with its state drawn inside.
     */
    private View makeCompactLock(boolean showDisconnect){
        showDashboardDisconnect=showDisconnect;compactLock=true;
        // The switch draws Locked/Unlocked itself; this detached label only keeps updateHeader uniform.
        lockTitle=design.text("Lock",15,true);
        lockControl=new LockControl();lockControl.compact=true;lockReady=lockControl;
        View square=design.iconSquare("lock",design.dim);
        LinearLayout words=column();
        offlineTitle=design.text("Lock state unknown",16,true);offlineTitle.setTypeface(design.semibold());
        offlineDetail=design.text("",13,false);offlineDetail.setTextColor(design.muted);offlineDetail.setVisibility(View.GONE);
        for(TextView line:new TextView[]{offlineTitle,offlineDetail}){
            // One line each keeps the pair inside a 48 dp row; Saira's tall metrics need no extra spacing.
            line.setLineSpacing(0,1);line.setMaxLines(1);line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(line,new LinearLayout.LayoutParams(-1,-2));
        }
        LinearLayout offline=new CompactLockStatus(square,words,offlineTitle);lockOffline=offline;
        offline.setOnClickListener(v->showConnectionDetails());
        connectionAction=design.button("Connect",this::connect);
        connectionAction.setMaxLines(1);
        connectionAction.setPadding(design.dp(16),0,design.dp(16),0);
        connectionAction.setMinWidth(design.dp(48));
        setConnectionActionBackground(connectionAction.getBackground());
        LinearLayout content=new CompactLockRow(offline,offlineTitle,connectionAction);
        // The row fills its frameless cell: connected, the slider is the whole card; offline,
        // the row draws its own card. Every control keeps the full cell height as its touch target.
        content.addView(offline,new LinearLayout.LayoutParams(0,-2,1));
        content.addView(lockControl,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(-2,-1);action.setMarginStart(design.dp(10));
        content.addView(connectionAction,action);
        return registerDashboardLock(content);
    }
    /**
     * Lock bar row. When the status and the worded action do not both fit, the action becomes a
     * 48 dp icon button (its text and description stay for accessibility); after that the title
     * ellipsizes. The status drops its own icon square first, so that one goes before the words.
     */
    private final class CompactLockRow extends LinearLayout {
        private final View status;private final TextView title,action;
        private final android.text.method.TransformationMethod words;
        private final android.graphics.drawable.Drawable glyph;
        private boolean iconOnly;
        private final Paint card=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds=new RectF();
        CompactLockRow(View status,TextView title,TextView action){
            super(MainActivity.this);this.status=status;this.title=title;this.action=action;
            setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(design.dp(48));
            words=action.getTransformationMethod();
            glyph=new android.graphics.drawable.Drawable(){
                @Override public void draw(Canvas canvas){
                    android.graphics.Rect b=getBounds();
                    // Cancel and Disconnect close the link; Connect and Allow Nearby devices start it.
                    String key="Cancel".contentEquals(action.getText())||"Disconnect".contentEquals(action.getText())?"close":"bluetooth";
                    design.drawIcon(canvas,key,action.getCurrentTextColor(),b.exactCenterX(),b.exactCenterY(),b.width());
                }
                @Override public void setAlpha(int alpha){}
                @Override public void setColorFilter(android.graphics.ColorFilter filter){}
                @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
            };
            glyph.setBounds(0,0,design.dp(20),design.dp(20));
        }
        /** Offline or connecting, the row is its own surface card; connected, the slider is. */
        @Override protected void dispatchDraw(Canvas canvas){
            if(status.getVisibility()!=GONE){
                card.setColor(design.surface);bounds.set(0,0,getWidth(),getHeight());
                float r=Math.min(design.dp(20),getHeight()/2f);canvas.drawRoundRect(bounds,r,r,card);
            }
            super.dispatchDraw(canvas);
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            // Offline content sits the same inset from the sides as the 40 dp square and pill from top and bottom.
            int side=status.getVisibility()!=GONE?design.dp(COMPACT_LOCK_INSET):0;
            if(getPaddingLeft()!=side||getPaddingRight()!=side)setPadding(side,0,side,0);
            int width=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();
            boolean icon=false;
            if(MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED&&action.getVisibility()!=GONE){
                // Natural worded width from the text itself: toggling and re-measuring would request layout every pass.
                float worded=Math.max(design.dp(48),action.getPaint().measureText(action.getText().toString())+design.dp(32));
                float need=status.getVisibility()!=GONE?title.getPaint().measureText(title.getText().toString()):design.dp(96);
                icon=width-worded-design.dp(10)<need;
            }
            if(icon!=iconOnly){
                iconOnly=icon;
                action.setTransformationMethod(icon?new android.text.method.TransformationMethod(){
                    @Override public CharSequence getTransformation(CharSequence source,View view){return "";}
                    @Override public void onFocusChanged(View view,CharSequence source,boolean focused,int direction,android.graphics.Rect previous){}
                }:words);
                action.setPadding(design.dp(icon?14:16),action.getPaddingTop(),design.dp(icon?14:16),action.getPaddingBottom());
                action.setCompoundDrawablesRelative(icon?glyph:null,null,null,null);
                action.setCompoundDrawablePadding(0);
                LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)action.getLayoutParams();
                p.setMarginStart(design.dp(icon?4:10));p.resolveLayoutDirection(getLayoutDirection());
                if(!icon)action.setTooltipText(null);
            }
            // The icon names its action on long press; the description follows Connect/Cancel/Disconnect.
            if(icon&&!android.text.TextUtils.equals(action.getTooltipText(),action.getContentDescription()))
                action.setTooltipText(action.getContentDescription());
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    /** Lock bar inset (dp) between its cell edge and the visible offline content or button pill. */
    private static final int COMPACT_LOCK_INSET=7;
    /** A Lock bar button's pill, inset top and bottom inside its full-height touch target. */
    private android.graphics.drawable.Drawable compactPill(android.graphics.drawable.Drawable fill){
        return new android.graphics.drawable.InsetDrawable(fill,0,design.dp(COMPACT_LOCK_INSET),0,design.dp(COMPACT_LOCK_INSET));
    }
    private void setConnectionActionBackground(android.graphics.drawable.Drawable fill){
        int left=connectionAction.getPaddingLeft(),right=connectionAction.getPaddingRight();
        connectionAction.setBackground(compactLock?compactPill(fill):fill);
        // InsetDrawable supplies vertical padding but also resets both horizontal sides to zero.
        if(compactLock)connectionAction.setPadding(left,connectionAction.getPaddingTop(),right,connectionAction.getPaddingBottom());
    }
    /** Offline lock bar status: drops its icon square before it would cut the state short. */
    private final class CompactLockStatus extends LinearLayout {
        private final View symbol;private final TextView title,detail;
        CompactLockStatus(View symbol,LinearLayout words,TextView title){
            super(MainActivity.this);this.symbol=symbol;this.title=title;this.detail=(TextView)words.getChildAt(1);
            setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(design.dp(48));
            setClickable(true);setFocusable(true);
            setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,30)),null,design.shape(android.graphics.Color.WHITE,12,0)));
            addView(symbol,new LinearLayout.LayoutParams(design.dp(40),design.dp(40)));
            LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(0,-2,1);space.setMarginStart(design.dp(12));
            addView(words,space);
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec);
            // Both lines decide: the square goes before either the state or its time is cut short.
            float needed=Math.max(title.getPaint().measureText(title.getText().toString()),
                    detail.getPaint().measureText(detail.getText().toString()));
            boolean room=MeasureSpec.getMode(widthSpec)==MeasureSpec.UNSPECIFIED||width-design.dp(52)>=needed;
            symbol.setVisibility(room?VISIBLE:GONE);
            LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)getChildAt(1).getLayoutParams();
            int start=room?design.dp(12):design.dp(2);
            if(p.getMarginStart()!=start){p.setMarginStart(start);p.resolveLayoutDirection(getLayoutDirection());}
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    /** The lock state heading; its glyph gives way before the state word on a very narrow card. */
    private final class LockHeading extends LinearLayout {
        private final TextView state;
        LockHeading(TextView state){
            super(MainActivity.this);this.state=state;
            setGravity(Gravity.CENTER_VERTICAL);
            addView(state,new LinearLayout.LayoutParams(-1,-2));
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();
            // On a narrow five-column card the slider already identifies the lock.
            // Keep the state readable before spending width on a second lock glyph.
            android.graphics.drawable.Drawable glyph=state.getCompoundDrawablesRelative()[0];
            if(glyph!=null){
                int size=width<design.dp(112)?0:design.dp(20);
                if(glyph.getBounds().width()!=size){glyph.setBounds(0,0,size,size);state.setCompoundDrawablesRelative(glyph,null,null,null);}
                state.setCompoundDrawablePadding(size==0?0:design.dp(8));
            }
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    /** Let narrow offline cards use their full width for status instead of a thin text column. */
    private final class LockOfflineStatus extends LinearLayout {
        private final View symbol,chevron;
        private final LinearLayout words;
        private final TextView title;
        LockOfflineStatus(View symbol,LinearLayout words,TextView title,View chevron){
            super(MainActivity.this);this.symbol=symbol;this.words=words;this.title=title;this.chevron=chevron;
            setGravity(Gravity.CENTER_VERTICAL);
            addView(symbol,new LinearLayout.LayoutParams(design.dp(34),design.dp(34)));
            addView(words,new LinearLayout.LayoutParams(0,-2,1));
            // The row opens the connection details; a chevron says so without a caption.
            LinearLayout.LayoutParams more=new LinearLayout.LayoutParams(design.dp(15),design.dp(15));more.setMarginStart(design.dp(6));
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(chevron,more);
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();
            // Allow the title two lines beside the icon. Below that, give the text a
            // full-width row so timestamps and the unverified state remain readable.
            float titleWidth=title.getPaint().measureText(title.getText().toString());
            boolean stack=MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED&&width-design.dp(44)<Math.max(design.dp(90),titleWidth/2);
            setOrientation(stack?VERTICAL:HORIZONTAL);
            // The status already names the lock. A separate decorative icon row would
            // consume the room needed by the timestamp and Connect in a short card.
            symbol.setVisibility(stack?GONE:VISIBLE);chevron.setVisibility(stack?GONE:VISIBLE);
            LinearLayout.LayoutParams wordsSize=(LinearLayout.LayoutParams)words.getLayoutParams();
            int wordsWidth=stack?-1:0,wordsTop=0,wordsStart=stack?0:design.dp(10);float wordsWeight=stack?0:1;
            wordsSize.width=wordsWidth;wordsSize.weight=wordsWeight;wordsSize.topMargin=wordsTop;wordsSize.setMarginStart(wordsStart);
            wordsSize.resolveLayoutDirection(words.getLayoutDirection());
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    private TextView connectionLabel(){
        TextView label=design.text("Disconnected",13,true);
        design.fitStatusLine(label);
        label.setOnClickListener(v->showConnectionDetails());
        label.setTooltipText("Connection details");
        return label;
    }
    /** States that need no extra line in the connection dialog: the state row already says them. */
    private static final java.util.Set<String> PLAIN_CONNECTION_STATES=new java.util.HashSet<>(java.util.Arrays.asList(
            "","Not connected","Disconnected","Connected","Connecting…","Checking owner…","Searching…","Paused in background",
            "Simulation on","Simulation off","Simulated connection","Simulation disconnected","Choose your scooter.",
            BleController.NEARBY_NEEDED,BleController.BLUETOOTH_OFF,BleController.NO_PROFILE));
    /**
     * Connection dialog: a state row (icon and one word) and the last connection time. A line appears only for an
     * error; missing permission, Bluetooth or owner setup turn the main button into the action that fixes it.
     */
    private void showConnectionDetails(){
        BleController ble=scooterService==null?null:scooterService.ble;
        ScooterProfiles.Profile profile=scooterService==null?null:scooterService.profiles.active();
        boolean permission=hasBluetoothPermission();
        android.bluetooth.BluetoothManager manager=getSystemService(android.bluetooth.BluetoothManager.class);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        boolean bluetoothOff=permission&&adapter!=null&&!adapter.isEnabled();
        boolean simulated=ble!=null&&ble.simulating();
        BleController.State state=ble==null?BleController.State.DISCONNECTED:ble.state();
        boolean ready=ble!=null&&ble.ready();
        boolean active=ble!=null&&(ble.connected()||ready||state==BleController.State.CONNECTING||state==BleController.State.AUTHENTICATING||state==BleController.State.SCANNING||state==BleController.State.WAITING);
        String word;int tone;
        if(!permission){word="No permission";tone=design.dim;}
        else if(ble==null){word="Starting…";tone=design.muted;}
        else if(ready){word=simulated?"Simulated":"Connected";tone=design.ok;}
        else if(state==BleController.State.WAITING){word="Retrying…";tone=design.orange;}
        else if(state==BleController.State.SCANNING){word="Searching…";tone=design.orange;}
        else if(state==BleController.State.AUTHENTICATING){word="Checking owner…";tone=design.orange;}
        else if(active){word="Connecting…";tone=design.orange;}
        else if(bluetoothOff){word="Bluetooth off";tone=design.dim;}
        else{word="Not connected";tone=design.dim;}
        String message=ble==null?"":ble.message();
        // Errors only, in their short form (a dropped change keeps its "change cancelled").
        String error=!active&&ble!=null&&!PLAIN_CONNECTION_STATES.contains(message)?message:null;
        if(state==BleController.State.WAITING&&ble!=null&&!PLAIN_CONNECTION_STATES.contains(message))error=message;
        LinearLayout body=column();body.setPadding(design.dp(24),design.dp(4),design.dp(24),design.dp(4));
        TextView stateValue=connectionRow(body,"State",word);
        View dot=design.icon(ready?"check":"bluetooth",16,tone);
        stateValue.setTextColor(ready?design.text:design.muted);
        ((LinearLayout)stateValue.getParent()).addView(dot,((LinearLayout)stateValue.getParent()).indexOfChild(stateValue),connectionIcon());
        if(profile!=null&&profile.lastConnected>0)connectionRow(body,"Last connected",DisplayTime.dateTime(this,preferences,profile.lastConnected));
        if(error!=null){
            TextView line=design.text(error,14,false);line.setTextColor(design.danger);line.setPadding(0,design.dp(10),0,design.dp(2));body.addView(line);
            if(BleController.NOTHING_FOUND.equals(error)){
                // "Why?" opens the two likely causes instead of a sentence on every search.
                TextView why=connectionLink("Why?");TextView reasons=design.caption("Power the scooter on and close the NAVEE app.");
                reasons.setVisibility(View.GONE);why.setOnClickListener(v->{reasons.setVisibility(View.VISIBLE);why.setVisibility(View.GONE);});
                body.addView(why);body.addView(reasons);
            }else if(BleController.OWNER_REJECTED.equals(error)){
                TextView check=connectionLink("Check owner setup");
                check.setOnClickListener(v->{if(controlDialog!=null)controlDialog.dismiss();openMoreSection("Scooters");});body.addView(check);
            }
        }
        boolean noProfile=profile==null||!profile.configured();
        String primary=active?"Disconnect":!permission?"Allow Nearby devices":bluetoothOff?"Turn on Bluetooth":noProfile&&!simulated?"Add scooter":"Connect";
        controlDialog=new AlertDialog.Builder(dialogContext()).setCustomTitle(dialogTitle("Connection")).setView(body).setPositiveButton("Close",null)
                .setNegativeButton(primary,(d,which)->{
                    if(active){if(ble!=null)ble.disconnect();redraw();}
                    else if(primary.equals("Add scooter"))openMoreSection("Scooters");
                    // Connect asks for Nearby devices or turns on Bluetooth first, then connects.
                    else connect();
                }).create();
        controlDialog.show();styleDialog(controlDialog,active?0:AlertDialog.BUTTON_NEGATIVE);
    }
    /** A label/value row in the connection dialog; returns the value view. */
    private TextView connectionRow(LinearLayout parent,String label,String value){
        LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.setMinimumHeight(design.dp(40));
        TextView name=design.text(label,14,false);name.setTextColor(design.muted);line.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView shown=design.text(value,14,true);shown.setGravity(Gravity.END);line.addView(shown,new LinearLayout.LayoutParams(-2,-2));
        line.setFocusable(true);line.setContentDescription(label+", "+value);
        parent.addView(line,new LinearLayout.LayoutParams(-1,-2));return shown;
    }
    private LinearLayout.LayoutParams connectionIcon(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(design.dp(16),design.dp(16));p.setMarginEnd(design.dp(8));return p;}
    private TextView connectionLink(String label){
        TextView link=design.text(label,14,true);link.setTextColor(design.accent);link.setMinHeight(design.dp(48));link.setGravity(Gravity.CENTER_VERTICAL);
        link.setClickable(true);link.setFocusable(true);link.setContentDescription(label);return link;
    }
    /** An animated span keeps the spinner next to its word, without adding a separate row. */
    private CharSequence connectingLabel(TextView owner){
        android.text.SpannableString text=new android.text.SpannableString("Connecting  \uFFFC");
        android.graphics.drawable.Drawable spinner=new android.graphics.drawable.Drawable(){
            private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas canvas){
                paint.setColor(design.muted);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(design.dp(2));paint.setStrokeCap(Paint.Cap.ROUND);
                RectF arc=new RectF(getBounds());arc.inset(design.dp(2),design.dp(2));
                canvas.drawArc(arc,Ui.motionEnabled()?(SystemClock.uptimeMillis()%900)*.4f:0,250,false,paint);
                if(resumed&&owner.isShown()&&Ui.motionEnabled())owner.postInvalidateDelayed(32);
            }
            @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
            @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}
            @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        };
        int size=Math.round(owner.getTextSize());spinner.setBounds(0,0,size,size);
        text.setSpan(new android.text.style.ImageSpan(spinner,android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),text.length()-1,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }
    private void renameScooter(){
        if(scooterService==null||scooterService.profiles.active()==null){toast("Set up a scooter profile first.");return;}ScooterProfiles.Profile profile=scooterService.profiles.active();EditText input=new EditText(this);input.setSingleLine(true);input.setText(profile.name);input.setSelectAllOnFocus(true);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(48)});
        design.styleSearch(input);input.setCompoundDrawablesRelative(null,null,null,null);input.setBackground(design.shape(design.raised,14,design.outline));input.setContentDescription("Scooter name");
        FrameLayout field=new FrameLayout(this);field.setPadding(design.dp(24),design.dp(8),design.dp(24),design.dp(4));field.addView(input,new FrameLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setCustomTitle(dialogTitle("Scooter name")).setView(field).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String name=input.getText().toString().trim();if(name.isEmpty()){input.setError("Enter a name.");return;}profile.name=name;profile.nameDefault=false;scooterService.profiles.update(profile);dialog.dismiss();rebuild();});
    }
    /** Other tabs: a tappable connection line above the page title. The lock lives on Home. */
    private LinearLayout makeCompactHeader(){
        LinearLayout header=column();header.setPadding(design.dp(expanded?24:18),design.dp(10),design.dp(expanded?24:18),design.dp(6));
        String pageName="";for(int i=0;i<pages.length;i++)if(pages[i].equals(selectedPage))pageName=names[i];
        connectionTitle=connectionLabel();connectionTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,12.5f*design.textScale);
        connectionTitle.setTypeface(design.regular());header.addView(connectionTitle);
        liveStatsTitle=null;
        if(selectedPage.equals("stats")){
            liveStatsTitle=new LiveStatsTitle(design);header.addView(liveStatsTitle);
        }else{
            TextView title=design.text(pageName,24,true);title.setPadding(0,design.dp(2),0,0);
            if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);header.addView(title);
        }
        profileTitle=null;
        return header;
    }
    private View navigation(boolean rail){
        LinearLayout nav=rail?column():row();nav.setGravity(rail?Gravity.TOP|Gravity.CENTER_HORIZONTAL:Gravity.CENTER);nav.setBackgroundColor(design.bg);nav.setPadding(design.dp(rail?8:4),design.dp(rail?20:6),design.dp(rail?8:4),design.dp(rail?12:6));
        View selected=null;
        for(int i=0;i<pages.length;i++){final String page=pages[i];View item=design.nav(names[i],icons[i],selectedPage.equals(page),()->select(page));LinearLayout.LayoutParams p=rail?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1);if(rail){item.setMinimumHeight(design.dp(78));p.bottomMargin=design.dp(8);}nav.addView(item,p);if(selectedPage.equals(page))selected=item;}
        if(!rail)return nav;
        // App text and Android font size compound. Let two-line captions determine each
        // item's height, and keep every destination reachable in a short or large-text rail.
        ScrollView scroller=new ScrollView(this);scroller.setFillViewport(false);scroller.setVerticalScrollBarEnabled(false);scroller.addView(nav,new ScrollView.LayoutParams(-1,-2));
        View current=selected;if(current!=null)scroller.post(()->current.requestRectangleOnScreen(new Rect(0,0,current.getWidth(),current.getHeight()),true));
        return scroller;
    }
    private void select(String page){
        if(page.equals("lights")){preferences.edit().putString("scooter_section","Lights").apply();page="controls";if(page.equals(selectedPage)){rebuild();return;}}
        // Arriving from another tab opens the main Scooter page; only the Lights shortcut above opens Lights.
        else if(page.equals("controls")&&!selectedPage.equals("controls"))preferences.edit().putString("scooter_section","").apply();
        else if(page.equals("controls")&&screen instanceof ControlsScreen&&((ControlsScreen)screen).lights){((ControlsScreen)screen).showMain();return;}
        if(page.equals(selectedPage)){if(scroll!=null)scroll.smoothScrollTo(0,0);return;}
        captureScroll();
        if(selectedPage.equals("more"))markMoreAway();
        if(selectedPage.equals("stats"))markStatsAway();
        if(page.equals("stats")){expireStatsSection(true);if(resumed)markStatsActive();}
        if(page.equals("more")){expireMoreSection(true);if(resumed)markMoreActive();}
        int from=java.util.Arrays.asList(pages).indexOf(selectedPage),to=java.util.Arrays.asList(pages).indexOf(page);
        selectedPage=page;preferences.edit().putString("last_page",page).apply();
        navFrom=from;transitionRebuild(Integer.signum(to-from));navFrom=-1;
    }
    /** Brief returns preserve the Live Stats section; a longer absence starts at Overview. */
    private void markStatsAway(){
        if(preferences.contains(STATS_AWAY_AT))return;
        preferences.edit().putLong(STATS_AWAY_AT,SystemClock.elapsedRealtime()).putInt(STATS_AWAY_BOOT,bootCount()).apply();
    }
    private void markStatsActive(){
        preferences.edit().remove(STATS_AWAY_AT).remove(STATS_AWAY_BOOT).apply();
    }
    private boolean expireStatsSection(boolean retainActive){return expireStatsSection(retainActive,SystemClock.elapsedRealtime());}
    private boolean expireStatsSection(boolean retainActive,long now){
        long away=preferences.getLong(STATS_AWAY_AT,-1);
        boolean expired=away<0?!retainActive:now<away||now-away>=STATS_RETENTION_MS
                ||preferences.getInt(STATS_AWAY_BOOT,-1)!=bootCount();
        if(!expired)return false;
        boolean changed=!"Overview".equals(preferences.getString("stats_section","Overview"));
        preferences.edit().putString("stats_section","Overview").remove(STATS_AWAY_AT).remove(STATS_AWAY_BOOT).apply();
        scrollPositions.put("stats",0);StatsScreen.clearSavedScroll();
        if(screen instanceof StatsScreen)((StatsScreen)screen).resetScroll();
        if(scroll!=null&&"stats".equals(scrollPage))scroll.scrollTo(0,0);
        return changed;
    }
    /** Retain a submenu only while in More or for a short return from another tab/app. */
    private void markMoreAway(){
        if(preferences.contains(MORE_AWAY_AT))return;
        preferences.edit().putLong(MORE_AWAY_AT,SystemClock.elapsedRealtime()).putInt(MORE_AWAY_BOOT,bootCount()).apply();
    }
    private void markMoreActive(){
        if(preferences.contains(MORE_AWAY_AT))preferences.edit().remove(MORE_AWAY_AT).remove(MORE_AWAY_BOOT).apply();
    }
    private int bootCount(){
        return android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);
    }
    private boolean expireMoreSection(boolean retainActive){
        return expireMoreSection(retainActive,SystemClock.elapsedRealtime());
    }
    private boolean expireMoreSection(boolean retainActive,long now){
        long away=preferences.getLong(MORE_AWAY_AT,-1);
        boolean expired=away<0?!retainActive:now<away||now-away>=MORE_RETENTION_MS
                ||preferences.getInt(MORE_AWAY_BOOT,-1)!=bootCount();
        if(!expired)return false;
        boolean hadSection=!preferences.getString("more_section","").isEmpty();
        preferences.edit().putString("more_section","").remove(MORE_AWAY_AT).remove(MORE_AWAY_BOOT)
                .remove("more_wide_section_scroll").remove("more_wide_menu_scroll").apply();
        scrollPositions.put("more",0);
        if(scroll!=null&&scrollPage.equals("more"))scroll.scrollTo(0,0);
        if(screen instanceof MoreScreen)((MoreScreen)screen).resetScroll();
        return hadSection;
    }
    private void openMoreSection(String section){
        // A deliberate shortcut wins over the older submenu's expired return state.
        expireMoreSection(true);markMoreActive();
        preferences.edit().putString("more_section",section).remove("more_wide_section_scroll").remove("more_wide_menu_scroll").apply();scrollPositions.put("more",0);
        if(selectedPage.equals("more")){if(scroll!=null)scroll.scrollTo(0,0);rebuild();}else select("more");
    }
    private void captureScroll(){if(scroll!=null)scrollPositions.put(scrollPage,scroll.getScrollY());}
    private boolean horizontalHinge(){return fold!=null&&fold.isSeparating()&&fold.hinge.width()>fold.hinge.height()&&fold.hinge.top>0&&fold.hinge.width()>0;}
    private boolean verticalHinge(){return fold!=null&&fold.isSeparating()&&fold.hinge.height()>fold.hinge.width()&&fold.hinge.left>0&&fold.hinge.height()>0;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private void heading(LinearLayout parent,String title,String detail){TextView h=design.text(title,22,true);h.setPadding(0,design.dp(12),0,design.dp(6));parent.addView(h);if(detail!=null){TextView d=design.caption(detail);d.setPadding(0,0,0,design.dp(16));parent.addView(d);}}
    private void updateHeader(){
        if(liveStatsTitle!=null)liveStatsTitle.setLive(scooterService!=null&&scooterService.ble.connected());
        BleController ble=scooterService==null?null:scooterService.ble;Boolean locked=ble==null?null:ble.locked();String pending=ble==null?null:ble.pending();boolean ready=ble!=null&&ble.ready(),home=selectedPage.equals("home");Boolean shownLock=locked;if(shownLock==null&&scooterService!=null){Integer saved=scooterService.displayFeatures().get("lock");if(saved!=null)shownLock=saved!=0;}
        boolean connecting=ble!=null&&(ble.state()==BleController.State.CONNECTING||ble.state()==BleController.State.AUTHENTICATING
                ||ble.state()==BleController.State.SCANNING||ble.state()==BleController.State.WAITING||ble.connected())&&!ready;
        ScooterProfiles.Profile active=scooterService==null?null:scooterService.profiles.active();
        long last=active==null?0:active.lastConnected;
        String scooterName=active==null?"your scooter":active.name;
        for(int lockIndex=dashboardLocks.isEmpty()?-1:0;lockIndex<dashboardLocks.size();lockIndex++){
        if(lockIndex>=0)loadLockViews(dashboardLocks.get(lockIndex));
        if(lockTitle!=null){
            boolean editing=screen instanceof DashboardScreen&&((DashboardScreen)screen).isEditing();
            boolean showBar=ready;
            if(lockReady!=null)lockReady.setVisibility(showBar?View.VISIBLE:View.GONE);
            if(lockOffline!=null)lockOffline.setVisibility(showBar?View.GONE:View.VISIBLE);
            lockTitle.setText("lock".equals(pending)?"Updating…":shownLock==null?"Lock":shownLock?"Locked":"Unlocked");
            lockTitle.setTextColor(Boolean.FALSE.equals(shownLock)&&ready?design.danger:design.text);
            design.decorate(lockTitle,Boolean.FALSE.equals(shownLock)?"unlock":"lock");
            android.graphics.drawable.Drawable lockGlyph=lockTitle.getCompoundDrawablesRelative()[0];
            if(lockGlyph!=null){lockGlyph.setBounds(0,0,design.dp(20),design.dp(20));lockTitle.setCompoundDrawablesRelative(lockGlyph,null,null,null);lockTitle.setCompoundDrawablePadding(design.dp(8));}
            lockControl.update(shownLock,ready&&pending==null&&!editing&&lockOffered(ble,shownLock));
            if(offlineTitle!=null){
                // The scooter name is already in the Home header; repeating it can fill
                // a narrow card and push Cancel out of view at larger system font sizes.
                String unknown=compactLock?"Lock state unknown":"Lock state unverified";
                String known=shownLock==null?null:shownLock?"Locked":"Unlocked";
                offlineTitle.setText(connecting?(compactLock?"Connecting…":"Connecting"):known==null?unknown:known);
                offlineTitle.setTextColor(design.muted);
                String description=known==null?unknown:"Last known lock state: "+known.toLowerCase(java.util.Locale.ROOT)+". Reconnect to verify.";
                offlineTitle.setContentDescription(connecting?"Connecting to "+scooterName+". "+description:description);
                // The feature cache contains scooter readbacks, never a requested lock change.
                // Keep its stale label visible even during reconnect; it does not enable the switch.
                // A past connection alone cannot verify the lock; its age is already in the header.
                String detail=known==null?"":connecting?"Last known: "+known.toLowerCase(java.util.Locale.ROOT):"Last known";
                offlineDetail.setText(detail);offlineDetail.setVisibility(detail.isEmpty()?View.GONE:View.VISIBLE);
            }
        }
        }
        if(!dashboardLocks.isEmpty())loadLockViews(dashboardLocks.get(0));
        if(connectionTitle!=null){
            String state=ready?"Connected":connecting?"Connecting":"Disconnected";
            String shown=state+(ready&&active!=null?" · "+active.name:"")+(!ready&&!connecting&&last>0?" · last seen "+DisplayTime.recent(this,preferences,last,System.currentTimeMillis()):"");
            connectionTitle.setText(connecting?connectingLabel(connectionTitle):shown);
            connectionTitle.setContentDescription(state+(!ready&&!connecting&&last>0?", last connected "+DisplayTime.dateTime(this,preferences,last):"")+". Connection details");
            connectionTitle.setTextColor(design.muted);
        }
        updateConnectionAction(ready,connecting);
        ScooterProfiles.Profile p=scooterService==null?null:scooterService.profiles.active();if(profileTitle!=null){profileTitle.setText(p==null?"Your scooter":p.name);profileTitle.setVisibility(home?View.VISIBLE:View.GONE);}
    }
    /**
     * Whether the lock switch can be used in the shown direction on this model. Riding, a missing
     * motion report or a warning keep it tappable so the tap says why; the gate still refuses.
     */
    private static boolean lockOffered(BleController ble,Boolean shownLock){
        if(ble==null)return false;CapabilityResolver.Decision d=ble.decision("lock",shownLock==null||!shownLock?1:0);
        return d.available()||d.blockedByRide();
    }
    /** Updates the action presentation without changing the Bluetooth transport. */
    private void updateConnectionAction(boolean ready,boolean connecting){
        BleController ble=scooterService==null?null:scooterService.ble;
        for(int lockIndex=dashboardLocks.isEmpty()?-1:0;lockIndex<dashboardLocks.size();lockIndex++){
        if(lockIndex>=0)loadLockViews(dashboardLocks.get(lockIndex));
        if(connectionAction!=null){
            boolean busy=ble!=null&&(ble.connected()||connecting||ready);
            connectionAction.setVisibility(!ready||showDashboardDisconnect?View.VISIBLE:View.GONE);
            boolean allow=!ready&&!busy&&!hasBluetoothPermission();
            String action=ready?"Disconnect":busy?"Cancel":allow?"Allow Nearby devices":"Connect";
            connectionAction.setContentDescription(ready?"Disconnect scooter":busy?"Cancel connection":allow?"Allow Nearby devices":"Connect scooter");
            // Only restyle on a real state change, not on every incoming BLE packet.
            if(!action.contentEquals(connectionAction.getText())){
                connectionAction.setText(action);
                connectionAction.setTextColor(busy?design.text:design.onAccent);
                android.graphics.drawable.Drawable fill=new android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(Ui.withAlpha(busy?design.accent:android.graphics.Color.WHITE,35)),
                        design.shape(busy?design.raised:design.accent,14,busy?design.outline:0),null);
                setConnectionActionBackground(fill);
            }
            final BleController bleRef=ble;
            connectionAction.setOnClickListener(v->{if(busy){if(bleRef!=null)bleRef.disconnect();redraw();}else connect();});
            connectionAction.setEnabled(!(screen instanceof DashboardScreen&&((DashboardScreen)screen).isEditing()));
        }
        }
        if(!dashboardLocks.isEmpty())loadLockViews(dashboardLocks.get(0));
    }
    private final class LockControl extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint words=new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics wordMetrics=new Paint.FontMetrics();
        private final RectF track=new RectF();
        private Boolean value;
        private boolean available,dragging,dragSide;
        /** Lock bar: draw only the state word, vertically centred inside the switch. */
        boolean compact;
        /** When the rider last asked for a lock change; the scooter's confirmation then gets a haptic. */
        private long requestedAt;
        private float downX,downY,previewX,position;
        private android.animation.ValueAnimator motion;
        /** Recent thumb positions for a short speed trail; visual only, never the lock value. */
        private final LockSliderTrail trail=new LockSliderTrail();
        LockControl(){super(MainActivity.this);setMinimumWidth(design.dp(48));setMinimumHeight(design.dp(48));setClickable(true);setFocusable(true);setContentDescription("Electronic lock switch");setOnClickListener(v->apply(!Boolean.TRUE.equals(value)));}
        private void apply(boolean target){if(available&&value!=null&&scooterService!=null&&target!=value){if(scooterService.ble.set("lock",target?1:0))requestedAt=SystemClock.elapsedRealtime();redraw();}}
        void update(Boolean locked,boolean enabled){
            boolean changed=value==null?locked!=null:!value.equals(locked);
            boolean hadState=value!=null;
            if(changed&&hadState&&locked!=null&&requestedAt>0&&SystemClock.elapsedRealtime()-requestedAt<15000){requestedAt=0;Haptics.confirm(this);}
            value=locked;available=enabled;setEnabled(enabled);if(!enabled)trail.clear();
            if(!enabled&&dragging){dragging=false;setPressed(false);releaseParent();settle(hadState&&locked!=null);}
            else if(changed&&!dragging)settle(hadState&&locked!=null);
            setContentDescription(locked==null?"Electronic lock state unavailable":"Electronic lock, "+(locked?"locked":"unlocked")+". Double-tap or slide to "+(locked?"unlock":"lock"));
            if(Build.VERSION.SDK_INT>=30)setStateDescription(locked==null?"Unavailable":locked?"Locked":"Unlocked");
            invalidate();
        }
        private void cancelMotion(){if(motion!=null){motion.cancel();motion=null;}}
        private void releaseParent(){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
        /** Position is visual only. Value/accessibility always reflect the last confirmed report. */
        private void settle(boolean animate){
            cancelMotion();
            final float target=Boolean.TRUE.equals(value)?1:0;
            if(!animate||!resumed||!isAttachedToWindow()||!isShown()||!Ui.motionEnabled()||Math.abs(target-position)<.001f){position=target;trail.clear();invalidate();return;}
            motion=android.animation.ValueAnimator.ofFloat(position,target);
            motion.setDuration(180);motion.setInterpolator(new android.view.animation.PathInterpolator(.2f,0,0,1));
            motion.addUpdateListener(animation->move((Float)animation.getAnimatedValue()));
            motion.start();
        }
        /** Moves the drawn thumb only; the trail samples it while motion is allowed. */
        private void move(float next){position=next;if(available&&Ui.motionEnabled())trail.record(next,SystemClock.uptimeMillis());else trail.clear();invalidate();}
        void stopMotion(){cancelMotion();dragging=false;setPressed(false);releaseParent();position=Boolean.TRUE.equals(value)?1:0;trail.clear();invalidate();}
        @Override protected void onDetachedFromWindow(){stopMotion();super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE)stopMotion();}
        @Override public CharSequence getAccessibilityClassName(){return Switch.class.getName();}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setCheckable(value!=null);info.setChecked(Boolean.TRUE.equals(value));info.setEnabled(available);}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(!available)return false;
            if(event.getAction()==MotionEvent.ACTION_DOWN){cancelMotion();downX=event.getX();downY=event.getY();previewX=downX;dragging=false;dragSide=downX>getWidth()/2f;setPressed(true);return true;}
            if(event.getAction()==MotionEvent.ACTION_MOVE){float delta=event.getX()-downX;if(Math.abs(delta)>ViewConfiguration.get(MainActivity.this).getScaledTouchSlop()&&Math.abs(delta)>Math.abs(event.getY()-downY)){dragging=true;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);}if(dragging){boolean side=event.getX()>getWidth()/2f;if(side!=dragSide)Haptics.tick(this);dragSide=side;previewX=event.getX();move(sliderGeometry().positionFor(previewX));}return true;}
            if(event.getAction()==MotionEvent.ACTION_UP){boolean dragged=dragging;boolean target=previewX>getWidth()/2f;dragging=false;setPressed(false);releaseParent();if(dragged)apply(target);else performClick();settle(true);return true;}
            if(event.getAction()==MotionEvent.ACTION_CANCEL){dragging=false;setPressed(false);releaseParent();settle(true);return true;}return true;
        }
        private LockSliderGeometry sliderGeometry(){
            return new LockSliderGeometry(getWidth(),getHeight(),design.dp(58),design.dp(5));
        }
        /** The state word alone, centred on the bar's height; false when it cannot fit the lane. */
        private boolean drawState(Canvas canvas,String state,float left,float right,float h){
            int alpha=words.getAlpha();
            android.text.TextPaint head=new android.text.TextPaint(words);
            head.setTypeface(design.semibold());head.setTextSize(design.sp(15));
            if(head.measureText(state)>right-left)head.setTextSize(design.sp(13));
            Paint.FontMetrics metrics=head.getFontMetrics();
            if(metrics.descent-metrics.ascent>h||head.measureText(state)>right-left)return false;
            head.setColor(!available?design.dim:Boolean.FALSE.equals(value)?design.danger:design.text);head.setAlpha(alpha);
            canvas.drawText(state,(left+right)/2,h/2-(metrics.ascent+metrics.descent)/2,head);
            return true;
        }
        /** Locked: white knob on the right. Unlocked: red knob on the left. Tap anywhere or slide to change. */
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float x=0,y=0,w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
            boolean locked=Boolean.TRUE.equals(value),unlocked=Boolean.FALSE.equals(value);
            // Lock bar: the slider is the card, so it reaches the cell edges with the cards' 20 dp corners.
            float edge=compact?design.dp(1)/2f:design.dp(1);
            track.set(x+edge,y+edge,x+w-edge,y+h-edge);float r=compact?Math.min(design.dp(20),track.height()/2):track.height()/2;
            paint.setShader(null);paint.setStyle(Paint.Style.FILL);
            if(compact){paint.setColor(design.surface);canvas.drawRoundRect(track,r,r,paint);}
            if(unlocked&&available){paint.setShader(new android.graphics.LinearGradient(track.left,0,track.right,0,Ui.withAlpha(design.accent,82),Ui.withAlpha(design.accent,20),android.graphics.Shader.TileMode.CLAMP));}
            else paint.setColor(compact?design.surface:design.dark?design.bg:design.raised);
            canvas.drawRoundRect(track,r,r,paint);paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(design.dp(1));paint.setColor(unlocked&&available?Ui.withAlpha(design.accent,110):design.raised);canvas.drawRoundRect(track,r,r,paint);paint.setStyle(Paint.Style.FILL);
            // A tall card is still a horizontal switch. Keep the thumb and its travel
            // independent of card height, and share that geometry with touch tracking.
            LockSliderGeometry geometry=sliderGeometry();
            float knob=geometry.radius;
            float cx=geometry.centerFor(position);
            int thumb=!available?design.dim:locked?android.graphics.Color.WHITE:design.accent;
            // Speed trail: a few faint, shrinking discs back to where the thumb was a moment ago,
            // inside the track and under the crisp thumb. It empties within ~60 ms of stopping.
            if(!trail.isEmpty()&&available&&Ui.motionEnabled()){
                float lag=cx-geometry.centerFor(trail.tail(SystemClock.uptimeMillis(),position));
                lag=Math.max(-geometry.diameter*1.5f,Math.min(geometry.diameter*1.5f,lag));
                if(Math.abs(lag)>.5f){
                    float strength=Math.min(1,Math.abs(lag)/Math.max(1,geometry.diameter));
                    int save=canvas.save();canvas.clipRect(track);
                    // Discs at most ~a third of a radius apart, so a fast thumb blurs instead of stacking visible coins.
                    int steps=Math.max(5,Math.min(12,(int)Math.ceil(Math.abs(lag)/Math.max(1,knob*.35f))));
                    for(int i=steps;i>=1;i--){float f=i/(steps+1f);paint.setColor(Ui.withAlpha(thumb,Math.round(56*strength*(1-f)*5f/steps)));canvas.drawCircle(cx-lag*f,h/2,knob*(1-.15f*f),paint);}
                    canvas.restoreToCount(save);
                    postInvalidateOnAnimation();
                }
            }else if(!trail.isEmpty()&&!Ui.motionEnabled())trail.clear();
            paint.setColor(thumb);canvas.drawCircle(cx,h/2,knob,paint);
            design.drawIcon(canvas,locked?"lock":"unlock",!available?design.surface:locked?design.bg:design.onAccent,cx,h/2,knob*0.95f);
            // The accessibility label keeps the full tap-or-slide action; the arrow shows the slide direction.
            String label=value==null?"Unavailable":locked?"Unlock":"Lock";
            words.setTypeface(design.semibold());
            words.setTextSize(design.sp(13));words.setColor(!available?design.dim:unlocked?Ui.withAlpha(design.danger,235):design.muted);
            // Let the knob pass through the label's space without obscuring its words.
            words.setAlpha(Math.round(255*Math.max(0,1-4*Math.abs(position-(locked?1:0)))));
            boolean arrow=available&&value!=null&&!dragging;
            float lane=arrow?design.dp(18):design.dp(4);
            float textLeft=locked||value==null?x+design.dp(18):x+geometry.diameter+lane,textRight=locked||value==null?x+w-geometry.diameter-lane:x+w-design.dp(18);
            float textWidth=Math.max(0,textRight-textLeft);
            if(arrow){
                // Points the way the knob slides: left to unlock, right to lock.
                float size=design.dp(12),ax=locked?cx-knob-design.dp(10):cx+knob+design.dp(10);
                design.drawIcon(canvas,locked?"back":"chevron",Ui.withAlpha(words.getColor(),words.getAlpha()),ax,h/2,size);
            }
            // The switch remains a full-height touch target even in a very narrow card.
            // Its accessibility label always keeps the full tap/slide action text.
            String fitted=words.measureText(label)<=textWidth?label:"";
            words.setTextAlign(Paint.Align.CENTER);words.getFontMetrics(wordMetrics);
            if(compact&&value!=null){drawState(canvas,locked?"Locked":"Unlocked",textLeft,textRight,h);return;}
            canvas.drawText(fitted,(textLeft+textRight)/2,h/2-(wordMetrics.ascent+wordMetrics.descent)/2,words);
        }
    }
    @Override public void connect(){
        if (scooterService != null && scooterService.simulating()) {
            scooterService.ble.connectLast(); redraw(); return;
        }
        if(!resumed){pendingConnect=true;return;}
        if(!hasBluetoothPermission()){pendingConnect=true;requestBluetooth();return;}
        android.bluetooth.BluetoothManager manager=getSystemService(android.bluetooth.BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null){toast("This phone does not support Bluetooth.");return;}if(!adapter.isEnabled()){pendingConnect=true;startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BLUETOOTH);return;}
        if(scooterService==null){pendingConnect=true;ensureService();return;}
        ScooterProfiles.Profile profile=scooterService.profiles.active();if(profile==null||!profile.configured()){select("more");return;}
        BleController.State state=scooterService.ble.state();if(state==BleController.State.CONNECTING||state==BleController.State.AUTHENTICATING||scooterService.ble.ready())return;
        scooterService.ble.connectLast();if(profile.mac.isEmpty())showScanner(false);redraw();
    }
    private boolean hasBluetoothPermission(){return Build.VERSION.SDK_INT>=31?checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED:checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    @Override public void requestBluetooth(){if(!resumed)return;if(hasBluetoothPermission()){ensureService();if(scooterService!=null){if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}}return;}requestPermissions(Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION},BLUETOOTH_PERMISSION);}
    @Override public void requestRideLocation(Runnable ready){
        if (scooterService != null && scooterService.simulating()) { toast("Exit simulation first"); return; }
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
        if(request==BLUETOOTH_PERMISSION){if(hasBluetoothPermission())requestBluetooth();else{pendingConnect=pendingScan=false;toast(BleController.NEARBY_NEEDED);}redraw();}
    }
    private void finishRidePermission(){
        if(destroyed||!resumed||!rideLocationResolved||pendingRideLocation==null)return;
        Runnable ready=pendingRideLocation;pendingRideLocation=null;rideLocationResolved=false;ready.run();
    }

    private void showScanner(boolean start){
        if (scooterService != null && scooterService.simulating()) { toast("Exit simulation to find a real scooter"); return; }
        if(!resumed){pendingScan=true;return;}
        if(!hasBluetoothPermission()){pendingScan=true;requestBluetooth();return;}if(scooterService==null){pendingScan=true;ensureService();return;}
        if(scooterService.isRecording()){toast("Stop recording first");return;}
        if(scooterService.ble.pending()!=null){toast("Scooter busy. Try again.");return;}
        android.bluetooth.BluetoothManager manager=getSystemService(android.bluetooth.BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();if(adapter==null){toast("This phone does not support Bluetooth.");return;}if(!adapter.isEnabled()){pendingScan=true;startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BLUETOOTH);return;}
        if(scooterService.profiles.active()==null){select("more");return;}
        if(start){scooterService.ble.disconnect();scooterService.ble.scan();}
        if(scanDialog!=null&&scanDialog.isShowing()){updateScan();return;}
        final AlertDialog[] sheet=new AlertDialog[1];
        LinearLayout content=sheetContent("Choose your scooter",null,()->{if(sheet[0]!=null)sheet[0].dismiss();});
        LinearLayout status=row();status.setGravity(Gravity.CENTER_VERTICAL);status.setPadding(design.dp(2),0,0,design.dp(12));
        scanSpinner=new Spinner();status.addView(scanSpinner,new LinearLayout.LayoutParams(design.dp(14),design.dp(14)));
        scanStatus=design.caption("");scanStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,13*design.textScale);
        LinearLayout.LayoutParams statusText=new LinearLayout.LayoutParams(0,-2,1);statusText.leftMargin=design.dp(10);status.addView(scanStatus,statusText);
        content.addView(status);
        scanList=column();content.addView(scanList);
        LinearLayout actions=row();actions.setPadding(0,design.dp(6),0,0);
        TextView again=design.secondaryButton("Scan again",()->{if(scooterService!=null){scooterService.ble.scan();scanSignature="";updateScan();}});
        scanConnect=design.button("Connect",this::connectSelectedScan);
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=design.dp(5);actions.addView(again,left);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.leftMargin=design.dp(5);actions.addView(scanConnect,right);
        content.addView(actions);
        scanSignature="";scanSelected=null;
        scanDialog=showSheet(content);sheet[0]=scanDialog;
        scanDialog.setOnDismissListener(d->{scanDialog=null;scanList=null;scanStatus=null;scanSpinner=null;scanConnect=null;scanSelected=null;if(scooterService!=null&&scooterService.ble.state()==BleController.State.SCANNING)scooterService.ble.disconnect();});
        updateScan();
    }
    private void connectSelectedScan(){
        if(scooterService==null||scanSelected==null)return;
        for(ScanResult result:scanResults)if(result.getDevice().getAddress().equals(scanSelected)){scooterService.ble.connectDiscovered(result);if(scanDialog!=null)scanDialog.dismiss();redraw();return;}
    }
    private static int signalRank(int rssi){return rssi>=-60?4:rssi>=-70?3:rssi>=-80?2:1;}
    private void updateScan(){
        if(scanList==null||scooterService==null)return;scanResults.clear();scanResults.addAll(scooterService.ble.discoveries());
        boolean scanning=scooterService.ble.state()==BleController.State.SCANNING;
        ScooterProfiles.Profile profile=scooterService.profiles.active();String mine=profile==null?"":profile.mac;
        boolean present=false;for(ScanResult result:scanResults)if(result.getDevice().getAddress().equals(scanSelected))present=true;
        // The profile's own scooter starts selected; other scooters need a deliberate tap before Connect.
        if(!present){scanSelected=null;for(ScanResult result:scanResults)if(!mine.isEmpty()&&result.getDevice().getAddress().equalsIgnoreCase(mine))scanSelected=result.getDevice().getAddress();}
        scanStatus.setText(scanning?"Searching…":scanResults.isEmpty()?"No scooter found. Is it on?":scanResults.size()==1?"1 scooter found":scanResults.size()+" scooters found");
        scanSpinner.setVisibility(scanning?View.VISIBLE:View.GONE);
        StringBuilder signature=new StringBuilder(String.valueOf(scanSelected)).append(':').append(profile==null?"":profile.id);
        for(ScanResult result:scanResults){String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();ScooterProfiles.Profile saved=scooterService.profiles.hardwareMatch(null,result.getDevice().getAddress(),null);signature.append('|').append(result.getDevice().getAddress()).append(':').append(signalRank(result.getRssi())).append(':').append(name).append(':').append(saved==null?"":saved.id+":"+saved.name);}
        if(!signature.toString().equals(scanSignature)){
            scanSignature=signature.toString();scanList.removeAllViews();
            for(ScanResult result:scanResults){
                String address=result.getDevice().getAddress();ScooterProfiles.Profile saved=scooterService.profiles.hardwareMatch(null,address,null);
                scanList.addView(scanOption(result,saved,address.equals(scanSelected)));
            }
        }
        scanConnect.setEnabled(scanSelected!=null);
    }
    /** Nearby scooter row (ph/10): scooter icon, name, signal bars; the selected scooter has a red outline. */
    private View scanOption(ScanResult result,ScooterProfiles.Profile saved,boolean selected){
        String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();if(name==null||name.isEmpty())name="Navee scooter";
        if(saved!=null)name=saved.name;
        ScooterProfiles.Profile active=scooterService.profiles.active();boolean match=saved!=null&&active!=null&&saved.id.equals(active.id);
        String address=result.getDevice().getAddress(),suffix=address.substring(Math.max(0,address.length()-5));
        int rank=signalRank(result.getRssi());String strength=rank==4?"Strong signal":rank==3?"Good signal":rank==2?"Fair signal":"Weak signal";
        LinearLayout option=row();option.setGravity(Gravity.CENTER_VERTICAL);option.setPadding(design.dp(12),design.dp(10),design.dp(14),design.dp(10));option.setMinimumHeight(design.dp(64));
        android.graphics.drawable.GradientDrawable face=new android.graphics.drawable.GradientDrawable();face.setCornerRadius(design.dp(16));
        face.setColor(selected?Ui.withAlpha(design.accent,design.dark?26:18):design.surface);face.setStroke(design.dp(1.5f),selected?design.accent:design.surface);
        option.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,30)),face,null));
        option.addView(design.iconSquare("scooter",design.accent),new LinearLayout.LayoutParams(design.dp(40),design.dp(40)));
        LinearLayout words=column();
        TextView title=design.text(name,15,true);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(title);
        // Every known address uses its saved name and label, including another profile's scooter.
        LinearLayout detail=row();detail.setGravity(Gravity.CENTER_VERTICAL);detail.setPadding(0,design.dp(3),0,0);
        if(saved!=null){
            TextView yours=design.text(match?"Yours":"Saved",11,true);yours.setTextColor(design.ok);yours.setPadding(design.dp(8),design.dp(1),design.dp(8),design.dp(2));
            yours.setBackground(design.shape(Ui.withAlpha(design.ok,design.dark?40:30),100,0));
            LinearLayout.LayoutParams chip=new LinearLayout.LayoutParams(-2,-2);chip.setMarginEnd(design.dp(8));detail.addView(yours,chip);
        }
        detail.addView(design.caption(suffix));words.addView(detail);
        LinearLayout.LayoutParams wordsParams=new LinearLayout.LayoutParams(0,-2,1);wordsParams.leftMargin=design.dp(12);wordsParams.rightMargin=design.dp(10);option.addView(words,wordsParams);
        SignalBars bars=new SignalBars(rank);int color=rank>=3?design.ok:design.orange;bars.set(rank,color,design.raised);
        option.addView(bars,new LinearLayout.LayoutParams(design.dp(18),design.dp(14)));
        option.setClickable(true);option.setFocusable(true);option.setSelected(selected);
        option.setContentDescription(name+", "+strength+(saved==null?"":match?", your scooter":", saved scooter in My scooters")+", address ending "+suffix);
        if(Build.VERSION.SDK_INT>=30)option.setStateDescription(selected?"Selected":"");
        option.setOnClickListener(v->{scanSelected=address;scanSignature="";updateScan();});
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=design.dp(8);option.setLayoutParams(params);
        return option;
    }
    private Map<String,Telemetry.Metric> metrics(){LinkedHashMap<String,Telemetry.Metric> values=new LinkedHashMap<>();if(scooterService!=null)for(Telemetry.Metric metric:scooterService.ble.telemetry.metrics())values.put(metric.key,metric);return values;}
    private Double fresh(Map<String,Telemetry.Metric> metrics,String key,long age){Telemetry.Metric metric=metrics.get(key);return scooterService!=null&&scooterService.ble.connected()&&metric!=null&&metric.numericValue!=null&&metric.updatedMs>=0&&SystemClock.elapsedRealtime()-metric.updatedMs<=age?metric.numericValue:null;}
    private String number(Double value,int digits,String unit){return value==null?"—":String.format(Locale.US,"%."+digits+"f",value)+(unit.isEmpty()?"":" "+unit);}
    private String distance(Double km){if(km==null)return "—";String units=preferences.getString("units","mi");if(units.equals("km"))return number(km,1,"km");if(units.equals("dual"))return number(km/1.609344,1,"mi")+" / "+number(km,1,"km");return number(km/1.609344,1,"mi");}
    private String speed(Double kmh){return kmh==null?"—":DisplayUnits.speed(kmh,preferences.getString("units","mi"));}
    private String featureLabel(ScooterCommands.Feature feature,int value){
        if(feature.key.equals("start_speed")&&value==0)return "Standing start";
        // Another model's variant has its own labels; speeds keep the rider's units.
        String oem=scooterService==null?null:scooterService.ble.decision(feature.key).oemLabel(value);
        if(oem!=null)return oem;
        return DisplayUnits.featureValue(feature,value,preferences.getString("units","mi"));
    }
    /** The decided variant when it is another model's (not the UT5 MAX catalogue), else null. */
    private FeatureVariant oemVariant(String key){
        if(scooterService==null)return null;CapabilityResolver.Decision d=scooterService.ble.decision(key);
        return d.variant!=null&&d.variant.decode!=FeatureVariant.Decode.UT5_FEATURE?d.variant:null;
    }
    /** A control that is Lab on the UT5 MAX but mapped normally (not Lab) for the connected model. */
    private boolean ordinaryOemRow(String key){
        FeatureVariant variant=oemVariant(key);
        return variant!=null&&!variant.lab&&scooterService.ble.decision(key).support==CapabilityResolver.Support.KNOWN_ENCODING;
    }
    /**
     * Sends at once, or first asks where the official app asks on that model (an OEM-mapped control off the
     * UT5 MAX). The UT5 MAX keeps today's direct controls.
     */
    private void confirmChange(ScooterCommands.Feature feature,int value,Runnable send){
        String caution=scooterService==null?null:ModelRegistry.caution(scooterService.ble.decision(feature.key),value);
        if(caution==null){send.run();return;}
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(shortLabel(feature)).setMessage(caution)
                .setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,n)->{if(scooterService!=null)send.run();}).create();
        dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    private static final String AUTO_RANGE="Auto (riding mode)";
    private TextView metricLine(LinearLayout parent,String label){LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);TextView labelView=design.caption(label);line.addView(labelView,new LinearLayout.LayoutParams(0,-2,1));TextView value=design.text("—",16,true);value.setGravity(Gravity.RIGHT);line.addView(value);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=design.dp(8);p.bottomMargin=design.dp(8);parent.addView(line,p);return value;}
    private final class LoadingScreen implements AppScreen {
        private final LinearLayout view=column();
        /** With permission: a spinner while the service opens. Without it: the one button that unblocks it. */
        LoadingScreen(){
            LinearLayout card=design.card();card.setGravity(Gravity.CENTER_HORIZONTAL);card.setPadding(design.dp(18),design.dp(22),design.dp(18),design.dp(18));
            if(hasBluetoothPermission()){
                Spinner spinner=new Spinner();card.addView(spinner,new LinearLayout.LayoutParams(design.dp(28),design.dp(28)));
            }else{
                TextView allow=design.button("Allow Nearby devices",MainActivity.this::requestBluetooth);
                android.graphics.drawable.Drawable glyph=new android.graphics.drawable.Drawable(){
                    @Override public void draw(Canvas canvas){android.graphics.Rect b=getBounds();design.drawIcon(canvas,"bluetooth",design.onAccent,b.exactCenterX(),b.exactCenterY(),b.width());}
                    @Override public void setAlpha(int alpha){}
                    @Override public void setColorFilter(android.graphics.ColorFilter filter){}
                    @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
                };
                glyph.setBounds(0,0,design.dp(18),design.dp(18));allow.setCompoundDrawablesRelative(glyph,null,null,null);allow.setCompoundDrawablePadding(design.dp(8));
                // Wrapped width keeps the glyph beside its words.
                LinearLayout.LayoutParams allowSize=new LinearLayout.LayoutParams(-2,-2);allowSize.bottomMargin=design.dp(6);card.addView(allow,allowSize);
                TextView needed=design.caption("Needed to connect");needed.setGravity(Gravity.CENTER);card.addView(needed,new LinearLayout.LayoutParams(-1,-2));
            }
            LinearLayout.LayoutParams top=new LinearLayout.LayoutParams(-1,-2);top.topMargin=design.dp(12);view.addView(card,top);
        }
        @Override public View view(){return view;}
        @Override public void refresh(){}
    }
    /**
     * Volt Scooter tab: one scrolling page (top speed card, then grouped settings) and a Lights page for
     * the line and star zones. Search filters every group, on either page, and hides empty groups.
     * On wide screens (side rail shown) the groups sit in two columns under one page scroll: search, top
     * speed and Ride on the left, the other groups on the right; the Lights page puts headlights and
     * line lights left, star lights right. The phone layout stays a single column.
     */
    private final class ControlsScreen implements AppScreen {
        private final LinearLayout view=column();
        private final ArrayList<ControlRow> rows=new ArrayList<>();
        private final ArrayList<ChargeDoneRow> chargeRows=new ArrayList<>();
        private final LinkedHashMap<String,LinearLayout> sections=new LinkedHashMap<>(), bodies=new LinkedHashMap<>();
        private final LinkedHashMap<String,TextView> labels=new LinkedHashMap<>();
        private final LinkedHashMap<View,String[]> extras=new LinkedHashMap<>();
        private final TextView results,bannerText,bannerAction,zonesValue,clockButton,nameValue;
        private final EditText search;
        private final LinearLayout lightsHeader,banner,readOnlyBanner,testBanner;private TextView identifyAction,testBannerText,testReviewAction;private String offeredKeys="";
        private final View headlightRow,zonesRow,clockSpinner;
        private final SettingLine clockLine,chargerLine;
        private final TopSpeedCard topSpeed;
        /** Two-column panes on wide screens; both null on a phone. */
        private final LinearLayout leftPane,rightPane;
        private boolean lights;
        private String query="";
        ControlsScreen(boolean lightsPage){
            // Only "Lights" (set by Quick access) is meaningful now; older category values open the main page.
            lights=lightsPage||"Lights".equals(preferences.getString("scooter_section",""));
            lightsHeader=column();
            LinearLayout backRow=row();backRow.setGravity(Gravity.CENTER_VERTICAL);backRow.setMinimumHeight(design.dp(48));
            backRow.addView(design.icon("back",16,design.accent),new LinearLayout.LayoutParams(design.dp(16),design.dp(16)));
            TextView backText=design.text("Scooter",13.5f,false);backText.setTypeface(design.medium());backText.setTextColor(design.accent);backText.setPadding(design.dp(4),0,design.dp(10),0);backRow.addView(backText);
            backRow.setClickable(true);backRow.setFocusable(true);backRow.setContentDescription("Back to Scooter settings");backRow.setBackground(rowRipple());
            backRow.setOnClickListener(v->showMain());lightsHeader.addView(backRow,new LinearLayout.LayoutParams(-2,-2));
            TextView lightsTitle=design.text("Lights",24,true);lightsTitle.setPadding(0,0,0,design.dp(10));if(Build.VERSION.SDK_INT>=28)lightsTitle.setAccessibilityHeading(true);lightsHeader.addView(lightsTitle);
            view.addView(lightsHeader);
            // Disconnected: a dark-red banner explains that the grey values are the last seen settings.
            banner=row();banner.setGravity(Gravity.CENTER_VERTICAL);banner.setPadding(design.dp(14),design.dp(4),design.dp(8),design.dp(4));banner.setMinimumHeight(design.dp(60));
            banner.setBackground(design.shape(Ui.withAlpha(design.accent,design.dark?36:22),16,Ui.withAlpha(design.accent,design.dark?100:80)));
            banner.addView(design.icon("bluetooth",18,design.text),new LinearLayout.LayoutParams(design.dp(18),design.dp(18)));
            bannerText=design.text("Last seen (disconnected)",13,false);
            LinearLayout.LayoutParams bannerWords=new LinearLayout.LayoutParams(0,-2,1);bannerWords.leftMargin=design.dp(10);bannerWords.rightMargin=design.dp(8);banner.addView(bannerText,bannerWords);
            bannerAction=pill("Connect",MainActivity.this::connect);bannerAction.setContentDescription("Connect scooter");banner.addView(bannerAction);
            LinearLayout.LayoutParams bannerParams=new LinearLayout.LayoutParams(-1,-2);bannerParams.bottomMargin=design.dp(10);view.addView(banner,bannerParams);
            // Connected to a model outside the UT5 MAX layer: one banner; unmapped controls are hidden.
            readOnlyBanner=row();readOnlyBanner.setGravity(Gravity.CENTER_VERTICAL);readOnlyBanner.setPadding(design.dp(14),design.dp(4),design.dp(14),design.dp(4));readOnlyBanner.setMinimumHeight(design.dp(52));
            readOnlyBanner.setBackground(design.shape(Ui.withAlpha(design.accent,design.dark?36:22),16,Ui.withAlpha(design.accent,design.dark?100:80)));
            readOnlyBanner.addView(design.icon("lock",18,design.text),new LinearLayout.LayoutParams(design.dp(18),design.dp(18)));
            TextView readOnlyText=design.text(BleController.LIMITED_MODEL,13,false);LinearLayout.LayoutParams readOnlyWords=new LinearLayout.LayoutParams(0,-2,1);readOnlyWords.leftMargin=design.dp(10);readOnlyWords.rightMargin=design.dp(8);readOnlyBanner.addView(readOnlyText,readOnlyWords);
            identifyAction=pill("Identify",()->{if(scooterService!=null){scooterService.ble.identifyModel();toast(scooterService.ble.message());redraw();}});identifyAction.setContentDescription("Identify scooter model");identifyAction.setTag("scooter-identify-model");readOnlyBanner.addView(identifyAction);
            readOnlyBanner.setVisibility(View.GONE);view.addView(readOnlyBanner,new LinearLayout.LayoutParams(-1,-2));
            ((LinearLayout.LayoutParams)readOnlyBanner.getLayoutParams()).bottomMargin=design.dp(10);
            // An unfinished guided test (restoration outstanding) stays visible until the owner reviews it.
            testBanner=row();testBanner.setGravity(Gravity.CENTER_VERTICAL);testBanner.setPadding(design.dp(14),design.dp(4),design.dp(14),design.dp(4));testBanner.setMinimumHeight(design.dp(52));
            testBanner.setBackground(design.shape(Ui.withAlpha(design.accent,design.dark?36:22),16,Ui.withAlpha(design.accent,design.dark?100:80)));
            testBanner.addView(design.icon("lab",18,design.text),new LinearLayout.LayoutParams(design.dp(18),design.dp(18)));
            testBannerText=design.text("Unfinished test",13,false);LinearLayout.LayoutParams testWords=new LinearLayout.LayoutParams(0,-2,1);testWords.leftMargin=design.dp(10);testWords.rightMargin=design.dp(8);testBanner.addView(testBannerText,testWords);
            testReviewAction=pill("Review",()->openMoreSection("Compatibility"));testReviewAction.setContentDescription("Review unfinished test");testReviewAction.setTag("scooter-review-test");testBanner.addView(testReviewAction);
            testBanner.setVisibility(View.GONE);view.addView(testBanner,new LinearLayout.LayoutParams(-1,-2));
            ((LinearLayout.LayoutParams)testBanner.getLayoutParams()).bottomMargin=design.dp(10);
            // Wide: the banner spans both columns; search, results and top speed lead the left column.
            leftPane=expanded?column():null;rightPane=expanded?column():null;
            LinearLayout lead=expanded?leftPane:view;
            search=new EditText(MainActivity.this);search.setSingleLine(true);search.setHint("Search");design.styleSearch(search);search.setContentDescription("Search scooter settings");lead.addView(search,design.searchLayout());
            SearchFocus.bind(search, getWindow());
            results=design.caption("");results.setPadding(design.dp(3),0,0,design.dp(8));lead.addView(results);
            if(expanded)view.addView(new TwoPane(leftPane,rightPane),new LinearLayout.LayoutParams(-1,-2));
            topSpeed=new TopSpeedCard();lead.addView(topSpeed.card);
            for(String name:new String[]{"Ride","Battery","Lights","Line lights","Star lights","Dashboard & sound","Security","Profile"}){
                LinearLayout section=column();TextView label=design.sectionLabel(name);section.addView(label);
                LinearLayout body=design.groupCard();section.addView(body);
                sections.put(name,section);bodies.put(name,body);labels.put(name,label);if(!expanded)view.addView(section);
            }
            placeSections();
            ArrayList<ScooterCommands.Feature> ordered=new ArrayList<>();
            String[] priority={"cruise_control","energy_recovery","traction_control","hill_start_assist","parking_assist","hill_descent_control","start_speed",
                    "charge_limit","low_power","timed_charging","charge_start","auto_sensor","headlight","display_units","range_algorithm","turn_sound","lock_expiry"};
            for(String key:priority)ordered.add(ScooterCommands.find(key));
            for(ScooterCommands.Feature f:ScooterCommands.catalog())if(!java.util.Arrays.asList(priority).contains(f.key))ordered.add(f);
            // Light controls only other models have; hidden unless the connected model maps them.
            ordered.addAll(ScooterCommands.oemOnly());
            for(ScooterCommands.Feature f:ordered){
                // Lock, PIN and proximity stay out of this list. Lab (experimental) controls get a row that shows only
                // where another model maps them normally; maximum_speed's row replaces the top speed card the same way.
                if(f.key.equals("lock")||f.key.equals("pin")||f.key.equals("proximity_key"))continue;
                ControlRow control=new ControlRow(f);rows.add(control);addRow(groupOf(f),control.card);
                if(f.key.equals("timed_charging")||f.key.equals("charge_start")){
                    ChargeDoneRow done=new ChargeDoneRow(f.key.equals("charge_start"));chargeRows.add(done);
                    addRow("Battery",done.row.card);
                    extras.put(done.row.card,new String[]{"Battery","charge done by finish charging deadline time"});
                }
            }
            // Lights page: a read-only first row says the front headlight is switched on the scooter itself.
            SettingLine headlight=new SettingLine(design.featureIcon("headlight"),"Headlight",null);headlight.value.setText("Power button");
            headlight.line.setFocusable(true);headlight.line.setContentDescription("Headlight, switched with the scooter's power button");
            headlightRow=headlight.card;LinearLayout lightsBody=bodies.get("Lights");
            if(lightsBody.getChildCount()>0){View line=new View(MainActivity.this);line.setBackgroundColor(design.raised);line.setTag(lightsBody.getChildAt(0));lightsBody.addView(line,0,new LinearLayout.LayoutParams(-1,design.dp(1)));}
            lightsBody.addView(headlightRow,0);extras.put(headlightRow,new String[]{"Lights","headlight front lamp power button"});
            SettingLine zones=new SettingLine("star","Line & star lights",null);zones.chevron(this::showLights);zonesValue=zones.value;zonesRow=zones.card;
            addRow("Lights",zonesRow);extras.put(zonesRow,new String[]{"Lights","line star lights zones ambient pattern color boost"});
            clockLine=new SettingLine("history","Scooter clock",null);
            clockSpinner=new Spinner();clockSpinner.setVisibility(View.GONE);clockLine.line.addView(clockSpinner,rowSpinnerSize());
            clockButton=pill("Sync",()->{if(scooterService!=null){scooterService.ble.syncClock();redraw();}});clockButton.setContentDescription("Sync scooter clock");
            LinearLayout.LayoutParams clockParams=new LinearLayout.LayoutParams(-2,-2);clockParams.leftMargin=design.dp(10);clockLine.line.addView(clockButton,clockParams);
            addRow("Battery",clockLine.card);extras.put(clockLine.card,new String[]{"Battery","sync scooter clock scheduled charging time timezone"});
            chargerLine=new SettingLine("battery","Charger for estimates",null);chargerLine.chevron(MainActivity.this::showChargerEstimateChoice);
            chargerLine.line.setTag("estimate-charger-setting");chargerLine.value.setTag("estimate-charger-value");
            addRow("Battery",chargerLine.card);extras.put(chargerLine.card,new String[]{"Battery","charger for estimates automatic auto stock high speed charging time remaining"});
            SettingLine security=new SettingLine("shield","PIN & proximity unlock",null);security.chevron(()->openMoreSection("Security"));
            addRow("Security",security.card);extras.put(security.card,new String[]{"Security","pin password proximity unlock pairing bluetooth bond security"});
            SettingLine identity=new SettingLine("pencil","Scooter name",null);identity.chevron(MainActivity.this::renameScooter);nameValue=identity.value;
            addRow("Profile",identity.card);extras.put(identity.card,new String[]{"Profile","scooter name rename profile"});
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();applyFilter();}public void afterTextChanged(Editable text){}});
            applyFilter();
        }
        private String groupOf(ScooterCommands.Feature f){
            if(f.key.startsWith("line_"))return "Line lights";if(f.key.startsWith("star_"))return "Star lights";
            if(f.key.equals("headlight")||f.key.equals("auto_sensor")||ScooterCommands.oemOnly(f.key))return "Lights";
            return f.group.equals("Ride")?"Ride":f.group.equals("Battery")?"Battery":f.group.equals("Security")?"Security":"Dashboard & sound";
        }
        /** The main page holds everything except the line and star zones; the Lights page holds only lighting. */
        private boolean onPage(String group){
            boolean zone=group.equals("Line lights")||group.equals("Star lights");
            return lights?zone||group.equals("Lights"):!zone;
        }
        /**
         * Wide screens only: moves each group into its column for the current page. Reading order stays the
         * phone order (left column, then right). Main page: Ride left, the rest right. Lights page: everything
         * up to Line lights left (headlights first), Star lights onward right; searches fill the same columns.
         */
        private void placeSections(){
            if(leftPane==null)return;
            for(LinearLayout section:sections.values())if(section.getParent() instanceof ViewGroup)((ViewGroup)section.getParent()).removeView(section);
            for(Map.Entry<String,LinearLayout> e:sections.entrySet()){
                String name=e.getKey();
                boolean left=lights?!(name.equals("Star lights")||name.equals("Dashboard & sound")||name.equals("Security")||name.equals("Profile")):name.equals("Ride");
                (left?leftPane:rightPane).addView(e.getValue());
            }
        }
        private void addRow(String group,View child){LinearLayout body=bodies.get(group);if(body.getChildCount()>0){View line=new View(MainActivity.this);line.setBackgroundColor(design.raised);line.setTag(child);body.addView(line,new LinearLayout.LayoutParams(-1,design.dp(1)));}body.addView(child);}
        private void applyFilter(){
            String needle=normalize(query);boolean searching=!needle.isEmpty();int count=0;
            java.util.HashSet<String> visible=new java.util.HashSet<>();
            for(ControlRow row:rows){String group=groupOf(row.feature);boolean match=rowOffered(row.feature.key)&&(searching?row.haystack.contains(needle):onPage(group));row.card.setVisibility(match?View.VISIBLE:View.GONE);if(match){count++;visible.add(group);}}
            boolean zones=false;for(ControlRow row:rows)zones|=(row.feature.key.startsWith("line_")||row.feature.key.startsWith("star_"))&&offered(row.feature.key);
            for(Map.Entry<View,String[]> e:extras.entrySet()){
                String[] metadata=e.getValue();
                boolean match=e.getKey()==zonesRow&&(lights||!zones)||e.getKey()==clockLine.card&&!offered("clock_sync")||e.getKey()==headlightRow&&!lights&&!searching?false:searching?normalize(metadata[1]).contains(needle):onPage(metadata[0]);
                if("charge-done-by".equals(e.getKey().getTag())&&!chargeDoneOffered())match=false;
                e.getKey().setVisibility(match?View.VISIBLE:View.GONE);if(match){count++;visible.add(metadata[0]);}
            }
            boolean speed=topSpeedCard()&&(searching?TOP_SPEED_WORDS.contains(needle):!lights);
            topSpeed.card.setVisibility(speed?View.VISIBLE:View.GONE);if(speed)count++;
            for(Map.Entry<String,LinearLayout> e:sections.entrySet()){
                e.getValue().setVisibility(visible.contains(e.getKey())?View.VISIBLE:View.GONE);
                LinearLayout body=bodies.get(e.getKey());boolean hasVisible=false;
                for(int i=0;i<body.getChildCount();i++){View child=body.getChildAt(i);if(child.getTag() instanceof View)child.setVisibility(hasVisible&&((View)child.getTag()).getVisibility()==View.VISIBLE?View.VISIBLE:View.GONE);else if(child.getVisibility()==View.VISIBLE)hasVisible=true;}
            }
            labels.get("Lights").setText((lights&&!searching?"Headlights":"Lights").toUpperCase(Locale.ROOT));
            lightsHeader.setVisibility(lights?View.VISIBLE:View.GONE);
            results.setVisibility(searching?View.VISIBLE:View.GONE);results.setText(count==0?"No matching settings":count+(count==1?" setting":" settings"));
        }
        /**
         * Settings rows show only controls this model has a known encoding for (UT5 MAX: all; a profile that
         * has never connected: all, unavailable). The range
         * estimate row is Volt's own source choice, so it always shows.
         */
        private boolean offered(String key){
            BleController ble=scooterService==null?null:scooterService.ble;
            if(!key.equals("clock_sync")&&ScooterCommands.find(key).experimental)return ordinaryOemRow(key);
            return ble==null||key.equals("range_algorithm")||ble.controlOffered(key);
        }
        /** The UT5 MAX top speed card (four fixed choices); another model's maximum speed table is an ordinary row. */
        private boolean topSpeedCard(){return offered("maximum_speed")&&oemVariant("maximum_speed")==null;}
        private boolean rowOffered(String key){return key.equals("maximum_speed")?offered(key)&&oemVariant(key)!=null:offered(key);}
        private void clearSearch(){
            if(search.getText().length()>0)search.setText("");search.clearFocus();
            android.view.inputmethod.InputMethodManager keyboard=getSystemService(android.view.inputmethod.InputMethodManager.class);
            if(keyboard!=null)keyboard.hideSoftInputFromWindow(search.getWindowToken(),0);
        }
        private void showLights(){Motion.page(scroll,1,()->{lights=true;preferences.edit().putString("scooter_section","Lights").apply();clearSearch();placeSections();applyFilter();refresh();if(scroll!=null)scroll.scrollTo(0,0);});}
        private void showMain(){Motion.page(scroll,-1,()->{lights=false;preferences.edit().putString("scooter_section","").apply();clearSearch();placeSections();applyFilter();refresh();if(scroll!=null)scroll.scrollTo(0,0);});}
        boolean back(){
            if(!query.isEmpty()){search.setText("");return true;}
            if(lights){showMain();return true;}
            return false;
        }
        private Integer featureNow(String key){
            BleController ble=scooterService==null?null:scooterService.ble;Integer live=ble==null?null:ble.featureValue(key);
            return live!=null?live:scooterService==null?null:scooterService.displayFeatures().get(key);
        }
        private String zonesSummary(){
            Integer line=featureNow("line_light"),star=featureNow("star_light");if(line==null&&star==null)return "2 zones";
            int on=(Integer.valueOf(1).equals(line)?1:0)+(Integer.valueOf(1).equals(star)?1:0);return on==0?"Off":on==2?"Both on":"1 on";
        }
        @Override public View view(){return view;}
        @Override public void close(){if(controlDialog!=null){controlDialog.dismiss();controlDialog=null;}choiceSheet=null;}
        @Override public void refresh(){
            for(ControlRow row:rows)row.refresh();
            for(ChargeDoneRow row:chargeRows)row.refresh();
            topSpeed.refresh();
            BleController ble=scooterService==null?null:scooterService.ble;boolean ready=ble!=null&&ble.ready();
            boolean connecting=ble!=null&&!ready&&(ble.connected()||ble.state()==BleController.State.CONNECTING||ble.state()==BleController.State.AUTHENTICATING
                    ||ble.state()==BleController.State.SCANNING||ble.state()==BleController.State.WAITING);
            banner.setVisibility(ready?View.GONE:View.VISIBLE);
            // Two words keep it clear the grey values are not live; the Connect pill says the rest.
            bannerText.setText(connecting?"Connecting…":"Last seen (disconnected)");
            bannerAction.setVisibility(connecting?View.GONE:View.VISIBLE);
            readOnlyBanner.setVisibility(ble!=null&&ble.limitedModel()?View.VISIBLE:View.GONE);
            java.util.List<TestJournal.Entry> unfinished=ble==null?java.util.Collections.<TestJournal.Entry>emptyList():ble.unresolvedTests();
            boolean recordsLost=ble!=null&&!ble.testRecordsHealthy();
            int elsewhere=ble==null?0:ble.unresolvedTestsElsewhere(),orphaned=ble==null?0:ble.orphanedTests().size();
            boolean testing=ble!=null&&ble.testing();
            boolean reviewNeeded=recordsLost||!unfinished.isEmpty()||elsewhere>0||orphaned>0||testing;
            testBanner.setVisibility(reviewNeeded?View.VISIBLE:View.GONE);
            if(reviewNeeded){
                String text=recordsLost?"Test records unavailable"
                        :!unfinished.isEmpty()?"Unfinished test · "+ScooterCommands.find(unfinished.get(0).feature).label+(unfinished.size()>1?" +"+(unfinished.size()-1):"")
                        :orphaned>0?"Unfinished test · removed scooter"
                        :elsewhere>0?"Unfinished test · another scooter":"Setting test in progress";
                if(!text.contentEquals(testBannerText.getText()))testBannerText.setText(text);
                testReviewAction.setContentDescription(recordsLost?"Review test records":testing?"Review setting test":"Review unfinished test");
            }
            identifyAction.setVisibility(ble!=null&&ble.modelUnidentified()?View.VISIBLE:View.GONE);
            // Rows follow the model's decisions; re-filter when the set of mapped controls changes.
            StringBuilder offered=new StringBuilder();for(ControlRow row:rows)offered.append(rowOffered(row.feature.key)?'1':'0');offered.append(offered("clock_sync")?'1':'0').append(topSpeedCard()?'1':'0');
            if(!offered.toString().equals(offeredKeys)){offeredKeys=offered.toString();applyFilter();}
            boolean clockPending=ble!=null&&"clock_sync".equals(ble.pending());
            CapabilityResolver.Decision clockDecision=ble==null?null:ble.decision("clock_sync");
            boolean clockReady=clockDecision!=null&&(clockDecision.available()||clockDecision.blockedByRide());clockButton.setEnabled(clockReady);
            clockLine.name.setTextColor(clockReady?design.text:design.muted);
            // Results are row state: a spinner while sending, then "Sent 14:02" (no clock readback exists) or a short failure.
            clockSpinner.setVisibility(clockPending?View.VISIBLE:View.GONE);
            String clock=ble==null?null:ble.commandResults().get("clock_sync");String chip=clockPending?null:resultChip(ble,"clock_sync");
            long sent=ble==null?0:ble.commandResultTime("clock_sync");
            boolean accepted=!clockPending&&clock!=null&&clock.startsWith("Accepted")&&sent>0;
            clockLine.value.setText(accepted?"Sent "+DisplayTime.clock(MainActivity.this,preferences,sent):chip!=null?chip:"");
            clockLine.value.setTextColor(chip!=null&&!accepted?design.danger:design.muted);
            clockLine.line.setContentDescription("Scooter clock"+(clockPending?", sending":accepted?", sent "+DisplayTime.clock(MainActivity.this,preferences,sent)+", not verified":chip!=null?", "+chip:""));
            zonesValue.setText(zonesSummary());
            ScooterProfiles.Profile profile=scooterService==null?null:scooterService.profiles.active();nameValue.setText(profile==null?"Not set":profile.name);
            boolean chargerEditable=profile!=null&&!scooterService.isChargeRecording();
            chargerLine.line.setEnabled(chargerEditable);chargerLine.name.setTextColor(chargerEditable?design.text:design.muted);
            // Name the family estimates actually use, and why when it is not the saved choice.
            ScooterService.ChargerBasis basis=scooterService==null?ScooterService.ChargerBasis.UNKNOWN:scooterService.chargerBasis();
            ChargeSessionStore.Charger effective=scooterService==null?null:scooterService.effectiveChargeCharger();
            String family=effective==ChargeSessionStore.Charger.HIGH_SPEED?"High-speed":"Stock";
            chargerLine.value.setText(effective==null?"Automatic":basis==ScooterService.ChargerBasis.DETECTED?"Auto · "+family:family);
            chargerLine.setSub(basis==ScooterService.ChargerBasis.RECORDING?"Recording label · stop to change"
                    :basis==ScooterService.ChargerBasis.DETECTED?"Detected from power"
                    :scooterService!=null&&scooterService.isChargeRecording()?"Stop charge recording to change":null);
            if(choiceSheet!=null)choiceSheet.refresh();
        }
    }
    private static final String TOP_SPEED_WORDS=normalize("Top speed maximum speed sport mode limit mph km/h");
    private static String normalize(String text){return text==null?"":text.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+","");}
    /** Dashboard actions reuse the normal control dispatch and its live-state safety checks. */
    private void quickAction(String id) {
        QuickAccessActions.Entry entry = QuickAccessActions.find(id);
        if (entry == null) return;
        if (id.equals("stats") || id.equals("rides")) { select(id); return; }
        if (id.equals("charge_done_by")) { toggleChargeDoneBy(); return; }
        if (id.equals("charge_done_time")) { showChargeDoneTime(); return; }
        if (id.equals("feature:timed_charging")) { toggleManualCharge(); return; }
        if (id.equals("feature:charge_start")) { showChargingTime(); return; }
        if (scooterService == null) { toast("Connect to your scooter first."); return; }
        if (id.equals("lights")) { showQuickSettings("Lights"); return; }
        if (id.equals("controls")) { showQuickSettings("Settings"); return; }
        if (id.startsWith("more:")) { showQuickMore(id.substring(5), null); return; }
        if (id.startsWith("section:")) {
            String section = id.substring(8);
            if (section.equals("Security")) showQuickMore("Security", null);
            else if (section.equals("Profile")) renameScooter();
            else showQuickSettings(section);
            return;
        }
        ScooterCommands.Feature feature = entry.feature();
        if (feature == null) return;
        if (feature.key.equals("range_algorithm")) { closeQuickMenu(); showRangeSource(); return; }
        // The same resolver as the settings rows and the write gate: an unmapped control says why.
        CapabilityResolver.Decision decision = scooterService.ble.decision(feature.key);
        if (decision.support != CapabilityResolver.Support.KNOWN_ENCODING) { toast(decision.reasonText()); return; }
        if (decision.variant != null && decision.variant.lab) { showQuickMore("Lab", feature); return; }
        // Another model's region, firmware or full-ID rule refuses it: say why, as its Scooter row does (never on the UT5 MAX).
        if (decision.reason == CapabilityResolver.Reason.REGION_RULE || decision.reason == CapabilityResolver.Reason.FIRMWARE_UNKNOWN
                || decision.reason == CapabilityResolver.Reason.FIRMWARE_OLD || decision.reason == CapabilityResolver.Reason.FULL_PID_NEEDED) {
            toast(decision.reasonText()); return;
        }
        if (feature.sensitive || feature.key.equals("proximity_key")) {
            showQuickMore("Security", null); return;
        }
        BleController ble = scooterService.ble;
        if (!ble.ready()) { toast("Connect to change this setting."); return; }
        if (ble.pending() != null) { toast("Wait for the current scooter change."); return; }
        boolean fresh = feature.type == ScooterCommands.Type.TIME
                ? scooterService.readingFresh("scheduled_charge_time_raw")
                : scooterService.featureFresh(feature.key);
        Integer actual = ble.featureValue(feature.key);
        if (!fresh || (feature.type != ScooterCommands.Type.TIME && actual == null)) {
            toast("No fresh value yet"); return;
        }
        if (entry.directToggle(decision)) {
            int target = actual == 1 ? 0 : 1;
            confirmChange(feature, target, () -> {
                if (!ble.set(feature.key, target)) toast(ble.message());
                // The stable window survives a synchronous readback/redraw. Answer the
                // accepted tap once, using its requested direction, before rebuilding Home.
                else Haptics.toggle(getWindow().getDecorView(), target == 1);
                redraw();
            });
        } else {
            closeQuickMenu();
            if (controlDialog != null) { controlDialog.dismiss(); controlDialog = null; }
            editFeature(feature);
        }
    }

    private void closeQuickMenu() {
        AlertDialog old = quickMenuDialog; quickMenuDialog = null;
        MoreScreen oldMore = quickMoreScreen; quickMoreScreen = null;
        quickMenuRows.clear();
        quickChargeRows.clear();
        if (oldMore != null) oldMore.close();
        if (old != null) old.dismiss();
    }

    /** A named scooter settings group shown above Home, keeping the underlying layout untouched. */
    private void showQuickSettings(String section) {
        closeQuickMenu();
        if (controlDialog != null) { controlDialog.dismiss(); controlDialog = null; }
        final AlertDialog[] self = new AlertDialog[1];
        LinearLayout body = sheetContent(section.equals("Settings") ? "Scooter settings" : section,
                null, () -> { if (self[0] != null) self[0].dismiss(); });
        body.setTag("quick-settings-sheet");
        LinearLayout settings = design.groupCard(); body.addView(settings);
        ArrayList<ScooterCommands.Feature> features = new ArrayList<>(ScooterCommands.catalog());
        // Another model's own light controls join the Lights sheet only where it maps them.
        for (ScooterCommands.Feature f : ScooterCommands.oemOnly()) if (scooterService.ble.controlOffered(f.key)) features.add(f);
        for (ScooterCommands.Feature f : features) {
            if (f.experimental && !ordinaryOemRow(f.key) || f.sensitive || f.key.equals("lock") || f.key.equals("proximity_key")) continue;
            boolean match = section.equals("Settings")
                    || section.equals("Lights") && (f.key.equals("headlight") || f.key.equals("auto_sensor") || ScooterCommands.oemOnly(f.key)
                            || f.key.startsWith("line_") || f.key.startsWith("star_"))
                    || section.equals("Line lights") && f.key.startsWith("line_")
                    || section.equals("Star lights") && f.key.startsWith("star_")
                    || section.equals("Dashboard & sound") && (f.group.equals("Preferences")
                            || f.key.equals("turn_sound"))
                    || section.equals(f.group);
            if (!match) continue;
            if (settings.getChildCount() > 0) design.divider(settings);
            ControlRow row = new ControlRow(f); quickMenuRows.add(row);
            settings.addView(row.card); row.refresh();
            if((f.key.equals("timed_charging")||f.key.equals("charge_start"))&&chargeDoneOffered()){
                design.divider(settings);ChargeDoneRow done=new ChargeDoneRow(f.key.equals("charge_start"));
                quickChargeRows.add(done);settings.addView(done.row.card);done.refresh();
            }
        }
        if (section.equals("Settings")) {
            for (String id : new String[]{"section:Security", "section:Profile", "more:App"}) {
                QuickAccessActions.Entry shortcut = QuickAccessActions.find(id);
                design.divider(settings);
                SettingLine row = new SettingLine(shortcut.icon, shortcut.label, null);
                row.chevron(() -> quickAction(id)); settings.addView(row.card);
            }
        }
        AlertDialog dialog = showSheet(body); self[0] = dialog; quickMenuDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (quickMenuDialog == dialog) { quickMenuDialog = null; quickMenuRows.clear(); quickChargeRows.clear(); }
        });
    }

    /** Reuses the existing PIN/experimental approvals and application preferences in a temporary panel. */
    private void showQuickMore(String section, ScooterCommands.Feature inspect) {
        closeQuickMenu();
        if (controlDialog != null) { controlDialog.dismiss(); controlDialog = null; }
        final AlertDialog[] self = new AlertDialog[1];
        String title = section.equals("Security") ? "PIN & proximity unlock"
                : section.equals("Lab") ? "Experimental controls"
                : section.equals("App") ? "App preferences"
                : section.equals("Scooters") ? "My scooters"
                : section.equals("Compatibility") ? "Compatibility"
                : section.equals("Data") ? "Data & history" : "About & permissions";
        LinearLayout body = sheetContent(title, null,
                () -> { if (self[0] != null) self[0].dismiss(); });
        body.setTag("quick-more-sheet");
        MoreScreen overlay = new MoreScreen(this, section);
        quickMoreScreen = overlay;
        body.addView(overlay.view(), new LinearLayout.LayoutParams(-1,
                Math.max(design.dp(200), Math.round(getResources().getDisplayMetrics().heightPixels * .64f))));
        AlertDialog dialog = showSheet(body); self[0] = dialog; quickMenuDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (quickMenuDialog == dialog) {
                quickMenuDialog = null; quickMoreScreen = null; overlay.close();
            }
        });
        if (inspect != null) overlay.inspectQuickFeature(inspect);
    }

    /** How long a failure chip outlives the first fresh readback that follows it. */
    private static final long RESULT_CHIP_MS=4000;
    /**
     * Short-lived row state for a command result: "Rejected" or "Not confirmed", or null. It clears once a fresh
     * readback arrives a few seconds after the result, so the row returns to showing the scooter's own value.
     */
    private String resultChip(BleController ble,String key){
        String evidence=ble==null?null:ble.commandResults().get(key);
        if(evidence==null||evidence.equals("Confirmed")||evidence.startsWith("Setting confirmed")||evidence.startsWith("Accepted; no clock"))return null;
        long at=ble.commandResultTime(key);
        // Saved results from earlier launches feed Command evidence only; chips are for this session's changes.
        if(at<=0)return null;
        if(ble.ready()&&System.currentTimeMillis()-ble.statusAge()>at+RESULT_CHIP_MS)return null;
        return evidence.startsWith("Rejected")?"Rejected":"Not confirmed";
    }
    private LinearLayout.LayoutParams rowSpinnerSize(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(design.dp(20),design.dp(20));p.setMarginStart(design.dp(10));return p;}
    /** A small red chip for a failed change; it sits on the row just before the value or switch. */
    private TextView failureChip(){
        TextView chip=design.text("",11.5f,true);chip.setTextColor(design.danger);chip.setMaxLines(1);
        chip.setPadding(design.dp(8),design.dp(2),design.dp(8),design.dp(3));chip.setBackground(design.shape(Ui.withAlpha(design.danger,design.dark?40:28),100,0));
        chip.setVisibility(View.GONE);return chip;
    }
    /** Phone-width labels; search still matches the catalog's full names and descriptions. */
    private String shortLabel(ScooterCommands.Feature f){
        if(f.key.equals("charge_start"))return "Charge start";
        if(f.key.equals("charge_limit"))return "Charge limit";
        // "Dashboard" also names the Home tab; this setting is the scooter's own display.
        if(f.key.equals("display_units"))return "Scooter display units";
        if(f.key.endsWith("_boost_color"))return "Boost color";
        if(f.key.endsWith("_pattern"))return "Pattern";
        if(f.key.endsWith("_color"))return "Color";
        return f.label;
    }
    private String rowDetail(ScooterCommands.Feature f){
        if(f.key.equals("parking_assist"))return "Boost + full throttle uphill in Sport";
        if(f.key.equals("low_power"))return "Limits speed below 20%";
        return null;
    }
    private String rowIcon(ScooterCommands.Feature f){
        if(f.key.equals("hill_start_assist"))return "walk";
        if(f.key.equals("charge_start")||f.key.equals("timed_charging"))return "clock";
        return design.featureIcon(f.key);
    }
    /** Picker sheets have no explanation line, except Lock duration's lock-out warning. */
    private String explanation(ScooterCommands.Feature f){
        return f.key.equals("lock_expiry")?f.description:null;
    }
    private boolean inlineChoice(ScooterCommands.Feature f){return f.type==ScooterCommands.Type.CHOICE&&(f.key.startsWith("line_")||f.key.startsWith("star_"));}
    /** Short value forms for the right edge of a row; pickers still list the full labels. */
    private String valueText(ScooterCommands.Feature feature,int value){
        if(feature.key.equals("range_algorithm") && scooterService != null && scooterService.followsRidingMode()) return AUTO_RANGE;
        if(feature.key.equals("display_units")&&(value==0||value==1))return value==0?"Miles":"Kilometers";
        String label=featureLabel(feature,value);
        if(feature.key.equals("lock_expiry"))return label.replace(" hours"," h");
        return label;
    }
    private android.graphics.drawable.RippleDrawable rowRipple(){
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,26)),null,design.shape(android.graphics.Color.WHITE,10,0));
    }
    /** Small red pill action; the inset keeps a 48dp touch target around the visible pill. */
    private TextView pill(String label,Runnable action){
        TextView pill=design.text(label,12.5f,true);pill.setGravity(Gravity.CENTER);
        pill.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{design.dim,design.onAccent}));
        pill.setPadding(design.dp(16),0,design.dp(16),0);pill.setMinHeight(design.dp(48));pill.setMinWidth(design.dp(64));
        android.graphics.drawable.StateListDrawable fill=new android.graphics.drawable.StateListDrawable();
        fill.addState(new int[]{-android.R.attr.state_enabled},design.shape(design.raised,100,0));
        fill.addState(new int[]{},design.shape(design.accent,100,0));
        pill.setBackground(new android.graphics.drawable.InsetDrawable(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(android.graphics.Color.WHITE,50)),fill,null),0,design.dp(7),0,design.dp(7)));
        pill.setContentDescription(label);pill.setClickable(true);pill.setFocusable(true);
        pill.setOnClickListener(v->{if(action!=null)action.run();});
        design.pressFeedback(pill);
        return pill;
    }
    /** One divided row inside a group card: boxed icon, label with optional sub-text, then a value, switch or action. */
    private final class SettingLine {
        final LinearLayout card=column(),line=row(),words=column();
        final TextView name,sub,value;
        SettingLine(String icon,String label,String detail){
            line.setGravity(Gravity.CENTER_VERTICAL);line.setBaselineAligned(false);line.setMinimumHeight(design.dp(60));line.setPadding(0,design.dp(8),0,design.dp(8));
            name=design.text(label,15,false);name.setMaxLines(2);design.decorate(name,icon);
            value=design.text("",14,false);value.setTextColor(design.muted);value.setGravity(Gravity.END);value.setMaxLines(1);
            value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout heading=new LinearLayout(MainActivity.this){
                @Override protected void onMeasure(int widthSpec,int heightSpec){
                    int available=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
                    boolean hasValue=value.getVisibility()!=View.GONE&&value.length()>0;
                    float titleWidth=android.text.Layout.getDesiredWidth(name.getText(),name.getPaint())+name.getCompoundPaddingLeft()+name.getCompoundPaddingRight();
                    float valueWidth=android.text.Layout.getDesiredWidth(value.getText(),value.getPaint())+value.getCompoundPaddingLeft()+value.getCompoundPaddingRight();
                    // The outer row has already reserved room for its switch, swatches,
                    // status and action. Decide from intrinsic text widths, independent
                    // of our previous orientation, and measure the chosen layout once.
                    boolean stack=hasValue&&MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED
                            &&Math.ceil(titleWidth)+Math.ceil(valueWidth)+design.dp(10)>available;
                    setOrientation(stack?VERTICAL:HORIZONTAL);
                    LinearLayout.LayoutParams np=(LinearLayout.LayoutParams)name.getLayoutParams();
                    np.width=stack?-1:0;np.weight=stack?0:1;
                    LinearLayout.LayoutParams vp=(LinearLayout.LayoutParams)value.getLayoutParams();
                    vp.width=stack?-1:-2;vp.setMarginStart(stack?name.getCompoundPaddingStart():hasValue?design.dp(10):0);
                    vp.topMargin=stack?design.dp(3):0;vp.resolveLayoutDirection(getLayoutDirection());
                    int gravity=(stack?Gravity.START:Gravity.END)|Gravity.TOP;if(value.getGravity()!=gravity)value.setGravity(gravity);
                    super.onMeasure(widthSpec,heightSpec);
                }
            };
            heading.setGravity(Gravity.CENTER_VERTICAL);heading.setBaselineAligned(false);
            heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));heading.addView(value,new LinearLayout.LayoutParams(-2,-2));
            words.addView(heading,new LinearLayout.LayoutParams(-1,-2));
            sub=design.caption("");sub.setPaddingRelative(icon==null||icon.isEmpty()?0:design.dp(42),design.dp(3),0,0);words.addView(sub);setSub(detail);
            line.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            card.addView(line);
        }
        void chevron(Runnable action){
            View chevron=design.icon("chevron",15,design.muted);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(design.dp(15),design.dp(15));cp.leftMargin=design.dp(6);line.addView(chevron,cp);
            line.setClickable(true);line.setFocusable(true);line.setBackground(rowRipple());line.setOnClickListener(v->action.run());
        }
        void setSub(String text){sub.setText(text==null?"":text);sub.setVisibility(text==null||text.isEmpty()?View.GONE:View.VISIBLE);}
    }
    private boolean chargeDoneOffered(){
        return scooterService==null||scooterService.ble==null
                ||scooterService.ble.controlOffered("timed_charging")&&scooterService.ble.controlOffered("charge_start");
    }
    private void scheduleResult(String message){
        if(destroyed)return;
        if(message!=null)toast(message);
        redraw();
    }
    private String doneByActionBlocked(ChargeScheduleController.View charge){
        if(!charge.doneByEnabled)return charge.doneByBlockedReason;
        if(scooterService==null||!scooterService.ble.ready())return "Connect your scooter first";
        return charge.busy?"Wait for the current scooter change":null;
    }
    private void toggleChargeDoneBy(){toggleChargeDoneBy(true);}
    private void toggleChargeDoneBy(boolean feedback){
        if(scooterService==null){toast("Connect a charger first");return;}
        if(scooterService.isChargeMeasuring()){showChargeMeasure();return;}
        ChargeScheduleController.View charge=scooterService.chargeSchedule();
        String blocked=doneByActionBlocked(charge);if(blocked!=null){toast(blocked);return;}
        boolean enable=!charge.doneByEnabled;
        if(enable&&scooterService.chargeMeasureOffered()){offerChargeMeasure();return;}
        scooterService.setChargeDoneByEnabled(enable,this::scheduleResult);
        if(feedback)Haptics.toggle(getWindow().getDecorView(),enable);redraw();
    }
    /** A bounded connection is the owner's choice: ask, and say what it does, before measuring. */
    private void offerChargeMeasure(){
        boolean background=preferences.getBoolean("background_connection",false);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle("Measure this charge first?")
                .setMessage("Volt didn't see this charge begin, so it would plan for a fresh charger, which usually finishes early. "
                        +"Watching the charger for about 10 minutes lets it plan closer to your time, still with the 20-minute buffer. "
                        +"Volt stays connected"+(background?"":", even in the background,")+" and shows a notification until the schedule is set.")
                .setPositiveButton("Measure first",(d,which)->{
                    if(scooterService==null)return;
                    // Conditions can change while the dialog is open; never leave the request unanswered.
                    if(scooterService.startChargeMeasure())toast("Measuring · Charge done by is set within "+scooterService.chargeMeasureMinutesLeft()+" min");
                    else scooterService.setChargeDoneByEnabled(true,this::scheduleResult);
                    redraw();
                })
                .setNegativeButton("Schedule now",(d,which)->{if(scooterService!=null)scooterService.setChargeDoneByEnabled(true,this::scheduleResult);redraw();})
                .create();
        controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    private void showChargeMeasure(){
        if(scooterService.chargeMeasureArming()){toast("Setting the schedule…");return;}
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle("Measuring this charge")
                .setMessage("Charge done by is set automatically within "+scooterService.chargeMeasureMinutesLeft()+" min. "
                        +"Schedule now plans with what Volt knows so far. Stop measuring leaves Charge done by off.")
                .setPositiveButton("Keep measuring",null)
                .setNeutralButton("Schedule now",(d,which)->{if(scooterService!=null)scooterService.scheduleChargeNow();redraw();})
                .setNegativeButton("Stop measuring",(d,which)->{if(scooterService!=null)scooterService.cancelChargeMeasure();redraw();})
                .create();
        controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    private void toggleManualCharge(){toggleManualCharge(true);}
    private void toggleManualCharge(boolean feedback){
        if(scooterService==null){toast("Connect your scooter first");return;}
        ChargeScheduleController.View charge=scooterService.chargeSchedule();
        if(charge.manualBlockedReason!=null){toast(charge.manualBlockedReason);return;}
        boolean enable=!charge.manualEnabled;
        scooterService.setManualChargeEnabled(enable,this::scheduleResult);
        if(feedback)Haptics.toggle(getWindow().getDecorView(),enable);redraw();
    }
    private void showChargeDoneTime(){
        if(scooterService==null){toast("Select a scooter first");return;}
        ChargeScheduleController.View charge=scooterService.chargeSchedule();
        if(charge.timeBlockedReason!=null){toast(charge.timeBlockedReason);return;}
        final String profile=scooterService.profiles.active()==null?"":scooterService.profiles.active().id;
        TimePickerDialog picker=new TimePickerDialog(dialogContext(),(view,hour,minute)->{
            if(scooterService==null||scooterService.profiles.active()==null
                    ||!profile.equals(scooterService.profiles.active().id)){toast("The selected scooter changed");return;}
            boolean deferred=scooterService.chargeSchedule().doneByEnabled&&!scooterService.ble.ready();
            scooterService.setChargeDoneByTime(hour,minute,result->{
                if(result==null&&deferred)toast("Saved for next time");
                scheduleResult(result);
            });redraw();
        },charge.doneByMinuteOfDay/60,charge.doneByMinuteOfDay%60,DisplayTime.is24Hour(this,preferences));
        picker.setTitle("Charge done by");controlDialog=picker;picker.show();
        styleDialogButton(picker.getButton(AlertDialog.BUTTON_POSITIVE),design.accent);
        styleDialogButton(picker.getButton(AlertDialog.BUTTON_NEGATIVE),design.muted);
    }
    /** Local charge planning uses the same row and picker language as scooter-owned settings. */
    private final class ChargeDoneRow {
        final SettingLine row;final Switch toggle;final boolean time;final View spinner;
        ChargeDoneRow(boolean time){
            this.time=time;row=new SettingLine("clock","Charge done by",null);row.card.setTag("charge-done-by");
            row.line.setTag(time?"charge-done-time":"charge-done-toggle");
            spinner=new Spinner();spinner.setVisibility(View.GONE);row.line.addView(spinner,rowSpinnerSize());
            if(time){toggle=null;row.chevron(MainActivity.this::showChargeDoneTime);}
            else{
                row.value.setVisibility(View.GONE);
                toggle=new Haptics.FeedbackSwitch(MainActivity.this);toggle.setShowText(false);toggle.setMinHeight(design.dp(48));
                toggle.setMinimumWidth(design.dp(54));design.styleSwitch(toggle);toggle.setContentDescription("Charge done by");
                toggle.setOnClickListener(v->{toggle.setChecked(scooterService!=null&&scooterService.chargeSchedule().doneByEnabled);toggleChargeDoneBy(false);});
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.leftMargin=design.dp(10);row.line.addView(toggle,p);
                row.line.setClickable(true);row.line.setFocusable(true);row.line.setBackground(rowRipple());
                row.line.setOnClickListener(v->toggleChargeDoneBy());
            }
        }
        void refresh(){
            ChargeScheduleController.View charge=scooterService==null?null:scooterService.chargeSchedule();
            String blocked=charge==null?(time?"Select a scooter first":"Connect a charger first"):time?charge.timeBlockedReason:doneByActionBlocked(charge);
            boolean measuring=!time&&scooterService!=null&&scooterService.isChargeMeasuring();if(measuring)blocked=null;
            boolean on=charge!=null&&charge.doneByEnabled;int minute=charge==null?8*60:charge.doneByMinuteOfDay;
            Calendar at=Calendar.getInstance();at.set(Calendar.HOUR_OF_DAY,minute/60);at.set(Calendar.MINUTE,minute%60);
            String value=time?DisplayTime.clock(MainActivity.this,preferences,at.getTimeInMillis())
                    :measuring?"Measuring · "+scooterService.chargeMeasureMinutesLeft()+" min":on?"On":"Off";
            if(!time)row.value.setVisibility(measuring?View.VISIBLE:View.GONE);
            row.name.setTextColor(blocked==null?design.text:design.muted);row.value.setText(value);
            row.line.setAlpha(blocked==null?1f:.5f);row.line.setEnabled(true);
            row.line.setContentDescription((time?"Charge done by time":"Charge done by")+", "+value+(blocked==null?"":", "+blocked));
            boolean pending=charge!=null&&charge.busy||measuring;spinner.setVisibility(pending?View.VISIBLE:View.GONE);
            if(toggle!=null){toggle.setChecked(on);toggle.setEnabled(blocked==null&&!pending);toggle.setClickable(blocked==null&&!pending);toggle.setFocusable(blocked==null&&!pending);toggle.setVisibility(pending?View.GONE:View.VISIBLE);}
        }
    }
    private final class ControlRow {
        final ScooterCommands.Feature feature;final LinearLayout card;final SettingLine row;final TextView value,name,result;final Switch toggle;final View action,spinner;
        final String haystack;final boolean inline;SegmentedSelector patternSelector;
        final LinkedHashMap<Integer,View> options=new LinkedHashMap<>();
        /** Colour swatches; another model with more colours adds its swatches on their own full-width line. */
        private LinearLayout swatchRow;private boolean wideSwatches;
        /** Moving while this setting needs the scooter parked: the row greys out and a tap says why. */
        private boolean parkedBlocked;
        ControlRow(ScooterCommands.Feature feature){
            this.feature=feature;inline=inlineChoice(feature);
            String detail=rowDetail(feature);
            row=new SettingLine(inline?null:rowIcon(feature),shortLabel(feature),detail);card=row.card;name=row.name;value=row.value;action=row.line;
            haystack=normalize(feature.searchText()+" "+shortLabel(feature)+" "+feature.key+" "+(detail==null?"":detail));
            if(feature.key.equals("range_algorithm"))action.setTag("scooter-range-estimate");
            // Result state lives on the row line: a red chip for a failed change, a spinner while one is pending.
            result=failureChip();LinearLayout.LayoutParams chipSize=new LinearLayout.LayoutParams(-2,-2);chipSize.setMarginStart(design.dp(8));row.line.addView(result,1,chipSize);
            spinner=new Spinner();spinner.setVisibility(View.GONE);row.line.addView(spinner,2,rowSpinnerSize());
            if(feature.type==ScooterCommands.Type.TOGGLE){
                value.setVisibility(View.GONE);
                toggle=new Haptics.FeedbackSwitch(MainActivity.this);toggle.setShowText(false);toggle.setTextOn("");toggle.setTextOff("");toggle.setMinHeight(design.dp(48));toggle.setMinimumWidth(design.dp(54));design.styleSwitch(toggle);
                toggle.setContentDescription(shortLabel(feature));
                toggle.setOnClickListener(v->{if(scooterService!=null){if(feature.key.equals("timed_charging")){toggle.setChecked(scooterService.chargeSchedule().manualEnabled);toggleManualCharge(false);return;}int target=toggle.isChecked()?1:0;Integer old=scooterService.ble.featureValue(feature.key);toggle.setChecked(old!=null&&old==1);confirmChange(feature,target,()->{scooterService.ble.set(feature.key,target);redraw();});}});
                LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=design.dp(10);row.line.addView(toggle,tp);
                row.line.setFocusable(true);row.line.setBackground(rowRipple());row.line.setOnClickListener(v->{if(feature.key.equals("timed_charging"))toggleManualCharge();else if(parkedBlocked)toast("Stop to change");else if(toggle.isEnabled())toggle.performClick();});
            }else if(inline){
                toggle=null;value.setVisibility(View.GONE);
                if(feature.key.endsWith("_pattern")){
                    // Firmware readback owns the selected value, including while a write is pending.
                    row.line.setMinimumHeight(design.dp(40));row.line.setPadding(0,design.dp(10),0,0);
                    patternSelector=new SegmentedSelector(design,feature.label,feature.options,index->{
                        restorePatternSelection(false);
                        chooseInline(feature,feature.optionValues[index]);
                    });
                    patternSelector.setWrapChoices(true);
                    patternSelector.setTag("scooter-selector-"+feature.key);
                    LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.topMargin=design.dp(6);pp.bottomMargin=design.dp(8);card.addView(patternSelector,pp);
                }else{
                    // Colours are swatches on the right of the row.
                    LinearLayout swatches=row();swatches.setGravity(Gravity.CENTER_VERTICAL);swatchRow=swatches;
                    for(int i=0;i<feature.optionValues.length;i++){
                        final int choice=feature.optionValues[i];String label=feature.options[i];
                        Swatch swatch=new Swatch(label.equals("Red")?design.accent:label.equals("Ice blue")?design.blue:design.muted);
                        swatch.setContentDescription(shortLabel(feature)+" "+label);swatch.setOnClickListener(v->chooseInline(feature,choice));
                        options.put(choice,swatch);swatches.addView(swatch,new LinearLayout.LayoutParams(design.dp(48),design.dp(48)));
                    }
                    row.line.addView(swatches);
                }
            }else{toggle=null;row.chevron(()->{if(feature.key.equals("charge_start"))showChargingTime();else if(parkedBlocked)toast("Stop to change");else editFeature(feature);});}
        }
        void refresh(){
            if(feature.key.equals("timed_charging")||feature.key.equals("charge_start")){refreshChargeSchedule();return;}
            if(feature.key.equals("range_algorithm")){
                boolean editable=scooterService!=null&&scooterService.profiles.active()!=null;
                String source=editable&&scooterService.usesLearnedRange()?"Volt (learned)":"Scooter";
                parkedBlocked=false;name.setTextColor(editable?design.text:design.muted);action.setEnabled(editable);
                row.line.setAlpha(1f);value.setText(source);value.setTextColor(design.muted);row.setSub(null);
                spinner.setVisibility(View.GONE);result.setVisibility(View.GONE);
                action.setContentDescription("Range estimate, "+source+", opens choices");return;
            }
            BleController ble=scooterService==null?null:scooterService.ble;Integer liveActual=ble==null?null:ble.featureValue(feature.key);Integer actual=liveActual!=null?liveActual:scooterService==null?null:scooterService.displayFeatures().get(feature.key);
            // The same decision admits the write; riding keeps a parked-only row tappable to say "Stop to change".
            CapabilityResolver.Decision decision=ble==null?null:ble.decision(feature.key);boolean parked=decision!=null&&decision.blockedByRide();
            boolean decided=decision!=null&&(decision.available()||parked);
            boolean available=decided&&liveActual!=null&&scooterService.featureFresh(feature.key);
            String shown;
            if(feature.type==ScooterCommands.Type.TIME){Double seconds=ble==null?null:ble.telemetry.number("scheduled_charge_time_raw");if(seconds==null&&scooterService!=null){TelemetryHistory.Reading cached=scooterService.displayReadings().get("scheduled_charge_time_raw");if(cached!=null)seconds=cached.number;}available=decided&&seconds!=null&&scooterService.readingFresh("scheduled_charge_time_raw");shown=seconds==null?"Not reported":seconds<=0?"Not scheduled":DisplayTime.clock(MainActivity.this,preferences,(long)(seconds*1000)-TimeZone.getDefault().getOffset(System.currentTimeMillis()));}
            else shown=actual==null?"Not reported":valueText(feature,actual);
            boolean choices=feature.type!=ScooterCommands.Type.CHOICE||(ble!=null&&ble.featureChoices(feature.key).length>0);
            // Parked-only settings grey out while the scooter moves; the row stays tappable to say "Stop to change".
            parkedBlocked=available&&choices&&parked;
            String note=rowNote(ble,decision,parked);
            boolean usable=available&&choices&&!parkedBlocked;
            name.setTextColor(usable?design.text:design.muted);action.setEnabled(available&&choices);
            row.line.setAlpha(parkedBlocked?.45f:1f);
            if(toggle==null&&!inline){
                // A long value moves under the label so the row never wraps beside the chevron.
                // Another model's caption joins a long time value under the label too, so neither is squeezed (never on the UT5 MAX).
                boolean captioned=note!=null&&decision!=null&&decision.layer!=ModelRegistry.Layer.UT5_MAX;
                boolean wide=(feature.type!=ScooterCommands.Type.TIME||captioned)&&shown.length()>10;value.setText(wide?"":shown);row.setSub(joinNote(wide?shown:rowDetail(feature),note));
            }else if(inline)row.setSub(actual==null?"Not reported":note);
            else row.setSub(joinNote(rowDetail(feature),note));
            if(patternSelector!=null){restorePatternSelection(true);patternSelector.setEnabled(usable);patternSelector.setAlpha(usable?1f:.5f);}
            if(swatchRow!=null)extraSwatches(decision);
            for(Map.Entry<Integer,View> option:options.entrySet()){
                boolean on=actual!=null&&actual.intValue()==option.getKey();View view=option.getValue();
                ((Swatch)view).set(on);
                view.setEnabled(usable);view.setAlpha(usable?1f:.5f);
                if(Build.VERSION.SDK_INT>=30)view.setStateDescription(on?"Selected":"");
            }
            // Short-lived result state: a spinner in the switch slot while pending, then a red chip if it failed.
            boolean pending=ble!=null&&feature.key.equals(ble.pending());
            String chip=pending?null:resultChip(ble,feature.key);
            spinner.setVisibility(pending?View.VISIBLE:View.GONE);
            result.setText(chip==null?"":chip);result.setVisibility(chip==null?View.GONE:View.VISIBLE);
            // TalkBack reads the name and value, plus any short state.
            action.setContentDescription(shortLabel(feature)+", "+shown+(pending?", confirming":chip!=null?", "+chip.toLowerCase(Locale.ROOT):parkedBlocked?", stop to change":note!=null?", "+note.toLowerCase(Locale.ROOT):""));
            if(toggle!=null){toggle.setVisibility(pending?View.GONE:View.VISIBLE);toggle.setEnabled(usable);toggle.setChecked(actual!=null&&actual==1);if(Build.VERSION.SDK_INT>=30)toggle.setStateDescription(actual==null?"Not reported":actual==1?"On":"Off");}
        }
        private void refreshChargeSchedule(){
            ChargeScheduleController.View charge=scooterService==null?null:scooterService.chargeSchedule();
            BleController ble=scooterService==null?null:scooterService.ble;
            CapabilityResolver.Decision decision=ble==null?null:ble.decision(feature.key);
            String note=rowNote(ble,decision,decision!=null&&decision.blockedByRide());
            String blocked=charge==null?"Connect your scooter first":toggle==null?charge.manualTimeBlockedReason:charge.manualBlockedReason;
            boolean pending=charge!=null&&charge.busy,on=charge!=null&&charge.manualEnabled;
            String shown=toggle!=null?(on?"On":"Off"):charge==null||charge.manualStartWallMs<=0?"Not scheduled"
                    :DisplayTime.clock(MainActivity.this,preferences,charge.manualStartWallMs);
            name.setTextColor(blocked==null?design.text:design.muted);value.setText(shown);row.setSub(note);
            action.setEnabled(true);row.line.setAlpha(blocked==null?1f:.5f);parkedBlocked=false;
            spinner.setVisibility(pending?View.VISIBLE:View.GONE);result.setVisibility(View.GONE);
            action.setContentDescription(shortLabel(feature)+", "+shown+(blocked==null?"":", "+blocked)
                    +(note==null||note.equals(blocked)?"":", "+note));
            if(toggle!=null){toggle.setVisibility(pending?View.GONE:View.VISIBLE);toggle.setChecked(on);toggle.setEnabled(blocked==null&&!pending);toggle.setClickable(blocked==null&&!pending);toggle.setFocusable(blocked==null&&!pending);}
        }
        /**
         * Short caption for a connected row: why the control is refused (not the transient states the row
         * already shows), else "Untested here" for an OEM-mapped control on another model. None on the UT5 MAX.
         */
        private String rowNote(BleController ble,CapabilityResolver.Decision d,boolean parked){
            if(ble==null||d==null||!ble.ready())return null;
            if(!d.available()&&!parked&&d.reason!=CapabilityResolver.Reason.BUSY&&d.reason!=CapabilityResolver.Reason.FIELD_ABSENT
                    &&d.reason!=CapabilityResolver.Reason.NOT_CONNECTED&&d.reason!=CapabilityResolver.Reason.STATUS_STALE)return d.reasonText();
            return d.layer!=ModelRegistry.Layer.UT5_MAX&&d.evidence==CapabilityResolver.Evidence.OEM_MAPPED?"Untested here":null;
        }
        /** UT5 MAX swatches never change; colours beyond them (another model's table) show only while that model offers them. */
        private void extraSwatches(CapabilityResolver.Decision decision){
            int[] offered=decision==null||decision.variant==null||decision.variant.decode==FeatureVariant.Decode.UT5_FEATURE?new int[0]:decision.values();
            java.util.HashSet<Integer> base=new java.util.HashSet<>();for(int v:feature.optionValues)base.add(v);
            for(int v:offered)if(!options.containsKey(v)){
                if(!wideSwatches){
                    // Six colours do not fit beside the label: the swatches take their own line, sharing its width.
                    wideSwatches=true;row.line.removeView(swatchRow);
                    for(int i=0;i<swatchRow.getChildCount();i++)swatchRow.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(0,design.dp(48),1));
                    LinearLayout.LayoutParams wide=new LinearLayout.LayoutParams(-1,-2);wide.bottomMargin=design.dp(6);card.addView(swatchRow,wide);
                }
                final int choice=v;String label=decision.label(v);
                Swatch swatch=new Swatch(swatchColor(label));swatch.rainbow=label.equals("Rainbow");
                swatch.setContentDescription(shortLabel(feature)+" "+label);swatch.setOnClickListener(x->chooseInline(feature,choice));
                options.put(choice,swatch);swatchRow.addView(swatch,new LinearLayout.LayoutParams(0,design.dp(48),1));
            }
            for(Map.Entry<Integer,View> option:options.entrySet())if(!base.contains(option.getKey())){
                boolean shown=false;for(int v:offered)shown|=v==option.getKey();option.getValue().setVisibility(shown?View.VISIBLE:View.GONE);
            }
        }
        private String joinNote(String text,String note){return note==null?text:text==null||text.isEmpty()?note:text+" · "+note;}
        private void restorePatternSelection(boolean animate){
            Integer actual=scooterService==null?null:scooterService.ble.featureValue(feature.key);
            if(actual==null&&scooterService!=null)actual=scooterService.displayFeatures().get(feature.key);
            int selected=-1;for(int i=0;i<feature.optionValues.length;i++)if(actual!=null&&actual==feature.optionValues[i])selected=i;
            patternSelector.select(selected,animate);
        }
    }
    private void chooseInline(ScooterCommands.Feature feature,int target){
        if(scooterService==null)return;Integer actual=scooterService.ble.featureValue(feature.key);if(actual!=null&&actual==target)return;
        confirmChange(feature,target,()->{if(!scooterService.ble.set(feature.key,target))toast(scooterService.ble.message());redraw();});
    }
    /** Swatch colour for a lamp colour label (UT5 MAX: ice blue and red). */
    private int swatchColor(String label){
        switch(label){
            case "Red": return design.accent;
            case "Ice blue": return design.blue;
            case "Blue": return android.graphics.Color.parseColor("#3D6BE0");
            case "Green": return design.ok;
            case "Purple": return android.graphics.Color.parseColor("#8E5BD0");
            default: return design.muted;
        }
    }
    /** Lamp colour swatch; the selected colour gains a ring. */
    private final class Swatch extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int color;private boolean on;
        /** A multicolour lamp mode: drawn as a colour wheel. */
        boolean rainbow;
        Swatch(int color){super(MainActivity.this);this.color=color;setClickable(true);setFocusable(true);
            setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,40)),null,null));}
        void set(boolean selected){if(on!=selected){on=selected;invalidate();}setSelected(selected);}
        @Override public CharSequence getAccessibilityClassName(){return android.widget.RadioButton.class.getName();}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setCheckable(true);info.setChecked(on);}
        @Override protected void onDraw(Canvas canvas){
            float cx=getWidth()/2f,cy=getHeight()/2f;paint.setStyle(Paint.Style.FILL);paint.setColor(color);
            if(rainbow)paint.setShader(new android.graphics.SweepGradient(cx,cy,new int[]{0xFFE5484D,0xFFF5B83D,0xFF3DBE6B,0xFF3D6BE0,0xFF8E5BD0,0xFFE5484D},null));
            canvas.drawCircle(cx,cy,design.dp(10),paint);paint.setShader(null);
            if(on){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(design.dp(2));paint.setColor(design.text);canvas.drawCircle(cx,cy,design.dp(14),paint);}
        }
    }
    /** Four signal bars, filled up to a rank. Used by the top speed options and the scooter picker. */
    private final class SignalBars extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private int rank,color,off;
        SignalBars(int rank){super(MainActivity.this);this.rank=rank;setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void set(int rank,int color,int off){this.rank=rank;this.color=color;this.off=off;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            float bar=design.dp(3),gap=design.dp(2),h=getHeight(),x=(getWidth()-(4*bar+3*gap))/2;float[] heights={.34f,.58f,.82f,1f};
            for(int i=0;i<4;i++){paint.setColor(i<rank?color:off);canvas.drawRoundRect(new RectF(x,h-h*heights[i],x+bar,h),design.dp(1),design.dp(1),paint);x+=bar+gap;}
        }
    }
    /** Small rotating arc for waiting states. */
    private final class Spinner extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Spinner(){super(MainActivity.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            float stroke=design.dp(2);RectF arc=new RectF(stroke,stroke,getWidth()-stroke,getHeight()-stroke);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(design.raised);canvas.drawOval(arc,paint);
            paint.setColor(design.accent);canvas.drawArc(arc,Ui.motionEnabled()?(SystemClock.uptimeMillis()%1000)*.36f:0,100,false,paint);
            if(resumed&&isShown()&&Ui.motionEnabled())postInvalidateDelayed(32);
        }
    }
    /** Volt top speed card (ph/24): the current maximum in the rider's units and four raised choices. Commands stay in km/h. */
    private final class TopSpeedCard {
        final ScooterCommands.Feature feature=ScooterCommands.find("maximum_speed");
        final LinearLayout card=column(),options=row();
        final TextView title,amount,unit,status;
        final ArrayList<LinearLayout> buttons=new ArrayList<>();final ArrayList<TextView> numbers=new ArrayList<>();final ArrayList<SignalBars> bars=new ArrayList<>();
        private String styled="";
        /** The speed the rider tapped; its button pulses until the scooter confirms or the change ends. */
        private Integer requested;private View pulsing;private android.animation.ValueAnimator pulse;
        TopSpeedCard(){
            card.setPadding(design.dp(16),design.dp(14),design.dp(16),design.dp(16));
            LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.bottomMargin=design.dp(2);card.setLayoutParams(cardParams);
            // Naming Sport here states the "Sport mode only" fact without a separate line.
            title=design.text("Top speed · Sport",12.5f,false);title.setTypeface(design.medium());card.addView(title);
            LinearLayout reading=row();reading.setPadding(0,design.dp(8),0,design.dp(14));
            amount=design.text("—",42,true);amount.setIncludeFontPadding(false);reading.addView(amount);
            unit=design.text("",14,false);unit.setPadding(design.dp(8),0,0,0);reading.addView(unit);
            card.addView(reading);unit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            for(int i=0;i<feature.optionValues.length;i++){
                final int kmh=feature.optionValues[i];
                LinearLayout button=column();button.setGravity(Gravity.CENTER);button.setMinimumHeight(design.dp(60));button.setPadding(0,design.dp(8),0,design.dp(10));
                SignalBars signal=new SignalBars(i+1);button.addView(signal,new LinearLayout.LayoutParams(design.dp(18),design.dp(12)));
                TextView number=design.text("",16,true);number.setGravity(Gravity.CENTER);number.setPadding(0,design.dp(6),0,0);button.addView(number);
                button.setClickable(true);button.setFocusable(true);button.setOnClickListener(v->choose(v,kmh));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=design.dp(7);options.addView(button,p);
                buttons.add(button);numbers.add(number);bars.add(signal);
            }
            card.addView(options,new LinearLayout.LayoutParams(-1,-2));
            status=design.text("",12,false);status.setPadding(0,design.dp(10),0,0);status.setVisibility(View.GONE);card.addView(status);
        }
        private String number(int kmh,String units){return "km".equals(units)?Integer.toString(kmh):Long.toString(Math.round(kmh/1.609344));}
        /** Spoken form: the rounded figure the rider sees, plus the exact OEM km/h value it sends. */
        private String spoken(int kmh,String units){return "km".equals(units)?kmh+" kilometres per hour":number(kmh,units)+" miles per hour, "+kmh+" kilometres per hour";}
        private void choose(View source,int kmh){
            if(scooterService==null)return;BleController ble=scooterService.ble;
            Integer actual=ble.featureValue(feature.key);if(actual!=null&&actual==kmh)return;
            if(!ble.set(feature.key,kmh))toast(ble.message());else{requested=kmh;Haptics.tick(source);}redraw();
        }
        void refresh(){
            BleController ble=scooterService==null?null:scooterService.ble;
            Integer live=ble==null?null:ble.featureValue(feature.key);
            Integer actual=live!=null?live:scooterService==null?null:scooterService.displayFeatures().get(feature.key);
            boolean ready=ble!=null&&ble.ready();
            CapabilityResolver.Decision decision=ble==null?null:ble.decision(feature.key);
            boolean available=decision!=null&&(decision.available()||decision.blockedByRide())&&live!=null&&scooterService.featureFresh(feature.key)&&ble.featureChoices(feature.key).length>0;
            String units=preferences.getString("units","mi");
            amount.setText(actual==null?"—":number(actual,units));
            unit.setText("km".equals(units)?"km/h":"dual".equals(units)&&actual!=null?"mph · "+actual+" km/h":"mph");
            amount.setContentDescription("Top speed, Sport mode maximum, "+(actual==null?"not reported":spoken(actual,units))+(ready?"":", last seen setting"));
            for(int i=0;i<buttons.size();i++){
                int kmh=feature.optionValues[i];boolean on=actual!=null&&actual==kmh;
                numbers.get(i).setText(number(kmh,units));
                LinearLayout button=buttons.get(i);button.setEnabled(available);button.setSelected(on);
                button.setContentDescription("Set top speed to "+spoken(kmh,units));
                if(Build.VERSION.SDK_INT>=30)button.setStateDescription(on?"Selected":"");
            }
            boolean pending=ble!=null&&feature.key.equals(ble.pending());
            if(!pending)requested=null;
            int index=-1;for(int i=0;i<buttons.size();i++)if(requested!=null&&requested==feature.optionValues[i])index=i;
            pulse(pending&&index>=0?buttons.get(index):null);
            if(pending&&index>=0&&Build.VERSION.SDK_INT>=30)buttons.get(index).setStateDescription("Confirming");
            // Without motion the pulse cannot show, so a one-word status stands in for it.
            String feedback=pending?(Ui.motionEnabled()&&index>=0?null:"Confirming…"):resultChip(ble,feature.key);
            status.setText(feedback==null?"":feedback);status.setVisibility(feedback==null?View.GONE:View.VISIBLE);
            String state=ready+"|"+available+"|"+actual;
            if(!state.equals(styled)){styled=state;style(!ready,available,actual);}
        }
        /** Breathes the tapped speed button while the scooter confirms it; null stops and restores. */
        private void pulse(View target){
            if(target==pulsing)return;
            if(pulse!=null){pulse.cancel();pulse=null;}
            if(pulsing!=null)pulsing.setAlpha(1f);
            pulsing=target;
            if(target==null||!Ui.motionEnabled())return;
            pulse=android.animation.ValueAnimator.ofFloat(1f,.45f);pulse.setDuration(520);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.setInterpolator(new android.view.animation.PathInterpolator(.4f,0,.2f,1));
            pulse.addUpdateListener(a->{if(!target.isAttachedToWindow()){a.cancel();return;}target.setAlpha((Float)a.getAnimatedValue());});
            pulse.start();
        }
        /** Red gradient while live; a grey card when only last-seen values are known. */
        private void style(boolean grey,boolean available,Integer actual){
            int fg=grey?(design.dark?Ui.withAlpha(design.text,200):design.text):design.onAccent;
            if(grey)card.setBackground(design.shape(design.outline,22,0));
            else{android.graphics.drawable.GradientDrawable gradient=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{design.accent,design.accentDeep});gradient.setCornerRadius(design.dp(22));card.setBackground(gradient);}
            title.setTextColor(Ui.withAlpha(fg,220));amount.setTextColor(fg);unit.setTextColor(Ui.withAlpha(fg,220));status.setTextColor(Ui.withAlpha(fg,235));
            int selectedFill=design.dark?design.bg:design.text,selectedText=design.dark?design.text:design.bg;
            for(int i=0;i<buttons.size();i++){
                boolean on=actual!=null&&actual==feature.optionValues[i];
                int fill=on?selectedFill:Ui.withAlpha(android.graphics.Color.BLACK,grey?30:40);
                int ring=on?Ui.withAlpha(android.graphics.Color.WHITE,grey?70:150):Ui.withAlpha(fg,60);
                buttons.get(i).setBackground(raisedOption(fill,ring,on?2:1));
                int ink=on?selectedText:fg;numbers.get(i).setTextColor(ink);
                int barColor=on&&!grey?design.accent:ink;bars.get(i).set(i+1,barColor,Ui.withAlpha(barColor,90));
                if(buttons.get(i)!=pulsing)buttons.get(i).setAlpha(available||grey?1f:.6f);
            }
        }
        private android.graphics.drawable.Drawable raisedOption(int fill,int ring,int ringDp){
            android.graphics.drawable.GradientDrawable shadow=design.shape(Ui.withAlpha(android.graphics.Color.BLACK,40),12,0);
            android.graphics.drawable.GradientDrawable face=new android.graphics.drawable.GradientDrawable();face.setColor(fill);face.setCornerRadius(design.dp(12));face.setStroke(design.dp(ringDp),ring);
            android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{shadow,face});
            layers.setLayerInset(0,0,design.dp(2),0,0);layers.setLayerInset(1,0,0,0,design.dp(2));
            return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(android.graphics.Color.WHITE,50)),layers,null);
        }
    }
    // ---- Volt dialogs ------------------------------------------------------------------------------
    /** Rounded Volt surface and Saira buttons for the small AlertDialogs; only the primary action (if any) is red. */
    /** One offer per Model ID per app session (it survives Activity recreation), a few seconds after a connection settles; answers persist under ReportInvite.PREFIX. */
    private static final java.util.Set<String> reportInvited=java.util.Collections.synchronizedSet(new java.util.HashSet<>());private Dialog reportDialog;private long reportReadySinceMs;
    /** The offer's read-only check is running; onDestroy stops it (a dialog's dismiss listener runs too late). */
    private boolean reportChecking;
    private void maybeInviteReport(){
        BleController ble=scooterService==null?null:scooterService.ble;
        if(ble==null||!ble.ready()){reportReadySinceMs=0;return;}
        long now=SystemClock.elapsedRealtime();if(reportReadySinceMs==0)reportReadySinceMs=now;
        // The same guard as the check itself: never offer what Check & share would refuse (rides, charge capture or measurement, tests, pending changes).
        if(now-reportReadySinceMs<2500||onboarding!=null||!resumed||reportDialog!=null&&reportDialog.isShowing()
                ||controlDialog!=null&&controlDialog.isShowing()||!ble.discoveryAllowed())return;
        String model=ReportInvite.model(ble.descriptor());
        if(model.isEmpty()||reportInvited.contains(model)||!ReportInvite.due(preferences.getString(ReportInvite.PREFIX+model,""),System.currentTimeMillis()))return;
        reportInvited.add(model);showReportInvite(model);
    }
    /** Help Volt support this scooter: one tap starts the read-only check and opens "Which scooter is this?"; the email app then opens with the report to read before sending. */
    private void showReportInvite(String model){
        LinearLayout box=column();box.setPadding(design.dp(24),design.dp(4),design.dp(24),design.dp(4));
        box.addView(design.text("Volt hasn't seen a Model "+model+" before. A 30-second check reads the scooter without changing anything. Then your email app opens a short report you can read before you send it.",15,false));
        addressLine(box);
        TextView privacy=design.caption("No serial, MAC, owner key, PIN, names or routes are included.");privacy.setPadding(0,design.dp(6),0,0);box.addView(privacy);
        TextView status=design.caption("");status.setPadding(0,design.dp(10),0,0);status.setVisibility(View.GONE);status.setTag("report-invite-status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);box.addView(status);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setCustomTitle(dialogTitle("Help Volt support this scooter")).setView(scroll)
                .setPositiveButton("Check & share",null).setNegativeButton("Not now",null).setNeutralButton("Don't ask again",null).create();
        reportDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
        reportChecking=false;
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{preferences.edit().putString(ReportInvite.PREFIX+model,ReportInvite.later(System.currentTimeMillis())).apply();dialog.dismiss();});
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{preferences.edit().putString(ReportInvite.PREFIX+model,"never").apply();dialog.dismiss();});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String[] scooter={""};String problem=startReportCheck(scooter);
            if(problem!=null){status.setText(problem);status.setVisibility(View.VISIBLE);return;}
            dialog.dismiss();showReportPicker(model,scooter,null);
        });
        dialog.setOnDismissListener(d->{if(reportDialog==dialog)reportDialog=null;});
    }
    /** Starts the offer's read-only check on the active scooter (its id goes in scooter[0]); what stopped it, or null once it runs. */
    private String startReportCheck(String[] scooter){
        BleController ble=scooterService==null?null:scooterService.ble;
        if(ble==null||!ble.ready())return "Connect your scooter first.";
        ScooterProfiles.Profile checked=scooterService.profiles.active();scooter[0]=checked==null?"":checked.id;
        ble.startDiscovery();
        if(!ble.discovering())return ble.message();
        reportChecking=true;return null;
    }
    /** The compatibility checker's Email to Volt: the same sheet and email as the offer, for the check already finished. */
    @Override public void emailReport(CompatibilityReport report){
        if(report==null||scooterService==null||reportDialog!=null&&reportDialog.isShowing())return;
        ScooterProfiles.Profile p=scooterService.profiles.active();
        showReportPicker(scooterService.ble.descriptor().rulePid(),new String[]{p==null?"":p.id},report);
    }
    /**
     * The check runs while the owner says which scooter this is. Next waits for both; a check that stops offers Try again whatever the answer.
     * With finished (from the checker), there is no check to wait for and closing stores no answer to the offer.
     */
    private void showReportPicker(String model,String[] scooter,CompatibilityReport finished){
        CompatibilityReport[] ready={finished};Dialog[] self={null};ModelPicker[] picker={null};
        Runnable phase=()->{
            ModelPicker p=picker[0];
            if(reportChecking){p.primary("Next",false);p.status("Checking your scooter, about 30 seconds…",true);}
            else if(ready[0]==null)p.primary("Try again",true);
            else{p.primary("Next",p.choice()!=null);p.status(p.choice()==null?"Check finished ✓ Choose your scooter, or Not sure.":"Check finished ✓",false);}
        };
        Runnable watch=new Runnable(){@Override public void run(){
            Dialog dialog=self[0];
            if(destroyed||dialog==null||!dialog.isShowing()||!reportChecking)return;
            BleController ble=scooterService==null?null:scooterService.ble;
            // A check that finishes while Volt is in the background waits: the email app opens only in front of Volt.
            if(ble!=null&&ble.discovering()||!resumed){handler.postDelayed(this,400);return;}
            reportChecking=false;
            ScooterProfiles.Profile now=scooterService==null?null:scooterService.profiles.active();
            DiscoveryCoordinator finished=ble==null||now==null||!now.id.equals(scooter[0])?null:ble.discovery();
            ready[0]=finished==null||finished.interrupted()?null:ble.compatibilityReport(AppVersion.name(MainActivity.this));
            if(ready[0]==null)picker[0].status("The check stopped before it finished.",false);
            phase.run();
        }};
        Runnable next=()->{
            if(reportChecking)return;
            if(ready[0]!=null){if(picker[0].choice()!=null)finishReport(model,ready[0],picker[0].choice(),scooter[0],self[0]);return;}
            String problem=startReportCheck(scooter);
            if(problem!=null){picker[0].status(problem,false);return;}
            phase.run();handler.postDelayed(watch,400);
        };
        Runnable close=()->{if(finished==null)preferences.edit().putString(ReportInvite.PREFIX+model,ReportInvite.later(System.currentTimeMillis())).apply();if(self[0]!=null)self[0].dismiss();};
        picker[0]=new ModelPicker(this,design,c->phase.run());
        LinearLayout content=sheetContent("Which scooter is this?",null,close);
        picker[0].build(content,new Spinner(),"Next",next);
        Dialog dialog=showTallSheet(content);self[0]=dialog;reportDialog=dialog;
        dialog.setOnCancelListener(d->close.run());
        dialog.setOnDismissListener(d->{if(reportChecking&&scooterService!=null)scooterService.ble.stopDiscovery();reportChecking=false;if(reportDialog==dialog)reportDialog=null;});
        phase.run();if(finished==null)handler.postDelayed(watch,400);
    }
    /** After the check and the owner's answer: optionally name the drive modes, then open the email, still for the same scooter. */
    private void finishReport(String model,CompatibilityReport report,NaveeModels.Choice choice,String scooter,Dialog dialog){
        // A check on the developer simulator says nothing about the saved scooter it stands in for.
        dialog.dismiss();if(scooterService!=null&&!scooterService.simulating())rememberModel(scooter,choice,false);
        java.util.function.Consumer<DriveModeSurvey> send=modes->{
            if(destroyed)return;
            ScooterProfiles.Profile now=scooterService==null?null:scooterService.profiles.active();
            if(now==null||!now.id.equals(scooter)){toast("The scooter changed, so Volt didn't open the report.");return;}
            if(email(ReportInvite.subject(model),ReportInvite.body(report,choice,modes,model))){
                if(!model.isEmpty())preferences.edit().putString(ReportInvite.PREFIX+model,"sent").apply();toast("Your email app has the report. Thanks for helping!");
            }else toast("No email app could open the report.");
        };
        BleController ble=scooterService==null?null:scooterService.ble;
        // Optional: the owner names each drive mode the scooter reports (DriveModeStep); skipping still sends the report.
        if(ble!=null&&ble.ready()&&ble.telemetry.number("driving_mode")!=null)
            reportDialog=new DriveModeStep(this,design,ble,modes->{if(!destroyed)reportDialog=null;send.accept(modes);}).show();
        else send.accept(null);
    }
    /**
     * Keeps the owner's answer on that scooter, so Volt names and pictures it until Volt can identify it itself (ModelRegistry.ownerModel).
     * A listed model replaces any earlier answer; Not sure clears it only when the owner chose it in Edit scooter (cleared).
     */
    private void rememberModel(String profileId,NaveeModels.Choice choice,boolean cleared){
        ScooterProfiles.Profile p=null;if(scooterService!=null)for(ScooterProfiles.Profile item:scooterService.profiles.all())if(item.id.equals(profileId))p=item;
        if(p==null||choice==null)return;
        String model=choice.source==NaveeModels.Choice.Source.LIST?choice.name:cleared&&!choice.known()?"":null;
        if(model==null||model.equals(p.ownerModel))return;
        p.ownerModel=model;
        // A name Volt chose itself follows the answer, as it follows an identified model on connecting.
        if(p.nameDefault){String name=ModelRegistry.modelName(p.savedDescriptor());p.name=name.isEmpty()?"NAVEE scooter":name;}
        scooterService.profiles.update(p);redraw();
    }
    /** More › My scooters › Edit scooter › Which model is it?: the same sheet as the report flows, saving the owner's answer for that scooter. */
    @Override public void chooseScooterModel(String profileId){
        if(scooterService==null||reportDialog!=null&&reportDialog.isShowing())return;
        ScooterProfiles.Profile p=null;for(ScooterProfiles.Profile item:scooterService.profiles.all())if(item.id.equals(profileId))p=item;
        if(p==null)return;
        Dialog[] self={null};ModelPicker[] picker={null};
        String saved=p.ownerModel;
        Runnable save=()->{
            NaveeModels.Choice choice=picker[0].choice();
            if(choice==null||choice.source==NaveeModels.Choice.Source.TYPED)return; // listed-only below; never a save that keeps nothing
            rememberModel(profileId,choice,true);if(self[0]!=null)self[0].dismiss();
        };
        // Save keeps exactly the row shown as chosen: never an answer the search has hidden.
        picker[0]=new ModelPicker(this,design,c->picker[0].primary("Save",picker[0].choice()!=null&&picker[0].choiceShown())).listedOnly();
        LinearLayout content=sheetContent("Which scooter is this?",null,()->{if(self[0]!=null)self[0].dismiss();});
        picker[0].build(content,null,"Save",save);
        if(!saved.isEmpty()&&saved.equals(NaveeModels.exact(saved)))picker[0].select(NaveeModels.Choice.listed(saved));
        picker[0].primary("Save",picker[0].choice()!=null&&picker[0].choiceShown());
        picker[0].status("Volt shows this model's name and photo until it can identify your scooter itself.",false);
        Dialog dialog=showTallSheet(content);self[0]=dialog;reportDialog=dialog;
        dialog.setOnDismissListener(d->{if(reportDialog==dialog)reportDialog=null;});
    }
    /** After repeated failures on a scooter that has never connected, offer once per app session to email its connection details. */
    private static final java.util.Set<String> connectOffered=java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private void maybeOfferConnectionReport(){
        BleController ble=scooterService==null?null:scooterService.ble;
        if(ble==null||ble.simulating()||!resumed||ble.state()!=BleController.State.DISCONNECTED)return;
        if(reportDialog!=null&&reportDialog.isShowing()||controlDialog!=null&&controlDialog.isShowing())return;
        ScooterProfiles.Profile p=scooterService.profiles.active();
        if(p==null||!p.configured()||connectOffered.contains(p.id))return;
        if(!ConnectionReport.due(p.lastConnected==0,ble.connectionAttempts(p.id),preferences.getString(ConnectionReport.PREFIX+p.id,""),System.currentTimeMillis()))return;
        connectOffered.add(p.id);showConnectionReportOffer(p);
    }
    /** Volt couldn't connect: Send details asks which scooter this is, then the email app opens with the redacted connection details to read before sending. */
    private void showConnectionReportOffer(ScooterProfiles.Profile p){
        LinearLayout box=column();box.setPadding(design.dp(24),design.dp(4),design.dp(24),design.dp(4));
        box.addView(design.text("Sending Volt's connection details helps fix this for your model. Your email app opens a short report you can read before you send it.",15,false));
        addressLine(box);
        TextView privacy=design.caption("It lists each connection step and error code, the scooter's model ID, and your Android version and phone model. No serial, MAC, owner key, PIN or names.");
        privacy.setPadding(0,design.dp(6),0,0);box.addView(privacy);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setCustomTitle(dialogTitle("Volt couldn't connect to this scooter")).setView(scroll)
                .setPositiveButton("Send details",null).setNegativeButton("Not now",null).setNeutralButton("Don't ask again",null).create();
        reportDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
        String key=ConnectionReport.PREFIX+p.id;
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{preferences.edit().putString(key,ReportInvite.later(System.currentTimeMillis())).apply();dialog.dismiss();});
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{preferences.edit().putString(key,"never").apply();dialog.dismiss();});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.dismiss();showConnectionPicker(p,key);});
        dialog.setOnDismissListener(d->{if(reportDialog==dialog)reportDialog=null;});
    }
    private void showConnectionPicker(ScooterProfiles.Profile p,String key){
        Dialog[] self={null};ModelPicker[] picker={null};
        Runnable send=()->{
            if(picker[0].choice()==null||scooterService==null)return;
            rememberModel(p.id,picker[0].choice(),false);
            BleController ble=scooterService.ble;String model=ble.advertisedModelId(p);
            String json=ConnectionReport.json(AppVersion.name(this),Build.VERSION.SDK_INT,Build.MANUFACTURER+" "+Build.MODEL,model,ble.advertisedTransport(p),ble.mtu(),
                    ble.connectionAttempts(p.id),ble.connectionActivity(p.id));
            if(email(ConnectionReport.subject(model),ConnectionReport.body(json,picker[0].choice(),model))){preferences.edit().putString(key,"sent").apply();self[0].dismiss();toast("Your email app has the details. Thanks for helping!");}
            else toast("No email app could open the details.");
        };
        Runnable close=()->{preferences.edit().putString(key,ReportInvite.later(System.currentTimeMillis())).apply();if(self[0]!=null)self[0].dismiss();};
        picker[0]=new ModelPicker(this,design,c->{picker[0].primary("Send",true);picker[0].status("Your email app opens next, so you can read it first.",false);});
        LinearLayout content=sheetContent("Which scooter is this?",null,close);
        picker[0].build(content,null,"Send",send);
        picker[0].primary("Send",false);picker[0].status("Choose your scooter, or Not sure, then Send.",false);
        Dialog dialog=showTallSheet(content);self[0]=dialog;reportDialog=dialog;
        dialog.setOnCancelListener(d->close.run());
        dialog.setOnDismissListener(d->{if(reportDialog==dialog)reportDialog=null;});
    }
    /** The report address on its own line, so it never wraps mid-address. */
    private void addressLine(LinearLayout box){
        TextView to=design.caption("To "+ReportInvite.ADDRESS);to.setPadding(0,design.dp(10),0,0);to.setTextIsSelectable(true);box.addView(to);
    }
    @Override public boolean emailVolt(String subject,String body){return email(subject,body);}
    /** Opens the owner's email app addressed to Volt's report alias and filled in; false when no app can. */
    private boolean email(String subject,String body){
        Intent mail=new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:"));
        mail.putExtra(Intent.EXTRA_EMAIL,new String[]{ReportInvite.ADDRESS});mail.putExtra(Intent.EXTRA_SUBJECT,subject);
        mail.putExtra(Intent.EXTRA_TEXT,body);
        try{startActivity(mail);return true;}catch(android.content.ActivityNotFoundException none){}
        Intent send=new Intent(Intent.ACTION_SEND);send.setType("message/rfc822");send.putExtras(mail);
        try{startActivity(Intent.createChooser(send,"Email the report"));return true;}catch(RuntimeException none){return false;}
    }
    private void styleDialog(AlertDialog dialog,int primaryButton){
        android.view.Window window=dialog.getWindow();
        if(window!=null)window.setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(design.shape(design.surface,24,0),design.dp(16)));
        TextView message=dialog.findViewById(android.R.id.message);
        if(message!=null){message.setTypeface(design.regular());message.setTextColor(design.muted);message.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,14*design.textScale);message.setLineSpacing(design.dp(3),1);}
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL})
            styleDialogButton(dialog.getButton(which),which==primaryButton?design.accent:design.text);
    }
    private void styleDialogButton(android.widget.Button button,int color){
        if(button==null)return;button.setTextColor(color);button.setTypeface(design.semibold());button.setAllCaps(false);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,14*design.textScale);
    }
    private TextView dialogTitle(String title){
        TextView view=design.text(title,20,true);view.setPadding(design.dp(24),design.dp(22),design.dp(24),design.dp(6));
        if(Build.VERSION.SDK_INT>=28)view.setAccessibilityHeading(true);return view;
    }
    /** Bottom sheet frame (ph/27): grab handle, title, close button and an optional one-line explanation. */
    private LinearLayout sheetContent(String title,String explanation,Runnable close){
        LinearLayout content=column();content.setPadding(design.dp(18),design.dp(8),design.dp(18),design.dp(18));
        View grab=new View(this);grab.setBackground(design.shape(design.outline,2,0));
        LinearLayout.LayoutParams grabParams=new LinearLayout.LayoutParams(design.dp(36),design.dp(4));grabParams.gravity=Gravity.CENTER_HORIZONTAL;grabParams.bottomMargin=design.dp(8);content.addView(grab,grabParams);
        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=design.text(title,20,true);if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);head.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout dismiss=new FrameLayout(this);
        android.graphics.drawable.GradientDrawable disc=new android.graphics.drawable.GradientDrawable();disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);disc.setColor(design.surface);
        dismiss.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,40)),disc,null));
        dismiss.addView(design.icon("close",16,design.text),new FrameLayout.LayoutParams(design.dp(16),design.dp(16),Gravity.CENTER));
        dismiss.setContentDescription("Close");dismiss.setClickable(true);dismiss.setFocusable(true);dismiss.setOnClickListener(v->close.run());
        LinearLayout.LayoutParams dismissParams=new LinearLayout.LayoutParams(design.dp(48),design.dp(48));dismissParams.leftMargin=design.dp(10);head.addView(dismiss,dismissParams);
        content.addView(head);
        if(explanation!=null&&!explanation.isEmpty()){TextView line=design.caption(explanation);line.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,13*design.textScale);line.setPadding(0,design.dp(6),0,design.dp(14));content.addView(line);}
        else{View gap=new View(this);content.addView(gap,new LinearLayout.LayoutParams(1,design.dp(10)));}
        return content;
    }
    private AlertDialog showSheet(View content){
        ScrollView holder=new ScrollView(this);holder.addView(content,new ScrollView.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setView(holder).create();
        android.view.Window window=dialog.getWindow();
        Motion.present(dialog,Motion.SHEET);
        if(window!=null){
            android.graphics.drawable.GradientDrawable sheet=new android.graphics.drawable.GradientDrawable();sheet.setColor(design.bg);
            float r=design.dp(24);sheet.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});sheet.setStroke(design.dp(1),design.raised);
            window.setBackgroundDrawable(sheet);window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            window.getDecorView().setPadding(0,0,0,0);
        }
        dialog.show();
        if(window!=null)window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        return dialog;
    }
    /** The open tall sheet, kept nine-tenths of Volt's window tall through rotation, folding and window resizing. */
    private Dialog tallSheet;private boolean tallSheetFollows;
    private int tallSheetHeight(){int h=getWindow().getDecorView().getHeight();return Math.round((h>0?h:getResources().getDisplayMetrics().heightPixels)*.9f);}
    private void fitTallSheet(){
        android.view.Window window=tallSheet==null||!tallSheet.isShowing()?null:tallSheet.getWindow();
        if(window!=null&&window.getAttributes().height!=tallSheetHeight())window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,tallSheetHeight());
    }
    /** A bottom sheet of fixed height whose content fills it, so a long list scrolls between a fixed title and footer. */
    private Dialog showTallSheet(LinearLayout content){
        Dialog dialog=new Dialog(dialogContext());dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(content,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        content.setFocusableInTouchMode(true);content.requestFocus();
        android.view.Window window=dialog.getWindow();
        Motion.present(dialog,Motion.SHEET);
        if(window!=null){
            android.graphics.drawable.GradientDrawable sheet=new android.graphics.drawable.GradientDrawable();sheet.setColor(design.bg);
            float r=design.dp(24);sheet.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});sheet.setStroke(design.dp(1),design.raised);
            window.setBackgroundDrawable(sheet);window.setGravity(Gravity.BOTTOM);window.getDecorView().setPadding(0,0,0,0);
            // The keyboard waits for a tap on the search box; while it is up, the sheet rises so the results above it stay in view.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        if(window!=null)window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,tallSheetHeight());
        tallSheet=dialog;
        // Volt's window changes size without being recreated (configChanges): follow it, keeping the sheet's answer and check.
        if(!tallSheetFollows){tallSheetFollows=true;getWindow().getDecorView().addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot)fitTallSheet();});}
        return dialog;
    }
    private static final String RANGE_INTRO_SEEN="range_learned_intro_seen";
    private RangeSourceSheet rangeSourceSheet;
    private void showRangeSource(){
        if(scooterService==null||scooterService.profiles.active()==null){toast("Choose a scooter first");return;}
        if(controlDialog!=null)controlDialog.dismiss();
        rangeSourceSheet=new RangeSourceSheet();
    }
    /** Local source choice; the scooter option retains the guarded drive-mode follower. */
    private final class RangeSourceSheet {
        final String profile=scooterService.profiles.active().id;
        final AlertDialog dialog;
        final ArrayList<LinearLayout> options=new ArrayList<>();
        final ArrayList<Ui.RadioDot> dots=new ArrayList<>();
        final ArrayList<TextView> names=new ArrayList<>();
        final TextView status;
        final View progress;
        final LinearLayout statusRow;
        AlertDialog intro;
        boolean pending;
        int requestId;
        long requestedRevision=-1;
        LearnedRangeEstimate.Result result;
        RangeSourceSheet(){
            final AlertDialog[] self=new AlertDialog[1];
            LinearLayout body=sheetContent("Range estimate",null,()->{if(self[0]!=null)self[0].dismiss();});
            body.setTag("range-source-sheet");
            LinearLayout list=design.groupCard();body.addView(list,new LinearLayout.LayoutParams(-1,-2));
            String[] labels={"Scooter's estimate","Volt's estimate (learned)"};
            for(int i=0;i<labels.length;i++){
                if(i>0)design.divider(list);
                final boolean learned=i==1;
                LinearLayout option=row();option.setGravity(Gravity.CENTER_VERTICAL);
                option.setMinimumHeight(design.dp(64));option.setPadding(design.dp(4),design.dp(12),design.dp(4),design.dp(12));
                TextView name=design.text(labels[i],16,false);name.setMaxLines(3);
                option.addView(name,new LinearLayout.LayoutParams(0,-2,1));
                Ui.RadioDot dot=design.radioDot();LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(design.dp(24),design.dp(24));dp.leftMargin=design.dp(14);option.addView(dot,dp);
                option.setTag(learned?"range-source-volt":"range-source-scooter");
                option.setClickable(true);option.setFocusable(true);option.setBackground(rowRipple());option.setContentDescription(labels[i]);
                name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                option.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View v,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(v,info);info.setClassName(android.widget.RadioButton.class.getName());info.setCheckable(true);info.setChecked(dot.checked);}});
                option.setOnClickListener(v->choose(learned));list.addView(option);options.add(option);dots.add(dot);names.add(name);
            }
            statusRow=row();statusRow.setGravity(Gravity.CENTER_VERTICAL);statusRow.setPadding(design.dp(4),design.dp(14),design.dp(4),0);
            progress=new Spinner();LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(design.dp(16),design.dp(16));sp.rightMargin=design.dp(10);statusRow.addView(progress,sp);
            status=design.caption("");status.setTag("range-learning-status");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            statusRow.addView(status,new LinearLayout.LayoutParams(0,-2,1));body.addView(statusRow);
            dialog=showSheet(body);self[0]=dialog;controlDialog=dialog;
            dialog.setOnDismissListener(d->{requestId++;if(intro!=null)intro.dismiss();if(rangeSourceSheet==this)rangeSourceSheet=null;if(controlDialog==dialog)controlDialog=null;});
            refresh();
            request();
        }
        boolean current(){return !destroyed&&scooterService!=null&&scooterService.profiles.active()!=null&&profile.equals(scooterService.profiles.active().id);}
        void choose(boolean learned){
            if(!current()){dialog.dismiss();return;}
            Haptics.tick(options.get(learned?1:0));
            if(learned&&scooterService.simulating()){toast("Exit simulation to use saved rides.");return;}
            if(learned&&requestedRevision!=scooterService.learnedRangeRevision())request();
            if(learned&&(pending||result==null)){toast("Checking saved rides…");return;}
            if(learned&&result.status==LearnedRangeEstimate.Status.UNAVAILABLE){request();return;}
            if(learned&&result.status==LearnedRangeEstimate.Status.INSUFFICIENT){
                showExplanation(false);return;
            }
            scooterService.useLearnedRange(learned);
            // An explicit scooter choice means following drive mode. Opening the picker or
            // choosing Volt preserves an existing fixed firmware preference without writes.
            if(!learned&&!scooterService.followsRidingMode())scooterService.followRidingMode(true);
            if(learned&&!preferences.getBoolean(RANGE_INTRO_SEEN,false))showExplanation(true);
            refresh();
        }
        void showExplanation(boolean firstUse){
            if(intro!=null&&intro.isShowing())return;
            final AlertDialog[] shown=new AlertDialog[1];
            LinearLayout body=sheetContent(firstUse?"Volt's estimate":"More rides needed",null,()->{if(shown[0]!=null)shown[0].dismiss();});
            body.setTag(firstUse?"range-learned-intro":"range-more-rides");
            LinearLayout message=row();message.setGravity(Gravity.TOP);message.setPadding(design.dp(16),design.dp(16),design.dp(16),design.dp(16));
            message.setBackground(design.shape(Ui.withAlpha(design.accent,design.dark?24:14),18,Ui.withAlpha(design.accent,50)));
            message.addView(design.icon("route",26,design.accent),new LinearLayout.LayoutParams(design.dp(26),design.dp(26)));
            TextView words=design.text(firstUse?"Volt uses your recorded rides to learn how far you can go. It improves as you record more rides.":"Record a few more rides so Volt can learn your range. Use your scooter's estimate for now.",15,false);
            LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.leftMargin=design.dp(14);message.addView(words,wp);body.addView(message);
            TextView gotIt=design.button("Got it",()->{if(shown[0]!=null)shown[0].dismiss();});gotIt.setTag("range-intro-done");
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=design.dp(16);body.addView(gotIt,bp);
            intro=showSheet(body);shown[0]=intro;if(firstUse)preferences.edit().putBoolean(RANGE_INTRO_SEEN,true).apply();
            intro.setOnDismissListener(d->intro=null);
        }
        void request(){
            if(pending||!current()||!dialog.isShowing())return;
            pending=true;result=null;requestedRevision=scooterService.learnedRangeRevision();int id=++requestId;refresh();
            java.util.function.Consumer<LearnedRangeEstimate.Result> done=next->{
                if(id!=requestId||!current()||!dialog.isShowing())return;
                result=next;pending=false;requestedRevision=scooterService.learnedRangeRevision();refresh();
            };
            scooterService.requestLearnedRangeEstimate(done);
        }
        void refresh(){
            if(!current()){dialog.dismiss();return;}
            if(!pending&&requestedRevision>=0&&requestedRevision!=scooterService.learnedRangeRevision()){request();return;}
            boolean learned=scooterService.usesLearnedRange();
            boolean ready=!pending&&result!=null&&result.status==LearnedRangeEstimate.Status.READY;
            for(int i=0;i<options.size();i++){
                boolean on=learned==(i==1);dots.get(i).set(on);names.get(i).setTypeface(on?design.semibold():design.regular());
                boolean unavailable=i==1&&!ready;
                names.get(i).setTextColor(unavailable?design.muted:design.text);
                dots.get(i).setAlpha(unavailable?.45f:1f);
                String state=on?"Selected":"Not selected";
                if(unavailable)state+=scooterService.simulating()?", unavailable in simulation":pending||result==null?", checking saved rides":result.status==LearnedRangeEstimate.Status.INSUFFICIENT?", more rides needed":", tap to retry";
                if(Build.VERSION.SDK_INT>=30)options.get(i).setStateDescription(state);
            }
            String message=scooterService.simulating()?"Exit simulation to use saved rides.":pending?"Checking saved rides…":result==null?"":result.status==LearnedRangeEstimate.Status.UNAVAILABLE?"Couldn't read saved rides. Tap Volt's estimate to retry."
                    :learned&&result.status==LearnedRangeEstimate.Status.INSUFFICIENT?"Using scooter's estimate for now."
                    :learned&&ready?learnedSummary():"";
            status.setText(message);progress.setVisibility(pending?View.VISIBLE:View.GONE);
            statusRow.setVisibility(!message.isEmpty()?View.VISIBLE:View.GONE);
        }
        /** "About 10 km at 50% · likely 8–12 km": the learned forecast at the last battery reading. */
        String learnedSummary(){
            TelemetryHistory.Reading soc=scooterService.displayReadings().get("battery_percent");
            Double percent=soc==null||soc.value==null?null:soc.number,km=result.remainingKm(percent);
            if(km==null)return "";
            Double low=result.likelyLowKm(percent),high=result.likelyHighKm(percent);
            return "About "+wholeDistance(km,km)+" at "+Math.round(percent)+"%"+(low==null||high==null?"":" · likely "+wholeDistance(low,high));
        }
        String wholeDistance(double lowKm,double highKm){
            String units=preferences.getString("units","mi");
            String km=span(lowKm,highKm,"km"),mi=span(lowKm/1.609344,highKm/1.609344,"mi");
            return units.equals("km")?km:units.equals("dual")?mi+" / "+km:mi;
        }
        String span(double low,double high,String unit){
            long a=Math.round(low),b=Math.round(high);
            return (a==b?Long.toString(a):a+"–"+b)+" "+unit;
        }
    }

    /** Local per-scooter estimate preference: never sends a charger command or starts capture. */
    private void showChargerEstimateChoice(){
        if(scooterService==null||scooterService.profiles.active()==null||scooterService.isChargeRecording())return;
        String profile=scooterService.profiles.active().id;
        final AlertDialog[] sheet=new AlertDialog[1];
        LinearLayout content=sheetContent("Charger for estimates",
                "Automatic recognizes UT5 MAX chargers from live charging power. You can choose a charger yourself instead.",
                ()->{if(sheet[0]!=null)sheet[0].dismiss();});
        SegmentedSelector selector=new SegmentedSelector(design,"Charger for estimates",new String[]{"Auto","Stock","High-speed"},choice->{
            if(scooterService==null||scooterService.profiles.active()==null||!profile.equals(scooterService.profiles.active().id)){
                if(sheet[0]!=null)sheet[0].dismiss();return;
            }
            if(scooterService.setChargeCharger(choice==0?null:choice==1?ChargeSessionStore.Charger.STOCK:ChargeSessionStore.Charger.HIGH_SPEED)){
                if(sheet[0]!=null)sheet[0].dismiss();redraw();
            }else toast("Stop charge recording before changing the charger.");
        });
        selector.setTag("estimate-charger-selector");
        ChargeSessionStore.Charger selected=scooterService.chargeCharger();
        selector.select(selected==null?0:selected==ChargeSessionStore.Charger.STOCK?1:2,false);
        content.addView(selector,new LinearLayout.LayoutParams(-1,-2));
        TextView explanation=design.caption("Connecting near full may leave the charger uncertain; Volt uses the current rate until it knows. This preference changes estimates only and is saved for this scooter.");
        explanation.setPadding(0,design.dp(12),0,design.dp(4));content.addView(explanation);
        controlDialog=showSheet(content);sheet[0]=controlDialog;
    }
    private void editFeature(ScooterCommands.Feature feature){
        if(feature.key.equals("range_algorithm")){showRangeSource();return;}
        if(feature.type==ScooterCommands.Type.TIME){showChargingTime();return;}
        // A slider on the UT5 MAX can be an explicit OEM value list on another model.
        FeatureVariant variant=oemVariant(feature.key);
        if(feature.type!=ScooterCommands.Type.SLIDER||variant!=null&&variant.values.length>0){showChoice(feature);return;}
        if(scooterService==null||!scooterService.ble.ready()||scooterService.ble.pending()!=null)return;
        Integer current=scooterService.ble.featureValue(feature.key);if(current==null)return;
        final AlertDialog[] sheet=new AlertDialog[1];Runnable close=()->{if(sheet[0]!=null)sheet[0].dismiss();};
        LinearLayout body=sheetContent(shortLabel(feature),explanation(feature),close);
        TextView amount=design.text(featureLabel(feature,current),28,true);amount.setPadding(design.dp(2),design.dp(4),0,design.dp(10));body.addView(amount);
        SeekBar slider=new SeekBar(this);slider.setMax((feature.max-feature.min)/feature.step);slider.setProgress(Math.max(0,(current-feature.min)/feature.step));slider.setProgressTintList(ColorStateList.valueOf(design.accent));slider.setThumbTintList(ColorStateList.valueOf(design.accent));slider.setProgressBackgroundTintList(ColorStateList.valueOf(design.outline));slider.setContentDescription(shortLabel(feature));body.addView(slider,new LinearLayout.LayoutParams(-1,design.dp(48)));
        // The range ends sit under the slider instead of a sentence about them.
        LinearLayout ends=row();ends.setPadding(design.dp(4),0,design.dp(4),design.dp(6));ends.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        String units=preferences.getString("units","mi"),high=DisplayUnits.featureValue(feature,feature.max,units),low=DisplayUnits.featureValue(feature,feature.min,units);
        int unitAt=high.lastIndexOf(' ');if(unitAt>0&&low.endsWith(high.substring(unitAt)))low=low.substring(0,low.length()-(high.length()-unitAt));
        TextView lowText=design.caption(low);ends.addView(lowText,new LinearLayout.LayoutParams(0,-2,1));
        TextView highText=design.caption(high);highText.setGravity(Gravity.END);ends.addView(highText,new LinearLayout.LayoutParams(-2,-2));
        body.addView(ends,new LinearLayout.LayoutParams(-1,-2));
        final int[] selected={current},base={current};slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int n,boolean user){if(user){selected[0]=feature.min+n*feature.step;amount.setText(featureLabel(feature,selected[0]));}}public void onStopTrackingTouch(SeekBar s){}});
        TextView error=design.caption("");error.setTextColor(design.danger);error.setPadding(design.dp(2),design.dp(4),0,design.dp(4));body.addView(error);
        LinearLayout actions=row();
        TextView cancel=design.secondaryButton("Cancel",close);
        final ApplyCheck[] check=new ApplyCheck[1];
        TextView apply=design.button("Apply",()->{
            Integer latest=scooterService.ble.featureValue(feature.key);
            if(!scooterService.ble.ready()||latest==null){error.setText("Not connected");return;}
            if(!latest.equals(base[0])){
                // The scooter changed underneath: reload the slider to its readback and say so with a brief checkmark.
                base[0]=latest;selected[0]=latest;error.setText("");
                slider.setProgress(Math.max(0,(latest-feature.min)/feature.step));amount.setText(featureLabel(feature,latest));
                check[0].show();return;
            }
            int value=selected[0];
            confirmChange(feature,value,()->{if(scooterService.ble.set(feature.key,value)){if(sheet[0]!=null)sheet[0].dismiss();}else error.setText(scooterService.ble.message());redraw();});});
        check[0]=new ApplyCheck(apply,"Apply");
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=design.dp(5);actions.addView(cancel,left);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.leftMargin=design.dp(5);actions.addView(apply,right);
        body.addView(actions);
        AlertDialog dialog=showSheet(body);sheet[0]=dialog;controlDialog=dialog;
    }
    /**
     * Apply button feedback: the label gives way to a checkmark that springs in, holds a few seconds, then the
     * label returns. Spoken as "Updated on scooter".
     */
    private final class ApplyCheck {
        private final TextView button;private final String label;private final ColorStateList colors;
        private float progress;private android.animation.ValueAnimator motion;
        private final Runnable hide=()->animateTo(0);
        private final android.graphics.drawable.Drawable mark=new android.graphics.drawable.Drawable(){
            @Override public void draw(Canvas canvas){
                if(progress<=0)return;android.graphics.Rect b=getBounds();
                float grow=progress<1?(float)(1+.18*Math.sin(Math.PI*progress))*progress:1;
                design.drawIcon(canvas,"check",colors.getColorForState(button.getDrawableState(),design.onAccent),b.exactCenterX(),b.exactCenterY(),design.dp(24)*grow);
            }
            @Override public void setAlpha(int alpha){}
            @Override public void setColorFilter(android.graphics.ColorFilter filter){}
            @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        };
        ApplyCheck(TextView button,String label){this.button=button;this.label=label;this.colors=button.getTextColors();}
        void show(){
            button.removeCallbacks(hide);
            mark.setBounds(0,0,button.getWidth(),button.getHeight());button.getOverlay().remove(mark);button.getOverlay().add(mark);
            button.setContentDescription("Updated on scooter");button.announceForAccessibility("Updated on scooter");
            animateTo(1);button.postDelayed(hide,2600);
        }
        private void animateTo(float target){
            if(motion!=null){motion.cancel();motion=null;}
            if(!Ui.motionEnabled()||!button.isAttachedToWindow()){apply(target);finish(target);return;}
            motion=android.animation.ValueAnimator.ofFloat(progress,target);motion.setDuration(target>0?260:200);
            motion.setInterpolator(new android.view.animation.PathInterpolator(.2f,0,0,1));
            motion.addUpdateListener(a->apply((Float)a.getAnimatedValue()));
            motion.addListener(new android.animation.AnimatorListenerAdapter(){
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator a){cancelled=true;}
                @Override public void onAnimationEnd(android.animation.Animator a){if(!cancelled)finish(target);}
            });
            motion.start();
        }
        /** The label fades out as the mark grows in, and back. */
        private void apply(float value){progress=value;button.setTextColor(colors.withAlpha(Math.round(255*Math.max(0,1-value*1.6f))));button.invalidate();}
        private void finish(float target){
            if(target>0)return;
            button.getOverlay().remove(mark);button.setTextColor(colors);button.setText(label);button.setContentDescription(label);
        }
    }
    private ChoiceSheet choiceSheet;
    private void showChoice(ScooterCommands.Feature feature){
        if(scooterService==null||!scooterService.ble.ready()||scooterService.ble.pending()!=null)return;
        int[] choices=scooterService.ble.featureChoices(feature.key);if(choices.length==0){toast("Not on this firmware");return;}
        if(feature.key.equals("range_algorithm")) {
            int[] withFollow=new int[choices.length+1]; withFollow[0]=-1;
            System.arraycopy(choices,0,withFollow,1,choices.length); choices=withFollow;
        }
        choiceSheet=new ChoiceSheet(feature,choices);
    }
    /** Choice picker (ph/27). It stays open until the scooter confirms the new value by readback. */
    private final class ChoiceSheet {
        final ScooterCommands.Feature feature;final int[] choices;final AlertDialog dialog;
        final ArrayList<LinearLayout> options=new ArrayList<>();final ArrayList<Ui.RadioDot> dots=new ArrayList<>();final ArrayList<TextView> names=new ArrayList<>();
        final SegmentedSelector selector;
        final LinearLayout wait;final TextView status;
        Integer target;boolean sent;String failure;
        ChoiceSheet(ScooterCommands.Feature feature,int[] choices){
            this.feature=feature;this.choices=choices;
            final AlertDialog[] self=new AlertDialog[1];
            LinearLayout content=sheetContent(shortLabel(feature),explanation(feature),()->{if(self[0]!=null)self[0].dismiss();});
            String[] labels=new String[choices.length];boolean compact=choices.length>=2&&choices.length<=5&&!feature.key.equals("range_algorithm");
            for(int i=0;i<choices.length;i++){labels[i]=featureLabel(feature,choices[i]);compact&=labels[i].length()<=18;}
            selector=compact?new SegmentedSelector(design,shortLabel(feature),labels,this::choose):null;
            // Another model's longer tables (five speeds, five sounds) wrap onto rows instead of scrolling past the edge.
            if(selector!=null&&oemVariant(feature.key)!=null)selector.setWrapChoices(true);
            if(selector!=null){selector.setTag("scooter-selector-"+feature.key);content.addView(selector,new LinearLayout.LayoutParams(-1,-2));}
            else{
            LinearLayout list=design.groupCard();
            for(int i=0;i<choices.length;i++){
                if(i>0)design.divider(list);
                final int index=i;
                LinearLayout option=row();option.setGravity(Gravity.CENTER_VERTICAL);option.setMinimumHeight(design.dp(54));option.setPadding(design.dp(4),design.dp(6),design.dp(2),design.dp(6));
                boolean auto=feature.key.equals("range_algorithm")&&choices[i]==-1;
                TextView label=design.text(auto?AUTO_RANGE:featureLabel(feature,choices[i]),15,false);option.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                if(auto){
                    // The Auto chip marks the one choice that follows the riding mode by itself.
                    TextView chip=design.text("Auto",11,true);chip.setTextColor(design.blue);chip.setPadding(design.dp(8),design.dp(2),design.dp(8),design.dp(3));
                    chip.setBackground(design.shape(Ui.withAlpha(design.blue,design.dark?40:28),100,0));chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    LinearLayout.LayoutParams chipSize=new LinearLayout.LayoutParams(-2,-2);chipSize.setMarginStart(design.dp(8));option.addView(chip,chipSize);
                }
                Ui.RadioDot dot=design.radioDot();LinearLayout.LayoutParams dotParams=new LinearLayout.LayoutParams(design.dp(22),design.dp(22));dotParams.leftMargin=design.dp(10);option.addView(dot,dotParams);
                option.setClickable(true);option.setFocusable(true);option.setBackground(rowRipple());option.setContentDescription(label.getText());
                option.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(android.widget.RadioButton.class.getName());info.setCheckable(true);info.setChecked(dot.checked);}});
                option.setOnClickListener(v->choose(index));
                list.addView(option);options.add(option);dots.add(dot);names.add(label);
            }
            content.addView(list);
            }
            wait=row();wait.setGravity(Gravity.CENTER_VERTICAL);wait.setPadding(design.dp(4),design.dp(4),0,design.dp(4));
            wait.addView(new Spinner(),new LinearLayout.LayoutParams(design.dp(14),design.dp(14)));
            TextView waiting=design.caption("Confirming…");waiting.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,13*design.textScale);
            LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-2,-2);wp.leftMargin=design.dp(10);wait.addView(waiting,wp);wait.setVisibility(View.GONE);content.addView(wait);
            status=design.caption("");status.setPadding(design.dp(4),design.dp(4),0,0);status.setVisibility(View.GONE);content.addView(status);
            dialog=showSheet(content);self[0]=dialog;controlDialog=dialog;
            dialog.setOnDismissListener(d->{if(choiceSheet==ChoiceSheet.this)choiceSheet=null;});
            refresh();
        }
        void choose(int index){
            if(selector!=null)restoreSelection(false);
            if(scooterService==null||sent)return;BleController ble=scooterService.ble;
            if(feature.key.equals("range_algorithm")) {
                if(choices[index]==-1) { scooterService.followRidingMode(true); dialog.dismiss(); return; }
                scooterService.followRidingMode(false);
            }
            Integer actual=ble.featureValue(feature.key);if(actual!=null&&actual==choices[index]){dialog.dismiss();return;}
            int choice=choices[index];
            confirmChange(feature,choice,()->{
                if(sent)return;failure=null;
                if(ble.set(feature.key,choice)){target=choice;sent=true;}else failure=ble.message();
                refresh();redraw();
            });
        }
        void refresh(){
            BleController ble=scooterService==null?null:scooterService.ble;boolean ready=ble!=null&&ble.ready();String pending=ble==null?null:ble.pending();
            Integer actual=ble==null?null:ble.featureValue(feature.key);
            boolean waiting=sent&&feature.key.equals(pending);
            if(sent&&!waiting){
                sent=false;
                if(target!=null&&target.equals(actual)){dialog.dismiss();return;}
                String feedback=ble==null?null:ble.commandResults().get(feature.key);
                failure=feedback==null||feedback.equals("Confirmed")||feedback.startsWith("Setting confirmed")||!feedback.startsWith("Rejected")?"Not confirmed":"Rejected";target=null;
            }
            Integer shown=waiting?target:feature.key.equals("range_algorithm") && scooterService != null && scooterService.followsRidingMode() ? Integer.valueOf(-1) : actual;boolean enabled=ready&&pending==null&&!sent;
            if(selector!=null){restoreSelection(true);boolean fresh=enabled&&actual!=null&&scooterService.featureFresh(feature.key);selector.setEnabled(fresh);selector.setAlpha(fresh?1f:.5f);}
            else for(int i=0;i<choices.length;i++){
                boolean on=shown!=null&&shown==choices[i];dots.get(i).set(on);dots.get(i).setEnabled(enabled);
                names.get(i).setTypeface(on?design.bold():design.regular());names.get(i).setTextColor(enabled||on?design.text:design.muted);
                options.get(i).setEnabled(enabled);if(Build.VERSION.SDK_INT>=30)options.get(i).setStateDescription(on?"Selected":"Not selected");
            }
            wait.setVisibility(waiting?View.VISIBLE:View.GONE);
            String message=failure!=null?failure:!ready&&!waiting?"Not connected":null;
            status.setText(message==null?"":message);status.setTextColor(failure!=null?design.danger:design.muted);status.setVisibility(message==null?View.GONE:View.VISIBLE);
        }
        private void restoreSelection(boolean animate){
            Integer actual=scooterService==null?null:scooterService.ble.featureValue(feature.key);int selected=-1;
            for(int i=0;i<choices.length;i++)if(actual!=null&&actual==choices[i])selected=i;
            selector.select(selected,animate);
        }
    }
    private void showChargingTime(){
        if(scooterService==null){toast("Connect your scooter first");return;}
        ChargeScheduleController.View charge=scooterService.chargeSchedule();
        if(charge.manualTimeBlockedReason!=null){toast(charge.manualTimeBlockedReason);return;}
        Calendar initial=Calendar.getInstance();if(charge.manualStartWallMs>0)initial.setTimeInMillis(charge.manualStartWallMs);
        final String profile=scooterService.profiles.active()==null?"":scooterService.profiles.active().id;
        // Other models retain their official whole-hour start-time domain.
        boolean wholeHours=scooterService.ble.decision("charge_start").layer!=ModelRegistry.Layer.UT5_MAX;
        if(wholeHours)initial.set(Calendar.MINUTE,0);
        TimePickerDialog picker=new TimePickerDialog(dialogContext(),(view,hour,picked)->{
            if(scooterService==null||scooterService.profiles.active()==null
                    ||!profile.equals(scooterService.profiles.active().id)){toast("The selected scooter changed");return;}
            long chosen=ChargeScheduleController.nextOccurrence(System.currentTimeMillis(),hour*60+(wholeHours?0:picked),TimeZone.getDefault());
            scooterService.setManualChargeStart(chosen,this::scheduleResult);redraw();
        },initial.get(Calendar.HOUR_OF_DAY),initial.get(Calendar.MINUTE),DisplayTime.is24Hour(this,preferences));
        picker.setTitle(wholeHours?"Next charge start (on the hour)":"Next charge start");controlDialog=picker;picker.show();
        styleDialogButton(picker.getButton(AlertDialog.BUTTON_POSITIVE),design.accent);
        styleDialogButton(picker.getButton(AlertDialog.BUTTON_NEGATIVE),design.muted);
    }
    /** The gutter follows the actual fold bounds within this content pane. */
    private final class TwoPane extends LinearLayout {
        final View first,second,gutter;
        TwoPane(View left,View right){super(MainActivity.this);setOrientation(HORIZONTAL);first=left;second=right;gutter=new View(MainActivity.this);addView(first,new LinearLayout.LayoutParams(0,-2,1));addView(gutter,new LinearLayout.LayoutParams(design.dp(20),1));addView(second,new LinearLayout.LayoutParams(0,-2,1));setBaselineAligned(false);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(this::requestLayout);}
        @Override protected void onMeasure(int width,int height){
            int size=View.MeasureSpec.getSize(width);int gap=design.dp(20);int firstWidth=(size-gap)/2;
            if(verticalHinge()){int relative=fold.hinge.left-Ui.layoutLeftInWindow(this);int hingeGap=Math.max(design.dp(20),fold.hinge.width()+design.dp(16));int proposed=relative-design.dp(8);if(proposed>=design.dp(220)&&size-proposed-hingeGap>=design.dp(220)){firstWidth=proposed;gap=hingeGap;}}
            LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)first.getLayoutParams();p.width=Math.max(0,firstWidth);p.weight=0;LinearLayout.LayoutParams g=(LinearLayout.LayoutParams)gutter.getLayoutParams();g.width=gap;LinearLayout.LayoutParams s=(LinearLayout.LayoutParams)second.getLayoutParams();s.width=Math.max(0,size-firstWidth-gap);s.weight=0;super.onMeasure(width,height);
        }
    }
    @Override public void exportDashboardLayouts(){
        if(scooterService==null||scooterService.profiles.active()==null){toast("Choose a scooter first.");return;}
        // Flush edits before creating a separate dashboard reader.
        if(screen instanceof DashboardScreen){screen.close();screen=null;rebuild();}
        try{DashboardConfig config=new DashboardConfig(preferences,scooterService.profiles.active().id);
            saveText("volt-dashboards.json","application/json",config.exportLayouts());
        }catch(Exception error){toast("Export failed: unreadable slot.");}
    }
    @Override public void importDashboardLayouts(){
        if(scooterService==null||scooterService.profiles.active()==null){toast("Choose a scooter first.");return;}
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        picker.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});
        try{startActivityForResult(picker,IMPORT_LAYOUTS);}catch(Exception missing){toast("A document picker could not open.");}
    }
    private void confirmDashboardImport(String text,DashboardConfig.LayoutTransfer info){
        if(destroyed||enteringMaintenance||scooterService==null||scooterService.profiles.active()==null)return;
        String profileId=scooterService.profiles.active().id;
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle("Replace dashboard layouts?")
                .setMessage("Replaces "+scooterService.profiles.active().name+"’s dashboard and all 3 slots.")
                .setNegativeButton("Cancel",null).setPositiveButton("Import layouts",(d,n)->{
                    if(destroyed||enteringMaintenance||DataMaintenance.active())return;
                    if(scooterService==null||scooterService.profiles.active()==null||!profileId.equals(scooterService.profiles.active().id)){toast("The selected scooter changed. Import again.");return;}
                    if(screen!=null){screen.close();screen=null;}
                    boolean restored=new DashboardConfig(preferences,profileId).importLayouts(text);
                    rebuild();toast(restored?"Dashboards imported.":"The dashboards could not be imported.");
                }).create();controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    @Override public void dataBackup(Boolean restore,String preselect){
        if(enteringMaintenance||destroyed)return;
        if(restore==null){
            LinearLayout box=new LinearLayout(dialogContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(design.dp(22),design.dp(18),design.dp(22),design.dp(4));
            TextView summary=design.caption(preselect!=null&&preselect.equals("rides")
                    ?"Save or restore your recorded rides."
                    :"One backup for scooter access, rides, charges, history, dashboards and settings. Choose what to include next.");
            summary.setPadding(0,0,0,design.dp(12));box.addView(summary);
            TextView backUp=design.button("Back up",null),restoreButton=design.secondaryButton("Restore",null);
            backUp.setTag("data-backup-create");restoreButton.setTag("data-backup-restore");box.addView(backUp);box.addView(restoreButton);
            AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(preselect!=null&&preselect.equals("rides")?"Ride backups":"Back up & restore").setView(box).setNegativeButton("Cancel",null).create();
            backUp.setOnClickListener(v->{dialog.dismiss();dataBackup(false,preselect);});restoreButton.setOnClickListener(v->{dialog.dismiss();dataBackup(true,preselect);});
            controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_NEGATIVE);return;
        }
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(restore?"Restore?":"Back up?")
                .setMessage((restore?"Choose a backup, review its contents, then confirm. Nothing changes before you approve. ":"")+"Volt pauses until you reopen it.")
                .setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,n)->beginBackup(restore,preselect)).create();
        controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    private void beginBackup(boolean restore,String preselect){
        if(enteringMaintenance||destroyed)return;
        if(binding&&!bound){toast("Still opening. Try again.");return;}
        if(!preparingExports.isEmpty()||documents.contains("path")||applyingOwnerImport||pendingOwnerFile().exists()||new File(getFilesDir(),"pending-owner-import.tmp").exists()){
            toast("Finish the current import or export first.");return;
        }
        ShizukuCaptureClient capture=ShizukuCaptureClient.get(this);
        if(capture.busy||capture.pendingCleanup()){toast("Finish Shizuku cleanup first");return;}
        if(scooterService==null)scooterService=ScooterService.currentForMaintenance();
        String unavailable=scooterService==null?null:scooterService.dataMaintenanceUnavailable();
        if(unavailable!=null){toast(unavailable);return;}
        enteringMaintenance=true;resumed=false;connectionHaptics.cancel();pendingConnect=pendingScan=false;
        closeQuickMenu();if(scanDialog!=null)scanDialog.dismiss();if(controlDialog!=null)controlDialog.dismiss();
        if(screen!=null){screen.close();screen=null;}onboarding=null;
        preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        handler.removeCallbacksAndMessages(null);fold.stop();
        if(scooterService!=null)scooterService.unlisten(serviceChanged);
        TextView wait=design.text("Preparing your app data…",20,true);wait.setGravity(Gravity.CENTER);wait.setBackgroundColor(design.bg);setContentView(wait);
        java.util.function.Consumer<String> prepared=error->{
            if(!DataMaintenance.active())DataMaintenance.enter();
            Intent transfer=new Intent(this,BackupActivity.class).putExtra("restore",restore);
            if(preselect!=null)transfer.putExtra("preselect",preselect);
            if(error!=null)transfer.putExtra("preparation_error",error);
            DataMaintenance.preparedTransfer(transfer);
            startActivity(transfer);stopService(new Intent(this,ScooterService.class));finish();
        };
        if(scooterService==null){DataMaintenance.enter();prepared.accept(null);}
        else scooterService.prepareDataMaintenance(prepared);
    }
    @Override public void importOwner(){
        if(pendingOwnerFile().exists()){maybeApplyOwnerImport();if(pendingOwnerFile().exists())toast("Owner import waiting…");return;}
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});startActivityForResult(intent,IMPORT_OWNER);
    }
    @Override public void saveText(String name,String mime,String content){prepareSave(name,mime,null,content==null?"":content);}
    @Override public void saveFile(String name,String mime,File content){if(content==null||!content.isFile()){toast("The export file is unavailable.");return;}prepareSave(name,mime,content,null);}
    private void prepareSave(String name,String mime,File source,String text){
        if(documents.contains("path")){toast("Finish the current save before starting another export.");return;}
        File staging=new File(getFilesDir(),"pending-export-"+System.currentTimeMillis());preparingExports.add(staging.getAbsolutePath());documents.edit().putString("path",staging.getAbsolutePath()).putString("name",name).putString("mime",mime).putBoolean("ready",false).putBoolean("picker",false).commit();
        new Thread(()->{try(OutputStream out=new FileOutputStream(staging)){if(source==null)out.write(text.getBytes(StandardCharsets.UTF_8));else try(InputStream in=new FileInputStream(source)){copy(in,out);}documents.edit().putBoolean("ready",true).commit();handler.post(this::maybeLaunchSave);}catch(Exception e){staging.delete();documents.edit().clear().commit();handler.post(()->toast("Could not prepare the export."));}finally{preparingExports.remove(staging.getAbsolutePath());}},"Volt-export").start();
    }
    private void maybeLaunchSave(){
        if(!resumed||destroyed||!documents.getBoolean("ready",false)||documents.getBoolean("picker",false))return;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(documents.getString("mime","application/octet-stream"));intent.putExtra(Intent.EXTRA_TITLE,documents.getString("name","navee-export"));documents.edit().putBoolean("picker",true).commit();
        try{startActivityForResult(intent,SAVE_DOCUMENT);}catch(Exception e){documents.edit().putBoolean("picker",false).apply();toast("A document picker could not open.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==ShizukuSetupActivity.REQUEST){
            if(result==ShizukuSetupActivity.RESULT_IMPORT_BACKUP&&onboarding!=null){onboarding.showImport();return;}
            OwnerIdentityParser.Result owner=ShizukuCaptureClient.get(this).takeApproved();
            if(result==RESULT_OK&&owner!=null){try{
                if(pendingOwnerFile().exists())throw new IllegalStateException();
                JSONObject setup=new JSONObject();setup.put("owner_identity",owner.identity);setup.put("role",owner.role);setup.put("name",IMPORTED_NAME);
                stageOwnerImport(setup.toString());
                if(onboarding!=null)onboarding.onOwnerImportStaged();
                maybeApplyOwnerImport();
            }catch(Exception ignored){toast("Import failed. Retry setup.");}}
            return;
        }
        if(request==ENABLE_BLUETOOTH){if(result==RESULT_OK){if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}}else pendingConnect=pendingScan=false;return;}
        if(request==SAVE_DOCUMENT){File staging=new File(documents.getString("path",new File(getFilesDir(),"missing-export").getAbsolutePath()));if(result!=RESULT_OK||data==null||data.getData()==null){staging.delete();documents.edit().clear().apply();return;}Uri destination=data.getData();new Thread(()->{try(InputStream in=new FileInputStream(staging);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new java.io.IOException();copy(in,out);handler.post(()->toast("Saved."));}catch(Exception e){handler.post(()->toast("The export could not be saved."));}finally{staging.delete();documents.edit().clear().commit();}},"Volt-save").start();return;}
        if(request==IMPORT_LAYOUTS&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri uri=data.getData();
            new Thread(()->{try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new java.io.IOException();
                String text=readLimited(in); DashboardConfig.LayoutTransfer info=DashboardConfig.inspectLayouts(text);
                handler.post(()->confirmDashboardImport(text,info));
            }catch(Exception error){handler.post(()->toast("Not a Volt layout file. Nothing changed."));}},"Volt-layout-import").start();
            return;
        }
        if(request==IMPORT_OWNER&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();pickedOwnerUri=null;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new java.io.IOException();stageOwnerImport(readLimited(in));pickedOwnerUri=uri;maybeApplyOwnerImport();}catch(Exception e){pickedOwnerUri=null;
            // First-run setup shows its own inline "Not an owner file."; elsewhere the parser's reason is a toast.
            if(onboarding==null)toast(e instanceof IllegalArgumentException?e.getMessage():"The setup file could not be read.");else onboarding.onOwnerImported(false);}}
    }
    /** Only the normalized owner setup is staged, never raw Bluetooth capture logs. */
    private void stageOwnerImport(String text)throws Exception{
        OwnerIdentityParser.Result parsed=OwnerIdentityParser.parse(text);String name=IMPORTED_NAME,mac="",serial="";
        try{JSONObject json=new JSONObject(text);name=json.optString("name",name);mac=json.optString("scooter_address","");serial=json.optString("scooter_serial","");}catch(Exception ignored){}
        name=name.trim();if(name.isEmpty())name=IMPORTED_NAME;if(name.length()>80)name=name.substring(0,80);
        if(!mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))mac="";else mac=mac.toUpperCase(Locale.ROOT);if(!serial.matches("[A-Za-z][A-Za-z0-9]{16}"))serial="";
        JSONObject normalized=new JSONObject();normalized.put("format","navee-private-pending-owner-v1");normalized.put("import_id",UUID.randomUUID().toString());normalized.put("owner_identity",parsed.identity);normalized.put("role",parsed.role);normalized.put("name",name);normalized.put("scooter_address",mac);normalized.put("scooter_serial",serial);
        writePendingOwnerImport(normalized);importWaitNotified=false;
    }
    private void writePendingOwnerImport(JSONObject normalized)throws java.io.IOException{
        File temporary=new File(getFilesDir(),"pending-owner-import.tmp");try(FileOutputStream out=new FileOutputStream(temporary)){out.write(normalized.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!temporary.renameTo(pendingOwnerFile())){temporary.delete();throw new java.io.IOException("Could not stage owner setup");}
    }
    /** The document the rider picked for the last owner import, so its Downloads copy can be removed. */
    private Uri pickedOwnerUri;
    /** True while the owner-setup document picked during this session can still be offered for deletion. */
    public boolean hasPickedOwnerFile(){return downloadsDocument(pickedOwnerUri);}
    /** Only a Downloads (or on-device Download folder) document is called a "Downloads copy". */
    private static boolean downloadsDocument(Uri uri){
        if(uri==null)return false;String authority=uri.getAuthority();
        if("com.android.providers.downloads.documents".equals(authority))return true;
        if(!"com.android.externalstorage.documents".equals(authority))return false;
        try{String id=android.provider.DocumentsContract.getDocumentId(uri);int colon=id.indexOf(':');String path=colon<0?id:id.substring(colon+1);
            return path.equals("Download")||path.startsWith("Download/");}catch(RuntimeException invalid){return false;}
    }
    /** Deletes the picked owner-setup document; false when nothing was picked or the provider cannot delete it. */
    public boolean deletePickedOwnerFile(){
        Uri uri=pickedOwnerUri;if(!downloadsDocument(uri))return false;
        try{if(!android.provider.DocumentsContract.deleteDocument(getContentResolver(),uri))return false;pickedOwnerUri=null;return true;}
        catch(Exception unsupported){return false;}
    }
    /** Name given to an imported profile whose setup file carries none. */
    static final String IMPORTED_NAME="Imported scooter";
    private File pendingOwnerFile(){return new File(getFilesDir(),"pending-owner-import.json");}
    private void maybeApplyOwnerImport(){
        File pending=pendingOwnerFile();if(destroyed||!resumed||applyingOwnerImport||!pending.isFile())return;
        if(scooterService==null){if(hasBluetoothPermission())ensureService();return;}
        String unavailable=scooterService.scooterChangeUnavailable();
        if(unavailable!=null){if(!importWaitNotified){toast(unavailable);importWaitNotified=true;}return;}
        applyingOwnerImport=true;
        try(InputStream in=new FileInputStream(pending)){
            String text=readLimited(in);OwnerIdentityParser.Result parsed=OwnerIdentityParser.parse(text);JSONObject json=new JSONObject(text);String importId=json.getString("import_id");
            if(!"navee-private-pending-owner-v1".equals(json.optString("format")))throw new IllegalArgumentException("Import failed. Pick file again.");
            String newProfileId=ScooterProfiles.ownerImportProfileId(importId),targetId=json.optString("target_profile_id","");
            boolean applied=importId.equals(preferences.getString("owner_import_applied",""));
            String name=json.optString("name",IMPORTED_NAME),mac=json.optString("scooter_address",""),serial=json.optString("scooter_serial","");
            // NAVEE's owner identity belongs to the account, not the physical scooter. An earlier
            // unconnected scooter with the same identity must not absorb a fresh Add a scooter flow.
            ScooterProfiles.Profile match=null;
            for(ScooterProfiles.Profile profile:scooterService.profiles.all()){
                if(profile.id.equals(targetId.isEmpty()?newProfileId:targetId)){match=profile;break;}
            }
            if(match!=null&&!ownerImportCompatible(match,parsed,mac,serial))throw new IllegalArgumentException("Saved setup no longer matches. Pick file again.");
            if(match==null&&!targetId.isEmpty()&&!targetId.equals(newProfileId))throw new IllegalArgumentException("That scooter was removed. Pick file again.");
            if(match==null&&targetId.isEmpty()){
                for(ScooterProfiles.Profile profile:scooterService.profiles.all()){
                    if(!ownerImportCompatible(profile,parsed,mac,serial))continue;
                    if((!serial.isEmpty()&&serial.equals(profile.serial))||(!mac.isEmpty()&&mac.equalsIgnoreCase(profile.mac))){match=profile;break;}
                }
                if(match==null&&onboarding!=null){
                    ScooterProfiles.Profile earlier=onboarding.ownerImportProfile();
                    if(ownerImportCompatible(earlier,parsed,mac,serial))match=earlier;
                }
            }
            if(applied&&match==null)throw new IllegalArgumentException("Imported scooter is unavailable. Pick file again.");
            if(applied&&onboarding==null){pending.delete();return;}
            // Bind an existing target in the private recovery file before changing it. Newly
            // created targets use the request's deterministic ID from their very first save.
            String selectedId=match==null?newProfileId:match.id;
            if(!selectedId.equals(targetId)){json.put("target_profile_id",selectedId);writePendingOwnerImport(json);}
            // A picker return can trigger automatic reconnect. Cancel that session before
            // selecting a profile, after checking recording and pending-command state.
            scooterService.ble.disconnect();
            // Volt's own fallback stays a default name, so a later model name can replace it.
            if(match==null)match=scooterService.profiles.createForOwnerImport(importId,name,parsed.identity,parsed.role);
            else{scooterService.profiles.select(match.id);scooterService.ble.profileSelected();}
            if(match.id.equals(newProfileId)&&(match.name.trim().isEmpty()||IMPORTED_NAME.equals(match.name.trim())))match.nameDefault=true;
            if(!mac.isEmpty())match.mac=mac;if(!serial.isEmpty())match.serial=serial;scooterService.profiles.update(match);
            // Commit the existing profile snapshot before deleting its recovery staging file.
            SharedPreferences profilePrefs=getSharedPreferences("profiles",MODE_PRIVATE);String savedProfiles=profilePrefs.getString("items","[]");if(!profilePrefs.edit().putString("items",savedProfiles).commit())throw new java.io.IOException();
            if(!preferences.edit().putString("owner_import_applied",importId).commit())throw new java.io.IOException();
            // During first-run setup the flow connects at its own step, after Nearby devices.
            if(onboarding!=null){onboarding.onOwnerImported(true);pending.delete();importWaitNotified=false;return;}
            pending.delete();importWaitNotified=false;
            selectedPage="home";scrollPositions.put("home",0);scrollPage="home";if(scroll!=null)scroll.scrollTo(0,0);rebuild();toast("Owner setup imported");scooterService.ble.connectLast();if(match.mac.isEmpty())showScanner(false);
        }catch(IllegalArgumentException e){pending.delete();if(onboarding==null)toast(e.getMessage());else onboarding.onOwnerImported(false);}
        catch(Exception e){if(!importWaitNotified){toast("Import paused. Will retry.");importWaitNotified=true;}}
        finally{applyingOwnerImport=false;}
    }
    /** Missing hardware identifiers are not matches, but known contradictions forbid reuse. */
    private static boolean ownerImportCompatible(ScooterProfiles.Profile profile,OwnerIdentityParser.Result parsed,String mac,String serial){
        return profile!=null&&profile.identity.equalsIgnoreCase(parsed.identity)&&profile.role==parsed.role
                &&(serial.isEmpty()||profile.serial.isEmpty()||serial.equals(profile.serial))
                &&(mac.isEmpty()||profile.mac.isEmpty()||mac.equalsIgnoreCase(profile.mac));
    }
    private static String readLimited(InputStream in)throws java.io.IOException{ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;while((count=in.read(chunk))!=-1){if(buffer.size()+count>1048576)throw new IllegalArgumentException("Setup files must be smaller than 1 MiB.");buffer.write(chunk,0,count);}return new String(buffer.toByteArray(),StandardCharsets.UTF_8);}
    private static void copy(InputStream in,OutputStream out)throws java.io.IOException{byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);}
}
