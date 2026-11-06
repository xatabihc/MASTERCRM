package com.mastercrm.offline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Versioned portable password-protected backup (MCRMBK03).
 *
 * Wire format: 8-byte magic || 16-byte random salt || 12-byte random nonce
 *              || AES-256-GCM(ciphertext + 16-byte authentication tag).
 * The authenticated AAD includes all header bytes, to detect header tampering.
 * The payload is GZIP-compressed UTF-8 JSON. Password-derived key uses
 * PBKDF2-HMAC-SHA256 with 210,000 iterations; no key or password is stored.
 *
 * Note: This is a bounded in-memory implementation, NOT a streaming archive.
 * Files from unknown sources must be treated as untrusted.
 */
public final class BackupCrypto {
    public static final String EXTENSION = ".mcrm";
    public static final int MAX_JSON_BYTES = 64 * 1024 * 1024;
    public static final int MAX_BACKUP_BYTES = 80 * 1024 * 1024;
    private static final byte[] MAGIC = "MCRMBK03".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int AUTH_TAG_BITS = 128;
    private static final int HEADER_BYTES = MAGIC.length + SALT_BYTES + NONCE_BYTES;
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {}

    private static void checkPassword(char[] password) {
        if (password == null || password.length < 12 || password.length > 1024)
            throw new IllegalArgumentException("Пароль: минимум 12 и максимум 1024 символа");
    }

    private static SecretKeySpec deriveKey(char[] password, byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        byte[] bytes = null;
        try {
            bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(bytes, "AES");
        } finally {
            spec.clearPassword();
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    public static byte[] encrypt(String json, char[] password) {
        checkPassword(password);
        if (json == null) throw new IllegalArgumentException("Нет данных для экспорта");
        byte[] plain = json.getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_JSON_BYTES) {
            Arrays.fill(plain, (byte) 0);
            throw new IllegalArgumentException("Слишком большой архив. Лимит: 64 МБ JSON");
        }
        byte[] zipped = null;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream zip = new GZIPOutputStream(buffer)) {
                zip.write(plain);
            }
            zipped = buffer.toByteArray();
            byte[] header = new byte[HEADER_BYTES];
            System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
            byte[] salt = new byte[SALT_BYTES];
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(nonce);
            System.arraycopy(salt, 0, header, MAGIC.length, SALT_BYTES);
            System.arraycopy(nonce, 0, header, MAGIC.length + SALT_BYTES, NONCE_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt),
                    new GCMParameterSpec(AUTH_TAG_BITS, nonce));
            cipher.updateAAD(header);
            byte[] encrypted = cipher.doFinal(zipped);
            if (encrypted.length + HEADER_BYTES > MAX_BACKUP_BYTES)
                throw new IllegalArgumentException("Размер зашифрованной копии превышает 80 МБ");
            byte[] result = new byte[HEADER_BYTES + encrypted.length];
            System.arraycopy(header, 0, result, 0, HEADER_BYTES);
            System.arraycopy(encrypted, 0, result, HEADER_BYTES, encrypted.length);
            Arrays.fill(encrypted, (byte) 0);
            return result;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Не удалось зашифровать копию", e);
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (zipped != null) Arrays.fill(zipped, (byte) 0);
        }
    }

    public static String decrypt(byte[] file, char[] password) {
        checkPassword(password);
        if (file == null || file.length < HEADER_BYTES + 16 ||
                file.length > MAX_BACKUP_BYTES)
            throw new IllegalArgumentException("Неверный формат или размер копии");
        for (int i = 0; i < MAGIC.length; i++)
            if (file[i] != MAGIC[i])
                throw new IllegalArgumentException("Неизвестная версия защищённой копии");
        byte[] header = Arrays.copyOf(file, HEADER_BYTES);
        byte[] salt = Arrays.copyOfRange(file, MAGIC.length, MAGIC.length + SALT_BYTES);
        byte[] nonce = Arrays.copyOfRange(file, MAGIC.length + SALT_BYTES, HEADER_BYTES);
        byte[] zipped = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt),
                    new GCMParameterSpec(AUTH_TAG_BITS, nonce));
            cipher.updateAAD(header);
            zipped = cipher.doFinal(file, HEADER_BYTES, file.length - HEADER_BYTES);
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(zipped));
                 ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = gzip.read(buffer)) != -1) {
                    if (result.size() + count > MAX_JSON_BYTES)
                        throw new IllegalArgumentException("Размер распакованных данных превышает 64 МБ");
                    result.write(buffer, 0, count);
                }
                byte[] json = result.toByteArray();
                try {
                    return new String(json, StandardCharsets.UTF_8);
                } finally {
                    Arrays.fill(json, (byte) 0);
                }
            }
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("Неверный пароль или повреждена копия");
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("Повреждённый или неподдерживаемый файл копии");
        } finally {
            if (zipped != null) Arrays.fill(zipped, (byte) 0);
        }
    }
}
