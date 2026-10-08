public class Check {
    public static void main(String[] args) {
        if (!"1".equals(FizzBuzz.at(1))) { System.exit(1); }
        if (!"Fizz".equals(FizzBuzz.at(3))) { System.exit(1); }
        if (!"Buzz".equals(FizzBuzz.at(5))) { System.exit(1); }
        if (!"FizzBuzz".equals(FizzBuzz.at(15))) { System.exit(1); }
        System.out.println("ok");
    }
}