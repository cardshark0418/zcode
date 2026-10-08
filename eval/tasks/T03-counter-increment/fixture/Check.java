public class Check {
    public static void main(String[] args) {
        Counter c = new Counter();
        if (c.increment() != 1 || c.increment() != 2 || c.get() != 2) {
            System.err.println("increment failed");
            System.exit(1);
        }
        System.out.println("ok");
    }
}