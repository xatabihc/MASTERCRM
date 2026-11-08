package com.mastercrm.offline;

/**
 * Advisory only. This timestamp confirms the last locally verified .mcrm5
 * export, not a cloud backup and not a guarantee the exported file still exists.
 */
public final class BackupReminder {
    static final long REMINDER_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private BackupReminder() {}

    /** Empty string means no banner should be displayed. */
    public static String warning(boolean containsClientData, long verifiedAt, long now) {
        if (!containsClientData) return "";
        if (verifiedAt <= 0 || verifiedAt > now)
            return "Нет подтверждённой резервной копии на этом устройстве. "
                    + "Сохраните защищённый файл .mcrm5 отдельно от телефона.";
        long age = now - verifiedAt;
        if (age < REMINDER_INTERVAL_MILLIS) return "";
        long days = age / (24L * 60 * 60 * 1000);
        return "Последняя проверенная копия создана " + days
                + " дн. назад. Сделайте новую и храните файл отдельно от телефона.";
    }
}
