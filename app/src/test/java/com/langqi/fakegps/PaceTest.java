package com.langqi.fakegps;

import org.junit.Test;
import static org.junit.Assert.*;

public class PaceTest {
    @Test public void legacyDecimalMinutesConvertToMinutesAndSeconds() {
        assertEquals(333, Pace.parse("5.55"));
        assertEquals("5:33", Pace.format(Pace.parse("5.55")));
    }

    @Test public void repeatedFiveSecondAdjustmentsHaveNoRoundingDrift() {
        String pace = "5:33";
        for (int i = 0; i < 12; i++) pace = Pace.format(Pace.parse(pace) + 5);
        assertEquals("6:33", pace);
    }

    @Test public void malformedAndNonFinitePacesAreRejected() {
        for (String value : new String[]{"5:60", "5:9", "NaN", "Infinity", "-1", "0", "", "1:00:00"}) {
            assertThrows("Should reject " + value, IllegalArgumentException.class, () -> Pace.parse(value));
        }
    }
}
