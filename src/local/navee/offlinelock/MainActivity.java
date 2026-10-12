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
            IMPORT_OWNER=201, SAVE_DOCUMENT=202;
    private static final long BODY_REFRESH_MS=1000;
    private static final java.util.Set<String> preparingExports=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final String[] pages={"home","controls","stats","rides","more"};
    private final String[] names={"Home","Controls","Stats","Rides","More"};
    private final String[] icons={"home","tune","stats","rides","settings"};
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
    private AlertDialog scanDialog;
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
    }
    @Override public void onPause(){
        resumed=false;ScooterService.setUiVisible(false);captureScroll();fold.stop();handler.removeCallbacks(pulse);cancelDeferredBodyRefresh();
        if(scooterService!=null)scooterService.visible(false);
        super.onPause();
    }
    @Override public void onDestroy(){
        destroyed=true;bodyRefreshScheduled=false;handler.removeCallbacksAndMessages(null);fold.stop();preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
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
        if(destroyed||rebuilding)return;rebuilding=true;
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
        screen=selectedPage.equals("home")?new HomeScreen():selectedPage.equals("controls")?new ControlsScreen():scooterService==null?new LoadingScreen():selectedPage.equals("stats")?new StatsScreen(this):selectedPage.equals("rides")?new RidesScreen(this):new MoreScreen(this);
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
    private LinearLayout makeHeader(){
        if(!selectedPage.equals("home"))return makeCompactHeader();
        LinearLayout header=column();header.setPadding(design.dp(expanded?24:18),design.dp(16),design.dp(expanded?24:18),0);
        LinearLayout brand=row();brand.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout title=column();title.addView(design.text("Navee Offline",24,true));profileTitle=design.caption("Your scooter, your controls");profileTitle.setPadding(0,design.dp(5),0,0);title.addView(profileTitle);brand.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        View more=design.icon("bluetooth",24,design.accent);more.setPadding(design.dp(8),design.dp(8),design.dp(8),design.dp(8));more.setContentDescription("Choose a nearby scooter");more.setFocusable(true);more.setOnClickListener(v->showScanner(true));brand.addView(more,new LinearLayout.LayoutParams(design.dp(48),design.dp(48)));header.addView(brand);
        LinearLayout lock=design.card();lock.setPadding(design.dp(20),design.dp(16),design.dp(20),design.dp(14));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=design.dp(16);lp.bottomMargin=design.dp(8);lock.setLayoutParams(lp);
        LinearLayout lockRow=row();lockRow.setGravity(Gravity.CENTER_VERTICAL);LinearLayout words=column();words.addView(design.caption("ELECTRONIC LOCK"));lockTitle=design.text("Waiting for scooter",25,true);lockTitle.setPadding(0,design.dp(8),0,design.dp(4));words.addView(lockTitle);lockRow.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        lockControl=new LockControl();LinearLayout.LayoutParams switchParams=new LinearLayout.LayoutParams(design.dp(112),design.dp(68));switchParams.leftMargin=design.dp(14);lockRow.addView(lockControl,switchParams);lock.addView(lockRow);
        lockHint=design.caption("Connect to see the confirmed lock state.");lockHint.setPadding(0,design.dp(4),0,0);lock.addView(lockHint);header.addView(lock);
        LinearLayout connection=row();connection.setGravity(Gravity.CENTER_VERTICAL);LinearLayout connectionWords=column();connectionTitle=design.text("Bluetooth only",13,true);connectionWords.addView(connectionTitle);connectionDetail=design.caption("Connect directly to your scooter.");connectionDetail.setMaxLines(2);connectionDetail.setPadding(0,design.dp(3),0,0);connectionWords.addView(connectionDetail);connection.addView(connectionWords,new LinearLayout.LayoutParams(0,-2,1));
        connectionAction=design.secondaryButton("Connect",this::connect);LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(-2,-2);action.leftMargin=design.dp(12);connection.addView(connectionAction,action);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=design.dp(12);header.addView(connection,cp);return header;
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
        if(rail){View fill=new View(this);nav.addView(fill,new LinearLayout.LayoutParams(1,0,1));TextView offline=design.caption("OFFLINE\nBY DESIGN");offline.setTextSize(10*design.textScale);offline.setGravity(Gravity.CENTER);offline.setLineSpacing(design.dp(5),1);nav.addView(offline);}
        return nav;
    }
    private void select(String page){if(page.equals(selectedPage)){if(scroll!=null)scroll.smoothScrollTo(0,0);return;}captureScroll();selectedPage=page;preferences.edit().putString("last_page",page).apply();rebuild();}
    private void captureScroll(){if(scroll!=null)scrollPositions.put(scrollPage,scroll.getScrollY());}
    private boolean horizontalHinge(){return fold!=null&&fold.hinge.width()>fold.hinge.height()&&fold.hinge.top>0&&fold.hinge.width()>0;}
    private boolean verticalHinge(){return fold!=null&&fold.hinge.height()>fold.hinge.width()&&fold.hinge.left>0&&fold.hinge.height()>0;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private void heading(LinearLayout parent,String title,String detail){TextView h=design.text(title,22,true);h.setPadding(0,design.dp(12),0,design.dp(6));parent.addView(h);if(detail!=null){TextView d=design.caption(detail);d.setPadding(0,0,0,design.dp(16));parent.addView(d);}}
    private void updateHeader(){
        if(lockTitle==null)return;BleController ble=scooterService==null?null:scooterService.ble;
        Boolean locked=ble==null?null:ble.locked();String pending=ble==null?null:ble.pending();boolean ready=ble!=null&&ble.ready();
        lockTitle.setText(locked==null?"State unavailable":locked?"Locked":"Unlocked");lockTitle.setTextColor(locked==null?design.muted:locked?design.orange:design.accent);
        lockControl.update(locked,ready&&pending==null);
        lockHint.setText(pending!=null?(pending.equals("lock")?"Waiting for the scooter to confirm…":"Applying a setting…"):ready?(Boolean.TRUE.equals(locked)?"Tap the switch to unlock.":"Tap the switch to lock."):(ble!=null&&ble.connected()?"Waiting for a fresh scooter reading.":"Connect to see the confirmed lock state."));
        String state=ble==null?(hasBluetoothPermission()?"Opening local connection…":"Nearby devices permission needed"):ble.state()==BleController.State.READY?(ble.ready()?"Connected · Bluetooth only":"Connected · Refreshing state"):ble.state()==BleController.State.WAITING?"Waiting for your scooter":ble.state()==BleController.State.SCANNING?"Finding nearby scooters":ble.state()==BleController.State.CONNECTING?"Connecting…":ble.state()==BleController.State.AUTHENTICATING?"Authenticating locally…":"Bluetooth only";
        connectionTitle.setText(state);connectionTitle.setTextColor(ready?design.accent:design.text);
        connectionDetail.setText(ble==null?(hasBluetoothPermission()?"Your connection stays local.":"Allow access to connect to your scooter."):ble.message());
        boolean active=ble!=null&&(ble.connected()||ble.state()==BleController.State.CONNECTING||ble.state()==BleController.State.AUTHENTICATING);connectionAction.setText(active?"Disconnect":"Connect");connectionAction.setOnClickListener(v->{if(active){ble.disconnect();redraw();}else connect();});
        ScooterProfiles.Profile p=scooterService==null?null:scooterService.profiles.active();profileTitle.setText(p==null?"Your scooter, your controls":p.name+(expanded&&fold!=null?" · "+fold.posture:""));
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
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==BLUETOOTH_PERMISSION){if(hasBluetoothPermission())requestBluetooth();else{pendingConnect=pendingScan=false;toast("Allow Nearby devices when you are ready to connect.");}redraw();}}
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
    private final class HomeScreen implements AppScreen {
        private final LinearLayout view=column();private final Ui.BatteryRing battery;private final TextView batteryTitle,batteryDetail,voltage,power,temperature,liveSpeed,range,odometer,rideTitle,rideDetail,rideButton,headlightValue,regenValue;
        private final Ui.ChartView chart;private final TextView chartCaption;private final TextView lightsButton,recoveryButton;
        HomeScreen(){
            LinearLayout primary=column(),secondary=column();heading(primary,"Ready for the road","A clear view of the scooter you are connected to.");
            LinearLayout batteryCard=design.card();LinearLayout batteryRow=row();batteryRow.setGravity(Gravity.CENTER_VERTICAL);battery=design.new BatteryRing();batteryRow.addView(battery,new LinearLayout.LayoutParams(design.dp(136),design.dp(164)));LinearLayout words=column();batteryTitle=design.text("Battery",24,true);words.addView(batteryTitle);batteryDetail=design.caption("Waiting for a battery reading.");batteryDetail.setPadding(0,design.dp(8),0,0);words.addView(batteryDetail);batteryRow.addView(words,new LinearLayout.LayoutParams(0,-2,1));batteryCard.addView(batteryRow);
            voltage=metricLine(batteryCard,"Pack voltage");power=metricLine(batteryCard,"Battery power");temperature=metricLine(batteryCard,"Pack temperature");primary.addView(batteryCard);
            LinearLayout rangeCard=design.card();liveSpeed=metricLine(rangeCard,"Live speed");liveSpeed.setTextColor(design.blue);range=metricLine(rangeCard,"Scooter's estimated range");odometer=metricLine(rangeCard,"Total odometer");rangeCard.addView(design.caption("The scooter's range prediction depends on its selected estimate mode."));primary.addView(rangeCard);
            LinearLayout ride=design.card();rideTitle=design.text("Capture your next ride",18,true);ride.addView(rideTitle);rideDetail=design.caption("Record battery, distance and energy to compare hills, Sport and Boost.");rideDetail.setPadding(0,design.dp(8),0,design.dp(10));ride.addView(rideDetail);rideButton=design.button("Start ride recording",()->{if(scooterService==null)return;if(scooterService.isRecording())scooterService.stopRide();else scooterService.startRide(new SimpleDateFormat("MMM d · h:mm a",Locale.US).format(Calendar.getInstance().getTime()));redraw();});ride.addView(rideButton);ride.addView(design.secondaryButton("View rides",()->select("rides")));primary.addView(ride);
            heading(secondary,expanded?"Live energy":"Live battery power","Measured values from the current connection.");LinearLayout powerCard=design.card();chart=design.new ChartView();powerCard.addView(chart);chartCaption=design.caption("The chart fills when the scooter supplies fresh electrical readings.");powerCard.addView(chartCaption);secondary.addView(powerCard);
            LinearLayout quick=design.card();quick.addView(design.text("Quick controls",18,true));headlightValue=metricLine(quick,"Headlight");lightsButton=design.secondaryButton("Toggle headlight",()->{Integer value=scooterService==null?null:scooterService.ble.featureValue("headlight");if(value!=null){scooterService.ble.set("headlight",value==1?0:1);redraw();}});quick.addView(lightsButton);regenValue=metricLine(quick,"Energy recovery");recoveryButton=design.secondaryButton("Choose recovery",()->showChoice(ScooterCommands.find("energy_recovery")));quick.addView(recoveryButton);quick.addView(design.secondaryButton("All controls",()->select("controls")));secondary.addView(quick);
            if(expanded&&!horizontalHinge())view.addView(new TwoPane(primary,secondary));else{view.addView(primary);view.addView(secondary);}
        }
        @Override public View view(){return view;}
        @Override public void refresh(){
            Map<String,Telemetry.Metric> values=metrics();Double percent=fresh(values,"battery_percent",15000),volts=fresh(values,"voltage_v",12000),watts=fresh(values,"power_w",5000),temp=fresh(values,"temperature_c",15000),charge=fresh(values,"charging_state",15000);
            boolean charging=charge!=null&&charge.intValue()==1;battery.set(percent,volts==null?"Waiting for voltage":number(volts,1,"V"),charging);
            batteryTitle.setText(percent==null?"Battery":charging?"Charging":"Your battery");Double health=fresh(values,"battery_health_percent",60000);batteryDetail.setText(percent==null?"Waiting for a fresh battery reading.":(health==null?"Charge reported by the scooter.":"Reported health "+number(health,0,"%")));
            voltage.setText(number(volts,2,"V"));power.setText(number(watts,0,"W"));temperature.setText(number(temp,0,"°C"));liveSpeed.setText(speed(fresh(values,"speed_kmh",5000)));range.setText(distance(fresh(values,"remaining_range_km",15000)));odometer.setText(distance(fresh(values,"odometer_km",30000)));
            ArrayList<Ui.Point> points=new ArrayList<>();if(scooterService!=null){long offset=System.currentTimeMillis()-SystemClock.elapsedRealtime();for(Ui.Point point:scooterService.trend("power_w"))points.add(new Ui.Point(offset+point.millis,point.value));}chart.set(points,"W",design.accent,"No fresh power readings yet");chartCaption.setText(watts==null?"Power is unavailable until the scooter supplies a complete signed electrical reading.":"Signed battery power · Last two minutes · Touch to inspect");
            boolean active=scooterService!=null&&scooterService.isRecording();rideTitle.setText(active?"Ride recording is active":"Capture your next ride");rideButton.setText(active?"Stop & save recording":"Start ride recording");rideButton.setEnabled(active||(scooterService!=null&&scooterService.ble.ready()));
            if(active&&scooterService.rides!=null){RideStore.Summary summary=scooterService.rides.activeSummary();rideDetail.setText(summary==null?"Saving measurements locally.":String.format(Locale.US,"%d:%02d elapsed  ·  %s recorded",summary.elapsedMs/60000,(summary.elapsedMs/1000)%60,distance(summary.distanceKm)));}else rideDetail.setText("Record battery, distance and energy to compare hills, Sport and Boost.");
            Integer headlight=scooterService==null?null:scooterService.ble.featureValue("headlight"),recovery=scooterService==null?null:scooterService.ble.featureValue("energy_recovery");headlightValue.setText(headlight==null?"—":headlight==1?"On":"Off");regenValue.setText(recovery==null?"—":ScooterCommands.find("energy_recovery").valueLabel(recovery));boolean enabled=scooterService!=null&&scooterService.ble.ready()&&scooterService.ble.pending()==null;lightsButton.setEnabled(enabled&&headlight!=null);recoveryButton.setEnabled(enabled&&recovery!=null);
        }
    }
    private final class ControlsScreen implements AppScreen {
        private final LinearLayout view=column();private final ArrayList<ControlRow> rows=new ArrayList<>();
        private final LinkedHashMap<String,LinearLayout> sections=new LinkedHashMap<>();
        private final LinkedHashMap<String,LinearLayout[]> columns=new LinkedHashMap<>();
        private final LinkedHashMap<View,String[]> extraCards=new LinkedHashMap<>();
        private final String[] categories={"Ride","Battery","Lights","Line lights","Star lights","Display","Security"};
        private final TextView results;private final View categoryTabs;private String category,query;
        private TextView clockButton;
        ControlsScreen(){
            category=preferences.getString("controls_section","Ride");if(!java.util.Arrays.asList(categories).contains(category))category="Ride";
            query=preferences.getString("controls_query","");
            heading(view,"Scooter settings","Choose a section or search all settings.");
            EditText search=new EditText(MainActivity.this);search.setSingleLine(true);search.setHint("Search lights, charging, speed…");search.setText(query);search.setTextSize(14*design.textScale);search.setTextColor(design.text);search.setHintTextColor(design.muted);search.setPadding(design.dp(14),design.dp(10),design.dp(14),design.dp(10));search.setMinimumHeight(design.dp(48));search.setBackground(design.shape(design.raised,14,design.outline));search.setContentDescription("Search scooter settings");view.addView(search,new LinearLayout.LayoutParams(-1,-2));
            categoryTabs=design.sectionTabs(categories,category,label->{category=label;query="";search.setText("");preferences.edit().putString("controls_section",category).putString("controls_query","").apply();android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(search.getWindowToken(),0);search.clearFocus();applyFilter();if(scroll!=null)scroll.smoothScrollTo(0,0);});view.addView(categoryTabs,new LinearLayout.LayoutParams(-1,-2));
            results=design.caption("");results.setPadding(0,0,0,design.dp(10));view.addView(results);
            for(String name:categories){LinearLayout group=column();sections.put(name,group);view.addView(group);if(expanded&&!horizontalHinge()){LinearLayout left=column(),right=column();group.addView(new TwoPane(left,right));columns.put(name,new LinearLayout[]{left,right});}else columns.put(name,new LinearLayout[]{group,group});}
            ArrayList<ScooterCommands.Feature> ordered=new ArrayList<>();String[] priority={"maximum_speed","start_speed","energy_recovery","traction_control","cruise_control"};
            for(String key:priority)ordered.add(ScooterCommands.find(key));for(ScooterCommands.Feature feature:ScooterCommands.catalog())if(!java.util.Arrays.asList(priority).contains(feature.key))ordered.add(feature);
            for(ScooterCommands.Feature feature:ordered){
                if(feature.experimental||feature.key.equals("lock")||feature.key.equals("pin")||feature.key.equals("proximity_key"))continue;
                ControlRow control=new ControlRow(feature);rows.add(control);addCard(controlCategory(feature),control.card);
            }
            LinearLayout clock=design.card();clock.addView(design.text("Scooter clock",16,true));TextView detail=design.caption("Set the charging clock from this phone after changing time zones.");detail.setPadding(0,design.dp(8),0,design.dp(8));clock.addView(detail);clockButton=design.secondaryButton("Set scooter clock from phone",()->{if(scooterService!=null){scooterService.ble.syncClock();redraw();}});clock.addView(clockButton);addCard("Battery",clock);extraCards.put(clock,new String[]{"Battery","scooter clock scheduled charging time timezone"});
            LinearLayout security=design.card();security.addView(design.text("PIN & proximity unlock",18,true));TextView explanation=design.caption("Your handlebar fallback PIN and phone pairing are in More → Security.");explanation.setPadding(0,design.dp(8),0,design.dp(8));security.addView(explanation);security.addView(design.secondaryButton("Open PIN & proximity settings",()->{preferences.edit().putString("more_section","Security").apply();select("more");}));addCard("Security",security);extraCards.put(security,new String[]{"Security","pin password proximity unlock pairing bluetooth bond security"});
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();preferences.edit().putString("controls_query",query).apply();applyFilter();}public void afterTextChanged(Editable text){}});
            applyFilter();
        }
        private void addCard(String category,View card){LinearLayout[] pair=columns.get(category);(pair[0].getChildCount()<=pair[1].getChildCount()?pair[0]:pair[1]).addView(card);}
        private String controlCategory(ScooterCommands.Feature feature){if(feature.key.startsWith("line_"))return "Line lights";if(feature.key.startsWith("star_"))return "Star lights";return feature.group.equals("Ride")?"Ride":feature.group.equals("Battery")?"Battery":feature.group.equals("Preferences")?"Display":feature.group.equals("Security")?"Security":"Lights";}
        private void applyFilter(){
            String needle=query.trim().toLowerCase(Locale.ROOT);String normalized=needle.replaceAll("[\\s_-]+","");boolean searching=!needle.isEmpty();int count=0;
            java.util.HashSet<String> visibleSections=new java.util.HashSet<>();
            for(ControlRow row:rows){String group=controlCategory(row.feature);boolean match=searching?(row.feature.label+" "+row.feature.description+" "+row.feature.group+" "+row.feature.key).toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+","").contains(normalized):group.equals(category);row.card.setVisibility(match?View.VISIBLE:View.GONE);if(match){count++;visibleSections.add(group);}}
            for(Map.Entry<View,String[]> entry:extraCards.entrySet()){String[] metadata=entry.getValue();boolean match=searching?metadata[1].contains(needle):metadata[0].equals(category);entry.getKey().setVisibility(match?View.VISIBLE:View.GONE);if(match){count++;visibleSections.add(metadata[0]);}}
            for(Map.Entry<String,LinearLayout> entry:sections.entrySet())entry.getValue().setVisibility(visibleSections.contains(entry.getKey())?View.VISIBLE:View.GONE);
            results.setText(searching?(count==0?"No matching settings. Try another word.":count+" matching settings across all sections"):category+" · "+count+" settings");
        }
        @Override public View view(){return view;}
        @Override public void close(){for(ControlRow row:rows)row.dragCancelled=true;}
        @Override public void refresh(){for(ControlRow row:rows)row.refresh();if(clockButton!=null)clockButton.setEnabled(scooterService!=null&&scooterService.ble.ready()&&scooterService.ble.pending()==null);}
    }
    private final class ControlRow {
        final ScooterCommands.Feature feature;final LinearLayout card;final TextView value,detail,action,result;final Switch toggle;final SeekBar slider;final TextView sliderValue;boolean dragging,dragCancelled;
        ControlRow(ScooterCommands.Feature feature){
            this.feature=feature;card=design.card();LinearLayout title=row();title.setGravity(Gravity.CENTER_VERTICAL);TextView name=design.text(feature.label,16,true);title.addView(name,new LinearLayout.LayoutParams(0,-2,1));value=design.text("—",15,true);value.setTextColor(design.accent);value.setGravity(Gravity.RIGHT);title.addView(value);card.addView(title);
            detail=design.caption(feature.description);detail.setPadding(0,design.dp(8),0,design.dp(8));card.addView(detail);
            if(feature.type==ScooterCommands.Type.TOGGLE){toggle=new Switch(MainActivity.this);toggle.setShowText(false);toggle.setTextOn("");toggle.setTextOff("");toggle.setText("Enabled");toggle.setTextColor(design.text);toggle.setTextSize(13*design.textScale);toggle.setMinHeight(design.dp(48));toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{design.accent,design.muted}));toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Ui.withAlpha(design.accent,80),design.outline}));toggle.setContentDescription(feature.label);toggle.setOnClickListener(v->{if(scooterService!=null){int target=toggle.isChecked()?1:0;Integer old=scooterService.ble.featureValue(feature.key);toggle.setChecked(old!=null&&old==1);scooterService.ble.set(feature.key,target);redraw();}});card.addView(toggle);action=null;slider=null;sliderValue=null;}
            else if(feature.type==ScooterCommands.Type.SLIDER){toggle=null;action=null;sliderValue=design.text("—",15,true);sliderValue.setTextColor(design.accent);card.addView(sliderValue);slider=new SeekBar(MainActivity.this);slider.setMax((feature.max-feature.min)/feature.step);slider.setProgressTintList(ColorStateList.valueOf(design.accent));slider.setThumbTintList(ColorStateList.valueOf(design.accent));slider.setContentDescription(feature.label);slider.setMinimumHeight(design.dp(48));slider.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)dragCancelled=true;return false;});slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){dragging=true;dragCancelled=false;}public void onProgressChanged(SeekBar s,int progress,boolean user){if(user)sliderValue.setText("Set to "+featureLabel(feature,feature.min+progress*feature.step));}public void onStopTrackingTouch(SeekBar s){dragging=false;int target=feature.min+s.getProgress()*feature.step;target=Math.max(feature.min,Math.min(feature.max,target));if(!dragCancelled&&scooterService!=null)scooterService.ble.set(feature.key,target);redraw();}});card.addView(slider,new LinearLayout.LayoutParams(-1,design.dp(48)));}
            else{toggle=null;slider=null;sliderValue=null;action=design.secondaryButton(feature.type==ScooterCommands.Type.TIME?"Choose start time":"Change",()->{if(feature.type==ScooterCommands.Type.TIME)showChargingTime();else showChoice(feature);});card.addView(action);}
            result=design.caption("");result.setPadding(0,design.dp(7),0,0);result.setVisibility(View.GONE);card.addView(result);
        }
        void refresh(){
            BleController ble=scooterService==null?null:scooterService.ble;Integer actual=ble==null?null:ble.featureValue(feature.key);boolean available=ble!=null&&ble.ready()&&ble.pending()==null&&actual!=null;
            if(feature.type==ScooterCommands.Type.TIME){Double seconds=scooterService==null?null:scooterService.ble.telemetry.number("scheduled_charge_time_raw");available=ble!=null&&ble.ready()&&ble.pending()==null&&seconds!=null;value.setText(seconds==null||seconds<=0?"Not scheduled":new SimpleDateFormat("EEE h:mm a",Locale.US).format(new java.util.Date((long)(seconds*1000)-TimeZone.getDefault().getOffset(System.currentTimeMillis()))));}
            else value.setText(actual==null?"Not reported":featureLabel(feature,actual));
            value.setTextColor(available?design.accent:design.muted);
            if(toggle!=null){toggle.setEnabled(available);toggle.setChecked(actual!=null&&actual==1);if(Build.VERSION.SDK_INT>=30)toggle.setStateDescription(actual==null?"Not reported":actual==1?"On":"Off");}
            if(slider!=null&&!dragging){slider.setEnabled(available);if(actual!=null)slider.setProgress(Math.max(0,Math.min(slider.getMax(),(actual-feature.min)/feature.step)));sliderValue.setText(actual==null?"Waiting for a scooter reading":"Confirmed: "+featureLabel(feature,actual));}
            if(action!=null)action.setEnabled(available&&(feature.type!=ScooterCommands.Type.CHOICE||ble.featureChoices(feature.key).length>0));
            String feedback=ble==null?null:ble.commandResults().get(feature.key);if(ble!=null&&!ble.ready()&&"Confirmed".equals(feedback))feedback="Previous change confirmed";if(ble!=null&&feature.key.equals(ble.pending()))feedback="Waiting for confirmation…";result.setVisibility(feedback==null?View.GONE:View.VISIBLE);result.setText(feedback==null?"":feedback);result.setTextColor(feedback!=null&&feedback.equals("Confirmed")?design.accent:design.muted);
        }
    }
    private void showChoice(ScooterCommands.Feature feature){
        if(scooterService==null||!scooterService.ble.ready()||scooterService.ble.pending()!=null)return;
        int[] choices=scooterService.ble.featureChoices(feature.key);if(choices.length==0){toast("This choice is not available on the current firmware.");return;}String[] labels=new String[choices.length];Integer actual=scooterService.ble.featureValue(feature.key);int current=-1;for(int i=0;i<choices.length;i++){labels[i]=featureLabel(feature,choices[i]);if(actual!=null&&actual==choices[i])current=i;}
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(feature.label).setSingleChoiceItems(labels,current,(d,which)->{if(scooterService.ble.set(feature.key,choices[which]))d.dismiss();else toast(scooterService.ble.message());redraw();}).setNegativeButton("Close",null).create();dialog.show();
    }
    private void showChargingTime(){
        if(scooterService==null||!scooterService.ble.ready())return;Double stored=scooterService.ble.telemetry.number("scheduled_charge_time_raw");Calendar initial=Calendar.getInstance();if(stored!=null&&stored>0)initial.setTimeInMillis((long)(stored*1000)-TimeZone.getDefault().getOffset(System.currentTimeMillis()));
        TimePickerDialog picker=new TimePickerDialog(dialogContext(),(view,hour,minute)->{Calendar chosen=Calendar.getInstance();chosen.set(Calendar.HOUR_OF_DAY,hour);chosen.set(Calendar.MINUTE,minute);chosen.set(Calendar.SECOND,0);chosen.set(Calendar.MILLISECOND,0);if(chosen.getTimeInMillis()<=System.currentTimeMillis())chosen.add(Calendar.DAY_OF_YEAR,1);long timestamp=chosen.getTimeInMillis();scooterService.ble.setChargeStart(timestamp/1000,TimeZone.getDefault().getOffset(System.currentTimeMillis())/1000);redraw();},initial.get(Calendar.HOUR_OF_DAY),initial.get(Calendar.MINUTE),android.text.format.DateFormat.is24HourFormat(this));picker.setTitle("Next charging start");picker.show();
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
