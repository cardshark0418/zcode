public class Check {
    public static void main(String[] args) {
        int got = SumRange.sum(1, 3);
        if (got != 6) {
            System.err.println("expected 6 got " + got);
            System.exit(1);
        }
        if (SumRange.sum(5, 5) != 5) {
            System.err.println("sum(5,5) failed");
            System.exit(1);
        }
        System.out.println("ok");
    }
}