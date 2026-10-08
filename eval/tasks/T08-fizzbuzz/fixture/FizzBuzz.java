public class FizzBuzz {
    public static String at(int n) {
        // BUG: order / conditions wrong
        if (n % 3 == 0) return "Fizz";
        if (n % 5 == 0) return "Buzz";
        if (n % 15 == 0) return "FizzBuzz";
        return Integer.toString(n);
    }
}