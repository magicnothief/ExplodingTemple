package features;

// the bits of net.minecraft.util.Mth the features use, with the same rounding
final class Mth {
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) {
            SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0);
        }
    }

    private Mth() {
    }

    // a lookup table, which the features have to match exactly
    static float sin(float f) {
        return SIN[(int) (f * 10430.378f) & 0xFFFF];
    }

    static int floor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
    }

    static int ceil(float f) {
        int i = (int) f;
        return f > i ? i + 1 : i;
    }

    static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }
}
