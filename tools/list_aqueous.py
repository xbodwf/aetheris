"""List the aqueous species the shipped data actually holds.

Written as a file rather than a one-liner because the quoting needed to survive PowerShell's here-string
handling is more trouble than the script.
"""

import glob
import json

AQUEOUS = []
for path in sorted(glob.glob("common/src/main/resources/data/aetheris/species/*.json")):
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    for species in data.get("species", []):
        if species["phase"] == "AQUEOUS":
            AQUEOUS.append((species["id"], species["formula"]))

print(f"aqueous species in the shipped data: {len(AQUEOUS)}")
for identifier, formula in AQUEOUS:
    print(f"   {identifier:34s} {formula}")

print()
for probe in ("sodium", "potassium", "calcium", "nitrate", "carbonate", "ammonium", "zinc", "silver"):
    hits = [identifier for identifier, _ in AQUEOUS if probe in identifier]
    print(f"   {probe:10s}: {' '.join(hits) if hits else 'MISSING'}")
