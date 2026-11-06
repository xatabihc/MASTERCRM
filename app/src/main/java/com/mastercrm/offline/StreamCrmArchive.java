package com.mastercrm.offline;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Streamed ZIP content for encrypted MCRMBK05 backups.
 *
 * No whole-database JSON is materialized in memory: four NDJSON tables and
 * separate binary JPEG entries. Import uses ONE SQLite transaction. The
 * transaction is committed only after the encrypted stream's final
 * authenticated marker has been verified by reading through EOF.
 */
public final class StreamCrmArchive {
    private static final String[] TABLES={"clients","jobs","payments","expenses"};
    private static final int MAX_LINE=64*1024;
    private static final int MAX_PHOTO=2*1024*1024;
    private static final long MAX_UNCOMPRESSED=512L*1024*1024;
    private static final int MAX_ROWS_PER_TABLE=100000, MAX_PHOTOS=10000;
    private static final Pattern PHOTO=Pattern.compile(
            "photos/([1-9][0-9]*)_([1-9][0-9]*)_(before|after)_([0-9]+)\\.jpg");
    private static final String[] CLIENT_FIELDS={"id","name","phone","note","created_at"};
    private static final String[] JOB_FIELDS={"id","number","client_id","title","description",
            "status","price","due_date","created_at"};
    private static final String[] PAYMENT_FIELDS={"id","job_id","amount","note","created_at"};
    private static final String[] EXPENSE_FIELDS={"id","job_id","amount","category","note","created_at"};
    private StreamCrmArchive(){}

    private static String[] fields(String table) {
        switch(table) {
            case "clients":return CLIENT_FIELDS;
            case "jobs":return JOB_FIELDS;
            case "payments":return PAYMENT_FIELDS;
            case "expenses":return EXPENSE_FIELDS;
            default:throw new IllegalArgumentException("Неизвестная таблица");
        }
    }
    private static boolean numeric(String field){
        return "id".equals(field)||"client_id".equals(field)||"job_id".equals(field)
                ||"price".equals(field)||"amount".equals(field)||"created_at".equals(field);
    }
    private static void entry(ZipOutputStream zip,String name)throws IOException {
        zip.putNextEntry(new ZipEntry(name));
    }
    private static void writeLine(ZipOutputStream zip,String line,Counter limit)throws IOException{
        byte[] bytes=(line+"\n").getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_LINE)throw new IOException("Запись слишком длинная: "+bytes.length);
        limit.add(bytes.length);
        zip.write(bytes);
    }
    public static void write(CrmStore store,OutputStream target)throws IOException {
        SQLiteDatabase db=store.getReadableDatabase();
        Counter limit=new Counter();
        db.beginTransactionNonExclusive();
        try(ZipOutputStream zip=new ZipOutputStream(target,StandardCharsets.UTF_8)) {
            entry(zip,"manifest.json");
            JSONObject manifest=new JSONObject();
            try {
                manifest.put("format","mastercrm-stream");
                manifest.put("schema",2);
                manifest.put("created_at",System.currentTimeMillis());
            } catch(JSONException ex){throw new IOException("Ошибка создания заголовка",ex);}
            writeLine(zip,manifest.toString(),limit);
            zip.closeEntry();

            for(String table:TABLES) {
                entry(zip,table+".ndjson");
                try(Cursor c=db.rawQuery("SELECT * FROM "+table+" ORDER BY id",null)) {
                    if(c.getCount()>MAX_ROWS_PER_TABLE)throw new IOException("Очень много записей в "+table);
                    while(c.moveToNext()) {
                        JSONObject row=new JSONObject();
                        try {
                            for(String key:fields(table)) {
                                int i=c.getColumnIndexOrThrow(key);
                                if(c.isNull(i))row.put(key,JSONObject.NULL);
                                else if(c.getType(i)==Cursor.FIELD_TYPE_INTEGER)row.put(key,c.getLong(i));
                                else row.put(key,c.getString(i));
                            }
                        } catch(JSONException ex){throw new IOException("Ошибка кодирования записи",ex);}
                        writeLine(zip,row.toString(),limit);
                    }
                }
                zip.closeEntry();
            }
            int photos=0;
            try(Cursor c=db.rawQuery(
                    "SELECT id,job_id,kind,image,created_at FROM job_photos ORDER BY id",null)){
                while(c.moveToNext()) {
                    if(++photos>MAX_PHOTOS)throw new IOException("Очень много фотографий");
                    long id=c.getLong(0),jobId=c.getLong(1),when=c.getLong(4);
                    String kind=c.getString(2);
                    byte[] jpeg=c.getBlob(3);
                    checkPhoto(jpeg,kind);
                    entry(zip,"photos/"+id+"_"+jobId+"_"+kind+"_"+when+".jpg");
                    limit.add(jpeg.length);
                    zip.write(jpeg);
                    zip.closeEntry();
                }
            }
            zip.finish();
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    private static final class Counter{
        long bytes=0;
        void add(int n)throws IOException {
            bytes+=n;
            if(bytes>MAX_UNCOMPRESSED)
                throw new IOException("Распакованные данные превышают 512 МБ");
        }
    }
    private static String readLine(ZipInputStream zip,Counter limit)throws IOException {
        ByteArrayOutputStream line=new ByteArrayOutputStream();
        for(;;) {
            int ch=zip.read();
            if(ch==-1) {
                return line.size()==0?null:new String(line.toByteArray(),StandardCharsets.UTF_8);
            }
            limit.add(1);
            if(ch=='\n')return new String(line.toByteArray(),StandardCharsets.UTF_8);
            if(line.size()>=MAX_LINE)
                throw new IOException("Слишком длинная запись в архиве");
            line.write(ch);
        }
    }
    private static byte[] photoBytes(ZipInputStream zip,Counter limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[8192];int n;
        while((n=zip.read(buf))!=-1) {
            limit.add(n);
            if(out.size()+n>MAX_PHOTO)throw new IOException("Фотография больше 2 МБ");
            out.write(buf,0,n);
        }
        return out.toByteArray();
    }
    private static void checkPhoto(byte[] bytes,String kind)throws IOException{
        if(!("before".equals(kind)||"after".equals(kind))
                ||bytes==null||bytes.length<4||bytes.length>MAX_PHOTO
                ||(bytes[0]&255)!=255||(bytes[1]&255)!=216
                ||(bytes[bytes.length-2]&255)!=255||(bytes[bytes.length-1]&255)!=217)
            throw new IOException("Неверный JPEG или тип фотографии");
    }
    private static void validateDb(SQLiteDatabase db)throws IOException{
        String[] checks={
            "SELECT id FROM jobs WHERE price<0 OR trim(title)='' OR trim(number)='' " +
            "OR status NOT IN ('Новый','В работе','Готов','Выдан','Архив') LIMIT 1",
            "SELECT id FROM clients WHERE trim(name)='' LIMIT 1",
            "SELECT id FROM payments WHERE amount=0 LIMIT 1",
            "SELECT id FROM expenses WHERE amount<=0 LIMIT 1",
            "SELECT j.id FROM jobs j WHERE " +
            "COALESCE((SELECT SUM(amount) FROM payments WHERE job_id=j.id),0)<0 " +
            "OR COALESCE((SELECT SUM(amount) FROM payments WHERE job_id=j.id),0)>j.price LIMIT 1",
            "SELECT job_id FROM job_photos GROUP BY job_id,kind HAVING COUNT(*)>10 LIMIT 1"
        };
        for(String q:checks)try(Cursor c=db.rawQuery(q,null)){
            if(c.moveToFirst())throw new IOException("Архив содержит недопустимые данные");
        }
        try(Cursor c=db.rawQuery("PRAGMA foreign_key_check",null)){
            if(c.moveToFirst())throw new IOException("Нарушены связи между записями");
        }
    }

    private static void restoreRows(SQLiteDatabase db,String table,ZipInputStream zip,
                                    Counter limit)throws IOException {
        String line;int count=0;
        while((line=readLine(zip,limit))!=null) {
            if(line.isEmpty())throw new IOException("Пустая строка данных");
            if(++count>MAX_ROWS_PER_TABLE)throw new IOException("Слишком много записей: "+table);
            try{
                JSONObject row=new JSONObject(line);
                ContentValues values=new ContentValues();
                for(String field:fields(table)) {
                    if(!row.has(field))throw new IOException("Не хватает поля "+field);
                    if(row.isNull(field))values.putNull(field);
                    else if(numeric(field))values.put(field,row.getLong(field));
                    else values.put(field,row.getString(field));
                }
                db.insertOrThrow(table,null,values);
            }catch(JSONException | android.database.SQLException ex) {
                throw new IOException("Ошибка записи "+table+" №"+count,ex);
            }
        }
    }
    private static void restorePhoto(SQLiteDatabase db,ZipInputStream zip,Counter limit,
                                     Matcher matcher)throws IOException {
        byte[] jpeg=photoBytes(zip,limit);
        String kind=matcher.group(3);
        checkPhoto(jpeg,kind);
        try {
            ContentValues values=new ContentValues();
            values.put("id",Long.parseLong(matcher.group(1)));
            values.put("job_id",Long.parseLong(matcher.group(2)));
            values.put("kind",kind);
            values.put("image",jpeg);
            values.put("created_at",Long.parseLong(matcher.group(4)));
            db.insertOrThrow("job_photos",null,values);
        }catch(NumberFormatException | android.database.SQLException e){
            throw new IOException("Неверные метаданные фотографии",e);
        }
    }
    /**
     * The authenticated input MUST remain open until the final marker has
     * been read. Wrapping it in a non-closing InputStream prevents ZipInputStream
     * from prematurely ending the authentication check.
     */
    public static void restore(CrmStore store,InputStream authenticated)throws IOException {
        Counter limit=new Counter();
        SQLiteDatabase db=store.getWritableDatabase();
        FilterInputStream nonclosing=new FilterInputStream(authenticated){
            @Override public void close() {}
        };
        boolean manifest=false;
        int tableIndex=0,photoCount=0;
        db.beginTransaction();
        try(ZipInputStream zip=new ZipInputStream(nonclosing,StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();
                if(entry.isDirectory())throw new IOException("Неожиданная папка в архиве");
                if(!manifest) {
                    if(!"manifest.json".equals(name))throw new IOException("Нет заголовка архива");
                    String line=readLine(zip,limit);
                    if(line==null)throw new IOException("Пустой заголовок");
                    try {
                        JSONObject meta=new JSONObject(line);
                        if(!"mastercrm-stream".equals(meta.optString("format"))
                                ||meta.optInt("schema")!=2)
                            throw new IOException("Неизвестная версия резервной копии");
                    }catch(JSONException ex){throw new IOException("Неверный заголовок",ex);}
                    if(readLine(zip,limit)!=null)throw new IOException("Лишние данные заголовка");
                    manifest=true;
                    db.delete("job_photos",null,null);
                    db.delete("payments",null,null);
                    db.delete("expenses",null,null);
                    db.delete("jobs",null,null);
                    db.delete("clients",null,null);
                } else if(tableIndex<TABLES.length) {
                    String table=TABLES[tableIndex++];
                    if(!(table+".ndjson").equals(name))
                        throw new IOException("Неверный порядок таблиц: "+name);
                    restoreRows(db,table,zip,limit);
                } else {
                    Matcher matcher=PHOTO.matcher(name);
                    if(!matcher.matches())throw new IOException("Недопустимый файл: "+name);
                    if(++photoCount>MAX_PHOTOS)throw new IOException("Слишком много фотографий");
                    restorePhoto(db,zip,limit,matcher);
                }
                zip.closeEntry();
            }
            if(!manifest||tableIndex!=TABLES.length)
                throw new IOException("В архиве отсутствуют обязательные таблицы");

            // The ZIP reader may stop at its directory. Drain the underlying
            // authenticated stream through EOF, which MUST validate the final
            // GCM record and reject truncation and unexpected bytes.
            byte[] buffer=new byte[8192];
            while(authenticated.read(buffer)!=-1) { /* authenticate trailer */ }
            validateDb(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
