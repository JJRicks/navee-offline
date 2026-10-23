package com.jjricks.volt;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** User-facing clocks follow the phone unless the user chooses an explicit clock format. */
public final class DisplayTime {
    private DisplayTime() {}

    public static boolean is24Hour(Context context, SharedPreferences settings) {
        String choice = settings.getString("time_format", "system");
        return choice.equals("24h") || (!choice.equals("12h") && DateFormat.is24HourFormat(context));
    }

    public static String clock(Context context, SharedPreferences settings, long wall) {
        return wall <= 0 ? "" : format(is24Hour(context, settings) ? "Hm" : "hm", wall);
    }

    public static String dateTime(Context context, SharedPreferences settings, long wall) {
        return wall <= 0 ? "" : format("yMMMd", wall) + " · " + clock(context, settings, wall);
    }

    /** A readable local date (including year), preferred clock, and the mode at ride start. */
    public static String rideName(Context context, SharedPreferences settings, long wall, String mode) {
        return format("yMMMd", wall) + " - " + clock(context, settings, wall)
                + (mode == null || mode.isEmpty() ? "" : " - " + mode);
    }

    /** Today needs only a clock; other local calendar days use a compact 05 MAR prefix. */
    public static String recent(Context context, SharedPreferences settings, long wall, long now) {
        if (wall <= 0) return "";
        Calendar then = Calendar.getInstance(), today = Calendar.getInstance();
        then.setTimeInMillis(wall); today.setTimeInMillis(now);
        boolean sameYear = then.get(Calendar.YEAR) == today.get(Calendar.YEAR);
        if (sameYear && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR))
            return clock(context, settings, wall);
        String date = new SimpleDateFormat("dd MMM", Locale.ENGLISH)
                .format(new Date(wall)).toUpperCase(Locale.ENGLISH);
        return date + " · " + clock(context, settings, wall);
    }

    private static String format(String skeleton, long wall) {
        Locale locale = Locale.getDefault();
        String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        return new SimpleDateFormat(pattern, locale).format(new Date(wall));
    }
}
