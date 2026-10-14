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
            main(() -> invoke(screen, "phoneOptions", new Class[0])); settle();
            main(() -> { View options = root(); check(find(options, SegmentedSelector.class) != null, "Phone placement uses sliding selector"); text(options, "Mounted").performClick(); check(activity.service().settings.getString("phone_placement", "").equals("mounted"), "Placement saves immediately"); text(options, "Pocket").performClick(); ((AlertDialog)((List<?>)field(screen, "dialogs")).get(0)).dismiss(); });
            if (context.getResources().getConfiguration().screenWidthDp < 650) main(() -> invoke(screen, "showDetails", new Class[]{String.class}, id));
            settle();
            check(text(screen.view(), "Energy direction") == null, "Redundant current-direction selection removed");
            View root = root(); check(find(root, RideChartView.class) != null, "Unified chart exists");
            check(find(root, RouteMapView.class) != null, "Map at top of same overview");
            main(() -> { text(root, "Elev.").performClick(); check("elevation".equals(field(find(root, RouteMapView.class), "metric")), "Elevation overlay selects recorded heights"); text(root, "Grade").performClick(); verifyMapContrast((RouteMapView)find(root,RouteMapView.class)); }); settle(); screenshot("grade-route");
            main(() -> { text(root,"Power").performClick();SegmentedSelector selector=(SegmentedSelector)text(root,"Elev.").getParent().getParent();View track=(View)field(selector,"track");check(track.getWidth()<=selector.getWidth(),"Short route overlay labels fit without hidden choices");verifyZoomButtons(root); }); screenshot("overview");
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
            check(chart != null && chart.getHeight() >= activity.ui().dp(160), "Fullscreen graph has usable height");
            main(() -> { ViewGroup choices = (ViewGroup)field(ancestor(chart,"ChartPanel"),"choices");
                ScrollView overlays = (ScrollView)choices.getParent(); overlays.scrollTo(0, choices.getHeight());
                check(choices.getChildAt(choices.getChildCount()-1).getGlobalVisibleRect(new android.graphics.Rect()), "Last fullscreen overlay remains reachable in the scrolling controls"); overlays.scrollTo(0,0);
            });
            main(() -> verifyZoomButtons(full));
            check(context.getSharedPreferences("app_settings", 0).getStringSet("ride_chart_series", Collections.emptySet()).size() == 5, "Series selection persists immediately");
            main(() -> verifyInspection(chart)); settle(); screenshot("expanded-inspection");
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
            check(!activity.service().ble.connected() && !activity.service().isRecording(), "No scooter connection or active recording");
            result.putString("result", "PASS");
        } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("error", error.toString()); try { screenshot("failure"); } catch (Throwable ignored) {} }
        finally {
            result.putInt("checks", checks); result.putStringArrayList("screenshots", new ArrayList<>(screenshots));
            if (activity != null) main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
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
        View pending=root();check(text(pending,"Finishing the sensor files…")!=null&&find(pending,RouteMapView.class)==null,"Open ride explains sensor save and reserves map space");
        main(()->{setField(activity.service(),"phoneSavingCount",0);screen.refresh();});
        long until=SystemClock.uptimeMillis()+5000;while(find(pending,RouteMapView.class)==null&&SystemClock.uptimeMillis()<until)settle();
        check(root()==pending&&find(pending,RouteMapView.class)!=null,"Already-open compact dialog gains map after sensor save");
        check(text(pending,"Finishing the sensor files…")==null&&text(pending,"Loading route…")==null,"Completed load removes placeholder");
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
        touch(chart,start,0,MotionEvent.ACTION_DOWN,new float[]{x,y}); touch(chart,start,40,MotionEvent.ACTION_UP,new float[]{x,y});
        double first = (double)field(chart,"inspectedTime"); check(Double.isFinite(first), "Tap selects a raw reading timestamp");
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
    void mapPreferences() {
        AtomicReference<AlertDialog> dialog = new AtomicReference<>();
        main(() -> { activity.service().settings.edit().remove("map_basemap_enabled").commit(); dialog.set(MapPreferences.show(activity)); }); settle();
        main(() -> {
            View content = dialog.get().getWindow().getDecorView(); Switch toggle = (Switch)find(content, Switch.class);
            check(toggle != null && toggle.isChecked(), "Basemap enabled by default");
            check(!activity.service().settings.contains("map_tile_url"), "Fresh provider uses USGS default");
            text(content, "OpenStreetMap").performClick(); check(activity.service().settings.getString("map_tile_url", "").contains("openstreetmap.org"), "Provider selection saved");
            text(content, "USGS").performClick(); check(activity.service().settings.getString("map_tile_url", "").contains("nationalmap.gov"), "USGS restored");
            toggle.performClick(); check(!activity.service().settings.getBoolean("map_basemap_enabled", true), "Basemap can be disabled in preferences"); dialog.get().dismiss();
        });
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
