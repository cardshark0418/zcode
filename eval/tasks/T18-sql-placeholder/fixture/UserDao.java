public class UserDao {
    public static String findByIdSql(long id) {
        return "select * from users where id = " + id;
    }
}