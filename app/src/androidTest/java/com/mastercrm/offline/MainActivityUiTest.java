package com.mastercrm.offline;

import android.app.UiAutomation;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Runs against an ephemeral emulator, never a real user's installed MasterCRM. */
@RunWith(AndroidJUnit4.class)
public class MainActivityUiTest {
    @Rule public ActivityScenarioRule<MainActivity> rule =
            new ActivityScenarioRule<>(MainActivity.class);

    /**
     * Headless GitHub Android emulators occasionally show a system dialog
     * "Pixel Launcher isn't responding" or "Quickstep isn't responding".
     * This is the OS launcher, not MasterCRM. Only dismiss THAT exact dialog;
     * never conceal a MasterCRM ANR or app error.
     */
    private boolean dismissLauncherAnr() {
        UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityNodeInfo window=automation.getRootInActiveWindow();
        if(window==null)return false;
        List<AccessibilityNodeInfo> messages=window.findAccessibilityNodeInfosByText("isn't responding");
        if(messages==null||messages.isEmpty())return false;
        boolean launcherAnr=false;
        for(AccessibilityNodeInfo node:messages){
            CharSequence raw=node.getText();
            String message=raw==null?"":raw.toString();
            if((message.contains("Launcher")||message.contains("Quickstep"))
                    && message.contains("isn't responding")) {
                launcherAnr=true;
                break;
            }
        }
        if(!launcherAnr)return false;
        List<AccessibilityNodeInfo> close=window.findAccessibilityNodeInfosByText("Close app");
        if(close==null||close.isEmpty())return false;
        boolean dismissed=close.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if(dismissed)SystemClock.sleep(800);
        return dismissed;
    }

    private void withLauncherRecovery(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | AssertionError first) {
            if(!dismissLauncherAnr())throw first;
            // Re-run only after recovering from a positively identified
            // Pixel Launcher/Quickstep system ANR.
            step.run();
        }
    }

    @Test public void focusedFieldRemainsAboveKeyboard() {
        dismissLauncherAnr();
        onView(withContentDescription("Раздел: Клиенты")).perform(click());
        onView(withContentDescription("Создать запись")).perform(click());
        onView(withContentDescription("Примечание")).perform(scrollTo(),click());
        SystemClock.sleep(800); // Wait for the platform IME animation, not a database operation.
        onView(withContentDescription("Примечание")).check((view,failure)->{
            if(failure!=null)throw failure;
            org.junit.Assert.assertTrue(view.hasFocus());
            androidx.core.view.WindowInsetsCompat insets=androidx.core.view.ViewCompat.getRootWindowInsets(view);
            org.junit.Assert.assertNotNull(insets);
            org.junit.Assert.assertTrue("Test must exercise a visible keyboard",
                    insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()));
            android.graphics.Rect frame=new android.graphics.Rect();
            view.getWindowVisibleDisplayFrame(frame);
            int[] position=new int[2];view.getLocationOnScreen(position);
            org.junit.Assert.assertTrue("Focused field must fit above the IME",
                    position[1]+view.getHeight()<=frame.bottom);
        });
        onView(withContentDescription("Примечание")).perform(closeSoftKeyboard());
        onView(withText("Отмена")).perform(click());
    }

    @Test public void orderAndPaymentDraftsSurviveRecreationWithoutFinancialWrites() {
        String title="UI draft financial invariant 1.6";
        final long[] id={0};
        rule.getScenario().onActivity(activity->{
            try(CrmStore db=new CrmStore(activity)) {
                CrmStore.Client client=new CrmStore.Client();client.name="Draft invariant client";
                CrmStore.Job job=new CrmStore.Job();job.clientId=db.saveClient(client);
                job.title=title;job.price=200000;id[0]=db.saveJob(job);
                db.addPayment(id[0],50000,"Initial synthetic payment");
            }
        });
        dismissLauncherAnr();
        onView(withContentDescription("Раздел: Заказы")).perform(click());
        onView(withText(title)).perform(scrollTo(),click());
        rule.getScenario().recreate();
        onView(withText("Редактировать заказ")).perform(scrollTo(),click());
        onView(withContentDescription("Стоимость, ₽"))
                .perform(scrollTo(),replaceText("9999.99"),closeSoftKeyboard());
        rule.getScenario().recreate();
        onView(withContentDescription("Стоимость, ₽")).check(matches(withText("9999.99")));
        onView(withText("Отмена")).perform(click());
        onView(withText(title)).perform(scrollTo(),click());
        onView(withText("+ Оплата")).perform(scrollTo(),click());
        onView(withContentDescription("Сумма, ₽"))
                .perform(replaceText("456.78"),closeSoftKeyboard());
        rule.getScenario().recreate();
        onView(withContentDescription("Сумма, ₽")).check(matches(withText("456.78")));
        onView(withText("Отмена")).perform(click());
        rule.getScenario().onActivity(activity->{
            try(CrmStore db=new CrmStore(activity)) {
                assertEquals(200000,db.job(id[0]).price);
                assertEquals(50000,db.job(id[0]).paid);
            }
        });
    }

    @Test public void clientDraftSurvivesRecreationWithoutSaving() {
        dismissLauncherAnr();
        onView(withContentDescription("Раздел: Клиенты")).perform(click());
        onView(withContentDescription("Создать запись")).perform(click());
        onView(withContentDescription("Имя / организация"))
                .perform(replaceText("Несохранённый черновик 1.6"),closeSoftKeyboard());
        onView(withContentDescription("Примечание"))
                .perform(scrollTo(),replaceText("Длинный текст\nВторая строка"),closeSoftKeyboard());
        rule.getScenario().recreate();
        onView(withContentDescription("Имя / организация"))
                .check(matches(withText("Несохранённый черновик 1.6")));
        onView(withContentDescription("Примечание"))
                .check(matches(withText("Длинный текст\nВторая строка")));
        onView(withText("Отмена")).perform(click());
        rule.getScenario().onActivity(activity->{
            try(CrmStore db=new CrmStore(activity)) {
                assertEquals(0,db.clients("Несохранённый черновик 1.6",0).size());
            }
        });
    }

    @Test public void expenseDraftSurvivesRecreationWithoutChangingMoney() {
        dismissLauncherAnr();
        onView(withContentDescription("Раздел: Финансы")).perform(click());
        onView(withContentDescription("Создать запись")).perform(click());
        onView(withContentDescription("Сумма, ₽"))
                .perform(scrollTo(),replaceText("1234.56"),closeSoftKeyboard());
        onView(withContentDescription("Комментарий"))
                .perform(scrollTo(),replaceText("Черновик расхода"),closeSoftKeyboard());
        rule.getScenario().recreate();
        onView(withContentDescription("Сумма, ₽")).check(matches(withText("1234.56")));
        onView(withContentDescription("Комментарий")).check(matches(withText("Черновик расхода")));
        onView(withText("Отмена")).perform(click());
    }

    @Test public void searchAndTabSurviveRecreation() {
        dismissLauncherAnr();
        onView(withContentDescription("Раздел: Клиенты")).perform(click());
        onView(androidx.test.espresso.matcher.ViewMatchers.withHint("Имя или телефон клиента"))
                .perform(replaceText("Поиск 1.6"),closeSoftKeyboard());
        rule.getScenario().recreate();
        onView(androidx.test.espresso.matcher.ViewMatchers.withHint("Имя или телефон клиента"))
                .check(matches(withText("Поиск 1.6")));
    }

    @Test public void reusesHistoricalServiceOnlyAfterExplicitSelection() {
        final String uniqueTitle="Услуга из истории UI 1.1";
        rule.getScenario().onActivity(activity->{
            CrmStore db=new CrmStore(activity);
            try {
                CrmStore.Client client=new CrmStore.Client();
                client.name="Клиент UI выбор услуги";
                long id=db.saveClient(client);
                CrmStore.Job old=new CrmStore.Job();
                old.clientId=id;
                old.title=uniqueTitle;
                old.description="Проверка шаблона из прошлого заказа";
                old.price=215000;
                db.saveJob(old);
            } finally {db.close();}
        });
        dismissLauncherAnr();
        withLauncherRecovery(()->onView(withContentDescription("Раздел: Заказы")).perform(click()));
        withLauncherRecovery(()->onView(withContentDescription("Создать запись")).perform(click()));
        withLauncherRecovery(()->onView(withText("Подставить услугу из прошлых заказов"))
                .perform(scrollTo(),click()));
        withLauncherRecovery(()->onView(withContentDescription("Найти услугу"))
                .perform(replaceText(uniqueTitle),closeSoftKeyboard()));
        withLauncherRecovery(()->onView(withText(uniqueTitle+" · "+Money.format(215000)+" · заказов: 1"))
                .perform(scrollTo(),click()));
        withLauncherRecovery(()->onView(withContentDescription("Работа / услуга"))
                .check(matches(withText(uniqueTitle))));
        withLauncherRecovery(()->onView(withContentDescription("Описание, материалы и заметки"))
                .check(matches(withText("Проверка шаблона из прошлого заказа"))));
        withLauncherRecovery(()->onView(withContentDescription("Стоимость, ₽"))
                .check(matches(withText("2150.00"))));
        withLauncherRecovery(()->onView(withText("Отмена")).perform(click()));
        rule.getScenario().onActivity(activity->{
            CrmStore db=new CrmStore(activity);
            try {
                // Choosing a suggestion may prefill a form, but must not save it.
                assertEquals(1,db.serviceSuggestions(uniqueTitle,50).get(0).uses);
            } finally {db.close();}
        });
    }

    @Test public void financialPeriodTabsPersistAcrossScreens() {
        dismissLauncherAnr();
        withLauncherRecovery(()->onView(withContentDescription("Финансовый период: Неделя"))
                .perform(click()));
        withLauncherRecovery(()->onView(withText("Получено за неделю"))
                .check(matches(isDisplayed())));
        withLauncherRecovery(()->onView(withContentDescription("Раздел: Финансы"))
                .perform(click()));
        withLauncherRecovery(()->onView(withText("Денежный поток за неделю"))
                .check(matches(isDisplayed())));
        withLauncherRecovery(()->onView(withContentDescription("Финансовый период: Всё"))
                .perform(click()));
        withLauncherRecovery(()->onView(withText("Денежный поток за всё время"))
                .check(matches(isDisplayed())));
        // Restore the default period, so other tests don't inherit this preference.
        withLauncherRecovery(()->onView(withContentDescription("Финансовый период: Месяц"))
                .perform(click()));
    }

    @Test public void createsClientAndOrderAndNavigatesToDarkTheme() {
        int stage=0;
        try {
            // Clear launcher ANRs that appeared during the previous test.
            dismissLauncherAnr();
            stage=1; withLauncherRecovery(()->onView(withText("MASTERCRM  ·  OFFLINE"))
                    .check(matches(isDisplayed())));
            stage=2; withLauncherRecovery(()->onView(withContentDescription("Раздел: Клиенты"))
                    .perform(click()));
            stage=3; withLauncherRecovery(()->onView(withContentDescription("Создать запись"))
                    .perform(click()));
            stage=4; withLauncherRecovery(()->onView(withContentDescription("Имя / организация"))
                    .perform(replaceText("UI автотест клиента"),closeSoftKeyboard()));
            stage=5; withLauncherRecovery(()->onView(withContentDescription("Телефон"))
                    .perform(replaceText("+79001112233"),closeSoftKeyboard()));
            stage=6; withLauncherRecovery(()->onView(withText("Сохранить"))
                    .perform(click()));
            stage=7; withLauncherRecovery(()->onView(withText("UI автотест клиента"))
                    .check(matches(isDisplayed())));

            stage=8; withLauncherRecovery(()->onView(withContentDescription("Раздел: Заказы"))
                    .perform(click()));
            stage=9; withLauncherRecovery(()->onView(withContentDescription("Создать запись"))
                    .perform(click()));
            stage=10; withLauncherRecovery(()->onView(withContentDescription("Работа / услуга"))
                    .perform(replaceText("Диагностика UI"),closeSoftKeyboard()));
            stage=11; withLauncherRecovery(()->onView(withContentDescription("Стоимость, ₽"))
                    .perform(scrollTo(),replaceText("1250"),closeSoftKeyboard()));
            stage=12; withLauncherRecovery(()->onView(withText("Сохранить"))
                    .perform(click()));
            stage=13; rule.getScenario().onActivity(activity -> {
                CrmStore data=new CrmStore(activity);
                try {
                    assertFalse(data.clients("UI автотест клиента",0).isEmpty());
                    assertEquals(125000,data.jobs("Диагностика UI","",0).get(0).price);
                } finally { data.close(); }
            });

            stage=14; withLauncherRecovery(()->onView(withText("Закрыть"))
                    .perform(click()));
            stage=15; withLauncherRecovery(()->onView(withContentDescription("Раздел: Ещё"))
                    .perform(click()));
            stage=16; withLauncherRecovery(()->onView(withText("Тёмная тема"))
                    .perform(click()));
            stage=17; withLauncherRecovery(()->onView(withText("Светлая тема"))
                    .check(matches(isDisplayed())));
            stage=18; withLauncherRecovery(()->onView(withText("Светлая тема"))
                    .perform(click()));
            stage=19; withLauncherRecovery(()->onView(withText("Тёмная тема"))
                    .check(matches(isDisplayed())));
        } catch(Throwable failure) {
            throw new AssertionError("MainActivity UI test failed at stage "+stage,failure);
        }
    }
}
