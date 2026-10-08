public class Point {
    public final int x, y;
    public Point(int x, int y) { this.x = x; this.y = y; }
    @Override public boolean equals(Object o) {
        // BUG: casts without checks
        Point p = (Point) o;
        return p.x == x && p.y == y;
    }
}