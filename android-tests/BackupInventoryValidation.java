package local.navee.offlinelock.tests;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/**
 * Disposable emulator only, read-only. Run after the other journeys have populated the app
 * (rides, charges, history, dashboards, map tiles, exports): every preference namespace and key,
 * and every storage root actually present, must be classified by the backup registry.
 */
public final class BackupInventoryValidation extends Instrumentation {
    private int assertions;
    /** Written only by AppDataMaintenanceValidation to prove writers are frozen; not app storage. */
    private static final java.util.Set<String> TEST_MARKERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "app_settings/maintenance_test_marker", "root files/maintenance-write-sentinel"));
    private final ArrayList<String> problems = new ArrayList<>();
    @Override public void onCreate(Bundle args) { start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
            require(hardware.contains("ranchu") || hardware.contains("goldfish") || Build.PRODUCT.startsWith("sdk_gphone"), "Refusing physical phone");
            Context app = getTargetContext();
            Class<?> registry = Class.forName("local.navee.offlinelock.BackupRegistry");
            Method rule = registry.getDeclaredMethod("rule", String.class, String.class), namespace = registry.getDeclaredMethod("namespace", String.class), root = registry.getDeclaredMethod("root", String.class);
            for (Method m : new Method[]{rule, namespace, root}) m.setAccessible(true);
            java.lang.reflect.Field privateField = registry.getDeclaredField("PRIVATE_NAMESPACES"); privateField.setAccessible(true);
            java.util.List<String> privateNamespaces = java.util.Arrays.asList((String[]) privateField.get(null));
            File prefs = new File(app.getApplicationInfo().dataDir, "shared_prefs");
            File[] files = prefs.listFiles();
            int keys = 0;
            if (files != null) for (File file : files) {
                String name = file.getName().replaceAll("\\.xml$", "");
                if (name.endsWith(".bak")) continue;
                if (privateNamespaces.contains(name)) continue;
                if (!(Boolean) namespace.invoke(null, name)) { problems.add("namespace " + name); continue; }
                for (Map.Entry<String, ?> entry : app.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                    keys++;
                    if (rule.invoke(null, name, entry.getKey()) == null) problems.add(name + "/" + entry.getKey());
                }
            }
            for (String[] folder : new String[][]{{"files", app.getFilesDir().getPath()}, {"no_backup", app.getNoBackupFilesDir().getPath()}, {"cache", app.getCacheDir().getPath()}, {"databases", app.getDatabasePath("x").getParent()}}) {
                File[] children = new File(folder[1]).listFiles();
                if (children != null) for (File child : children) {
                    try { root.invoke(null, folder[0] + "/" + child.getName()); }
                    catch (java.lang.reflect.InvocationTargetException unclassified) { problems.add("root " + folder[0] + "/" + child.getName()); }
                }
            }
            problems.removeAll(TEST_MARKERS);
            require(keys > 0, "Run after populating the app; no preferences were found");
            require(problems.isEmpty(), "Unclassified storage, assign it in BackupRegistry: " + problems);
            result.putString("result", "PASS"); result.putInt("keys", keys);
        } catch (Throwable failure) { result.putString("result", "FAIL"); result.putString("error", failure.toString()); }
        result.putInt("assertions", assertions); result.putInt("scooter_mutations", 0);
        finish("PASS".equals(result.getString("result")) ? 0 : 1, result);
    }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
