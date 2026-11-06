package com.mastercrm.offline;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

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

    @Test public void createsClientAndOrderAndNavigatesToDarkTheme() {
        int stage=0;
        try {
            stage=1; onView(withText("MASTERCRM  ·  OFFLINE")).check(matches(isDisplayed()));
            stage=2; onView(withContentDescription("Раздел: Клиенты")).perform(click());
            stage=3; onView(withContentDescription("Создать запись")).perform(click());
            stage=4; onView(withContentDescription("Имя / организация"))
                    .perform(replaceText("UI автотест клиента"),closeSoftKeyboard());
            stage=5; onView(withContentDescription("Телефон"))
                    .perform(replaceText("+79001112233"),closeSoftKeyboard());
            stage=6; onView(withText("Сохранить")).perform(click());
            stage=7; onView(withText("UI автотест клиента")).check(matches(isDisplayed()));

            stage=8; onView(withContentDescription("Раздел: Заказы")).perform(click());
            stage=9; onView(withContentDescription("Создать запись")).perform(click());
            stage=10; onView(withContentDescription("Работа / услуга"))
                    .perform(replaceText("Диагностика UI"),closeSoftKeyboard());
            stage=11; onView(withContentDescription("Стоимость, ₽"))
                    .perform(scrollTo(),replaceText("1250"),closeSoftKeyboard());
            stage=12; onView(withText("Сохранить")).perform(click());
            stage=13; rule.getScenario().onActivity(activity -> {
                CrmStore data=new CrmStore(activity);
                try {
                    assertFalse(data.clients("UI автотест клиента",0).isEmpty());
                    assertEquals(125000,data.jobs("Диагностика UI","",0).get(0).price);
                } finally { data.close(); }
            });

            stage=14; onView(withText("Закрыть")).perform(click());
            stage=15; onView(withContentDescription("Раздел: Ещё")).perform(click());
            stage=16; onView(withText("Тёмная тема")).perform(click());
            stage=17; onView(withText("Светлая тема")).check(matches(isDisplayed()));
            stage=18; onView(withText("Светлая тема")).perform(click());
            stage=19; onView(withText("Тёмная тема")).check(matches(isDisplayed()));
        } catch(Throwable failure) {
            throw new AssertionError("MainActivity UI test failed at stage "+stage,failure);
        }
    }
}
