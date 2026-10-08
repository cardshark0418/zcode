public class Check {
    public static void main(String[] args) {
        String j = JsonUser.toJson("ada");
        if (!j.equals("{\"name\":\"ada\"}")) {
            System.err.println(j);
            System.exit(1);
        }
        System.out.println("ok");
    }
}