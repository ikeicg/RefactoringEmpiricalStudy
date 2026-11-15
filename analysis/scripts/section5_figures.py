"""Figures for Section 5. Usage: python3 section5_figures.py <out_dir>  (reads stats.json + *_per_year.csv)"""
import csv, json, sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

out = sys.argv[1]
S = json.load(open(f"{out}/stats.json"))
P = ["checkstyle", "mockito", "jabref"]
N = {"checkstyle": "Checkstyle", "mockito": "Mockito", "jabref": "JabRef"}
C = {"checkstyle": "#2a78d6", "mockito": "#eb6834", "jabref": "#1baf7a"}
INK, INK2, GRID, SURF = "#0b0b0b", "#52514e", "#e4e3df", "#ffffff"
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 9, "axes.edgecolor": INK2,
                     "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK2,
                     "axes.spines.top": False, "axes.spines.right": False})

def per_year(p):
    return list(csv.DictReader(open(f"{out}/{p}_per_year.csv")))

YEARS = list(range(2001, 2027))

# Figure 2: refactoring instances per year, one panel per project (same x range, own y scale)
fig, axes = plt.subplots(3, 1, figsize=(6.5, 5.6), sharex=True)
for ax, p in zip(axes, P):
    d = {int(r["year"]): int(r["refactoring_instances"]) for r in per_year(p)}
    ys = [d.get(y, 0) if y in d else None for y in YEARS]
    xs = [y for y in YEARS if y in d]
    ax.bar(xs, [d[y] for y in xs], width=0.72, color=C[p], edgecolor=SURF, linewidth=0.8)
    peak = max(d, key=d.get)
    ax.annotate(f"{d[peak]:,}", (peak, d[peak]), xytext=(0, 2), textcoords="offset points",
                ha="center", va="bottom", fontsize=8, color=INK)
    ax.set_title(N[p], loc="left", fontsize=9.5, color=INK, fontweight="bold", pad=3)
    ax.grid(axis="y", color=GRID, linewidth=0.6); ax.set_axisbelow(True)
    ax.set_ylim(0, d[peak] * 1.18)
    ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{int(v):,}"))
    ax.tick_params(length=0)
axes[1].set_ylabel("Refactoring instances")
axes[-1].set_xticks(range(2001, 2027, 2))
axes[-1].set_xlabel("Year (2026 covers January to the snapshot at the end of September)")
fig.tight_layout(h_pad=1.0)
fig.savefig(f"{out}/fig_refactorings_per_year.pdf"); fig.savefig(f"{out}/fig_refactorings_per_year.png", dpi=200)

# Figure 3: share of commits with at least one refactoring, per year
fig, ax = plt.subplots(figsize=(6.5, 2.9))
for p in P:
    rows = [r for r in per_year(p) if int(r["nonmerge_commits"]) >= 30]  # skip years with <30 commits
    xs = [int(r["year"]) for r in rows]; ys = [float(r["pct_commits_with_refactoring"]) for r in rows]
    ax.plot(xs, ys, color=C[p], linewidth=2, marker="o", markersize=3.5, markeredgecolor=SURF, markeredgewidth=0.8)
    dy = {"checkstyle": -6, "mockito": 0, "jabref": 6}[p]
    ax.annotate(N[p], (xs[-1], ys[-1]), xytext=(6, dy), textcoords="offset points", va="center", fontsize=8.5, color=INK)
ax.set_ylabel("% of commits with refactoring")
ax.set_ylim(0, 65); ax.set_xlim(2000.5, 2028.6)
ax.set_xticks(range(2001, 2027, 2))
ax.grid(axis="y", color=GRID, linewidth=0.6); ax.set_axisbelow(True); ax.tick_params(length=0)
fig.tight_layout()
fig.savefig(f"{out}/fig_share_commits_per_year.pdf"); fig.savefig(f"{out}/fig_share_commits_per_year.png", dpi=200)

# Figure 4: refactoring families, share of instances per project (grouped horizontal bars)
fams = [f for f, _, _ in S["checkstyle"]["families"]]
order = sorted(fams, key=lambda f: -sum(dict((a, c) for a, _, c in S[p]["families"])[f] for p in P))
order = [f for f in order if f != "Other"] + ["Other"]
short = {"Hierarchy (Pull Up / Push Down / Extract Superclass)": "Hierarchy",
         "Signature (Parameters / Exceptions)": "Signature", "Modifier / Annotation": "Modifier / Annotation"}
fig, ax = plt.subplots(figsize=(6.5, 4.0))
h = 0.26
for i, p in enumerate(P):
    share = dict((a, c) for a, _, c in S[p]["families"])
    ys = [k + (i - 1) * h for k in range(len(order))]
    ax.barh(ys, [share[f] for f in order], height=h, color=C[p], edgecolor=SURF, linewidth=0.8, label=N[p])
ax.set_yticks(range(len(order))); ax.set_yticklabels([short.get(f, f) for f in order], color=INK)
ax.invert_yaxis()
ax.set_xlabel("Share of the project's refactoring instances (%)")
ax.grid(axis="x", color=GRID, linewidth=0.6); ax.set_axisbelow(True); ax.tick_params(length=0)
ax.legend(frameon=False, loc="lower right", fontsize=8.5)
fig.tight_layout()
fig.savefig(f"{out}/fig_families.pdf"); fig.savefig(f"{out}/fig_families.png", dpi=200)
print("ok")
