package dev.aetheris.engine.chem

/** Thrown when a formula string cannot be parsed. */
class FormulaException(message: String) : IllegalArgumentException(message)

/**
 * An immutable molecular formula: a multiset of element symbols plus an optional charge.
 *
 * Supported syntax:
 *  - element symbols: `H2O`, `Fe2O3`
 *  - nested groups:   `Ca(OH)2`, `Fe2(SO4)3`, `[Fe(CN)6]`
 *  - hydrates:        `CuSO4·5H2O` (also accepts `.` or `*` as the separator)
 *  - ionic charge:    `SO4^2-`, `Na^+`, `Fe^3+`
 *
 * Molar mass is derived from [Elements], never stored: add or correct an element weight in
 * one place and every formula in the game updates.
 */
class Formula internal constructor(
    counts: Map<String, Int>,
    val charge: Int,
) {
    /** Element symbol to atom count, in insertion order. */
    val counts: Map<String, Int> = LinkedHashMap(counts)

    /** Molar mass in g/mol, summed from standard atomic weights. */
    val molarMass: Double = this.counts.entries.sumOf { (symbol, n) ->
        Elements.require(symbol).atomicMass * n
    }

    val elements: Set<String> get() = counts.keys

    fun countOf(symbol: String): Int = counts[symbol] ?: 0

    /** True when the formula contains no element outside [allowed]. */
    fun only(vararg allowed: String): Boolean {
        val set = allowed.toSet()
        return counts.keys.all { it in set }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Formula && counts == other.counts && charge == other.charge)

    override fun hashCode(): Int = 31 * counts.hashCode() + charge

    override fun toString(): String {
        val sb = StringBuilder()
        for ((symbol, n) in counts) {
            sb.append(symbol)
            if (n != 1) sb.append(n)
        }
        if (charge != 0) {
            sb.append('^')
            val magnitude = kotlin.math.abs(charge)
            if (magnitude != 1) sb.append(magnitude)
            sb.append(if (charge > 0) '+' else '-')
        }
        return sb.toString()
    }

    companion object {
        fun of(vararg pairs: Pair<String, Int>): Formula = of(pairs.toMap(), 0)

        fun of(counts: Map<String, Int>, charge: Int = 0): Formula {
            require(counts.isNotEmpty()) { "Formula must contain at least one element" }
            val copy = LinkedHashMap<String, Int>()
            for ((symbol, n) in counts) {
                require(n > 0) { "Atom count must be positive for '$symbol' (was $n)" }
                Elements.require(symbol)
                copy[symbol] = n
            }
            return Formula(copy, charge)
        }

        fun parse(text: String): Formula = Parser(text).parse()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Formula {
            val counts = sequence("^")
            var charge = 0
            if (i < s.length && s[i] == '^') {
                i++
                charge = parseCharge()
            }
            if (i != s.length) throw FormulaException("Unexpected '${s[i]}' at index $i in \"$s\"")
            if (counts.isEmpty()) throw FormulaException("Empty formula: \"$s\"")
            return Formula(counts, charge)
        }

        /** Parses terms until end of input or one of [terminators] is reached. */
        private fun sequence(terminators: String): LinkedHashMap<String, Int> {
            val counts = LinkedHashMap<String, Int>()
            while (i < s.length) {
                val c = s[i]
                if (c in terminators) break
                when {
                    c == '(' || c == '[' -> {
                        val close = if (c == '(') ')' else ']'
                        i++
                        val inner = sequence(close.toString())
                        if (i >= s.length || s[i] != close) {
                            throw FormulaException("Missing '$close' for group opened in \"$s\"")
                        }
                        i++
                        merge(counts, inner, parseCount())
                    }
                    c == '·' || c == '.' || c == '*' -> {
                        i++
                        val mult = parseCount()
                        merge(counts, sequence("·.*^"), mult)
                    }
                    c.isUpperCase() -> {
                        val start = i
                        i++
                        if (i < s.length && s[i].isLowerCase()) i++
                        val symbol = s.substring(start, i)
                        if (Elements.of(symbol) == null) {
                            throw FormulaException("Unknown element '$symbol' at index $start in \"$s\"")
                        }
                        counts[symbol] = (counts[symbol] ?: 0) + parseCount()
                    }
                    else -> throw FormulaException("Unexpected '$c' at index $i in \"$s\"")
                }
            }
            return counts
        }

        private fun parseCount(): Int {
            if (i >= s.length || !s[i].isDigit()) return 1
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            val n = s.substring(start, i).toIntOrNull()
                ?: throw FormulaException("Count too large in \"$s\"")
            if (n <= 0) throw FormulaException("Count must be positive in \"$s\"")
            return n
        }

        private fun parseCharge(): Int {
            if (i >= s.length) throw FormulaException("Dangling '^' in \"$s\"")
            var magnitude: Long? = null
            if (s[i].isDigit()) {
                val start = i
                while (i < s.length && s[i].isDigit()) i++
                magnitude = s.substring(start, i).toLongOrNull()
                    ?: throw FormulaException("Bad charge magnitude in \"$s\"")
            }
            var sign = 0
            if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                sign = if (s[i] == '+') 1 else -1
                i++
            }
            if (magnitude == null && sign == 0) throw FormulaException("Bad charge in \"$s\"")
            if (magnitude == null) magnitude = 1L
            if (sign == 0) sign = 1
            val value = magnitude * sign
            if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
                throw FormulaException("Charge out of range in \"$s\"")
            }
            return value.toInt()
        }

        private fun merge(target: MutableMap<String, Int>, source: Map<String, Int>, multiplier: Int) {
            for ((symbol, n) in source) {
                target[symbol] = (target[symbol] ?: 0) + n * multiplier
            }
        }
    }
}
