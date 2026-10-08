import java.util.*;
public class Unique {
    public static List<String> keepOrder(List<String> in) {
        // BUG: uses HashSet then new ArrayList 鈥?order lost / wrong
        return new ArrayList<>(new HashSet<>(in));
    }
}