package dev.aetheris.engine.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aetheris.engine.chem.Formula
import dev.aetheris.engine.chem.Phase
import dev.aetheris.engine.chem.Species
import dev.aetheris.engine.chem.SpeciesRegistry

/** Thrown when a species data file cannot be understood. */
class SpeciesFormatException(message: String) : IllegalArgumentException(message)

/**
 * Raw NASA-7 polynomial coefficients, kept verbatim next to the derived constants.
 *
 * The simulation currently uses a single constant heat capacity, but storing the polynomial
 * means a future temperature-dependent implementation needs no re-download or re-conversion.
 * The arrays are `[a1, a2, a3, a4, a5, a6, a7]` for the low and high temperature ranges.
 */
data class Nasa7(
    val temperatureRanges: List<Double>,
    val low: List<Double>,
    val high: List<Double>,
)

/**
 * One species exactly as it appears on disk.
 *
 * This is the boundary between the simulation ([Species]) and the outside world (data files
 * generated from public thermochemical databases). Keeping it separate means the engine never
 * has to know whether a value came from a data pack, a converter, or a test fixture.
 *
 * @param formationEnthalpy standard enthalpy of formation at 298.15 K, J/mol.
 * @param heatCapacity      molar heat capacity at 298.15 K, J/(mol*K).
 * @param entropy           standard molar entropy at 298.15 K, J/(mol*K).
 * @param nasa7             original polynomial, when the record came from a NASA-7 source.
 */
data class SpeciesRecord(
    val id: String,
    val name: String,
    val formula: String,
    val phase: Phase,
    val formationEnthalpy: Double,
    val heatCapacity: Double,
    val entropy: Double = 0.0,
    /** ARGB tint for rendering a solution; 0 = colourless. Stored as "#RRGGBB" in JSON. */
    val color: Int = 0,
    /** Miscibility group for a liquid; solutes sharing it dissolve into one phase. */
    val solventGroup: String? = null,
    /** Explicit molar volume in m^3/mol; 0 = estimate from the phase. */
    val molarVolumeM3: Double = 0.0,
    val nasa7: Nasa7? = null,
    /**
     * Structural notation, when the substance's connectivity is known.
     *
     * Optional because most of the catalogue is inorganic: a formula is enough for an ion or a
     * mineral. It matters for organics, where `C2H6O` is ambiguous between ethanol and dimethyl
     * ether and only the structure says which functional groups are present.
     */
    val smiles: String? = null,
    /**
     * Dynamic viscosity in **millipascal-seconds** (numerically the same as the centipoise a chemistry
     * table quotes), or 0 when unknown.
     *
     * The unit is mPa*s on disk because that is how viscosity is tabulated in practice, which keeps
     * transcription from a reference free of factor-of-a-thousand mistakes; [Species] converts it to
     * the SI Pa*s the engine works in.
     */
    val viscosity: Double = 0.0,
)

/**
 * Reads and writes the `aetheris:species/v1` JSON files under a mod's `data/aetheris` directory.
 *
 * The format is intentionally flat and unit-annotated so that any converter (the bundled
 * `tools/convert_nasa.py`, or a future data-pack editor) can produce it without knowing the
 * engine internals:
 *
 * ```json
 * {
 *   "format": "aetheris:species/v1",
 *   "species": [
 *     { "id": "aetheris:methane", "name": "Methane", "formula": "CH4", "smiles": "C",
 *       "phase": "GAS", "hf": -74810.0, "cp": 35.69, "s": 186.25,
 *       "nasa7": { "tRanges": [200, 1000, 6000], "low": [...7], "high": [...7] } }
 *   ]
 * }
 * ```
 */
object SpeciesCodec {

    const val FORMAT = "aetheris:species/v1"

    /** One millipascal-second in pascal-seconds: viscosity is stored in the former, used in the latter. */
    private const val MILLIPASCAL_SECONDS = 1.0e-3

    /** Parses a data file into records without touching any registry. */
    fun parse(json: String): List<SpeciesRecord> {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw SpeciesFormatException("Species file is not a JSON object: ${e.message}")
        }
        val format = root.get("format")?.asString
            ?: throw SpeciesFormatException("Species file is missing its 'format' field")
        if (format != FORMAT) {
            throw SpeciesFormatException("Unsupported species format '$format' (expected '$FORMAT')")
        }
        val array = root.getAsJsonArray("species")
            ?: throw SpeciesFormatException("Species file is missing its 'species' array")
        return array.mapIndexed { index, element ->
            if (!element.isJsonObject) throw SpeciesFormatException("species[$index] is not an object")
            parseRecord(element.asJsonObject, index)
        }
    }

    /** Parses [json] and registers every species into [registry], returning them in file order. */
    fun load(json: String, registry: SpeciesRegistry): List<Species> =
        parse(json).map { record ->
            registry.register(
                Species(
                    id = record.id,
                    name = record.name,
                    formula = Formula.parse(record.formula),
                    phase = record.phase,
                    formationEnthalpy = record.formationEnthalpy,
                    heatCapacity = record.heatCapacity,
                    entropy = record.entropy,
                    color = record.color,
                    solventGroup = record.solventGroup,
                    molarVolumeM3 = record.molarVolumeM3,
                    smiles = record.smiles,
                    viscosityPaS = record.viscosity * MILLIPASCAL_SECONDS,
                )
            )
        }

    /** Serialises records back into the on-disk format (stable field order). */
    fun encode(records: List<SpeciesRecord>): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        val array = com.google.gson.JsonArray()
        for (record in records) {
            val o = JsonObject()
            o.addProperty("id", record.id)
            o.addProperty("name", record.name)
            o.addProperty("formula", record.formula)
            record.smiles?.let { o.addProperty("smiles", it) }
            o.addProperty("phase", record.phase.name)
            o.addProperty("hf", record.formationEnthalpy)
            o.addProperty("cp", record.heatCapacity)
            o.addProperty("s", record.entropy)
            if (record.color != 0) {
                o.addProperty("color", "#%06X".format(record.color and 0xFFFFFF))
            }
            record.solventGroup?.let { o.addProperty("solvent", it) }
            if (record.viscosity != 0.0) o.addProperty("viscosity", record.viscosity)
            if (record.molarVolumeM3 != 0.0) o.addProperty("molarVolume", record.molarVolumeM3)
            record.nasa7?.let { n ->
                val n7 = JsonObject()
                n7.add("tRanges", toArray(n.temperatureRanges))
                n7.add("low", toArray(n.low))
                n7.add("high", toArray(n.high))
                o.add("nasa7", n7)
            }
            array.add(o)
        }
        root.add("species", array)
        return GSON.toJson(root)
    }

    private fun toArray(values: List<Double>): com.google.gson.JsonArray {
        val a = com.google.gson.JsonArray()
        values.forEach { a.add(it) }
        return a
    }

    private fun parseRecord(o: JsonObject, index: Int): SpeciesRecord {
        fun required(name: String): com.google.gson.JsonElement =
            o.get(name) ?: throw SpeciesFormatException("species[$index] is missing '$name'")

        val id = required("id").asString
        val name = required("name").asString
        val formula = required("formula").asString
        val phaseName = required("phase").asString
        val phase = Phase.values().firstOrNull { it.name == phaseName }
            ?: throw SpeciesFormatException(
                "species[$index] ('$id') has unknown phase '$phaseName' " +
                    "(expected one of ${Phase.values().joinToString { it.name }})"
            )
        val hf = required("hf").asDouble
        val cp = required("cp").asDouble
        val entropy = o.get("s")?.asDouble ?: 0.0
        val color = o.get("color")?.asString?.let { parseColor(it, id) } ?: 0
        val solventGroup = o.get("solvent")?.asString
        val molarVolume = o.get("molarVolume")?.asDouble ?: 0.0
        val nasa7 = o.getAsJsonObject("nasa7")?.let { parseNasa7(it, id) }
        val smiles = o.get("smiles")?.asString
        val viscosity = o.get("viscosity")?.asDouble ?: 0.0
        return SpeciesRecord(
            id, name, formula, phase, hf, cp, entropy, color, solventGroup, molarVolume, nasa7,
            smiles, viscosity,
        )
    }

    /** Parses "#RRGGBB" (opaque) or "#AARRGGBB" into a packed ARGB int. */
    private fun parseColor(text: String, id: String): Int {
        val hex = text.removePrefix("#")
        val value = hex.toLongOrNull(16)
            ?: throw SpeciesFormatException("species '$id' has a malformed color '$text'")
        return when (hex.length) {
            6 -> (0xFF shl 24) or value.toInt()
            8 -> value.toInt()
            else -> throw SpeciesFormatException("species '$id' color must be #RRGGBB or #AARRGGBB, was '$text'")
        }
    }

    private fun parseNasa7(o: JsonObject, id: String): Nasa7 {
        fun doubles(field: String, size: Int?): List<Double> {
            val array = o.getAsJsonArray(field)
                ?: throw SpeciesFormatException("species '$id' nasa7 is missing '$field'")
            if (size != null && array.size() != size) {
                throw SpeciesFormatException(
                    "species '$id' nasa7 '$field' has ${array.size()} values, expected $size"
                )
            }
            return array.map { it.asDouble }
        }
        return Nasa7(
            temperatureRanges = doubles("tRanges", 3),
            low = doubles("low", 7),
            high = doubles("high", 7),
        )
    }

    private val GSON = com.google.gson.GsonBuilder().setPrettyPrinting().create()
}
