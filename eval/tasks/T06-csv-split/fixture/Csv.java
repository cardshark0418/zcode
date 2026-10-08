public class Csv {
    public static String firstField(String line) {
        if (line == null) return "";
        // BUG: splits on every character-ish 鈥?wrong delimiter use
        String[] parts = line.split(",");
        if (parts.length == 0) return "";
        // BUG: returns last
        return parts[parts.length - 1].trim();
    }
}