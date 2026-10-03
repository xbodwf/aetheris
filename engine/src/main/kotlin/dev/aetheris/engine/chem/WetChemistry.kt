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

    /** Cu^2+ + 2 OH^- <-> Cu(OH)2(s), and Fe^2+ + 2 OH^- <-> Fe(OH)2(s). */
    val precipitation: ReactionTemplate = PrecipitationTemplate(
        rules = listOf(
            PrecipitationTemplate.Rule(COPPER_2_HYDROXIDE, mapOf(COPPER_2_ION to 1.0, HYDROXIDE_ION to 2.0)),
            PrecipitationTemplate.Rule(IRON_2_HYDROXIDE, mapOf(IRON_2_ION to 1.0, HYDROXIDE_ION to 2.0)),
        ),
    )
}
