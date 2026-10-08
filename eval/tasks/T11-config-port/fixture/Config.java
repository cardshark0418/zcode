public class Config {
    public static int port() {
        return 8000; // BUG: should be 8080
    }
}