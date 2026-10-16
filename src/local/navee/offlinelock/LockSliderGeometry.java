package local.navee.offlinelock;

/** Shared drawing/touch geometry: a horizontal thumb keeps travel even in a tall card. */
public final class LockSliderGeometry {
    public final float diameter, radius, firstCenter, travel;

    public LockSliderGeometry(float width, float height, float maximumDiameter, float inset) {
        diameter = Math.max(0, Math.min(Math.min(height, maximumDiameter), width / 2f));
        radius = Math.max(0, diameter / 2f - inset);
        firstCenter = diameter / 2f;
        travel = Math.max(0, width - diameter);
    }

    public float centerFor(float position) {
        return firstCenter + travel * Math.max(0, Math.min(1, position));
    }

    public float positionFor(float x) {
        return Math.max(0, Math.min(1, (x - firstCenter) / Math.max(1, travel)));
    }
}
