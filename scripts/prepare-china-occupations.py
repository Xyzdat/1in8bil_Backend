"""Immutable China extract of ILOEST Nov 2025. No network or database access."""
import csv
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "data/statistics/expanded/socio/occupations-modelled-2025.csv.gz"
RELEASE = "china-occupations-iloest-2025-v1"
INDICATOR = "EMP_2EMP_SEX_OCU_NB"


def encoded(obj):
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def build():
    rows = []
    with gzip.open(RAW, "rt", encoding="utf-8-sig", newline="") as stream:
        for line, row in enumerate(csv.DictReader(stream), 2):
            if row["ref_area"] != "CHN" or not 2000 <= int(row["time"]) <= 2025:
                continue
            dimensions = {key: row[key] for key in ("source", "sex", "classif1")}
            raw_value = row["obs_value"]
            value = float(raw_value) if raw_value else None
            quality = "MISSING" if value is None else "REJECTED" if value < 0 or row["obs_status"] == "U" else "VALID"
            provenance = dict(line=line, obs_status=row["obs_status"], note_source="", note_indicator="", note_classif="",
                              basis="ILO_MODELLED_ESTIMATE", modelEdition="Nov. 2025", rawValue=raw_value)
            key = hashlib.sha256(encoded(["iloest-china", "CHN", INDICATOR, int(row["time"]), dimensions])).hexdigest()
            rows.append(dict(key=key, sourceId="iloest-china", country="CHN", indicator=INDICATOR, year=int(row["time"]),
                             dimensions=dimensions, value=value if quality == "VALID" else None, quality=quality, provenance=provenance))
    rows.sort(key=lambda row: row["key"])
    assert rows and len({row["key"] for row in rows}) == len(rows)
    valid = [row for row in rows if row["quality"] == "VALID"]
    # Validate each cohort before shipping. Group 96 stays combined; never split it into 6 and 9.
    for year in range(2000, 2026):
        for sex in ("SEX_T", "SEX_F", "SEX_M"):
            cohort = {row["dimensions"]["classif1"]: row["value"] for row in valid if row["year"] == year and row["dimensions"]["sex"] == sex}
            assert set(cohort) == {"OCU_ISCO08_" + code for code in ("TOTAL", "1", "2", "3", "4", "5", "7", "8", "96")}
            # Independent rounded model outputs have small discrepancies (e.g. 0.021 thousand in 2020).
            assert abs(sum(value for code, value in cohort.items() if not code.endswith("TOTAL")) - cohort["OCU_ISCO08_TOTAL"]) < .05
    coverage = [dict(country="CHN", indicator=INDICATOR, validCount=len(valid), missingCount=sum(row["quality"] == "MISSING" for row in rows),
                     rejectedCount=sum(row["quality"] == "REJECTED" for row in rows), latestYear=max(row["year"] for row in valid))]
    source = dict(url="https://rplumber.ilo.org/data/indicator?format=.csv.gz&id=EMP_2EMP_SEX_OCU_NB_A", license="CC-BY-4.0",
                  licenseEvidence="https://www.ilo.org/rights-and-permissions", sourceUpdated="04/03/2026 06:48:58", modelEdition="Nov. 2025",
                  attribution="International Labour Organization, ILOSTAT, ILO modelled estimates, Nov. 2025. "
                  "This is an adaptation of a copyrighted work of the International Labour Organization (ILO). "
                  "This adaptation has not been prepared, reviewed or endorsed by the ILO and should not be considered an official ILO adaptation. "
                  "The ILO disclaims all responsibility for its content and accuracy. Responsibility rests solely with the author or authors of the adaptation.",
                  sha256=hashlib.sha256(RAW.read_bytes()).hexdigest(), notes=["Counts in thousands, modelled not census observations.",
                  "OCU_ISCO08_96 is the published combined group 6 + 9, not ISCO unit group 96. Retain without statistical splitting."])
    files = {"observations.json.gz": gzip.compress(encoded(rows), mtime=0), "coverage.json": encoded(coverage)}
    manifest = dict(schemaVersion=1, id=RELEASE, stage="work-income", retrievedOn="2026-11-26", minYear=2000, maxYear=2025,
                    countries=["CHN"], sources={"iloest-china": source}, indicators={INDICATOR: dict(sourceId="iloest-china",
                    label="Employment by sex and occupation — ILO modelled estimates Nov. 2025", unit="thousand_employed_persons", minimum=0, maximum=None)},
                    rowCount=len(rows), coverageCount=1, qualityCounts={quality: sum(row["quality"] == quality for row in rows) for quality in ("VALID", "MISSING", "REJECTED")},
                    hashes={file: hashlib.sha256(data).hexdigest() for file, data in files.items()})
    files["manifest.json"] = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
    dest = ROOT / "src/main/resources/statistics/socioeconomic" / RELEASE
    dest.mkdir(parents=True, exist_ok=True)
    for file, data in files.items():
        path = dest / file
        if path.exists() and path.read_bytes() != data:
            raise ValueError("Immutable release changed: " + file)
        path.write_bytes(data)
    print(json.dumps(dict(release=RELEASE, rows=len(rows), quality=manifest["qualityCounts"], latestYear=2025)))


if __name__ == "__main__":
    build()
