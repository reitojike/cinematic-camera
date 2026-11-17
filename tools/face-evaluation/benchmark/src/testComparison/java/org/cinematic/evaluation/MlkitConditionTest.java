package org.cinematic.evaluation;
import org.junit.Test;
import static org.junit.Assert.*;

public class MlkitConditionTest {
    @Test public void legacyConditionsUnchanged() {
        var fast = MlkitCondition.fromMode("FAST");
        assertTrue(fast.fast); assertEquals(0.1f, fast.minimumFaceSize, 0f);
        assertEquals("mlkit-16.1.7-FAST-min0.1-no-tracking", fast.id);
        var accurate = MlkitCondition.fromMode("ACCURATE");
        assertFalse(accurate.fast); assertEquals(0.1f, accurate.minimumFaceSize, 0f);
        assertEquals("mlkit-16.1.7-ACCURATE-min0.1-no-tracking", accurate.id);
    }
    @Test public void diagnosticConditionIsSeparateAccurateMode() {
        var candidate = MlkitCondition.fromMode("ACCURATE_MIN005");
        assertFalse(candidate.fast); assertEquals(0.05f, candidate.minimumFaceSize, 0f);
        assertEquals("mlkit-16.1.7-ACCURATE-min0.05-no-tracking", candidate.id);
        assertNotEquals(MlkitCondition.fromMode("ACCURATE").id, candidate.id);
    }
    @Test public void unknownAndArbitraryModesRejected() {
        for (String mode : new String[] {null, "", "ACCURATE_MIN001", "0.05", "accurate", "FAST_MIN005"}) {
            assertThrows(IllegalArgumentException.class, () -> MlkitCondition.fromMode(mode));
        }
    }
}
