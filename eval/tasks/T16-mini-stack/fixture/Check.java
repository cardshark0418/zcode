import java.util.*;
public class Check {
    public static void main(String[] args) {
        MiniStack s = new MiniStack();
        s.push(1); s.push(2);
        if (s.peek() != 2 || s.pop() != 2 || s.pop() != 1 || !s.isEmpty()) System.exit(1);
        try { s.pop(); System.exit(1); } catch (EmptyStackException ok) {}
        try { s.peek(); System.exit(1); } catch (EmptyStackException ok) {}
        System.out.println("ok");
    }
}