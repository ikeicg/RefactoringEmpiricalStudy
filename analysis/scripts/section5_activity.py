"""Section 5.4: refactoring activity over time, by Murphy-Hill et al. level (low / medium / high).

Usage: python3 section5_activity.py <datasets_dir> <out_dir>
  datasets_dir: refactoring-extraction/datasets (contains <project>/<project>_extraction.csv)
  out_dir:      where <project>_monthly.csv, levels.json and fig_activity_monthly.pdf/.png are written

Levels follow Murphy-Hill, Parnin and Black, "How we refactor, and how we know it" (ICSE 2009):
  high   - changes the signatures of classes, methods or fields (rename, move, change type, parameters, modifiers)
  medium - changes signatures AND significantly changes blocks of code (extract/inline method, pull up, extract class)
  low    - changes only blocks of code inside method bodies (local variables, conditionals, lambdas, loops)
"""
import csv, json, sys, collections
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
import datetime as dt

csv.field_size_limit(10 ** 9)
P = ["checkstyle", "mockito", "jabref"]
N = {"checkstyle": "Checkstyle", "mockito": "Mockito", "jabref": "JabRef"}

LOW = {
    "Extract Variable", "Inline Variable", "Rename Variable", "Change Variable Type", "Merge Variable",
    "Split Variable", "Add Variable Modifier", "Remove Variable Modifier", "Add Variable Annotation",
    "Remove Variable Annotation", "Modify Variable Annotation", "Invert Condition", "Merge Conditional",
    "Split Conditional", "Replace Conditional With Ternary", "Merge Catch", "Try With Resources",
    "Replace Generic With Diamond", "Replace Loop With Pipeline", "Replace Pipeline With Loop",
    "Replace Anonymous With Lambda", "Assert Throws", "Move Code",
}
MEDIUM = {
    "Extract Method", "Inline Method", "Extract And Move Method", "Move And Inline Method", "Merge Method",
    "Split Method", "Pull Up Method", "Push Down Method", "Pull Up Attribute", "Push Down Attribute",
    "Extract Class", "Extract Interface", "Extract Superclass", "Extract Subclass", "Collapse Hierarchy",
    "Merge Class", "Split Class", "Extract Attribute", "Inline Attribute", "Encapsulate Attribute",
    "Parameterize Variable", "Parameterize Attribute", "Parameterize Test", "Localize Parameter",
    "Replace Variable With Attribute", "Replace Attribute With Variable", "Replace Anonymous With Class",
}


def level(t):
    """Every other type changes a signature without rewriting code blocks: rename, move, change type,
    add/remove/reorder parameter, thrown exceptions, access modifiers, modifiers and annotations."""
    return "low" if t in LOW else "medium" if t in MEDIUM else "high"


ds, out = sys.argv[1], sys.argv[2]
monthly, shares, types = {}, {}, collections.defaultdict(set)
for p in P:
    m = collections.defaultdict(collections.Counter)
    for r in csv.DictReader(open(f"{ds}/{p}/{p}_extraction.csv", encoding="utf-8", errors="replace")):
        lv = level(r["refactoring_type"])
        m[r["commit_date"][:7]][lv] += 1
        types[lv].add(r["refactoring_type"])
    first, last = min(m), max(m)
    y, mo = int(first[:4]), int(first[5:])
    rows = []
    while f"{y:04d}-{mo:02d}" <= last:
        k = f"{y:04d}-{mo:02d}"
        rows.append((k, m[k]["low"], m[k]["medium"], m[k]["high"]))
        y, mo = (y + 1, 1) if mo == 12 else (y, mo + 1)
    monthly[p] = rows
    with open(f"{out}/{p}_monthly.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["month", "low", "medium", "high", "total"])
        for k, a, b, c in rows:
            w.writerow([k, a, b, c, a + b + c])
    tot = collections.Counter()
    for k in m:
        tot.update(m[k])
    n = sum(tot.values())
    peak = max(rows, key=lambda r: r[1] + r[2] + r[3])
    active = [r for r in rows if r[1] + r[2] + r[3] > 0]
    shares[p] = {"instances": n, "months": len(rows), "months_with_refactoring": len(active),
                 "peak_month": peak[0], "peak_instances": peak[1] + peak[2] + peak[3],
                 **{lv: tot[lv] for lv in ("low", "medium", "high")},
                 **{f"{lv}_share": round(tot[lv] / n, 4) for lv in ("low", "medium", "high")}}

json.dump({"projects": shares, "types_per_level": {k: sorted(v) for k, v in types.items()}},
          open(f"{out}/levels.json", "w"), indent=2)

# Figure: monthly refactoring instances, stacked by level, one panel per project (own y scale)
INK, INK2, GRID, SURF = "#0b0b0b", "#52514e", "#e4e3df", "#ffffff"
LV = [("high", "High level (signatures only)", "#1c5cab"),
      ("medium", "Medium level (signatures and code blocks)", "#3987e5"),
      ("low", "Low level (code blocks only)", "#86b6ef")]
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 9, "axes.edgecolor": INK2,
                     "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK2,
                     "axes.spines.top": False, "axes.spines.right": False})
fig, axes = plt.subplots(3, 1, figsize=(6.5, 6.0), sharex=True)
for ax, p in zip(axes, P):
    rows = monthly[p]
    x = [dt.date(int(k[:4]), int(k[5:]), 15) for k, *_ in rows]
    bottom = [0] * len(rows)
    for key, label, col in LV:
        i = {"low": 1, "medium": 2, "high": 3}[key]
        v = [r[i] for r in rows]
        ax.bar(x, v, width=27, bottom=bottom, color=col, label=label, linewidth=0)
        bottom = [b + a for a, b in zip(v, bottom)]
    s = shares[p]
    ax.set_title(N[p], loc="left", fontsize=9.5, color=INK, fontweight="bold", pad=3)
    pk = dt.date(int(s["peak_month"][:4]), int(s["peak_month"][5:]), 15)
    ax.annotate(f"{s['peak_instances']:,} ({s['peak_month']})", (pk, s["peak_instances"]), xytext=(4, -2),
                textcoords="offset points", ha="left", va="top", fontsize=8, color=INK)
    ax.set_ylim(0, s["peak_instances"] * 1.08)
    ax.grid(axis="y", color=GRID, linewidth=0.6); ax.set_axisbelow(True)
    ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{int(v):,}"))
    ax.tick_params(length=0)
axes[1].set_ylabel("Refactoring instances per month")
axes[-1].xaxis.set_major_locator(mdates.YearLocator(2))
axes[-1].xaxis.set_major_formatter(mdates.DateFormatter("%Y"))
axes[-1].set_xlim(dt.date(2001, 1, 1), dt.date(2026, 12, 31))
axes[-1].set_xlabel("Month of the commit (2026 runs to the snapshot at the end of September)")
h, l = axes[0].get_legend_handles_labels()
fig.legend(h, l, loc="upper center", ncol=3, frameon=False, fontsize=7.6, bbox_to_anchor=(0.5, 1.0),
           handlelength=1.2, columnspacing=1.2)
fig.tight_layout(rect=(0, 0, 1, 0.96), h_pad=1.0)
for ext in ("pdf", "png"):
    fig.savefig(f"{out}/fig_activity_monthly.{ext}", dpi=200)
print(json.dumps(shares, indent=1))
