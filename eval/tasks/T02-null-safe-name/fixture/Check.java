public class Check {
    public static void main(String[] args) {
        if (!"alice".equals(User.label(new User(" Alice ")))) {
            System.err.println("trim/lower failed");
            System.exit(1);
        }
        if (!"anonymous".equals(User.label(new User(null)))) {
            System.err.println("null name failed");
            System.exit(1);
        }
        if (!"anonymous".equals(User.label(new User("  ")))) {
            System.err.println("blank name failed");
            System.exit(1);
        }
        try {
            User.label(null);
            System.err.println("null user should NPE or treat as anonymous 鈥?prefer anonymous");
        } catch (NullPointerException ignored) {
            // acceptable if agent only fixed name; Check still requires non-null User for anonymous cases above
        }
        // Prefer: null user -> anonymous
        try {
            String s = User.label(null);
            if (!"anonymous".equals(s)) {
                System.err.println("null user expected anonymous");
                System.exit(1);
            }
        } catch (NullPointerException e) {
            System.err.println("null user NPE 鈥?return anonymous instead");
            System.exit(1);
        }
        System.out.println("ok");
    }
}