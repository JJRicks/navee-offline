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
            RIDE_LOCATION_PERMISSION=104, IMPORT_OWNER=201, SAVE_DOCUMENT=202;
    private static final long BODY_REFRESH_MS=1000;
    private static final long MORE_RETENTION_MS=60_000;
    private static final String MORE_AWAY_AT="more_away_elapsed", MORE_AWAY_BOOT="more_away_boot";
    private static final java.util.Set<String> preparingExports=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final Handler handler=new Handler(Looper.getMainLooper());
    // Volt order: Scooter is the raised centre button of the floating tab bar.
    private final String[] pages={"home","stats","controls","rides","more"};
    private final String[] names={"Home","Stats","Scooter","Rides","More"};
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
    private TextView connectionTitle,connectionDetail,connectionAction,lockTitle,lockHint,profileTitle,offlineTitle,offlineDetail;
    private View lockReady,lockOffline;
    private LockControl lockControl;
    private boolean showDashboardDisconnect=true;
    private String selectedPage="home",scrollPage="home";
    private boolean bound,binding,resumed,destroyed,pendingConnect,pendingScan,expanded,rebuilding,applyingOwnerImport,importWaitNotified,bodyRefreshScheduled;
    private long lastBodyRefreshMs;
    private int topInset,bottomInset,leftInset,rightInset;
    private AlertDialog scanDialog, controlDialog;
    private AlertDialog quickMenuDialog;
    private MoreScreen quickMoreScreen;
    private final ArrayList<ControlRow> quickMenuRows = new ArrayList<>();
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
    private final Runnable settingsRebuild = this::rebuild;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged=(p,key)->{
        if(key.equals("theme")||key.equals("units")||key.equals("temperature_unit")) {
            handler.removeCallbacks(settingsRebuild);
            handler.postDelayed(settingsRebuild, Ui.motionEnabled() ? 210 : 0);
        } else if(key.equals("text_scale")) { handler.removeCallbacks(settingsRebuild); handler.post(settingsRebuild); }
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
        setTheme(darkTheme()?R.style.VoltDark:R.style.VoltLight);
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
        expireMoreSection(saved!=null);
        fold=new FoldMonitor(this,()->{if(!destroyed)rebuild();});
        preferences.registerOnSharedPreferenceChangeListener(settingsChanged);
        rebuild();
    }
    @Override public void onResume(){
        super.onResume();resumed=true;ScooterService.setUiVisible(true);fold.start();
        if(selectedPage.equals("more")){boolean expired=expireMoreSection(true);markMoreActive();if(expired)rebuild();}
        if(hasBluetoothPermission()){ensureService();if(bound&&scooterService!=null)startService(new Intent(this,ScooterService.class));}
        if(scooterService!=null){maybeApplyOwnerImport();scooterService.visible(true);if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}if(scooterService.ble.connected())scooterService.ble.refresh(false);}
        handler.removeCallbacks(pulse);handler.post(pulse);
        finishRidePermission();
    }
    @Override public void onPause(){
        if(selectedPage.equals("more"))markMoreAway();
        resumed=false;if(lockControl!=null)lockControl.stopMotion();ScooterService.setUiVisible(false);captureScroll();fold.stop();handler.removeCallbacks(pulse);cancelDeferredBodyRefresh();
        if(scooterService!=null)scooterService.visible(false);
        super.onPause();
    }
    @Override public void onDestroy(){
        if(Build.VERSION.SDK_INT>=33&&systemBack!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBack);
        destroyed=true;closeQuickMenu();pendingRideLocation=null;if(controlDialog!=null)controlDialog.dismiss();bodyRefreshScheduled=false;handler.removeCallbacksAndMessages(null);fold.stop();preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
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
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && screen instanceof DashboardScreen)
            ((DashboardScreen) screen).onUserTouch();
        return super.dispatchTouchEvent(event);
    }
    @Override public void onBackPressed(){navigateBack();}
    private void navigateBack(){
        if(screen instanceof OnboardingScreen){if(!((OnboardingScreen)screen).back())finish();return;}
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
    private Context dialogContext(){return new ContextThemeWrapper(this,design.dark?R.style.VoltDialog:R.style.VoltDialogLight);}
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
        if (quickMoreScreen != null) quickMoreScreen.refresh();
        if (choiceSheet != null) choiceSheet.refresh();
        if(resumed&&scooterService!=null&&scooterService.isRecording()&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!preferences.getBoolean("notification_prompted",false)){preferences.edit().putBoolean("notification_prompted",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);}
        boolean awake=resumed&&preferences.getBoolean("keep_screen_awake",true)&&scooterService!=null&&scooterService.isRecording();
        if(awake)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void rebuild(){
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
            clearDashboardLock();profileTitle=null;scroll=null;pageFrame=null;
            onboarding=new OnboardingScreen(this,new OnboardingScreen.Listener(){public void onFinished(){onboarding=null;selectedPage="home";rebuild();}});
            screen=onboarding;root.addView(onboarding.view(),new FrameLayout.LayoutParams(-1,-1));
            setContentView(root);root.requestApplyInsets();styleSystemBars();
            rebuilding=false;redraw();return;
        }
        onboarding=null;
        LinearLayout shell=column();root.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        simulationNotice = design.text("Simulated scooter · Sample data · Exit", 12, true);
        simulationNotice.setTag("simulation-banner");
        simulationNotice.setTextColor(design.onAccent);
        simulationNotice.setBackgroundColor(design.accent);
        simulationNotice.setGravity(Gravity.CENTER);
        simulationNotice.setMinHeight(design.dp(48));
        simulationNotice.setPadding(design.dp(12), design.dp(6), design.dp(12), design.dp(6));
        simulationNotice.setContentDescription("Simulated scooter. Sample data, Bluetooth paused. Tap to exit simulation.");
        simulationNotice.setOnClickListener(v -> {
            if (scooterService != null) { scooterService.setSimulation(false); rebuild(); }
        });
        simulationNotice.setVisibility(displayedSimulation ? View.VISIBLE : View.GONE);
        shell.addView(simulationNotice, new LinearLayout.LayoutParams(-1, -2));
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
        screen=selectedPage.equals("home")?new DashboardScreen(this,new DashboardScreen.Actions(){public View lock(boolean showDisconnect){return makeDashboardLock(showDisconnect);}public void clearLock(){clearDashboardLock();}public void navigate(String page){select(page);}public void quickAction(String id){MainActivity.this.quickAction(id);}public void rename(){renameScooter();}public void connectionDetails(){showConnectionDetails();}public void startRide(){select("rides");if(screen instanceof RidesScreen)((RidesScreen)screen).beginRecording();}}):selectedPage.equals("controls")?new ControlsScreen(false):scooterService==null?new LoadingScreen():selectedPage.equals("stats")?new StatsScreen(this):selectedPage.equals("rides")?new RidesScreen(this):new MoreScreen(this);
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
            View bar=design.floatingNav(names,icons,java.util.Arrays.asList(pages).indexOf(selectedPage),NAV_CENTER,i->select(pages[i]));
            FrameLayout.LayoutParams barParams=new FrameLayout.LayoutParams(-1,design.floatingNavHeight(),Gravity.BOTTOM);
            barParams.leftMargin=design.dp(14);barParams.rightMargin=design.dp(14);barParams.bottomMargin=design.dp(10);
            pageFrame.addView(bar,barParams);
            scroll.setClipToPadding(false);
            scroll.setPadding(scroll.getPaddingLeft(),scroll.getPaddingTop(),scroll.getPaddingRight(),scroll.getPaddingBottom()+design.floatingNavHeight()+design.dp(16));
        }
        setContentView(root);root.requestApplyInsets();
        styleSystemBars();
        int restore=scrollPositions.containsKey(selectedPage)?scrollPositions.get(selectedPage):0;ScrollView target=scroll;target.post(()->{if(scroll==target)target.scrollTo(0,restore);});
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
    private void clearDashboardLock(){if(lockControl!=null)lockControl.stopMotion();lockTitle=null;lockControl=null;lockHint=null;connectionTitle=null;connectionDetail=null;connectionAction=null;offlineTitle=null;offlineDetail=null;lockReady=null;lockOffline=null;}
    private LinearLayout makeHeader(){
        if(!selectedPage.equals("home"))return makeCompactHeader();
        // Home's header (status line, scooter name and edit pencil) belongs to the dashboard toolbar.
        clearDashboardLock();profileTitle=null;return column();
    }
    /** Volt lock card: a full-width bar you tap anywhere on, or slide, to lock and unlock. */
    private View makeDashboardLock(boolean showDisconnect){
        showDashboardDisconnect=showDisconnect;
        LinearLayout content=column();
        lockReady=column();
        lockTitle=design.text("Lock",15,true);
        // A weighted state beside an unbounded caption can leave only a few pixels for
        // "Unlocked" on a narrow tile. Keep the state on one line and stack the caption
        // when the actual text and icon widths need more room.
        lockTitle.setMaxLines(1);lockTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView kind=design.caption("Electronic lock");kind.setMaxLines(1);kind.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout head=new LockHeading(lockTitle,kind);
        ((LinearLayout)lockReady).addView(head);
        lockControl=new LockControl();
        LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,design.dp(58));bar.topMargin=design.dp(10);
        ((LinearLayout)lockReady).addView(lockControl,bar);
        content.addView(lockReady,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout offline=column();lockOffline=offline;
        View square=design.iconSquare("lock",design.dim);
        LinearLayout words=column();
        offlineTitle=design.text("Lock state unverified",14,true);words.addView(offlineTitle);
        offlineDetail=design.caption("");offlineDetail.setPadding(0,design.dp(2),0,0);words.addView(offlineDetail);
        LinearLayout line=new LockOfflineStatus(square,words,offlineTitle);
        line.setMinimumHeight(design.dp(48));
        line.setOnClickListener(v->showConnectionDetails());
        offline.addView(line);
        content.addView(offline,new LinearLayout.LayoutParams(-1,-2));
        // Connect/Cancel are always available; Disconnect is a per-widget choice.
        connectionAction=design.button("Connect",this::connect);
        content.addView(connectionAction);
        lockHint=design.caption("");lockHint.setVisibility(View.GONE);content.addView(lockHint);
        connectionDetail=design.caption("");connectionDetail.setVisibility(View.GONE);content.addView(connectionDetail);
        updateHeader();return content;
    }
    /** Choose the lock header's arrangement from its measured content, not a phone model. */
    private final class LockHeading extends LinearLayout {
        private final TextView state,kind;
        LockHeading(TextView state,TextView kind){
            super(MainActivity.this);this.state=state;this.kind=kind;
            setGravity(Gravity.CENTER_VERTICAL);
            addView(state,new LinearLayout.LayoutParams(0,-2,1));
            addView(kind,new LinearLayout.LayoutParams(-2,-2));
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();
            float stateWidth=state.getPaint().measureText(state.getText().toString())+state.getCompoundPaddingLeft()+state.getCompoundPaddingRight();
            float kindWidth=kind.getPaint().measureText(kind.getText().toString())+kind.getCompoundPaddingLeft()+kind.getCompoundPaddingRight();
            boolean stack=MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED&&stateWidth+design.dp(12)+kindWidth>width;
            setOrientation(stack?VERTICAL:HORIZONTAL);
            LinearLayout.LayoutParams stateSize=(LinearLayout.LayoutParams)state.getLayoutParams();
            int stateWidthParam=stack?-1:0;float stateWeight=stack?0:1;
            stateSize.width=stateWidthParam;stateSize.weight=stateWeight;
            LinearLayout.LayoutParams kindSize=(LinearLayout.LayoutParams)kind.getLayoutParams();
            int kindWidthParam=stack?-1:-2,kindTop=stack?design.dp(4):0,kindStart=stack?0:design.dp(12);
            kindSize.width=kindWidthParam;kindSize.topMargin=kindTop;kindSize.setMarginStart(kindStart);
            // Resolve relative margins for this pass without requesting another layout.
            kindSize.resolveLayoutDirection(kind.getLayoutDirection());
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    /** Let narrow offline cards use their full width for status instead of a thin text column. */
    private final class LockOfflineStatus extends LinearLayout {
        private final View symbol;
        private final LinearLayout words;
        private final TextView title;
        LockOfflineStatus(View symbol,LinearLayout words,TextView title){
            super(MainActivity.this);this.symbol=symbol;this.words=words;this.title=title;
            setGravity(Gravity.CENTER_VERTICAL);
            addView(symbol,new LinearLayout.LayoutParams(design.dp(34),design.dp(34)));
            addView(words,new LinearLayout.LayoutParams(0,-2,1));
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
            symbol.setVisibility(stack?GONE:VISIBLE);
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
    private void showConnectionDetails(){
        BleController ble=scooterService==null?null:scooterService.ble;
        ScooterProfiles.Profile profile=scooterService==null?null:scooterService.profiles.active();
        String detail=ble==null?(hasBluetoothPermission()?"Opening the local connection.":"Nearby devices permission is needed."):ble.message();
        if(profile!=null&&profile.lastConnected>0)
            detail+="\n\nLast connected: "+DisplayTime.dateTime(this,preferences,profile.lastConnected);
        boolean active=ble!=null&&(ble.connected()||ble.ready()||ble.state()==BleController.State.CONNECTING||ble.state()==BleController.State.AUTHENTICATING||ble.state()==BleController.State.SCANNING||ble.state()==BleController.State.WAITING);
        controlDialog=new AlertDialog.Builder(dialogContext()).setCustomTitle(dialogTitle("Connection")).setMessage(detail).setPositiveButton("Close",null)
                .setNegativeButton(active?"Disconnect":"Connect",(d,which)->{if(active){if(ble!=null)ble.disconnect();redraw();}else connect();}).create();
        controlDialog.show();styleDialog(controlDialog,active?0:AlertDialog.BUTTON_NEGATIVE);
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
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setCustomTitle(dialogTitle("Scooter name")).setView(field).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String name=input.getText().toString().trim();if(name.isEmpty()){input.setError("Enter a name.");return;}profile.name=name;scooterService.profiles.update(profile);dialog.dismiss();rebuild();});
    }
    /** Other tabs: a tappable connection line above the page title. The lock lives on Home. */
    private LinearLayout makeCompactHeader(){
        LinearLayout header=column();header.setPadding(design.dp(expanded?24:18),design.dp(10),design.dp(expanded?24:18),design.dp(6));
        String pageName="";for(int i=0;i<pages.length;i++)if(pages[i].equals(selectedPage))pageName=names[i];
        connectionTitle=connectionLabel();connectionTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,12.5f*design.textScale);
        connectionTitle.setTypeface(design.regular());header.addView(connectionTitle);
        TextView title=design.text(pageName,24,true);title.setPadding(0,design.dp(2),0,0);
        if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);header.addView(title);
        profileTitle=null;
        return header;
    }
    private View navigation(boolean rail){
        LinearLayout nav=rail?column():row();nav.setGravity(rail?Gravity.TOP|Gravity.CENTER_HORIZONTAL:Gravity.CENTER);nav.setBackgroundColor(design.bg);nav.setPadding(design.dp(rail?8:4),design.dp(rail?20:6),design.dp(rail?8:4),design.dp(rail?12:6));
        for(int i=0;i<pages.length;i++){final String page=pages[i];View item=design.nav(names[i],icons[i],selectedPage.equals(page),()->select(page));LinearLayout.LayoutParams p=rail?new LinearLayout.LayoutParams(-1,design.dp(78)):new LinearLayout.LayoutParams(0,-2,1);if(rail)p.bottomMargin=design.dp(8);nav.addView(item,p);}
        return nav;
    }
    private void select(String page){
        if(page.equals("lights")){preferences.edit().putString("scooter_section","Lights").apply();page="controls";if(page.equals(selectedPage)){rebuild();return;}}
        // Arriving from another tab opens the main Scooter page; only the Lights shortcut above opens Lights.
        else if(page.equals("controls")&&!selectedPage.equals("controls"))preferences.edit().putString("scooter_section","").apply();
        else if(page.equals("controls")&&screen instanceof ControlsScreen&&((ControlsScreen)screen).lights){((ControlsScreen)screen).showMain();return;}
        if(page.equals(selectedPage)){if(scroll!=null)scroll.smoothScrollTo(0,0);return;}
        captureScroll();
        if(selectedPage.equals("more"))markMoreAway();
        if(page.equals("more")){expireMoreSection(true);if(resumed)markMoreActive();}
        selectedPage=page;preferences.edit().putString("last_page",page).apply();rebuild();
        if(scroll!=null)design.reveal(scroll);
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
        BleController ble=scooterService==null?null:scooterService.ble;Boolean locked=ble==null?null:ble.locked();String pending=ble==null?null:ble.pending();boolean ready=ble!=null&&ble.ready(),home=selectedPage.equals("home");Boolean shownLock=locked;if(shownLock==null&&scooterService!=null){Integer saved=scooterService.displayFeatures().get("lock");if(saved!=null)shownLock=saved!=0;}
        boolean connecting=ble!=null&&(ble.state()==BleController.State.CONNECTING||ble.state()==BleController.State.AUTHENTICATING
                ||ble.state()==BleController.State.SCANNING||ble.state()==BleController.State.WAITING||ble.connected())&&!ready;
        ScooterProfiles.Profile active=scooterService==null?null:scooterService.profiles.active();
        long last=active==null?0:active.lastConnected;
        String scooterName=active==null?"your scooter":active.name;
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
            lockControl.update(shownLock,ready&&pending==null&&!editing);
            lockHint.setVisibility(View.GONE);
            if(offlineTitle!=null){
                // The scooter name is already in the Home header; repeating it can fill
                // a narrow card and push Cancel out of view at larger system font sizes.
                offlineTitle.setText(connecting?"Connecting":"Lock state unverified");
                offlineTitle.setContentDescription(connecting?"Connecting to "+scooterName:"Lock state unverified");
                offlineDetail.setText(connecting?"Tap for details":last>0?"Last verified "+DisplayTime.recent(this,preferences,last,System.currentTimeMillis()):"Connect to check the lock");
            }
        }
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
    /** Updates the action presentation without changing the Bluetooth transport. */
    private void updateConnectionAction(boolean ready,boolean connecting){
        BleController ble=scooterService==null?null:scooterService.ble;
        if(connectionAction!=null){
            boolean busy=ble!=null&&(ble.connected()||connecting||ready);
            connectionAction.setVisibility(!ready||showDashboardDisconnect?View.VISIBLE:View.GONE);
            String action=ready?"Disconnect":busy?"Cancel":"Connect";
            connectionAction.setContentDescription(ready?"Disconnect scooter":busy?"Cancel connection":"Connect scooter");
            // Only restyle on a real state change, not on every incoming BLE packet.
            if(!action.contentEquals(connectionAction.getText())){
                connectionAction.setText(action);
                connectionAction.setTextColor(busy?design.text:design.onAccent);
                connectionAction.setBackground(new android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(Ui.withAlpha(busy?design.accent:android.graphics.Color.WHITE,35)),
                        design.shape(busy?design.raised:design.accent,14,busy?design.outline:0),null));
            }
            final BleController bleRef=ble;
            connectionAction.setOnClickListener(v->{if(busy){if(bleRef!=null)bleRef.disconnect();redraw();}else connect();});
            connectionAction.setEnabled(!(screen instanceof DashboardScreen&&((DashboardScreen)screen).isEditing()));
        }
    }
    private final class LockControl extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint words=new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics wordMetrics=new Paint.FontMetrics();
        private final RectF track=new RectF();
        private Boolean value;
        private boolean available,dragging;
        private float downX,downY,previewX,position;
        private android.animation.ValueAnimator motion;
        LockControl(){super(MainActivity.this);setMinimumWidth(design.dp(48));setMinimumHeight(design.dp(48));setClickable(true);setFocusable(true);setContentDescription("Electronic lock switch");setOnClickListener(v->apply(!Boolean.TRUE.equals(value)));}
        private void apply(boolean target){if(available&&value!=null&&scooterService!=null&&target!=value){scooterService.ble.set("lock",target?1:0);redraw();}}
        void update(Boolean locked,boolean enabled){
            boolean changed=value==null?locked!=null:!value.equals(locked);
            boolean hadState=value!=null;
            value=locked;available=enabled;setEnabled(enabled);
            if(!enabled&&dragging){dragging=false;setPressed(false);releaseParent();settle(hadState&&locked!=null);}
            else if(changed&&!dragging)settle(hadState&&locked!=null);
            setContentDescription(locked==null?"Electronic lock state unavailable":"Electronic lock, "+(locked?"locked":"unlocked")+", tap to "+(locked?"unlock":"lock"));
            if(Build.VERSION.SDK_INT>=30)setStateDescription(locked==null?"Unavailable":locked?"Locked":"Unlocked");
            invalidate();
        }
        private void cancelMotion(){if(motion!=null){motion.cancel();motion=null;}}
        private void releaseParent(){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
        /** Position is visual only. Value/accessibility always reflect the last confirmed report. */
        private void settle(boolean animate){
            cancelMotion();
            final float target=Boolean.TRUE.equals(value)?1:0;
            if(!animate||!resumed||!isAttachedToWindow()||!isShown()||!Ui.motionEnabled()||Math.abs(target-position)<.001f){position=target;invalidate();return;}
            motion=android.animation.ValueAnimator.ofFloat(position,target);
            motion.setDuration(180);motion.setInterpolator(new android.view.animation.PathInterpolator(.2f,0,0,1));
            motion.addUpdateListener(animation->{position=(Float)animation.getAnimatedValue();invalidate();});
            motion.start();
        }
        void stopMotion(){cancelMotion();dragging=false;setPressed(false);releaseParent();position=Boolean.TRUE.equals(value)?1:0;invalidate();}
        @Override protected void onDetachedFromWindow(){stopMotion();super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE)stopMotion();}
        @Override public CharSequence getAccessibilityClassName(){return Switch.class.getName();}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setCheckable(value!=null);info.setChecked(Boolean.TRUE.equals(value));info.setEnabled(available);}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(!available)return false;
            if(event.getAction()==MotionEvent.ACTION_DOWN){cancelMotion();downX=event.getX();downY=event.getY();previewX=downX;dragging=false;setPressed(true);return true;}
            if(event.getAction()==MotionEvent.ACTION_MOVE){float delta=event.getX()-downX;if(Math.abs(delta)>ViewConfiguration.get(MainActivity.this).getScaledTouchSlop()&&Math.abs(delta)>Math.abs(event.getY()-downY)){dragging=true;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);}if(dragging){previewX=event.getX();position=Math.max(0,Math.min(1,(previewX-getHeight()/2f)/Math.max(1,getWidth()-getHeight())));invalidate();}return true;}
            if(event.getAction()==MotionEvent.ACTION_UP){boolean dragged=dragging;boolean target=previewX>getWidth()/2f;dragging=false;setPressed(false);releaseParent();if(dragged)apply(target);else performClick();settle(true);return true;}
            if(event.getAction()==MotionEvent.ACTION_CANCEL){dragging=false;setPressed(false);releaseParent();settle(true);return true;}return true;
        }
        /** Locked: white knob on the right. Unlocked: red knob on the left. Tap anywhere or slide to change. */
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float x=0,y=0,w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
            boolean locked=Boolean.TRUE.equals(value),unlocked=Boolean.FALSE.equals(value);
            track.set(x+design.dp(1),y+design.dp(1),x+w-design.dp(1),y+h-design.dp(1));float r=track.height()/2;
            paint.setShader(null);paint.setStyle(Paint.Style.FILL);
            if(unlocked&&available){paint.setShader(new android.graphics.LinearGradient(track.left,0,track.right,0,Ui.withAlpha(design.accent,82),Ui.withAlpha(design.accent,20),android.graphics.Shader.TileMode.CLAMP));}
            else paint.setColor(design.dark?design.bg:design.raised);
            canvas.drawRoundRect(track,r,r,paint);paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(design.dp(1));paint.setColor(unlocked&&available?Ui.withAlpha(design.accent,110):design.raised);canvas.drawRoundRect(track,r,r,paint);paint.setStyle(Paint.Style.FILL);
            float knob=h/2-design.dp(5);
            float cx=x+h/2+Math.max(0,w-h)*position;
            paint.setColor(!available?design.dim:locked?android.graphics.Color.WHITE:design.accent);canvas.drawCircle(cx,h/2,knob,paint);
            design.drawIcon(canvas,locked?"lock":"unlock",!available?design.surface:locked?design.bg:design.onAccent,cx,h/2,knob*0.95f);
            String label=value==null?"Lock state unavailable":locked?"Tap or slide to unlock":"Tap or slide to lock";
            words.setTypeface(design.semibold());
            words.setTextSize(design.sp(13));words.setColor(!available?design.dim:unlocked?Ui.withAlpha(design.danger,235):design.muted);
            // Let the knob pass through the label's space without obscuring its words.
            words.setAlpha(Math.round(255*Math.max(0,1-4*Math.abs(position-(locked?1:0)))));
            float textLeft=locked||value==null?x+design.dp(18):x+h+design.dp(4),textRight=locked||value==null?x+w-h-design.dp(4):x+w-design.dp(18);
            float textWidth=Math.max(0,textRight-textLeft);
            if(words.measureText(label)>textWidth)label=value==null?"Unavailable":locked?"Unlock":"Lock";
            // The switch remains a full-height touch target even in a very narrow card.
            // Its accessibility label always keeps the full tap/slide action text.
            String fitted=words.measureText(label)<=textWidth?label:"";
            words.setTextAlign(Paint.Align.CENTER);words.getFontMetrics(wordMetrics);
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
        ScooterProfiles.Profile profile=scooterService.profiles.active();if(profile==null||!profile.configured()){select("more");toast("Add your owner identity to set up a scooter.");return;}
        BleController.State state=scooterService.ble.state();if(state==BleController.State.CONNECTING||state==BleController.State.AUTHENTICATING||scooterService.ble.ready())return;
        scooterService.ble.connectLast();if(profile.mac.isEmpty())showScanner(false);redraw();
    }
    private boolean hasBluetoothPermission(){return Build.VERSION.SDK_INT>=31?checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED:checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    @Override public void requestBluetooth(){if(!resumed)return;if(hasBluetoothPermission()){ensureService();if(scooterService!=null){if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}}return;}requestPermissions(Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION},BLUETOOTH_PERMISSION);}
    @Override public void requestRideLocation(Runnable ready){
        if (scooterService != null && scooterService.simulating()) { toast("Turn off simulation to record a real ride"); return; }
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
        if (scooterService != null && scooterService.simulating()) { toast("Exit simulation to find a real scooter"); return; }
        if(!resumed){pendingScan=true;return;}
        if(!hasBluetoothPermission()){pendingScan=true;requestBluetooth();return;}if(scooterService==null){pendingScan=true;ensureService();return;}
        if(scooterService.isRecording()){toast("Stop the ride recording before choosing another scooter.");return;}
        if(scooterService.ble.pending()!=null){toast("Wait for the current scooter change before opening discovery.");return;}
        android.bluetooth.BluetoothManager manager=getSystemService(android.bluetooth.BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();if(adapter==null){toast("This phone does not support Bluetooth.");return;}if(!adapter.isEnabled()){pendingScan=true;startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BLUETOOTH);return;}
        if(scooterService.profiles.active()==null){select("more");toast("Set up an owner profile before choosing a scooter.");return;}
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
        scanStatus.setText(scanning?"Searching nearby…":scanResults.isEmpty()?"No scooter found. Turn it on and scan again.":scanResults.size()==1?"1 scooter found":scanResults.size()+" scooters found");
        scanSpinner.setVisibility(scanning?View.VISIBLE:View.GONE);
        StringBuilder signature=new StringBuilder(String.valueOf(scanSelected));
        for(ScanResult result:scanResults){String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();signature.append('|').append(result.getDevice().getAddress()).append(':').append(signalRank(result.getRssi())).append(':').append(name);}
        if(!signature.toString().equals(scanSignature)){
            scanSignature=signature.toString();scanList.removeAllViews();
            for(ScanResult result:scanResults){
                String address=result.getDevice().getAddress();boolean match=!mine.isEmpty()&&address.equalsIgnoreCase(mine);
                scanList.addView(scanOption(result,match,address.equals(scanSelected)));
            }
        }
        scanConnect.setEnabled(scanSelected!=null);
    }
    /** Nearby scooter row (ph/10): scooter icon, name, signal bars; the selected scooter has a red outline. */
    private View scanOption(ScanResult result,boolean match,boolean selected){
        String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();if(name==null||name.isEmpty())name="Navee scooter";
        String address=result.getDevice().getAddress(),suffix=address.substring(Math.max(0,address.length()-5));
        int rank=signalRank(result.getRssi());String strength=rank==4?"Strong signal":rank==3?"Good signal":rank==2?"Fair signal":"Weak signal";
        LinearLayout option=row();option.setGravity(Gravity.CENTER_VERTICAL);option.setPadding(design.dp(12),design.dp(10),design.dp(14),design.dp(10));option.setMinimumHeight(design.dp(64));
        android.graphics.drawable.GradientDrawable face=new android.graphics.drawable.GradientDrawable();face.setCornerRadius(design.dp(16));
        face.setColor(selected?Ui.withAlpha(design.accent,design.dark?26:18):design.surface);face.setStroke(design.dp(1.5f),selected?design.accent:design.surface);
        option.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,30)),face,null));
        option.addView(design.iconSquare("scooter",design.accent),new LinearLayout.LayoutParams(design.dp(40),design.dp(40)));
        LinearLayout words=column();
        TextView title=design.text(name,15,true);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(title);
        TextView detail=design.caption(strength+(match?" · matches your scooter profile":"")+" · "+suffix);detail.setPadding(0,design.dp(3),0,0);words.addView(detail);
        LinearLayout.LayoutParams wordsParams=new LinearLayout.LayoutParams(0,-2,1);wordsParams.leftMargin=design.dp(12);wordsParams.rightMargin=design.dp(10);option.addView(words,wordsParams);
        SignalBars bars=new SignalBars(rank);int color=rank>=3?design.ok:design.orange;bars.set(rank,color,design.raised);
        option.addView(bars,new LinearLayout.LayoutParams(design.dp(18),design.dp(14)));
        option.setClickable(true);option.setFocusable(true);option.setSelected(selected);
        option.setContentDescription(name+", "+strength+(match?", matches your scooter profile":"")+", address ending "+suffix);
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
    private String featureLabel(ScooterCommands.Feature feature,int value){return DisplayUnits.featureValue(feature,value,preferences.getString("units","mi"));}
    private TextView metricLine(LinearLayout parent,String label){LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);TextView labelView=design.caption(label);line.addView(labelView,new LinearLayout.LayoutParams(0,-2,1));TextView value=design.text("—",16,true);value.setGravity(Gravity.RIGHT);line.addView(value);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=design.dp(8);p.bottomMargin=design.dp(8);parent.addView(line,p);return value;}
    private final class LoadingScreen implements AppScreen {
        private final LinearLayout view=column();
        LoadingScreen(){heading(view,"Your offline workspace",hasBluetoothPermission()?"Preparing local profiles and measurements…":"Allow Nearby devices to set up the local connection.");LinearLayout card=design.card();card.addView(design.text("Everything stays on your phone",18,true));TextView detail=design.caption("Scooter controls, owner profiles and ride recordings work through the local Bluetooth connection.");detail.setPadding(0,design.dp(8),0,design.dp(12));card.addView(detail);card.addView(design.button(hasBluetoothPermission()?"Open local workspace":"Allow Nearby devices",()->{if(hasBluetoothPermission())ensureService();else requestBluetooth();}));view.addView(card);}
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
        private final LinkedHashMap<String,LinearLayout> sections=new LinkedHashMap<>(), bodies=new LinkedHashMap<>();
        private final LinkedHashMap<String,TextView> labels=new LinkedHashMap<>();
        private final LinkedHashMap<View,String[]> extras=new LinkedHashMap<>();
        private final TextView results,bannerText,bannerAction,zonesValue,clockButton,nameValue;
        private final EditText search;
        private final LinearLayout lightsHeader,banner;
        private final View lightsNote,zonesRow;
        private final SettingLine clockLine;
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
            bannerText=design.text("Last seen settings · connect to change",13,false);
            LinearLayout.LayoutParams bannerWords=new LinearLayout.LayoutParams(0,-2,1);bannerWords.leftMargin=design.dp(10);bannerWords.rightMargin=design.dp(8);banner.addView(bannerText,bannerWords);
            bannerAction=pill("Connect",MainActivity.this::connect);bannerAction.setContentDescription("Connect scooter");banner.addView(bannerAction);
            LinearLayout.LayoutParams bannerParams=new LinearLayout.LayoutParams(-1,-2);bannerParams.bottomMargin=design.dp(10);view.addView(banner,bannerParams);
            // Wide: the banner (and the Lights help card) span both columns; search, results and top speed lead the left column.
            leftPane=expanded?column():null;rightPane=expanded?column():null;
            LinearLayout lead=expanded?leftPane:view;
            search=new EditText(MainActivity.this);search.setSingleLine(true);search.setHint("Search");design.styleSearch(search);search.setContentDescription("Search scooter settings");lead.addView(search,design.searchLayout());
            SearchFocus.bind(search, getWindow());
            results=design.caption("");results.setPadding(design.dp(3),0,0,design.dp(8));lead.addView(results);
            LinearLayout note=design.card();note.setOrientation(LinearLayout.HORIZONTAL);note.setGravity(Gravity.CENTER_VERTICAL);
            note.addView(design.icon("info",20,design.accent),new LinearLayout.LayoutParams(design.dp(20),design.dp(20)));
            TextView noteText=design.caption("Front headlight: use the scooter’s power button. Settings confirm configuration, not lamp output.");noteText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,13*design.textScale);
            LinearLayout.LayoutParams noteWords=new LinearLayout.LayoutParams(0,-2,1);noteWords.leftMargin=design.dp(12);note.addView(noteText,noteWords);
            lightsNote=note;view.addView(note);
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
            for(ScooterCommands.Feature f:ordered){
                // The top speed card replaces maximum_speed; experimental, lock, PIN and proximity stay out of this list.
                if(f.experimental||f.key.equals("lock")||f.key.equals("pin")||f.key.equals("proximity_key")||f.key.equals("maximum_speed"))continue;
                ControlRow control=new ControlRow(f);rows.add(control);addRow(groupOf(f),control.card);
            }
            SettingLine zones=new SettingLine("star","Line & star lights",null);zones.chevron(this::showLights);zonesValue=zones.value;zonesRow=zones.card;
            addRow("Lights",zonesRow);extras.put(zonesRow,new String[]{"Lights","line star lights zones ambient pattern color boost"});
            clockLine=new SettingLine("history","Sync scooter clock",null);
            clockButton=pill("Sync",()->{if(scooterService!=null){scooterService.ble.syncClock();redraw();}});clockButton.setContentDescription("Sync scooter clock");
            LinearLayout.LayoutParams clockParams=new LinearLayout.LayoutParams(-2,-2);clockParams.leftMargin=design.dp(10);clockLine.line.addView(clockButton,clockParams);
            addRow("Battery",clockLine.card);extras.put(clockLine.card,new String[]{"Battery","sync scooter clock scheduled charging time timezone"});
            SettingLine security=new SettingLine("shield","PIN & proximity unlock",null);security.chevron(()->openMoreSection("Security"));
            addRow("Security",security.card);extras.put(security.card,new String[]{"Security","pin password proximity unlock pairing bluetooth bond security"});
            SettingLine identity=new SettingLine("pencil","Scooter name",null);identity.chevron(MainActivity.this::renameScooter);nameValue=identity.value;
            addRow("Profile",identity.card);extras.put(identity.card,new String[]{"Profile","scooter name rename profile"});
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();applyFilter();}public void afterTextChanged(Editable text){}});
            applyFilter();
        }
        private String groupOf(ScooterCommands.Feature f){
            if(f.key.startsWith("line_"))return "Line lights";if(f.key.startsWith("star_"))return "Star lights";
            if(f.key.equals("headlight")||f.key.equals("auto_sensor"))return "Lights";
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
            for(ControlRow row:rows){String group=groupOf(row.feature);boolean match=searching?row.haystack.contains(needle):onPage(group);row.card.setVisibility(match?View.VISIBLE:View.GONE);if(match){count++;visible.add(group);}}
            for(Map.Entry<View,String[]> e:extras.entrySet()){
                String[] metadata=e.getValue();
                boolean match=e.getKey()==zonesRow&&lights?false:searching?normalize(metadata[1]).contains(needle):onPage(metadata[0]);
                e.getKey().setVisibility(match?View.VISIBLE:View.GONE);if(match){count++;visible.add(metadata[0]);}
            }
            boolean speed=searching?TOP_SPEED_WORDS.contains(needle):!lights;
            topSpeed.card.setVisibility(speed?View.VISIBLE:View.GONE);if(speed)count++;
            for(Map.Entry<String,LinearLayout> e:sections.entrySet()){
                e.getValue().setVisibility(visible.contains(e.getKey())?View.VISIBLE:View.GONE);
                LinearLayout body=bodies.get(e.getKey());boolean hasVisible=false;
                for(int i=0;i<body.getChildCount();i++){View child=body.getChildAt(i);if(child.getTag() instanceof View)child.setVisibility(hasVisible&&((View)child.getTag()).getVisibility()==View.VISIBLE?View.VISIBLE:View.GONE);else if(child.getVisibility()==View.VISIBLE)hasVisible=true;}
            }
            labels.get("Lights").setText((lights&&!searching?"Headlights":"Lights").toUpperCase(Locale.ROOT));
            lightsHeader.setVisibility(lights?View.VISIBLE:View.GONE);
            lightsNote.setVisibility(lights&&!searching?View.VISIBLE:View.GONE);
            results.setVisibility(searching?View.VISIBLE:View.GONE);results.setText(count==0?"No matching settings":count+(count==1?" setting":" settings"));
        }
        private void clearSearch(){
            if(search.getText().length()>0)search.setText("");search.clearFocus();
            android.view.inputmethod.InputMethodManager keyboard=getSystemService(android.view.inputmethod.InputMethodManager.class);
            if(keyboard!=null)keyboard.hideSoftInputFromWindow(search.getWindowToken(),0);
        }
        private void showLights(){lights=true;preferences.edit().putString("scooter_section","Lights").apply();clearSearch();placeSections();applyFilter();refresh();if(scroll!=null)scroll.scrollTo(0,0);design.reveal(view);}
        private void showMain(){lights=false;preferences.edit().putString("scooter_section","").apply();clearSearch();placeSections();applyFilter();refresh();if(scroll!=null)scroll.scrollTo(0,0);design.reveal(view);}
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
            topSpeed.refresh();
            BleController ble=scooterService==null?null:scooterService.ble;boolean ready=ble!=null&&ble.ready();
            boolean connecting=ble!=null&&!ready&&(ble.connected()||ble.state()==BleController.State.CONNECTING||ble.state()==BleController.State.AUTHENTICATING
                    ||ble.state()==BleController.State.SCANNING||ble.state()==BleController.State.WAITING);
            banner.setVisibility(ready?View.GONE:View.VISIBLE);
            bannerText.setText(connecting?"Connecting · showing last seen settings":"Last seen settings · connect to change");
            bannerAction.setVisibility(connecting?View.GONE:View.VISIBLE);
            boolean clockReady=ready&&ble.pending()==null;clockButton.setEnabled(clockReady);
            clockLine.name.setTextColor(clockReady?design.text:design.muted);
            String clock=ble==null?null:ble.commandResults().get("clock_sync");
            if(ble!=null&&"clock_sync".equals(ble.pending()))clock="Waiting for scooter…";
            else if(clock!=null)clock=clock.replace("; no clock readback available"," · no readback available").replace("; "," · ");
            clockLine.setSub(clock);
            zonesValue.setText(zonesSummary());
            ScooterProfiles.Profile profile=scooterService==null?null:scooterService.profiles.active();nameValue.setText(profile==null?"Not set":profile.name);
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
        if (feature.experimental) { showQuickMore("Lab", feature); return; }
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
            toast("This firmware has not supplied a fresh value for this setting."); return;
        }
        if (entry.directToggle()) {
            if (!ble.set(feature.key, actual == 1 ? 0 : 1)) toast(ble.message());
            redraw();
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
        for (ScooterCommands.Feature f : ScooterCommands.catalog()) {
            if (f.experimental || f.sensitive || f.key.equals("lock") || f.key.equals("proximity_key")) continue;
            boolean match = section.equals("Settings")
                    || section.equals("Lights") && (f.key.equals("headlight") || f.key.equals("auto_sensor")
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
            if (quickMenuDialog == dialog) { quickMenuDialog = null; quickMenuRows.clear(); }
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

    /** Phone-width labels; search still matches the catalog's full names and descriptions. */
    private String shortLabel(ScooterCommands.Feature f){
        if(f.key.equals("charge_start"))return "Charge start";
        if(f.key.equals("charge_limit"))return "Charge limit";
        if(f.key.endsWith("_boost_color"))return "Boost color";
        if(f.key.endsWith("_pattern"))return "Pattern";
        if(f.key.endsWith("_color"))return "Color";
        return f.label;
    }
    private String rowDetail(ScooterCommands.Feature f){
        if(f.key.equals("parking_assist"))return "Boost + full throttle uphill in Sport";
        if(f.key.equals("low_power"))return "Limits speed below 20%";
        if(f.key.equals("auto_sensor"))return "Ambient sensing for head and tail light";
        return null;
    }
    private String rowIcon(ScooterCommands.Feature f){
        if(f.key.equals("hill_start_assist"))return "walk";
        if(f.key.equals("charge_start")||f.key.equals("timed_charging"))return "clock";
        return design.featureIcon(f.key);
    }
    /** One line under a picker title; settings without a shorter wording keep the catalog description. */
    private String explanation(ScooterCommands.Feature f){
        if(f.key.equals("energy_recovery"))return "Stronger recovery slows coasting more.";
        if(f.key.equals("display_units"))return "Units shown on the scooter’s own dashboard.";
        if(f.key.equals("range_algorithm"))return "Changes how range is predicted, not the battery capacity.";
        if(f.key.equals("charge_limit"))return "Charging stops at this battery level.";
        if(f.key.equals("start_speed"))return "Wheel speed needed before the throttle responds. Zero allows standing starts.";
        return f.description;
    }
    private boolean inlineChoice(ScooterCommands.Feature f){return f.type==ScooterCommands.Type.CHOICE&&(f.key.startsWith("line_")||f.key.startsWith("star_"));}
    /** Short value forms for the right edge of a row; pickers still list the full labels. */
    private String valueText(ScooterCommands.Feature feature,int value){
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
            line.setGravity(Gravity.CENTER_VERTICAL);line.setMinimumHeight(design.dp(60));line.setPadding(0,design.dp(8),0,design.dp(8));
            name=design.text(label,15,false);name.setMaxLines(2);design.decorate(name,icon);words.addView(name);
            sub=design.caption("");sub.setPaddingRelative(icon==null||icon.isEmpty()?0:design.dp(42),design.dp(3),0,0);words.addView(sub);setSub(detail);
            line.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            value=design.text("",14,false);value.setTextColor(design.muted);value.setGravity(Gravity.END);value.setMaxLines(1);
            value.setEllipsize(android.text.TextUtils.TruncateAt.END);value.setMaxWidth(design.dp(124));
            LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);vp.leftMargin=design.dp(10);line.addView(value,vp);
            card.addView(line);
        }
        void chevron(Runnable action){
            View chevron=design.icon("chevron",15,design.muted);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(design.dp(15),design.dp(15));cp.leftMargin=design.dp(6);line.addView(chevron,cp);
            line.setClickable(true);line.setFocusable(true);line.setBackground(rowRipple());line.setOnClickListener(v->action.run());
        }
        void setSub(String text){sub.setText(text==null?"":text);sub.setVisibility(text==null||text.isEmpty()?View.GONE:View.VISIBLE);}
    }
    private final class ControlRow {
        final ScooterCommands.Feature feature;final LinearLayout card;final SettingLine row;final TextView value,name,result;final Switch toggle;final View action;
        final String haystack;final boolean inline;SegmentedSelector patternSelector;
        final LinkedHashMap<Integer,View> options=new LinkedHashMap<>();
        ControlRow(ScooterCommands.Feature feature){
            this.feature=feature;inline=inlineChoice(feature);
            String detail=rowDetail(feature);
            row=new SettingLine(inline?null:rowIcon(feature),shortLabel(feature),detail);card=row.card;name=row.name;value=row.value;action=row.line;
            haystack=normalize(feature.label+" "+shortLabel(feature)+" "+feature.description+" "+feature.key+" "+(detail==null?"":detail));
            if(feature.type==ScooterCommands.Type.TOGGLE){
                value.setVisibility(View.GONE);
                toggle=new Switch(MainActivity.this);toggle.setShowText(false);toggle.setTextOn("");toggle.setTextOff("");toggle.setMinHeight(design.dp(48));toggle.setMinimumWidth(design.dp(54));design.styleSwitch(toggle);
                toggle.setContentDescription(shortLabel(feature));
                toggle.setOnClickListener(v->{if(scooterService!=null){int target=toggle.isChecked()?1:0;Integer old=scooterService.ble.featureValue(feature.key);toggle.setChecked(old!=null&&old==1);scooterService.ble.set(feature.key,target);redraw();}});
                LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=design.dp(10);row.line.addView(toggle,tp);
                row.line.setFocusable(true);row.line.setBackground(rowRipple());row.line.setOnClickListener(v->{if(toggle.isEnabled())toggle.performClick();});
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
                    LinearLayout swatches=row();swatches.setGravity(Gravity.CENTER_VERTICAL);
                    for(int i=0;i<feature.optionValues.length;i++){
                        final int choice=feature.optionValues[i];String label=feature.options[i];
                        Swatch swatch=new Swatch(label.equals("Red")?design.accent:label.equals("Ice blue")?design.blue:design.muted);
                        swatch.setContentDescription(shortLabel(feature)+" "+label);swatch.setOnClickListener(v->chooseInline(feature,choice));
                        options.put(choice,swatch);swatches.addView(swatch,new LinearLayout.LayoutParams(design.dp(48),design.dp(48)));
                    }
                    row.line.addView(swatches);
                }
            }else{toggle=null;row.chevron(()->editFeature(feature));}
            row.line.setOnLongClickListener(v->{showAbout(feature);return true;});
            result=design.caption("");result.setPaddingRelative(inline?0:design.dp(42),0,0,design.dp(10));result.setVisibility(View.GONE);card.addView(result);
        }
        void refresh(){
            BleController ble=scooterService==null?null:scooterService.ble;Integer liveActual=ble==null?null:ble.featureValue(feature.key);Integer actual=liveActual!=null?liveActual:scooterService==null?null:scooterService.displayFeatures().get(feature.key);boolean available=ble!=null&&ble.ready()&&ble.pending()==null&&liveActual!=null&&scooterService.featureFresh(feature.key);
            String shown;
            if(feature.type==ScooterCommands.Type.TIME){Double seconds=ble==null?null:ble.telemetry.number("scheduled_charge_time_raw");if(seconds==null&&scooterService!=null){TelemetryHistory.Reading cached=scooterService.displayReadings().get("scheduled_charge_time_raw");if(cached!=null)seconds=cached.number;}available=ble!=null&&ble.ready()&&ble.pending()==null&&seconds!=null&&scooterService.readingFresh("scheduled_charge_time_raw");shown=seconds==null?"Not reported":seconds<=0?"Not scheduled":DisplayTime.recent(MainActivity.this,preferences,(long)(seconds*1000)-TimeZone.getDefault().getOffset(System.currentTimeMillis()),System.currentTimeMillis());}
            else shown=actual==null?"Not reported":valueText(feature,actual);
            boolean choices=feature.type!=ScooterCommands.Type.CHOICE||(ble!=null&&ble.featureChoices(feature.key).length>0);
            name.setTextColor(available?design.text:design.muted);action.setEnabled(available&&choices);
            if(toggle==null&&!inline){
                // A long value moves under the label so the row never wraps beside the chevron.
                boolean wide=shown.length()>10;value.setText(wide?"":shown);row.setSub(wide?shown:rowDetail(feature));
            }else if(inline)row.setSub(actual==null?"Not reported":null);
            if(patternSelector!=null){restorePatternSelection(true);patternSelector.setEnabled(available&&choices);patternSelector.setAlpha(available&&choices?1f:.5f);}
            for(Map.Entry<Integer,View> option:options.entrySet()){
                boolean on=actual!=null&&actual.intValue()==option.getKey();View view=option.getValue();
                ((Swatch)view).set(on);
                view.setEnabled(available&&choices);view.setAlpha(available&&choices?1f:.5f);
                if(Build.VERSION.SDK_INT>=30)view.setStateDescription(on?"Selected":"");
            }
            action.setContentDescription(shortLabel(feature)+", "+shown+". "+feature.description);
            if(toggle!=null){toggle.setEnabled(available);toggle.setChecked(actual!=null&&actual==1);if(Build.VERSION.SDK_INT>=30)toggle.setStateDescription(actual==null?"Not reported":actual==1?"On":"Off");}
            // Last-seen state is explained once by the page banner; rows keep rejections and pending changes.
            String feedback=ble==null?null:ble.commandResults().get(feature.key);if(feedback!=null&&(feedback.equals("Confirmed")||feedback.startsWith("Setting confirmed")))feedback=null;if(ble!=null&&feature.key.equals(ble.pending()))feedback="Waiting for scooter…";result.setVisibility(feedback==null?View.GONE:View.VISIBLE);result.setText(feedback==null?"":feedback);
        }
        private void restorePatternSelection(boolean animate){
            Integer actual=scooterService==null?null:scooterService.ble.featureValue(feature.key);
            if(actual==null&&scooterService!=null)actual=scooterService.displayFeatures().get(feature.key);
            int selected=-1;for(int i=0;i<feature.optionValues.length;i++)if(actual!=null&&actual==feature.optionValues[i])selected=i;
            patternSelector.select(selected,animate);
        }
    }
    private void chooseInline(ScooterCommands.Feature feature,int target){
        if(scooterService==null)return;Integer actual=scooterService.ble.featureValue(feature.key);if(actual!=null&&actual==target)return;
        if(!scooterService.ble.set(feature.key,target))toast(scooterService.ble.message());redraw();
    }
    private void showAbout(ScooterCommands.Feature feature){
        controlDialog=new AlertDialog.Builder(dialogContext()).setCustomTitle(dialogTitle(shortLabel(feature)))
                .setMessage(feature.description+(feature.requiresParked?"\n\nChanges only while the scooter is stopped.":"")).setPositiveButton("Close",null).create();
        controlDialog.show();styleDialog(controlDialog,0);
    }
    /** Lamp colour swatch; the selected colour gains a ring. */
    private final class Swatch extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int color;private boolean on;
        Swatch(int color){super(MainActivity.this);this.color=color;setClickable(true);setFocusable(true);
            setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,40)),null,null));}
        void set(boolean selected){if(on!=selected){on=selected;invalidate();}setSelected(selected);}
        @Override public CharSequence getAccessibilityClassName(){return android.widget.RadioButton.class.getName();}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setCheckable(true);info.setChecked(on);}
        @Override protected void onDraw(Canvas canvas){
            float cx=getWidth()/2f,cy=getHeight()/2f;paint.setStyle(Paint.Style.FILL);paint.setColor(color);canvas.drawCircle(cx,cy,design.dp(10),paint);
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
    /** Radio indicator: a grey ring, or a thick red ring when selected. */
    private final class RadioDot extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);boolean checked;
        RadioDot(){super(MainActivity.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void set(boolean value){if(checked!=value){checked=value;invalidate();}}
        @Override protected void onDraw(Canvas canvas){
            float width=design.dp(checked?5:2),radius=Math.min(getWidth(),getHeight())/2f-width/2f;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);paint.setColor(checked?design.accent:isEnabled()?design.muted:design.dim);
            canvas.drawCircle(getWidth()/2f,getHeight()/2f,radius,paint);
        }
    }
    /** Volt top speed card (ph/24): the current maximum in the rider's units and four raised choices. Commands stay in km/h. */
    private final class TopSpeedCard {
        final ScooterCommands.Feature feature=ScooterCommands.find("maximum_speed");
        final LinearLayout card=column(),options=row();
        final TextView title,amount,unit,status;
        final ArrayList<LinearLayout> buttons=new ArrayList<>();final ArrayList<TextView> numbers=new ArrayList<>();final ArrayList<SignalBars> bars=new ArrayList<>();
        private String styled="";
        TopSpeedCard(){
            card.setPadding(design.dp(16),design.dp(14),design.dp(16),design.dp(16));
            LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.bottomMargin=design.dp(2);card.setLayoutParams(cardParams);
            title=design.text("Top speed",12.5f,false);title.setTypeface(design.medium());card.addView(title);
            LinearLayout reading=row();reading.setPadding(0,design.dp(8),0,design.dp(14));
            amount=design.text("—",42,true);amount.setIncludeFontPadding(false);reading.addView(amount);
            unit=design.text("",14,false);unit.setPadding(design.dp(8),0,0,0);reading.addView(unit);
            card.addView(reading);unit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            for(int i=0;i<feature.optionValues.length;i++){
                final int kmh=feature.optionValues[i];
                LinearLayout button=column();button.setGravity(Gravity.CENTER);button.setMinimumHeight(design.dp(60));button.setPadding(0,design.dp(8),0,design.dp(10));
                SignalBars signal=new SignalBars(i+1);button.addView(signal,new LinearLayout.LayoutParams(design.dp(18),design.dp(12)));
                TextView number=design.text("",16,true);number.setGravity(Gravity.CENTER);number.setPadding(0,design.dp(6),0,0);button.addView(number);
                button.setClickable(true);button.setFocusable(true);button.setOnClickListener(v->choose(kmh));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=design.dp(7);options.addView(button,p);
                buttons.add(button);numbers.add(number);bars.add(signal);
            }
            card.addView(options,new LinearLayout.LayoutParams(-1,-2));
            status=design.text("",12,false);status.setPadding(0,design.dp(10),0,0);status.setVisibility(View.GONE);card.addView(status);
        }
        private String number(int kmh,String units){return "km".equals(units)?Integer.toString(kmh):Long.toString(Math.round(kmh/1.609344));}
        /** Spoken form: the rounded figure the rider sees, plus the exact OEM km/h value it sends. */
        private String spoken(int kmh,String units){return "km".equals(units)?kmh+" kilometres per hour":number(kmh,units)+" miles per hour, "+kmh+" kilometres per hour";}
        private void choose(int kmh){
            if(scooterService==null)return;BleController ble=scooterService.ble;
            Integer actual=ble.featureValue(feature.key);if(actual!=null&&actual==kmh)return;
            if(!ble.set(feature.key,kmh))toast(ble.message());redraw();
        }
        void refresh(){
            BleController ble=scooterService==null?null:scooterService.ble;
            Integer live=ble==null?null:ble.featureValue(feature.key);
            Integer actual=live!=null?live:scooterService==null?null:scooterService.displayFeatures().get(feature.key);
            boolean ready=ble!=null&&ble.ready();
            boolean available=ready&&ble.pending()==null&&live!=null&&scooterService.featureFresh(feature.key)&&ble.featureChoices(feature.key).length>0;
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
            String feedback=ble==null?null:ble.commandResults().get(feature.key);
            if(feedback!=null&&(feedback.equals("Confirmed")||feedback.startsWith("Setting confirmed")))feedback=null;
            if(ble!=null&&feature.key.equals(ble.pending()))feedback="Waiting for scooter confirmation…";
            status.setText(feedback==null?"":feedback);status.setVisibility(feedback==null?View.GONE:View.VISIBLE);
            String state=ready+"|"+available+"|"+actual;
            if(!state.equals(styled)){styled=state;style(!ready,available,actual);}
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
                buttons.get(i).setAlpha(available||grey?1f:.6f);
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
        dialog.show();
        android.view.Window window=dialog.getWindow();
        if(window!=null){
            android.graphics.drawable.GradientDrawable sheet=new android.graphics.drawable.GradientDrawable();sheet.setColor(design.bg);
            float r=design.dp(24);sheet.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});sheet.setStroke(design.dp(1),design.raised);
            window.setBackgroundDrawable(sheet);window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            window.getDecorView().setPadding(0,0,0,0);
        }
        return dialog;
    }
    private void editFeature(ScooterCommands.Feature feature){
        if(feature.type==ScooterCommands.Type.TIME){showChargingTime();return;}
        if(feature.type!=ScooterCommands.Type.SLIDER){showChoice(feature);return;}
        if(scooterService==null||!scooterService.ble.ready()||scooterService.ble.pending()!=null)return;
        Integer current=scooterService.ble.featureValue(feature.key);if(current==null)return;
        final AlertDialog[] sheet=new AlertDialog[1];Runnable close=()->{if(sheet[0]!=null)sheet[0].dismiss();};
        LinearLayout body=sheetContent(shortLabel(feature),explanation(feature),close);
        TextView amount=design.text(featureLabel(feature,current),28,true);amount.setPadding(design.dp(2),design.dp(4),0,design.dp(10));body.addView(amount);
        SeekBar slider=new SeekBar(this);slider.setMax((feature.max-feature.min)/feature.step);slider.setProgress(Math.max(0,(current-feature.min)/feature.step));slider.setProgressTintList(ColorStateList.valueOf(design.accent));slider.setThumbTintList(ColorStateList.valueOf(design.accent));slider.setProgressBackgroundTintList(ColorStateList.valueOf(design.outline));slider.setContentDescription(shortLabel(feature));body.addView(slider,new LinearLayout.LayoutParams(-1,design.dp(48)));
        final int[] selected={current};slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int n,boolean user){if(user){selected[0]=feature.min+n*feature.step;amount.setText(featureLabel(feature,selected[0]));}}public void onStopTrackingTouch(SeekBar s){}});
        TextView error=design.caption("");error.setTextColor(design.danger);error.setPadding(design.dp(2),design.dp(4),0,design.dp(4));body.addView(error);
        LinearLayout actions=row();
        TextView cancel=design.secondaryButton("Cancel",close);
        TextView apply=design.button("Apply",()->{Integer latest=scooterService.ble.featureValue(feature.key);if(!scooterService.ble.ready()||latest==null||!latest.equals(current)){error.setText("Scooter state changed. Close and reopen this setting.");return;}if(scooterService.ble.set(feature.key,selected[0])){if(sheet[0]!=null)sheet[0].dismiss();}else error.setText(scooterService.ble.message());redraw();});
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=design.dp(5);actions.addView(cancel,left);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.leftMargin=design.dp(5);actions.addView(apply,right);
        body.addView(actions);
        AlertDialog dialog=showSheet(body);sheet[0]=dialog;controlDialog=dialog;
    }
    private ChoiceSheet choiceSheet;
    private void showChoice(ScooterCommands.Feature feature){
        if(scooterService==null||!scooterService.ble.ready()||scooterService.ble.pending()!=null)return;
        int[] choices=scooterService.ble.featureChoices(feature.key);if(choices.length==0){toast("This choice is not available on the current firmware.");return;}
        choiceSheet=new ChoiceSheet(feature,choices);
    }
    /** Choice picker (ph/27). It stays open until the scooter confirms the new value by readback. */
    private final class ChoiceSheet {
        final ScooterCommands.Feature feature;final int[] choices;final AlertDialog dialog;
        final ArrayList<LinearLayout> options=new ArrayList<>();final ArrayList<RadioDot> dots=new ArrayList<>();final ArrayList<TextView> names=new ArrayList<>();
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
            if(selector!=null){selector.setTag("scooter-selector-"+feature.key);content.addView(selector,new LinearLayout.LayoutParams(-1,-2));}
            else{
            LinearLayout list=design.groupCard();
            for(int i=0;i<choices.length;i++){
                if(i>0)design.divider(list);
                final int index=i;
                LinearLayout option=row();option.setGravity(Gravity.CENTER_VERTICAL);option.setMinimumHeight(design.dp(54));option.setPadding(design.dp(4),design.dp(6),design.dp(2),design.dp(6));
                TextView label=design.text(featureLabel(feature,choices[i]),15,false);option.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                RadioDot dot=new RadioDot();LinearLayout.LayoutParams dotParams=new LinearLayout.LayoutParams(design.dp(22),design.dp(22));dotParams.leftMargin=design.dp(10);option.addView(dot,dotParams);
                option.setClickable(true);option.setFocusable(true);option.setBackground(rowRipple());option.setContentDescription(label.getText());
                option.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(android.widget.RadioButton.class.getName());info.setCheckable(true);info.setChecked(dot.checked);}});
                option.setOnClickListener(v->choose(index));
                list.addView(option);options.add(option);dots.add(dot);names.add(label);
            }
            content.addView(list);
            }
            wait=row();wait.setGravity(Gravity.CENTER_VERTICAL);wait.setPadding(design.dp(4),design.dp(4),0,design.dp(4));
            wait.addView(new Spinner(),new LinearLayout.LayoutParams(design.dp(14),design.dp(14)));
            TextView waiting=design.caption("Waiting for scooter confirmation…");waiting.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,13*design.textScale);
            LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-2,-2);wp.leftMargin=design.dp(10);wait.addView(waiting,wp);wait.setVisibility(View.GONE);content.addView(wait);
            status=design.caption("");status.setPadding(design.dp(4),design.dp(4),0,0);status.setVisibility(View.GONE);content.addView(status);
            dialog=showSheet(content);self[0]=dialog;controlDialog=dialog;
            dialog.setOnDismissListener(d->{if(choiceSheet==ChoiceSheet.this)choiceSheet=null;});
            refresh();
        }
        void choose(int index){
            if(selector!=null)restoreSelection(false);
            if(scooterService==null||sent)return;BleController ble=scooterService.ble;
            Integer actual=ble.featureValue(feature.key);if(actual!=null&&actual==choices[index]){dialog.dismiss();return;}
            failure=null;
            if(ble.set(feature.key,choices[index])){target=choices[index];sent=true;}else failure=ble.message();
            refresh();redraw();
        }
        void refresh(){
            BleController ble=scooterService==null?null:scooterService.ble;boolean ready=ble!=null&&ble.ready();String pending=ble==null?null:ble.pending();
            Integer actual=ble==null?null:ble.featureValue(feature.key);
            boolean waiting=sent&&feature.key.equals(pending);
            if(sent&&!waiting){
                sent=false;
                if(target!=null&&target.equals(actual)){dialog.dismiss();return;}
                String feedback=ble==null?null:ble.commandResults().get(feature.key);
                failure=feedback==null||feedback.equals("Confirmed")||feedback.startsWith("Setting confirmed")?"The scooter did not confirm this change.":feedback;target=null;
            }
            Integer shown=waiting?target:actual;boolean enabled=ready&&pending==null&&!sent;
            if(selector!=null){restoreSelection(true);boolean fresh=enabled&&actual!=null&&scooterService.featureFresh(feature.key);selector.setEnabled(fresh);selector.setAlpha(fresh?1f:.5f);}
            else for(int i=0;i<choices.length;i++){
                boolean on=shown!=null&&shown==choices[i];dots.get(i).set(on);dots.get(i).setEnabled(enabled);
                names.get(i).setTypeface(on?design.bold():design.regular());names.get(i).setTextColor(enabled||on?design.text:design.muted);
                options.get(i).setEnabled(enabled);if(Build.VERSION.SDK_INT>=30)options.get(i).setStateDescription(on?"Selected":"Not selected");
            }
            wait.setVisibility(waiting?View.VISIBLE:View.GONE);
            String message=failure!=null?failure:!ready&&!waiting?"Not connected · connect to change this setting":null;
            status.setText(message==null?"":message);status.setTextColor(failure!=null?design.danger:design.muted);status.setVisibility(message==null?View.GONE:View.VISIBLE);
        }
        private void restoreSelection(boolean animate){
            Integer actual=scooterService==null?null:scooterService.ble.featureValue(feature.key);int selected=-1;
            for(int i=0;i<choices.length;i++)if(actual!=null&&actual==choices[i])selected=i;
            selector.select(selected,animate);
        }
    }
    private void showChargingTime(){
        if(scooterService==null||!scooterService.ble.ready())return;Double stored=scooterService.ble.telemetry.number("scheduled_charge_time_raw");Calendar initial=Calendar.getInstance();if(stored!=null&&stored>0)initial.setTimeInMillis((long)(stored*1000)-TimeZone.getDefault().getOffset(System.currentTimeMillis()));
        TimePickerDialog picker=new TimePickerDialog(dialogContext(),(view,hour,minute)->{Calendar chosen=Calendar.getInstance();chosen.set(Calendar.HOUR_OF_DAY,hour);chosen.set(Calendar.MINUTE,minute);chosen.set(Calendar.SECOND,0);chosen.set(Calendar.MILLISECOND,0);if(chosen.getTimeInMillis()<=System.currentTimeMillis())chosen.add(Calendar.DAY_OF_YEAR,1);long timestamp=chosen.getTimeInMillis();scooterService.ble.setChargeStart(timestamp/1000,TimeZone.getDefault().getOffset(System.currentTimeMillis())/1000);redraw();},initial.get(Calendar.HOUR_OF_DAY),initial.get(Calendar.MINUTE),DisplayTime.is24Hour(this,preferences));picker.setTitle("Next charge start");controlDialog=picker;picker.show();
        styleDialogButton(picker.getButton(AlertDialog.BUTTON_POSITIVE),design.accent);styleDialogButton(picker.getButton(AlertDialog.BUTTON_NEGATIVE),design.muted);
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
        if(request==ShizukuSetupActivity.REQUEST){
            OwnerIdentityParser.Result owner=ShizukuCaptureClient.get(this).takeApproved();
            if(result==RESULT_OK&&owner!=null){try{
                if(pendingOwnerFile().exists())throw new IllegalStateException();
                JSONObject setup=new JSONObject();setup.put("owner_identity",owner.identity);setup.put("role",owner.role);setup.put("name","My UT5 MAX");
                stageOwnerImport(setup.toString());
                if(onboarding!=null)onboarding.onOwnerImportStaged();
                maybeApplyOwnerImport();
            }catch(Exception ignored){toast("The owner setup could not be imported. Retry setup when the pending import is finished.");}}
            return;
        }
        if(request==ENABLE_BLUETOOTH){if(result==RESULT_OK){if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}}else pendingConnect=pendingScan=false;return;}
        if(request==SAVE_DOCUMENT){File staging=new File(documents.getString("path",new File(getFilesDir(),"missing-export").getAbsolutePath()));if(result!=RESULT_OK||data==null||data.getData()==null){staging.delete();documents.edit().clear().apply();return;}Uri destination=data.getData();new Thread(()->{try(InputStream in=new FileInputStream(staging);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new java.io.IOException();copy(in,out);handler.post(()->toast("Saved."));}catch(Exception e){handler.post(()->toast("The export could not be saved."));}finally{staging.delete();documents.edit().clear().commit();}},"Navee-save").start();return;}
        if(request==IMPORT_OWNER&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new java.io.IOException();stageOwnerImport(readLimited(in));maybeApplyOwnerImport();}catch(Exception e){toast(e instanceof IllegalArgumentException?e.getMessage():"The setup file could not be read.");if(onboarding!=null)onboarding.onOwnerImported(false);}}
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
            // During first-run setup the flow connects at its own step, after Nearby devices.
            if(onboarding!=null){onboarding.onOwnerImported(true);return;}
            selectedPage="home";scrollPositions.put("home",0);scrollPage="home";if(scroll!=null)scroll.scrollTo(0,0);rebuild();toast("Owner setup imported privately. Connecting locally…");scooterService.ble.connectLast();if(match.mac.isEmpty())showScanner(false);
        }catch(IllegalArgumentException e){pending.delete();toast(e.getMessage());if(onboarding!=null)onboarding.onOwnerImported(false);}
        catch(Exception e){if(!importWaitNotified){toast("The private owner setup could not be applied. It is saved for retry.");importWaitNotified=true;}}
        finally{applyingOwnerImport=false;}
    }
    private static String readLimited(InputStream in)throws java.io.IOException{ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;while((count=in.read(chunk))!=-1){if(buffer.size()+count>1048576)throw new IllegalArgumentException("Setup files must be smaller than 1 MiB.");buffer.write(chunk,0,count);}return new String(buffer.toByteArray(),StandardCharsets.UTF_8);}
    private static void copy(InputStream in,OutputStream out)throws java.io.IOException{byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);}
}
