package dev.aetheris.engine.chem

/**
 * The volume units, fixed in one place so that buckets, test tubes, tanks and the world all agree.
 *
 * The convention is the one Create uses, and the one vanilla's own fluid accounting implies: **a bucket is
 * one litre**, and a millibucket is a millilitre. Vanilla never claims a fluid block is a cubic metre
 * either - it counts a source block as one bucket - so a solution block holds one bucket, which is what
 * makes `1 bucket = 1 cell` true and keeps every container's number meaningful.
 *
 *     1 mB = 1 mL      1 bucket = 1000 mB = 1 L      1 solution block = 1 bucket = 1000 mB
 *
 * Internally everything stays SI - [Mixture.volume] is cubic metres - and these constants exist so the
 * conversion happens in exactly one place instead of being spelled out at each container. It also means the
 * amounts a chemist writes work directly: 55.5 mol of water is a litre, and a litre fills a block.
 */
object FluidUnits {

    /** Millibuckets in one bucket, i.e. millilitres in a litre. */
    const val MILLIBUCKETS_PER_BUCKET = 1000

    /** Cubic metres in one bucket: a bucket is a litre, not a cubic metre. */
    const val CUBIC_METRES_PER_BUCKET = 1.0e-3

    /** The volume of one solution block, m^3. One bucket, so a bucket fills a block exactly. */
    const val CELL_VOLUME = CUBIC_METRES_PER_BUCKET

    /** Millibuckets one solution block holds. */
    const val MILLIBUCKETS_PER_CELL = MILLIBUCKETS_PER_BUCKET

    /** A test tube's capacity in millibuckets: a quarter of a block. */
    const val TEST_TUBE_MILLIBUCKETS = 250

    fun cubicMetres(millibuckets: Double): Double =
        millibuckets * CUBIC_METRES_PER_BUCKET / MILLIBUCKETS_PER_BUCKET

    fun millibuckets(cubicMetres: Double): Double =
        cubicMetres * MILLIBUCKETS_PER_BUCKET / CUBIC_METRES_PER_BUCKET
}
