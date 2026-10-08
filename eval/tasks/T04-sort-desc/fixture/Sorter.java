import java.util.*;
public class Sorter {
    public static List<Integer> desc(List<Integer> in) {
        List<Integer> out = new ArrayList<>(in);
        // BUG: ascending
        out.sort(Integer::compareTo);
        return out;
    }
}