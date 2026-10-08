public class Stats {
    public static double avg(int[] xs) {
        // BUG: integer division + empty crash
        int s = 0;
        for (int x : xs) s += x;
        return s / xs.length;
    }
}