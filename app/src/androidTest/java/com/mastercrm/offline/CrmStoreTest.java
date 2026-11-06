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

    private byte[] streamedBackup(char[] password)throws Exception {
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        try(java.io.OutputStream secure=StreamBackupCrypto.encrypt(output,password)){
            StreamCrmArchive.write(db,secure);
        }
        return output.toByteArray();
    }

    @Test public void verifiesValidProtectedBackupWithoutReplacingCurrentDatabase()throws Exception {
        long backedUpJob=job(client("Внутри копии"),27000);
        db.addPayment(backedUpJob,12000,"Аванс");
        byte[] photo={(byte)0xff,(byte)0xd8,12,13,(byte)0xff,(byte)0xd9};
        db.addPhoto(backedUpJob,"before",photo);
        char[] pass="Проверочный пароль резервной копии".toCharArray();
        byte[] encrypted=streamedBackup(pass);

        // These records exist only in the current database, not the archive.
        long currentJob=job(client("Новая запись после копии"),45000);
        db.addPayment(currentJob,10000,"Новый платёж");
        long currentIncome=db.summary().totalIncome;
        long currentDebt=db.summary().totalDebt;

        StreamCrmArchive.VerificationResult verified;
        try(java.io.InputStream input=StreamBackupCrypto.decrypt(
                new java.io.ByteArrayInputStream(encrypted),pass)) {
            verified=StreamCrmArchive.verify(db,input);
        }
        assertEquals(1,verified.clients);
        assertEquals(1,verified.jobs);
        assertEquals(1,verified.payments);
        assertEquals(0,verified.expenses);
        assertEquals(1,verified.photos);

        // A successful trial import must be rolled back, including photos and money.
        assertNotNull(db.job(currentJob));
        assertEquals(10000,db.job(currentJob).paid);
        assertEquals(2,db.summary().totalClients);
        assertEquals(currentIncome,db.summary().totalIncome);
        assertEquals(currentDebt,db.summary().totalDebt);
        assertEquals(1,db.photos(backedUpJob).size());
        java.util.Arrays.fill(pass,'\0');
    }

    @Test public void invalidBackupVerificationRollsBackAndPreservesDatabase()throws Exception {
        long jobId=job(client("Не потерять при проверке"),12000);
        db.addPayment(jobId,2500,"");
        char[] pass="Пароль проверки повреждённой копии".toCharArray();
        byte[] encrypted=streamedBackup(pass);
        // Removes the authenticated end record, detected only after reading ZIP.
        byte[] truncated=java.util.Arrays.copyOf(encrypted,encrypted.length-1);
        try(java.io.InputStream input=StreamBackupCrypto.decrypt(
                new java.io.ByteArrayInputStream(truncated),pass)) {
            StreamCrmArchive.verify(db,input);
            fail("Truncated backup should fail verification");
        }catch(java.io.IOException expected){}
        assertEquals(1,db.summary().totalClients);
        assertEquals(2500,db.job(jobId).paid);
        assertEquals(9500,db.summary().totalDebt);
        java.util.Arrays.fill(pass,'\0');
    }

    @Test public void largePhotoArchiveRestoresDozensOfPhotosAndAllPayments() throws Exception {
        long clientId=client("Много фотографий");
        byte[] photo=new byte[128*1024];
        char[] pass="Сложный пароль для фотоархива 1.0".toCharArray();
        java.util.Random random=new java.util.Random(712);
        int count=0;
        for(int i=0;i<4;i++) {
            long id=job(clientId,50000+i*10000);
            db.addPayment(id,12000,"Частичная оплата");
            for(int n=0;n<9;n++) {
                random.nextBytes(photo);
                photo[0]=(byte)0xff;photo[1]=(byte)0xd8;
                photo[photo.length-2]=(byte)0xff;photo[photo.length-1]=(byte)0xd9;
                db.addPhoto(id,"before",photo);
                count++;
            }
        }
        byte[] archive=streamedBackup(pass);
        assertTrue("Photo archive should be several megabytes",archive.length>1024*1024);
        long outside=job(client("Временный клиент"),1000);
        assertNotNull(db.job(outside));
        try(java.io.InputStream input=StreamBackupCrypto.decrypt(
                new java.io.ByteArrayInputStream(archive),pass)) {
            StreamCrmArchive.VerificationResult result=StreamCrmArchive.verify(db,input);
            assertEquals(36,result.photos);
            assertEquals(4,result.payments);
        }
        assertNotNull("Dry-run verification must preserve live data",db.job(outside));
        try(java.io.InputStream input=StreamBackupCrypto.decrypt(
                new java.io.ByteArrayInputStream(archive),pass)) {
            StreamCrmArchive.restore(db,input);
        }
        assertNull(db.job(outside));
        assertEquals(count,36);
        assertEquals(4,db.jobs("","",0).size());
        long totalPhotos=0;
        for(CrmStore.Job j:db.jobs("","",0)) {
            assertEquals(12000,j.paid);
            totalPhotos+=db.photos(j.id).size();
        }
        assertEquals(36,totalPhotos);
        java.util.Arrays.fill(pass,'\0');
    }

    @Test public void streamedZipRoundTripRestoresClientsJobsPaymentsAndPhotos()throws Exception {
        long client=client("Потоковая копия 500");
        long id=job(client,99000);
        db.addPayment(id,71000,"Аванс");
        db.addExpense(id,1000,"Материалы","Ремонт");
        byte[] image={(byte)0xff,(byte)0xd8,12,13,14,(byte)0xff,(byte)0xd9};
        db.addPhoto(id,"before",image);
        db.addPhoto(id,"after",image);
        char[] password="Пароль-для-потокового-архива".toCharArray();
        byte[] archive=streamedBackup(password);
        assertTrue(archive.length>100);
        long extra=job(client("Временная запись"),1500);
        assertNotNull(db.job(extra));
        try(java.io.InputStream clear=StreamBackupCrypto.decrypt(
                new java.io.ByteArrayInputStream(archive),password)) {
            StreamCrmArchive.restore(db,clear);
        }
        assertEquals(1,db.summary().totalClients);
        assertEquals(1,db.jobs("","",0).size());
        assertEquals(71000,db.job(id).paid);
        assertEquals(1000,db.job(id).cost);
        assertEquals(2,db.photos(id).size());
        assertArrayEquals(image,db.photos(id).get(0).jpeg);
        java.util.Arrays.fill(password,'\0');
    }

    @Test public void streamedArchiveWrongPasswordAndTruncationCannotReplaceDatabase()throws Exception {
        long id=job(client("Сохранить после ошибки"),34500);
        db.addPayment(id,5000,"");
        char[] password="Проверочный-пароль-потоковой-копии".toCharArray();
        byte[] archive=streamedBackup(password);
        try(java.io.InputStream clear=StreamBackupCrypto.decrypt(
                new java.io.ByteArrayInputStream(archive),
                "неправильный-длинный-пароль".toCharArray())) {
            StreamCrmArchive.restore(db,clear);
            fail("Wrong password accepted");
        }catch(java.io.IOException expected){}
        assertEquals(5000,db.job(id).paid);
        byte[] truncated=java.util.Arrays.copyOf(archive,archive.length-8);
        try(java.io.InputStream clear=StreamBackupCrypto.decrypt(
                new java.io.ByteArrayInputStream(truncated),password)) {
            StreamCrmArchive.restore(db,clear);
            fail("Truncated archive accepted");
        }catch(java.io.IOException expected){}
        assertEquals(5000,db.job(id).paid);
        assertEquals(1,db.summary().totalClients);
    }

    @Test public void streamedArchiveSupportsThousandsOfClients()throws Exception {
        for(int i=0;i<1102;i++){
            CrmStore.Client c=new CrmStore.Client();
            c.name="Клиент потока "+i;
            db.saveClient(c);
        }
        char[] password="Длинный-потоковый-пароль-01234".toCharArray();
        byte[] file=streamedBackup(password);
        try(java.io.InputStream clear=StreamBackupCrypto.decrypt(
                new java.io.ByteArrayInputStream(file),password)) {
            StreamCrmArchive.restore(db,clear);
        }
        assertEquals(1102,db.summary().totalClients);
        assertEquals(100,db.clients("",0).size());
        assertEquals(2,db.clients("",11).size());
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
        assertEquals(100,db.clientOverviews("",0,false).size());
        assertEquals(100,db.clients("",9).size());
        assertEquals(5,db.clients("",10).size());
        assertEquals(1,db.clients("Покупатель 1004",0).size());
        assertEquals(1,db.clients("0001004",0).size());
    }

    @Test public void repeatClientsAndIndividualBalancesAreCalculated() {
        long regular=client("Постоянный клиент");
        long occasional=client("Первый визит");
        long j1=job(regular,22000);
        long j2=job(regular,30000);
        job(occasional,5000);
        db.addPayment(j1,20000,"Аванс");
        db.addPayment(j2,30000,"Полная оплата");

        CrmStore.ClientMetrics stats=db.clientMetrics(regular);
        assertEquals(2,stats.jobCount);
        assertEquals(50000,stats.paid);
        assertEquals(2000,stats.debt);
        assertTrue(stats.regular());
        assertFalse(db.clientMetrics(occasional).regular());

        java.util.List<CrmStore.Client> repeat=db.clients("",0,true);
        assertEquals(1,repeat.size());
        assertEquals(regular,repeat.get(0).id);
        assertEquals(1,db.clients("Постоянный",0,true).size());
        assertEquals(0,db.clients("Первый визит",0,true).size());
        java.util.List<CrmStore.ClientOverview> overview=db.clientOverviews("",0,true);
        assertEquals(1,overview.size());
        assertEquals(regular,overview.get(0).id);
        assertEquals(2,overview.get(0).jobCount);
        assertEquals(50000,overview.get(0).paid);
        assertEquals(2000,overview.get(0).debt);
        assertTrue(overview.get(0).regular());
    }

    @Test public void overdueReceivablesShowLargestUnpaidOrdersFirst() {
        long clientId=client("Тест долгов");
        long small=job(clientId,10000),large=job(clientId,50000);
        long paid=job(clientId,40000);
        db.addPayment(small,2000,"");
        db.addPayment(large,1000,"");
        db.addPayment(paid,40000,"");
        java.util.List<CrmStore.Job> owed=db.debtors(10);
        assertEquals(2,owed.size());
        assertEquals(large,owed.get(0).id);
        assertEquals(49000,Money.outstanding(owed.get(0).price,owed.get(0).paid));
        assertEquals(small,owed.get(1).id);
        assertEquals(1,db.debtors(1).size());
    }

    @Test public void dayWeekMonthAndAllCashFlowsReflectRealPaymentsAndRefunds() {
        long id=job(client("Периоды"),18000);
        db.addPayment(id,12000,"Получено");
        db.addPayment(id,-2000,"Возврат");
        db.addExpense(id,3000,"Материалы","");
        for(String period:new String[]{"day","week","month","all"}) {
            CrmStore.PeriodMetrics m=db.periodMetrics(period);
            assertEquals("income "+period,10000,m.income);
            assertEquals("expenses "+period,3000,m.expense);
            assertEquals("profit "+period,7000,m.cashProfit());
            assertEquals("orders "+period,1,m.ordersCreated);
        }
        try {db.periodMetrics("future");fail("unknown period");}
        catch(IllegalArgumentException expected){}
    }

    @Test public void olderMovementsExcludedFromCurrentCashPeriod() {
        long id=job(client("Прошлый месяц"),9000);
        long payment=db.addPayment(id,4500,"");
        long expense=db.addExpense(id,1500,"Запчасти","");
        long old=java.time.LocalDate.now().minusMonths(2)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        android.content.ContentValues time=new android.content.ContentValues();
        time.put("created_at",old);
        db.getWritableDatabase().update("payments",time,"id=?",new String[]{""+payment});
        db.getWritableDatabase().update("expenses",time,"id=?",new String[]{""+expense});
        db.getWritableDatabase().update("jobs",time,"id=?",new String[]{""+id});
        CrmStore.PeriodMetrics month=db.periodMetrics("month");
        assertEquals(0,month.income);
        assertEquals(0,month.expense);
        assertEquals(0,month.ordersCreated);
        CrmStore.PeriodMetrics all=db.periodMetrics("all");
        assertEquals(4500,all.income);
        assertEquals(1500,all.expense);
        assertEquals(1,all.ordersCreated);
        assertEquals(4500,db.summary().totalIncome);
    }

    @Test public void overdueOrdersExcludeClosedAndUndatedJobs() {
        long clientId=client("Сроки");
        String today=CrmStore.today();
        String yesterday=java.time.LocalDate.now().minusDays(1).toString();
        String tomorrow=java.time.LocalDate.now().plusDays(1).toString();
        long overdue=job(clientId,20000);
        long dueToday=job(clientId,21000);
        long soon=job(clientId,22000);
        long undated=job(clientId,23000);
        long issued=job(clientId,24000);
        setDeadline(overdue,yesterday,"Новый");
        setDeadline(dueToday,today,"В работе");
        setDeadline(soon,tomorrow,"Новый");
        setDeadline(issued,yesterday,"Выдан");
        assertEquals(2,db.overdueJobs(0).size());
        assertEquals(overdue,db.overdueJobs(0).get(0).id);
        assertEquals(dueToday,db.overdueJobs(0).get(1).id);
        assertEquals(2,db.summary().dueJobs);
        assertEquals(1,db.upcomingJobs(7,50).size());
        assertEquals(soon,db.upcomingJobs(7,50).get(0).id);
        assertNotNull(db.job(undated));
    }

    @Test public void overdueAndUpcomingRespectDateBoundaries() {
        long clientId=client("Границы срока");
        long day7=job(clientId,10000);
        long day8=job(clientId,10000);
        long yesterday=job(clientId,10000);
        setDeadline(day7,java.time.LocalDate.now().plusDays(7).toString(),"Готов");
        setDeadline(day8,java.time.LocalDate.now().plusDays(8).toString(),"Готов");
        setDeadline(yesterday,java.time.LocalDate.now().minusDays(1).toString(),"Готов");
        assertEquals(1,db.upcomingJobs(7,10).size());
        assertEquals(day7,db.upcomingJobs(7,10).get(0).id);
        assertEquals(yesterday,db.overdueJobs(0).get(0).id);
        try {db.upcomingJobs(0,10);fail("zero day range");}
        catch(IllegalArgumentException expected){}
    }

    @Test public void paginatesMoreThanOneHundredUpcomingOrders() {
        long clientId=client("Много сроков");
        String tomorrow=java.time.LocalDate.now().plusDays(1).toString();
        for(int i=0;i<105;i++) {
            long id=job(clientId,10000+i);
            setDeadline(id,tomorrow,"В работе");
        }
        assertEquals(100,db.upcomingJobsPage(7,0).size());
        assertEquals(5,db.upcomingJobsPage(7,1).size());
        assertEquals(0,db.upcomingJobsPage(7,2).size());
        assertEquals(5,db.upcomingJobs(7,5).size());
    }

    private void setDeadline(long jobId,String deadline,String status) {
        CrmStore.Job existing=db.job(jobId);
        existing.dueDate=deadline;
        existing.status=status;
        db.saveJob(existing);
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
