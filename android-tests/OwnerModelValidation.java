package com.jjricks.volt.tests;

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
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import com.jjricks.volt.MainActivity;
import com.jjricks.volt.ScooterProfiles;

/**
 * Fresh disposable emulator only, no simulator. More › My scooters › Edit scooter › Which model is it? keeps the
 * owner's answer on a scooter Volt can't identify (Model ID 23451): Volt's default name follows it, the GT5 Max photo
 * (bundled ahead of its Model ID) appears in My scooters and on Home, Change model starts from the answer, and Not
 * sure clears it. A scooter Volt identifies itself (the UT5 MAX) offers no such choice.
 */
public final class OwnerModelValidation extends Instrumentation {
    private Bundle arguments;
    private MainActivity activity;
    private int assertions;
    private final ArrayList<String> screenshots = new ArrayList<>();
    private String unknownId, ut5Id;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            admit();
            require(settings().edit().putBoolean("auto_connect", false).putBoolean("background_connection", false)
                    .putString("theme", arguments.getString("theme", "dark")).commit(), "Fixture settings failed");
            seed();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> activity.service() != null, "App did not launch"); settle();
            main(() -> require(!activity.service().simulating(), "The simulator is on"));
            main(() -> { invoke(activity, "select", new Class<?>[]{String.class}, "more"); invoke(more(), "selectSection", new Class<?>[]{String.class}, "Scooters"); });
            settle();
            main(() -> require(modelPhoto(unknownId) == null, "A photo before any answer"));

            // Volt names the UT5 MAX itself, so it has no model question.
            main(() -> tag("scooter-edit-" + ut5Id).performClick()); settle();
            main(() -> { AlertDialog edit = lastDialog(); require(edit.getWindow().getDecorView().findViewWithTag("scooter-model-" + ut5Id) == null, "The UT5 MAX asks which model it is"); edit.dismiss(); });
            settle();

            // Which model is it? → GT5 Max → Save.
            main(() -> tag("scooter-edit-" + unknownId).performClick()); settle();
            main(() -> { View ask = lastDialog().getWindow().getDecorView().findViewWithTag("scooter-model-" + unknownId);
                require(ask instanceof TextView && "Which model is it?".contentEquals(((TextView) ask).getText()), "Edit scooter has no model question"); });
            screenshot("edit-ask");
            main(() -> lastDialog().getWindow().getDecorView().findViewWithTag("scooter-model-" + unknownId).performClick()); settle();
            require(sheetShowing() && "Save".equals(sheetText("model-next")) && !sheet("model-next").isEnabled()
                    && sheetText("model-status").startsWith("Volt shows this model's name and photo"), "The model sheet did not open with Save off");
            main(() -> ((android.widget.EditText) sheet("model-search")).setText("gt5 max")); settle();
            main(() -> sheet("model-gt5max").performClick()); settle();
            require(sheet("model-next").isEnabled(), "Choosing GT5 Max did not enable Save"); screenshot("sheet-gt5max");
            main(() -> sheet("model-next").performClick()); settle();
            require(!sheetShowing(), "Save did not close the sheet");
            main(() -> {
                ScooterProfiles.Profile p = profile(unknownId);
                require("GT5 Max".equals(p.ownerModel) && "GT5 Max".equals(p.name) && p.nameDefault, "The answer or Volt's default name was not kept: " + p.ownerModel + " / " + p.name);
                require("".equals(profile(ut5Id).ownerModel), "Another scooter changed");
                require(new ScooterProfiles(getTargetContext()).all().stream().anyMatch(q -> q.id.equals(unknownId) && "GT5 Max".equals(q.ownerModel)), "The answer was not saved");
            });
            AtomicReference<android.graphics.Bitmap> gt5 = new AtomicReference<>();
            main(() -> { gt5.set(modelPhoto(unknownId)); require(gt5.get() != null && "gt5max".equals(photoKey(unknownId)), "Volt has no GT5 Max photo for the answer"); });
            await(() -> shows(gt5.get()), "My scooters shows no GT5 Max photo");
            screenshot("my-scooters-gt5max");
            main(() -> invoke(activity, "select", new Class<?>[]{String.class}, "home")); settle();
            AtomicReference<android.graphics.Bitmap> home = new AtomicReference<>();
            main(() -> home.set(modelPhoto(unknownId, 2)));
            await(() -> shows(home.get()), "Home shows no GT5 Max photo");
            screenshot("home-gt5max");

            // Change model starts from the answer; Not sure clears it and Volt's default name follows.
            main(() -> { invoke(activity, "select", new Class<?>[]{String.class}, "more"); invoke(more(), "selectSection", new Class<?>[]{String.class}, "Scooters"); });
            settle();
            main(() -> tag("scooter-edit-" + unknownId).performClick()); settle();
            main(() -> { View root = lastDialog().getWindow().getDecorView(), change = root.findViewWithTag("scooter-model-" + unknownId);
                require(change instanceof TextView && "Change model".contentEquals(((TextView) change).getText()) && text(root, "Your answer: GT5 Max") != null, "Edit scooter does not show the answer");
                change.performClick(); });
            settle();
            require(sheetShowing() && sheet("model-gt5max").isSelected() && sheet("model-next").isEnabled(), "Change model did not start from the answer");
            // Only listed models can be kept, so this sheet offers no write-in: an unlisted name says so instead of a Save that keeps nothing.
            main(() -> ((android.widget.EditText) sheet("model-search")).setText("Mystery GT9")); settle();
            require(sheet("model-typed") == null && sheet("model-none") != null && sheetText("model-none").startsWith("No listed model matches “Mystery GT9”"),
                    "Edit scooter offered a write-in it cannot keep");
            // The search hides the earlier answer, so Save is off: no save that only looks like it kept the typed name.
            require(!sheet("model-next").isEnabled(), "Save stayed on while the search hides the answer");
            screenshot("sheet-unlisted");
            main(() -> ((android.widget.EditText) sheet("model-search")).setText("gt5")); settle();
            require(sheet("model-gt5max").isSelected() && sheet("model-next").isEnabled(), "Save did not return with the answer in view");
            main(() -> ((android.widget.EditText) sheet("model-search")).setText("")); settle();
            main(() -> sheet("model-unsure").performClick()); settle();
            main(() -> sheet("model-next").performClick()); settle();
            main(() -> { ScooterProfiles.Profile p = profile(unknownId); require("".equals(p.ownerModel) && "NAVEE scooter".equals(p.name), "Not sure did not clear the answer: " + p.ownerModel + " / " + p.name); });
            main(() -> require(modelPhoto(unknownId) == null && photoKey(unknownId).isEmpty(), "Volt still has a photo after Not sure"));
            await(() -> !shows(gt5.get()), "The GT5 Max photo stayed after Not sure");
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString()); error.printStackTrace();
        } finally {
            result.putInt("assertions", assertions); result.putString("screenshots", screenshots.toString());
            result.putBoolean("physical_device_tested", false);
            try { if (activity != null) main(() -> activity.finish()); } catch (Throwable ignored) { }
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
        require(manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled(), "Emulator Bluetooth must stay off");
    }
    /** A scooter Volt can't name (Model ID 23451, default name) and a UT5 MAX; synthetic identities only. */
    private void seed() {
        main(() -> {
            ScooterProfiles profiles = new ScooterProfiles(getTargetContext());
            ScooterProfiles.Profile ut5 = profiles.create("Synthetic UT5", "A1B2C3D4E5F6", 0);
            ut5.mac = "C0:FF:EE:00:00:01"; ut5.advertisedPid = "25381"; ut5.advertisedPidWall = 1; ut5.lastConnected = System.currentTimeMillis() - 60000; profiles.update(ut5);
            ScooterProfiles.Profile unknown = profiles.create("", "0F1E2D3C4B5A", 0);
            unknown.mac = "C0:FF:EE:00:00:02"; unknown.advertisedPid = "23451"; unknown.advertisedPidWall = 1; unknown.lastConnected = System.currentTimeMillis() - 60000; profiles.update(unknown);
            ut5Id = ut5.id; unknownId = unknown.id;
            require(unknown.nameDefault && ut5.ownerModel.isEmpty() && unknown.ownerModel.isEmpty(), "Fixture profiles are not as expected");
        });
        prefs("onboarding").edit().putBoolean("onboarding_done", true).commit();
    }
    private ScooterProfiles.Profile profile(String id) { for (ScooterProfiles.Profile p : activity.service().profiles.all()) if (p.id.equals(id)) return p; throw new IllegalStateException("No profile " + id); }
    /** On the main thread: the photo Volt decodes for this scooter's saved descriptor (My scooters samples at 4, Home at 2), or null. */
    private android.graphics.Bitmap modelPhoto(String id) { return modelPhoto(id, 4); }
    private android.graphics.Bitmap modelPhoto(String id, int sample) {
        try {
            ClassLoader app = activity.getClassLoader(); Object descriptor = profile(id).savedDescriptor();
            Class<?> photos = Class.forName("com.jjricks.volt.ScooterPhotos", true, app), type = Class.forName("com.jjricks.volt.DeviceDescriptor", true, app);
            Method get = photos.getDeclaredMethod("get", android.content.res.Resources.class, type, int.class); get.setAccessible(true);
            return (android.graphics.Bitmap) get.invoke(null, activity.getResources(), descriptor, sample);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private String photoKey(String id) {
        try { Class<?> registry = Class.forName("com.jjricks.volt.ModelRegistry", true, activity.getClassLoader());
            return (String) registry.getMethod("photo", Class.forName("com.jjricks.volt.DeviceDescriptor", true, activity.getClassLoader())).invoke(null, profile(id).savedDescriptor()); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    /** Some shown image on screen draws exactly this bitmap (Volt caches each decoded photo, so it is the same object). */
    private boolean shows(android.graphics.Bitmap bitmap) {
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        main(() -> found.set(bitmap != null && holds(root(), bitmap)));
        return found.get();
    }
    private static boolean holds(View view, android.graphics.Bitmap bitmap) {
        if (view instanceof ImageView && view.isShown() && ((ImageView) view).getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                && ((android.graphics.drawable.BitmapDrawable) ((ImageView) view).getDrawable()).getBitmap() == bitmap) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (holds(((ViewGroup) view).getChildAt(i), bitmap)) return true;
        return false;
    }
    private static TextView text(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView found = text(((ViewGroup) view).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private View tag(String tag) { View v = root().findViewWithTag(tag); require(v != null, "Missing " + tag); return v; }
    private AlertDialog lastDialog() { List<?> list = (List<?>) field(more(), "dialogs"); require(!list.isEmpty(), "Dialog missing"); return (AlertDialog) list.get(list.size() - 1); }
    private android.app.Dialog sheetDialog() { return (android.app.Dialog) field(activity, "reportDialog"); }
    private boolean sheetShowing() { AtomicReference<Boolean> open = new AtomicReference<>(false); main(() -> open.set(sheetDialog() != null && sheetDialog().isShowing())); return open.get(); }
    /** The view with this tag in the showing model sheet (callable on or off the main thread). */
    private View sheet(String tag) {
        AtomicReference<View> found = new AtomicReference<>();
        Runnable look = () -> { android.app.Dialog d = sheetDialog(); found.set(d != null && d.isShowing() ? d.getWindow().getDecorView().findViewWithTag(tag) : null); };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) look.run(); else main(look);
        return found.get();
    }
    private String sheetText(String tag) { AtomicReference<String> text = new AtomicReference<>(""); main(() -> { View v = sheet(tag); if (v instanceof TextView) text.set(String.valueOf(((TextView) v).getText())); }); return text.get(); }
    private void screenshot(String label) throws Exception {
        File dir = new File(getTargetContext().getExternalFilesDir(null), "owner-model-validation"); require(dir.isDirectory() || dir.mkdirs(), "Screenshot directory unavailable");
        File file = new File(dir, arguments.getString("theme", "dark") + "-" + label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot(); require(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
        screenshots.add(file.getAbsolutePath());
    }
    private View root() { return (View) field(activity, "root"); }
    private Object more() { Object screen = field(activity, "screen"); require(screen != null && screen.getClass().getSimpleName().equals("MoreScreen"), "More screen is not active"); return screen; }
    private SharedPreferences settings() { return prefs("app_settings"); }
    private SharedPreferences prefs(String name) { return getTargetContext().getSharedPreferences(name, Context.MODE_PRIVATE); }
    private void settle() { waitForIdleSync(); SystemClock.sleep(400); waitForIdleSync(); }
    private void main(Runnable task) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new IllegalStateException(error.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(100);
        require(condition.getAsBoolean(), message);
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new IllegalStateException(message); }
    private Object field(Object target, String name) {
        try { Class<?> type = target.getClass(); while (type != null) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (NoSuchFieldException next) { type = type.getSuperclass(); } }
            throw new NoSuchFieldException(name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
