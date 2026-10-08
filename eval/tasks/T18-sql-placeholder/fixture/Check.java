public class Check {
    public static void main(String[] args) {
        String sql = UserDao.findByIdSql(42);
        if (!sql.toLowerCase().contains("?")) {
            System.err.println("missing placeholder: " + sql);
            System.exit(1);
        }
        if (sql.contains("42")) {
            System.err.println("id still embedded: " + sql);
            System.exit(1);
        }
        System.out.println("ok");
    }
}