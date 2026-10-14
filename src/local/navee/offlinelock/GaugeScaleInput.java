package local.navee.offlinelock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.DoubleUnaryOperator;

/** Rounded editor text must not silently change a saved canonical gauge limit. */
final class GaugeScaleInput {
    private final double original;
    final String text;

    GaugeScaleInput(double canonical, double displayed) {
        original = canonical;
        BigDecimal decimal = BigDecimal.valueOf(displayed);
        BigDecimal rounded = decimal.setScale(6, RoundingMode.HALF_UP);
        // Do not show a genuinely nonzero, very small bound as zero.
        if (rounded.signum() == 0 && decimal.signum() != 0) rounded = decimal;
        text = rounded.stripTrailingZeros().toPlainString();
    }

    double resolve(String entered, DoubleUnaryOperator toCanonical) {
        double displayed = Double.parseDouble(entered.trim());
        if (!Double.isFinite(displayed)) throw new NumberFormatException("Non-finite limit");
        double value = displayed == Double.parseDouble(text) ? original : toCanonical.applyAsDouble(displayed);
        if (!Double.isFinite(value)) throw new NumberFormatException("Non-finite limit");
        return value;
    }
}
