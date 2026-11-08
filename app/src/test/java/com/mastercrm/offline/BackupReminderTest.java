package com.mastercrm.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BackupReminderTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1800000000000L;

    @Test public void emptyCrmNeverShowsWarning() {
        assertEquals("", BackupReminder.warning(false, 0, NOW));
        assertEquals("", BackupReminder.warning(false, NOW - 30 * DAY, NOW));
    }

    @Test public void missingOrFutureBackupWarns() {
        assertTrue(BackupReminder.warning(true, 0, NOW).contains(".mcrm5"));
        assertTrue(BackupReminder.warning(true, NOW + DAY, NOW).contains(".mcrm5"));
    }

    @Test public void onlyWarnsAtSevenDays() {
        assertEquals("", BackupReminder.warning(true, NOW - 6 * DAY, NOW));
        assertEquals("", BackupReminder.warning(true, NOW - 7 * DAY + 1, NOW));
        assertTrue(BackupReminder.warning(true, NOW - 7 * DAY, NOW).contains("7 дн."));
        assertTrue(BackupReminder.warning(true, NOW - 30 * DAY, NOW).contains("30 дн."));
    }
}
