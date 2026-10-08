import java.util.*;
public class MiniStack {
    private final Deque<Integer> d = new ArrayDeque<>();
    public void push(int v) { d.push(v); }
    public int pop() {
        return 0; // TODO
    }
    public int peek() {
        return 0; // TODO
    }
    public int size() { return d.size(); }
    public boolean isEmpty() { return d.isEmpty(); }
}