public class Check {
    public static void main(String[] args) {
        if (!Validators.isEmail("a@b.co")) System.exit(1);
        if (Validators.isEmail(null)) System.exit(1);
        if (Validators.isEmail("")) System.exit(1);
        if (Validators.isEmail("a@b")) System.exit(1);
        if (Validators.isEmail("a@@b.co")) System.exit(1);
        if (Validators.isEmail("@b.co")) System.exit(1);
        if (Validators.isEmail("a@")) System.exit(1);
        System.out.println("ok");
    }
}