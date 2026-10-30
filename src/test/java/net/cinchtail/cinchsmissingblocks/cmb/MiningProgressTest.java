package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MiningProgressTest {
    @Test void miningUsesElapsedTicksRatherThanClickCount() {
        double oncePerTick = 0, oncePerSixTicks = 0;
        for (int tick = 0; tick <= 30; tick++) {
            oncePerTick = MiningProgress.advance(oncePerTick, 1.0 / 30, 1);
            if (tick % 6 == 0) oncePerSixTicks = MiningProgress.advance(oncePerSixTicks, 1.0 / 30, 6);
        }
        assertEquals(oncePerTick, oncePerSixTicks, 1e-9);
        assertTrue(oncePerTick >= 1);
    }

    @Test void duplicatesAndIdleTimeCannotInstantlyBreakThePiece() {
        assertEquals(.01, MiningProgress.advance(0, .01, 200), 1e-9);
        assertEquals(.01, MiningProgress.advance(.01, .01, 0), 1e-9);
        assertEquals(.07, MiningProgress.advance(.01, .01, 39), 1e-9);
    }

    @Test void slowToolsHaveNoTwentyHitCapAndFastToolsCanBreakImmediately() {
        double progress = 0;
        for (int hit = 0; hit < 20; hit++) progress = MiningProgress.advance(progress, 1.0 / 300, 6);
        assertTrue(progress < .4);
        assertEquals(1.2, MiningProgress.advance(0, 1.2, 0), 1e-9);
        assertEquals(0, MiningProgress.advance(0, 0, 6));
        assertEquals(0, MiningProgress.stage(.01));
        assertEquals(5, MiningProgress.stage(.55));
        assertEquals(9, MiningProgress.stage(1));
    }
}
