public class User {
    public final String name;
    public User(String name) { this.name = name; }

    public static String label(User u) {
        // BUG: no null checks
        return u.name.trim().toLowerCase();
    }
}