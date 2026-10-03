package dev.aetheris.engine.chem.structure

import dev.aetheris.engine.chem.Elements

/** Thrown when a structure string cannot be parsed. */
class SmilesException(message: String) : IllegalArgumentException(message)

/**
 * Parser for the subset of SMILES a chemistry engine needs to recognise functional groups.
 *
 * Supported: the organic subset (`B C N O P S F Cl Br I`) and aromatic `b c n o p s` without
 * brackets; bracket atoms `[Na+]`, `[nH]`, `[CH3]`, `[O-]` with isotopes, charges and hydrogen
 * counts; branches `(...)`; ring closures `1`-`9` and `%10`-`%99`; bonds `- = # : / \`; and the
 * component separator `.` for salts and mixtures.
 *
 * Deliberately not modelled: chirality (`@`, `@@`) and atom classes (`:1`) are accepted and
 * discarded, because nothing in this engine is stereospecific yet. Wildcard atoms (`*`) are
 * rejected rather than silently mis-read.
 *
 * Fragments separated by `.` become one [Molecule] with disconnected components. That is the right
 * shape for a dissolved salt, whose ions must still count towards the formula and whose groups must
 * still be found.
 */
object Smiles {

    /** Elements that may be written without brackets. */
    private val ORGANIC_SUBSET = setOf("B", "C", "N", "O", "P", "S", "F", "Cl", "Br", "I")

    /** Lower-case aromatic element symbols. */
    private const val AROMATIC_LOWER = "bcnops"

    fun parse(text: String): Molecule {
        if (text.isBlank()) throw SmilesException("Empty structure string")
        return Parser(text).parse()
    }

    private class Parser(private val s: String) {
        private var i = 0
        private val atoms = ArrayList<Atom>()
        private val bonds = ArrayList<Bond>()
        private var current = -1
        private var pendingOrder: Double? = null
        private var pendingAromatic = false
        private val branches = ArrayList<Frame>()
        private val openRings = HashMap<Int, OpenRing>()

        /** A saved position, so `)` can return to the atom the branch hung off. */
        private class Frame(val atom: Int, val order: Double?, val aromatic: Boolean)

        /** A ring bond waiting for its other end. */
        private class OpenRing(val atom: Int, val order: Double?, val aromatic: Boolean)

        fun parse(): Molecule {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '(' -> {
                        if (current < 0) fail("branch opened before any atom")
                        branches.add(Frame(current, pendingOrder, pendingAromatic))
                        pendingOrder = null
                        pendingAromatic = false
                        i++
                    }

                    c == ')' -> {
                        if (branches.isEmpty()) fail("unbalanced ')'")
                        val frame = branches.removeAt(branches.size - 1)
                        current = frame.atom
                        pendingOrder = frame.order
                        pendingAromatic = frame.aromatic
                        i++
                    }

                    c == '.' -> {
                        if (pendingOrder != null || pendingAromatic) {
                            fail("bond symbol before a component separator")
                        }
                        current = -1
                        i++
                    }

                    c == '-' -> setBond(1.0, false)
                    c == '=' -> setBond(2.0, false)
                    c == '#' -> setBond(3.0, false)
                    c == ':' -> setBond(1.5, true)
                    c == '/' || c == '\\' -> setBond(1.0, false)

                    c == '%' -> {
                        i++
                        closeRing(readRingNumber(2))
                    }

                    c.isDigit() -> closeRing(readRingNumber(1))

                    c == '[' -> attach(parseBracketAtom())

                    else -> attach(parseSubsetAtom())
                }
            }
            if (branches.isNotEmpty()) fail("unclosed '('")
            if (openRings.isNotEmpty()) fail("unclosed ring bond ${openRings.keys.min()}")
            if (pendingOrder != null || pendingAromatic) fail("dangling bond symbol at the end")
            return Molecule(atoms, bonds)
        }

        private fun setBond(order: Double, aromatic: Boolean) {
            if (pendingOrder != null || pendingAromatic) fail("two bond symbols in a row")
            pendingOrder = order
            pendingAromatic = aromatic
            i++
        }

        private fun attach(atom: Atom) {
            val index = atoms.size
            atoms.add(atom)
            if (current < 0) {
                if (pendingOrder != null || pendingAromatic) fail("a bond symbol cannot start a structure")
            } else {
                val bothAromatic = atoms[current].aromatic && atom.aromatic
                val order = pendingOrder ?: if (bothAromatic) 1.5 else 1.0
                val aromatic = pendingAromatic || (pendingOrder == null && bothAromatic)
                bonds.add(Bond(current, index, order, aromatic))
            }
            pendingOrder = null
            pendingAromatic = false
            current = index
        }

        private fun closeRing(number: Int) {
            if (current < 0) fail("ring bond $number before any atom")
            val open = openRings.remove(number)
            if (open == null) {
                openRings[number] = OpenRing(current, pendingOrder, pendingAromatic)
            } else {
                val bothAromatic = atoms[open.atom].aromatic && atoms[current].aromatic
                val unspecific = pendingOrder == null && open.order == null
                val order = pendingOrder ?: open.order ?: if (bothAromatic) 1.5 else 1.0
                val aromatic = pendingAromatic || open.aromatic || (unspecific && bothAromatic)
                bonds.add(Bond(open.atom, current, order, aromatic))
            }
            pendingOrder = null
            pendingAromatic = false
        }

        private fun readRingNumber(digits: Int): Int {
            if (i + digits > s.length) fail("ring bond number runs past the end of the string")
            val text = s.substring(i, i + digits)
            if (!text.all { it.isDigit() }) fail("ring bond number must be digits, was '$text'")
            i += digits
            return text.toInt()
        }

        private fun parseSubsetAtom(): Atom {
            if (i + 1 < s.length) {
                val pair = s.substring(i, i + 2)
                if (pair == "Cl" || pair == "Br") {
                    i += 2
                    return Atom(pair)
                }
            }
            val c = s[i]
            if (c in AROMATIC_LOWER) {
                i++
                return Atom(c.uppercase(), aromatic = true)
            }
            val symbol = c.toString()
            if (symbol in ORGANIC_SUBSET) {
                i++
                return Atom(symbol)
            }
            fail("unexpected character '$c'")
        }

        private fun parseBracketAtom(): Atom {
            expect('[')

            // Isotope prefix: read and discarded, nothing here depends on nuclear mass.
            while (i < s.length && s[i].isDigit()) i++

            if (i >= s.length) fail("unterminated bracket atom")
            var aromatic = false
            val element: String = when {
                s[i] == '*' -> fail("wildcard atoms are not supported")

                s[i].isLowerCase() -> {
                    val c = s[i]
                    if (c !in AROMATIC_LOWER) fail("unexpected lower-case element '$c' inside brackets")
                    aromatic = true
                    i++
                    c.uppercase()
                }

                else -> {
                    val start = i
                    i++
                    if (i < s.length && s[i].isLowerCase()) {
                        val candidate = s.substring(start, i + 1)
                        if (Elements.of(candidate) != null) i++
                    }
                    val symbol = s.substring(start, i)
                    if (Elements.of(symbol) == null) fail("unknown element '$symbol'")
                    symbol
                }
            }

            // Chirality: accepted, not modelled.
            while (i < s.length && s[i] == '@') i++

            var hydrogens = 0
            if (i < s.length && s[i] == 'H') {
                i++
                val start = i
                while (i < s.length && s[i].isDigit()) i++
                hydrogens = if (i > start) s.substring(start, i).toInt() else 1
            }

            var charge = 0
            if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val symbol = s[i]
                val sign = if (symbol == '+') 1 else -1
                i++
                val start = i
                while (i < s.length && s[i].isDigit()) i++
                charge = if (i > start) {
                    sign * s.substring(start, i).toInt()
                } else {
                    var magnitude = 1
                    while (i < s.length && s[i] == symbol) {
                        i++
                        magnitude++
                    }
                    sign * magnitude
                }
            }

            // Atom class: accepted, not modelled.
            while (i < s.length && s[i] == ':') {
                i++
                while (i < s.length && s[i].isDigit()) i++
            }

            expect(']')
            return Atom(element, charge, hydrogens, aromatic)
        }

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) fail("expected '$c'")
            i++
        }

        private fun fail(message: String): Nothing =
            throw SmilesException("$message (at index $i in \"$s\")")
    }
}
