package com.mastercrm.offline;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;

public class StreamBackupCryptoTest {
    private static char[] password() { return "Проверочный-пароль-длиннее-12".toCharArray(); }
    private static byte[] encrypt(byte[] bytes) throws IOException {
        ByteArrayOutputStream dest=new ByteArrayOutputStream();
        try(OutputStream encrypted=StreamBackupCrypto.encrypt(dest,password())) {
            encrypted.write(bytes);
        }
        return dest.toByteArray();
    }
    private static byte[] decrypt(byte[] file,char[] pass) throws IOException {
        ByteArrayOutputStream result=new ByteArrayOutputStream();
        try(InputStream source=StreamBackupCrypto.decrypt(new ByteArrayInputStream(file),pass)){
            byte[] buffer=new byte[197];int n;
            while((n=source.read(buffer))!=-1)result.write(buffer,0,n);
        }
        return result.toByteArray();
    }
    @Test public void roundTripsOverManyAuthenticatedRecords() throws Exception {
        byte[] original=new byte[65536*4+123];new Random(145).nextBytes(original);
        byte[] file=encrypt(original);
        assertEquals('M',file[0]);assertEquals('5',file[7]);
        assertArrayEquals(original,decrypt(file,password()));
    }
    @Test public void emptyStreamStillHasAuthenticatedEndRecord() throws Exception {
        assertEquals(0,decrypt(encrypt(new byte[0]),password()).length);
    }
    @Test public void encryptionUsesUniqueSaltAndNonce() throws Exception {
        byte[] original={1,2,3,4};
        byte[] a=encrypt(original),b=encrypt(original);
        assertFalse(Arrays.equals(a,b));
        assertArrayEquals(original,decrypt(a,password()));
        assertArrayEquals(original,decrypt(b,password()));
    }
    @Test public void rejectsWrongPasswordAndTampering() throws Exception {
        byte[] a=encrypt(new byte[75000]);
        try{decrypt(a,"совсем-другой-длинный-пароль".toCharArray());fail();}
        catch(IOException expected){}
        a[10]^=1; // Authenticated salt in header
        try{decrypt(a,password());fail();}
        catch(IOException expected){}
        a=encrypt(new byte[75000]);a[a.length-20]^=1; // Corrupt record tag
        try{decrypt(a,password());fail();}
        catch(IOException expected){}
    }
    @Test public void rejectsTruncatedStreamAndAppendedGarbage()throws Exception{
        byte[] a=encrypt(new byte[100]);
        for(int n=1;n<=22;n++) {
            try{decrypt(Arrays.copyOf(a,a.length-n),password());fail("truncated "+n);}
            catch(IOException expected){}
        }
        byte[] extra=Arrays.copyOf(a,a.length+1);extra[extra.length-1]=99;
        try{decrypt(extra,password());fail();}
        catch(IOException expected){}
    }
    @Test public void rejectsOldBackupAndWrongMagic()throws Exception{
        byte[] invalid=encrypt(new byte[1]);invalid[5]='Q';
        try{decrypt(invalid,password());fail();}
        catch(IOException expected){}
        try{StreamBackupCrypto.encrypt(new ByteArrayOutputStream(),"short".toCharArray());fail();}
        catch(IllegalArgumentException expected){}
    }
}
