package cn.personal.shunfeng;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class LedgerRules {
    private LedgerRules() {}
    public static long money(String text, boolean allowZero) {
        if (text == null || !text.trim().matches("[0-9]{1,6}(\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("请输入有效金额，最多两位小数");
        long cents;
        try { cents = new BigDecimal(text.trim()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("金额格式不正确"); }
        checkCents(cents, allowZero);
        return cents;
    }
    public static void checkCents(long value, boolean allowZero) {
        if (value < (allowZero ? 0 : 1) || value > 99999999L)
            throw new IllegalArgumentException(allowZero ? "费用须在 0 到 999999.99 元之间" : "收入须在 0.01 到 999999.99 元之间");
    }
    public static String format(long cents) {
        return BigDecimal.valueOf(cents, 2).setScale(2).toPlainString();
    }
    public static String date(String input) {
        try {
            LocalDate d = LocalDate.parse(input, DateTimeFormatter.ISO_LOCAL_DATE);
            if (!d.toString().equals(input) || d.getYear() < 2000 || d.isAfter(LocalDate.now())) throw new IllegalArgumentException("记账日期须在 2000 年到今天之间");
            return input;
        } catch (DateTimeParseException | NullPointerException e) { throw new IllegalArgumentException("日期格式不正确"); }
    }
    public static String time(String input) {
        if (input == null || !input.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException("时间格式不正确");
        return input;
    }
    public static String text(String value, int max, String label) {
        if (value == null) value = "";
        value = value.trim();
        if (value.length() > max || value.indexOf('\u0000') >= 0) throw new IllegalArgumentException(label + "过长或包含无效字符");
        return value;
    }
    public static String csv(String text) {
        String t = text == null ? "" : text;
        String stripped = t.trim();
        if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) t = "'" + t;
        if (t.startsWith("\t") || t.startsWith("\r") || t.startsWith("\n")) t = "'" + t;
        return "\"" + t.replace("\"", "\"\"") + "\"";
    }
}
