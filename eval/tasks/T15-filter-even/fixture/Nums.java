import java.util.*;
import java.util.stream.*;
public class Nums {
    public static List<Integer> evens(List<Integer> in) {
        // BUG: keeps odds
        return in.stream().filter(n -> n % 2 != 0).collect(Collectors.toList());
    }
}