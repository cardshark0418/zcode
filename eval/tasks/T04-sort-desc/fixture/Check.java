import java.util.*;
public class Check {
    public static void main(String[] args) {
        List<Integer> got = Sorter.desc(Arrays.asList(1, 3, 2));
        if (!got.equals(Arrays.asList(3, 2, 1))) {
            System.err.println("got " + got);
            System.exit(1);
        }
        System.out.println("ok");
    }
}