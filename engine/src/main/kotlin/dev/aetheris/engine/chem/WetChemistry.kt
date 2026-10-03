package dev.aetheris.engine.chem

/**
 * The curated wet-chemistry slice: the ions and sparingly soluble salts that make the
 * "a solution meets a reagent and something precipitates" demo work.
 *
 * The substances themselves live in data (`data/aetheris/species/aqueous.json`); this object only
 * names their ids and pairs ions with the solids they form. Adding another precipitate is one
 * [PrecipitationTemplate.Rule] plus its species record - no engine change.
 */
object WetChemistry {

    const val WATER = "aetheris:water"
    const val SODIUM_ION = "aetheris:sodium_ion"
    const val HYDROXIDE_ION = "aetheris:hydroxide_ion"
    const val COPPER_2_ION = "aetheris:copper_2_ion"
    const val SULFATE_ION = "aetheris:sulfate_ion"
    const val IRON_2_ION = "aetheris:iron_2_ion"
    const val COPPER_2_HYDROXIDE = "aetheris:copper_2_hydroxide"
    const val IRON_2_HYDROXIDE = "aetheris:iron_2_hydroxide"
    const val CALCIUM_CARBONATE = "aetheris:calcium_carbonate"

    /** The most sparingly soluble thing in the catalogue, and the one that demonstrates why that matters. */
    const val CALCIUM_2_ION = "aetheris:calcium_2_ion"
    const val CARBONATE_ION = "aetheris:carbonate_ion"

    /**
     * Solid iron as the shipped catalogue names it.
     *
     * The NIST import supplies the condensed metals, so the id is theirs; the curated aqueous file supplies
     * the ions. This constant is the seam between the two.
     */
    const val IRON = "nist:iron"

    /** Cu^2+ + 2 OH^- <-> Cu(OH)2(s), Fe^2+ + 2 OH^- <-> Fe(OH)2(s), Ca^2+ + CO3^2- <-> CaCO3(s). */
    val precipitation: ReactionTemplate = PrecipitationTemplate(
        rules = listOf(
            PrecipitationTemplate.Rule(COPPER_2_HYDROXIDE, mapOf(COPPER_2_ION to 1.0, HYDROXIDE_ION to 2.0)),
            PrecipitationTemplate.Rule(IRON_2_HYDROXIDE, mapOf(IRON_2_ION to 1.0, HYDROXIDE_ION to 2.0)),
            // The sparingly soluble one, and the reason it is worth having: its solubility product is about
            // 3e-9, so a litre of "1 M calcium carbonate" cannot exist. Nothing tells it that - the K falls out
            // of the three formation energies below, which is what makes a saturated solution stop on its own.
            PrecipitationTemplate.Rule(CALCIUM_CARBONATE, mapOf(CALCIUM_2_ION to 1.0, CARBONATE_ION to 1.0)),
        ),
    )

    /**
     * Metals that acid actually attacks, with the charge of the ion each becomes.
     *
     * Iron only, and copper deliberately absent even though `aetheris:copper_2_ion` exists: copper is below
     * hydrogen in the reactivity series, so hydrochloric acid leaves it alone. Listing it would invent a
     * reaction, which is exactly the mistake this map exists to make impossible.
     */
    val metalAcid: ReactionTemplate = MetalAcidTemplate(
        metals = mapOf(IRON to 2),
    )
}
