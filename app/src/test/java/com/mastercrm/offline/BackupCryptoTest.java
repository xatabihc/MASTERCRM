package com.mastercrm.offline;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public class BackupCryptoTest {
    private static final String SAMPLE = "{\"client\":\"Клиент: Иван\",\"phone\":\"+7999\",\"amount\":250}";
    private static char[] password(){ return "Очень-Длинный-Пароль_№23".toCharArray(); }

    @Test public void encryptedBackupRoundTripWithUnicode() {
        char[] p=password();
        byte[] encrypted=BackupCrypto.encrypt(SAMPLE,p);
        assertEquals('M',encrypted[0]);
        assertEquals('3',encrypted[7]);
        assertFalse(new String(encrypted,StandardCharsets.ISO_8859_1).contains("Иван"));
        assertEquals(SAMPLE,BackupCrypto.decrypt(encrypted,p));
        Arrays.fill(p,'\0');
    }
    @Test public void encryptionUsesRandomSaltAndNonce() {
        char[] p=password();
        byte[] a=BackupCrypto.encrypt(SAMPLE,p),b=BackupCrypto.encrypt(SAMPLE,p);
        assertFalse(Arrays.equals(a,b));
        assertEquals(SAMPLE,BackupCrypto.decrypt(a,p));
        assertEquals(SAMPLE,BackupCrypto.decrypt(b,p));
        Arrays.fill(p,'\0');
    }
    @Test public void incorrectPasswordCannotDecrypt() {
        byte[] a=BackupCrypto.encrypt(SAMPLE,password());
        try { BackupCrypto.decrypt(a,"a-very-different-password".toCharArray()); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("пароль")); }
    }
    @Test public void tamperingHeaderAndCiphertextIsDetected() {
        byte[] a=BackupCrypto.encrypt(SAMPLE,password());
        a[8] ^= 1; // Change salt but leave version unchanged.
        try { BackupCrypto.decrypt(a,password()); fail(); }
        catch (IllegalArgumentException expected) {}
        a=BackupCrypto.encrypt(SAMPLE,password());
        a[a.length-1] ^= 1;
        try { BackupCrypto.decrypt(a,password()); fail(); }
        catch (IllegalArgumentException expected) {}
    }
    @Test public void wrongFormatAndShortPasswordRejected() {
        try {BackupCrypto.encrypt(SAMPLE,"short".toCharArray());fail();}
        catch(IllegalArgumentException expected){}
        byte[] a=BackupCrypto.encrypt(SAMPLE,password());
        a[0]=(byte)'X';
        try {BackupCrypto.decrypt(a,password());fail();}
        catch(IllegalArgumentException expected){}
    }
    @Test public void malformedPayloadRejectedBeforeDecryption() {
        try {BackupCrypto.decrypt(new byte[10],password());fail();}
        catch(IllegalArgumentException expected){}
    }
}
