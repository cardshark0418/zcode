# Generates eval/tasks/T01..T20 fixtures (idempotent overwrite of fixture sources).
$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Tasks = Join-Path $Root "tasks"

function Ensure-Dir([string]$p) {
    if (-not (Test-Path $p)) { New-Item -ItemType Directory -Path $p | Out-Null }
}

function Write-Utf8NoBom([string]$path, [string]$content) {
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [IO.File]::WriteAllText($path, $content, $utf8)
}

function Write-Task {
    param(
        [string]$Id,
        [string]$Slug,
        [string]$Title,
        [string]$Category,
        [string]$Difficulty,
        [string]$Prompt,
        [hashtable]$Files,   # relpath -> content
        [string]$VerifyMain  # e.g. Check.java under fixture/
    )
    $dir = Join-Path $Tasks "$Id-$Slug"
    Ensure-Dir $dir
    Ensure-Dir (Join-Path $dir "fixture")

    Write-Utf8NoBom (Join-Path $dir "task.json") @"
{
  "id": "$Id",
  "slug": "$Slug",
  "title": "$Title",
  "category": "$Category",
  "difficulty": "$Difficulty",
  "language": "java",
  "verify": "verify.ps1"
}
"@

    Write-Utf8NoBom (Join-Path $dir "prompt.md") $Prompt

    Write-Utf8NoBom (Join-Path $dir "verify.ps1") @"
`$ErrorActionPreference = "Continue"
`$here = `$PSScriptRoot
& "`$PSScriptRoot\..\..\lib\Assert-JavaMain.ps1" -WorkDir (Join-Path `$here "fixture") -MainRelPath "$VerifyMain"
exit `$LASTEXITCODE
"@

    foreach ($rel in $Files.Keys) {
        $path = Join-Path $dir "fixture\$rel"
        Ensure-Dir (Split-Path $path -Parent)
        Write-Utf8NoBom $path $Files[$rel]
    }
}

Ensure-Dir $Tasks

# --- T01 off-by-one ---
Write-Task -Id "T01" -Slug "off-by-one" -Title "Fix off-by-one in sumRange" -Category "bugfix" -Difficulty "easy" `
    -Prompt @"
Fix ``SumRange.java`` so ``sum(1,3)`` returns 6 (1+2+3). Do not change ``Check.java``.
Run ``javac``/``java Check`` style verification mentally: Check must pass.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "SumRange.java" = @"
public class SumRange {
    /** Inclusive sum from lo to hi. */
    public static int sum(int lo, int hi) {
        int s = 0;
        // BUG: should be i <= hi
        for (int i = lo; i < hi; i++) {
            s += i;
        }
        return s;
    }
}
"@
        "Check.java" = @"
public class Check {
    public static void main(String[] args) {
        int got = SumRange.sum(1, 3);
        if (got != 6) {
            System.err.println("expected 6 got " + got);
            System.exit(1);
        }
        if (SumRange.sum(5, 5) != 5) {
            System.err.println("sum(5,5) failed");
            System.exit(1);
        }
        System.out.println("ok");
    }
}
"@
    }

# --- T02 NPE ---
Write-Task -Id "T02" -Slug "null-safe-name" -Title "Null-safe display name" -Category "bugfix" -Difficulty "easy" `
    -Prompt @"
``User.label(User)`` throws NPE when name is null. Return ``"anonymous"`` when name is null or blank. Do not change Check.java.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "User.java" = @"
public class User {
    public final String name;
    public User(String name) { this.name = name; }

    public static String label(User u) {
        // BUG: no null checks
        return u.name.trim().toLowerCase();
    }
}
"@
        "Check.java" = @"
public class Check {
    public static void main(String[] args) {
        if (!"alice".equals(User.label(new User(" Alice ")))) {
            System.err.println("trim/lower failed");
            System.exit(1);
        }
        if (!"anonymous".equals(User.label(new User(null)))) {
            System.err.println("null name failed");
            System.exit(1);
        }
        if (!"anonymous".equals(User.label(new User("  ")))) {
            System.err.println("blank name failed");
            System.exit(1);
        }
        try {
            User.label(null);
            System.err.println("null user should NPE or treat as anonymous — prefer anonymous");
        } catch (NullPointerException ignored) {
            // acceptable if agent only fixed name; Check still requires non-null User for anonymous cases above
        }
        // Prefer: null user -> anonymous
        try {
            String s = User.label(null);
            if (!"anonymous".equals(s)) {
                System.err.println("null user expected anonymous");
                System.exit(1);
            }
        } catch (NullPointerException e) {
            System.err.println("null user NPE — return anonymous instead");
            System.exit(1);
        }
        System.out.println("ok");
    }
}
"@
    }

# --- T03 implement method ---
Write-Task -Id "T03" -Slug "counter-increment" -Title "Implement Counter.increment" -Category "feature" -Difficulty "easy" `
    -Prompt @"
``Counter.increment()`` is unimplemented (returns 0). Make it increase the counter by 1 and return the new value. Do not change Check.java.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Counter.java" = @"
public class Counter {
    private int value;
    public int get() { return value; }
    public int increment() {
        // TODO
        return 0;
    }
}
"@
        "Check.java" = @"
public class Check {
    public static void main(String[] args) {
        Counter c = new Counter();
        if (c.increment() != 1 || c.increment() != 2 || c.get() != 2) {
            System.err.println("increment failed");
            System.exit(1);
        }
        System.out.println("ok");
    }
}
"@
    }

# --- T04 comparator ---
Write-Task -Id "T04" -Slug "sort-desc" -Title "Fix descending sort" -Category "bugfix" -Difficulty "easy" `
    -Prompt @"
``Sorter.desc`` should sort integers descending. Current comparator is wrong. Fix Sorter.java only.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Sorter.java" = @"
import java.util.*;
public class Sorter {
    public static List<Integer> desc(List<Integer> in) {
        List<Integer> out = new ArrayList<>(in);
        // BUG: ascending
        out.sort(Integer::compareTo);
        return out;
    }
}
"@
        "Check.java" = @"
import java.util.*;
public class Check {
    public static void main(String[] args) {
        List<Integer> got = Sorter.desc(Arrays.asList(1, 3, 2));
        if (!got.equals(Arrays.asList(3, 2, 1))) {
            System.err.println("got " + got);
            System.exit(1);
        }
        System.out.println("ok");
    }
}
"@
    }

# --- T05 interface ---
Write-Task -Id "T05" -Slug "calculator-impl" -Title "Implement Calculator" -Category "feature" -Difficulty "easy" `
    -Prompt @"
Implement ``BasicCalculator`` for interface ``Calculator`` (add/sub/mul/div). Integer division truncates toward zero. div by 0 should throw ArithmeticException. Do not change Calculator.java or Check.java.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Calculator.java" = @"
public interface Calculator {
    int add(int a, int b);
    int sub(int a, int b);
    int mul(int a, int b);
    int div(int a, int b);
}
"@
        "BasicCalculator.java" = @"
public class BasicCalculator implements Calculator {
    // TODO: implement all methods
    public int add(int a, int b) { return 0; }
    public int sub(int a, int b) { return 0; }
    public int mul(int a, int b) { return 0; }
    public int div(int a, int b) { return 0; }
}
"@
        "Check.java" = @"
public class Check {
    public static void main(String[] args) {
        Calculator c = new BasicCalculator();
        if (c.add(2, 3) != 5 || c.sub(5, 2) != 3 || c.mul(3, 4) != 12 || c.div(7, 2) != 3) {
            System.err.println("ops failed");
            System.exit(1);
        }
        try {
            c.div(1, 0);
            System.err.println("div0 should throw");
            System.exit(1);
        } catch (ArithmeticException ok) {}
        System.out.println("ok");
    }
}
"@
    }

# --- T06 split ---
Write-Task -Id "T06" -Slug "csv-split" -Title "Fix CSV field split" -Category "bugfix" -Difficulty "easy" `
    -Prompt @"
``Csv.firstField`` should return the first comma-separated field. Empty string -> empty. Fix the bug in Csv.java.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Csv.java" = @"
public class Csv {
    public static String firstField(String line) {
        if (line == null) return "";
        // BUG: splits on every character-ish — wrong delimiter use
        String[] parts = line.split(",");
        if (parts.length == 0) return "";
        // BUG: returns last
        return parts[parts.length - 1].trim();
    }
}
"@
        "Check.java" = @"
public class Check {
    public static void main(String[] args) {
        if (!"a".equals(Csv.firstField("a,b,c"))) { System.err.println("1"); System.exit(1); }
        if (!"x".equals(Csv.firstField("x"))) { System.err.println("2"); System.exit(1); }
        if (!"".equals(Csv.firstField(""))) { System.err.println("3"); System.exit(1); }
        System.out.println("ok");
    }
}
"@
    }

# --- T07 rename ---
Write-Task -Id "T07" -Slug "rename-method" -Title "Rename deprecated method usages" -Category "refactor" -Difficulty "medium" `
    -Prompt @"
``Legacy.greetOld`` is deprecated. Update ``App.java`` to call ``Legacy.greet`` instead, with the same arguments. Do not change Check.java. You may leave greetOld in Legacy.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Legacy.java" = @"
public class Legacy {
    @Deprecated
    public static String greetOld(String name) { return "hi " + name; }
    public static String greet(String name) { return "hello " + name; }
}
"@
        "App.java" = @"
public class App {
    public static String run(String name) {
        return Legacy.greetOld(name);
    }
}
"@
        "Check.java" = @"
import java.nio.file.*;
public class Check {
    public static void main(String[] args) throws Exception {
        if (!"hello bob".equals(App.run("bob"))) {
            System.err.println("behavior");
            System.exit(1);
        }
        String src = Files.readString(Path.of("App.java"));
        if (src.contains("greetOld")) {
            System.err.println("App.java still references greetOld");
            System.exit(1);
        }
        if (!src.contains("Legacy.greet(")) {
            System.err.println("App.java should call Legacy.greet");
            System.exit(1);
        }
        System.out.println("ok");
    }
}
"@
    }

# --- T08 fizzbuzz ---
Write-Task -Id "T08" -Slug "fizzbuzz" -Title "Fix FizzBuzz" -Category "bugfix" -Difficulty "easy" `
    -Prompt @"
Fix ``FizzBuzz.at``: multiples of 15 -> FizzBuzz, of 3 -> Fizz, of 5 -> Buzz, else number as string.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "FizzBuzz.java" = @"
public class FizzBuzz {
    public static String at(int n) {
        // BUG: order / conditions wrong
        if (n % 3 == 0) return "Fizz";
        if (n % 5 == 0) return "Buzz";
        if (n % 15 == 0) return "FizzBuzz";
        return Integer.toString(n);
    }
}
"@
        "Check.java" = @"
public class Check {
    public static void main(String[] args) {
        if (!"1".equals(FizzBuzz.at(1))) { System.exit(1); }
        if (!"Fizz".equals(FizzBuzz.at(3))) { System.exit(1); }
        if (!"Buzz".equals(FizzBuzz.at(5))) { System.exit(1); }
        if (!"FizzBuzz".equals(FizzBuzz.at(15))) { System.exit(1); }
        System.out.println("ok");
    }
}
"@
    }

# --- T09 mini router ---
Write-Task -Id "T09" -Slug "mini-router" -Title "Add GET /health route" -Category "feature" -Difficulty "medium" `
    -Prompt @"
Extend ``Router`` so ``handle("GET", "/health")`` returns ``"ok"``. Unknown routes return ``"404"``. Keep existing ``/ping`` behavior.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Router.java" = @"
public class Router {
    public static String handle(String method, String path) {
        if ("GET".equals(method) && "/ping".equals(path)) {
            return "pong";
        }
        return "404";
    }
}
"@
        "Check.java" = @"
public class Check {
    public static void main(String[] args) {
        if (!"pong".equals(Router.handle("GET", "/ping"))) System.exit(1);
        if (!"ok".equals(Router.handle("GET", "/health"))) System.exit(1);
        if (!"404".equals(Router.handle("POST", "/health"))) System.exit(1);
        if (!"404".equals(Router.handle("GET", "/nope"))) System.exit(1);
        System.out.println("ok");
    }
}
"@
    }

# --- T10 json key ---
Write-Task -Id "T10" -Slug "json-key-typo" -Title "Fix JSON key typo in serializer" -Category "bugfix" -Difficulty "easy" `
    -Prompt @"
``JsonUser.toJson`` should emit key ``"name"`` not ``"naem"``. Fix JsonUser.java.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "JsonUser.java" = @"
public class JsonUser {
    public static String toJson(String name) {
        return "{\"naem\":\"" + name + "\"}";
    }
}
"@
        "Check.java" = @"
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
"@
    }

# --- T11 config port ---
Write-Task -Id "T11" -Slug "config-port" -Title "Fix default server port" -Category "bugfix" -Difficulty "easy" `
    -Prompt @"
Application should listen on port 8080 by default. ``Config.port()`` currently returns 8000. Fix it. Do not change Check.java.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Config.java" = @"
public class Config {
    public static int port() {
        return 8000; // BUG: should be 8080
    }
}
"@
        "Check.java" = @"
public class Check {
    public static void main(String[] args) {
        if (Config.port() != 8080) {
            System.err.println("port=" + Config.port());
            System.exit(1);
        }
        System.out.println("ok");
    }
}
"@
    }

# --- T12 email ---
Write-Task -Id "T12" -Slug "email-validate" -Title "Implement simple email check" -Category "feature" -Difficulty "medium" `
    -Prompt @"
Implement ``Validators.isEmail``: must contain exactly one ``@``, non-empty local and domain, domain contains a dot. No regex required. Null -> false.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Validators.java" = @"
public class Validators {
    public static boolean isEmail(String s) {
        return false; // TODO
    }
}
"@
        "Check.java" = @"
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
"@
    }

# --- T13 equals ---
Write-Task -Id "T13" -Slug "equals-null" -Title "Fix Point.equals null-safety" -Category "bugfix" -Difficulty "easy" `
    -Prompt @"
``Point.equals`` should return false for null and non-Point, and compare x/y for Points. Fix NPE on null.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Point.java" = @"
public class Point {
    public final int x, y;
    public Point(int x, int y) { this.x = x; this.y = y; }
    @Override public boolean equals(Object o) {
        // BUG: casts without checks
        Point p = (Point) o;
        return p.x == x && p.y == y;
    }
}
"@
        "Check.java" = @"
public class Check {
    public static void main(String[] args) {
        Point a = new Point(1, 2);
        if (!a.equals(new Point(1, 2))) System.exit(1);
        if (a.equals(null)) System.exit(1);
        if (a.equals("no")) System.exit(1);
        if (a.equals(new Point(1, 3))) System.exit(1);
        System.out.println("ok");
    }
}
"@
    }

# --- T14 dedupe ---
Write-Task -Id "T14" -Slug "unique-preserve" -Title "Deduplicate preserving order" -Category "bugfix" -Difficulty "medium" `
    -Prompt @"
``Unique.keepOrder`` should remove duplicates while preserving first-seen order. Current implementation is wrong.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Unique.java" = @"
import java.util.*;
public class Unique {
    public static List<String> keepOrder(List<String> in) {
        // BUG: uses HashSet then new ArrayList — order lost / wrong
        return new ArrayList<>(new HashSet<>(in));
    }
}
"@
        "Check.java" = @"
import java.util.*;
public class Check {
    public static void main(String[] args) {
        List<String> got = Unique.keepOrder(Arrays.asList("b", "a", "b", "c", "a"));
        if (!got.equals(Arrays.asList("b", "a", "c"))) {
            System.err.println(got);
            System.exit(1);
        }
        System.out.println("ok");
    }
}
"@
    }

# --- T15 filter ---
Write-Task -Id "T15" -Slug "filter-even" -Title "Filter even numbers" -Category "bugfix" -Difficulty "easy" `
    -Prompt @"
``Nums.evens`` should return only even integers, same order. Fix the predicate bug.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Nums.java" = @"
import java.util.*;
import java.util.stream.*;
public class Nums {
    public static List<Integer> evens(List<Integer> in) {
        // BUG: keeps odds
        return in.stream().filter(n -> n % 2 != 0).collect(Collectors.toList());
    }
}
"@
        "Check.java" = @"
import java.util.*;
public class Check {
    public static void main(String[] args) {
        List<Integer> got = Nums.evens(Arrays.asList(1, 2, 3, 4));
        if (!got.equals(Arrays.asList(2, 4))) {
            System.err.println(got);
            System.exit(1);
        }
        System.out.println("ok");
    }
}
"@
    }

# --- T16 stack ---
Write-Task -Id "T16" -Slug "mini-stack" -Title "Implement MiniStack pop/peek" -Category "feature" -Difficulty "medium" `
    -Prompt @"
Finish ``MiniStack``: push/pop/peek/size/isEmpty. pop/peek on empty should throw EmptyStackException.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "MiniStack.java" = @"
import java.util.*;
public class MiniStack {
    private final Deque<Integer> d = new ArrayDeque<>();
    public void push(int v) { d.push(v); }
    public int pop() {
        return 0; // TODO
    }
    public int peek() {
        return 0; // TODO
    }
    public int size() { return d.size(); }
    public boolean isEmpty() { return d.isEmpty(); }
}
"@
        "Check.java" = @"
import java.util.*;
public class Check {
    public static void main(String[] args) {
        MiniStack s = new MiniStack();
        s.push(1); s.push(2);
        if (s.peek() != 2 || s.pop() != 2 || s.pop() != 1 || !s.isEmpty()) System.exit(1);
        try { s.pop(); System.exit(1); } catch (EmptyStackException ok) {}
        try { s.peek(); System.exit(1); } catch (EmptyStackException ok) {}
        System.out.println("ok");
    }
}
"@
    }

# --- T17 path join ---
Write-Task -Id "T17" -Slug "path-join" -Title "Fix path join" -Category "bugfix" -Difficulty "easy" `
    -Prompt @"
``Paths2.join(a,b)`` should join with ``/`` and avoid duplicate slashes when ``a`` ends with ``/`` or ``b`` starts with ``/``.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Paths2.java" = @"
public class Paths2 {
    public static String join(String a, String b) {
        // BUG: naive concat
        return a + "/" + b;
    }
}
"@
        "Check.java" = @"
public class Check {
    public static void main(String[] args) {
        if (!"a/b".equals(Paths2.join("a", "b"))) System.exit(1);
        if (!"a/b".equals(Paths2.join("a/", "b"))) System.exit(1);
        if (!"a/b".equals(Paths2.join("a", "/b"))) System.exit(1);
        if (!"a/b".equals(Paths2.join("a/", "/b"))) System.exit(1);
        System.out.println("ok");
    }
}
"@
    }

# --- T18 sql template ---
Write-Task -Id "T18" -Slug "sql-placeholder" -Title "Use placeholder in SQL template" -Category "bugfix" -Difficulty "medium" `
    -Prompt @"
``UserDao.findByIdSql`` currently concatenates id into SQL (unsafe). Return a SQL string that uses a single ``?`` placeholder instead of embedding the id value. The method signature stays the same for API compat but the returned SQL must NOT contain the numeric id digits from the argument.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "UserDao.java" = @"
public class UserDao {
    public static String findByIdSql(long id) {
        return "select * from users where id = " + id;
    }
}
"@
        "Check.java" = @"
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
"@
    }

# --- T19 DTO ---
Write-Task -Id "T19" -Slug "dto-age" -Title "Add age field to Person DTO" -Category "feature" -Difficulty "easy" `
    -Prompt @"
Add ``age`` (int) to ``Person`` with constructor ``Person(String name, int age)`` and include ``"age":N`` in ``toJson()``. Keep name field. Update any call sites in fixture if needed so Check passes.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Person.java" = @"
public class Person {
    private final String name;
    public Person(String name) { this.name = name; }
    public String toJson() {
        return "{\"name\":\"" + name + "\"}";
    }
}
"@
        "Check.java" = @"
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
"@
    }

# --- T20 stacktrace fix ---
Write-Task -Id "T20" -Slug "fix-from-error" -Title "Fix bug from failing Check" -Category "bugfix" -Difficulty "medium" `
    -Prompt @"
``Stats.avg`` should return the average of ints as double. Empty array -> 0.0. Run the failing Check logic in your head and fix Stats.java. Do not change Check.java.
"@ `
    -VerifyMain "Check.java" `
    -Files @{
        "Stats.java" = @"
public class Stats {
    public static double avg(int[] xs) {
        // BUG: integer division + empty crash
        int s = 0;
        for (int x : xs) s += x;
        return s / xs.length;
    }
}
"@
        "Check.java" = @"
public class Check {
    public static void main(String[] args) {
        if (Math.abs(Stats.avg(new int[]{1, 2, 3}) - 2.0) > 1e-9) {
            System.err.println("avg 1,2,3");
            System.exit(1);
        }
        if (Math.abs(Stats.avg(new int[]{1, 2}) - 1.5) > 1e-9) {
            System.err.println("avg 1,2");
            System.exit(1);
        }
        if (Math.abs(Stats.avg(new int[]{}) - 0.0) > 1e-9) {
            System.err.println("empty");
            System.exit(1);
        }
        System.out.println("ok");
    }
}
"@
    }

Write-Host "Generated tasks under $Tasks"
Get-ChildItem $Tasks -Directory | Select-Object -ExpandProperty Name
