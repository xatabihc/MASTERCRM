package com.mastercrm.offline;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * MCRMBK05: independently authenticated 64 KiB records. Unlike the legacy
 * single-message AES-GCM .mcrm format, decryption has fixed memory use.
 *
 * header = ASCII "MCRMBK05" + salt[16] + noncePrefix[8].
 * each record = plaintextLength[4, big endian] + AES-GCM(plaintext),
 * where the 16-byte tag authenticates the entire header, the record index,
 * and plaintextLength as AAD. Nonce = noncePrefix[8] || recordIndex[4].
 * A final zero-length authenticated record is mandatory, with no trailing data.
 *
 * Records are ordered. A truncated, reordered or corrupted stream is rejected.
 * All records share a PBKDF2-HMAC-SHA256 derived AES-256 key (210k rounds).
 */
public final class StreamBackupCrypto {
    public static final String EXTENSION = ".mcrm5";
    private static final byte[] MAGIC="MCRMBK05".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT=16, PREFIX=8, HEADER=32, TAG=16, CHUNK=65536;
    private static final long MAX_DATA=1024L*1024*1024; // 1 GiB uncompressed ZIP stream
    private static final SecureRandom RNG=new SecureRandom();

    private StreamBackupCrypto(){}

    private static void checkPassword(char[] password) {
        if(password==null || password.length<12 || password.length>1024)
            throw new IllegalArgumentException("Пароль должен быть длиной 12–1024 символа");
    }
    private static SecretKeySpec derive(char[] password,byte[] salt) throws GeneralSecurityException {
        PBEKeySpec kdf=new PBEKeySpec(password,salt,210_000,256);
        byte[] key=null;
        try {
            key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(kdf).getEncoded();
            return new SecretKeySpec(key,"AES");
        } finally {
            kdf.clearPassword();
            if(key!=null)Arrays.fill(key,(byte)0);
        }
    }
    private static byte[] header(InputStream in) throws IOException {
        byte[] bytes=new byte[HEADER];
        readExact(in,bytes,0,bytes.length);
        for(int i=0;i<MAGIC.length;i++)if(bytes[i]!=MAGIC[i])
            throw new IOException("Неверный формат: ожидается защищённая копия .mcrm5");
        return bytes;
    }
    private static void readExact(InputStream in,byte[] dest,int offset,int length)
            throws IOException {
        while(length>0) {
            int n=in.read(dest,offset,length);
            if(n<0)throw new IOException("Архив обрезан или повреждён");
            if(n==0) { int ch=in.read();if(ch<0)throw new IOException("Архив обрезан");dest[offset++]=(byte)ch;length--;continue; }
            offset+=n;length-=n;
        }
    }
    private static byte[] iv(byte[] h,int sequence) {
        byte[] nonce=new byte[12];
        System.arraycopy(h,24,nonce,0,PREFIX);
        nonce[8]=(byte)(sequence>>>24);nonce[9]=(byte)(sequence>>>16);
        nonce[10]=(byte)(sequence>>>8);nonce[11]=(byte)sequence;
        return nonce;
    }
    private static byte[] size(int n) {
        return new byte[]{(byte)(n>>>24),(byte)(n>>>16),(byte)(n>>>8),(byte)n};
    }
    private static Cipher cipher(int mode,SecretKeySpec key,byte[] header,int sequence,byte[] len)
            throws GeneralSecurityException {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode,key,new GCMParameterSpec(128,iv(header,sequence)));
        c.updateAAD(header);
        c.updateAAD(size(sequence));
        c.updateAAD(len);
        return c;
    }

    public static OutputStream encrypt(OutputStream out,char[] password) throws IOException {
        checkPassword(password);
        byte[] h=new byte[HEADER];
        System.arraycopy(MAGIC,0,h,0,MAGIC.length);
        byte[] random=new byte[SALT+PREFIX];
        RNG.nextBytes(random);
        System.arraycopy(random,0,h,MAGIC.length,random.length);
        try {
            SecretKeySpec key=derive(password,Arrays.copyOfRange(h,8,24));
            out.write(h);
            return new Encrypted(out,h,key);
        } catch(GeneralSecurityException e) {throw new IOException("Не удалось создать ключ",e);}
    }

    public static InputStream decrypt(InputStream in,char[] password) throws IOException {
        checkPassword(password);
        byte[] h=header(in);
        try {
            SecretKeySpec key=derive(password,Arrays.copyOfRange(h,8,24));
            return new Decrypted(in,h,key);
        } catch(GeneralSecurityException e) {throw new IOException("Не удалось создать ключ",e);}
    }

    private static final class Encrypted extends OutputStream {
        private final OutputStream out;
        private final byte[] h,buffer=new byte[CHUNK];
        private final SecretKeySpec key;
        private int used=0,sequence=0;
        private long total=0;
        private boolean closed=false;

        Encrypted(OutputStream out,byte[] h,SecretKeySpec key){
            this.out=out;this.h=h;this.key=key;
        }
        private void chunk(int count) throws IOException {
            if(sequence<0)throw new IOException("Слишком много блоков");
            byte[] len=size(count);
            try {
                Cipher c=cipher(Cipher.ENCRYPT_MODE,key,h,sequence,len);
                byte[] ct=c.doFinal(buffer,0,count);
                out.write(len);
                out.write(ct);
                Arrays.fill(ct,(byte)0);
            } catch(GeneralSecurityException e){throw new IOException("Ошибка шифрования",e);}
            sequence++;
        }
        @Override public void write(int value)throws IOException{
            byte[] one={(byte)value};write(one,0,1);
        }
        @Override public void write(byte[] bytes,int off,int length)throws IOException{
            if(closed)throw new IOException("Архив уже закрыт");
            if(bytes==null)throw new NullPointerException();
            if(off<0||length<0||off>bytes.length-length)throw new IndexOutOfBoundsException();
            if(total+length>MAX_DATA)throw new IOException("Превышен лимит потока: 1 ГБ");
            total+=length;
            while(length>0) {
                int n=Math.min(length,CHUNK-used);
                System.arraycopy(bytes,off,buffer,used,n);
                used+=n;off+=n;length-=n;
                if(used==CHUNK){chunk(used);Arrays.fill(buffer,(byte)0);used=0;}
            }
        }
        @Override public void flush()throws IOException{out.flush();}
        @Override public void close()throws IOException{
            if(closed)return;
            try {
                if(used>0)chunk(used);
                Arrays.fill(buffer,(byte)0);used=0;
                // Explicit authenticated end marker; even empty archives have one.
                chunk(0);
                out.flush();
            }finally{closed=true;out.close();}
        }
    }

    private static final class Decrypted extends InputStream {
        private final InputStream in;
        private final byte[] h;
        private final SecretKeySpec key;
        private byte[] clear=new byte[0];
        private int position=0,sequence=0;
        private long total=0;
        private boolean ended=false,closed=false;
        Decrypted(InputStream in,byte[] h,SecretKeySpec key){
            this.in=in;this.h=h;this.key=key;
        }
        private void next()throws IOException{
            if(ended)return;
            if(sequence<0)throw new IOException("Слишком много блоков");
            byte[] len=new byte[4];readExact(in,len,0,4);
            int n=((len[0]&255)<<24)|((len[1]&255)<<16)|((len[2]&255)<<8)|(len[3]&255);
            if(n<0||n>CHUNK)throw new IOException("Недопустимый размер блока");
            if(total+n>MAX_DATA)throw new IOException("Архив слишком большой");
            byte[] encrypted=new byte[n+TAG];
            readExact(in,encrypted,0,encrypted.length);
            try {
                Cipher c=cipher(Cipher.DECRYPT_MODE,key,h,sequence,len);
                clear=c.doFinal(encrypted);
                if(clear.length!=n)throw new IOException("Неверная длина блока");
                total+=n;position=0;
                if(n==0) {
                    ended=true;
                    if(in.read()!=-1)throw new IOException("Лишние данные после конца архива");
                }
            } catch(AEADBadTagException e){
                throw new IOException("Неверный пароль или архив повреждён",e);
            } catch(GeneralSecurityException e){
                throw new IOException("Не удалось расшифровать архив",e);
            } finally {Arrays.fill(encrypted,(byte)0);}
            sequence++;
        }
        @Override public int read()throws IOException {
            byte[] b=new byte[1];return read(b,0,1)<0?-1:(b[0]&255);
        }
        @Override public int read(byte[] out,int off,int len)throws IOException {
            if(closed)throw new IOException("Архив закрыт");
            if(out==null)throw new NullPointerException();
            if(off<0||len<0||off>out.length-len)throw new IndexOutOfBoundsException();
            if(len==0)return 0;
            while(position==clear.length&&!ended) {
                Arrays.fill(clear,(byte)0);next();
            }
            if(ended)return -1;
            int size=Math.min(len,clear.length-position);
            System.arraycopy(clear,position,out,off,size);position+=size;
            return size;
        }
        @Override public void close()throws IOException{
            if(closed)return;closed=true;
            Arrays.fill(clear,(byte)0);
            in.close();
        }
    }
}
