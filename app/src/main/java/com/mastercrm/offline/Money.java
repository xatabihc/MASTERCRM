package com.mastercrm.offline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Store all currency as integer kopecks. Never use float/double for money. */
public final class Money {
    private Money() {}

    public static long parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("Укажите сумму");
        String value = raw.trim().replace("\u00a0", "").replace(" ", "").replace(',', '.');
        if (!value.matches("[+-]?\\d{1,12}(\\.\\d{1,2})?")) {
            throw new IllegalArgumentException("Сумма: число, не более двух знаков после запятой");
        }
        return new BigDecimal(value).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    public static String format(long minor) {
        NumberFormat format = NumberFormat.getNumberInstance(new Locale("ru", "RU"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(BigDecimal.valueOf(minor, 2)) + " ₽";
    }

    public static long outstanding(long agreed, long paid) {
        return Math.max(0L, Math.subtractExact(agreed, paid));
    }
}
