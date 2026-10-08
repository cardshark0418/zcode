public class Check {
    public static void main(String[] args) {
        Point a = new Point(1, 2);
        if (!a.equals(new Point(1, 2))) System.exit(1);
        if (a.equals(null)) System.exit(1);
        if (a.equals("no")) System.exit(1);
        if (a.equals(new Point(1, 3))) System.exit(1);
        System.out.println("ok");
    }
}