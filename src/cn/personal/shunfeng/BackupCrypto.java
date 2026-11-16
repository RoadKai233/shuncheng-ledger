package cn.personal.shunfeng;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class BackupCrypto {
    private static final byte[] MAGIC = "SFBOOK01".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 180000;
    public static final int MAX_BYTES = 12 * 1024 * 1024;
    private BackupCrypto() {}
    private static SecretKey key(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        byte[] raw = null;
        try {
            raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(raw, "AES");
        } finally { spec.clearPassword(); if (raw != null) Arrays.fill(raw, (byte) 0); }
    }
    public static byte[] encrypt(byte[] plain, char[] password) throws Exception {
        if (password.length < 8) throw new IllegalArgumentException("备份密码至少 8 位");
        if (plain.length > MAX_BYTES - 52) throw new IllegalArgumentException("备份数据过大");
        byte[] salt = new byte[16], iv = new byte[12];
        SecureRandom random = new SecureRandom(); random.nextBytes(salt); random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv)); c.updateAAD(MAGIC);
        byte[] encrypted = c.doFinal(plain);
        return ByteBuffer.allocate(36 + encrypted.length).put(MAGIC).put(salt).put(iv).put(encrypted).array();
    }
    public static byte[] decrypt(byte[] blob, char[] password) throws Exception {
        if (blob.length < 52 || blob.length > MAX_BYTES) throw new IllegalArgumentException("不是有效的顺路账本备份");
        ByteBuffer b = ByteBuffer.wrap(blob); byte[] magic = new byte[8], salt = new byte[16], iv = new byte[12];
        b.get(magic); b.get(salt); b.get(iv);
        if (!Arrays.equals(magic, MAGIC)) throw new IllegalArgumentException("请选择 .sfbook 加密备份文件");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv)); c.updateAAD(MAGIC);
        byte[] encrypted = new byte[b.remaining()]; b.get(encrypted);
        return c.doFinal(encrypted);
    }
}
