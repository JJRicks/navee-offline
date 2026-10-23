package com.jjricks.volt;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import java.util.HashMap;
import java.util.Map;

/** Bundled model photos, decoded once per model and sample size; the sources are 2048 px. */
final class ScooterPhotos {
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private ScooterPhotos() {}

    /** This model's photo, or null when it has none or cannot be decoded. */
    static synchronized Bitmap get(Resources resources, DeviceDescriptor d, int sampleSize) {
        String key = ModelRegistry.photo(d);
        int id = "ut5max".equals(key) ? R.drawable.ut5max : "n65i".equals(key) ? R.drawable.n65i : 0;
        if (id == 0) return null;
        Bitmap cached = CACHE.get(key + "@" + sampleSize);
        if (cached != null) return cached;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sampleSize;
            Bitmap decoded = BitmapFactory.decodeResource(resources, id, options);
            if (decoded != null) CACHE.put(key + "@" + sampleSize, decoded);
            return decoded;
        } catch (RuntimeException | OutOfMemoryError unavailable) { return null; }
    }

    /** Shows the model's photo in view, replacing another model's; false (view untouched) when it has none. */
    static boolean show(ImageView view, DeviceDescriptor d, int sampleSize) {
        Bitmap photo = get(view.getResources(), d, sampleSize);
        if (photo == null) return false;
        if (!(view.getDrawable() instanceof BitmapDrawable) || ((BitmapDrawable) view.getDrawable()).getBitmap() != photo)
            view.setImageBitmap(photo);
        String model = ModelRegistry.modelName(d);
        if (view.getImportantForAccessibility() != ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO)
            view.setContentDescription("NAVEE " + (model.isEmpty() ? "" : model + " ") + "scooter");
        return true;
    }
}
