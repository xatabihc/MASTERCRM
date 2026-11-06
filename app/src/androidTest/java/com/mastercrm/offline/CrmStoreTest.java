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
        assertEquals(40000,db.summary().totalDebt);
        assertEquals(2,db.jobs("Ремонт","",0).size());
        assertEquals(2,db.jobsForClient(c).size());
    }
    @Test public void archivesPaidDeliveredJobAndReopensAfterRefund() {
        long id=job(client("Архив"),9000);
        CrmStore.Job delivered=db.job(id);
        delivered.status="Выдан";
        db.saveJob(delivered);
        assertEquals("Выдан",db.job(id).status);
        db.addPayment(id,9000,"Оплачено");
        assertEquals("Архив",db.job(id).status);
        assertEquals(0,db.summary().totalDebt);
        db.addPayment(id,-1500,"Частичный возврат");
        assertEquals("Выдан",db.job(id).status);
        assertEquals(1500,db.summary().totalDebt);
        db.addPayment(id,1500,"Повторная оплата");
        assertEquals("Архив",db.job(id).status);
    }
    @Test public void financialChartContainsActualCurrentMonthData() {
        long id=job(client("Статистика"),12000);
        db.addPayment(id,8000,"В кассу");
        db.addExpense(id,2700,"Материалы","");
        List<CrmStore.MonthMetrics> metrics=db.monthlyMetrics();
        assertEquals(6,metrics.size());
        CrmStore.MonthMetrics current=metrics.get(5);
        assertEquals(8000,current.income);
        assertEquals(2700,current.expenses);
        assertEquals(5300,current.profit());
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
    @Test public void protectedBackupRoundTripOnAndroidSQLite() throws Exception {
        long id=job(client("Защищённый клиент"),26750);
        db.addPayment(id,17500,"Аванс");
        byte[] jpeg={(byte)0xff,(byte)0xd8,0x01,0x02,(byte)0xff,(byte)0xd9};
        db.addPhoto(id,"after",jpeg);
        String oldJson=db.exportJson();
        char[] password="Тестовый-пароль-длина-24".toCharArray();
        byte[] encrypted=BackupCrypto.encrypt(oldJson,password);
        assertEquals(oldJson,BackupCrypto.decrypt(encrypted,password));
        long extra=job(client("Временный"),1000);
        assertNotNull(db.job(extra));
        try {
            BackupCrypto.decrypt(encrypted,"очень-другой-пароль".toCharArray());
            fail("Wrong password must not be accepted");
        } catch(IllegalArgumentException expected) {}
        assertNotNull(db.job(extra));
        db.restoreJson(BackupCrypto.decrypt(encrypted,password));
        assertEquals(1,db.jobs("","",0).size());
        assertEquals(17500,db.job(id).paid);
        assertArrayEquals(jpeg,db.photos(id).get(0).jpeg);
        java.util.Arrays.fill(password,'\0');
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
    @Test public void rejectsOverpaymentInBackupWithoutChangingOldData() throws Exception {
        long id=job(client("Оригинал"),10000);
        db.addPayment(id,3000,"Аванс");
        String original=db.exportJson();
        JSONObject forged=new JSONObject(original);
        forged.getJSONArray("payments").getJSONObject(0).put("amount",10001);
        try {
            db.restoreJson(forged.toString());
            fail("Overpayment in imported backup must be rejected");
        } catch(IllegalArgumentException expected) {}
        assertEquals(3000,db.job(id).paid);
        assertEquals(7000,db.summary().totalDebt);
        assertEquals(1,db.clients("",0).size());
    }

    @Test public void rejectsNegativeNetPaymentInBackupWithoutChangingOldData() throws Exception {
        long id=job(client("Сохранить оплату"),10000);
        db.addPayment(id,3200,"Платёж");
        JSONObject forged=new JSONObject(db.exportJson());
        forged.getJSONArray("payments").getJSONObject(0).put("amount",-400);
        try {
            db.restoreJson(forged.toString());
            fail("Negative payment total must not be accepted");
        } catch(IllegalArgumentException expected) {}
        assertEquals(3200,db.job(id).paid);
    }

    @Test public void invalidStatusAndBlankNameCannotReplaceCurrentData() throws Exception {
        long id=job(client("Настоящий клиент"),1200);
        JSONObject forged=new JSONObject(db.exportJson());
        forged.getJSONArray("jobs").getJSONObject(0).put("status","Несуществующий статус");
        try { db.restoreJson(forged.toString()); fail("status"); }
        catch(IllegalArgumentException expected) {}
        assertEquals("Новый",db.job(id).status);

        forged=new JSONObject(db.exportJson());
        forged.getJSONArray("clients").getJSONObject(0).put("name"," ");
        try { db.restoreJson(forged.toString()); fail("blank name"); }
        catch(IllegalArgumentException expected) {}
        assertEquals("Настоящий клиент",db.client(db.job(id).clientId).name);
    }

    @Test public void searchingAndPagingWorkWithMoreThanOneThousandClients() {
        for(int i=0;i<1005;i++) {
            CrmStore.Client client=new CrmStore.Client();
            client.name="Покупатель " + i;
            client.phone="+7900" + String.format(java.util.Locale.ROOT,"%07d",i);
            db.saveClient(client);
        }
        assertEquals(1005,db.summary().totalClients);
        assertEquals(100,db.clients("",0).size());
        assertEquals(100,db.clients("",9).size());
        assertEquals(5,db.clients("",10).size());
        assertEquals(1,db.clients("Покупатель 1004",0).size());
        assertEquals(1,db.clients("0001004",0).size());
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
