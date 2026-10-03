package dev.aetheris.engine.chem

import dev.aetheris.engine.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The unit ladder is a convention, and a convention that is only written down in prose drifts. These
 * tests are the definition: one bucket is a litre, one block is a bucket, and the amounts a chemist writes
 * fill a block without conversion.
 */
class FluidUnitsTest {

    @Test
    fun `a millibucket is a millilitre and a bucket is a litre`() {
        assertEquals(1000.0, FluidUnits.MILLIBUCKETS_PER_BUCKET.toDouble(), 0.0)
        assertEquals(1.0e-3, FluidUnits.CUBIC_METRES_PER_BUCKET, 1e-15)
        assertEquals(1000.0, FluidUnits.millibuckets(1.0e-3), 1e-9)
        assertEquals(1.0e-3, FluidUnits.cubicMetres(1000.0), 1e-15)
    }

    @Test
    fun `a solution block holds exactly one bucket`() {
        assertEquals(
            FluidUnits.MILLIBUCKETS_PER_BUCKET.toDouble(),
            FluidUnits.millibuckets(FluidUnits.CELL_VOLUME),
            1e-9,
        )
    }

    @Test
    fun `a litre of water fills a block, so a chemist's amounts need no conversion`() {
        // 55.5 mol of water is a litre, which is why this is the number somebody writes by instinct - and
        // under this convention it fills a block exactly, with no factor of a thousand in sight.
        val registry = SpeciesRegistry.defaults()
        val cell = Mixture(
            registry,
            FluidUnits.CELL_VOLUME,
            Constants.T0,
            mapOf(SpeciesRegistry.H2O_LIQUID to 55.5),
        )

        assertEquals(1.0, cell.condensedVolume() / cell.volume, 2e-3)
        assertEquals(55.5, cell.molarity(SpeciesRegistry.H2O_LIQUID), 0.3)
    }
}
