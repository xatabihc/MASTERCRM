package com.mastercrm.offline;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration tests are intended for a disposable CI emulator.
 * They delete the preview database and MUST NOT be executed on a user's real data.
 */
@RunWith(AndroidJUnit4.class)
public class CrmStoreTest {
    private Context context;
    private CrmStore db;

    @Before public void setup() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(CrmStore.DATABASE_NAME);
        db=new CrmStore(context);
    }
    @After public void tearDown() {
        db.close();
        context.deleteDatabase(CrmStore.DATABASE_NAME);
    }

    private long client(String name) {
        CrmStore.Client c=new CrmStore.Client();
        c.name=name;c.phone="+79990000000";
        return db.saveClient(c);
    }
    private long job(long clientId,long kopecks) {
        CrmStore.Job j=new CrmStore.Job();
        j.clientId=clientId;j.title="Ремонт";j.price=kopecks;
        return db.saveJob(j);
    }

    @Test public void createsJobsPaysRefundsAndDuplicatesWithoutDoubleCounting() {
        long c=client("Тестовый клиент");
        long id=job(c,30000);
        assertEquals(30000,db.job(id).price);
        assertTrue(db.job(id).number.startsWith("MC-"));
        db.addPayment(id,10000,"Аванс");
        db.addPayment(id,15000,"Оплата");
        assertEquals(25000,db.job(id).paid);
        db.addPayment(id,-5000,"Возврат");
        assertEquals(20000,db.job(id).paid);
        assertEquals(10000,db.summary().totalDebt);
        db.addExpense(id,3000,"Материалы","Детали");
        assertEquals(3000,db.summary().totalExpense);
        assertEquals(17000,db.summary().totalIncome-db.summary().totalExpense);
        long duplicate=db.duplicateJob(id);
        assertNotEquals(id,duplicate);
        assertEquals(0,db.job(duplicate).paid);
        assertEquals(30000,db.job(duplicate).price);
        assertEquals("Новый",db.job(duplicate).status);
        assertEquals(50000,db.summary().totalDebt);
        assertEquals(2,db.jobs("Ремонт","",0).size());
        assertEquals(2,db.jobsForClient(c).size());
    }
    @Test public void refusesOverpaymentAndOverrefund() {
        long id=job(client("Контроль"),10000);
        try {db.addPayment(id,10001,"");fail("overpay");}
        catch(IllegalArgumentException expected){}
        db.addPayment(id,2500,"");
        try {db.addPayment(id,-2501,"");fail("overrefund");}
        catch(IllegalArgumentException expected){}
        assertEquals(2500,db.job(id).paid);
    }
    @Test public void backupIncludesPhotosAndRestoresAtomically() throws Exception {
        long id=job(client("Фото"),19000);
        byte[] tinyJpeg={(byte)0xff,(byte)0xd8,0x01,0x02,(byte)0xff,(byte)0xd9};
        db.addPhoto(id,"before",tinyJpeg);
        db.addPayment(id,1000,"");
        String json=db.exportJson();
        JSONObject root=new JSONObject(json);
        assertEquals(2,root.getInt("schema"));
        assertEquals(1,root.getJSONArray("job_photos").length());
        long other=job(client("Лишний"),100);
        assertNotNull(db.job(other));
        db.restoreJson(json);
        assertEquals(1,db.jobs("","",0).size());
        assertEquals(1,db.photos(id).size());
        assertArrayEquals(tinyJpeg,db.photos(id).get(0).jpeg);
        assertEquals(1000,db.job(id).paid);
    }
    @Test public void malformedPhotoBackupDoesNotWipeExistingData() throws Exception {
        long id=job(client("Сохранить"),7800);
        String json=db.exportJson();
        JSONObject root=new JSONObject(json);
        JSONObject bogus=new JSONObject();
        bogus.put("id",42);bogus.put("job_id",id);bogus.put("kind","before");
        bogus.put("image","AAAA");bogus.put("created_at",123L);
        root.getJSONArray("job_photos").put(bogus);
        try {db.restoreJson(root.toString());fail("invalid image");}
        catch(IllegalArgumentException expected){}
        assertNotNull(db.job(id));
        assertEquals(1,db.clients("",0).size());
    }
    @Test public void canImportPreviousSchemaOneBackup() throws Exception {
        long id=job(client("Старая версия"),2500);
        JSONObject old=new JSONObject(db.exportJson());
        old.put("schema",1);old.remove("job_photos");
        db.restoreJson(old.toString());
        assertNotNull(db.job(id));
        assertEquals(1,db.clients("",0).size());
    }
    @Test public void migratesFromSchemaOneWithoutDroppingCustomers() {
        long id=job(client("Миграция"),1234);
        SQLiteDatabase writable=db.getWritableDatabase();
        writable.execSQL("DROP TABLE job_photos");
        writable.setVersion(1);
        db.close();
        db=new CrmStore(context);
        assertEquals(2,db.getReadableDatabase().getVersion());
        assertNotNull(db.job(id));
        assertEquals(0,db.photos(id).size());
    }
}
