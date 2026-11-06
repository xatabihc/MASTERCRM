package com.mastercrm.offline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import android.util.Base64;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline-first local data store.
 * Every monetary amount is a long in kopecks. IDs are never reused.
 * Import uses a single SQLite transaction; a failed import preserves prior data.
 */
public final class CrmStore extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "mastercrm_offline_preview.db";
    public static final String[] STATUS = {"Новый", "В работе", "Готов", "Выдан", "Архив"};
    private static final int SCHEMA = 2;
    private static final int MAX_PHOTO_BYTES = 2 * 1024 * 1024;
    private static final int PAGE_SIZE = 100;

    public static class Client {
        public long id;
        public String name = "";
        public String phone = "";
        public String note = "";
        public long createdAt;
    }
    public static class Job {
        public long id, clientId, price, paid, cost;
        public String number = "", title = "", description = "", status = STATUS[0], dueDate = "";
        public String clientName = "", clientPhone = "";
        public long createdAt;
    }
    public static class Payment {
        public long id, jobId, amount, createdAt;
        public String note = "";
    }
    public static class Expense {
        public long id, jobId, amount, createdAt;
        public String category = "", note = "";
    }
    public static class Photo {
        public long id, jobId, createdAt;
        public String kind = "before";
        public byte[] jpeg;
    }
    public static class MonthMetrics {
        public String month;
        public long income, expenses;
        public long profit() { return income - expenses; }
    }
    public static class Summary {
        public long activeJobs, totalClients, dueJobs, totalIncome, totalExpense, totalDebt, todayJobs;
    }

    public CrmStore(Context ctx) {
        super(ctx.getApplicationContext(), DATABASE_NAME, null, SCHEMA);
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE clients (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', " +
                "note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE jobs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT NOT NULL UNIQUE, " +
                "client_id INTEGER NOT NULL REFERENCES clients(id) ON DELETE RESTRICT, " +
                "title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'Новый', " +
                "price INTEGER NOT NULL DEFAULT 0 CHECK(price >= 0), " +
                "due_date TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE payments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL REFERENCES jobs(id) ON DELETE RESTRICT, " +
                "amount INTEGER NOT NULL CHECK(amount <> 0), note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE expenses (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER REFERENCES jobs(id) ON DELETE RESTRICT, " +
                "amount INTEGER NOT NULL CHECK(amount > 0), category TEXT NOT NULL, " +
                "note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_clients_name ON clients(name)");
        db.execSQL("CREATE INDEX idx_jobs_client ON jobs(client_id)");
        db.execSQL("CREATE INDEX idx_jobs_status ON jobs(status)");
        db.execSQL("CREATE INDEX idx_payments_job ON payments(job_id)");
        db.execSQL("CREATE INDEX idx_expenses_job ON expenses(job_id)");
        createPhotoTable(db);
    }
    private static void createPhotoTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE job_photos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "job_id INTEGER NOT NULL REFERENCES jobs(id) ON DELETE RESTRICT, " +
                "kind TEXT NOT NULL CHECK(kind IN ('before','after')), " +
                "image BLOB NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_photos_job ON job_photos(job_id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion >= 2) {
            createPhotoTable(db); // Additive migration: no existing user tables are changed.
            oldVersion = 2;
        }
        if (oldVersion != newVersion)
            throw new IllegalStateException("Нет безопасной миграции с " + oldVersion + " на " + newVersion);
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }
    private static String col(Cursor c, String column) {
        int i = c.getColumnIndexOrThrow(column);
        return c.isNull(i) ? "" : c.getString(i);
    }
    private static long num(Cursor c, String column) {
        int i = c.getColumnIndexOrThrow(column);
        return c.isNull(i) ? 0L : c.getLong(i);
    }
    public static String today() { return LocalDate.now().toString(); }

    public synchronized long saveClient(Client client) {
        if (clean(client.name, 120).isEmpty()) throw new IllegalArgumentException("Укажите имя клиента");
        ContentValues v = new ContentValues();
        v.put("name", clean(client.name, 120));
        v.put("phone", clean(client.phone, 60));
        v.put("note", clean(client.note, 2000));
        SQLiteDatabase db = getWritableDatabase();
        if (client.id > 0) {
            if (db.update("clients", v, "id=?", new String[]{String.valueOf(client.id)}) != 1)
                throw new IllegalArgumentException("Клиент не найден");
            return client.id;
        }
        v.put("created_at", System.currentTimeMillis());
        return db.insertOrThrow("clients", null, v);
    }
    public synchronized List<Client> clients(String query, int page) {
        List<Client> list = new ArrayList<>();
        String like = "%" + clean(query, 100) + "%";
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,phone,note,created_at FROM clients WHERE name LIKE ? OR phone LIKE ? " +
                "ORDER BY id DESC LIMIT ? OFFSET ?",
                new String[]{like, like, "" + PAGE_SIZE, "" + (Math.max(page, 0) * PAGE_SIZE)})) {
            while (c.moveToNext()) list.add(readClient(c));
        }
        return list;
    }
    public synchronized Client client(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,phone,note,created_at FROM clients WHERE id=?", new String[]{"" + id})) {
            return c.moveToFirst() ? readClient(c) : null;
        }
    }
    private static Client readClient(Cursor c) {
        Client x = new Client();
        x.id = num(c, "id"); x.name = col(c, "name");
        x.phone = col(c, "phone"); x.note = col(c, "note");
        x.createdAt = num(c, "created_at"); return x;
    }

    public synchronized long saveJob(Job job) {
        if (job.clientId <= 0 || client(job.clientId) == null) throw new IllegalArgumentException("Выберите клиента");
        if (clean(job.title, 160).isEmpty()) throw new IllegalArgumentException("Укажите название заказа");
        if (job.price < 0) throw new IllegalArgumentException("Стоимость не может быть отрицательной");
        boolean validStatus = false;
        for (String s : STATUS) if (s.equals(job.status)) validStatus = true;
        if (!validStatus) throw new IllegalArgumentException("Неизвестный статус");
        String due = clean(job.dueDate, 10);
        if (!due.isEmpty()) {
            try { if (!LocalDate.parse(due).toString().equals(due)) throw new Exception(); }
            catch (Exception e) { throw new IllegalArgumentException("Дата в формате ГГГГ-ММ-ДД"); }
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("client_id", job.clientId);
        v.put("title", clean(job.title, 160));
        v.put("description", clean(job.description, 4000));
        String status=job.status;
        if(job.id>0) {
            long paid=paidForJob(job.id);
            if("Архив".equals(status) && paid!=job.price)
                throw new IllegalArgumentException("Архивировать можно только полностью оплаченный заказ");
            if("Выдан".equals(status) && paid==job.price) status="Архив";
        } else if("Архив".equals(status) && job.price!=0) {
            throw new IllegalArgumentException("Новый неоплаченный заказ нельзя архивировать");
        }
        v.put("status", status);
        v.put("price", job.price);
        v.put("due_date", due);
        if (job.id > 0) {
            if (job.price < paidForJob(job.id))
                throw new IllegalArgumentException("Нельзя установить цену ниже суммы полученных оплат");
            if (db.update("jobs", v, "id=?", new String[]{"" + job.id}) != 1)
                throw new IllegalArgumentException("Заказ не найден");
            return job.id;
        }
        db.beginTransaction();
        try {
            // UNIQUE temporary number is replaced within the same transaction.
            v.put("number", "TEMP-" + java.util.UUID.randomUUID());
            v.put("created_at", System.currentTimeMillis());
            long id = db.insertOrThrow("jobs", null, v);
            ContentValues number = new ContentValues();
            number.put("number", String.format(java.util.Locale.ROOT, "MC-%06d", id));
            db.update("jobs", number, "id=?", new String[]{"" + id});
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }
    private static final String JOB_SELECT =
            "SELECT j.id,j.number,j.client_id,j.title,j.description,j.status,j.price,j.due_date,j.created_at, " +
            "c.name AS client_name,c.phone AS client_phone, " +
            "COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.job_id=j.id),0) AS paid, " +
            "COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE e.job_id=j.id),0) AS cost " +
            "FROM jobs j JOIN clients c ON c.id=j.client_id ";
    public synchronized List<Job> jobs(String query, String filter, int page) {
        List<Job> list = new ArrayList<>();
        String like = "%" + clean(query, 100) + "%";
        boolean statusFilter = filter != null && !filter.isEmpty();
        String sql = JOB_SELECT + "WHERE (j.number LIKE ? OR j.title LIKE ? OR c.name LIKE ? OR c.phone LIKE ?) " +
                (statusFilter ? "AND j.status=? " : "") + "ORDER BY j.id DESC LIMIT ? OFFSET ?";
        List<String> args = new ArrayList<>();
        for (int i = 0; i < 4; i++) args.add(like);
        if (statusFilter) args.add(filter);
        args.add("" + PAGE_SIZE); args.add("" + (Math.max(page, 0) * PAGE_SIZE));
        try (Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) list.add(readJob(c));
        }
        return list;
    }
    public synchronized Job job(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                JOB_SELECT + "WHERE j.id=?", new String[]{"" + id})) {
            return c.moveToFirst() ? readJob(c) : null;
        }
    }
    public synchronized List<Job> jobsForClient(long clientId) {
        List<Job> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                JOB_SELECT + "WHERE j.client_id=? ORDER BY j.id DESC LIMIT 1000",
                new String[]{"" + clientId})) {
            while(c.moveToNext()) list.add(readJob(c));
        }
        return list;
    }
    /** Duplicate an order without transferring its payments or expenses. */
    public synchronized long duplicateJob(long jobId) {
        Job existing = job(jobId);
        if (existing == null) throw new IllegalArgumentException("Заказ не найден");
        Job copy = new Job();
        copy.clientId = existing.clientId;
        copy.title = existing.title + " (копия)";
        copy.description = existing.description;
        copy.price = existing.price;
        copy.dueDate = "";
        copy.status = STATUS[0];
        return saveJob(copy);
    }

    private static Job readJob(Cursor c) {
        Job j = new Job();
        j.id = num(c,"id"); j.number = col(c,"number"); j.clientId = num(c,"client_id");
        j.title = col(c,"title"); j.description = col(c,"description"); j.status = col(c,"status");
        j.price = num(c,"price"); j.paid = num(c,"paid"); j.cost = num(c,"cost");
        j.dueDate = col(c,"due_date"); j.createdAt = num(c,"created_at");
        j.clientName = col(c,"client_name"); j.clientPhone = col(c,"client_phone");
        return j;
    }
    public synchronized long paidForJob(long jobId) {
        return DatabaseUtils.longForQuery(getReadableDatabase(),
                "SELECT COALESCE(SUM(amount),0) FROM payments WHERE job_id=?", new String[]{"" + jobId});
    }
    public synchronized long addPayment(long jobId, long amount, String note) {
        if (amount == 0) throw new IllegalArgumentException("Сумма должна отличаться от нуля");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Job j = job(jobId);
            if (j == null) throw new IllegalArgumentException("Заказ не найден");
            long total = Math.addExact(j.paid, amount);
            if (total < 0) throw new IllegalArgumentException("Возврат не может превышать полученные оплаты");
            if (total > j.price) throw new IllegalArgumentException("Оплата не может превышать цену заказа");
            ContentValues v = new ContentValues();
            v.put("job_id", jobId); v.put("amount", amount);
            v.put("note", clean(note, 500)); v.put("created_at", System.currentTimeMillis());
            long id = db.insertOrThrow("payments", null, v);
            if("Выдан".equals(j.status) && total==j.price) {
                ContentValues archived=new ContentValues();
                archived.put("status","Архив");
                db.update("jobs",archived,"id=?",new String[]{""+jobId});
            } else if("Архив".equals(j.status) && total<j.price) {
                ContentValues restored=new ContentValues();
                restored.put("status","Выдан");
                db.update("jobs",restored,"id=?",new String[]{""+jobId});
            }
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    public synchronized List<Payment> payments(long jobId) {
        List<Payment> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM payments WHERE job_id=? ORDER BY id DESC", new String[]{"" + jobId})) {
            while (c.moveToNext()) {
                Payment p = new Payment(); p.id = num(c,"id"); p.jobId = num(c,"job_id");
                p.amount = num(c,"amount"); p.note = col(c,"note");
                p.createdAt = num(c,"created_at"); result.add(p);
            }
        }
        return result;
    }
    public synchronized long addExpense(long jobId, long amount, String category, String note) {
        if (amount <= 0) throw new IllegalArgumentException("Расход должен быть положительным");
        if (clean(category, 100).isEmpty()) throw new IllegalArgumentException("Укажите категорию");
        if (jobId != 0 && job(jobId) == null) throw new IllegalArgumentException("Заказ не найден");
        ContentValues v = new ContentValues();
        if (jobId == 0) v.putNull("job_id"); else v.put("job_id", jobId);
        v.put("amount", amount); v.put("category", clean(category,100));
        v.put("note", clean(note,500)); v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("expenses", null, v);
    }
    public synchronized List<Expense> expenses(int limit) {
        List<Expense> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM expenses ORDER BY id DESC LIMIT ?",new String[]{""+Math.max(limit,1)})) {
            while(c.moveToNext()) {
                Expense e = new Expense(); e.id=num(c,"id"); e.jobId=num(c,"job_id");
                e.amount=num(c,"amount"); e.category=col(c,"category");
                e.note=col(c,"note"); e.createdAt=num(c,"created_at");result.add(e);
            }
        }
        return result;
    }
    public synchronized Summary summary() {
        SQLiteDatabase db = getReadableDatabase();
        Summary s = new Summary();
        s.totalClients = DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM clients",null);
        s.activeJobs = DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM jobs WHERE status NOT IN ('Выдан','Архив')", null);
        s.dueJobs = DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM jobs WHERE due_date<>'' AND due_date<=? AND status NOT IN ('Выдан','Архив')",
                new String[]{today()});
        s.todayJobs = DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM jobs WHERE created_at>=?",
                new String[]{"" + LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()});
        s.totalIncome = DatabaseUtils.longForQuery(db,"SELECT COALESCE(SUM(amount),0) FROM payments",null);
        s.totalExpense = DatabaseUtils.longForQuery(db,"SELECT COALESCE(SUM(amount),0) FROM expenses",null);
        s.totalDebt = DatabaseUtils.longForQuery(db,
                "SELECT COALESCE(SUM(MAX(j.price-COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.job_id=j.id),0),0)),0) FROM jobs j WHERE j.status!='Архив'",
                null);
        return s;
    }
    /** Six real calendar months, local-device time zone. Payments include refunds. */
    public synchronized List<MonthMetrics> monthlyMetrics() {
        List<MonthMetrics> result=new ArrayList<>();
        java.time.YearMonth now=java.time.YearMonth.now();
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();
        SQLiteDatabase db=getReadableDatabase();
        for(int i=5;i>=0;i--) {
            java.time.YearMonth month=now.minusMonths(i);
            long from=month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long until=month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
            String[] args={""+from,""+until};
            MonthMetrics m=new MonthMetrics();
            m.month=month.toString();
            m.income=DatabaseUtils.longForQuery(db,
                    "SELECT COALESCE(SUM(amount),0) FROM payments WHERE created_at>=? AND created_at<?",args);
            m.expenses=DatabaseUtils.longForQuery(db,
                    "SELECT COALESCE(SUM(amount),0) FROM expenses WHERE created_at>=? AND created_at<?",args);
            result.add(m);
        }
        return result;
    }

    private static void validatePhoto(String kind, byte[] jpeg) {
        if (!("before".equals(kind) || "after".equals(kind)))
            throw new IllegalArgumentException("Неверный тип фотографии");
        if (jpeg == null || jpeg.length < 4 || jpeg.length > MAX_PHOTO_BYTES
                || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8
                || (jpeg[jpeg.length-2] & 0xff) != 0xff
                || (jpeg[jpeg.length-1] & 0xff) != 0xd9)
            throw new IllegalArgumentException("Неверный JPEG или превышен лимит 2 МБ");
    }
    /** JPEG image bytes are stored privately in SQLite and included in JSON backups. */
    public synchronized long addPhoto(long jobId, String kind, byte[] jpeg) {
        validatePhoto(kind, jpeg);
        if (job(jobId) == null) throw new IllegalArgumentException("Заказ не найден");
        SQLiteDatabase db=getWritableDatabase();
        long count=DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM job_photos WHERE job_id=? AND kind=?",
                new String[]{"" + jobId,kind});
        if(count >= 10)throw new IllegalArgumentException("Не более 10 фотографий каждого типа");
        ContentValues v=new ContentValues();
        v.put("job_id",jobId);v.put("kind",kind);v.put("image",jpeg);
        v.put("created_at",System.currentTimeMillis());
        return db.insertOrThrow("job_photos",null,v);
    }
    public synchronized List<Photo> photos(long jobId) {
        List<Photo> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT id,job_id,kind,image,created_at FROM job_photos WHERE job_id=? ORDER BY id",
                new String[]{""+jobId})) {
            while(c.moveToNext()){
                Photo p=new Photo();
                p.id=num(c,"id");p.jobId=num(c,"job_id");p.kind=col(c,"kind");
                p.jpeg=c.getBlob(c.getColumnIndexOrThrow("image"));
                p.createdAt=num(c,"created_at");result.add(p);
            }
        }
        return result;
    }
    public synchronized boolean removePhoto(long photoId,long jobId) {
        return getWritableDatabase().delete("job_photos","id=? AND job_id=?",
                new String[]{""+photoId,""+jobId})==1;
    }

    public synchronized String exportJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", "mastercrm-offline-preview");
        root.put("schema", SCHEMA);
        root.put("exported_at", System.currentTimeMillis());
        for (String table : new String[]{"clients","jobs","payments","expenses","job_photos"}) {
            root.put(table, rows(table));
        }
        return root.toString(2);
    }
    private JSONArray rows(String table) throws JSONException {
        JSONArray array = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM " + table + " ORDER BY id",null)) {
            while(c.moveToNext()) {
                JSONObject obj = new JSONObject();
                for (int i=0;i<c.getColumnCount();i++) {
                    String name=c.getColumnName(i);
                    if(c.isNull(i)) obj.put(name, JSONObject.NULL);
                    else if(c.getType(i)==Cursor.FIELD_TYPE_INTEGER) obj.put(name,c.getLong(i));
                    else if(c.getType(i)==Cursor.FIELD_TYPE_BLOB)
                        obj.put(name,Base64.encodeToString(c.getBlob(i),Base64.NO_WRAP));
                    else obj.put(name,c.getString(i));
                }
                array.put(obj);
            }
        }
        return array;
    }
    private static void validateImportedFinancials(SQLiteDatabase db) {
        // Payment rows are signed (refunds may be negative), but final balance
        // per job must be between zero and agreed price, inclusive.
        try (Cursor c=db.rawQuery(
                "SELECT j.id FROM jobs j WHERE " +
                "COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.job_id=j.id),0) < 0 " +
                "OR COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.job_id=j.id),0) > j.price " +
                "LIMIT 1",null)) {
            if(c.moveToFirst())
                throw new IllegalArgumentException(
                        "В резервной копии есть заказы с переплатой или отрицательной оплатой");
        }
        try(Cursor c=db.rawQuery(
                "SELECT id FROM clients WHERE trim(name)='' " +
                "UNION ALL SELECT id FROM jobs WHERE trim(title)='' OR trim(number)='' " +
                "OR status NOT IN ('Новый','В работе','Готов','Выдан','Архив') " +
                "LIMIT 1",null)){
            if(c.moveToFirst())
                throw new IllegalArgumentException(
                        "В резервной копии есть записи с неверными именами или статусами");
        }
        // Existing Preview 0.1 and 0.2 backups might contain manually archived
        // unpaid orders. Keep them importable; the visible debt is still correct.
    }

    public synchronized void restoreJson(String input) throws JSONException {
        JSONObject root = new JSONObject(input);
        if (!"mastercrm-offline-preview".equals(root.optString("format"))
                || (root.optInt("schema") != 1 && root.optInt("schema") != SCHEMA))
            throw new IllegalArgumentException("Файл другой версии или формата; существующие данные не изменены");
        String[] tables={"clients","jobs","payments","expenses","job_photos"};
        // Schema 1 backups have no images and can still be safely imported.
        if(root.optInt("schema")==1)root.put("job_photos",new JSONArray());
        for (String table : tables) {
            JSONArray items=root.getJSONArray(table);
            if(items.length()>100000) throw new IllegalArgumentException("Слишком большой файл резервной копии");
        }
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("job_photos",null,null);
            db.delete("payments",null,null);
            db.delete("expenses",null,null);
            db.delete("jobs",null,null);
            db.delete("clients",null,null);
            for (String table : tables) {
                JSONArray items=root.getJSONArray(table);
                for (int i=0;i<items.length();i++) {
                    JSONObject item=items.getJSONObject(i);
                    ContentValues v=new ContentValues();
                    // Explicit column whitelist prevents injecting columns or changing schema.
                    String[] fields;
                    switch (table) {
                        case "clients": fields=new String[]{"id","name","phone","note","created_at"}; break;
                        case "jobs": fields=new String[]{"id","number","client_id","title","description","status","price","due_date","created_at"}; break;
                        case "payments": fields=new String[]{"id","job_id","amount","note","created_at"}; break;
                        case "expenses": fields=new String[]{"id","job_id","amount","category","note","created_at"}; break;
                        default: fields=new String[]{"id","job_id","kind","image","created_at"};
                    }
                    for (String field:fields) {
                        if (!item.has(field)) throw new JSONException("Отсутствует поле " + field + " в " + table);
                        if (item.isNull(field)) v.putNull(field);
                        else if (field.equals("image")) {
                            String raw=item.getString(field);
                            if(raw.length() > ((MAX_PHOTO_BYTES+2)/3)*4+8)
                                throw new IllegalArgumentException("Фотография слишком большая");
                            byte[] bytes=Base64.decode(raw,Base64.DEFAULT);
                            validatePhoto(item.getString("kind"),bytes);
                            v.put(field,bytes);
                        }
                        else if (field.equals("id") || field.equals("client_id") || field.equals("job_id")
                            || field.equals("price") || field.equals("amount") || field.equals("created_at"))
                            v.put(field,item.getLong(field));
                        else v.put(field,item.getString(field));
                    }
                    db.insertOrThrow(table,null,v);
                }
            }
            // Reject corrupt/oversized or fabricated backups, preserving old data by rollback.
            try(Cursor c=db.rawQuery(
                    "SELECT job_id,kind,COUNT(*) AS n FROM job_photos GROUP BY job_id,kind HAVING n>10",
                    null)) {
                if(c.moveToFirst())throw new IllegalArgumentException("Слишком много фотографий в резервной копии");
            }
            try(Cursor c=db.rawQuery(
                    "SELECT id FROM payments WHERE amount=0 UNION ALL " +
                    "SELECT id FROM jobs WHERE price<0 UNION ALL SELECT id FROM expenses WHERE amount<=0",
                    null)){
                if(c.moveToFirst())throw new IllegalArgumentException("Некорректные суммы в резервной копии");
            }
            // Payment totals and basic record semantics must be valid BEFORE commit.
            // A crafted JSON backup must never introduce a negative debt, overpayment,
            // unsupported status, or empty client/description while replacing real data.
            validateImportedFinancials(db);
            // Integrity checks still inside transaction.
            try (Cursor c=db.rawQuery("PRAGMA foreign_key_check",null)) {
                if(c.moveToFirst()) throw new IllegalArgumentException("Нарушены ссылки между записями");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
