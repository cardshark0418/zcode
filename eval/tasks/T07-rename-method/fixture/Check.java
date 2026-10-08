import java.nio.file.*;
public class Check {
    public static void main(String[] args) throws Exception {
        if (!"hello bob".equals(App.run("bob"))) {
            System.err.println("behavior");
            System.exit(1);
        }
        String src = Files.readString(Path.of("App.java"));
        if (src.contains("greetOld")) {
            System.err.println("App.java still references greetOld");
            System.exit(1);
        }
        if (!src.contains("Legacy.greet(")) {
            System.err.println("App.java should call Legacy.greet");
            System.exit(1);
        }
        System.out.println("ok");
    }
}