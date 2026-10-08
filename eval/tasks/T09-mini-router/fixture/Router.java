public class Router {
    public static String handle(String method, String path) {
        if ("GET".equals(method) && "/ping".equals(path)) {
            return "pong";
        }
        return "404";
    }
}