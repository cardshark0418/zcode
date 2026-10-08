public class Paths2 {
    public static String join(String a, String b) {
        // BUG: naive concat
        return a + "/" + b;
    }
}