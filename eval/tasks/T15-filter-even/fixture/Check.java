import java.util.*;
public class Check {
    public static void main(String[] args) {
        List<Integer> got = Nums.evens(Arrays.asList(1, 2, 3, 4));
        if (!got.equals(Arrays.asList(2, 4))) {
            System.err.println(got);
            System.exit(1);
        }
        System.out.println("ok");
    }
}