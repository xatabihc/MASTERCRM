package com.mastercrm.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public class MoneyTest {
    @Test public void parsesCommaAndDecimal() {
        assertEquals(123456L, Money.parse("1 234,56"));
        assertEquals(1250L, Money.parse("12.5"));
        assertEquals(0L, Money.parse("0"));
    }
    @Test public void rejectsExcessPrecision() {
        try { Money.parse("12,345"); fail("precision"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void keepsLargeIntegersExact() {
        assertEquals(99999999999999L, Money.parse("999999999999,99"));
    }
    @Test public void preventsNegativeDebt() {
        assertEquals(0L, Money.outstanding(100L, 200L));
        assertEquals(120L, Money.outstanding(200L, 80L));
    }
}
