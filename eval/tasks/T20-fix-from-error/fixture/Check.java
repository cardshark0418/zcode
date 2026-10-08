public class Check {
    public static void main(String[] args) {
        if (Math.abs(Stats.avg(new int[]{1, 2, 3}) - 2.0) > 1e-9) {
            System.err.println("avg 1,2,3");
            System.exit(1);
        }
        if (Math.abs(Stats.avg(new int[]{1, 2}) - 1.5) > 1e-9) {
            System.err.println("avg 1,2");
            System.exit(1);
        }
        if (Math.abs(Stats.avg(new int[]{}) - 0.0) > 1e-9) {
            System.err.println("empty");
            System.exit(1);
        }
        System.out.println("ok");
    }
}