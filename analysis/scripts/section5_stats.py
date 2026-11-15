"""Section 5 statistics: refactoring activity summary for Checkstyle, Mockito, JabRef.

Usage: python3 section5_stats.py <datasets_dir> <commits_dir> <out_dir>
  datasets_dir: refactoring-extraction/datasets (contains <project>/<project>_extraction.csv)
  commits_dir:  folder with <project>_commits.txt (git log --no-merges: hash|date|name|email)
  out_dir:      where stats.json and per-year CSVs are written
"""
import csv, json, sys, os, statistics, collections

PROJECTS = ["checkstyle", "mockito", "jabref"]
NAMES = {"checkstyle": "Checkstyle", "mockito": "Mockito", "jabref": "JabRef"}
# Commits the extraction could not analyze (run_log.txt): root commits + timeouts + errors.
NOT_ANALYZED = {"checkstyle": 11, "mockito": 1, "jabref": 20}


def is_test_path(path):
    """Same rule as the smell-measurement tool (Main.isTestPath), so Sections 5 and 6 agree."""
    p = "/" + path
    return any(k in p for k in ("/test/", "/tests/", "/testFixtures/", "/androidTest/", "/integrationTest/",
                                "/src/it/", "/src/xdocs-examples/", "/src/testinputs/", "/src/databaseTest/"))


def kind(x):
    return "test" if x["code_kind"] == "test" or is_test_path(x["file_path"]) else "production"


def family(t):
    if t.startswith("Rename"):
        return "Rename"
    if "Modifier" in t or "Annotation" in t:
        return "Modifier / Annotation"
    if t in ("Add Parameter", "Remove Parameter", "Reorder Parameter", "Merge Parameter",
             "Split Parameter", "Parameterize Variable", "Parameterize Attribute",
             "Localize Parameter", "Add Thrown Exception Type", "Remove Thrown Exception Type",
             "Change Thrown Exception Type"):
        return "Signature (Parameters / Exceptions)"
    if t.startswith("Change") and "Type" in t:
        return "Change Type"
    if t.startswith(("Pull Up", "Push Down")) or t in (
            "Extract Superclass", "Extract Subclass", "Extract Interface", "Collapse Hierarchy"):
        return "Hierarchy (Pull Up / Push Down / Extract Superclass)"
    if t.startswith("Move"):
        return "Move"
    if t.startswith("Extract"):
        return "Extract"
    if t.startswith("Inline"):
        return "Inline"
    if t in ("Add Parameter", "Remove Parameter", "Reorder Parameter", "Merge Parameter",
             "Split Parameter", "Parameterize Variable", "Parameterize Attribute",
             "Localize Parameter", "Add Thrown Exception Type", "Remove Thrown Exception Type"):
        return "Signature (Parameters / Exceptions)"
    return "Other"


class UF:
    def __init__(self):
        self.p = {}

    def find(self, x):
        self.p.setdefault(x, x)
        while self.p[x] != x:
            self.p[x] = self.p[self.p[x]]
            x = self.p[x]
        return x

    def union(self, a, b):
        self.p[self.find(a)] = self.find(b)


def identities(pairs):
    """Merge author aliases: two entries are one person if they share a name or an e-mail."""
    uf = UF()
    for name, email in pairs:
        n, e = "n:" + name.strip().lower(), "e:" + email.strip().lower()
        uf.union(n, e)
    return uf


def is_bot(name, email):
    s = (name + " " + email).lower()
    return "[bot]" in s or "dependabot" in s or "github-actions" in s or "renovate" in s


def main(ds, cm, out):
    os.makedirs(out, exist_ok=True)
    result = {}
    for p in PROJECTS:
        rows = list(csv.DictReader(open(f"{ds}/{p}/{p}_extraction.csv", newline="", encoding="utf-8")))
        r_reclassified = sum(1 for x in rows if x["code_kind"] == "production" and kind(x) == "test")
        for x in rows:
            x["code_kind"] = kind(x)
        commits = [l.rstrip("\n").split("|") for l in open(f"{cm}/{p}_commits.txt", encoding="utf-8", errors="replace")]
        analyzed = len(commits) - NOT_ANALYZED[p]
        r = {"reclassified_as_test": r_reclassified}
        # 1 totals
        per_commit = collections.Counter(x["commit"] for x in rows)
        r["instances"] = len(rows)
        r["nonmerge_commits"] = len(commits)
        r["analyzed_commits"] = analyzed
        r["ref_commits"] = len(per_commit)
        r["ref_commit_pct"] = round(100 * len(per_commit) / analyzed, 1)
        # 2 types
        types = collections.Counter(x["refactoring_type"] for x in rows)
        r["distinct_types"] = len(types)
        top10 = types.most_common(10)
        r["top10"] = [(t, n, round(100 * n / len(rows), 1)) for t, n in top10]
        r["top10_share"] = round(100 * sum(n for _, n in top10) / len(rows), 1)
        # 3 families
        fam = collections.Counter(family(x["refactoring_type"]) for x in rows)
        r["families"] = [(f, n, round(100 * n / len(rows), 1)) for f, n in fam.most_common()]
        # 4 production vs test
        kinds = collections.Counter(x["code_kind"] for x in rows)
        r["production"] = kinds["production"]
        r["test"] = kinds["test"]
        r["test_pct"] = round(100 * kinds["test"] / len(rows), 1)
        kind_commits = collections.defaultdict(set)
        for x in rows:
            kind_commits[x["code_kind"]].add(x["commit"])
        r["commits_touching_test"] = len(kind_commits["test"])
        r["commits_touching_production"] = len(kind_commits["production"])
        # 5 per year
        years = collections.defaultdict(lambda: {"commits": 0, "ref_commits": 0, "instances": 0})
        date_of = {}
        for h, d, *_ in commits:
            years[d[:4]]["commits"] += 1
            date_of[h] = d
        for h, n in per_commit.items():
            y = date_of[h][:4]
            years[y]["ref_commits"] += 1
            years[y]["instances"] += n
        ys = sorted(years)
        with open(f"{out}/{p}_per_year.csv", "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["year", "nonmerge_commits", "refactoring_commits", "refactoring_instances", "pct_commits_with_refactoring"])
            for y in ys:
                v = years[y]
                w.writerow([y, v["commits"], v["ref_commits"], v["instances"],
                            round(100 * v["ref_commits"] / v["commits"], 1) if v["commits"] else 0])
        r["per_year"] = {y: years[y] for y in ys}
        peak = max(ys, key=lambda y: years[y]["instances"])
        r["peak_year"] = (peak, years[peak]["instances"], round(100 * years[peak]["instances"] / len(rows), 1))
        r["first_year"], r["last_year"] = ys[0], ys[-1]
        # 6 density
        counts = sorted(per_commit.values())
        r["density"] = {
            "median": statistics.median(counts),
            "mean": round(statistics.mean(counts), 1),
            "p90": counts[int(0.9 * (len(counts) - 1))],
            "max": counts[-1],
            "max_commit": max(per_commit, key=per_commit.get)[:7],
            "eq1": sum(1 for c in counts if c == 1),
            "ge100": sum(1 for c in counts if c >= 100),
            "ge100_instances": sum(c for c in counts if c >= 100),
        }
        r["density"]["eq1_pct"] = round(100 * r["density"]["eq1"] / len(counts), 1)
        r["density"]["ge100_share"] = round(100 * r["density"]["ge100_instances"] / len(rows), 1)
        # 7 authors (identities merged across name/e-mail aliases)
        uf = identities([(c[2], c[3]) for c in commits] + [(x["author_name"], x["author_email"]) for x in rows])
        key = lambda name, email: uf.find("n:" + name.strip().lower())
        all_auth = {key(c[2], c[3]) for c in commits}
        bots = {key(c[2], c[3]) for c in commits if is_bot(c[2], c[3])}
        ref_by_auth = collections.Counter(key(x["author_name"], x["author_email"]) for x in rows)
        ref_auth = set(ref_by_auth)
        r["authors_total"] = len(all_auth - bots)
        r["authors_refactoring"] = len(ref_auth - bots)
        r["authors_refactoring_pct"] = round(100 * r["authors_refactoring"] / r["authors_total"], 1)
        r["bot_instances"] = sum(n for a, n in ref_by_auth.items() if a in bots)
        human = [n for a, n in ref_by_auth.most_common() if a not in bots]
        tot = sum(human)
        r["top1_share"] = round(100 * human[0] / tot, 1)
        r["top10_author_share"] = round(100 * sum(human[:10]) / tot, 1)
        # authors needed to reach 80% of instances
        acc = 0
        for i, n in enumerate(human, 1):
            acc += n
            if acc >= 0.8 * tot:
                r["authors_for_80pct"] = i
                break
        # 8 files
        files = collections.Counter(x["file_path"] for x in rows)
        r["distinct_files"] = len(files)
        kind_of = {x["file_path"]: x["code_kind"] for x in rows}
        r["top_files"] = [("/".join(f.split("/")[-2:]), n, kind_of[f]) for f, n in files.most_common(5)]
        test_rows = [x for x in rows if x["code_kind"] == "test"]
        r["test_resource_instances"] = sum(1 for x in test_rows if "/test/resources" in x["file_path"] or "/resources-noncompilable/" in x["file_path"])
        r["test_resource_pct_of_test"] = round(100 * r["test_resource_instances"] / max(1, len(test_rows)), 1)
        fcounts = sorted(files.values())
        r["files_once"] = sum(1 for c in fcounts if c == 1)
        r["files_once_pct"] = round(100 * r["files_once"] / len(files), 1)
        r["top10pct_files_share"] = round(100 * sum(fcounts[-max(1, len(fcounts) // 10):]) / len(rows), 1)
        # 9 test-file proxy (production refactorings only)
        prod = [x for x in rows if x["code_kind"] == "production"]
        yes = sum(1 for x in prod if x["has_test_file_proxy"] == "yes")
        r["proxy_yes"] = yes
        r["proxy_pct"] = round(100 * yes / len(prod), 1)
        result[p] = r
    json.dump(result, open(f"{out}/stats.json", "w"), indent=1, default=str)
    return result


if __name__ == "__main__":
    res = main(*sys.argv[1:4])
    for p, r in res.items():
        print("=====", p)
        for k, v in r.items():
            if k != "per_year":
                print(k, v)
