public class SumRange {
    /** Inclusive sum from lo to hi. */
    public static int sum(int lo, int hi) {
        int s = 0;
        // BUG: should be i <= hi
        for (int i = lo; i < hi; i++) {
            s += i;
        }
        return s;
    }
}