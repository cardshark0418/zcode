public class Check {
    public static void main(String[] args) {
        Calculator c = new BasicCalculator();
        if (c.add(2, 3) != 5 || c.sub(5, 2) != 3 || c.mul(3, 4) != 12 || c.div(7, 2) != 3) {
            System.err.println("ops failed");
            System.exit(1);
        }
        try {
            c.div(1, 0);
            System.err.println("div0 should throw");
            System.exit(1);
        } catch (ArithmeticException ok) {}
        System.out.println("ok");
    }
}