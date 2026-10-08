public class Check {
    public static void main(String[] args) {
        if (Config.port() != 8080) {
            System.err.println("port=" + Config.port());
            System.exit(1);
        }
        System.out.println("ok");
    }
}