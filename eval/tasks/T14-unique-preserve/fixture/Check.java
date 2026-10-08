import java.util.*;
public class Check {
    public static void main(String[] args) {
        List<String> got = Unique.keepOrder(Arrays.asList("b", "a", "b", "c", "a"));
        if (!got.equals(Arrays.asList("b", "a", "c"))) {
            System.err.println(got);
            System.exit(1);
        }
        System.out.println("ok");
    }
}