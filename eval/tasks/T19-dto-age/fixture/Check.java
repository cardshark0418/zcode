public class Check {
    public static void main(String[] args) {
        Person p = new Person("ada", 30);
        String j = p.toJson();
        if (!j.contains("\"name\":\"ada\"") || !j.contains("\"age\":30")) {
            System.err.println(j);
            System.exit(1);
        }
        System.out.println("ok");
    }
}