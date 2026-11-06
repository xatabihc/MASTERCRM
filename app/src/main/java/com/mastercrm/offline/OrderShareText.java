package com.mastercrm.offline;

/** A plain-text summary prepared only when the user explicitly chooses to share. */
public final class OrderShareText {
    private OrderShareText() {}

    public static String build(CrmStore.Job job) {
        if (job == null) throw new IllegalArgumentException("Заказ не найден");
        StringBuilder text = new StringBuilder("Заказ ").append(safe(job.number))
                .append("\nУслуга: ").append(safe(job.title))
                .append("\nСтатус: ").append(safe(job.status))
                .append("\nСтоимость: ").append(Money.format(job.price))
                .append("\nОплачено: ").append(Money.format(job.paid))
                .append("\nОстаток: ")
                .append(Money.format(Money.outstanding(job.price, job.paid)));
        if (!safe(job.dueDate).isEmpty()) text.append("\nСрок: ").append(job.dueDate.trim());
        return text.toString();
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
