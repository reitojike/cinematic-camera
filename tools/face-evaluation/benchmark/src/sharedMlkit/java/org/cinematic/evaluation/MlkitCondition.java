package org.cinematic.evaluation;

/** Exact evaluation conditions; not a product configuration or arbitrary tuning API. */
final class MlkitCondition {
    final boolean fast;
    final float minimumFaceSize;
    final String id;
    private MlkitCondition(boolean fast, float minimumFaceSize, String id) {
        this.fast = fast;
        this.minimumFaceSize = minimumFaceSize;
        this.id = id;
    }
    static MlkitCondition fromMode(String mode) {
        if ("FAST".equals(mode)) return new MlkitCondition(true, 0.1f,
            "mlkit-16.1.7-FAST-min0.1-no-tracking");
        if ("ACCURATE".equals(mode)) return new MlkitCondition(false, 0.1f,
            "mlkit-16.1.7-ACCURATE-min0.1-no-tracking");
        if ("ACCURATE_MIN005".equals(mode)) return new MlkitCondition(false, 0.05f,
            "mlkit-16.1.7-ACCURATE-min0.05-no-tracking");
        throw new IllegalArgumentException("mode");
    }
}
