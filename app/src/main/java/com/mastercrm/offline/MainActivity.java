package com.mastercrm.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
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
import android.graphics.Rect;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
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
import java.io.File;
import java.io.IOException;
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
    private static final int EXPORT_JSON = 21, IMPORT_JSON = 22, EXPORT_PDF = 23, PHOTO_BEFORE = 24, PHOTO_AFTER = 25, EXPORT_SECURE = 26, IMPORT_SECURE = 27, EXPORT_STREAM = 28, IMPORT_STREAM = 29, VERIFY_STREAM = 30, CAMERA_BEFORE = 31, CAMERA_AFTER = 32;
    private static final String[] TABS = {"Обзор","Заказы","Клиенты","Финансы","Ещё"};
    private CrmStore store;
    private int tab = 0, page = 0;
    private String search = "", filter = "";
    private int dueMode = 0; // 0=all; 1=overdue; 2=next seven days
    private boolean unpaidOnly=false; // non-archived orders with a positive balance
    private String period = "month";
    private boolean regularOnly=false;
    private boolean dark;
    private int bg, panel, text, sub, accent, stroke, green, red;
    private long pdfJobId;
    private long photoJobId;
    private String pendingCameraPath="";
    private LinearLayout body;
    private AlertDialog activeJobDialog;
    private long detailJobId, detailClientId;
    private int detailClientPage;
    private AlertDialog clientDialog;
    private ScrollView jobDetailScroll, clientDetailScroll;
    private AlertDialog editorDialog;
    private LinearLayout editorContent;
    private String editorType = "";
    private long editorId;
    private long[] editorClient;
    private Bundle restoringDraft;
    private EditText searchInput;
    private ScrollView screenScroll;

    private void beginEditor(String type, long id) {
        editorType=type; editorId=id; editorClient=null;
    }
    private void saveFields(View view, Bundle values, String path) {
        if(view instanceof EditText) {
            EditText edit=(EditText)view;
            values.putString(path,edit.getText().toString());
            values.putInt(path+"/start",edit.getSelectionStart());
            values.putInt(path+"/end",edit.getSelectionEnd());
            if(view.hasFocus())values.putString("focus",path);
        }
        else if(view instanceof Spinner) values.putInt(path,((Spinner)view).getSelectedItemPosition());
        else if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)saveFields(group.getChildAt(i),values,path+"/"+i);
        }
    }
    private void restoreFields(View view, Bundle values, String path) {
        if(view instanceof EditText && values.containsKey(path)) {
            EditText edit=(EditText)view;
            edit.setText(values.getString(path));
            int length=edit.length();
            edit.setSelection(Math.max(0,Math.min(length,values.getInt(path+"/start"))),
                    Math.max(0,Math.min(length,values.getInt(path+"/end"))));
            if(path.equals(values.getString("focus")))view.requestFocus();
        }
        else if(view instanceof Spinner && values.containsKey(path))((Spinner)view).setSelection(values.getInt(path));
        else if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)restoreFields(group.getChildAt(i),values,path+"/"+i);
        }
    }
    private void restoreEditor(Bundle draft) {
        if(draft==null)return;
        restoringDraft=draft;
        long id=draft.getLong("id");
        switch(draft.getString("type","")) {
            case "client": clientEditor(id==0?null:store.client(id)); break;
            case "job": jobEditor(id==0?null:store.job(id),draft.getLong("client")); break;
            case "payment": paymentEditor(id,false); break;
            case "refund": paymentEditor(id,true); break;
            case "expense": expenseEditor(id); break;
        }
        restoringDraft=null;
    }

    /** Let the dialog resize above IME; ScrollView then keeps the cursor visible. */
    private void prepareFormWindow(AlertDialog dialog, ScrollView scroll) {
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        if(dialog.getWindow()!=null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        scroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            View focused=scroll.findFocus();
            if(focused instanceof EditText) focused.post(()->{
                Rect rect=new Rect();focused.getFocusedRect(rect);
                focused.requestRectangleOnScreen(rect,false);
            });
        });
    }

    private void closeJobDialog() {
        AlertDialog dialog=activeJobDialog;
        activeJobDialog=null;detailJobId=0;
        if(dialog!=null&&dialog.isShowing())dialog.dismiss();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new CrmStore(this);
        // Android 15 enforces edge-to-edge. Handle all system bar insets in our
        // root view so the bottom tabs cannot overlap the OS Home/taskbar area.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if(state!=null) {
            photoJobId=state.getLong("photoJobId",0);
            pdfJobId=state.getLong("pdfJobId",0);
            pendingCameraPath=state.getString("pendingCameraPath","");
        }
        dark = getPreferences(MODE_PRIVATE).getBoolean("dark", false);
        String saved=getPreferences(MODE_PRIVATE).getString("financial_period","month");
        period=Arrays.asList("day","week","month","all").contains(saved)?saved:"month";
        regularOnly=getPreferences(MODE_PRIVATE).getBoolean("regular_only",false);
        if(state!=null) {
            tab=state.getInt("tab");page=state.getInt("page");
            search=state.getString("search","");filter=state.getString("filter","");
            dueMode=state.getInt("dueMode");unpaidOnly=state.getBoolean("unpaidOnly");
        }
        palette();
        render();
        if(state!=null) {
            screenScroll.post(()->screenScroll.scrollTo(0,state.getInt("scroll")));
            if(state.getBundle("draft")!=null)restoreEditor(state.getBundle("draft"));
            else {
                long clientId=state.getLong("detailClient"),jobId=state.getLong("detailJob");
                if(clientId>0 && store.client(clientId)!=null) {
                    clientDetails(clientId,state.getInt("clientPage"));
                    clientDetailScroll.post(()->clientDetailScroll.scrollTo(0,state.getInt("clientScroll")));
                }
                if(jobId>0 && store.job(jobId)!=null) {
                    jobDetails(jobId);
                    jobDetailScroll.post(()->jobDetailScroll.scrollTo(0,state.getInt("jobScroll")));
                }
            }
        }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putLong("photoJobId",photoJobId);
        out.putLong("pdfJobId",pdfJobId);
        out.putString("pendingCameraPath",pendingCameraPath);
        out.putInt("tab",tab);out.putInt("page",page);
        out.putString("search",searchInput==null?search:searchInput.getText().toString());
        out.putString("filter",filter);out.putInt("dueMode",dueMode);
        out.putBoolean("unpaidOnly",unpaidOnly);
        out.putInt("scroll",screenScroll==null?0:screenScroll.getScrollY());
        out.putLong("detailClient",detailClientId);out.putLong("detailJob",detailJobId);
        out.putInt("clientPage",detailClientPage);
        out.putInt("clientScroll",clientDetailScroll==null?0:clientDetailScroll.getScrollY());
        out.putInt("jobScroll",jobDetailScroll==null?0:jobDetailScroll.getScrollY());
        if(editorDialog!=null && editorDialog.isShowing() && !editorType.isEmpty()) {
            Bundle draft=new Bundle();draft.putString("type",editorType);draft.putLong("id",editorId);
            if(editorClient!=null)draft.putLong("client",editorClient[0]);
            saveFields(editorContent,draft,"fields");out.putBundle("draft",draft);
        }
        super.onSaveInstanceState(out);
    }
    @Override public void onDestroy() {
        closeJobDialog();
        if(clientDialog!=null)clientDialog.dismiss();
        if(editorDialog!=null)editorDialog.dismiss();
        if(store!=null)store.close();
        super.onDestroy();
    }
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
        androidx.core.view.WindowInsetsControllerCompat controller=
                WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);
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
        b.setTextColor(primary ? (dark ? Color.parseColor("#111827") : Color.WHITE) : text);
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
        x.addView(b,new LinearLayout.LayoutParams(0,-2,1));
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
    private void navigate(int i) {if(tab==i)return;tab=i;page=0;search="";filter="";dueMode=0;unpaidOnly=false;render();}
    private void showDueOrders(int mode) {
        tab=1;page=0;search="";filter="";dueMode=mode;unpaidOnly=false;render();
    }
    private void render() {
        palette();
        LinearLayout base=column();
        base.setBackgroundColor(bg);
        ViewCompat.setOnApplyWindowInsetsListener(base,(view,insets)->{
            androidx.core.graphics.Insets safe=insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left,safe.top,safe.right,safe.bottom);
            // Give the focused search field the available height in landscape.
            if(base.getChildCount()==3) {
                int visibility=insets.isVisible(WindowInsetsCompat.Type.ime())?View.GONE:View.VISIBLE;
                base.getChildAt(0).setVisibility(visibility);
                base.getChildAt(2).setVisibility(visibility);
            }
            return insets;
        });
        searchInput=null;
        setContentView(base);
        ViewCompat.requestApplyInsets(base);
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
        ScrollView sc=new ScrollView(this);sc.setFillViewport(true);screenScroll=sc;
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
            nav.addView(n,new LinearLayout.LayoutParams(0,-2,1));
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
        LinearLayout options=column();
        boolean twoRows=getResources().getConfiguration().fontScale>1.15f
                || getResources().getConfiguration().screenWidthDp<360;
        LinearLayout buttons=row();options.addView(buttons);
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
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
            if(twoRows && i==2) {
                space(options,6);buttons=row();options.addView(buttons);
            }
            if((twoRows?i%2:i)>0)p.setMargins(dp(4),0,0,0);
            buttons.addView(b,p);
        }
        target.addView(options);
        space(target,12);
    }
    private void dashboard() {
        CrmStore.Summary s=store.summary();
        CrmStore.PeriodMetrics cash=store.periodMetrics(period);
        List<CrmStore.Job> recentJobs=store.jobs("","",0);
        String backupWarning=BackupReminder.warning(s.totalClients>0,
                getPreferences(MODE_PRIVATE).getLong("last_verified_stream_backup",0),
                System.currentTimeMillis());
        if(!backupWarning.isEmpty()) {
            LinearLayout reminder=card(body);
            heading(reminder,"Защита данных");
            reminder.addView(label(backupWarning,13,false,sub));
            space(reminder,8);
            reminder.addView(button("Создать защищённую копию",false,()->navigate(4)));
        }
        if(s.totalClients==0) {
            LinearLayout welcome=card(body);
            heading(welcome,"Добро пожаловать!");
            welcome.addView(label("MasterCRM поможет вести заказы, оплаты и расходы без интернета.",15,false,text));
            space(welcome,12);
            welcome.addView(label("1. Добавьте первого клиента.",14,true,text));
            space(welcome,5);
            welcome.addView(label("2. Создайте заказ и укажите стоимость.",14,false,sub));
            space(welcome,5);
            welcome.addView(label("3. Отмечайте оплаты и сохраняйте защищённую резервную копию.",14,false,sub));
            space(welcome,12);
            welcome.addView(button("Добавить первого клиента",true,()->clientEditor(null)));
        } else if(recentJobs.isEmpty()) {
            LinearLayout welcome=card(body);
            heading(welcome,"Пора создать первый заказ");
            welcome.addView(label("Клиенты уже добавлены. Создайте работу, укажите стоимость и срок — затем отмечайте оплаты.",14,false,sub));
            space(welcome,12);
            welcome.addView(button("Создать первый заказ",true,()->jobEditor(null,0)));
        }
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
        if(s.totalClients==0) {
            quick.addView(button("Добавить клиента",true,()->clientEditor(null)));
        } else {
            quick.addView(button("Новый заказ",true,()->jobEditor(null,0)));
            space(quick,8);
            quick.addView(button("Добавить клиента",false,()->clientEditor(null)));
        }
        space(quick,8);
        quick.addView(button("Расход",false,()->expenseEditor(0)));
        LinearLayout recent=card(body);heading(recent,"Последние заказы");
        if(recentJobs.isEmpty())recent.addView(label("Заказов пока нет. Используйте подсказку выше, чтобы начать.",14,false,sub));
        for(int i=0;i<Math.min(5,recentJobs.size());i++) recent.addView(jobRow(recentJobs.get(i)));
        if(recentJobs.size()>5){space(recent,8);recent.addView(button("Все заказы",false,()->navigate(1)));}
    }
    private void searchBar(LinearLayout target,String hint) {
        EditText edit=new EditText(this);edit.setSingleLine(true);
        searchInput=edit;edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        edit.setText(search);edit.setHint(hint);edit.setTextColor(text);edit.setHintTextColor(sub);
        edit.setTextSize(15);edit.setPadding(dp(14),dp(10),dp(14),dp(10));
        edit.setBackground(shape(panel,stroke,12));
        edit.setMinHeight(dp(52));
        target.addView(edit,new LinearLayout.LayoutParams(-1,-2));
        Runnable submit=()->{
            search=edit.getText().toString().trim();page=0;
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(edit.getWindowToken(),0);
            render();
        };
        edit.setOnEditorActionListener((v,action,event)->{
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){submit.run();return true;}
            return false;
        });
        edit.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) {search=s.toString();}
            public void afterTextChanged(android.text.Editable s) {}
        });
        space(target,8);
        target.addView(button("Найти",true,submit));
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
        searchBar(body,"Номер, услуга, описание, клиент или телефон");
        LinearLayout filters=card(body);
        Spinner spin=spinner(new String[]{"Все",CrmStore.STATUS[0],CrmStore.STATUS[1],CrmStore.STATUS[2],CrmStore.STATUS[3],CrmStore.STATUS[4]});
        if(!filter.isEmpty())spin.setSelection(Arrays.asList(CrmStore.STATUS).indexOf(filter)+1);
        filters.addView(spin);
        filters.addView(button("Применить фильтр",false,()->{
            filter=spin.getSelectedItemPosition()==0?"":CrmStore.STATUS[spin.getSelectedItemPosition()-1];
            page=0;render();
        }));
        space(filters,8);
        filters.addView(button(unpaidOnly?"✓ С задолженностью · показать все":
                "Показать заказы с задолженностью",unpaidOnly,()->{
            unpaidOnly=!unpaidOnly;
            page=0;render();
        }));
        List<CrmStore.Job> list=unpaidOnly?store.unpaidJobs(search,filter,page):
                store.jobs(search,filter,page);
        if(list.isEmpty()) {
            empty(unpaidOnly?"Неоплаченных заказов по фильтрам нет":"Заказы не найдены");
            if(page>0)body.addView(button("← Предыдущая страница",false,
                    ()->{page--;render();}));
            return;
        }
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
        status.setMaxWidth(dp(160));
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
        LinearLayout sums=column();
        sums.addView(label("Стоимость: "+Money.format(j.price),15,true,text));
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
        long lastVerified=getPreferences(MODE_PRIVATE)
                .getLong("last_verified_stream_backup",0);
        String backupStatus=lastVerified<=0?"Нет автоматически проверенных экспортов на этом устройстве":
                "Последняя автоматически проверенная копия: "+
                Instant.ofEpochMilli(lastVerified).atZone(ZoneId.systemDefault())
                        .toLocalDate().toString();
        backup.addView(label(backupStatus,13,true,text));
        space(backup,8);
        backup.addView(label("Рекомендуется потоковая защищённая копия .mcrm5: сохраняет фото отдельно, не загружает всю базу в память. Пароль не сохраняется.",13,false,sub));
        space(backup,6);
        backup.addView(label("После сохранения программа повторно открывает и проверяет копию. Храните файл отдельно от телефона и не теряйте пароль. Дата не гарантирует, что файл по-прежнему существует.",12,false,sub));
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
        space(backup,8);
        backup.addView(button("Проверить копию .mcrm5 без восстановления",false,()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i,VERIFY_STREAM);
        }));
        space(backup,8);
        backup.addView(label("Проверка расшифрует архив и проверит все записи, суммы и фото, но оставит текущую базу без изменений.",12,false,sub));
        space(backup,14);
        backup.addView(button("Дополнительно: старые форматы (.mcrm и JSON)",false,
                ()->legacyBackupOptions()));
        LinearLayout about=card(body);heading(about,"О приложении");
        about.addView(label("MasterCRM Offline Preview · "+installedVersion(),14,true,text));
        space(about,8);
        about.addView(label("Работает без сети, серверов и платных сервисов. Отдельный пакет не заменяет установленную MasterCRM 0.9.4.",13,false,sub));
        space(about,6);
        about.addView(label("Не является фискальной системой; документы не заменяют кассовые чеки.",12,false,sub));
    }
    /** Keep legacy backup compatibility without crowding the primary safe path. */
    private void legacyBackupOptions() {
        AlertDialog legacyDialog=alertBuilder().setTitle("Дополнительные форматы")
                .setPositiveButton("Закрыть",null).create();
        LinearLayout old=form();
        space(backup,16);
        old.addView(label("Старый формат .mcrm: также зашифрован, но для больших архивов использует больше памяти. Совместимость сохранена.",12,false,sub));
        space(backup,10);
        old.addView(button("Создать старую копию .mcrm",false,()->{
            legacyDialog.dismiss();
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE,"mastercrm-"+CrmStore.today()+BackupCrypto.EXTENSION);
            startActivityForResult(i,EXPORT_SECURE);
        }));
        space(backup,8);
        old.addView(button("Восстановить старую копию .mcrm",false,()->{
            legacyDialog.dismiss();
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i,IMPORT_SECURE);
        }));
        space(backup,16);
        old.addView(label("Совместимость: старый JSON без шифрования. Такой файл содержит имена, телефоны, суммы и фотографии открытым текстом.",13,false,sub));
        space(backup,10);
        old.addView(button("Экспорт JSON (без защиты)",false,()->{
            legacyDialog.dismiss();
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE,"mastercrm-backup-"+CrmStore.today()+".json");
            startActivityForResult(i,EXPORT_JSON);
        }));
        space(backup,8);
        old.addView(button("Импорт JSON (без защиты)",false,()->{
            legacyDialog.dismiss();
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
            startActivityForResult(i,IMPORT_JSON);
        }));

        ScrollView content=new ScrollView(this);
        content.addView(old);
        legacyDialog.setView(content);
        legacyDialog.show();
    }
    private void empty(String title) {
        LinearLayout c=card(body);c.addView(label(title,17,true,text));
        space(c,10);
        c.addView(button("Сбросить поиск",false,()->{search="";filter="";page=0;render();}));
    }
    private Spinner spinner(String[] values) {
        // Activity uses a light base theme; modal controls must follow the
        // selected palette too, including their text, arrow and popup rows.
        android.content.Context themed=new android.view.ContextThemeWrapper(this,dark
                ? android.R.style.Theme_Material : android.R.style.Theme_Material_Light);
        Spinner s=new Spinner(themed);
        ArrayAdapter<String> a=new ArrayAdapter<>(themed,android.R.layout.simple_spinner_dropdown_item,values);
        s.setPopupBackgroundDrawable(shape(panel,stroke,10));
        s.setAdapter(a);s.setMinimumHeight(dp(48));return s;
    }
    private EditText field(LinearLayout holder,String label,String value,int inputType) {
        holder.addView(label(label,13,true,sub));space(holder,4);
        EditText edit=new EditText(this);
        edit.setText(value==null?"":value);
        edit.setContentDescription(label);
        edit.setTextColor(text);edit.setHintTextColor(sub);edit.setTextSize(16);
        edit.setInputType(inputType);
        edit.setMinHeight(dp(48));
        if((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0) {
            edit.setMinLines(2);edit.setMaxLines(6);edit.setGravity(Gravity.TOP|Gravity.START);
        } else edit.setSingleLine(true);
        edit.setOnFocusChangeListener((v,focused)->{
            if(focused)v.post(()->{Rect rect=new Rect();v.getFocusedRect(rect);
                v.requestRectangleOnScreen(rect,false);});
        });
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
        editorDialog=d;editorContent=content;
        if(restoringDraft!=null)restoreFields(content,restoringDraft,"fields");
        d.setOnDismissListener(it->{if(editorDialog==d){editorDialog=null;editorContent=null;editorType="";}});
        d.show();
        d.setCanceledOnTouchOutside(false);
        prepareFormWindow(d,scroll);
    }
    private void clientEditor(CrmStore.Client existing) {
        CrmStore.Client c=existing==null?new CrmStore.Client():existing;
        beginEditor("client",c.id);
        LinearLayout form=form();
        EditText name=field(form,"Имя / организация",c.name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText phone=field(form,"Телефон",c.phone,InputType.TYPE_CLASS_PHONE);
        EditText note=field(form,"Примечание",c.note,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        dialog(c.id==0?"Новый клиент":"Изменить клиента",form,"Сохранить",()->{
            c.name=name.getText().toString();c.phone=phone.getText().toString();
            c.note=note.getText().toString();store.saveClient(c);render();
        });
    }
    private void clientDetails(long id) {clientDetails(id,0);}
    private void clientDetails(long id,int historyPage) {
        CrmStore.Client c=store.client(id);
        if(c==null){error(new IllegalArgumentException("Клиент не найден"));return;}
        LinearLayout d=form();
        d.addView(label(c.name,22,true,text));space(d,10);
        d.addView(label(c.phone,15,false,sub));space(d,12);
        if(!c.phone.trim().isEmpty()) {
            d.addView(button("Набрать номер клиента",false,()->{
                Intent dial=new Intent(Intent.ACTION_DIAL,
                        Uri.fromParts("tel",c.phone.trim(),null));
                try {
                    startActivity(dial); // Opens dialer; never starts a call automatically.
                } catch(ActivityNotFoundException | SecurityException ex) {
                    error(new IllegalStateException("На телефоне нет доступной звонилки",ex));
                }
            }));
            space(d,12);
        }
        if(!c.note.isEmpty()){d.addView(label(c.note,14,false,text));space(d,12);}
        CrmStore.ClientMetrics stats=store.clientMetrics(c.id);
        if(stats.regular())d.addView(label("★ Постоянный клиент",14,true,green));
        line(d,"Всего заказов",""+stats.jobCount);
        line(d,"Получено от клиента",Money.format(stats.paid));
        line(d,"Текущий долг",Money.format(stats.debt));
        space(d,9);
        d.addView(button("Изменить",false,()->{clientDialog.dismiss();clientEditor(c);}));
        space(d,9);
        d.addView(button("+ Заказ для этого клиента",true,()->{clientDialog.dismiss();jobEditor(null,c.id);}));
        space(d,16);
        d.addView(label("Заказы клиента",17,true,text));space(d,8);
        List<CrmStore.Job> clientJobs=store.jobsForClient(c.id,historyPage);
        if(clientJobs.isEmpty()) d.addView(label("Заказов пока нет",13,false,sub));
        for(CrmStore.Job j:clientJobs) {
            d.addView(jobRow(j));space(d,12);
        }
        if(historyPage>0)d.addView(button("← Предыдущие заказы клиента",false,
                ()->clientDetails(id,historyPage-1)));
        if(clientJobs.size()==100)d.addView(button("Следующие заказы клиента →",false,
                ()->clientDetails(id,historyPage+1)));
        ScrollView scroll=new ScrollView(this);scroll.addView(d);
        if(clientDialog!=null)clientDialog.dismiss();
        AlertDialog details=alertBuilder().setTitle("Карточка клиента")
                .setView(scroll).setPositiveButton("Закрыть",null).create();
        clientDialog=details;detailClientId=id;detailClientPage=historyPage;clientDetailScroll=scroll;
        details.setOnDismissListener(ignored->{
            if(clientDialog==details){clientDialog=null;detailClientId=0;}
        });
        details.show();
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
        d.show();prepareFormWindow(d,scroll);load.run();
    }

    /** Picks a previously performed service without creating or changing any order. */
    private void servicePicker(EditText title,EditText description,EditText price) {
        LinearLayout form=form();
        form.addView(label("Берём данные из последнего заказа с таким названием. Перед сохранением проверьте стоимость.",13,false,sub));
        space(form,10);
        EditText query=field(form,"Найти услугу","",InputType.TYPE_CLASS_TEXT);
        LinearLayout results=column();
        form.addView(results);
        ScrollView scroll=new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog=alertBuilder().setTitle("Работы из истории")
                .setView(scroll).setNegativeButton("Отмена",null).create();
        Runnable load=()->{
            results.removeAllViews();
            List<CrmStore.ServiceSuggestion> items=store.serviceSuggestions(
                    query.getText().toString(),40);
            if(items.isEmpty()) {
                results.addView(label("Похожих работ пока нет. Можно ввести новую вручную.",13,false,sub));
            }
            for(CrmStore.ServiceSuggestion item:items) {
                String text=item.title+" · "+Money.format(item.price)+
                        " · заказов: "+item.uses;
                results.addView(button(text,false,()->{
                    title.setText(item.title);
                    description.setText(item.description);
                    price.setText(java.math.BigDecimal.valueOf(item.price,2).toPlainString());
                    dialog.dismiss();
                }));
                space(results,6);
            }
            if(items.size()==40)results.addView(label("Показаны 40 последних видов работ. Уточните название для поиска.",12,false,sub));
        };
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){load.run();}
            @Override public void afterTextChanged(android.text.Editable s){}
        });
        dialog.show();
        prepareFormWindow(dialog,scroll);
        load.run();
    }

    private void jobEditor(CrmStore.Job existing,long preferredClientId) {
        CrmStore.Job job=existing==null?new CrmStore.Job():existing;
        beginEditor("job",job.id);
        List<CrmStore.Client> clients=store.clients("",0);
        if(clients.isEmpty()){
            alertBuilder().setMessage("Сначала добавьте клиента")
                    .setPositiveButton("Добавить", (d,w)->clientEditor(null))
                    .setNegativeButton("Отмена",null).show();
            return;
        }
        LinearLayout f=form();
        long desired=restoringDraft!=null?restoringDraft.getLong("client"):
                (job.id>0?job.clientId:preferredClientId);
        CrmStore.Client initial=desired>0?store.client(desired):clients.get(0);
        if(initial==null)initial=clients.get(0);
        final long[] selectedClient={initial.id};editorClient=selectedClient;
        f.addView(label("Клиент",13,true,sub));
        TextView chosen=label(initial.name,16,true,text);
        f.addView(chosen);space(f,7);
        f.addView(button("Выбрать клиента по имени или телефону",false,
                ()->clientPicker(selectedClient,chosen)));
        space(f,12);
        EditText title=field(f,"Работа / услуга",job.title,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText description=field(f,"Описание, материалы и заметки",job.description,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText price=field(f,"Стоимость, ₽",java.math.BigDecimal.valueOf(job.price,2).toPlainString(),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if(job.id==0) {
            space(f,8);
            f.addView(button("Подставить услугу из прошлых заказов",false,
                    ()->servicePicker(title,description,price)));
            space(f,8);
        }
        EditText due=field(f,"Срок: ГГГГ-ММ-ДД (необязательно)",job.dueDate,InputType.TYPE_CLASS_DATETIME);
        due.setContentDescription("Срок заказа");
        f.addView(button("Выбрать срок в календаре",false,()->{
            java.time.LocalDate initialDate;
            try {initialDate=java.time.LocalDate.parse(due.getText().toString().trim());}
            catch(Exception ignored) {initialDate=java.time.LocalDate.now();}
            DatePickerDialog picker=new DatePickerDialog(this,
                    (view,year,month,day)->due.setText(
                            java.time.LocalDate.of(year,month+1,day).toString()),
                    initialDate.getYear(),initialDate.getMonthValue()-1,initialDate.getDayOfMonth());
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
        closeJobDialog();
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
        String nextStatus=CrmStore.nextQuickStatus(j.status,j.paid==j.price);
        if(nextStatus!=null) {
            d.addView(button("Следующий этап: "+nextStatus,true,()->{
                alertBuilder().setTitle("Подтвердить смену статуса")
                        .setMessage("Заказ "+j.number+": "+j.status+" → "+nextStatus
                                +"\nИзменятся только статус и связанные списки. "
                                +"Оплаты и суммы останутся прежними.")
                        .setNegativeButton("Отмена",null)
                        .setPositiveButton("Подтвердить",(confirm,which)->safe(()->{
                            store.advanceJobStatus(j.id);
                            closeJobDialog();
                            render();
                            jobDetails(j.id);
                        })).show();
            }));
            space(d,8);
        }
        d.addView(button("Редактировать заказ",false,()->{
            closeJobDialog();jobEditor(j,0);
        }));
        space(d,8);
        d.addView(button("Дублировать заказ",false,()->safe(()->{
            closeJobDialog();
            long copy=store.duplicateJob(j.id);
            render();jobDetails(copy);
        })));
        space(d,8);
        d.addView(button("+ Оплата",true,()->{closeJobDialog();paymentEditor(j.id,false);}));
        space(d,8);
        d.addView(button("Возврат клиенту",false,()->{
            closeJobDialog();paymentEditor(j.id,true);
        }));
        space(d,8);
        d.addView(button("+ Расход по заказу",false,()->{
            closeJobDialog();expenseEditor(j.id);
        }));
        space(d,8);
        d.addView(button("Заказ-наряд PDF",false,()->{
            closeJobDialog();exportPdf(j.id);
        }));
        space(d,8);
        d.addView(button("Поделиться сводкой заказа",false,()->{
            // Sharing is always explicit. No personal contacts, notes, or images included.
            Intent send=new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT,"Заказ "+j.number);
            send.putExtra(Intent.EXTRA_TEXT,OrderShareText.build(j));
            try {
                startActivity(Intent.createChooser(send,"Поделиться заказом"));
            } catch(ActivityNotFoundException | SecurityException ex) {
                error(new IllegalStateException("Нет приложения для отправки текста",ex));
            }
        }));
        space(d,14);
        d.addView(label("Фотографии заказа",17,true,text));
        space(d,7);
        d.addView(label("Фото хранятся только на устройстве и включаются в защищённую копию .mcrm5.",12,false,sub));
        space(d,8);
        d.addView(button("+ Фото до работы",false,()->{
            closeJobDialog();choosePhoto(j.id,true);
        }));
        space(d,6);
        d.addView(button("Камера · до работы",false,()->{
            closeJobDialog();capturePhoto(j.id,true);
        }));
        space(d,6);
        d.addView(button("+ Фото после работы",false,()->{
            closeJobDialog();choosePhoto(j.id,false);
        }));
        space(d,6);
        d.addView(button("Камера · после работы",false,()->{
            closeJobDialog();capturePhoto(j.id,false);
        }));
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
        AlertDialog dialog=alertBuilder().setTitle(j.number).setView(scroll)
                .setPositiveButton("Закрыть",null).create();
        activeJobDialog=dialog;detailJobId=id;jobDetailScroll=scroll;
        dialog.setOnDismissListener(ignored->{
            if(activeJobDialog==dialog){activeJobDialog=null;detailJobId=0;}
        });
        dialog.show();
    }
    private void paymentEditor(long id,boolean refund) {
        beginEditor(refund?"refund":"payment",id);
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
        beginEditor("expense",preferredJob);
        LinearLayout f=form();
        EditText category=field(f,"Категория","Материалы",InputType.TYPE_CLASS_TEXT);
        EditText amount=field(f,"Сумма, ₽","",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText note=field(f,"Комментарий","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        List<CrmStore.Job> all=new ArrayList<>(store.jobs("","",0));
        // A payment/expense action may originate beyond the first 100 orders.
        if(preferredJob>0 && all.stream().noneMatch(j->j.id==preferredJob)) {
            CrmStore.Job preferred=store.job(preferredJob);
            if(preferred!=null)all.add(0,preferred);
        }
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
    /** Use an external camera app and a temporary, private output URI.
     * No CAMERA / storage permissions are necessary for ACTION_IMAGE_CAPTURE.
     */
    private void capturePhoto(long jobId,boolean before) {
        if(store.job(jobId)==null){error(new IllegalArgumentException("Заказ не найден"));return;}
        final File file;
        try {
            File dir=new File(getCacheDir(),"camera");
            if(!dir.exists()&&!dir.mkdirs())throw new IOException("Не удалось создать папку для камеры");
            file=File.createTempFile("capture_",".jpg",dir);
        } catch(IOException ex) {
            error(new IllegalStateException("Не удалось создать временный файл фотографии",ex));
            return;
        }
        photoJobId=jobId;
        pendingCameraPath=file.getAbsolutePath();
        Uri output=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",file);
        Intent camera=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        camera.putExtra(MediaStore.EXTRA_OUTPUT,output);
        camera.setClipData(ClipData.newRawUri("MasterCRM photo",output));
        camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(camera,before?CAMERA_BEFORE:CAMERA_AFTER);
        } catch(ActivityNotFoundException|SecurityException ex) {
            pendingCameraPath="";
            file.delete();
            error(new IllegalStateException("Камера недоступна на этом устройстве",ex));
        }
    }
    /** Saves a compressed photo without blocking the main UI thread. */
    private void savePhotoAsync(long jobId,String kind,Uri uri,File temporary) {
        new Thread(()->{
            try {
                if(store.job(jobId)==null)throw new IllegalArgumentException("Заказ не найден");
                byte[] jpeg=photoJpeg(uri);
                store.addPhoto(jobId,kind,jpeg);
                runOnUiThread(()->{
                    Toast.makeText(this,"Фото сохранено",Toast.LENGTH_SHORT).show();
                    render();jobDetails(jobId);
                });
            }catch(Exception ex){
                runOnUiThread(()->error(ex));
            }finally{
                if(temporary!=null)temporary.delete();
            }
        },"mastercrm-photo-import").start();
    }
    private Bitmap thumbnail(byte[] jpeg) {
        return PhotoBitmapDecoder.decode(jpeg,320);
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
            Bitmap b=thumbnail(p.jpeg);
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
                        closeJobDialog();
                        render();jobDetails(jobId);
                    })).show()));
            item.addView(actions,new LinearLayout.LayoutParams(0,-2,1));
            parent.addView(item);space(parent,8);
        }
        if(!present)parent.addView(label("Фотографий пока нет",12,false,sub));
    }
    private void showPhoto(CrmStore.Photo p) {
        Bitmap b=PhotoBitmapDecoder.decode(p.jpeg,1600);
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
        if(requestCode==CAMERA_BEFORE||requestCode==CAMERA_AFTER) {
            String path=pendingCameraPath;
            pendingCameraPath="";
            if(path==null||path.isEmpty())return;
            File temp=new File(path);
            if(resultCode==RESULT_OK && temp.length()>0) {
                savePhotoAsync(photoJobId,
                        requestCode==CAMERA_BEFORE?"before":"after",
                        Uri.fromFile(temp),temp);
            } else {
                temp.delete();
                if(resultCode==RESULT_OK)
                    error(new IllegalStateException("Камера не сохранила фотографию"));
            }
            return;
        }
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(requestCode==PHOTO_BEFORE||requestCode==PHOTO_AFTER) {
            savePhotoAsync(photoJobId,
                    requestCode==PHOTO_BEFORE?"before":"after",uri,null);
        } else if(requestCode==EXPORT_STREAM) {
            encryptedBackupPrompt(uri,true,true);
        } else if(requestCode==VERIFY_STREAM) {
            encryptedBackupPrompt(uri,false,true,true);
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
                        getPreferences(MODE_PRIVATE).edit()
                                .remove("last_verified_stream_backup").apply();
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
        encryptedBackupPrompt(uri,exporting,streaming,false);
    }
    private void encryptedBackupPrompt(Uri uri,boolean exporting,boolean streaming,
                                       boolean verifyOnly) {
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
        dialog(verifyOnly?"Проверка защищённой копии":
                (exporting?"Защитить резервную копию":"Расшифровать копию"),form,
                verifyOnly?"Проверить":(exporting?"Сохранить":"Восстановить"),()->{
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
                        String verificationReport=null;
                        if(streaming) {
                            if(exporting) {
                                try(OutputStream raw=getContentResolver().openOutputStream(uri,"w")) {
                                    if(raw==null)throw new IllegalStateException("Не удалось открыть файл");
                                    try(OutputStream protectedStream=StreamBackupCrypto.encrypt(raw,workerPassword)) {
                                        StreamCrmArchive.write(store,protectedStream);
                                    }
                                }
                                // Reopen the SAF file after closing it and authenticate
                                // every block. Dry-run import always rolls back SQLite.
                                // Never record success before this verification finishes.
                                try {
                                    try(InputStream written=getContentResolver().openInputStream(uri)) {
                                        if(written==null)throw new IOException("Не удалось перечитать файл");
                                        try(InputStream clear=StreamBackupCrypto.decrypt(
                                                written,workerPassword)) {
                                            StreamCrmArchive.verify(store,clear);
                                        }
                                    }
                                } catch(Exception failedVerification) {
                                    throw new IOException("Файл создан, но его не удалось "
                                            +"проверить после записи. Не считайте эту "
                                            +"копию надёжной; сохраните новую.",failedVerification);
                                }
                                getPreferences(MODE_PRIVATE).edit()
                                        .putLong("last_verified_stream_backup",
                                                System.currentTimeMillis()).apply();
                            } else {
                                try(InputStream raw=getContentResolver().openInputStream(uri)) {
                                    if(raw==null)throw new IllegalStateException("Не удалось открыть файл");
                                    try(InputStream clear=StreamBackupCrypto.decrypt(raw,workerPassword)) {
                                        if(verifyOnly) {
                                            StreamCrmArchive.VerificationResult result=
                                                    StreamCrmArchive.verify(store,clear);
                                            verificationReport="Архив корректен.\n"+
                                                    "Клиенты: "+result.clients+"\n"+
                                                    "Заказы: "+result.jobs+"\n"+
                                                    "Оплаты: "+result.payments+"\n"+
                                                    "Расходы: "+result.expenses+"\n"+
                                                    "Фотографии: "+result.photos+"\n\n"+
                                                    "Данные текущего приложения не изменены.";
                                        } else {
                                            StreamCrmArchive.restore(store,clear);
                                        }
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
                        // The last exported file may refer to different data after
                        // a restore, so don't treat it as a current backup anymore.
                        if(!exporting&&!verifyOnly)
                            getPreferences(MODE_PRIVATE).edit()
                                    .remove("last_verified_stream_backup").apply();
                        final String report=verificationReport;
                        runOnUiThread(()->{
                            if(verifyOnly) {
                                alertBuilder().setTitle("Результат проверки копии")
                                        .setMessage(report)
                                        .setPositiveButton("Закрыть",null).show();
                            } else {
                                Toast.makeText(this,exporting?
                                        (streaming?"Защищённая копия сохранена и проверена":
                                                "Защищённая копия сохранена"):
                                        "Копия успешно восстановлена",Toast.LENGTH_LONG).show();
                                render();
                            }
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
