public class Check {
    public static void main(String[] args) {
        if (!"a/b".equals(Paths2.join("a", "b"))) System.exit(1);
        if (!"a/b".equals(Paths2.join("a/", "b"))) System.exit(1);
        if (!"a/b".equals(Paths2.join("a", "/b"))) System.exit(1);
        if (!"a/b".equals(Paths2.join("a/", "/b"))) System.exit(1);
        System.out.println("ok");
    }
}