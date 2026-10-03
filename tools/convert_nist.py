#!/usr/bin/env python3
"""Import thermochemistry from the NIST Chemistry WebBook into the Aetheris species format.

NIST has no bulk API, so this scrapes the per-species pages. For each species it reads the
gas-phase table (Mask=1) and the condensed-phase table (Mask=2) and pulls the first value of
each of dHf, S and Cp, which is normally the CODATA/Review value. Units are converted to
J/mol and J/(mol*K).

Only a curated list is imported (see SPECIES); ids are derived from the formula so they never
collide with the NASA catalogue's formula-derived ids. Output is our `aetheris:species/v1` JSON.

Usage:  python3 tools/convert_nist.py > common/src/main/resources/data/aetheris/species/nist.json
"""
import html
import json
import re
import sys
import urllib.request

BASE = "https://webbook.nist.gov/cgi/cbook.cgi"
UA = "Aetheris-DataImporter/0.1 (offline mod data tool)"

# (namespaced id, display name, formula, phase, NIST id). NIST id is 'C' + CAS without dashes.
# The `nist:` namespace is deliberate: it keeps imported ids from colliding with the NASA
# catalogue's formula-derived `aetheris:*` ids, so both can be loaded side by side.
SPECIES = [
    ("nist:calcium_carbonate", "Calcium carbonate (calcite)", "CaCO3", "SOLID", "C13397267"),
    ("nist:calcium_oxide", "Calcium oxide", "CaO", "SOLID", "C1305788"),
    ("nist:iron_3_oxide", "Iron(III) oxide (hematite)", "Fe2O3", "SOLID", "C1317608"),
    ("nist:copper_2_hydroxide", "Copper(II) hydroxide", "Cu(OH)2", "SOLID", "C20427592"),
    ("nist:silicon_dioxide", "Silicon dioxide (quartz)", "SiO2", "SOLID", "C14808607"),
    ("nist:graphite", "Carbon (graphite)", "C", "SOLID", "C7782425"),
    ("nist:water_liquid", "Water", "H2O", "LIQUID", "C7732185"),
    # Solid metals. NASA's gas set only has the vapours (Al(g), Fe(g) ...); these are the phases
    # that actually exist at room temperature.
    ("nist:aluminium", "Aluminium", "Al", "SOLID", "C7429905"),
    ("nist:iron", "Iron", "Fe", "SOLID", "C7439896"),
    ("nist:copper", "Copper", "Cu", "SOLID", "C7440508"),
    ("nist:zinc", "Zinc", "Zn", "SOLID", "C7440666"),
]


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=40) as response:
        return response.read().decode("utf-8", "replace")


def flatten(page: str) -> str:
    text = re.sub(r"<[^>]+>", " ", page)
    text = html.unescape(text)
    return re.sub(r"\s+", " ", text)


def first_number(text: str, label: str):
    """First number after `label`, tolerating NIST's optional state suffixes like ',1 bar'."""
    pattern = re.escape(label) + r"(?:,\s*(?:1 bar|[A-Za-z0-9]+))?\s+(-?\d+(?:\.\d+)?)"
    match = re.search(pattern, text)
    return float(match.group(1)) if match else None


def atom_count(formula: str) -> int:
    """Rough atom count for a Dulong-Petit heat-capacity estimate (groups counted once)."""
    return max(1, sum(1 for _ in re.finditer(r"[A-Z][a-z]?", formula)))


def scrape(nist_id: str, phase: str, formula: str):
    """Returns (hf J/mol, cp J/mol/K, s J/mol/K) or None when a page has no thermochemistry."""
    state = {"GAS": "gas", "LIQUID": "liquid", "SOLID": "solid"}.get(phase, "solid")
    masks = ["1", "2"] if phase == "GAS" else ["2", "1"]
    hf = cp = entropy = None
    for mask in masks:
        try:
            text = flatten(fetch(f"{BASE}?ID={nist_id}&Units=SI&Mask={mask}"))
        except Exception as exc:  # noqa: BLE001 - report and continue
            print(f"# {nist_id} mask={mask}: {exc}", file=sys.stderr)
            continue
        if hf is None:
            hf = first_number(text, f"f H\u00b0 {state}")
        if entropy is None:
            entropy = first_number(text, f"S\u00b0 {state}")
        if cp is None:
            for label in (f"C p,{state}", f"C p {state}", "C p"):
                cp = first_number(text, label)
                if cp is not None:
                    break
        if hf is not None and entropy is not None and cp is not None:
            break
    if hf is None:
        # An element in its standard state has dHf = 0 by definition; NIST often omits the row.
        if re.fullmatch(r"[A-Z][a-z]?", formula):
            hf = 0.0
        else:
            return None
    # Cp is reported in J/mol/K; when absent, estimate with Dulong-Petit (3R per atom).
    cp_value = cp if cp and cp > 0 else 24.9 * atom_count(formula)
    return {"hf": hf * 1000.0, "cp": round(cp_value, 2), "s": round(entropy, 3) if entropy is not None else 0.0}


def main() -> int:
    records = []
    for ident, name, formula, phase, nist_id in SPECIES:
        values = scrape(nist_id, phase, formula)
        if values is None:
            print(f"# skipped {ident}: no thermochemistry found for {nist_id}", file=sys.stderr)
            continue
        records.append({
            "id": ident,
            "name": name,
            "formula": formula,
            "phase": phase,
            "hf": round(values["hf"], 1),
            "cp": values["cp"],
            "s": values["s"],
        })
    json.dump({"format": "aetheris:species/v1", "species": records}, sys.stdout, indent=2)
    sys.stdout.write("\n")
    print(f"# imported {len(records)}/{len(SPECIES)} species from NIST", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
