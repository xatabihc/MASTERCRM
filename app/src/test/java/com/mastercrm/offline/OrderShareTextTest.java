package com.mastercrm.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public class OrderShareTextTest {
    @Test public void includesAmountsAndDeadline() {
        CrmStore.Job job = new CrmStore.Job();
        job.number = "M-100";
        job.title = "Ремонт";
        job.status = "Готов";
        job.price = 250000;
        job.paid = 100000;
        job.dueDate = "2026-11-12";
        String summary = OrderShareText.build(job);
        assertTrue(summary.contains("M-100"));
        assertTrue(summary.contains("Ремонт"));
        assertTrue(summary.contains(Money.format(150000)));
        assertTrue(summary.contains("2026-11-12"));
        assertFalse(summary.contains("Телефон"));
    }

    @Test public void omitsPrivateDataAndNormalizesLineBreaks() {
        CrmStore.Job job = new CrmStore.Job();
        job.title = "Сервис\nКонтроль";
        job.clientName = "Секретное имя";
        job.clientPhone = "+70000000000";
        job.description = "Внутреннее примечание";
        String text = OrderShareText.build(job);
        assertTrue(text.contains("Сервис Контроль"));
        assertFalse(text.contains(job.clientName));
        assertFalse(text.contains(job.clientPhone));
        assertFalse(text.contains(job.description));
    }

    @Test public void overpaymentDoesNotProduceNegativeDebt() {
        CrmStore.Job job = new CrmStore.Job();
        job.price = 100;
        job.paid = 200;
        assertTrue(OrderShareText.build(job).contains("Остаток: " + Money.format(0)));
    }
}
