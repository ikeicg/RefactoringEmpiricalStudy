"""Section 6.1 data for RQ1 (timing of refactoring activity).

Usage: python3 rq1_data.py <datasets_dir> <releases_dir> <commits_dir> <out_dir>
  datasets_dir: refactoring-extraction/datasets
  releases_dir: release-dates (contains <project>_releases.csv)
  commits_dir:  folder with <project>_commits.txt (git log --no-merges at the snapshot: hash|date|name|email)
Writes rq1_stats.json, <project>_weekly.csv, <project>_intervals.csv.
"""
import csv, json, re, sys, statistics, collections, datetime as dt

PROJECTS = ["checkstyle", "mockito", "jabref"]
SNAPSHOT = {"checkstyle": "2026-09-30", "mockito": "2026-09-25", "jabref": "2026-09-29"}
D = dt.date.fromisoformat


def is_bot(name, email):
    s = (name + " " + email).lower()
    return "[bot]" in s or "dependabot" in s or "github-actions" in s or "renovate" in s


def is_minor(v):
    m = re.match(r"^(\d+)\.(\d+)(?:\.(\d+))?$", v)
    return bool(m) and m.group(3) in (None, "0")


def pct(a, b):
    return round(100 * a / b, 1) if b else 0.0


def dispersion(xs):
    m = statistics.mean(xs)
    return round(statistics.pvariance(xs) / m, 2) if m else 0.0


def burstiness(dates):
    """Goh-Barabasi B on gaps (days) between consecutive events: -1 regular, 0 Poisson, 1 bursty."""
    ds = sorted(dates)
    gaps = [(b - a).days for a, b in zip(ds, ds[1:])]
    mu, sd = statistics.mean(gaps), statistics.pstdev(gaps)
    return round((sd - mu) / (sd + mu), 2) if sd + mu else 0.0


def main(ds, rd, cd, out):
    res = {}
    for p in PROJECTS:
        rows = list(csv.DictReader(open(f"{ds}/{p}/{p}_extraction.csv", newline="", encoding="utf-8")))
        inst = collections.Counter(x["commit"] for x in rows)
        commits = []
        for line in open(f"{cd}/{p}_commits.txt", encoding="utf-8", errors="replace"):
            h, d, n, e = line.rstrip("\n").split("|", 3)
            if not is_bot(n, e):
                commits.append((h, D(d), inst.get(h, 0)))
        rel_all = [(r["version"], D(r["release_date"])) for r in csv.DictReader(open(f"{rd}/{p}_releases.csv"))
                   if "pre-release" not in r["date_source"].lower()]
        rel = sorted(d for v, d in rel_all if is_minor(v))
        r = {"commits": len(commits), "ref_commits": sum(1 for c in commits if c[2]),
             "final_releases": len(rel_all), "minor_releases": len(rel),
             "minor_releases_in_history": sum(1 for d in rel if d <= D(SNAPSHOT[p]))}
        base = r["ref_commits"] / r["commits"]
        r["base_ref_share"] = round(100 * base, 1)

        # 1. release windows: commits in the k days before / after a minor release
        for k in (7, 30):
            for side in ("before", "after"):
                def in_win(d):
                    return any((0 < (rd_ - d).days <= k) if side == "before" else (0 < (d - rd_).days <= k) for rd_ in rel)
                win = [c for c in commits if in_win(c[1])]
                rc = sum(1 for c in win if c[2])
                r[f"w{k}_{side}_commits_share"] = pct(len(win), len(commits))
                r[f"w{k}_{side}_ref_share"] = pct(rc, len(win))
                r[f"w{k}_{side}_lift"] = round((rc / len(win)) / base, 2) if win else None
                r[f"w{k}_{side}_instances_per_commit"] = round(sum(c[2] for c in win) / len(win), 2) if win else None

        # 2. position inside the release interval (0 = right after a release, 1 = right before the next)
        bins_all, bins_ref = [0] * 10, [0] * 10
        intervals = []
        for a, b in zip(rel, rel[1:]):
            span = (b - a).days
            cs = [c for c in commits if a < c[1] < b]   # release-day commits are measured separately
            if span <= 0:
                continue
            for c in cs:
                k = min(9, int(10 * ((c[1] - a).days - 1) / span)) if span > 0 else 9
                bins_all[k] += 1
                if c[2]:
                    bins_ref[k] += 1
            if len(cs) >= 10:
                intervals.append({"start": a.isoformat(), "end": b.isoformat(), "days": span, "commits": len(cs),
                                  "ref_commits": sum(1 for c in cs if c[2]),
                                  "instances": sum(c[2] for c in cs)})
        relset = set(rel)
        rday = [c for c in commits if c[1] in relset]
        r["release_day_commits"] = len(rday)
        r["release_day_ref_share"] = pct(sum(1 for c in rday if c[2]), len(rday))
        r["release_day_lift"] = round((sum(1 for c in rday if c[2]) / len(rday)) / base, 2) if rday else None
        tot_a, tot_r = sum(bins_all), sum(bins_ref)
        r["interval_commits_covered"] = tot_a
        r["position_bins_ref_share"] = [pct(rb, ab) for rb, ab in zip(bins_ref, bins_all)]
        r["last10_lift"] = round((bins_ref[9] / bins_all[9]) / (tot_r / tot_a), 2) if bins_all[9] else None
        r["first10_lift"] = round((bins_ref[0] / bins_all[0]) / (tot_r / tot_a), 2) if bins_all[0] else None
        shares = [100 * i["ref_commits"] / i["commits"] for i in intervals]
        r["intervals_10plus"] = len(intervals)
        r["interval_ref_share_median"] = round(statistics.median(shares), 1)
        q = statistics.quantiles(shares, n=4)
        r["interval_ref_share_iqr"] = (round(q[0], 1), round(q[2], 1))
        r["interval_ref_share_min_max"] = (round(min(shares), 1), round(max(shares), 1))
        with open(f"{out}/{p}_intervals.csv", "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(intervals[0]))
            w.writeheader(); w.writerows(intervals)

        # 3. weekly series and burstiness, from the first to the last commit
        start = min(c[1] for c in commits)
        week = lambda d: (d - start).days // 7
        nweeks = week(max(c[1] for c in commits)) + 1
        wc, wr, wi = [0] * nweeks, [0] * nweeks, [0] * nweeks
        for c in commits:
            wc[week(c[1])] += 1
            if c[2]:
                wr[week(c[1])] += 1
                wi[week(c[1])] += c[2]
        active = [i for i in range(nweeks) if wc[i] > 0]
        r["weeks"] = nweeks
        r["active_weeks"] = len(active)
        r["weeks_with_refactoring"] = sum(1 for i in active if wr[i] > 0)
        r["weeks_with_refactoring_pct_of_active"] = pct(r["weeks_with_refactoring"], len(active))
        r["dispersion_ref_commits"] = dispersion([wr[i] for i in active])
        r["dispersion_all_commits"] = dispersion([wc[i] for i in active])
        r["dispersion_instances"] = dispersion([wi[i] for i in active])
        r["burstiness_ref_commits"] = burstiness([c[1] for c in commits if c[2]])
        r["burstiness_all_commits"] = burstiness([c[1] for c in commits])
        top = sorted(active, key=lambda i: -wi[i])[:max(1, len(active) // 20)]
        r["top5pct_weeks_instance_share"] = pct(sum(wi[i] for i in top), sum(wi))
        topc = sorted(active, key=lambda i: -wr[i])[:max(1, len(active) // 20)]
        r["top5pct_weeks_ref_commit_share"] = pct(sum(wr[i] for i in topc), sum(wr))
        with open(f"{out}/{p}_weekly.csv", "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["week_start", "commits", "refactoring_commits", "refactoring_instances"])
            for i in range(nweeks):
                w.writerow([(start + dt.timedelta(days=7 * i)).isoformat(), wc[i], wr[i], wi[i]])
        res[p] = r
    json.dump(res, open(f"{out}/rq1_stats.json", "w"), indent=1, default=str)
    return res


if __name__ == "__main__":
    for p, r in main(*sys.argv[1:5]).items():
        print("=====", p)
        for k, v in r.items():
            print(" ", k, v)
