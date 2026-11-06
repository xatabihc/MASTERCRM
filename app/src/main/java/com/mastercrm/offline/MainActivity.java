package com.mastercrm.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.WindowInsets;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** No Internet, accounts, permissions or third-party tracking. Android 8+. */
public final class MainActivity extends Activity {
    private static final int EXPORT_JSON = 21, IMPORT_JSON = 22, EXPORT_PDF = 23, PHOTO_BEFORE = 24, PHOTO_AFTER = 25, EXPORT_SECURE = 26, IMPORT_SECURE = 27, EXPORT_STREAM = 28, IMPORT_STREAM = 29;
    private static final String[] TABS = {"Обзор","Заказы","Клиенты","Финансы","Ещё"};
    private CrmStore store;
    private int tab = 0, page = 0;
    private String search = "", filter = "";
    private int dueMode = 0; // 0=all; 1=overdue; 2=next seven days
    private String period = "month";
    private boolean regularOnly=false;
    private boolean dark;
    private int bg, panel, text, sub, accent, stroke, green, red;
    private long pdfJobId;
    private long photoJobId;
    private LinearLayout body;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new CrmStore(this);
        // Android 15 enforces edge-to-edge. Handle all system bar insets in our
        // root view so the bottom tabs cannot overlap the OS Home/taskbar area.
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if(state!=null)photoJobId=state.getLong("photoJobId",0);
        dark = getPreferences(MODE_PRIVATE).getBoolean("dark", false);
        String saved=getPreferences(MODE_PRIVATE).getString("financial_period","month");
        period=Arrays.asList("day","week","month","all").contains(saved)?saved:"month";
        regularOnly=getPreferences(MODE_PRIVATE).getBoolean("regular_only",false);
        palette();
        render();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putLong("photoJobId",photoJobId);
        super.onSaveInstanceState(out);
    }
    @Override public void onDestroy() { if (store != null) store.close(); super.onDestroy(); }
    private int dp(float d) { return (int)(getResources().getDisplayMetrics().density * d + .5f); }
    private void palette() {
        bg = Color.parseColor(dark ? "#111827" : "#F5F7FB");
        panel = Color.parseColor(dark ? "#1F2937" : "#FFFFFF");
        text = Color.parseColor(dark ? "#F8FAFC" : "#111827");
        sub = Color.parseColor(dark ? "#CBD5E1" : "#64748B");
        accent = Color.parseColor(dark ? "#6EA8FE" : "#2563EB");
        stroke = Color.parseColor(dark ? "#334155" : "#E5EAF1");
        green = Color.parseColor(dark ? "#4ADE80" : "#059669");
        red = Color.parseColor(dark ? "#FDA4AF" : "#DC2626");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(panel);
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private GradientDrawable shape(int fill, int border, int radius) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radius)); if(border!=0)d.setStroke(dp(1),border);
        return d;
    }
    private LinearLayout column() {
        LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);return x;
    }
    private LinearLayout row() {
        LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.HORIZONTAL);x.setGravity(Gravity.CENTER_VERTICAL);return x;
    }
    private TextView label(String s,int size,boolean bold,int color) {
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFontFeatureSettings("kern");
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private void space(LinearLayout box,int dp) {
        View v=new View(this);box.addView(v,new LinearLayout.LayoutParams(1,dp(dp)));
    }
    private LinearLayout card(LinearLayout target) {
        LinearLayout c=column();c.setPadding(dp(16),dp(15),dp(16),dp(15));
        c.setBackground(shape(panel,stroke,18));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(12));
        target.addView(c,p);return c;
    }
    private Button button(String title, boolean primary, Runnable action) {
        Button b=new Button(this);
        b.setText(title);b.setTextSize(14);b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : text);
        b.setBackground(shape(primary ? accent : panel,primary?0:stroke,12));
        b.setMinHeight(dp(48));b.setPadding(dp(12),dp(9),dp(12),dp(9));
        b.setOnClickListener(v->action.run());return b;
    }
    private void heading(LinearLayout c,String s) {
        c.addView(label(s,19,true,text));
        space(c,12);
    }
    private void line(LinearLayout c,String left,String right) {
        LinearLayout x=row();
        TextView a=label(left,14,false,sub),b=label(right,14,true,text);
        x.addView(a,new LinearLayout.LayoutParams(0,-2,1));
        b.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        x.addView(b,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(4));c.addView(x,p);
    }
    private void banner(LinearLayout c,String title,String value) {
        c.addView(label(title,13,false,sub));space(c,4);c.addView(label(value,25,true,text));
    }
    private void error(Exception e) {
        String message=e.getMessage()==null?"Не удалось выполнить действие":e.getMessage();
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }
    private interface ThrowingRunnable { void run() throws Exception; }
    private void safe(ThrowingRunnable r) {
        try {r.run();} catch(Exception e) { error(e); }
    }
    private void navigate(int i) {tab=i;page=0;search="";filter="";dueMode=0;render();}
    private void showDueOrders(int mode) {
        tab=1;page=0;search="";filter="";dueMode=mode;render();
    }
    private void render() {
        palette();
        LinearLayout base=column();
        base.setBackgroundColor(bg);
        if (Build.VERSION.SDK_INT >= 30) {
            base.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
                return insets;
            });
        }
        setContentView(base);
        if (Build.VERSION.SDK_INT >= 30) base.requestApplyInsets();
        LinearLayout top=column();top.setPadding(dp(18),dp(17),dp(18),dp(13));
        top.addView(label("MASTERCRM  ·  OFFLINE",12,true,accent));
        LinearLayout title=row();
        TextView h=label(TABS[tab],29,true,text);
        title.addView(h,new LinearLayout.LayoutParams(0,-2,1));
        Button add=button("+",true,()->{
            if(tab==2)clientEditor(null);
            else if(tab==3)expenseEditor(0);
            else if(tab==1||tab==0)jobEditor(null,0);
            else navigate(1);
        });
        add.setContentDescription("Создать запись");
        title.addView(add,new LinearLayout.LayoutParams(dp(56),dp(50)));
        top.addView(title);
        top.addView(label("Данные хранятся только на этом устройстве",12,false,sub));
        base.addView(top);
        ScrollView sc=new ScrollView(this);sc.setFillViewport(true);
        body=column();body.setPadding(dp(16),dp(4),dp(16),dp(20));
        sc.addView(body,new ScrollView.LayoutParams(-1,-2));
        base.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        try {
            switch(tab) {
                case 0: dashboard(); break;
                case 1: jobs(); break;
                case 2: clients(); break;
                case 3: finance(); break;
                default: settings();
            }
        } catch(Exception e) {
            LinearLayout c=card(body);c.addView(label("Ошибка отображения: "+e.getMessage(),14,false,red));
        }
        LinearLayout nav=row();nav.setPadding(dp(3),dp(7),dp(3),dp(7));nav.setBackground(shape(panel,0,0));
        for(int i=0;i<TABS.length;i++) {
            final int at=i;
            TextView n=label(TABS[i],12,i==tab,i==tab?accent:sub);
            n.setGravity(Gravity.CENTER);
            n.setContentDescription("Раздел: "+TABS[i]);
            n.setMinHeight(dp(52));
            n.setBackground(i==tab?shape(bg,0,11):null);
            n.setOnClickListener(v->navigate(at));
            nav.addView(n,new LinearLayout.LayoutParams(0,dp(52),1));
        }
        base.addView(nav);
    }

    private String periodName() {
        switch(period) {
            case "day":return "сегодня";
            case "week":return "за неделю";
            case "month":return "за месяц";
            default:return "за всё время";
        }
    }
    private void periodSelector(LinearLayout target) {
        LinearLayout options=row();
        String[] keys={"day","week","month","all"};
        String[] labels={"День","Неделя","Месяц","Всё"};
        for(int i=0;i<keys.length;i++) {
            final String selected=keys[i];
            Button b=button(labels[i],period.equals(selected),()->{
                period=selected;
                getPreferences(MODE_PRIVATE).edit().putString("financial_period",selected).apply();
                render();
            });
            b.setContentDescription("Финансовый период: "+labels[i]);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);
            if(i>0)p.setMargins(dp(4),0,0,0);
            options.addView(b,p);
        }
        target.addView(options);
        space(target,12);
    }
    private void dashboard() {
        CrmStore.Summary s=store.summary();
        CrmStore.PeriodMetrics cash=store.periodMetrics(period);
        periodSelector(body);
        LinearLayout hero=card(body);
        hero.addView(label("Получено "+periodName(),14,false,sub));space(hero,5);
        hero.addView(label(Money.format(cash.income),31,true,green));
        space(hero,10);
        line(hero,"Расходы",Money.format(cash.expense));
        line(hero,"Кассовая прибыль",Money.format(cash.cashProfit()));
        line(hero,"Создано заказов "+periodName(),""+cash.ordersCreated);
        LinearLayout stats=card(body);heading(stats,"В работе");
        line(stats,"Активные заказы",""+s.activeJobs);
        line(stats,"Заказов сегодня",""+s.todayJobs);
        line(stats,"Срок сегодня или просрочен",""+s.dueJobs);
        line(stats,"Клиенты",""+s.totalClients);
        line(stats,"Неоплаченные суммы",Money.format(s.totalDebt));
        if(s.dueJobs>0) {
            stats.addView(button("Просрочено или срок сегодня: "+s.dueJobs,true,
                    ()->showDueOrders(1)));
            space(stats,8);
        }
        List<CrmStore.Job> upcoming=store.upcomingJobs(7,5);
        if(!upcoming.isEmpty()) {
            LinearLayout soon=card(body);heading(soon,"Ближайшие 7 дней");
            for(CrmStore.Job j:upcoming) {
                soon.addView(jobRow(j));space(soon,9);
            }
            soon.addView(button("Все ближайшие заказы",false,()->showDueOrders(2)));
        }
        LinearLayout quick=card(body);heading(quick,"Быстрые действия");
        quick.addView(button("Новый заказ",true,()->jobEditor(null,0)));
        space(quick,8);
        quick.addView(button("Добавить клиента",false,()->clientEditor(null)));
        space(quick,8);
        quick.addView(button("Расход",false,()->expenseEditor(0)));
        LinearLayout recent=card(body);heading(recent,"Последние заказы");
        List<CrmStore.Job> list=store.jobs("","",0);
        if(list.isEmpty())recent.addView(label("Заказов пока нет. Создайте первого клиента и заказ.",14,false,sub));
        for(int i=0;i<Math.min(5,list.size());i++) recent.addView(jobRow(list.get(i)));
        if(list.size()>5){space(recent,8);recent.addView(button("Все заказы",false,()->navigate(1)));}
    }
    private void searchBar(LinearLayout target,String hint) {
        EditText edit=new EditText(this);edit.setSingleLine(true);
        edit.setText(search);edit.setHint(hint);edit.setTextColor(text);edit.setHintTextColor(sub);
        edit.setTextSize(15);edit.setPadding(dp(14),dp(10),dp(14),dp(10));
        edit.setBackground(shape(panel,stroke,12));
        target.addView(edit,new LinearLayout.LayoutParams(-1,dp(52)));
        space(target,8);
        target.addView(button("Найти",true,()->{
            search=edit.getText().toString().trim();page=0;render();
        }));
        space(target,13);
    }
    private void jobs() {
        LinearLayout dueSelector=card(body);
        heading(dueSelector,"Сроки заказов");
        dueSelector.addView(button(dueMode==1?"✓ Просрочено и сегодня":"Просрочено и сегодня",
                dueMode==1,()->showDueOrders(dueMode==1?0:1)));
        space(dueSelector,6);
        dueSelector.addView(button(dueMode==2?"✓ Следующие 7 дней":"Следующие 7 дней",
                dueMode==2,()->showDueOrders(dueMode==2?0:2)));
        if(dueMode!=0) {
            List<CrmStore.Job> dated=dueMode==1?store.overdueJobs(page):
                    store.upcomingJobsPage(7,page);
            if(dated.isEmpty()){
                LinearLayout empty=card(body);
                empty.addView(label("Заказов с таким сроком нет",15,false,sub));
            } else {
                for(CrmStore.Job j:dated) {
                    LinearLayout c=card(body);c.addView(jobRow(j));
                }
                if(page>0)body.addView(button("← Предыдущая страница",false,
                        ()->{page--;render();}));
                if(dated.size()==100)body.addView(button("Следующая страница →",false,
                        ()->{page++;render();}));
            }
            return;
        }
        searchBar(body,"Номер, заказ, клиент или телефон");
        LinearLayout filters=card(body);
        Spinner spin=spinner(new String[]{"Все",CrmStore.STATUS[0],CrmStore.STATUS[1],CrmStore.STATUS[2],CrmStore.STATUS[3],CrmStore.STATUS[4]});
        if(!filter.isEmpty())spin.setSelection(Arrays.asList(CrmStore.STATUS).indexOf(filter)+1);
        filters.addView(spin);
        filters.addView(button("Применить фильтр",false,()->{
            filter=spin.getSelectedItemPosition()==0?"":CrmStore.STATUS[spin.getSelectedItemPosition()-1];
            page=0;render();
        }));
        List<CrmStore.Job> list=store.jobs(search,filter,page);
        if(list.isEmpty()) {empty("Заказы не найдены");return;}
        for(CrmStore.Job j:list) {
            LinearLayout c=card(body);c.addView(jobRow(j));
        }
        if(page>0)body.addView(button("← Предыдущая страница",false,()->{page--;render();}));
        if(list.size()==100) {body.addView(button("Следующая страница →",false,()->{page++;render();}));}
    }
    private View jobRow(CrmStore.Job j) {
        LinearLayout x=column();x.setPadding(0,dp(5),0,dp(5));
        LinearLayout top=row();
        TextView code=label(j.number,13,true,accent);
        top.addView(code,new LinearLayout.LayoutParams(0,-2,1));
        TextView status=label(j.status,12,true,statusColor(j.status));
        status.setPadding(dp(7),dp(5),dp(7),dp(5));
        status.setBackground(shape(bg,0,8));top.addView(status);
        x.addView(top);space(x,6);
        x.addView(label(j.title,18,true,text));
        space(x,4);x.addView(label(j.clientName,13,false,sub));space(x,8);
        if(!j.dueDate.isEmpty()) {
            boolean late=j.dueDate.compareTo(CrmStore.today())<=0
                    && !"Архив".equals(j.status) && !"Выдан".equals(j.status);
            x.addView(label("Срок: "+j.dueDate+(late?" · требуется внимание":""),
                    12,late,late?red:sub));
            space(x,6);
        }
        LinearLayout sums=row();
        sums.addView(label(Money.format(j.price),15,true,text),new LinearLayout.LayoutParams(0,-2,1));
        sums.addView(label("Долг: "+Money.format(Money.outstanding(j.price,j.paid)),12,false,sub));
        x.addView(sums);
        x.setOnClickListener(v->jobDetails(j.id));
        return x;
    }
    private int statusColor(String s) {
        if("Готов".equals(s))return green;
        if("Архив".equals(s)||"Выдан".equals(s))return sub;
        if("В работе".equals(s))return accent;
        return text;
    }
    private void clients() {
        searchBar(body,"Имя или телефон клиента");
        body.addView(button(regularOnly?"Постоянные клиенты · показать всех":
                "Показать постоянных клиентов (от 2 заказов)",false,()->{
            regularOnly=!regularOnly;
            page=0;
            getPreferences(MODE_PRIVATE).edit().putBoolean("regular_only",regularOnly).apply();
            render();
        }));
        space(body,12);
        List<CrmStore.ClientOverview> list=store.clientOverviews(search,page,regularOnly);
        if(list.isEmpty()) {empty(regularOnly?"Постоянные клиенты не найдены":"Клиенты не найдены");return;}
        for(CrmStore.ClientOverview c:list) {
            LinearLayout card=card(body);
            card.addView(label(c.name+(c.regular()?" · ★":""),18,true,text));
            space(card,6);card.addView(label(c.phone.isEmpty()?"Без телефона":c.phone,14,false,sub));
            space(card,6);
            line(card,"Заказов",""+c.jobCount);
            line(card,"Получено",Money.format(c.paid));
            if(c.debt>0)line(card,"К оплате",Money.format(c.debt));
            card.setOnClickListener(v->clientDetails(c.id));
        }
        if(page>0)body.addView(button("← Предыдущая страница",false,()->{page--;render();}));
        if(list.size()==100)body.addView(button("Следующая страница →",false,()->{page++;render();}));
    }
    private void finance() {
        CrmStore.Summary s=store.summary();
        CrmStore.PeriodMetrics cash=store.periodMetrics(period);
        periodSelector(body);
        LinearLayout summary=card(body);heading(summary,"Денежный поток "+periodName());
        line(summary,"Поступления",Money.format(cash.income));
        line(summary,"Расходы",Money.format(cash.expense));
        line(summary,"Кассовая прибыль",Money.format(cash.cashProfit()));
        line(summary,"Долги всех клиентов (на сегодня)",Money.format(s.totalDebt));
        summary.addView(label("Движение денег за выбранный период. Долги — текущий остаток по всем заказам, без налогов.",12,false,sub));
        LinearLayout debtors=card(body);heading(debtors,"Крупнейшие неоплаченные заказы");
        List<CrmStore.Job> owed=store.debtors(10);
        if(owed.isEmpty())debtors.addView(label("Нет непогашенных задолженностей",14,false,sub));
        for(CrmStore.Job j:owed) {
            debtors.addView(jobRow(j));
            space(debtors,12);
        }
        monthlyChart(body);
        LinearLayout add=card(body);
        add.addView(button("+ Новый расход",true,()->expenseEditor(0)));
        LinearLayout items=card(body);heading(items,"Последние расходы");
        List<CrmStore.Expense> entries=store.expenses(100);
        if(entries.isEmpty())items.addView(label("Расходов пока нет",14,false,sub));
        for(CrmStore.Expense e:entries) {
            line(items,e.category + (e.jobId>0?" · заказ #"+e.jobId:""),Money.format(e.amount));
            if(!e.note.isEmpty())items.addView(label(e.note,12,false,sub));
            space(items,8);
        }
    }
    private void monthlyChart(LinearLayout parent) {
        List<CrmStore.MonthMetrics> months=store.monthlyMetrics();
        LinearLayout chart=card(parent);
        heading(chart,"Поступления и расходы · 6 месяцев");
        chart.addView(label("Реальные движения денег по месяцам. Возвраты уменьшают поступления.",12,false,sub));
        space(chart,10);
        long max=1;
        for(CrmStore.MonthMetrics m:months)
            max=Math.max(max,Math.max(Math.abs(m.income),Math.abs(m.expenses)));
        for(CrmStore.MonthMetrics m:months) {
            String month=java.time.YearMonth.parse(m.month)
                    .format(DateTimeFormatter.ofPattern("LLLL yyyy",new Locale("ru","RU")));
            chart.addView(label(month,14,true,text));
            space(chart,5);
            moneyBar(chart,"Поступления",m.income,max,green);
            moneyBar(chart,"Расходы",m.expenses,max,red);
            space(chart,12);
        }
    }
    private void moneyBar(LinearLayout target,String name,long amount,long max,int color) {
        line(target,name,Money.format(amount));
        LinearLayout track=row();
        track.setBackground(shape(bg,0,6));
        float filled=(float)Math.max(0,amount) / (float)Math.max(1,max);
        // Negative net receipts are shown numerically, not as a misleading positive bar.
        float left=Math.min(100f,Math.max(0f,filled*100f));
        View bar=new View(this);
        bar.setBackground(shape(color,0,6));
        track.addView(bar,new LinearLayout.LayoutParams(0,dp(8),left));
        View rest=new View(this);
        track.addView(rest,new LinearLayout.LayoutParams(0,dp(8),100f-left));
        target.addView(track,new LinearLayout.LayoutParams(-1,dp(8)));
    }

    private String installedVersion() {
        try {
            String version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;
            return version==null?"неизвестная версия":version;
        } catch(android.content.pm.PackageManager.NameNotFoundException ex) {
            return "неизвестная версия";
        }
    }
    private void settings() {
        LinearLayout c=card(body);heading(c,"Оформление");
        c.addView(button(dark?"Светлая тема":"Тёмная тема",false,()->{
            dark=!dark;getPreferences(MODE_PRIVATE).edit().putBoolean("dark",dark).apply();render();
        }));
        LinearLayout backup=card(body);heading(backup,"Резервные копии");
        backup.addView(label("Рекомендуется потоковая защищённая копия .mcrm5: сохраняет фото отдельно, не загружает всю базу в память. Пароль не сохраняется.",13,false,sub));
        space(backup,12);
        backup.addView(button("Создать потоковую копию .mcrm5",true,()->{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE,"mastercrm-"+CrmStore.today()+StreamBackupCrypto.EXTENSION);
            startActivityForResult(i,EXPORT_STREAM);
        }));
        space(backup,8);
        backup.addView(button("Восстановить потоковую копию .mcrm5",false,()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i,IMPORT_STREAM);
        }));
        space(backup,16);
        backup.addView(label("Старый формат .mcrm: также зашифрован, но для больших архивов использует больше памяти. Совместимость сохранена.",12,false,sub));
        space(backup,10);
        backup.addView(button("Создать старую копию .mcrm",false,()->{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE,"mastercrm-"+CrmStore.today()+BackupCrypto.EXTENSION);
            startActivityForResult(i,EXPORT_SECURE);
        }));
        space(backup,8);
        backup.addView(button("Восстановить старую копию .mcrm",false,()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i,IMPORT_SECURE);
        }));
        space(backup,16);
        backup.addView(label("Совместимость: старый JSON без шифрования. Такой файл содержит имена, телефоны, суммы и фотографии открытым текстом.",13,false,sub));
        space(backup,10);
        backup.addView(button("Экспорт JSON (без защиты)",false,()->{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE,"mastercrm-backup-"+CrmStore.today()+".json");
            startActivityForResult(i,EXPORT_JSON);
        }));
        space(backup,8);
        backup.addView(button("Импорт JSON (без защиты)",false,()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
            startActivityForResult(i,IMPORT_JSON);
        }));
        LinearLayout about=card(body);heading(about,"О приложении");
        about.addView(label("MasterCRM Offline Preview · "+installedVersion(),14,true,text));
        space(about,8);
        about.addView(label("Работает без сети, серверов и платных сервисов. Отдельный пакет не заменяет установленную MasterCRM 0.9.4.",13,false,sub));
        space(about,6);
        about.addView(label("Не является фискальной системой; документы не заменяют кассовые чеки.",12,false,sub));
    }
    private void empty(String title) {
        LinearLayout c=card(body);c.addView(label(title,17,true,text));
        space(c,10);
        c.addView(button("Сбросить поиск",false,()->{search="";filter="";page=0;render();}));
    }
    private Spinner spinner(String[] values) {
        Spinner s=new Spinner(this);
        ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);
        s.setAdapter(a);s.setMinimumHeight(dp(48));return s;
    }
    private EditText field(LinearLayout holder,String label,String value,int inputType) {
        holder.addView(label(label,13,true,sub));space(holder,4);
        EditText edit=new EditText(this);
        edit.setText(value==null?"":value);
        edit.setContentDescription(label);
        edit.setTextColor(text);edit.setHintTextColor(sub);edit.setTextSize(16);
        edit.setInputType(inputType);
        edit.setPadding(dp(12),dp(11),dp(12),dp(11));edit.setBackground(shape(bg,stroke,10));
        holder.addView(edit,new LinearLayout.LayoutParams(-1,-2));space(holder,13);
        return edit;
    }
    private LinearLayout form() {
        LinearLayout c=column();c.setPadding(dp(14),dp(10),dp(14),dp(4));return c;
    }
    private AlertDialog.Builder alertBuilder() {
        return new AlertDialog.Builder(this, dark
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    private void dialog(String title,LinearLayout content,String positive,Runnable action) {
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        AlertDialog d=alertBuilder()
            .setTitle(title).setView(scroll).setNegativeButton("Отмена",null)
            .setPositiveButton(positive,null).create();
        d.setOnShowListener(it->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {action.run();d.dismiss();}
            catch(Exception e) { error(e); }
        }));
        d.show();
    }
    private void clientEditor(CrmStore.Client existing) {
        CrmStore.Client c=existing==null?new CrmStore.Client():existing;
        LinearLayout form=form();
        EditText name=field(form,"Имя / организация",c.name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText phone=field(form,"Телефон",c.phone,InputType.TYPE_CLASS_PHONE);
        EditText note=field(form,"Примечание",c.note,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        dialog(c.id==0?"Новый клиент":"Изменить клиента",form,"Сохранить",()->{
            c.name=name.getText().toString();c.phone=phone.getText().toString();
            c.note=note.getText().toString();store.saveClient(c);render();
        });
    }
    private void clientDetails(long id) {
        CrmStore.Client c=store.client(id);
        if(c==null){error(new IllegalArgumentException("Клиент не найден"));return;}
        LinearLayout d=form();
        d.addView(label(c.name,22,true,text));space(d,10);
        d.addView(label(c.phone,15,false,sub));space(d,12);
        if(!c.note.isEmpty()){d.addView(label(c.note,14,false,text));space(d,12);}
        CrmStore.ClientMetrics stats=store.clientMetrics(c.id);
        if(stats.regular())d.addView(label("★ Постоянный клиент",14,true,green));
        line(d,"Всего заказов",""+stats.jobCount);
        line(d,"Получено от клиента",Money.format(stats.paid));
        line(d,"Текущий долг",Money.format(stats.debt));
        space(d,9);
        d.addView(button("Изменить",false,()->clientEditor(c)));
        space(d,9);
        d.addView(button("+ Заказ для этого клиента",true,()->jobEditor(null,c.id)));
        space(d,16);
        d.addView(label("Заказы клиента",17,true,text));space(d,8);
        List<CrmStore.Job> clientJobs=store.jobsForClient(c.id);
        if(clientJobs.isEmpty()) d.addView(label("Заказов пока нет",13,false,sub));
        for(CrmStore.Job j:clientJobs) {
            d.addView(jobRow(j));space(d,12);
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(d);
        alertBuilder().setTitle("Карточка клиента")
                .setView(scroll).setPositiveButton("Закрыть",null).show();
    }
    /** Search all clients, not merely the first page; no cloud or permissions. */
    private void clientPicker(long[] selected,TextView chosen) {
        LinearLayout f=form();
        EditText q=field(f,"Поиск клиента","",InputType.TYPE_CLASS_TEXT);
        LinearLayout results=column();f.addView(results);
        ScrollView scroll=new ScrollView(this);scroll.addView(f);
        AlertDialog d=alertBuilder().setTitle("Выберите клиента")
                .setView(scroll).setNegativeButton("Отмена",null).create();
        Runnable load=()->{
            results.removeAllViews();
            List<CrmStore.Client> matches=store.clients(q.getText().toString().trim(),0);
            if(matches.isEmpty())results.addView(label("Ничего не найдено",14,false,sub));
            for(CrmStore.Client c:matches) {
                Button candidate=button(c.name+(c.phone.isEmpty()?"":" · "+c.phone),false,()->{
                    selected[0]=c.id;chosen.setText(c.name);d.dismiss();
                });
                results.addView(candidate);space(results,5);
            }
            if(matches.size()==100)results.addView(label(
                    "Показаны первые 100. Уточните поиск для остальных.",12,false,sub));
        };
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                load.run();
            }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });
        d.show();load.run();
    }

    private void jobEditor(CrmStore.Job existing,long preferredClientId) {
        CrmStore.Job job=existing==null?new CrmStore.Job():existing;
        List<CrmStore.Client> clients=store.clients("",0);
        if(clients.isEmpty()){
            alertBuilder().setMessage("Сначала добавьте клиента")
                    .setPositiveButton("Добавить", (d,w)->clientEditor(null))
                    .setNegativeButton("Отмена",null).show();
            return;
        }
        LinearLayout f=form();
        long desired=job.id>0?job.clientId:preferredClientId;
        CrmStore.Client initial=desired>0?store.client(desired):clients.get(0);
        if(initial==null)initial=clients.get(0);
        final long[] selectedClient={initial.id};
        f.addView(label("Клиент",13,true,sub));
        TextView chosen=label(initial.name,16,true,text);
        f.addView(chosen);space(f,7);
        f.addView(button("Выбрать клиента по имени или телефону",false,
                ()->clientPicker(selectedClient,chosen)));
        space(f,12);
        EditText title=field(f,"Работа / услуга",job.title,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText description=field(f,"Описание, материалы и заметки",job.description,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText price=field(f,"Стоимость, ₽",java.math.BigDecimal.valueOf(job.price,2).toPlainString(),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText due=field(f,"Срок: ГГГГ-ММ-ДД (необязательно)",job.dueDate,InputType.TYPE_CLASS_DATETIME);
        due.setContentDescription("Срок заказа");
        f.addView(button("Выбрать срок в календаре",false,()->{
            java.time.LocalDate initial;
            try {initial=java.time.LocalDate.parse(due.getText().toString().trim());}
            catch(Exception ignored) {initial=java.time.LocalDate.now();}
            DatePickerDialog picker=new DatePickerDialog(this,
                    (view,year,month,day)->due.setText(
                            java.time.LocalDate.of(year,month+1,day).toString()),
                    initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth());
            picker.show();
        }));
        space(f,6);
        f.addView(button("Очистить срок",false,()->due.setText("")));
        space(f,10);
        f.addView(label("Статус",13,true,sub));Spinner status=spinner(CrmStore.STATUS);
        int statusIndex=Arrays.asList(CrmStore.STATUS).indexOf(job.status);
        status.setSelection(Math.max(0,statusIndex));f.addView(status);
        dialog(job.id==0?"Новый заказ":job.number, f,"Сохранить",()->{
            job.clientId=selectedClient[0];
            job.title=title.getText().toString();job.description=description.getText().toString();
            job.price=Money.parse(price.getText().toString());job.dueDate=due.getText().toString().trim();
            job.status=CrmStore.STATUS[status.getSelectedItemPosition()];
            long id=store.saveJob(job);render();jobDetails(id);
        });
    }
    private void jobDetails(long id) {
        CrmStore.Job j=store.job(id);
        if(j==null){error(new IllegalArgumentException("Заказ не найден"));return;}
        LinearLayout d=form();
        d.addView(label(j.title,22,true,text));space(d,5);
        d.addView(label(j.clientName+"  ·  "+j.status,14,false,sub));space(d,9);
        if(!j.description.isEmpty()){d.addView(label(j.description,14,false,text));space(d,10);}
        line(d,"Номер",j.number);
        line(d,"Цена",Money.format(j.price));
        line(d,"Оплачено",Money.format(j.paid));
        line(d,"Остаток",Money.format(Money.outstanding(j.price,j.paid)));
        line(d,"Расходы по заказу",Money.format(j.cost));
        line(d,"Доход после расходов",Money.format(j.paid-j.cost));
        if(!j.dueDate.isEmpty())line(d,"Срок",j.dueDate);
        space(d,8);
        d.addView(button("Редактировать заказ",false,()->jobEditor(j,0)));
        space(d,8);
        d.addView(button("Дублировать заказ",false,()->safe(()->{
            long copy=store.duplicateJob(j.id);
            render();jobDetails(copy);
        })));
        space(d,8);
        d.addView(button("+ Оплата",true,()->paymentEditor(j.id,false)));
        space(d,8);
        d.addView(button("Возврат клиенту",false,()->paymentEditor(j.id,true)));
        space(d,8);
        d.addView(button("+ Расход по заказу",false,()->expenseEditor(j.id)));
        space(d,8);
        d.addView(button("Заказ-наряд PDF",false,()->exportPdf(j.id)));
        space(d,14);
        d.addView(label("Фотографии заказа",17,true,text));
        space(d,7);
        d.addView(label("Фото сохраняются только в приложении и включаются в JSON-копию.",12,false,sub));
        space(d,8);
        d.addView(button("+ Фото до работы",false,()->choosePhoto(j.id,true)));
        space(d,6);
        d.addView(button("+ Фото после работы",false,()->choosePhoto(j.id,false)));
        List<CrmStore.Photo> photos=store.photos(j.id);
        photoSection(d,"До работы",photos,"before",j.id);
        photoSection(d,"После работы",photos,"after",j.id);
        space(d,14);
        d.addView(label("История оплат",16,true,text));
        List<CrmStore.Payment> ps=store.payments(j.id);
        if(ps.isEmpty())d.addView(label("Платежей пока нет",13,false,sub));
        for(CrmStore.Payment p:ps) {
            line(d,datetime(p.createdAt)+(p.amount<0?" · возврат":""),Money.format(p.amount));
            if(!p.note.isEmpty())d.addView(label(p.note,12,false,sub));
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(d);
        alertBuilder().setTitle(j.number).setView(scroll)
                .setPositiveButton("Закрыть",null).show();
    }
    private void paymentEditor(long id,boolean refund) {
        LinearLayout f=form();
        CrmStore.Job j=store.job(id);
        if(j==null){error(new IllegalArgumentException("Заказ не найден"));return;}
        f.addView(label(refund?"Оплачено ранее: "+Money.format(j.paid):
                "Осталось оплатить: "+Money.format(Money.outstanding(j.price,j.paid)),14,false,sub));
        space(f,13);
        EditText amount=field(f,"Сумма, ₽","",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText note=field(f,"Комментарий","",InputType.TYPE_CLASS_TEXT);
        dialog(refund?"Возврат клиенту":"Добавить оплату",f,"Сохранить",()->{
            long v=Money.parse(amount.getText().toString());
            if(v<=0)throw new IllegalArgumentException("Сумма должна быть больше нуля");
            store.addPayment(id,refund?-v:v,note.getText().toString());
            render();jobDetails(id);
        });
    }
    private void expenseEditor(long preferredJob) {
        LinearLayout f=form();
        EditText category=field(f,"Категория","Материалы",InputType.TYPE_CLASS_TEXT);
        EditText amount=field(f,"Сумма, ₽","",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText note=field(f,"Комментарий","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        List<CrmStore.Job> all=store.jobs("","",0);
        String[] names=new String[all.size()+1];names[0]="Общий расход (без заказа)";
        int selected=0;
        for(int i=0;i<all.size();i++) {
            CrmStore.Job j=all.get(i); names[i+1]=j.number+" · "+j.title;
            if(j.id==preferredJob)selected=i+1;
        }
        f.addView(label("Привязка к заказу",13,true,sub));
        Spinner spin=spinner(names);spin.setSelection(selected);f.addView(spin);
        dialog("Новый расход",f,"Сохранить",()->{
            long v=Money.parse(amount.getText().toString());
            long jobId=spin.getSelectedItemPosition()==0?0:all.get(spin.getSelectedItemPosition()-1).id;
            store.addExpense(jobId,v,category.getText().toString(),note.getText().toString());
            render();
        });
    }
    private String datetime(long ts) {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm",new Locale("ru"))
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ts));
    }

    /** Uses system document picker; no gallery permissions or network access. */
    private void choosePhoto(long jobId,boolean before) {
        photoJobId=jobId;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i,before?PHOTO_BEFORE:PHOTO_AFTER);
    }
    private void photoSection(LinearLayout parent,String title,List<CrmStore.Photo> photos,String kind,long jobId) {
        parent.addView(label(title,14,true,sub));space(parent,5);
        boolean present=false;
        for(CrmStore.Photo p:photos) {
            if(!kind.equals(p.kind))continue;
            present=true;
            LinearLayout item=row();
            ImageView thumb=new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap b=BitmapFactory.decodeByteArray(p.jpeg,0,p.jpeg.length);
            if(b==null)continue;
            thumb.setImageBitmap(b);
            thumb.setContentDescription("Открыть фото: "+title);
            item.addView(thumb,new LinearLayout.LayoutParams(dp(102),dp(86)));
            LinearLayout actions=column();actions.setPadding(dp(10),0,0,0);
            actions.addView(button("Посмотреть",false,()->showPhoto(p)));
            space(actions,5);
            actions.addView(button("Удалить",false,()->alertBuilder()
                    .setTitle("Удалить фотографию?")
                    .setMessage("Фотография будет удалена из этого заказа.")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Удалить",(dialog,which)->safe(()->{
                        store.removePhoto(p.id,jobId);
                        render();jobDetails(jobId);
                    })).show()));
            item.addView(actions,new LinearLayout.LayoutParams(0,-2,1));
            parent.addView(item);space(parent,8);
        }
        if(!present)parent.addView(label("Фотографий пока нет",12,false,sub));
    }
    private void showPhoto(CrmStore.Photo p) {
        Bitmap b=BitmapFactory.decodeByteArray(p.jpeg,0,p.jpeg.length);
        if(b==null)throw new IllegalArgumentException("Фотография повреждена");
        ImageView large=new ImageView(this);
        large.setImageBitmap(b);
        large.setAdjustViewBounds(true);
        large.setScaleType(ImageView.ScaleType.FIT_CENTER);
        alertBuilder().setTitle("Фото "+("before".equals(p.kind)?"до":"после"))
                .setView(large).setPositiveButton("Закрыть",null).show();
    }
    private byte[] photoJpeg(Uri uri) {
        byte[] raw=readBytes(uri,16*1024*1024);
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(raw,0,raw.length,options);
        if(options.outWidth<1||options.outHeight<1)
            throw new IllegalArgumentException("Не удалось распознать изображение");
        options.inJustDecodeBounds=false;
        int sampling=1;
        while(Math.max(options.outWidth,options.outHeight)/sampling>1600)
            sampling*=2;
        options.inSampleSize=sampling;
        Bitmap bitmap=BitmapFactory.decodeByteArray(raw,0,raw.length,options);
        if(bitmap==null)throw new IllegalArgumentException("Формат изображения не поддерживается");
        try {
            int rotation=0;
            try {
                ExifInterface exif=new ExifInterface(new ByteArrayInputStream(raw));
                switch(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)) {
                    case ExifInterface.ORIENTATION_ROTATE_90: rotation=90;break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rotation=180;break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rotation=270;break;
                }
            } catch(Exception ignored) { /* No EXIF is legitimate. */ }
            if(rotation!=0) {
                Matrix m=new Matrix();m.postRotate(rotation);
                Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);
                if(rotated!=bitmap) {bitmap.recycle();bitmap=rotated;}
            }
            int quality=86;
            byte[] out;
            do {
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                if(!bitmap.compress(Bitmap.CompressFormat.JPEG,quality,buffer))
                    throw new IllegalArgumentException("Не удалось обработать фото");
                out=buffer.toByteArray();
                quality-=10;
            } while(out.length>2*1024*1024 && quality>=40);
            if(out.length>2*1024*1024)
                throw new IllegalArgumentException("Фото слишком большое после сжатия");
            return out;
        } finally {
            bitmap.recycle();
        }
    }
    private byte[] readBytes(Uri uri,int maxBytes) {
        try(InputStream input=getContentResolver().openInputStream(uri);
            ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if(input==null)throw new IllegalArgumentException("Нет доступа к выбранному файлу");
            byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1) {
                if(out.size()+n>maxBytes)throw new IllegalArgumentException("Файл более 16 МБ");
                out.write(buffer,0,n);
            }
            return out.toByteArray();
        } catch(java.io.IOException e) {
            throw new IllegalStateException("Ошибка чтения фото",e);
        }
    }

    private void exportPdf(long jobId) {
        pdfJobId=jobId;
        CrmStore.Job j=store.job(jobId);
        if(j==null)return;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE,j.number+".pdf");
        startActivityForResult(i,EXPORT_PDF);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(requestCode==PHOTO_BEFORE||requestCode==PHOTO_AFTER) {
            safe(()->{
                long id=photoJobId;
                if(store.job(id)==null)throw new IllegalArgumentException("Заказ для фотографии не найден");
                byte[] jpeg=photoJpeg(uri);
                store.addPhoto(id,requestCode==PHOTO_BEFORE?"before":"after",jpeg);
                Toast.makeText(this,"Фото сохранено",Toast.LENGTH_SHORT).show();
                render();jobDetails(id);
            });
        } else if(requestCode==EXPORT_STREAM) {
            encryptedBackupPrompt(uri,true,true);
        } else if(requestCode==IMPORT_STREAM) {
            alertBuilder().setTitle("Восстановить потоковую копию?")
                .setMessage("Все данные Preview будут заменены. При неправильном пароле или ошибке архива изменения будут отменены. Сначала сохраните копию текущих данных.")
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Продолжить",(d,w)->encryptedBackupPrompt(uri,false,true))
                .show();
        } else if(requestCode==EXPORT_SECURE) {
            encryptedBackupPrompt(uri,true,false);
        } else if(requestCode==IMPORT_SECURE) {
            alertBuilder().setTitle("Заменить данные из защищённой копии?")
                .setMessage("Текущие клиенты, заказы, оплаты, расходы и фотографии будут заменены после расшифровки. Сначала сохраните резервную копию текущих данных.")
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Продолжить",(dialog,which)->encryptedBackupPrompt(uri,false,false))
                .show();
        } else if(requestCode==IMPORT_JSON) {
            alertBuilder().setTitle("Заменить все данные?")
                .setMessage("Это незашифрованный формат. Текущие клиенты, заказы, оплаты, расходы и фотографии будут заменены. Сначала сохраните текущую копию.")
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Заменить",(d,w)->new Thread(()->{
                    try {
                        String input=readText(uri,80*1024*1024);
                        store.restoreJson(input);
                        runOnUiThread(()->{
                            Toast.makeText(this,"Резервная копия восстановлена",Toast.LENGTH_LONG).show();
                            render();
                        });
                    } catch(Exception e) {
                        runOnUiThread(()->error(e));
                    }
                },"mastercrm-json-import").start()).show();
        } else if(requestCode==EXPORT_JSON) {
            new Thread(()->{
                try {
                    String json=store.exportJson();
                    try(OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
                        if(out==null)throw new IllegalStateException("Не удалось открыть файл");
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    runOnUiThread(()->Toast.makeText(this,
                            "JSON экспорт завершён",Toast.LENGTH_SHORT).show());
                } catch(Exception e) {
                    runOnUiThread(()->error(e));
                }
            },"mastercrm-json-export").start();
        } else if(requestCode==EXPORT_PDF) safe(()->writePdf(uri,pdfJobId));
    }
    /** All encryption work runs off the UI thread; password is never persisted. */
    private void encryptedBackupPrompt(Uri uri,boolean exporting,boolean streaming) {
        LinearLayout form=form();
        form.addView(label(exporting
            ? "Создайте пароль длиной от 12 символов. Если потеряете его, восстановить копию невозможно."
            : "Введите пароль защищённой резервной копии.",13,false,sub));
        space(form,12);
        EditText pwd=field(form,"Пароль","",
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwd.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        EditText repeat=null;
        if(exporting) {
            repeat=field(form,"Повторите пароль","",
                    InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
            repeat.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        final EditText repeatField=repeat;
        dialog(exporting?"Защитить резервную копию":"Расшифровать копию",form,
                exporting?"Сохранить":"Восстановить",()->{
            char[] pass=pwd.getText().toString().toCharArray();
            char[] verify=repeatField==null?null:repeatField.getText().toString().toCharArray();
            try {
                if(pass.length<12||pass.length>1024)
                    throw new IllegalArgumentException("Нужно от 12 до 1024 символов");
                if(verify!=null&&!Arrays.equals(pass,verify))
                    throw new IllegalArgumentException("Пароли не совпадают");
                // Ownership of the only active copy of pass transfers to this worker.
                final char[] workerPassword=pass;
                pass=null;
                pwd.setText("");
                if(repeatField!=null)repeatField.setText("");
                new Thread(()->{
                    try {
                        if(streaming) {
                            if(exporting) {
                                try(OutputStream raw=getContentResolver().openOutputStream(uri,"w")) {
                                    if(raw==null)throw new IllegalStateException("Не удалось открыть файл");
                                    try(OutputStream protectedStream=StreamBackupCrypto.encrypt(raw,workerPassword)) {
                                        StreamCrmArchive.write(store,protectedStream);
                                    }
                                }
                            } else {
                                try(InputStream raw=getContentResolver().openInputStream(uri)) {
                                    if(raw==null)throw new IllegalStateException("Не удалось открыть файл");
                                    try(InputStream clear=StreamBackupCrypto.decrypt(raw,workerPassword)) {
                                        StreamCrmArchive.restore(store,clear);
                                    }
                                }
                            }
                        } else if(exporting) {
                            byte[] data=BackupCrypto.encrypt(store.exportJson(),workerPassword);
                            try(OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
                                if(out==null)throw new IllegalStateException("Не удалось открыть файл");
                                out.write(data);
                            } finally {Arrays.fill(data,(byte)0);}
                        } else {
                            byte[] data=readBytes(uri,BackupCrypto.MAX_BACKUP_BYTES);
                            try {
                                String json=BackupCrypto.decrypt(data,workerPassword);
                                store.restoreJson(json);
                            } finally {Arrays.fill(data,(byte)0);}
                        }
                        runOnUiThread(()->{
                            Toast.makeText(this,exporting?"Защищённая копия сохранена":
                                    "Копия успешно восстановлена",Toast.LENGTH_LONG).show();
                            if(!exporting)render();
                        });
                    } catch(Exception ex) {
                        runOnUiThread(()->error(ex));
                    } finally {
                        Arrays.fill(workerPassword,'\0');
                    }
                },"mastercrm-encrypted-backup").start();
            } finally {
                if(pass!=null)Arrays.fill(pass,'\0');
                if(verify!=null)Arrays.fill(verify,'\0');
            }
        });
    }

    private String readText(Uri uri,int maxBytes) {
        StringBuilder out=new StringBuilder();
        try(InputStream input=getContentResolver().openInputStream(uri);
            BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            char[] buf=new char[4096];int n;
            while((n=reader.read(buf))!=-1) {
                if(out.length()+n>maxBytes)throw new IllegalArgumentException("Файл слишком большой");
                out.append(buf,0,n);
            }
        }catch(java.io.IOException e){throw new IllegalStateException("Ошибка чтения копии",e);}
        return out.toString();
    }
    private void writePdf(Uri uri,long id) {
        CrmStore.Job j=store.job(id);
        if(j==null)throw new IllegalArgumentException("Заказ не найден");
        PdfDocument pdf=new PdfDocument();
        PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,1).create());
        android.graphics.Canvas canvas=page.getCanvas();
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(18,31,51));
        p.setTextSize(24);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        canvas.drawText("MasterCRM — заказ-наряд",42,66,p);
        p.setTextSize(15);p.setTypeface(Typeface.DEFAULT);
        int y=105;
        String[] lines={"Номер: "+j.number,
                "Дата: "+datetime(j.createdAt),
                "Клиент: "+j.clientName,
                "Телефон: "+j.clientPhone,
                "Работа: "+j.title,
                "Статус: "+j.status,
                "Срок: "+j.dueDate,
                "Стоимость: "+Money.format(j.price),
                "Оплачено: "+Money.format(j.paid),
                "Долг: "+Money.format(Money.outstanding(j.price,j.paid))};
        for(String line:lines) {canvas.drawText(shorten(line,68),42,y,p);y+=31;}
        y+=18;p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        canvas.drawText("Описание работ:",42,y,p);
        y+=28;p.setTypeface(Typeface.DEFAULT);
        for(String line:wrapText(j.description,60)){
            if(y>740)break;
            canvas.drawText(line,42,y,p);y+=23;
        }
        p.setTextSize(11);p.setColor(Color.GRAY);
        canvas.drawText("Информационный документ, не кассовый чек.",42,798,p);
        pdf.finishPage(page);
        try(OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
            if(out==null)throw new IllegalStateException("Нет доступа к выбранному файлу");
            pdf.writeTo(out);
        }catch(java.io.IOException e) {throw new IllegalStateException("Ошибка сохранения PDF",e);}
        finally {pdf.close();}
        Toast.makeText(this,"PDF сохранён",Toast.LENGTH_SHORT).show();
    }
    private String shorten(String s,int max) {
        return s.length()<=max?s:s.substring(0,max-1)+"…";
    }
    private List<String> wrapText(String s,int width) {
        List<String> result=new ArrayList<>();
        if(s==null||s.trim().isEmpty())return result;
        for(String paragraph:s.split("\\n",-1)) {
            StringBuilder line=new StringBuilder();
            for(String word:paragraph.trim().split("\\s+")) {
                if(line.length()+word.length()+1>width&&line.length()>0){
                    result.add(line.toString());line.setLength(0);
                }
                if(word.length()>width)word=shorten(word,width);
                if(line.length()>0)line.append(' ');
                line.append(word);
            }
            result.add(line.toString());
        }
        return result;
    }
}
