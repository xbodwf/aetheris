#!/usr/bin/env python3
"""Convert the NASA-7 thermodynamic database into Aetheris' species JSON format.

Source: the public NASA thermodynamic database (Gordon/McBride, NASA SP-273 and TM-4513),
as redistributed in Cantera's ``data/nasa_gas.yaml``. The polynomials give cp(T), h(T) and
s(T); Aetheris currently stores the values at the 298.15 K reference, and keeps the raw
coefficients alongside so a temperature-dependent engine can use them later.

Usage:
    python3 tools/convert_nasa.py [--input tools/data/nasa_gas.yaml] [--output <path>]

The output is a single ``aetheris:species/v1`` JSON file, which the engine's SpeciesCodec
reads directly (see engine/src/main/kotlin/dev/aetheris/engine/data/SpeciesCodec.kt).
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("PyYAML is required: python3 -m pip install --user pyyaml")


class _NoBoolLoader(yaml.SafeLoader):
    """SafeLoader without YAML 1.1 booleans, so the species name ``NO`` stays a string."""


_NoBoolLoader.yaml_implicit_resolvers = {
    ch: [(tag, regexp) for tag, regexp in resolvers if tag != "tag:yaml.org,2002:bool"]
    for ch, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}

R = 8.314462618  # J/(mol*K)
T0 = 298.15  # K, the engine's reference temperature

# The 118 element symbols Aetheris knows. Anything else (D, E, ...) is dropped.
ELEMENTS = {
    "H", "He", "Li", "Be", "B", "C", "N", "O", "F", "Ne", "Na", "Mg", "Al", "Si", "P", "S",
    "Cl", "Ar", "K", "Ca", "Sc", "Ti", "V", "Cr", "Mn", "Fe", "Co", "Ni", "Cu", "Zn", "Ga",
    "Ge", "As", "Se", "Br", "Kr", "Rb", "Sr", "Y", "Zr", "Nb", "Mo", "Tc", "Ru", "Rh", "Pd",
    "Ag", "Cd", "In", "Sn", "Sb", "Te", "I", "Xe", "Cs", "Ba", "La", "Ce", "Pr", "Nd", "Pm",
    "Sm", "Eu", "Gd", "Tb", "Dy", "Ho", "Er", "Tm", "Yb", "Lu", "Hf", "Ta", "W", "Re", "Os",
    "Ir", "Pt", "Au", "Hg", "Tl", "Pb", "Bi", "Po", "At", "Rn", "Fr", "Ra", "Ac", "Th", "Pa",
    "U", "Np", "Pu", "Am", "Cm", "Bk", "Cf", "Es", "Fm", "Md", "No", "Lr", "Rf", "Db", "Sg",
    "Bh", "Hs", "Mt", "Ds", "Rg", "Cn", "Nh", "Fl", "Mc", "Lv", "Ts", "Og",
}


def nasa7_properties(coeffs: list[float], t: float) -> tuple[float, float, float]:
    """Return (h, cp, s) at temperature ``t`` for one NASA-7 coefficient set."""
    a1, a2, a3, a4, a5, a6, a7 = coeffs
    cp = R * (a1 + a2 * t + a3 * t**2 + a4 * t**3 + a5 * t**4)
    h = R * t * (a1 + a2 * t / 2 + a3 * t**2 / 3 + a4 * t**3 / 4 + a5 * t**4 / 5 + a6 / t)
    s = R * (a1 * math.log(t) + a2 * t + a3 * t**2 / 2 + a4 * t**3 / 3 + a5 * t**4 / 4 + a7)
    return h, cp, s


def hill_order(counts: dict[str, int]) -> list[str]:
    """Hill notation: C first, then H, then the rest alphabetically (else all alphabetical)."""
    if "C" in counts:
        rest = sorted(k for k in counts if k not in ("C", "H"))
        return ["C"] + (["H"] if "H" in counts else []) + rest
    return sorted(counts)


def formula_string(counts: dict[str, int], charge: int) -> str:
    parts = [el + (str(n) if n != 1 else "") for el, n in ((e, counts[e]) for e in hill_order(counts))]
    text = "".join(parts)
    if charge:
        magnitude = abs(charge)
        text += "^" + (str(magnitude) if magnitude != 1 else "") + ("+" if charge > 0 else "-")
    return text


def slug(name: str, charge: int) -> str:
    base = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_").lower() or "species"
    if charge > 0:
        return f"{base}_p{charge}"
    if charge < 0:
        return f"{base}_m{-charge}"
    return base


def convert(species_list: list[dict]) -> tuple[list[dict], dict[str, int]]:
    out: list[dict] = []
    seen: set[str] = set()
    skipped = {"no_atoms": 0, "unsupported_element": 0, "nonpositive_cp": 0, "not_nasa7": 0}

    for entry in species_list:
        name = str(entry.get("name", ""))
        composition = entry.get("composition", {}) or {}
        thermo = entry.get("thermo", {}) or {}

        if thermo.get("model") != "NASA7":
            skipped["not_nasa7"] += 1
            continue

        charge = -int(composition.get("E", 0))
        counts = {el: int(n) for el, n in composition.items() if el != "E"}

        if not counts:
            skipped["no_atoms"] += 1
            continue
        if any(el not in ELEMENTS for el in counts) or any(n <= 0 for n in counts.values()):
            skipped["unsupported_element"] += 1
            continue

        ranges = thermo.get("temperature-ranges") or []
        data = thermo.get("data") or []
        if len(ranges) != 3 or len(data) != 2:
            skipped["not_nasa7"] += 1
            continue

        low, high = [float(c) for c in data[0]], [float(c) for c in data[1]]
        coeffs = low if T0 <= ranges[1] else high
        hf, cp, s = nasa7_properties(coeffs, T0)
        if not all(math.isfinite(v) for v in (hf, cp, s)) or cp <= 0.0:
            skipped["nonpositive_cp"] += 1
            continue

        species_id = "aetheris:" + slug(name, charge)
        if species_id in seen:
            suffix = 2
            while f"{species_id}_{suffix}" in seen:
                suffix += 1
            species_id = f"{species_id}_{suffix}"
        seen.add(species_id)

        out.append(
            {
                "id": species_id,
                "name": name,
                "formula": formula_string(counts, charge),
                "phase": "GAS",
                "hf": round(hf, 3),
                "cp": round(cp, 4),
                "s": round(s, 4),
                "nasa7": {
                    "tRanges": [float(r) for r in ranges],
                    "low": low,
                    "high": high,
                },
            }
        )

    out.sort(key=lambda r: r["id"])
    return out, skipped


def main() -> int:
    here = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=here / "tools/data/nasa_gas.yaml")
    parser.add_argument(
        "--output",
        type=Path,
        default=here / "common/src/main/resources/data/aetheris/species/nasa_gas.json",
    )
    args = parser.parse_args()

    if not args.input.exists():
        sys.exit(f"Input not found: {args.input}\nDownload the NASA database first (see tools/data/).")

    document = yaml.load(args.input.read_text(encoding="utf-8"), Loader=_NoBoolLoader)
    records, skipped = convert(document["species"])

    payload = {"format": "aetheris:species/v1", "species": records}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"converted {len(records)} species -> {args.output}")
    print("skipped:", ", ".join(f"{k}={v}" for k, v in skipped.items() if v))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
