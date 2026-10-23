package com.jjricks.volt;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import java.util.HashMap;
import java.util.Map;

/** Bundled model photos, decoded once per model and sample size. Sample sizes assume a 2048 px source; a smaller one is sampled less. */
final class ScooterPhotos {
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private ScooterPhotos() {}

    /** This model's photo, or null when it has none or cannot be decoded. */
    static synchronized Bitmap get(Resources resources, DeviceDescriptor d, int sampleSize) {
        String key = ModelRegistry.photo(d);
        int id = resource(key);
        if (id == 0) return null;
        Bitmap cached = CACHE.get(key + "@" + sampleSize);
        if (cached != null) return cached;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(resources, id, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = bounds.outWidth > 0 ? Math.max(1, sampleSize * bounds.outWidth / 2048) : sampleSize;
            Bitmap decoded = BitmapFactory.decodeResource(resources, id, options);
            if (decoded != null) CACHE.put(key + "@" + sampleSize, decoded);
            return decoded;
        } catch (RuntimeException | OutOfMemoryError unavailable) { return null; }
    }

    /** Each bundled photo, including those waiting for their Model ID (ModelRegistryTest checks every key has one here). */
    private static int resource(String key) {
        switch (key) {
            case "ut5max": return R.drawable.ut5max;
            case "n65i": return R.drawable.n65i;
            case "st3pro": return R.drawable.st3pro;
            case "gt5max": return R.drawable.gt5max;
            case "ut5ultrax": return R.drawable.ut5ultrax;
            case "gt3max": return R.drawable.gt3max;
            default: return 0;
        }
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
