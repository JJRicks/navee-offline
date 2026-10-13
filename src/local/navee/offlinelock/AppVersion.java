package local.navee.offlinelock;

import android.content.Context;
import android.content.pm.PackageManager;

/** The installed manifest is the single source for user-visible release metadata. */
final class AppVersion {
    private AppVersion() {}

    static String name(Context context) {
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return version == null || version.isEmpty() ? "unknown" : version;
        } catch (PackageManager.NameNotFoundException unavailable) {
            return "unknown";
        }
    }
}
