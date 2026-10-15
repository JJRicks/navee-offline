package local.navee.offlinelock.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.bluetooth.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import local.navee.offlinelock.*;

/** Empty-emulator-only visual/data fixture. Never uses owner profiles or connects Bluetooth. */
public final class RidesAnalysisValidation extends Instrumentation {
    MainActivity activity; RidesScreen screen; String id; Bundle args; int checks; final List<String> screenshots = new ArrayList<>();
    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"), "Refusing physical phone");
            Context context = getTargetContext();
            for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents"}) check(context.getSharedPreferences(name, 0).getAll().isEmpty(), "Requires empty preferences");
            check(context.getFilesDir().list().length == 0 && context.databaseList().length == 0, "Requires empty private data");
            BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter(); check(adapter == null || !adapter.isEnabled(), "Bluetooth must be off");
            context.getSharedPreferences("app_settings", 0).edit().putBoolean("auto_connect", false).putBoolean("background_connection", false).putBoolean("map_basemap_enabled", false).putString("theme", args.getString("theme", "dark")).commit();
            main(() -> { ScooterProfiles profiles = new ScooterProfiles(context); profiles.create("Synthetic ride validation", "000000000000", 0); });
            activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long until = SystemClock.uptimeMillis() + 8000; while (activity.service() == null && SystemClock.uptimeMillis() < until) SystemClock.sleep(40);
            check(activity.service() != null, "Service ready"); mapPreferences(); seed();
            main(() -> { invoke(activity, "select", new Class[]{String.class}, "rides"); screen = (RidesScreen) field(activity, "screen"); }); settle();
            verifyRideNaming();
            main(() -> invoke(screen, "phoneOptions", new Class[0])); settle();
            main(() -> { View options = root(); check(find(options, SegmentedSelector.class) != null, "Phone placement uses sliding selector"); text(options, "Mounted").performClick(); check(activity.service().settings.getString("phone_placement", "").equals("mounted"), "Placement saves immediately"); text(options, "Pocket").performClick(); ((AlertDialog)((List<?>)field(screen, "dialogs")).get(0)).dismiss(); });
            if (context.getResources().getConfiguration().screenWidthDp < 650) main(() -> invoke(screen, "showDetails", new Class[]{String.class}, id));
            settle();
            check(text(screen.view(), "Energy direction") == null, "Redundant current-direction selection removed");
            View root = root(); check(find(root, RideChartView.class) != null, "Unified chart exists");
            check(find(root, RouteMapView.class) != null, "Map at top of same overview");
            main(() -> { text(root, "Elev.").performClick(); check("elevation".equals(field(find(root, RouteMapView.class), "metric")), "Elevation overlay selects recorded heights"); text(root, "Grade").performClick(); verifyMapContrast((RouteMapView)find(root,RouteMapView.class)); }); settle(); screenshot("grade-route");
            main(() -> { text(root,"Power").performClick();SegmentedSelector selector=(SegmentedSelector)text(root,"Elev.").getParent().getParent();View track=(View)field(selector,"track");check(track.getWidth()<=selector.getWidth(),"Short route overlay labels fit without hidden choices");verifyZoomButtons(root); }); screenshot("overview");
            verifyExpandedMap(root);
            verifyBasemapControls(root);
            Object panel = ancestor(find(root, RideChartView.class), "ChartPanel");
            Set<?> selected = (Set<?>) field(panel, "chosen"); check(selected.size() == 2 && selected.contains("power_w") && selected.contains("speed_kmh"), "Power and speed selected by default");
            List<?> data = (List<?>)field(panel, "data"); check(data.size() == 8, "Eight graph series");
            main(() -> { View inline = find(root, RideChartView.class); inline.requestRectangleOnScreen(new android.graphics.Rect(0,0,inline.getWidth(),inline.getHeight()+activity.ui().dp(90)),true); }); settle();
            main(() -> verifyInspection((RideChartView)find(root, RideChartView.class))); settle(); screenshot("inline-inspection");
            main(() -> {
                ViewGroup choices = (ViewGroup) field(panel, "choices"); ((CheckBox)choices.getChildAt(2)).performClick(); ((CheckBox)choices.getChildAt(5)).performClick();
                ((CheckBox)choices.getChildAt(7)).performClick(); invoke(panel, "expand", new Class[0]);
            }); settle();
            View full = root(); RideChartView chart = (RideChartView)find(full, RideChartView.class);
            awaitFull(ancestor(chart, "ChartPanel"));
            check(chart != null && chart.getHeight() >= activity.ui().dp(160), "Fullscreen graph has usable height");
            main(() -> { ViewGroup choices = (ViewGroup)field(ancestor(chart,"ChartPanel"),"choices");
                ScrollView overlays = (ScrollView)choices.getParent().getParent(); View last = choices.getChildAt(choices.getChildCount()-1);
                overlays.scrollTo(0, Math.max(0,last.getBottom()-overlays.getHeight()));
                check(last.getGlobalVisibleRect(new android.graphics.Rect()), "Last fullscreen overlay remains reachable in the scrolling controls");
                overlays.scrollTo(0,0);
                check(text(full,"Ride chart controls and limits")==null&&byDescription(full,"Ride chart controls and limits")==null,"Gesture tutorial text is gone; the chart starts with a cursor instead");
            });
            main(() -> verifyZoomButtons(full));
            check(context.getSharedPreferences("app_settings", 0).getStringSet("ride_chart_series", Collections.emptySet()).size() == 5, "Series selection persists immediately");
            main(() -> verifyInspection(chart)); settle();
            check(chart.getHeight() >= activity.ui().dp(160), "Fullscreen graph retains usable height after inspection labels appear: actual="+chart.getHeight()+", measured="+chart.getMeasuredHeight()+", controls="+((ScrollView)field(ancestor(chart,"ChartPanel"),"overlayScroll")).getHeight()+", limit="+field(ancestor(chart,"ChartPanel"),"overlayLimit"));
            screenshot("expanded-inspection");
            double before = (double)field(chart, "to") - (double)field(chart, "from"); main(() -> chart.zoomBy(2)); settle();
            check((double)field(chart, "to") - (double)field(chart, "from") < before, "Fullscreen zoom changes time viewport");
            main(() -> verifyGestures(chart)); settle(); screenshot("expanded");
            main(chart::reset); check(Math.abs((double)field(chart, "to") - (double)field(chart, "duration")) < .01, "Fit time restores all samples");
            main(() -> { Object expanded = ancestor(chart, "ChartPanel"); ViewGroup choices = (ViewGroup)field(expanded, "choices");
                for(int i=0;i<choices.getChildCount();i++){CheckBox box=(CheckBox)choices.getChildAt(i); if(!box.isChecked())box.performClick();}
                check(((List<?>)field(chart,"series")).size()==8,"Every series can be overlaid together");
                ((CheckBox)choices.getChildAt(4)).performClick(); check(((List<?>)field(chart,"series")).size()==7,"Hiding a series removes its Y axis");
            });
            main(() -> ((AlertDialog)((List<?>)field(screen, "dialogs")).get(((List<?>)field(screen, "dialogs")).size() - 1)).dismiss()); settle();
            main(() -> {
                View rename = byDescription(root(), "Rename ride"); check(rename != null, "Rename pencil exposed"); rename.performClick();
            }); settle();
            AlertDialog renameDialog = (AlertDialog)((List<?>)field(screen, "dialogs")).get(((List<?>)field(screen, "dialogs")).size() - 1);
            main(() -> { EditText input = (EditText)find(renameDialog.getWindow().getDecorView(), EditText.class);input.setText(new String(new char[199]).replace('\0','a')+"🛴extra");check(input.length()==199&&!Character.isHighSurrogate(input.getText().charAt(input.length()-1)),"Rename field limits length without splitting emoji"); input.setText("Evening campus loop"); renameDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); }); settle();
            check(activity.service().rides.get(id).label.equals("Evening campus loop"), "Pencil rename saves to journal");
            verifySavingAndEmptyPhone();
            verifyFullDetail();
            check(!activity.service().ble.connected() && !activity.service().isRecording(), "No scooter connection or active recording");
            result.putString("result", "PASS");
        } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("error", error.toString()); try { screenshot("failure"); } catch (Throwable ignored) {} }
        finally {
            result.putInt("checks", checks); result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            if (activity != null) main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }
    void verifyRideNaming() throws Exception {
        // Open the actual naming dialog below the BLE guard; this fixture never starts a ride.
        main(() -> { activity.service().settings.edit().putString("last_ride_label", "Evening campus loop").commit(); invoke(screen, "showRideNameDialog", new Class[0]); }); settle();
        AlertDialog dialog = (AlertDialog)((List<?>)field(screen, "dialogs")).get(0);
        EditText name = (EditText)find(dialog.getWindow().getDecorView(), EditText.class);
        check(name.hasFocus() && name.hasWindowFocus(), "Ride name owns keyboard focus immediately");
        check(name.getSelectionStart() == 0 && name.getSelectionEnd() == name.length(), "Previous ride name is fully selected");
        check((dialog.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
                == WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE, "Naming dialog asks Android to show the keyboard");
        long until = SystemClock.uptimeMillis() + 5000;
        while (Build.VERSION.SDK_INT >= 30 && !name.getRootWindowInsets().isVisible(WindowInsets.Type.ime()) && SystemClock.uptimeMillis() < until) settle();
        check(Build.VERSION.SDK_INT < 30 || name.getRootWindowInsets().isVisible(WindowInsets.Type.ime()), "Naming keyboard is visible without another tap");
        screenshot("ride-name-keyboard");
        main(() -> text(dialog.getWindow().getDecorView(), "Cancel").performClick()); settle();
        check(!dialog.isShowing() && !name.isAttachedToWindow() && !activity.service().isRecording(), "Cancel releases the editor without recording");
        main(() -> { invoke(screen, "showRideNameDialog", new Class[0]); ((AlertDialog)((List<?>)field(screen,"dialogs")).get(0)).dismiss(); }); settle();
        check(((List<?>)field(screen,"dialogs")).isEmpty(), "Immediate dismissal leaves no pending naming dialog");
        main(() -> invoke(screen, "phoneOptions", new Class[0])); settle();
        View next = root();
        check(find(next, EditText.class) == null && (Build.VERSION.SDK_INT < 30 || !next.getRootWindowInsets().isVisible(WindowInsets.Type.ime())), "A dismissed naming request cannot reopen the keyboard over the next dialog");
        main(() -> ((AlertDialog)((List<?>)field(screen,"dialogs")).get(0)).dismiss()); settle();
    }
    void verifyExpandedMap(View sourceRoot) throws Exception {
        RouteMapView inline = (RouteMapView)find(sourceRoot, RouteMapView.class);
        int mapCount = ((List<?>)field(screen,"maps")).size();
        final int[] preselected = {-1};
        main(() -> {
            text(sourceRoot,"Grade").performClick(); inline.zoomBy(1);
            preselected[0] = (int)field(inline,"selected");
            long now = SystemClock.uptimeMillis(); float x = inline.getWidth()/2f, y = inline.getHeight()/2f;
            touch(inline,now,0,MotionEvent.ACTION_DOWN,new float[]{x,y}); touch(inline,now,30,MotionEvent.ACTION_UP,new float[]{x,y});
        }); settle();
        double originalZoom = (double)field(inline,"zoom"), originalCenter = (double)field(inline,"centerX");
        int originalPoint = (int)field(inline,"selected");
        check(originalPoint >= 0 && originalPoint != preselected[0], "Inline map tap selected the tapped point before expanding");
        View expand = byDescription(sourceRoot,"Expand route map");
        check(expand != null && byDescription(sourceRoot,"Fit route") != null, "Expansion and route centering have separate controls");
        main(expand::performClick); settle();
        View full = root(); RouteMapView map = (RouteMapView)find(full,RouteMapView.class);
        check(map != null && map != inline && full != sourceRoot, "Corner expansion opens a separate full-window map");
        check(map.getWidth() >= full.getWidth() - activity.ui().dp(8) && map.getHeight() >= activity.ui().dp(180), "Expanded map fills the dialog width with usable height");
        check(map.getHeight() > ((View)byDescription(full,"Route map readings and colors")).getHeight(), "Map gets more room than its bounded readouts");
        check(Math.abs((double)field(map,"zoom")-originalZoom) < .0001 && Math.abs((double)field(map,"centerX")-originalCenter) < .0000001,
                "Expanding preserves zoom and center instead of fitting the route");
        check("grade".equals(field(map,"metric")) && (int)field(map,"selected") == originalPoint, "Expansion preserves route colors and selected point");
        check(byDescription(full,"Route color: Grade").isSelected() && byDescription(full,"Expand route map") == null, "Expanded overlay selector matches and cannot recursively expand");
        main(() -> {
            float x=map.getWidth()/2f,y=map.getHeight()/2f,span=activity.ui().dp(24); long now=SystemClock.uptimeMillis();
            touch(map,now,0,MotionEvent.ACTION_DOWN,new float[]{x,y});
            touch(map,now,20,MotionEvent.ACTION_MOVE,new float[]{x-activity.ui().dp(40),y}); touch(map,now,50,MotionEvent.ACTION_UP,new float[]{x-activity.ui().dp(40),y});
            check(Math.abs((double)field(map,"centerX")-originalCenter)>.0000001,"Expanded route pans with a real drag");
            double before=(double)field(map,"zoom");
            touch(map,now+100,0,MotionEvent.ACTION_DOWN,new float[]{x-span,y});
            touch(map,now+100,20,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{x-span,y,x+span,y});
            touch(map,now+100,40,MotionEvent.ACTION_MOVE,new float[]{x-span*2,y,x+span*2,y});
            touch(map,now+100,60,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{x-span*2,y,x+span*2,y});
            touch(map,now+100,80,MotionEvent.ACTION_UP,new float[]{x-span*2,y});
            check((double)field(map,"zoom")>before,"Expanded route responds to two-finger zoom");
            text(full,"Accel").performClick();
            check("accel".equals(field(map,"metric")),"Expanded route can change its metric");
            byDescription(full,"Fit route").performClick(); check(!(boolean)field(map,"fitted"),"Dedicated center control requests a route fit");
        }); settle();
        check((boolean)field(map,"fitted"),"Route fit completes after drawing");
        screenshot("expanded-map");
        main(() -> byDescription(full,"Close expanded route map").performClick()); settle();
        check(root()==sourceRoot && inline.isAttachedToWindow() && !(boolean)field(inline,"closed"),"Closing expansion returns to the usable inline map");
        check((boolean)field(map,"closed") && ((List<?>)field(screen,"maps")).size()==mapCount,"Expanded map tile resources are released on close");
        check("accel".equals(field(inline,"metric")) && byDescription(sourceRoot,"Route color: Accel").isSelected(),"Closing keeps the chosen overlay in the inline map");
        check(Math.abs((double)field(inline,"zoom")-(double)field(map,"zoom"))<.0001,"Closing retains the expanded viewport");
        main(expand::performClick); settle();
        RouteMapView second=(RouteMapView)find(root(),RouteMapView.class);
        AlertDialog expanded=(AlertDialog)((List<?>)field(screen,"dialogs")).get(((List<?>)field(screen,"dialogs")).size()-1);
        main(() -> {
            View frame=(View)inline.getParent(); ViewGroup parent=(ViewGroup)frame.getParent(); int index=parent.indexOfChild(frame); ViewGroup.LayoutParams params=frame.getLayoutParams();
            parent.removeView(frame); parent.addView(frame,index,params);
        }); settle();
        check(!expanded.isShowing() && (boolean)field(second,"closed"),"Removing the source route dismisses expansion and closes its tile work");
        main(() -> text(sourceRoot,"Power").performClick()); settle();
    }
    void awaitFull(Object panel) {
        long until = SystemClock.uptimeMillis() + 15000;
        while (!(boolean)field(panel,"fullDataLoaded") && SystemClock.uptimeMillis() < until) settle();
        check((boolean)field(panel,"fullDataLoaded") && "All samples".contentEquals(((TextView)field(panel,"loadStatus")).getText()), "Full source loads asynchronously and is labeled accurately");
    }
    void verifyFullDetail() throws Exception {
        ScooterService service = activity.service(); long wall = System.currentTimeMillis() - 5000000;
        RideStore synthetic = new RideStore(new File(getTargetContext().getFilesDir(),"rides"));
        String longId = synthetic.start("fixture","Long full-detail fixture",wall,1000);
        service.phoneRides.start(longId,wall,1000,"pocket"); StringBuilder phone = new StringBuilder();
        for(int i=0;i<4096;i++) {
            long mono=1000+i*1000L; Map<String,RideStore.Sample> row=new LinkedHashMap<>();
            row.put("voltage_v",new RideStore.Sample(50d,"","V",0x72,mono)); row.put("current_a",new RideStore.Sample((i==1235?3456d:100d)/50,"","A",0x72,mono));
            row.put("speed_kmh",new RideStore.Sample(20d,mono)); row.put("battery_percent",new RideStore.Sample(80d,mono));
            if(i==2048)synthetic.markGap(wall+i*1000L-500,mono-500,"Fixture gap");
            synthetic.sample(wall+i*1000L,mono,row);
            phone.append(PhoneRideStore.row("GPS",wall+i*1000L,mono,43.82+i*.00001,-111.78,2,i==1235?15:5,.1,1500,2));
        }
        synthetic.stop(wall+4096000,4097000);service.phoneRides.appendBatch(longId,phone.toString(),"");service.phoneRides.stop(longId,wall+4096000,4097000);
        main(()->{service.rides=synthetic;service.settings.edit().putStringSet("ride_chart_series",new LinkedHashSet<>(Arrays.asList("power_w","speed_kmh","gps_speed"))).commit();invoke(screen,"showDetails",new Class[]{String.class},longId);});settle();
        Object panel=ancestor(find(root(),RideChartView.class),"ChartPanel");
        List<RideChartData.Series> preview=(List<RideChartData.Series>)field(panel,"data");
        check(preview.get(0).values.size()<=600 && preview.get(0).values.stream().noneMatch(p->p.time==1235000),"Inline long ride stays bounded and omits the deliberately off-stride spike");
        ExecutorService loader=(ExecutorService)field(screen,"chartLoader");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Future<?> blocker=loader.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
        check(entered.await(3,TimeUnit.SECONDS),"Deterministic loading blocker starts off the UI thread");
        Object firstExpanded;
        try {
            main(()->invoke(panel,"expand",new Class[0]));settle();firstExpanded=ancestor(find(root(),RideChartView.class),"ChartPanel");
            Future<?> pending=(Future<?>)field(panel,"fullLoad");
            check("Loading…".contentEquals(((TextView)field(firstExpanded,"loadStatus")).getText())&&!(boolean)field(firstExpanded,"fullDataLoaded"),"Preview remains visible while source loading is pending");
            main(()->{RideChartView chart=(RideChartView)field(firstExpanded,"chart");double span=(double)field(chart,"to")-(double)field(chart,"from");chart.zoomBy(2);check((double)field(chart,"to")-(double)field(chart,"from")<span,"UI remains responsive while the worker is busy");((AlertDialog)field(panel,"expandedDialog")).dismiss();});settle();
            check(pending.isCancelled()&&field(panel,"fullLoad")==null,"Closing fullscreen cancels its pending load");
            AlertDialog detail=(AlertDialog)((List<?>)field(screen,"dialogs")).get(((List<?>)field(screen,"dialogs")).size()-1);
            main(()->invoke(panel,"expand",new Class[0]));settle();Future<?> detached=(Future<?>)field(panel,"fullLoad");
            AlertDialog child=(AlertDialog)field(panel,"expandedDialog");main(detail::dismiss);settle();
            check(detached.isCancelled()&&!child.isShowing(),"Removing the source ride cancels and dismisses its expanded chart");
        } finally { release.countDown();blocker.get(5,TimeUnit.SECONDS); }
        settle();check(!(boolean)field(firstExpanded,"fullDataLoaded"),"A canceled result cannot populate a dismissed chart");
        main(()->{setField(service,"phoneSavingCount",1);invoke(screen,"showDetails",new Class[]{String.class},longId);});settle();
        Object retriedPanel=ancestor(find(root(),RideChartView.class),"ChartPanel");main(()->invoke(retriedPanel,"expand",new Class[0]));settle();
        Object expanded=ancestor(find(root(),RideChartView.class),"ChartPanel");TextView status=(TextView)field(expanded,"loadStatus");
        check("Saving…".contentEquals(status.getText())&&!status.isEnabled(),"Pending sensor save shows a disabled Saving… pill instead of a toast");
        check(((RideChartView)field(expanded,"chart")).getHeight()>=activity.ui().dp(160),"Saving header preserves usable graph height");
        // A null captured store simulates an unexpected worker RuntimeException without
        // exhausting emulator memory or adding a production-only test hook.
        main(()->{setField(service,"phoneSavingCount",0);RideStore saved=service.rides;try{service.rides=null;status.performClick();}finally{service.rides=saved;}});
        long failureDeadline=SystemClock.uptimeMillis()+5000;while(!status.isEnabled()&&SystemClock.uptimeMillis()<failureDeadline)settle();
        check("Preview · Retry".contentEquals(status.getText())&&status.isEnabled()&&!(boolean)field(expanded,"fullDataLoaded"),"Unexpected worker failure recovers to Preview/Retry instead of Loading forever");
        main(()->{setField(service,"phoneSavingCount",0);status.performClick();});awaitFull(expanded);settle();
        check(((RideChartView)field(expanded,"chart")).getHeight()>=activity.ui().dp(160),"All-samples load preserves usable graph height");
        List<RideChartData.Series> full=(List<RideChartData.Series>)field(expanded,"data");
        check(full.get(0).values.size()==4096&&full.get(2).values.size()==4096,"Expanded graph includes all scooter samples and accepted GPS fixes beyond both preview caps");
        check(full.get(0).values.get(2047).segment!=full.get(0).values.get(2048).segment,"Full source retains the journal gap boundary");
        check(Math.abs(full.get(0).sampleAt(1235000).value-3456)<.0001,"Short power spike survives loading");
        check(Math.abs(full.get(2).sampleAt(1235000).value-54/RideStore.KM_PER_MILE)<.0001,"Full GPS peak aligns with scooter time and displayed miles");
        RideChartView chart=(RideChartView)field(expanded,"chart");
        main(()->{setField(chart,"from",1234000d);setField(chart,"to",1236000d);chart.invalidate();});settle();
        main(()->{android.graphics.RectF plot=(android.graphics.RectF)field(chart,"plot");long start=SystemClock.uptimeMillis();touch(chart,start,0,MotionEvent.ACTION_DOWN,new float[]{plot.centerX(),plot.centerY()});touch(chart,start,40,MotionEvent.ACTION_UP,new float[]{plot.centerX(),plot.centerY()});View readout=(View)field(expanded,"inspection");check(text(readout,"3456.00 W")!=null&&text(readout,"At 20:35.000")!=null,"Native tap reaches a full-source spike omitted by inline preview");
            touch(chart,start+600,0,MotionEvent.ACTION_DOWN,new float[]{plot.centerX(),plot.centerY()});touch(chart,start+600,40,MotionEvent.ACTION_MOVE,new float[]{plot.right-1,plot.centerY()});touch(chart,start+600,70,MotionEvent.ACTION_UP,new float[]{plot.right-1,plot.centerY()});check(text(readout,"100.00 W")!=null,"Sliding reads the adjacent original value after the brief spike");});
        settle(); check(chart.getHeight()>=activity.ui().dp(160),"Full-detail scrubbing keeps plot room with large text");
        Set<String> ticks=new HashSet<>();for(int i=0;i<=7;i++)ticks.add(RideChartView.tickTime(1234000+2000d*i/7,2000d/7));
        check(ticks.size()==8&&ticks.contains("20:34.0"),"Subsecond chart ticks use distinct tenths");
        check("1:00.0".equals(RideChartView.tickTime(59999,200)),"Subsecond labels roll cleanly into the next minute");
        check("1:00".equals(RideChartView.tickTime(60000,1000)),"Ordinary tick labels keep whole seconds");
        main(()->{ViewGroup row=(ViewGroup)field(expanded,"inspection");int edge=0;for(int i=0;i<row.getChildCount();i++){ViewGroup cell=(ViewGroup)row.getChildAt(i);check(cell.getLeft()>=edge,"Full-source readout cells do not overlap after scrubbing");edge=cell.getRight();for(int j=0;j<cell.getChildCount();j++){View label=cell.getChildAt(j);check(label.getLeft()>=0&&label.getRight()<=cell.getWidth(),"Full-source readout text remains within its cell");}}});
        // The synthetic events above run back-to-back; allow their final rendered frame
        // to settle before visual evidence.
        SystemClock.sleep(1200);settle();
        screenshot("full-detail-spike");
        main(()->{for(Object dialog:new ArrayList<>((List<?>)field(screen,"dialogs")))((AlertDialog)dialog).dismiss();invoke(screen,"showDetails",new Class[]{String.class},id);});settle();
        Object other=ancestor(find(root(),RideChartView.class),"ChartPanel");main(()->invoke(other,"expand",new Class[0]));settle();Object otherExpanded=ancestor(find(root(),RideChartView.class),"ChartPanel");awaitFull(otherExpanded);
        check(((List<RideChartData.Series>)field(otherExpanded,"data")).get(0).values.size()==240,"Changing rides loads only the selected ride, never a stale full result");
        main(()->{for(Object dialog:new ArrayList<>((List<?>)field(screen,"dialogs")))((AlertDialog)dialog).dismiss();});
    }
    void verifyZoomButtons(View root){for(String label:new String[]{"Zoom out ride chart","Fit the whole ride in the chart","Zoom in ride chart"}){View button=byDescription(root,label);ViewGroup row=(ViewGroup)button.getParent();check(button.getTop()>=row.getPaddingTop()&&button.getBottom()<=row.getHeight()-row.getPaddingBottom(),"Zoom button fits inside row: "+label);}}
    void verifyMapContrast(RouteMapView map){
        int casing=(int)invoke(map,"routeCasingColor",new Class[0]);check(casing==(activity.ui().dark?android.graphics.Color.WHITE:0xff20242c),"Map canvas chooses contrasting route casing");
        setField(map,"streets",true);int tileCasing=(int)invoke(map,"routeCasingColor",new Class[0]);setField(map,"streets",false);
        check(tileCasing==0xff20242c,"Basemap uses dark casing in either theme");
        check((android.graphics.Color.luminance(0xffe2e2e2)+.05)/(android.graphics.Color.luminance(tileCasing)+.05)>=3,"Neutral route has at least 3:1 against casing");
        check((android.graphics.Color.luminance(0xffffd246)+.05)/(android.graphics.Color.luminance(tileCasing)+.05)>=3,"Yellow route endpoint has at least 3:1 against casing");
    }
    void verifySavingAndEmptyPhone() throws Exception{
        main(()->{for(Object dialog:new ArrayList<>((List<?>)field(screen,"dialogs")))((AlertDialog)dialog).dismiss();setField(activity.service(),"phoneSavingCount",1);invoke(screen,"showDetails",new Class[]{String.class},id);});settle();
        View pending=root();check(byDescription(pending,"Finishing the sensor files")!=null&&find(pending,RouteMapView.class)==null,"Open ride shows a map skeleton while the sensor save finishes");
        main(()->{setField(activity.service(),"phoneSavingCount",0);screen.refresh();});
        long until=SystemClock.uptimeMillis()+5000;while(find(pending,RouteMapView.class)==null&&SystemClock.uptimeMillis()<until)settle();
        check(root()==pending&&find(pending,RouteMapView.class)!=null,"Already-open compact dialog gains map after sensor save");
        check(byDescription(pending,"Finishing the sensor files")==null&&byDescription(pending,"Loading route")==null,"Completed load removes placeholder");
        main(()->((AlertDialog)((List<?>)field(screen,"dialogs")).get(0)).dismiss());
        ScooterService service=activity.service();String legacy=service.rides.start("fixture","Scooter only",System.currentTimeMillis(),900000);service.rides.stop(System.currentTimeMillis()+1000,901000);
        for(int attempt=0;attempt<2;attempt++){
            if(attempt==1){service.phoneRides.start(legacy,System.currentTimeMillis(),900000,"pocket");service.phoneRides.stop(legacy,System.currentTimeMillis()+1000,901000);}
            main(()->invoke(screen,"showDetails",new Class[]{String.class},legacy));settle();
            check(text(root(),"Phone & route")==null&&text(root(),"Export phone data")==null,"Empty phone data has no placeholder stats or export block");
            main(()->((AlertDialog)((List<?>)field(screen,"dialogs")).get(0)).dismiss());
        }
    }
    void verifyGestures(RideChartView chart) {
        chart.reset(); android.graphics.RectF plot = (android.graphics.RectF)field(chart, "plot");
        long start = SystemClock.uptimeMillis(); float cx = chart.getWidth() / 2f, cy = plot.centerY(), span = chart.getWidth() * .19f;
        touch(chart, start, 0, MotionEvent.ACTION_DOWN, new float[]{cx-span,cy});
        touch(chart, start, 20, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), new float[]{cx-span,cy,cx+span,cy});
        for(int i=1;i<=5;i++) touch(chart,start,20+i*20,MotionEvent.ACTION_MOVE,new float[]{cx-span*(1+i*.2f),cy,cx+span*(1+i*.2f),cy});
        check((double)field(chart,"to") - (double)field(chart,"from") < (double)field(chart,"duration"), "Actual two-finger gesture zooms timeline; span=" + ((android.view.ScaleGestureDetector)field(chart,"scale")).getCurrentSpan());
        touch(chart,start,140,MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{cx-span*2,cy,cx+span*2,cy});
        touch(chart,start,160,MotionEvent.ACTION_UP,new float[]{cx-span*2,cy});
        double zoomedFrom = (double)field(chart, "from");
        touch(chart,start+600,0,MotionEvent.ACTION_DOWN,new float[]{plot.centerX(),plot.bottom+activity.ui().dp(20)});
        touch(chart,start+600,30,MotionEvent.ACTION_MOVE,new float[]{plot.centerX()-activity.ui().dp(30),plot.bottom+activity.ui().dp(20)});
        touch(chart,start+600,60,MotionEvent.ACTION_UP,new float[]{plot.centerX()-activity.ui().dp(30),plot.bottom+activity.ui().dp(20)});
        check((double)field(chart,"from") > zoomedFrom, "Dragging the time labels still pans the zoomed plot");
        double beforePan = (double)field(chart,"from"); float center = plot.centerX(), small = activity.ui().dp(20), shift = activity.ui().dp(8);
        touch(chart,start+1000,0,MotionEvent.ACTION_DOWN,new float[]{center-small,cy});
        touch(chart,start+1000,20,MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{center-small,cy,center+small,cy});
        touch(chart,start+1000,40,MotionEvent.ACTION_MOVE,new float[]{center-small-shift,cy,center+small-shift,cy});
        check((double)field(chart,"from") > beforePan, "Two-finger translation also pans the plot");
        touch(chart,start+1000,60,MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{center-small-shift,cy,center+small-shift,cy});
        touch(chart,start+1000,80,MotionEvent.ACTION_UP,new float[]{center-small-shift,cy});
        double oldScroll = ((Number)field(chart,"axisScroll")).doubleValue();
        touch(chart,start+1600,0,MotionEvent.ACTION_DOWN,new float[]{plot.left-activity.ui().dp(12),cy});
        touch(chart,start+1600,20,MotionEvent.ACTION_MOVE,new float[]{activity.ui().dp(8),cy});
        touch(chart,start+1600,40,MotionEvent.ACTION_UP,new float[]{activity.ui().dp(8),cy});
        check(((Number)field(chart,"axisScroll")).doubleValue() > oldScroll, "Color scale rail scrolls independently");
        check(plot.width() > chart.getWidth() * .55, "Plot retains most of the screen with five axes selected");
    }
    void verifyInspection(RideChartView chart) {
        chart.reset(); android.graphics.RectF plot = (android.graphics.RectF)field(chart, "plot");
        long start = SystemClock.uptimeMillis(); float x = plot.left + plot.width() * .31f, y = plot.centerY();
        double originalFrom = (double)field(chart,"from"), originalTo = (double)field(chart,"to");
        double preselected = (double)field(chart,"inspectedTime");
        touch(chart,start,0,MotionEvent.ACTION_DOWN,new float[]{x,y}); touch(chart,start,40,MotionEvent.ACTION_UP,new float[]{x,y});
        double first = (double)field(chart,"inspectedTime"); check(Double.isFinite(first) && first != preselected, "Tap selects the tapped raw reading timestamp");
        Object panel = ancestor(chart,"ChartPanel"); ViewGroup readout = (ViewGroup)field(panel,"inspection");
        List<RideChartData.Series> selected = (List<RideChartData.Series>)field(chart,"series");
        check(readout.getChildCount() == selected.size(), "Every selected series gets an inspection value");
        for (RideChartData.Series series : selected) {
            RideChartData.Value sample = series.sampleAt(first);
            check(sample != null && sample.value != null, "Fixture cursor has an actual sample for " + series.label);
            check(text(readout, RideChartView.sampleValue(series,sample)) != null, "Readout shows original value with correct unit for " + series.label);
            check(text(readout,"At " + RideChartView.sampleTime(sample.time)) != null, "Readout includes actual sample time for " + series.label);
        }
        touch(chart,start+600,0,MotionEvent.ACTION_DOWN,new float[]{x,y});
        touch(chart,start+600,40,MotionEvent.ACTION_MOVE,new float[]{plot.left+plot.width()*.68f,y});
        touch(chart,start+600,70,MotionEvent.ACTION_UP,new float[]{plot.left+plot.width()*.68f,y});
        check((double)field(chart,"inspectedTime") > first, "Sliding moves raw-data cursor");
        check((double)field(chart,"from") == originalFrom && (double)field(chart,"to") == originalTo, "Scrubbing does not pan or zoom the graph");
        touch(chart,start+1200,0,MotionEvent.ACTION_DOWN,new float[]{x,y});
        touch(chart,start+1200,40,MotionEvent.ACTION_MOVE,new float[]{x+2,y+activity.ui().dp(45)});
        check((boolean)field(chart,"scrollingPage"), "Vertical gesture yields to page scrolling");
        touch(chart,start+1200,70,MotionEvent.ACTION_CANCEL,new float[]{x+2,y+activity.ui().dp(45)});
    }
    void touch(View view,long start,long offset,int action,float[] xy) {
        int count=xy.length/2; MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[count]; MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[count];
        for(int i=0;i<count;i++){props[i]=new MotionEvent.PointerProperties();props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].x=xy[i*2];coords[i].y=xy[i*2+1];coords[i].pressure=1;coords[i].size=1;}
        MotionEvent event=MotionEvent.obtain(start,start+offset,action,count,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);try{view.dispatchTouchEvent(event);}finally{event.recycle();}
    }
    void mapPreferences() throws Exception {
        SharedPreferences settings = activity.service().settings;
        AtomicReference<AlertDialog> dialog = new AtomicReference<>(), prompt = new AtomicReference<>();
        AtomicReference<View> content = new AtomicReference<>();
        main(() -> { settings.edit().remove("map_basemap_enabled").remove("map_basemap_explained").remove("map_tile_url").commit(); dialog.set(MapPreferences.show(activity)); }); settle();
        main(() -> {
            content.set(dialog.get().getWindow().getDecorView());
            check(text(content.get(), "Off").isSelected(), "Fresh basemaps default off without changing an existing preference");
            check(!settings.contains("map_tile_url"), "Fresh provider uses USGS default");
            check(text(content.get(), "Edit custom provider").getVisibility() != View.VISIBLE, "Custom provider editor hidden unless Custom is picked");
            int offTop = text(content.get(), "Off").getTop(), offHeight = text(content.get(), "Off").getHeight();
            for (String label : new String[]{"USGS (US)", "OSM", "Custom"}) check(text(content.get(), label).getTop() == offTop && text(content.get(), label).getHeight() == offHeight, "Map provider choices share one centred row height: " + label);
            text(content.get(), "OSM").performClick(); prompt.set(providerPrompt(content.get()));
            check(settings.getString("map_tile_url", "").contains("openstreetmap.org"), "Provider selection saved");
            check(prompt.get() != null && prompt.get().isShowing() && text(prompt.get().getWindow().getDecorView(), "Show online map?") != null, "First provider pick opens the privacy explanation");
            check(!settings.getBoolean("map_basemap_enabled", false), "Consent prompt does not enable tiles before approval");
        }); settle(); screenshot("basemap-first-enable-consent");
        main(() -> prompt.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()); settle();
        main(() -> {
            check(!settings.getBoolean("map_basemap_explained", false) && text(content.get(), "Off").isSelected(), "Cancel neither acknowledges nor enables basemaps");
            text(content.get(), "USGS (US)").performClick(); check(settings.getString("map_tile_url", "").contains("nationalmap.gov"), "USGS restored");
            providerPrompt(content.get()).onBackPressed();
        }); settle();
        main(() -> {
            check(!settings.getBoolean("map_basemap_explained", false) && text(content.get(), "Off").isSelected(), "Back neither acknowledges nor enables basemaps");
            text(content.get(), "USGS (US)").performClick(); prompt.set(providerPrompt(content.get())); dialog.get().dismiss();
        }); settle();
        main(() -> {
            check(!prompt.get().isShowing() && !settings.getBoolean("map_basemap_explained", false), "Closing settings dismisses its unapproved explanation without acknowledgement");
            dialog.set(MapPreferences.show(activity));
        }); settle();
        main(() -> {
            content.set(dialog.get().getWindow().getDecorView());
            check(text(content.get(), "USGS (US)").isAttachedToWindow(), "Reopened settings control attaches before consent input");
            text(content.get(), "USGS (US)").performClick(); providerPrompt(content.get()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        }); settle();
        main(() -> {
            check(text(content.get(), "USGS (US)").isSelected() && settings.getBoolean("map_basemap_enabled", false) && settings.getBoolean("map_basemap_explained", false), "Approval saves both the enabled state and one-time explanation acknowledgement");
            dialog.get().dismiss(); dialog.set(MapPreferences.show(activity));
        }); settle();
        main(() -> {
            content.set(dialog.get().getWindow().getDecorView());
            check(text(content.get(), "USGS (US)").isSelected() && providerPrompt(content.get()) == null, "Enabled state survives reopening preferences without another prompt");
            text(content.get(), "Off").performClick(); check(!settings.getBoolean("map_basemap_enabled", true), "An acknowledged basemap can be turned off directly");
            text(content.get(), "OSM").performClick(); check(text(content.get(), "OSM").isSelected() && settings.getBoolean("map_basemap_enabled", false) && providerPrompt(content.get()) == null, "Later enables use the remembered acknowledgement");
            dialog.get().dismiss();
            settings.edit().putBoolean("map_basemap_enabled", true).remove("map_basemap_explained").commit();
            dialog.set(MapPreferences.show(activity));
        }); settle();
        main(() -> {
            content.set(dialog.get().getWindow().getDecorView());
            check(!text(content.get(), "Off").isSelected() && providerPrompt(content.get()) == null, "Previously enabled installations remain enabled without a surprise prompt");
            dialog.get().dismiss();
            // Independent route fixture below starts with unapproved, disabled basemaps.
            settings.edit().putBoolean("map_basemap_enabled", false).remove("map_basemap_explained").commit();
        }); settle();
    }
    AlertDialog providerPrompt(View content) { return (AlertDialog)field(ancestor(text(content, "Off"), "ProviderControl"), "prompt"); }
    void verifyBasemapControls(View sourceRoot) throws Exception {
        SharedPreferences settings = activity.service().settings;
        RouteMapView inline = (RouteMapView)find(sourceRoot, RouteMapView.class);
        Switch inlineToggle = basemapSwitch(sourceRoot); AtomicInteger tileCalls = new AtomicInteger();
        main(() -> {
            for (Object map : (List<?>)field(screen, "maps")) fakeTiles((RouteMapView)map, tileCalls);
            inline.requestRectangleOnScreen(new android.graphics.Rect(0, 0, inline.getWidth(), inline.getHeight()), true);
            check(!inlineToggle.isChecked() && !(boolean)field(inline, "streets"), "Inline Basemap switch starts off with the offline route still visible");
            inlineToggle.requestRectangleOnScreen(new android.graphics.Rect(0, 0, inlineToggle.getWidth(), inlineToggle.getHeight()), true);
        }); settle(); screenshot("basemap-inline-off");
        main(() -> {
            inlineToggle.performClick(); AlertDialog prompt = basemapPrompt(inlineToggle);
            check(prompt != null && prompt.isShowing() && !inlineToggle.isChecked() && !(boolean)field(inline, "streets"), "Inline enable waits for privacy approval");
            check(text(prompt.getWindow().getDecorView(), "Provider sees your IP and map area. Rides stay on phone.") != null,
                    "Explanation names the provider's IP and viewed-area exposure without claiming routes upload");
            View control = (View)inlineToggle.getParent(); ViewGroup parent = (ViewGroup)control.getParent();
            int index = parent.indexOfChild(control); ViewGroup.LayoutParams params = control.getLayoutParams(); parent.removeView(control);
            check(!prompt.isShowing() && !settings.getBoolean("map_basemap_explained", false), "Removing the inline control cancels its pending approval");
            parent.addView(control, index, params);
        }); settle();
        check(tileCalls.get() == 0, "Default-off and canceled consent cause no tile requests");
        main(() -> { inlineToggle.performClick(); basemapPrompt(inlineToggle).getButton(AlertDialog.BUTTON_POSITIVE).performClick(); }); settle();
        check(inlineToggle.isChecked() && (boolean)field(inline, "streets"), "Approving the inline switch enables the current map");
        check(tileCalls.get() > 0, "Approved visible map uses the synthetic tile connection only");
        screenshot("basemap-inline-on");
        main(() -> {
            byDescription(sourceRoot, "Expand route map").performClick();
            // Install the fake before this new window has a chance to draw its enabled map.
            fakeTiles((RouteMapView)find(root(), RouteMapView.class), tileCalls);
        }); settle();
        View full = root(); RouteMapView expanded = (RouteMapView)find(full, RouteMapView.class); Switch expandedToggle = basemapSwitch(full);
        check(expandedToggle.isChecked() && (boolean)field(expanded, "streets"), "Expanded map inherits the remembered enabled preference");
        screenshot("basemap-expanded-on");
        main(() -> expandedToggle.performClick()); settle();
        check(!expandedToggle.isChecked() && !inlineToggle.isChecked() && !(boolean)field(inline, "streets") && !(boolean)field(expanded, "streets"), "Fullscreen disable immediately synchronizes both switches and maps");
        screenshot("basemap-expanded-off");
        int stopped = tileCalls.get(); main(() -> { inline.zoomBy(1); expanded.zoomBy(1); }); settle();
        check(tileCalls.get() == stopped, "Disabled maps do not request new tiles when the viewport changes");
        AtomicReference<AlertDialog> preferences = new AtomicReference<>();
        main(() -> preferences.set(MapPreferences.show(activity))); settle();
        main(() -> {
            View settingsContent = preferences.get().getWindow().getDecorView();
            check(text(settingsContent, "Off").isSelected(), "App preferences reflects the map's disabled state"); text(settingsContent, "USGS (US)").performClick();
            check(providerPrompt(settingsContent) == null, "Shared approval is reused from app preferences");
        }); settle();
        check(inlineToggle.isChecked() && expandedToggle.isChecked() && (boolean)field(inline, "streets") && (boolean)field(expanded, "streets"), "App preferences enable synchronizes inline and expanded maps without recreation");
        main(() -> { text(preferences.get().getWindow().getDecorView(), "Off").performClick(); preferences.get().dismiss(); byDescription(full, "Close expanded route map").performClick(); }); settle();
        check(!settings.getBoolean("map_basemap_enabled", true) && !inlineToggle.isChecked() && !(boolean)field(inline, "streets"), "Closing fullscreen preserves the chosen disabled state");
        check((boolean)field(expanded, "closed"), "Expanded map resources still close after preference changes");
    }
    Switch basemapSwitch(View root) {
        View found = byDescription(root, "Basemap"); check(found instanceof Switch, "Basemap switch has an accessible label"); return (Switch)found;
    }
    AlertDialog basemapPrompt(Switch toggle) { return (AlertDialog)field(toggle.getParent(), "prompt"); }
    void fakeTiles(RouteMapView map, AtomicInteger calls) {
        try {
            Class<?> provider = Class.forName("local.navee.offlinelock.MapTiles");
            Class<?> factory = Class.forName("local.navee.offlinelock.MapTiles$ConnectionFactory");
            Object connection = Proxy.newProxyInstance(factory.getClassLoader(), new Class[]{factory}, (proxy, method, arguments) -> {
                if ("open".equals(method.getName())) { calls.incrementAndGet(); throw new IOException("Synthetic offline tile provider"); }
                if ("toString".equals(method.getName())) return "Synthetic tile connection";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                return arguments != null && arguments.length == 1 && proxy == arguments[0];
            });
            Constructor<?> constructor = provider.getDeclaredConstructor(Context.class, String.class, factory, java.util.function.LongSupplier.class); constructor.setAccessible(true);
            Object instance = constructor.newInstance(activity, "https://synthetic.invalid/consent/{z}/{x}/{y}.png", connection, (java.util.function.LongSupplier)System::currentTimeMillis);
            setField(map, "provider", instance);
        } catch (Exception failure) { throw new IllegalStateException("Synthetic map provider", failure); }
    }
    TextView text(View view, String label) { if(view instanceof TextView && label.contentEquals(((TextView)view).getText()))return (TextView)view; if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView item=text(((ViewGroup)view).getChildAt(i),label);if(item!=null)return item;}return null; }
    void seed() throws Exception {
        ScooterService service = activity.service(); long wall = System.currentTimeMillis() - 240000;
        // Seed through a separate store, then publish only the finished journal. Otherwise
        // a service tick can add real-time disconnect heartbeats to the synthetic timeline.
        RideStore synthetic = new RideStore(new File(getTargetContext().getFilesDir(), "rides"));
        id = synthetic.start("fixture", "Campus hill · synthetic", wall, 1000); synthetic.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
        service.phoneRides.start(id, wall, 1000, "pocket"); StringBuilder phone = new StringBuilder();
        for (int i = 0; i < 240; i++) {
            long mono = 1000 + i * 1000; double speed = 20 + 4 * Math.sin(i / 15.0), watts = 600 + 500 * Math.sin(i / 20.0);
            Map<String,RideStore.Sample> row = new LinkedHashMap<>(); row.put("voltage_v", new RideStore.Sample(50d, "", "V", 0x72, mono)); row.put("current_a", new RideStore.Sample(watts / 50, "", "A", 0x72, mono));
            row.put("speed_kmh", new RideStore.Sample(speed, mono)); row.put("odometer_km", new RideStore.Sample(100 + i * .005, mono)); row.put("battery_percent", new RideStore.Sample(90 - i / 40.0, mono)); synthetic.sample(wall + i * 1000, mono, row);
            phone.append(PhoneRideStore.row("GPS", wall + i * 1000, mono, 43.82 + i * .00004, -111.78 + Math.sin(i / 40.0) * .0005, 3, speed / 3.6, .2, 1500 + Math.sin(i / 50.0) * 10, 4));
            phone.append(PhoneRideStore.row("TELEMETRY", wall + i * 1000, mono, speed, watts, 90 - i / 40.0, mono, mono, mono));
        }
        synthetic.stop(wall + 240000, 241000); service.phoneRides.appendBatch(id, phone.toString(), ""); service.phoneRides.stop(id, wall + 240000, 241000);
        main(() -> service.rides = synthetic);
    }
    View root() { List<?> dialogs = (List<?>)field(screen, "dialogs"); return dialogs.isEmpty() ? screen.view() : ((AlertDialog)dialogs.get(dialogs.size()-1)).getWindow().getDecorView(); }
    Object ancestor(View view, String suffix) { View current = view; while (current != null) { if (current.getClass().getSimpleName().equals(suffix)) return current; current = current.getParent() instanceof View ? (View)current.getParent() : null; } throw new AssertionError("Missing " + suffix); }
    View find(View view, Class<?> cls) { if (cls.isInstance(view)) return view; if (view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) { View found=find(((ViewGroup)view).getChildAt(i),cls); if(found!=null)return found; } return null; }
    View byDescription(View view, String text) { if (text.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view; if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View f=byDescription(((ViewGroup)view).getChildAt(i),text);if(f!=null)return f;}return null; }
    void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    Object field(Object target,String name){try{Class<?> c=target.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new NoSuchFieldException(name);}catch(Exception e){throw new IllegalStateException(name,e);}}
    void setField(Object target,String name,Object value){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new IllegalStateException(name,e);}}
    Object invoke(Object target,String name,Class<?>[] types,Object... args){try{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}catch(Exception e){throw new IllegalStateException(name,e);}}
    void main(Runnable action){AtomicReference<Throwable> failure = new AtomicReference<>(); runOnMainSync(() -> { try { action.run(); } catch(Throwable e) { failure.set(e); } }); if(failure.get()!=null)throw new IllegalStateException(failure.get());} void settle(){waitForIdleSync();SystemClock.sleep(350);waitForIdleSync();}
    void screenshot(String label)throws Exception{File dir=new File(getTargetContext().getExternalFilesDir(null),"rides-validation");dir.mkdirs();File file=new File(dir,args.getString("variant","rides")+"-"+label+".png");android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();screenshots.add(file.getAbsolutePath());}
}
