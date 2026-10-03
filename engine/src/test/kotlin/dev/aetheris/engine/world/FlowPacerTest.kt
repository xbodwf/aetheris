package dev.aetheris.engine.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A grid cell spreads in whole steps, but viscosity spans three orders of magnitude, so the rate being
 * modelled is rarely a whole number of steps per tick. These tests pin how the remainder is carried.
 */
class FlowPacerTest {

    @Test
    fun `a rate of one step per tick spends one step per tick`() {
        val pacer = FlowPacer()
        repeat(3) { assertEquals(1, pacer.stepsFor(1.0)) }
    }

    @Test
    fun `a thin liquid spends several steps in one tick`() {
        val pacer = FlowPacer()
        assertEquals(2, pacer.stepsFor(2.5), "two whole steps, with the half banked")
        assertEquals(0, pacer.stepsFor(0.0), "the banked half is not yet a step")
        assertEquals(1, pacer.stepsFor(0.5), "the second half completes it")
    }

    @Test
    fun `a thick liquid banks credit until it can afford a step`() {
        val pacer = FlowPacer()
        val rate = 1.0 / 256.0
        var steps = 0
        var ticks = 0
        while (steps == 0 && ticks < 1_000) {
            steps += pacer.stepsFor(rate)
            ticks++
        }
        assertEquals(1, steps)
        // 1/256 is exactly representable, so 256 accumulations land on exactly one step.
        assertEquals(256, ticks)
    }

    @Test
    fun `banked credit is dropped when the cell cannot flow`() {
        val pacer = FlowPacer()
        pacer.stepsFor(0.75)
        assertTrue(pacer.banked > 0.0, "three quarters of a step should be banked")

        pacer.reset()
        assertEquals(0.0, pacer.banked, 1e-12)
        assertEquals(
            0,
            pacer.stepsFor(0.25),
            "a cell that was walled in must not discharge the credit it sat on",
        )
    }

    @Test
    fun `one tick cannot spend an unbounded number of steps`() {
        val pacer = FlowPacer()
        assertEquals(FlowPacer.DEFAULT_MAX_STEPS_PER_TICK, pacer.stepsFor(1_000.0))
        assertEquals(
            0.0,
            pacer.banked,
            1e-12,
            "the surplus must be dropped, not banked, or the cap only delays the flood",
        )
    }

    @Test
    fun `a nonsense rate is ignored rather than banked`() {
        val pacer = FlowPacer()
        assertEquals(0, pacer.stepsFor(Double.NaN))
        assertEquals(0, pacer.stepsFor(-5.0))
        assertEquals(0, pacer.stepsFor(Double.POSITIVE_INFINITY))
        assertEquals(0.0, pacer.banked, 1e-12)
    }
}
