"""Offline extraction into immutable, licensed reference releases. Never connects to the DB.

Python 3 + openpyxl (read-only XLSX extraction); other formats use the standard library.
Run --stage family-housing first, then --stage work-income. Raw downloads are ignored by Git.
"""
import argparse
import csv
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent
MIN_YEAR, MAX_YEAR = 2000, 2025  # Keep projections/future years out of this historical release.
AS_OF = "2026-11-26"
RELEASE_IDS = {"family-housing": "family-housing-2026-11-v1", "work-income": "work-income-2026-11-v2"}


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def number(raw):
    if raw is None or str(raw).strip() in ("", "..", "...", "NA"):
        return None
    value = float(raw)
    if not math.isfinite(value):
        raise ValueError(f"Non-finite source number: {raw}")
    return value


class Release:
    def __init__(self, stage, raw_dir):
        self.stage, self.raw_dir = stage, raw_dir
        self.id = RELEASE_IDS[stage]
        self.countries = sorted(c["iso3"] for c in json.loads((ROOT / "src/main/resources/statistics/country-catalog.json").read_text(encoding="utf-8")))
        self.sources, self.indicators, self.rows = {}, {}, {}

    def indicator(self, key, source, label, unit, minimum=0, maximum=None, **extra):
        self.indicators[key] = dict(sourceId=source, label=label, unit=unit, minimum=minimum, maximum=maximum, **extra)

    def add(self, source, country, indicator, year, dimensions, raw, provenance, reject_reason=None):
        if country not in self.countries or not MIN_YEAR <= year <= MAX_YEAR:
            return
        spec = self.indicators[indicator]
        value = number(raw)
        quality = "MISSING" if value is None else "VALID"
        if value is not None and (value < spec["minimum"] or (spec["maximum"] is not None and value > spec["maximum"])):
            reject_reason = "OUT_OF_RANGE"
        if value is not None and reject_reason:
            quality = "REJECTED"
            provenance = dict(provenance, rejection=reject_reason, rawValue=str(raw))
            value = None
        key = digest(encoded([source, country, indicator, year, dimensions]))
        row = dict(key=key, sourceId=source, country=country, indicator=indicator, year=year,
                   dimensions=dimensions, value=value, quality=quality, provenance=provenance)
        if key in self.rows:
            raise ValueError(f"Duplicate observation: {country}/{indicator}/{year}/{dimensions}")
        self.rows[key] = row

    def un_households(self, source):
        import openpyxl
        book = openpyxl.load_workbook(self.raw_dir / source["file"], read_only=True, data_only=True)
        note = book["Important Note"]["A3"].value
        if "CC BY 3.0 IGO" not in note:
            raise ValueError("UN workbook licence changed; review before extraction")
        self.sources[source["id"]]["licenseText"] = note
        self.sources[source["id"]]["definitions"] = [list(r)[:2] for r in book["Definitions"].values if r[0]]
        self.sources[source["id"]]["sourceNotes"] = [list(r) for r in book["Data Sources"].values if r[0]]
        self.sources[source["id"]]["footnotes"] = [r[0] for r in book["Footnote"].values if r[0]]
        sheet = book["HH size and composition 2026"]
        header = next(sheet.iter_rows(min_row=5, max_row=5, values_only=True))
        # Keep the source field's meaning; overlapping household categories are not summed.
        names = ["SIZE_MEAN", "SIZE_1", "SIZE_2_3", "SIZE_4_5", "SIZE_6_PLUS", "FEMALE_HEAD",
                 "HEAD_UNDER20", "HEAD_20_64", "HEAD_60_PLUS", "HEAD_65_PLUS", "HAS_UNDER15", "HAS_UNDER18", "HAS_UNDER20",
                 "HAS_60_PLUS", "HAS_65_PLUS", "HAS_UNDER15_AND_60_PLUS", "HAS_UNDER15_AND_65_PLUS",
                 "HAS_UNDER18_AND_60_PLUS", "HAS_UNDER18_AND_65_PLUS", "HAS_UNDER20_AND_60_PLUS", "HAS_UNDER20_AND_65_PLUS",
                 "MEMBERS_UNDER15_MEAN", "MEMBERS_UNDER15_CONDITIONAL_MEAN", "MEMBERS_UNDER20_MEAN", "MEMBERS_UNDER20_CONDITIONAL_MEAN",
                 "MEMBERS_20_64_MEAN", "TYPE_SINGLE", "TYPE_COUPLE", "TYPE_COUPLE_CHILDREN", "TYPE_SINGLE_PARENT",
                 "TYPE_SINGLE_MOTHER", "TYPE_SINGLE_FATHER", "TYPE_EXTENDED", "TYPE_NON_RELATIVES", "TYPE_UNKNOWN",
                 "TYPE_NUCLEAR", "MULTI_GENERATION", "THREE_GENERATION", "SKIP_GENERATION"]
        for col, name in enumerate(names, 10):
            unit = "persons_per_household" if name.endswith("MEAN") else "percent_households"
            self.indicator("UN.HH." + name, source["id"], header[col], unit,
                           1 if name == "SIZE_MEAN" else 0, 100 if unit.startswith("percent") else None,
                           sourceColumn=openpyxl.utils.get_column_letter(col + 1))
        for index, row in enumerate(sheet.iter_rows(min_row=6, values_only=True), 6):
            if row[2] not in self.countries:
                continue
            dimensions = {"sourceCategory": row[6], "sourceCatalogId": str(row[7])}
            provenance = {"sheet": sheet.title, "row": index, "survey": row[9], "basis": "AGGREGATE_STATISTIC"}
            for col, name in enumerate(names, 10):
                self.add(source["id"], row[2], "UN.HH." + name, int(row[8]), dimensions, row[col], provenance)
        book.close()

    def wdi(self, source):
        self.indicator(source["indicator"], source["id"], source["label"], source["unit"], source["min"], source["max"])
        with zipfile.ZipFile(self.raw_dir / source["file"]) as archive:
            metadata = next(n for n in archive.namelist() if n.startswith("Metadata_Indicator_"))
            self.sources[source["id"]]["indicatorMetadata"] = list(csv.DictReader(io.StringIO(archive.read(metadata).decode("utf-8-sig"))))
            filename = next(n for n in archive.namelist() if n.startswith("API_"))
            records = csv.reader(io.StringIO(archive.read(filename).decode("utf-8-sig")))
            header = None
            for row in records:
                if not row:
                    continue
                if row[0] == "Last Updated Date":
                    self.sources[source["id"]]["sourceUpdated"] = row[1]
                if row[0] == "Country Name":
                    header = row
                    continue
                if header and len(row) > 3 and row[1] in self.countries:
                    if row[3] != source["indicator"]:
                        raise ValueError("Unexpected WDI indicator")
                    for col, year in enumerate(header):
                        if year.isdigit() and MIN_YEAR <= int(year) <= MAX_YEAR:
                            self.add(source["id"], row[1], source["indicator"], int(year), {}, row[col],
                                     {"file": filename, "basis": "PUBLISHED_ESTIMATE_OR_STATISTIC"})

    def ilo(self, source):
        self.indicator(source["indicator"], source["id"], source["label"], source["unit"], source["min"], source["max"],
                       definition=source["catalogMetadata"]["rep_var.label"],
                       usage="Preserve source, sex, classification and notes. No pooling across definitions.")
        with gzip.open(self.raw_dir / source["file"], "rt", encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            for line, row in enumerate(reader, 2):
                if row["ref_area"] not in self.countries or not row["time"].isdigit() or not MIN_YEAR <= int(row["time"]) <= MAX_YEAR:
                    continue
                if row["indicator"] != source["indicator"]:
                    raise ValueError("Unexpected ILO indicator")
                dimensions = {key: row[key] for key in ("source", "sex", "classif1", "classif2") if row.get(key)}
                if source["unit"] == "local_currency_per_employee_month":
                    # LCU, current USD and 2021 PPP are different series. Never relabel or mix them.
                    if "CUR_TYPE_LCU" not in dimensions.values():
                        continue
                provenance = {key: row.get(key, "") for key in ("obs_status", "note_classif", "note_indicator", "note_source")}
                provenance.update(line=line, basis="PUBLISHED_ESTIMATE_OR_STATISTIC")
                self.add(source["id"], row["ref_area"], source["indicator"], int(row["time"]), dimensions,
                         row["obs_value"], provenance, "SOURCE_MARKED_UNRELIABLE" if row.get("obs_status") == "U" else None)

    def ilo_dictionary(self, source):
        dimension = source["dimension"]
        used = set()
        for row in self.rows.values():
            if not row["sourceId"].startswith("ilo-"):
                continue
            value = row["dimensions"].get(dimension, row["provenance"].get(dimension, ""))
            if dimension.startswith("note_"):
                used.update(value.split("_"))
            else:
                used.add(value)
        with open(self.raw_dir / source["file"], encoding="utf-8-sig", newline="") as stream:
            entries = [row for row in csv.DictReader(stream) if row.get(dimension) in used and row.get(dimension)]
        missing = used - {r[dimension] for r in entries} - {""}
        if missing:
            raise ValueError(f"Unresolved ILO {dimension} codes: {missing}")
        self.sources[source["id"]]["entries"] = entries

    def write(self):
        rows = sorted(self.rows.values(), key=lambda r: r["key"])
        if not rows or {r["country"] for r in rows} != set(self.countries):
            raise ValueError("Release does not cover the 36 requested countries")
        coverage = []
        for country in self.countries:
            for indicator in sorted(self.indicators):
                selected = [r for r in rows if r["country"] == country and r["indicator"] == indicator]
                valid = [r for r in selected if r["quality"] == "VALID"]
                coverage.append(dict(country=country, indicator=indicator, validCount=len(valid),
                                     missingCount=sum(r["quality"] == "MISSING" for r in selected),
                                     rejectedCount=sum(r["quality"] == "REJECTED" for r in selected),
                                     latestYear=max((r["year"] for r in valid), default=None)))
        files = {"observations.json.gz": gzip.compress(encoded(rows), mtime=0), "coverage.json": encoded(coverage)}
        manifest = dict(schemaVersion=1, id=self.id, stage=self.stage, retrievedOn=AS_OF,
                        minYear=MIN_YEAR, maxYear=MAX_YEAR, countries=self.countries,
                        sources=self.sources, indicators=self.indicators,
                        rowCount=len(rows), coverageCount=len(coverage),
                        qualityCounts={q: sum(r["quality"] == q for r in rows) for q in ("VALID", "MISSING", "REJECTED")},
                        hashes={name: digest(data) for name, data in files.items()})
        files["manifest.json"] = json.dumps(manifest, ensure_ascii=False, indent=2, allow_nan=False).encode("utf-8") + b"\n"
        dest = ROOT / "src/main/resources/statistics/socioeconomic" / self.id
        # Reproducing the same input is safe; changed inputs require a new release ID.
        for name, data in files.items():
            if (dest / name).exists() and (dest / name).read_bytes() != data:
                raise ValueError(f"Immutable release changed: {name}. Assign a new release ID.")
        dest.mkdir(parents=True, exist_ok=True)
        for name, data in files.items():
            (dest / name).write_bytes(data)
        print(json.dumps({"release": self.id, "rows": len(rows), "quality": manifest["qualityCounts"],
                          "coverage": {i: sum(c["validCount"] > 0 for c in coverage if c["indicator"] == i) for i in self.indicators}}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", required=True, choices=["family-housing", "work-income"])
    parser.add_argument("--raw-dir", type=Path, default=ROOT / "data/statistics/expanded/socio")
    args = parser.parse_args()
    release = Release(args.stage, args.raw_dir)
    sources = json.loads((ROOT / "scripts/socioeconomic-sources.json").read_text(encoding="utf-8"))
    for source in sources:
        if source["stage"] != args.stage:
            continue
        release.sources[source["id"]] = dict(source, sha256=digest((args.raw_dir / source["file"]).read_bytes()))
        {"un-households": release.un_households, "wdi": release.wdi,
         "ilo": release.ilo, "ilo-dictionary": release.ilo_dictionary}[source["format"]](source)
    release.write()


if __name__ == "__main__":
    main()
