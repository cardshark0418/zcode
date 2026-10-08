public class Check {
    public static void main(String[] args) {
        if (!"pong".equals(Router.handle("GET", "/ping"))) System.exit(1);
        if (!"ok".equals(Router.handle("GET", "/health"))) System.exit(1);
        if (!"404".equals(Router.handle("POST", "/health"))) System.exit(1);
        if (!"404".equals(Router.handle("GET", "/nope"))) System.exit(1);
        System.out.println("ok");
    }
}