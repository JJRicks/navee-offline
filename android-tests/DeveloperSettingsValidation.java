package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.Instrumentation;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.BleController;
import local.navee.offlinelock.MainActivity;
import local.navee.offlinelock.MoreScreen;
import local.navee.offlinelock.ScooterProfiles;
import local.navee.offlinelock.ScooterService;
import local.navee.offlinelock.ScooterCommands;
import local.navee.offlinelock.SegmentedSelector;
import local.navee.offlinelock.Telemetry;
import local.navee.offlinelock.TelemetryHistory;
import local.navee.offlinelock.Ui;

/** Fresh disposable emulator only. Sample controls run with Bluetooth disabled and no real identity. */
public final class DeveloperSettingsValidation extends Instrumentation {
    private Bundle arguments;
    private MainActivity activity;
    private boolean admitted;
    private String profileId;
    private int assertions;
    private final ArrayList<String> checks = new ArrayList<>(), screenshots = new ArrayList<>();

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit(); admitted = true;
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", "dark").putString("units", "mi").putString("more_section", "App")
                    .putFloat("text_scale", Float.parseFloat(arguments.getString("text_scale", "1"))).commit(), "Fixture settings failed");
            seed(); launch();
            main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "more")); settle();
            verifySelectors(); checks.add("Rounded preference tracks retain readable labels, 48dp targets, radio semantics, selected indication and existing preference keys");
            verifyDeveloperGate(); checks.add("Developer options stay hidden and reject direct access until seven version taps");
            if (!"preferences".equals(arguments.getString("mode", "all"))) {
                verifySimulation();
                checks.add("Simulation supplies every telemetry field and ephemeral charts; local controls never create GATT/scans/command queues, recording or persisted sample data");
                checks.add("Simulation pauses immediately in background, restores genuine stale cache on exit, and is off after service recreation");
            }
            assertTransportIdle();
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
            if (activity != null) try { screenshot("failure"); } catch (Throwable ignored) { }
        } finally {
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks);
            result.putStringArrayList("screenshots", screenshots); result.putBoolean("physical_device_tested", false);
            if (admitted && activity != null) try { main(() -> { activity.finish(); ScooterService.setUiVisible(false); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); }); } catch (Throwable ignored) { }
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }
    private void admit() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing a physical phone");
        for (String name : new String[]{"app_settings", "profiles", "scooter", "pending_documents", "control_evidence"})
            require(prefs(name).getAll().isEmpty(), "Refusing existing private preferences");
        File[] files = getTargetContext().getFilesDir().listFiles();
        require(files != null && files.length == 0 && getTargetContext().databaseList().length == 0, "Refusing existing private data");
        for (String permission : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission on the emulator first");
        BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        require(adapter == null || !adapter.isEnabled(), "Bluetooth must be disabled before validation");
    }
    private void seed() {
        AtomicReference<TelemetryHistory> history = new AtomicReference<>(); AtomicReference<TelemetryHistory.Snapshot> saved = new AtomicReference<>();
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile profile = profiles.create("Synthetic developer fixture", "000000000000", 0);
            profile.serial = "X2538-SYNTHETIC"; profile.lastConnected = System.currentTimeMillis() - 60000; profiles.update(profile); profileId = profile.id;
            require(prefs("control_evidence").edit().putString(profileId, "{\"lock\":\"Preserve fixture evidence\"}").commit(), "Could not seed evidence fixture");
            Map<String, TelemetryHistory.Reading> values = new LinkedHashMap<>(); long wall = System.currentTimeMillis() - 60000;
            values.put("battery_percent", new TelemetryHistory.Reading("battery_percent", "Battery", "Battery", "37 %", "%", 37d, wall, true));
            values.put("odometer_km", new TelemetryHistory.Reading("odometer_km", "Odometer", "Ride", "123.45 km", "km", 123.45, wall, true));
            TelemetryHistory store = new TelemetryHistory(getTargetContext()); history.set(store);
            store.record(profileId, new TelemetryHistory.Snapshot(values, Collections.singletonMap("lock", 1), wall), 1, true);
            store.load(profileId, saved::set);
        }); await(() -> saved.get() != null, "Synthetic history did not persist"); main(() -> history.get().close());
    }
    private void launch() {
        activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> activity.service() != null && root().isAttachedToWindow() && root().getWidth() > 0, "App did not launch");
        await(() -> activity.service().displayReadings().get("battery_percent") != null
                && activity.service().displayReadings().get("battery_percent").number != null, "Genuine cache did not load"); settle();
    }
    private void verifySelectors() throws Exception {
        main(() -> invoke(more(), "selectSection", new Class<?>[]{String.class}, "App")); settle();
        String[] keys = {"theme", "units", "time_format"};
        String[][] values = {{"dark", "system", "light"}, {"mi", "km", "dual"}, {"system", "12h", "24h"}};
        for (int group = 0; group < keys.length; group++) {
            final String key = keys[group];
            main(() -> assertSelector(key));
            for (int choice : new int[]{2, 0}) {
                final int selected = choice; final String expected = values[group][choice];
                main(() -> { View previousSelector = selector(key); choices(key).get(selected).performClick();
                    if (!Ui.motionEnabled()) require(field(field(previousSelector, "track"), "animation") == null,
                            "Preference selection animated with system animations disabled"); }); settle();
                main(() -> { require(settings().getString(key, "").equals(expected), "Selector changed the wrong preference key"); assertSelector(key); });
            }
        }
        screenshot("preferences");
    }
    @SuppressWarnings("unchecked") private List<TextView> choices(String key) { return (List<TextView>)field(field(selector(key), "track"), "choices"); }
    private View selector(String key) { View found = root().findViewWithTag("preference-selector-" + key); require(found != null, "Missing selector " + key); return found; }
    private void assertSelector(String key) {
        View selector = selector(key); Object track = field(selector, "track");
        require(selector instanceof HorizontalScrollView && selector.getWidth() > 0, "Selector lost its bounded readable track");
        int selected = 0; List<Rect> targets = new ArrayList<>();
        for (TextView choice : choices(key)) {
            require(choice.getWidth() >= activity.ui().dp(48) && choice.getHeight() >= activity.ui().dp(48), "Preference target became too small");
            require(choice.isClickable() && choice.isFocusable(), "Preference is not accessible by touch/focus");
            require(choice.getLayout() != null && choice.getLayout().getLineCount() == 1 && choice.getLayout().getEllipsisCount(0) == 0
                    && choice.getLayout().getLineEnd(0) == choice.length(), "Preference label is clipped or ellipsized");
            require(choice.getPaint().measureText(choice.getText().toString()) <= choice.getWidth() - choice.getCompoundPaddingLeft() - choice.getCompoundPaddingRight() + 1,
                    "Preference label does not fit its target");
            Rect bounds = new Rect(choice.getLeft(), choice.getTop(), choice.getRight(), choice.getBottom());
            for (Rect other : targets) require(!Rect.intersects(bounds, other), "Preference targets overlap"); targets.add(bounds);
            AccessibilityNodeInfo node = choice.createAccessibilityNodeInfo();
            require(node.isCheckable() && node.isChecked() == choice.isSelected(), "Preference radio accessibility state is incorrect"); node.recycle();
            if (choice.isSelected()) {
                selected++; RectF indicator = (RectF)field(track, "indicator");
                require(Math.abs(indicator.left - choice.getLeft()) <= 1 && Math.abs(indicator.right - choice.getRight()) <= 1,
                        "Preference highlight did not settle over selected choice");
                int left = choice.getLeft() - selector.getScrollX(), right = choice.getRight() - selector.getScrollX();
                require(left >= -1 && right <= selector.getWidth() + 1, "Selected preference is hidden outside its viewport");
                if (Build.VERSION.SDK_INT >= 30) require("Selected".contentEquals(choice.getStateDescription()), "Selected preference lost its state description");
            }
        }
        require(selected == 1 && field(track, "animation") == null, "Preference selection is ambiguous or animation did not settle");
    }
    private void verifyDeveloperGate() {
        main(() -> {
            require(!settings().getBoolean("developer_options", false), "Developer options start unlocked");
            require(root().findViewWithTag("developer-options-menu") == null, "Hidden menu is visible before unlock");
            require(!activity.service().setSimulation(true) && !activity.service().simulating(), "Locked API enabled simulation");
            invoke(more(), "selectSection", new Class<?>[]{String.class}, "Developer");
            require(!"Developer".equals(field(more(), "selectedSection")), "Direct section access bypassed developer gate");
            settings().edit().putString("more_section", "Developer").commit(); activity.redraw();
        }); settle();
        main(() -> {
            require(!"Developer".equals(field(more(), "selectedSection")), "Persisted section bypassed developer gate");
            invoke(more(), "selectSection", new Class<?>[]{String.class}, "About");
        }); settle();
        main(() -> {
            View version = root().findViewWithTag("about-version"); require(version != null, "Version unlock target missing");
            for (int n = 0; n < 6; n++) version.performClick();
            require(!settings().getBoolean("developer_options", false), "Developer options unlocked before seven taps");
            version.performClick();
            require(settings().getBoolean("developer_options", false), "Seven taps did not unlock developer options");
        }); settle();
        main(() -> {
            invoke(more(), "selectSection", new Class<?>[]{String.class}, "");
            require(root().findViewWithTag("developer-options-menu") != null, "Unlocked developer menu missing");
            invoke(more(), "selectSection", new Class<?>[]{String.class}, "Developer");
        }); settle();
        main(() -> require("Developer".equals(field(more(), "selectedSection")) && root().findViewWithTag("developer-simulation-toggle") != null,
                "Unlocked developer screen is unreachable"));
    }
    private void verifySimulation() throws Exception {
        flushHistory(); final String databaseBefore = databaseSnapshot();
        final Map<String, ?> profilesBefore = new TreeMap<>(prefs("profiles").getAll()), evidenceBefore = new TreeMap<>(prefs("control_evidence").getAll());
        final Map<String, String> filesBefore = fileSnapshot();
        final long mutationsBefore = ((Number)field(activity.service().ble, "mutationSequence")).longValue();
        main(() -> { View toggle = root().findViewWithTag("developer-simulation-toggle"); require(toggle != null, "Missing simulation switch"); toggle.performClick(); });
        await(() -> activity.service().simulating(), "Simulation switch did not enable the preview"); settle();
        main(() -> {
            require(activity.service().ble.simulating() && activity.service().ble.ready() && activity.service().ble.connected(), "Simulation did not expose a local ready preview");
            require(activity.service().followsRidingMode(), "New profiles must follow riding mode by default");
            require(Integer.valueOf(3).equals(activity.service().ble.featureValue("range_algorithm")), "SPORT preview did not select Sport range model");
            activity.service().followRidingMode(false);
            require(activity.service().ble.set("range_algorithm", 2), "Fixed D estimate was rejected");
            activity.service().ble.tickSimulation();
            invoke(activity.service(), "followRangeMode");
            require(Integer.valueOf(2).equals(activity.service().ble.featureValue("range_algorithm")), "Automatic policy overwrote a fixed choice");
            activity.service().followRidingMode(true);
            require(Integer.valueOf(3).equals(activity.service().ble.featureValue("range_algorithm")), "Re-enabling Follow did not select Sport estimate");
            require(root().findViewWithTag("simulation-banner") != null && root().findViewWithTag("simulation-banner").isShown(), "Simulation is missing its persistent visible banner");
            Map<String, TelemetryHistory.Reading> shown = activity.service().displayReadings();
            for (Telemetry.Metric expected : new Telemetry().metrics()) {
                TelemetryHistory.Reading reading = shown.get(expected.key);
                require(reading != null && reading.value != null && !reading.value.isEmpty() && reading.live && reading.capturedWallMs > 0,
                        "Simulation omitted live parameter " + expected.key);
            }
            long now = System.currentTimeMillis(); List<TelemetryHistory.Point> chart = activity.service().simulationHistory("power_w", now - 86400000, now);
            require(!chart.isEmpty() && chart.size() <= 240, "Simulation history is empty or unbounded");
            for (TelemetryHistory.Point point : chart) require(point.wallMs >= now - 86400000 && point.wallMs <= now && Double.isFinite(point.value), "Simulation history produced an invalid point");
            invoke(activity, "showScanner", new Class<?>[]{boolean.class}, true);
            require(field(activity, "scanDialog") == null && activity.service().ble.connected(), "Simulation opened a real scanner or lost the local connection");
            activity.service().ble.disconnect();
            require(activity.service().simulating() && !activity.service().ble.ready() && !activity.service().ble.connected(), "Local disconnect did not stay in preview mode");
            activity.service().ble.visibility(false, false, 0); activity.service().ble.visibility(true, false, 0);
            require(!activity.service().ble.connected(), "Visibility silently undid an explicit simulated disconnect");
            activity.service().ble.connectLast();
            require(activity.service().ble.ready() && activity.service().ble.connected(), "Local reconnect failed");
            assertTransportIdle();
        });
        verifyScooterSelectors();
        final long sampleBefore = activity.service().ble.telemetry.metrics().get(0).updatedMs;
        SystemClock.sleep(6100);
        main(() -> {
            require(activity.service().ble.telemetry.metrics().get(0).updatedMs > sampleBefore, "Simulation did not refresh continuously");
            require(activity.service().ble.set("lock", 1), "Local lock preview rejected its change");
            require(Boolean.TRUE.equals(activity.service().ble.locked()), "Local lock preview did not change");
            require(activity.service().ble.set("lock", 0), "Local unlock preview rejected its change");
            require(Boolean.FALSE.equals(activity.service().ble.locked()), "Local unlock preview did not change");
            activity.service().startRide("Simulation must not be recorded");
            require(!activity.service().isRecording() && field(activity.service(), "phoneRecorder") == null, "Simulation started real recording or sensors");
            assertTransportIdle();
        });
        screenshot("simulation");
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home")); settle(); screenshot("simulation-home");
        main(() -> {
            activity.service().ble.setSimulationCharging(true);
            require(activity.service().ble.simulationCharging() && activity.service().ble.telemetry.number("power_w") < 0,
                    "Charging preview did not present charging power");
            for (Telemetry.Metric metric : activity.service().ble.telemetry.metrics()) require(metric.sourceCmd == -1, "Preview metric claims a real source opcode");
            require(activity.service().ble.telemetry.frames().isEmpty() && activity.service().ble.telemetry.statuses().isEmpty(), "Preview invented real response evidence");
            activity.service().ble.setSimulationCharging(false);
        });
        main(() -> { settings().edit().putBoolean("background_connection", true).commit(); activity.service().visible(false); });
        final long backgroundSample = activity.service().ble.telemetry.metrics().get(0).updatedMs;
        SystemClock.sleep(1250);
        main(() -> {
            require(activity.service().simulating() && !activity.service().workAllowed() && !Boolean.TRUE.equals(field(activity.service(), "workActive")), "Simulation keeps working in background");
            require(activity.service().ble.telemetry.metrics().get(0).updatedMs == backgroundSample, "Background simulation keeps ticking");
            require(!Boolean.TRUE.equals(field(activity.service(), "foregroundStarted")), "Simulation retained foreground service notification");
            require(!((android.os.PowerManager.WakeLock)field(activity.service(), "wakeLock")).isHeld(), "Simulation held a background wake lock");
            settings().edit().putBoolean("background_connection", false).commit(); activity.service().visible(true);
        }); settle();
        main(() -> { View banner = root().findViewWithTag("simulation-banner"); require(banner != null && banner.performClick(), "Preview exit banner is not actionable"); require(!activity.service().simulating(), "Banner did not disable simulation"); assertRealCache(); });
        flushHistory();
        require(databaseBefore.equals(databaseSnapshot()), "Simulation changed persistent telemetry history/cache rows");
        require(profilesBefore.equals(prefs("profiles").getAll()) && evidenceBefore.equals(prefs("control_evidence").getAll()), "Simulation changed profiles or real command evidence");
        require(filesBefore.equals(fileSnapshot()), "Simulation changed persistent private ride files");
        require(mutationsBefore == ((Number)field(activity.service().ble, "mutationSequence")).longValue(), "Simulation entered the real command mutation pipeline");
        main(() -> require(activity.service().setSimulation(true), "Could not re-enable preview for restart test"));
        ScooterService old = activity.service();
        main(() -> { activity.finish(); getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
        await(() -> Boolean.TRUE.equals(field(old, "destroyed")), "Old service did not finish for recreation test");
        launch();
        main(() -> { require(activity.service() != old && !activity.service().simulating() && !activity.service().ble.simulating(), "Simulation survived service recreation"); assertRealCache(); assertTransportIdle(); });
        flushHistory();
        require(databaseBefore.equals(databaseSnapshot()) && profilesBefore.equals(prefs("profiles").getAll()) && evidenceBefore.equals(prefs("control_evidence").getAll())
                && filesBefore.equals(fileSnapshot()), "Service recreation persisted simulated state");
    }
    /** Display-state injection stays inside the emulator's disconnected simulator; no GATT is made. */
    @SuppressWarnings("unchecked") private void verifyScooterSelectors() throws Exception {
        main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "lights")); settle();
        main(() -> {
            BleController ble=activity.service().ble;
            Map<String,Integer> values=(Map<String,Integer>)field(field(ble,"simulation"),"features");
            Map<String,String> results=(Map<String,String>)field(ble,"commandResults");
            for(String key:new String[]{"line_pattern","star_pattern"}){
                SegmentedSelector selector=(SegmentedSelector)root().findViewWithTag("scooter-selector-"+key);
                require(selector!=null,"Missing lighting selector "+key);Object row=control(key);invoke(row,"refresh");
                int before=values.get(key),next=(before+1)%3;
                selectorChoices(selector).get(next).performClick();invoke(row,"refresh");
                require(values.get(key)==next&&selected(selector)==next,"Confirmed simulated pattern not displayed: "+key+" actual="+values.get(key)+" selected="+selected(selector)+" enabled="+selector.isEnabled()+" fresh="+activity.service().featureFresh(key)+" message="+ble.message());
                assertChoiceTargets(selector);
                values.remove(key);invoke(row,"refresh");
                require(selected(selector)==-1&&((RectF)field(field(selector,"track"),"indicator")).isEmpty(),"Unknown pattern still highlights a choice");
                require(!selector.isEnabled()&&!selectorChoices(selector).get(0).isEnabled(),"Unknown pattern remains actionable");
                values.put(key,before);invoke(row,"refresh");
                setField(ble,"mutation",pending(key,next));invoke(row,"refresh");
                require(selected(selector)==before&&!selector.isEnabled(),"Pending write replaced confirmed pattern");
                selectorChoices(selector).get(next).performClick();require(values.get(key)==before,"Disabled pattern click wrote simulator data");
                setField(ble,"mutation",null);results.put(key,"Rejected (3)");invoke(row,"refresh");
                require(selected(selector)==before&&((TextView)field(row,"result")).getText().toString().contains("Rejected"),"Rejection lost previous selection or feedback");
                // A click that races a disconnection must roll back before its failed command.
                setField(ble,"state",BleController.State.DISCONNECTED);selectorChoices(selector).get(next).performClick();
                require(selected(selector)==before&&!selector.isEnabled(),"Failed write left optimistic pattern selected");
                setField(ble,"state",BleController.State.READY);invoke(row,"refresh");results.remove(key);
                values.put(key,next);invoke(row,"refresh");require(selected(selector)==next,"New readback did not move the highlight");
            }
            assertTransportIdle();activity.redraw();
            View pattern=root().findViewWithTag("scooter-selector-line_pattern");
            pattern.requestRectangleOnScreen(new Rect(0,0,pattern.getWidth(),pattern.getHeight()),true);
            android.view.ViewParent parent=pattern.getParent();while(parent!=null&&!(parent instanceof android.widget.ScrollView))parent=parent.getParent();
            if(parent instanceof android.widget.ScrollView)((android.widget.ScrollView)parent).scrollBy(0,activity.ui().dp(180));
        });settle();windowScreenshot("light-selectors",activity.getWindow().getDecorView());
        for(String key:new String[]{"energy_recovery","display_units","charge_limit","lock_expiry","prompt_tone"}){
            main(() -> {
                invoke(activity,"showChoice",new Class<?>[]{ScooterCommands.Feature.class},ScooterCommands.find(key));
                Object sheet=field(activity,"choiceSheet");SegmentedSelector selector=(SegmentedSelector)field(sheet,"selector");
                require(selector!=null,"Short fixed choice did not use slider: "+key);
                int[] values=(int[])field(sheet,"choices");Integer actual=activity.service().ble.featureValue(key);
                int index=0;while(values[index]!=actual)index++;
                require(selected(selector)==index,"Sheet did not select confirmed value");
                int next=(index+1)%values.length;
                setField(sheet,"target",values[next]);setField(sheet,"sent",true);
                setField(activity.service().ble,"mutation",pending(key,values[next]));invoke(sheet,"refresh");
                require(selected(selector)==index&&!selector.isEnabled(),"Pending sheet advertises unconfirmed target");
                setField(activity.service().ble,"mutation",null);
                ((Map<String,String>)field(activity.service().ble,"commandResults")).put(key,"Rejected (3)");invoke(sheet,"refresh");
                require(((AlertDialog)field(sheet,"dialog")).isShowing()&&selected(selector)==index
                        &&((TextView)field(sheet,"status")).getText().toString().contains("Rejected"),"Sheet rejection lost confirmed state or feedback");
                setField(sheet,"target",values[next]);setField(sheet,"sent",true);
                ((Map<String,Integer>)field(field(activity.service().ble,"simulation"),"features")).put(key,values[next]);invoke(sheet,"refresh");
                require(!((AlertDialog)field(sheet,"dialog")).isShowing(),"Confirmed readback did not dismiss sheet");
            });
        }
        main(() -> invoke(activity,"showChoice",new Class<?>[]{ScooterCommands.Feature.class},ScooterCommands.find("energy_recovery")));settle();
        main(() -> assertChoiceTargets((SegmentedSelector)field(field(activity,"choiceSheet"),"selector")));windowScreenshot("setting-selector",((AlertDialog)field(field(activity,"choiceSheet"),"dialog")).getWindow().getDecorView());
        main(() -> {((AlertDialog)field(field(activity,"choiceSheet"),"dialog")).dismiss();assertTransportIdle();});
        checks.add("Lighting and short fixed scooter choices use readable tracks, retain confirmed state while pending/rejected, clear unknown values, and close only after confirmed readback");
    }
    private Object control(String key){for(Object row:(List<?>)field(field(activity,"screen"),"rows"))if(((ScooterCommands.Feature)field(row,"feature")).key.equals(key))return row;throw new IllegalStateException("Missing control "+key);}
    @SuppressWarnings("unchecked") private List<TextView> selectorChoices(SegmentedSelector selector){return (List<TextView>)field(field(selector,"track"),"choices");}
    private int selected(SegmentedSelector selector){return ((Number)field(field(selector,"track"),"selected")).intValue();}
    private void assertChoiceTargets(SegmentedSelector selector){for(TextView choice:selectorChoices(selector)){require(choice.getWidth()>=activity.ui().dp(48)&&choice.getHeight()>=activity.ui().dp(48),"Choice target smaller than 48dp");require(choice.getPaint().measureText(choice.getText().toString())<=choice.getWidth()-choice.getCompoundPaddingLeft()-choice.getCompoundPaddingRight()+1,"Choice text clipped");AccessibilityNodeInfo node=choice.createAccessibilityNodeInfo();require(node.isCheckable()&&node.isChecked()==choice.isSelected(),"Incorrect choice accessibility state");node.recycle();}}
    private Object pending(String key,int value){try{Class<?> type=Class.forName("local.navee.offlinelock.BleController$Mutation");java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(String.class,int.class,int.class,long.class);constructor.setAccessible(true);return constructor.newInstance(key,0,value,0L);}catch(Exception e){throw new IllegalStateException(e);}}
    private void setField(Object target,String name,Object value){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new IllegalStateException(e);}}
    /** Native window render avoids unrelated queued Android toast windows obscuring the control. */
    private void windowScreenshot(String label,View window) throws Exception{
        AtomicReference<android.graphics.Bitmap> captured=new AtomicReference<>();
        main(()->{android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(window.getWidth(),window.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);window.draw(new android.graphics.Canvas(bitmap));captured.set(bitmap);});
        File dir=new File(getTargetContext().getExternalFilesDir(null),"developer-validation");require(dir.isDirectory()||dir.mkdirs(),"Screenshot directory unavailable");
        File file=new File(dir,arguments.getString("variant","developer").replaceAll("[^a-zA-Z0-9_-]","")+"-"+label+".png");
        try(FileOutputStream out=new FileOutputStream(file)){captured.get().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{captured.get().recycle();}screenshots.add(file.getAbsolutePath());
    }
    private void assertRealCache() {
        Map<String, TelemetryHistory.Reading> shown = activity.service().displayReadings();
        require(shown.get("battery_percent").number == 37d && !shown.get("battery_percent").live
                && shown.get("odometer_km").number == 123.45, "Simulation exit did not restore genuine stale readings");
    }
    private void assertTransportIdle() {
        BleController ble = activity.service().ble;
        for (String name : new String[]{"gatt", "writer", "scanner", "mutation"}) require(field(ble, name) == null, "Simulation touched real transport: " + name);
        require(((java.util.Collection<?>)field(ble, "queue")).isEmpty(), "Simulation queued real Bluetooth bytes");
        for (String name : new String[]{"scanning", "writing", "wanted", "challengePending", "pinReadPending"}) require(!Boolean.TRUE.equals(field(ble, name)), "Simulation started real transport work: " + name);
        require(!activity.service().isRecording() && !activity.service().phoneSaving() && field(activity.service(), "phoneRecorder") == null, "Simulation touched real recording/sensors");
        require(activity.service().profiles.active().mac.isEmpty(), "Simulation acquired or saved a real scooter address");
    }
    private void flushHistory() { AtomicReference<TelemetryHistory.Snapshot> ready = new AtomicReference<>(); main(() -> activity.service().history.load(profileId, ready::set)); await(() -> ready.get() != null, "History worker did not drain"); }
    private String databaseSnapshot() {
        File file = getTargetContext().getDatabasePath("telemetry-history.db"); require(file.exists(), "Synthetic history database missing");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            ArrayList<String> tables = new ArrayList<>(), rows = new ArrayList<>();
            try (Cursor names = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null)) { while (names.moveToNext()) tables.add(names.getString(0)); }
            for (String table : tables) {
                require(table.matches("[A-Za-z0-9_]+"), "Unexpected fixture table name");
                try (Cursor data = db.rawQuery("SELECT * FROM \"" + table + "\"", null)) {
                    while (data.moveToNext()) { StringBuilder row = new StringBuilder(table);
                        for (int col = 0; col < data.getColumnCount(); col++) { String value = data.isNull(col) ? "<NULL>" : data.getString(col); row.append('|').append(value.length()).append(':').append(value); }
                        rows.add(row.toString()); }
                }
            }
            Collections.sort(rows); return rows.toString();
        }
    }
    private Map<String, String> fileSnapshot() throws Exception { TreeMap<String, String> files = new TreeMap<>(); snapshotFiles(getTargetContext().getFilesDir(), "", files); return files; }
    private void snapshotFiles(File directory, String path, Map<String, String> files) throws Exception {
        File[] children = directory.listFiles(); require(children != null, "Private file fixture is unreadable");
        for (File file : children) { String key = path + file.getName(); if (file.isDirectory()) snapshotFiles(file, key + "/", files);
            else { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (FileInputStream in = new FileInputStream(file)) { byte[] buffer = new byte[4096]; int count; while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count); }
                files.put(key, Arrays.toString(digest.digest())); } }
    }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private MoreScreen more() { Object screen = field(activity, "screen"); require(screen instanceof MoreScreen, "More screen is not active"); return (MoreScreen)screen; }
    private View root() { return (View)field(activity, "root"); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void main(Runnable task) { runOnMainSync(task); }
    private void await(BooleanSupplier condition, String message) { long end = SystemClock.uptimeMillis() + 8000; while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(40); require(condition.getAsBoolean(), message); }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) { try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException e) { type = type.getSuperclass(); } } throw new NoSuchFieldException(name); } catch (Exception e) { throw new IllegalStateException("Field " + name, e); } }
    private Object invoke(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) { try { Class<?> type = target.getClass(); while (type != null) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (NoSuchMethodException e) { type = type.getSuperclass(); } } throw new NoSuchMethodException(name); } catch (Exception e) { throw new IllegalStateException("Method " + name, e); } }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "developer-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        String variant = arguments.getString("variant", "developer").replaceAll("[^a-zA-Z0-9_-]", ""); File file = new File(dir, variant + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
}
