package local.navee.offlinelock;
import android.view.View;
/** A screen can refresh values without rebuilding its inputs or scroll position. */
public interface AppScreen { View view(); void refresh(); default void close() {} }
