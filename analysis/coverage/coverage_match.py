"""Links JaCoCo coverage at the analysis snapshot to the refactored production files (requirement 1c).

Usage (from the repository root, after run_coverage.sh):
    python3 analysis/coverage/coverage_match.py
Reads  refactoring-extraction/datasets/<p>/<p>_extraction.csv and analysis/data/coverage/<p>_jacoco.xml
Writes analysis/data/coverage/<p>_file_coverage.csv and coverage_stats.json

Coverage is measured once, at the snapshot. It therefore describes the refactored files as they are
today, not at the moment of each refactoring. Files are matched by package path + file name, so a file
that was later moved or deleted is reported as "not present at snapshot", and one that still exists in a
module whose tests were not run (e.g. JabRef's GUI) as "outside measured module". The share of covered
refactorings is also given for the last 12 months before the snapshot, where the code is closest to
the measured version, and it is compared with the test-file proxy of Section 5.8.
"""
import csv, json, os, sys, collections, datetime as dt
import re
import xml.etree.ElementTree as ET

PKG_RX = re.compile(r"(?:^|/)((?:com|org|net)/.*\.java)$")

csv.field_size_limit(10 ** 9)
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DS = os.path.join(ROOT, "refactoring-extraction", "datasets")
OUT = os.path.join(ROOT, "analysis", "data", "coverage")
SNAP = {"checkstyle": "2026-09-30", "mockito": "2026-09-25", "jabref": "2026-09-29"}
MODULE = {"checkstyle": "whole project", "mockito": "mockito-core module", "jabref": "jablib module"}


def is_test_path(path):  # same rule as Section 5 and the smell-measurement tool
    p = "/" + path
    return any(k in p for k in ("/test/", "/tests/", "/testFixtures/", "/androidTest/", "/integrationTest/",
                                "/src/it/", "/src/xdocs-examples/", "/src/testinputs/", "/src/databaseTest/"))


def key(path):
    """'.../src/main/java/org/x/Foo.java' -> 'org/x/Foo.java' (what JaCoCo reports as package + file).
    Cutting at the first com/, org/ or net/ folder also matches files from older source layouts."""
    m = PKG_RX.search(path)
    return m.group(1) if m else path


def load_jacoco(f):
    cov = {}
    for pkg in ET.parse(f).getroot().iter("package"):
        for sf in pkg.findall("sourcefile"):
            c = {x.get("type"): (int(x.get("missed")), int(x.get("covered"))) for x in sf.findall("counter")}
            if "LINE" in c:
                cov[f"{pkg.get('name')}/{sf.get('name')}"] = c["LINE"]
    return cov


def snapshot_files(p):
    """Package keys of all production .java files in the snapshot checkout made by run_coverage.sh."""
    keys, src = set(), os.path.join(ROOT, "analysis", ".cache", "coverage", p)
    for d, _, fs in os.walk(src):
        rel = os.path.relpath(d, src).replace(os.sep, "/") + "/"
        if "/.git/" in "/" + rel or is_test_path(rel):
            continue
        keys.update(key(rel + f) for f in fs if f.endswith(".java"))
    return keys


def pct(a, b):
    return round(100 * a / b, 1) if b else None


stats = {}
for p in ("checkstyle", "mockito", "jabref"):
    xml = os.path.join(OUT, f"{p}_jacoco.xml")
    if not os.path.exists(xml):
        print(f"[{p}] skipped: {xml} not found (run run_coverage.sh {p} first)")
        continue
    cov = load_jacoco(xml)
    snap = snapshot_files(p)
    cutoff = (dt.date.fromisoformat(SNAP[p]) - dt.timedelta(days=365)).isoformat()
    files = collections.defaultdict(lambda: {"instances": 0, "recent": 0, "proxy_yes": 0})
    for r in csv.DictReader(open(os.path.join(DS, p, f"{p}_extraction.csv"), encoding="utf-8", errors="replace")):
        if r["code_kind"] == "test" or is_test_path(r["file_path"]) or not r["file_path"].endswith(".java"):
            continue
        f = files[key(r["file_path"])]
        f["instances"] += 1
        f["recent"] += r["commit_date"] >= cutoff
        f["proxy_yes"] += r["has_test_file_proxy"] == "yes"

    rows, agg = [], collections.Counter()
    for k, f in sorted(files.items()):
        missed, covered = cov.get(k, (None, None))
        present = covered is not None
        status = ("covered" if covered > 0 else "not covered") if present else \
            ("outside measured module" if k in snap else "not present at snapshot")
        agg["outside"] += (not present) and k in snap
        rows.append([k, f["instances"], f["recent"], f["proxy_yes"], status,
                     "" if not present else covered, "" if not present else missed,
                     "" if not present else pct(covered, covered + missed)])
        agg["files"] += 1
        agg["inst"] += f["instances"]
        if present:
            agg["files_present"] += 1; agg["inst_present"] += f["instances"]
            agg["lines_cov"] += covered; agg["lines_all"] += covered + missed
            agg["recent_present"] += f["recent"]
            if covered > 0:
                agg["files_cov"] += 1; agg["inst_cov"] += f["instances"]; agg["recent_cov"] += f["recent"]
            # proxy agreement, per refactoring instance
            agg["proxy_yes_present"] += f["proxy_yes"]
            agg["proxy_no_present"] += f["instances"] - f["proxy_yes"]
            if covered > 0:
                agg["proxy_yes_cov"] += f["proxy_yes"]
                agg["proxy_no_cov"] += f["instances"] - f["proxy_yes"]
    with open(os.path.join(OUT, f"{p}_file_coverage.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["file", "production_refactorings", "refactorings_last_12_months", "with_test_file_proxy",
                    "status", "lines_covered", "lines_missed", "line_coverage_pct"])
        w.writerows(rows)
    proj_lines = (sum(c for _, c in cov.values()), sum(m + c for m, c in cov.values()))
    stats[p] = {
        "measured": MODULE[p],
        "project_line_coverage_pct": pct(*proj_lines),
        "refactored_production_files": agg["files"],
        "files_present_at_snapshot": agg["files_present"],
        "files_outside_measured_module": agg["outside"],
        "files_present_with_coverage_pct": pct(agg["files_cov"], agg["files_present"]),
        "line_coverage_of_refactored_files_pct": pct(agg["lines_cov"], agg["lines_all"]),
        "production_refactorings": agg["inst"],
        "refactorings_in_present_files": agg["inst_present"],
        "refactorings_in_covered_files_pct": pct(agg["inst_cov"], agg["inst_present"]),
        "last_12_months_refactorings_in_present_files": agg["recent_present"],
        "last_12_months_in_covered_files_pct": pct(agg["recent_cov"], agg["recent_present"]),
        "proxy_yes_in_covered_files_pct": pct(agg["proxy_yes_cov"], agg["proxy_yes_present"]),
        "proxy_no_in_covered_files_pct": pct(agg["proxy_no_cov"], agg["proxy_no_present"]),
    }
    print(f"[{p}]", json.dumps(stats[p], indent=1))

if stats:
    json.dump(stats, open(os.path.join(OUT, "coverage_stats.json"), "w"), indent=2)
    print("Written:", os.path.join(OUT, "coverage_stats.json"))
