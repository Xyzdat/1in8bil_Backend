"""Offline checks against downloaded official files, without a database or live API."""
import csv
import gzip
import importlib.util
import io
import json
from pathlib import Path
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("extract", ROOT / "scripts/prepare-socioeconomic-statistics.py")
extract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(extract)


class ExtractionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.family = json.load(gzip.open(ROOT / "src/main/resources/statistics/socioeconomic/family-housing-2026-11-v1/observations.json.gz", "rt", encoding="utf-8"))
        cls.work = json.load(gzip.open(ROOT / "src/main/resources/statistics/socioeconomic" / extract.RELEASE_IDS["work-income"] / "observations.json.gz", "rt", encoding="utf-8"))

    def test_missing_zero_and_rejected_values_are_distinct(self):
        r = extract.Release("family-housing", ROOT)
        r.indicator("test", "source", "Size", "persons", 1, None)
        for year, raw in [(2020, None), (2021, "0.81"), (2022, "2.5")]:
            r.add("source", "PAK", "test", year, {}, raw, {})
        self.assertEqual([row["quality"] for row in r.rows.values()], ["MISSING", "REJECTED", "VALID"])
        self.assertIsNone(next(row for row in r.rows.values() if row["year"] == 2021)["value"])
        r.indicator("percent", "source", "Percent", "percent", 0, 100)
        r.add("source", "PAK", "percent", 2022, {}, "0", {})
        self.assertEqual(list(r.rows.values())[-1]["quality"], "VALID")

    def test_household_size_buckets_are_coherent_within_each_survey(self):
        groups = {}
        indicators = {"UN.HH.SIZE_1", "UN.HH.SIZE_2_3", "UN.HH.SIZE_4_5", "UN.HH.SIZE_6_PLUS"}
        for r in self.family:
            if r["indicator"] in indicators and r["quality"] == "VALID":
                key = (r["country"], r["year"], json.dumps(r["dimensions"], sort_keys=True))
                groups.setdefault(key, []).append(r["value"])
        complete = [v for v in groups.values() if len(v) == 4]
        self.assertEqual(len(complete), 217)
        for values in complete:
            self.assertLessEqual(abs(sum(values) - 100), 0.11)  # Source rounding to 0.01 percent.

    def test_un_values_match_original_cells_for_every_extracted_country_and_year(self):
        import openpyxl
        raw = ROOT / "data/statistics/expanded/socio/households-2026.xlsx"
        if not raw.exists():
            self.skipTest("Download source files to run independent extraction check")
        book = openpyxl.load_workbook(raw, data_only=True)
        manifest = json.loads((ROOT / "src/main/resources/statistics/socioeconomic/family-housing-2026-11-v1/manifest.json").read_text(encoding="utf-8"))
        for r in self.family:
            if r["sourceId"] == "un-households-2026" and r["quality"] == "VALID":
                column = manifest["indicators"][r["indicator"]]["sourceColumn"]
                cell = f"{column}{r['provenance']['row']}"
                self.assertEqual(float(book[r["provenance"]["sheet"]][cell].value), r["value"])
        book.close()

    def test_wdi_values_match_country_year_not_column_position(self):
        path = ROOT / "data/statistics/expanded/socio/electricity.zip"
        if not path.exists():
            self.skipTest("Download source files to run independent extraction check")
        with zipfile.ZipFile(path) as z:
            name = next(n for n in z.namelist() if n.startswith("API_"))
            text = z.read(name).decode("utf-8-sig")
        rows = list(csv.reader(io.StringIO(text)))
        header_index = next(i for i, r in enumerate(rows) if r and r[0] == "Country Name")
        header = rows[header_index]
        source = {r[1]: r for r in rows[header_index + 1:] if len(r) > 3}
        for r in self.family:
            if r["indicator"] == "EG.ELC.ACCS.ZS" and r["quality"] == "VALID":
                self.assertEqual(float(source[r["country"]][header.index(str(r["year"]))]), r["value"])

    def test_monthly_earnings_match_original_lines_currency_and_quality(self):
        path = ROOT / "data/statistics/expanded/socio/earnings-mean.csv.gz"
        if not path.exists():
            self.skipTest("Download source files to run independent extraction check")
        with gzip.open(path, "rt", encoding="utf-8-sig", newline="") as stream:
            source = dict(enumerate(csv.DictReader(stream), 2))
        checked = 0
        for r in self.work:
            if r["indicator"] != "EAR_EMTA_SEX_CUR_NB":
                continue
            original = source[r["provenance"]["line"]]
            self.assertEqual((original["ref_area"], int(original["time"])), (r["country"], r["year"]))
            self.assertEqual(original["classif1"], "CUR_TYPE_LCU")
            for dimension, value in r["dimensions"].items():
                self.assertEqual(original[dimension], value)
            if not original["obs_value"]:
                self.assertEqual(r["quality"], "MISSING")
                self.assertIsNone(r["value"])
            elif original["obs_status"] == "U":
                self.assertEqual(r["quality"], "REJECTED")
                self.assertIsNone(r["value"])
            else:
                self.assertEqual(r["value"], float(original["obs_value"]))
            checked += 1
        self.assertGreater(checked, 1000)

    def test_income_quintiles_form_a_distribution_within_country_and_year(self):
        groups = {}
        for r in self.work:
            if r["indicator"].startswith("SI.DST.") and r["quality"] == "VALID":
                groups.setdefault((r["country"], r["year"]), []).append(r["value"])
        complete = [values for values in groups.values() if len(values) == 5]
        self.assertGreater(len(complete), 100)
        for values in complete:
            self.assertLessEqual(abs(sum(values) - 100), 0.26)  # Published to one decimal place.


if __name__ == "__main__":
    unittest.main()
