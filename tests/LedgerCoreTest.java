import cn.personal.shunfeng.BackupCrypto;
import cn.personal.shunfeng.LedgerRules;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;

public class LedgerCoreTest {
    private static int assertions = 0;
    interface Operation { void run() throws Exception; }
    private static void eq(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static void yes(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("Condition failed");
    }
    private static void rejects(Class<? extends Exception> type, Operation operation) {
        assertions++;
        try { operation.run(); }
        catch (Exception e) {
            if (type.isInstance(e)) return;
            throw new AssertionError("Wrong failure: " + e, e);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    public static void main(String[] args) throws Exception {
        eq(1L, LedgerRules.money("0.01", false));
        eq(10L, LedgerRules.money("0.1", false));
        eq(30L, LedgerRules.money("0.1", false) + LedgerRules.money("0.2", false));
        eq(7990L, LedgerRules.money("79.90", false));
        eq(100L, LedgerRules.money(" 1.00 ", false));
        eq(99999999L, LedgerRules.money("999999.99", false));
        eq(0L, LedgerRules.money("0", true));
        eq(0L, LedgerRules.money("0.00", true));
        for (String invalid : new String[] {"", " ", "0", "0.00", "-1", "+1", ".50", "1.", "1.001", "1e2", "NaN", "Infinity", "1,000", "1000000", "1000000.00", "9999999999999999", "１", "1\n2"}) {
            rejects(IllegalArgumentException.class, () -> LedgerRules.money(invalid, false));
        }
        rejects(IllegalArgumentException.class, () -> LedgerRules.money(null, true));
        rejects(IllegalArgumentException.class, () -> LedgerRules.checkCents(-1L, true));
        rejects(IllegalArgumentException.class, () -> LedgerRules.checkCents(100000000L, true));
        rejects(IllegalArgumentException.class, () -> LedgerRules.checkCents(Long.MAX_VALUE, false));
        eq("0.00", LedgerRules.format(0));
        eq("0.01", LedgerRules.format(1));
        eq("-1.01", LedgerRules.format(-101));
        eq("999999.99", LedgerRules.format(99999999));
        eq("2024-02-29", LedgerRules.date("2024-02-29"));
        eq("2000-01-01", LedgerRules.date("2000-01-01"));
        eq(LocalDate.now().toString(), LedgerRules.date(LocalDate.now().toString()));
        for (String invalid : new String[] {"2023-02-29", "2024-02-30", "2024-13-01", "2024-00-01", "2024-01-00", "2024-1-01", "1999-12-31", "", " 2024-01-01", LocalDate.now().plusDays(1).toString()}) {
            rejects(IllegalArgumentException.class, () -> LedgerRules.date(invalid));
        }
        rejects(IllegalArgumentException.class, () -> LedgerRules.date(null));
        eq("00:00", LedgerRules.time("00:00"));
        eq("23:59", LedgerRules.time("23:59"));
        for (String invalid : new String[] {"24:00", "23:60", "3:45", "03:4", "-1:00", "12:30:45", ""}) {
            rejects(IllegalArgumentException.class, () -> LedgerRules.time(invalid));
        }
        rejects(IllegalArgumentException.class, () -> LedgerRules.time(null));
        eq("", LedgerRules.text(null, 20, "备注"));
        eq("机场 → 市区", LedgerRules.text(" 机场 → 市区 ", 20, "路线"));
        rejects(IllegalArgumentException.class, () -> LedgerRules.text("abcdef", 5, "备注"));
        rejects(IllegalArgumentException.class, () -> LedgerRules.text("a\u0000b", 5, "备注"));
        eq("\"普通路线\"", LedgerRules.csv("普通路线"));
        eq("\"\"", LedgerRules.csv(null));
        eq("\"a,b\"", LedgerRules.csv("a,b"));
        eq("\"他说\"\"好\"\"\"", LedgerRules.csv("他说\"好\""));
        eq("\"' =1+1\"", LedgerRules.csv(" =1+1"));
        eq("\"'=SUM(A1:A2)\"", LedgerRules.csv("=SUM(A1:A2)"));
        eq("\"'+1+1\"", LedgerRules.csv("+1+1"));
        eq("\"'-1+1\"", LedgerRules.csv("-1+1"));
        eq("\"'@SUM(A1)\"", LedgerRules.csv("@SUM(A1)"));
        eq("\"'\ttext\"", LedgerRules.csv("\ttext"));
        eq("\"'\rtext\"", LedgerRules.csv("\rtext"));
        eq("\"'\ntext\"", LedgerRules.csv("\ntext"));
        eq("\"a\nb\"", LedgerRules.csv("a\nb"));

        char[] password = "测试备份密码-Pass12".toCharArray();
        byte[] plain = "{\"entries\":[{\"route\":\"机场 → 市区\",\"income\":12880}]}".getBytes(StandardCharsets.UTF_8);
        byte[] blob = BackupCrypto.encrypt(plain, password);
        eq(plain.length + 52, blob.length);
        eq("SFBOOK01", new String(Arrays.copyOf(blob, 8), StandardCharsets.US_ASCII));
        yes(Arrays.equals(plain, BackupCrypto.decrypt(blob, password)));
        yes(!Arrays.equals(blob, BackupCrypto.encrypt(plain, password)));
        yes(!new String(blob, StandardCharsets.ISO_8859_1).contains("12880"));
        rejects(AEADBadTagException.class, () -> BackupCrypto.decrypt(blob, "wrong-password".toCharArray()));
        for (int offset : new int[] {8, 24, 36, blob.length - 1}) {
            byte[] corrupt = blob.clone(); corrupt[offset] ^= 1;
            rejects(AEADBadTagException.class, () -> BackupCrypto.decrypt(corrupt, password));
        }
        byte[] badHeader = blob.clone(); badHeader[0] ^= 1;
        rejects(IllegalArgumentException.class, () -> BackupCrypto.decrypt(badHeader, password));
        rejects(IllegalArgumentException.class, () -> BackupCrypto.decrypt(new byte[51], password));
        rejects(IllegalArgumentException.class, () -> BackupCrypto.decrypt(new byte[BackupCrypto.MAX_BYTES + 1], password));
        rejects(IllegalArgumentException.class, () -> BackupCrypto.encrypt(plain, "1234567".toCharArray()));
        rejects(IllegalArgumentException.class, () -> BackupCrypto.encrypt(new byte[BackupCrypto.MAX_BYTES - 51], password));
        byte[] large = new byte[BackupCrypto.MAX_BYTES - 52];
        large[0] = 1; large[large.length - 1] = 2;
        byte[] largeBlob = BackupCrypto.encrypt(large, password);
        eq(BackupCrypto.MAX_BYTES, largeBlob.length);
        yes(Arrays.equals(large, BackupCrypto.decrypt(largeBlob, password)));
        yes(Arrays.equals(new byte[0], BackupCrypto.decrypt(BackupCrypto.encrypt(new byte[0], password), password)));
        Arrays.fill(password, '\u0000');
        System.out.println("PASS: " + assertions + " core checks (money, validation, CSV, encryption and tamper rejection).");
    }
}
