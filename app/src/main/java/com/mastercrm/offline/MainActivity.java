package com.mastercrm.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
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
    private static final int EXPORT_JSON = 21, IMPORT_JSON = 22, EXPORT_PDF = 23;
    private static final String[] TABS = {"Обзор","Заказы","Клиенты","Финансы","Ещё"};
    private CrmStore store;
    private int tab = 0, page = 0;
    private String search = "", filter = "";
    private boolean dark;
    private int bg, panel, text, sub, accent, stroke, green, red;
    private long pdfJobId;
    private LinearLayout body;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new CrmStore(this);
        dark = getPreferences(MODE_PRIVATE).getBoolean("dark", false);
        palette();
        render();
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
    private void navigate(int i) {tab=i;page=0;search="";filter="";render();}
    private void render() {
        palette();
        LinearLayout base=column();base.setBackgroundColor(bg);setContentView(base);
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
            n.setMinHeight(dp(52));
            n.setBackground(i==tab?shape(bg,0,11):null);
            n.setOnClickListener(v->navigate(at));
            nav.addView(n,new LinearLayout.LayoutParams(0,dp(52),1));
        }
        base.addView(nav);
    }

    private void dashboard() {
        CrmStore.Summary s=store.summary();
        LinearLayout hero=card(body);
        hero.addView(label("Получено за всё время",14,false,sub));space(hero,5);
        hero.addView(label(Money.format(s.totalIncome),31,true,green));
        space(hero,10);
        line(hero,"Расходы",Money.format(s.totalExpense));
        line(hero,"Кассовая прибыль",Money.format(s.totalIncome-s.totalExpense));
        LinearLayout stats=card(body);heading(stats,"В работе");
        line(stats,"Активные заказы",""+s.activeJobs);
        line(stats,"Заказов сегодня",""+s.todayJobs);
        line(stats,"Срок сегодня или просрочен",""+s.dueJobs);
        line(stats,"Клиенты",""+s.totalClients);
        line(stats,"Неоплаченные суммы",Money.format(s.totalDebt));
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
        List<CrmStore.Client> list=store.clients(search,page);
        if(list.isEmpty()) {empty("Клиенты не найдены");return;}
        for(CrmStore.Client c:list) {
            LinearLayout card=card(body);
            card.addView(label(c.name,18,true,text));
            space(card,6);card.addView(label(c.phone.isEmpty()?"Без телефона":c.phone,14,false,sub));
            card.setOnClickListener(v->clientDetails(c.id));
        }
        if(page>0)body.addView(button("← Предыдущая страница",false,()->{page--;render();}));
        if(list.size()==100)body.addView(button("Следующая страница →",false,()->{page++;render();}));
    }
    private void finance() {
        CrmStore.Summary s=store.summary();
        LinearLayout summary=card(body);heading(summary,"Денежный поток");
        line(summary,"Поступления",Money.format(s.totalIncome));
        line(summary,"Расходы",Money.format(s.totalExpense));
        line(summary,"Кассовая прибыль",Money.format(s.totalIncome-s.totalExpense));
        line(summary,"Долги клиентов",Money.format(s.totalDebt));
        summary.addView(label("Показатели за всё время, без налогов и начислений.",12,false,sub));
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
    private void settings() {
        LinearLayout c=card(body);heading(c,"Оформление");
        c.addView(button(dark?"Светлая тема":"Тёмная тема",false,()->{
            dark=!dark;getPreferences(MODE_PRIVATE).edit().putBoolean("dark",dark).apply();render();
        }));
        LinearLayout backup=card(body);heading(backup,"Резервные копии");
        backup.addView(label("Формат JSON. Копия содержит персональные данные клиентов: храните файл в защищённом месте.",13,false,sub));
        space(backup,12);
        backup.addView(button("Экспорт данных",true,()->{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE,"mastercrm-backup-"+CrmStore.today()+".json");
            startActivityForResult(i,EXPORT_JSON);
        }));
        space(backup,8);
        backup.addView(button("Восстановить из JSON",false,()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
            startActivityForResult(i,IMPORT_JSON);
        }));
        LinearLayout about=card(body);heading(about,"О приложении");
        about.addView(label("MasterCRM Offline Preview · 0.1.0",14,true,text));
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
        String[] names=new String[clients.size()];
        int select=0;
        long desired=job.id>0?job.clientId:preferredClientId;
        for(int i=0;i<clients.size();i++){
            CrmStore.Client c=clients.get(i);
            names[i]=c.name+(c.phone.isEmpty()?"":" · "+c.phone);
            if(c.id==desired)select=i;
        }
        f.addView(label("Клиент",13,true,sub));Spinner clientSpin=spinner(names);
        clientSpin.setSelection(select);f.addView(clientSpin);space(f,12);
        EditText title=field(f,"Работа / услуга",job.title,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText description=field(f,"Описание, материалы и заметки",job.description,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText price=field(f,"Стоимость, ₽",java.math.BigDecimal.valueOf(job.price,2).toPlainString(),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText due=field(f,"Срок: ГГГГ-ММ-ДД (необязательно)",job.dueDate,InputType.TYPE_CLASS_DATETIME);
        f.addView(label("Статус",13,true,sub));Spinner status=spinner(CrmStore.STATUS);
        int statusIndex=Arrays.asList(CrmStore.STATUS).indexOf(job.status);
        status.setSelection(Math.max(0,statusIndex));f.addView(status);
        dialog(job.id==0?"Новый заказ":job.number, f,"Сохранить",()->{
            job.clientId=clients.get(clientSpin.getSelectedItemPosition()).id;
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
        if(requestCode==IMPORT_JSON) {
            safe(()->{
                String input=readText(uri,30*1024*1024);
                alertBuilder().setTitle("Заменить все данные?")
                    .setMessage("Будут удалены текущие клиенты, заказы, оплаты и расходы в этой Preview-версии. Сначала сохраните экспорт текущих данных. Продолжить?")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Заменить", (d,w)->safe(()->{
                        store.restoreJson(input);
                        Toast.makeText(this,"Резервная копия восстановлена",Toast.LENGTH_LONG).show();
                        render();
                    })).show();
            });
        } else if(requestCode==EXPORT_JSON) {
            safe(()->{
                String json=store.exportJson();
                try(OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
                    if(out==null)throw new IllegalStateException("Не удалось открыть файл");
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }catch(java.io.IOException e){throw new IllegalStateException(e);}
                Toast.makeText(this,"Экспорт завершён",Toast.LENGTH_SHORT).show();
            });
        } else if(requestCode==EXPORT_PDF) safe(()->writePdf(uri,pdfJobId));
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
