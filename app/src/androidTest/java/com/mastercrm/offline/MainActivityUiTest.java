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
        onView(withText("MASTERCRM  ·  OFFLINE")).check(matches(isDisplayed()));
        onView(withContentDescription("Раздел: Клиенты")).perform(click());
        onView(withContentDescription("Создать запись")).perform(click());
        onView(withContentDescription("Имя / организация"))
                .perform(replaceText("UI автотест клиента"),closeSoftKeyboard());
        onView(withContentDescription("Телефон"))
                .perform(replaceText("+79001112233"),closeSoftKeyboard());
        onView(withText("Сохранить")).perform(click());
        onView(withText("UI автотест клиента")).check(matches(isDisplayed()));

        onView(withContentDescription("Раздел: Заказы")).perform(click());
        onView(withContentDescription("Создать запись")).perform(click());
        onView(withContentDescription("Работа / услуга"))
                .perform(replaceText("Диагностика UI"),closeSoftKeyboard());
        onView(withContentDescription("Стоимость, ₽"))
                .perform(scrollTo(),replaceText("1250"),closeSoftKeyboard());
        onView(withText("Сохранить")).perform(click());

        rule.getScenario().onActivity(activity -> {
            CrmStore data = new CrmStore(activity);
            try {
                assertFalse(data.clients("UI автотест клиента",0).isEmpty());
                assertEquals(125000, data.jobs("Диагностика UI","",0).get(0).price);
            } finally {
                data.close();
            }
        });

        onView(withText("Закрыть")).perform(click());
        onView(withContentDescription("Раздел: Ещё")).perform(click());
        onView(withText("Тёмная тема")).perform(click());
        onView(withText("Светлая тема")).check(matches(isDisplayed()));
        onView(withText("Светлая тема")).perform(click());
        onView(withText("Тёмная тема")).check(matches(isDisplayed()));
    }
}
