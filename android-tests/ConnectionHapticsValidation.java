package local.navee.offlinelock.tests;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.view.View;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.navee.offlinelock.*;

/** Disposable emulator regression: real window lifecycle and system vibrator history. */
public final class ConnectionHapticsValidation extends Instrumentation {
    private MainActivity activity;
    private Object player;
    private final Bundle result = new Bundle();
    private final ArrayList<String> checks = new ArrayList<>();
    private File evidence;
    private String oldSystem;
    private int assertions;
    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        try {
            require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical device");
            require(prefs("profiles").getAll().isEmpty() && prefs("scooter").getAll().isEmpty(), "Refusing existing profiles; clear disposable app first");
            require(Build.VERSION.SDK_INT >= 34, "Requires API 34+ vibrator diagnostics");
            require(prefs("app_settings").getAll().isEmpty(), "Refusing existing app preferences");
            require(getTargetContext().databaseList().length == 0, "Refusing existing databases");
            require(getTargetContext().getFilesDir().list().length == 0, "Refusing existing files");
            require(getTargetContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED, "Grant Nearby permission first");
            BluetoothManager manager = getTargetContext().getSystemService(BluetoothManager.class);
            require(manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Disable emulator Bluetooth first");
            evidence = new File(getTargetContext().getExternalFilesDir(null), "haptics-validation/connection");
            require(evidence.mkdirs() || evidence.isDirectory(), "Evidence directory");
            oldSystem = shell("settings get system haptic_feedback_enabled").trim();
            require(oldSystem.equals("null") || oldSystem.matches("[0-9]+"), "Unexpected haptic preference value");
            shell("settings put system haptic_feedback_enabled 1");
            main(() -> {
                prefs("app_settings").edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putBoolean("developer_options", true).putBoolean("haptics_enabled", true).commit();
                new ScooterProfiles(getTargetContext()).create("Synthetic connection fixture", "000000000000", 0);
                OnboardingScreen.markDone(getTargetContext());
            });
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null && activity.getWindow().getDecorView().hasWindowFocus(), "Activity not ready");
            player = field(activity, "connectionHaptics");
            Vibrator vibrator = getTargetContext().getSystemService(Vibrator.class);
            require(vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_THUD, VibrationEffect.Composition.PRIMITIVE_TICK), "Emulator must support CLICK, THUD and TICK compositions");
            check("connected-cue", new String[]{"Primitive=CLICK(scale=0.25", "Primitive=THUD(scale=0.85"}, () -> main(() -> play(true)));
            check("disconnected-cue", new String[]{"Primitive=TICK(scale=0.35", "Primitive=TICK(scale=0.18", "Primitive=TICK(scale=0.08"}, () -> main(() -> play(false)));
            check("cancel-immediate", new String[]{"Primitive=CLICK(scale=0.25"}, () -> main(() -> { play(true); call(player,"cancel"); }));
            check("replacement-cue", new String[]{"Primitive=CLICK(scale=0.25", "Primitive=TICK(scale=0.35", "Primitive=TICK(scale=0.18", "Primitive=TICK(scale=0.08"}, () -> main(() -> { play(true); play(false); }));
            main(() -> prefs("app_settings").edit().putBoolean("haptics_enabled", false).commit()); settle();
            check("app-opt-out", new String[]{}, () -> main(() -> play(true)));
            main(() -> prefs("app_settings").edit().putBoolean("haptics_enabled", true).commit());
            shell("settings put system haptic_feedback_enabled 0"); settle();
            check("system-opt-out", new String[]{}, () -> main(() -> play(false)));
            shell("settings put system haptic_feedback_enabled 1"); settle();
            check("app-opt-out-mid-cue", new String[]{"Primitive=CLICK(scale=0.25"}, () -> main(() -> { play(true); prefs("app_settings").edit().putBoolean("haptics_enabled", false).commit(); }));
            main(() -> prefs("app_settings").edit().putBoolean("haptics_enabled", true).commit()); settle();
            main(() -> activity.getWindow().getDecorView().setHapticFeedbackEnabled(false));
            check("view-opt-out", new String[]{}, () -> main(() -> play(true)));
            main(() -> activity.getWindow().getDecorView().setHapticFeedbackEnabled(true));
            check("detached-view", new String[]{}, () -> main(() -> call(player,"play",new Class<?>[]{View.class,boolean.class},new View(activity),true)));
            AtomicReference<AlertDialog> dialog = new AtomicReference<>();
            checkLifecycleCancel("dialog-cancels-cue", () -> {
                dialog.set(new AlertDialog.Builder(activity).setMessage("Disposable focus check").create());
                dialog.get().show();
            }, () -> !activity.getWindow().getDecorView().hasWindowFocus(), "Dialog did not take focus");
            check("dialog-covered", new String[]{}, () -> main(() -> play(false)));
            check("dialog-dismiss-no-replay", new String[]{}, () -> { main(() -> dialog.get().dismiss()); await(() -> activity.getWindow().getDecorView().hasWindowFocus(), "Focus not restored"); });
            checkLifecycleCancel("pause-cancels-cue", () ->
                    activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
                    () -> !Boolean.TRUE.equals(field(activity,"resumed")), "Activity did not pause");
            check("paused-player", new String[]{}, () -> main(() -> play(false)));
            check("resume-no-replay", new String[]{}, () -> { getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); await(() -> activity.getWindow().getDecorView().hasWindowFocus() && Boolean.TRUE.equals(field(activity,"resumed")),"Activity did not resume"); });
            check("simulation-edges-silent", new String[]{}, () -> main(() -> { require(activity.service().setSimulation(true),"Simulation on failed"); activity.service().ble.disconnect(); activity.service().ble.connectLast(); require(activity.service().setSimulation(false),"Simulation off failed"); }));
            check("unauthenticated-ready-silent", new String[]{}, () -> main(() -> { setField(activity.service().ble,"state",BleController.State.READY); call(activity,"observeConnectionFeedback",new Class<?>[]{boolean.class},false); setField(activity.service().ble,"state",BleController.State.DISCONNECTED); }));
            // Seed only the activity-local previous state. No fake GATT object, connection, or traffic.
            check("observer-disconnect", new String[]{"Primitive=TICK(scale=0.35", "Primitive=TICK(scale=0.18", "Primitive=TICK(scale=0.08"}, () -> main(() -> { call(field(activity,"connectionFeedback"),"baseline",new Class<?>[]{Object.class,boolean.class},activity.service().ble,true); call(activity,"observeConnectionFeedback",new Class<?>[]{boolean.class},false); }));
            check("observer-repeat-silent", new String[]{}, () -> main(() -> { call(activity,"observeConnectionFeedback",new Class<?>[]{boolean.class},false); activity.redraw(); }));
            // Exercise the real focus callbacks, not just a hand-edited activity flag. A lost
            // connection underneath a settings dialog is consumed without a surprise cue later.
            main(() -> {
                dialog.set(new AlertDialog.Builder(activity).setMessage("Connection hidden by settings").create());
                dialog.get().show();
            });
            await(() -> !activity.getWindow().getDecorView().hasWindowFocus(), "Settings dialog did not take focus");
            check("dialog-hidden-connection-edge", new String[]{}, () -> main(() -> {
                call(field(activity,"connectionFeedback"),"baseline",new Class<?>[]{Object.class,boolean.class},activity.service().ble,true);
                call(activity,"observeConnectionFeedback",new Class<?>[]{boolean.class},false);
            }));
            check("dialog-hidden-edge-not-replayed", new String[]{}, () -> {
                main(() -> dialog.get().dismiss());
                await(() -> activity.getWindow().getDecorView().hasWindowFocus(), "Settings focus not restored");
                main(() -> call(activity,"observeConnectionFeedback",new Class<?>[]{boolean.class},false));
            });
            check("observer-hidden-disconnect-silent", new String[]{}, () -> main(() -> { setField(activity,"resumed",false); call(field(activity,"connectionFeedback"),"baseline",new Class<?>[]{Object.class,boolean.class},activity.service().ble,true); call(activity,"observeConnectionFeedback",new Class<?>[]{boolean.class},false); setField(activity,"resumed",true); call(activity,"observeConnectionFeedback",new Class<?>[]{boolean.class},true); }));
            main(this::assertTransportIdle);
            checkLifecycleCancel("destroy-cancels-cue", () -> activity.finish(),
                    () -> Boolean.TRUE.equals(field(activity,"destroyed")), "Activity not destroyed");
            result.putString("result", "PASS");
        } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("error", error.toString()); }
        finally {
            if (oldSystem != null) try {
                shell(oldSystem.equals("null") ? "settings delete system haptic_feedback_enabled"
                        : "settings put system haptic_feedback_enabled " + oldSystem);
                require(oldSystem.equals(shell("settings get system haptic_feedback_enabled").trim()), "System haptics preference cleanup failed");
            } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("cleanup_error", error.toString()); }
            if (activity != null) try {
                main(() -> { activity.finish(); ScooterService.setUiVisible(false);
                    getTargetContext().stopService(new Intent(getTargetContext(), ScooterService.class)); });
                waitForIdleSync();
            } catch (Throwable error) { result.putString("result", "FAIL"); result.putString("cleanup_error", error.toString()); }
            // The portable runner clears synthetic preferences and data in its outer finally.
            // This native finally still restores the global setting if an assertion fails.
            result.putInt("assertions", assertions); result.putStringArrayList("checks", checks); result.putBoolean("physical_device_tested", false);
            finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
        }
    }
    /** Window focus changes are asynchronous. A pulse may start before Android actually
     * transfers focus; assert cancellation at that transfer and silence afterward, rather
     * than assuming dialog.show()/startActivity()/finish() instantly changes window focus. */
    private void checkLifecycleCancel(String label, Runnable transition, BooleanSupplier complete,
                                     String failure) throws Exception {
        settle();
        String before = dump(label + "-before");
        final int[] playingGeneration = new int[1];
        main(() -> {
            play(true);
            playingGeneration[0] = ((Number) field(player, "generation")).intValue();
            transition.run();
        });
        await(complete, failure);
        main(() -> {
            require(((Number) field(player, "generation")).intValue() > playingGeneration[0],
                    label + ": lifecycle did not cancel the cue");
            require(field(player, "activeVibrator") == null, label + ": vibrator still owned after cancellation");
        });
        String atTransition = dump(label + "-transition");
        settle();
        String after = dump(label + "-after");
        Set<String> requests = requestIdentities(after);
        requests.removeAll(requestIdentities(before));
        ArrayList<String> effects = new ArrayList<>(events(after));
        effects.removeAll(events(before));
        require(requests.size() >= 1 && requests.size() <= 2, label + ": unexpected connection pulses " + effects);
        require(effects.get(0).contains("Primitive=CLICK(scale=0.25"), label + ": first pulse was not CLICK");
        if (effects.size() == 2) require(effects.get(1).contains("Primitive=THUD(scale=0.85"), label + ": second pulse was not THUD");
        Set<String> later = requestIdentities(after);
        later.removeAll(requestIdentities(atTransition));
        require(later.isEmpty(), label + ": pulse started after focus/lifecycle cancellation " + later);
        checks.add(label + ": lifecycle cancelled; no later pulses (" + requests.size() + " earlier requests)");
    }
    // Status/end times can change after cancellation; request timestamp+type remains stable.
    private Set<String> requestIdentities(String dump) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : events(dump)) {
            String[] fields = line.split("\\|", 3);
            if (fields.length >= 2) out.add(fields[0].trim() + "|" + fields[1].trim());
        }
        return out;
    }
    private void play(boolean connected) { call(player,"play",new Class<?>[]{View.class,boolean.class},activity.getWindow().getDecorView(),connected); }
    private void check(String label, String[] expected, Runnable action) throws Exception {
        settle(); String before=dump(label+"-before"); action.run(); settle(); String after=dump(label+"-after");
        Set<String> diff=events(after);diff.removeAll(events(before)); ArrayList<String> requested=new ArrayList<>(diff);
        require(requested.size()==expected.length,label+": expected "+expected.length+" requests, got "+requested);
        for(int i=0;i<expected.length;i++) require(requested.get(i).contains(expected[i]),label+": expected "+expected[i]+", got "+requested.get(i));
        checks.add(label+": "+requested.size()+" requests");
    }
    private Set<String> events(String dump){Set<String> out=new LinkedHashSet<>();for(String line:dump.split("\n"))if(line.contains("local.navee.offlinelock (uid="))out.add(line.trim());return out;}
    private String dump(String name)throws Exception{String text=shell("dumpsys vibrator_manager");try(FileOutputStream out=new FileOutputStream(new File(evidence,name+".txt"))){out.write(text.getBytes(StandardCharsets.UTF_8));}return text;}
    private String shell(String command){try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int count;while((count=in.read(buf))>=0)out.write(buf,0,count);return out.toString("UTF-8");}catch(Exception e){throw new RuntimeException(e);}}
    private void assertTransportIdle(){BleController ble=activity.service().ble;for(String name:new String[]{"gatt","writer","scanner","mutation"})require(field(ble,name)==null,"Transport created "+name);require(((Collection<?>)field(ble,"queue")).isEmpty(),"Queued Bluetooth bytes");for(String name:new String[]{"scanning","writing","wanted","challengePending","pinReadPending"})require(!Boolean.TRUE.equals(field(ble,name)),"Transport flag "+name);require(!activity.service().isRecording()&&!activity.service().isChargeRecording()&&!activity.service().phoneSaving()&&field(activity.service(),"phoneRecorder")==null,"Recording started");require(activity.service().profiles.active().mac.isEmpty(),"Real address present");}
    private Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new RuntimeException(e);}}
    private void setField(Object target,String name,Object value){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new RuntimeException(e);}}
    private Object call(Object target,String name,Class<?>[] types,Object...args){try{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}catch(Exception e){throw new RuntimeException(e);}}
    private Object call(Object target,String name){return call(target,name,new Class<?>[]{});}
    private SharedPreferences prefs(String name){return getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE);}
    private void main(Runnable action){AtomicReference<Throwable> error=new AtomicReference<>();runOnMainSync(()->{try{action.run();}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new RuntimeException(error.get());}
    private void await(BooleanSupplier condition,String message){long until=SystemClock.uptimeMillis()+8000;while(SystemClock.uptimeMillis()<until){AtomicReference<Boolean> ready=new AtomicReference<>(false);main(()->ready.set(condition.getAsBoolean()));if(ready.get())return;SystemClock.sleep(100);}throw new AssertionError(message);}
    private void settle(){waitForIdleSync();SystemClock.sleep(550);}
    private void require(boolean pass,String message){assertions++;if(!pass)throw new AssertionError(message);}
}
