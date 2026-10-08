public class Check {
    public static void main(String[] args) {
        if (!"a".equals(Csv.firstField("a,b,c"))) { System.err.println("1"); System.exit(1); }
        if (!"x".equals(Csv.firstField("x"))) { System.err.println("2"); System.exit(1); }
        if (!"".equals(Csv.firstField(""))) { System.err.println("3"); System.exit(1); }
        System.out.println("ok");
    }
}