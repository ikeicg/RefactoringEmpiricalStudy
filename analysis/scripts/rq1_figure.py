"""Figure for Section 6.1 (RQ1): share of commits with refactoring by position in the release interval."""
import json, sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
out = sys.argv[1]
S = json.load(open(f"{out}/rq1_stats.json"))
P = ["checkstyle", "mockito", "jabref"]; N = {"checkstyle": "Checkstyle", "mockito": "Mockito", "jabref": "JabRef"}
C = {"checkstyle": "#2a78d6", "mockito": "#eb6834", "jabref": "#1baf7a"}
INK, INK2, GRID, SURF = "#0b0b0b", "#52514e", "#e4e3df", "#ffffff"
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 9, "axes.edgecolor": INK2, "axes.labelcolor": INK2,
                     "xtick.color": INK2, "ytick.color": INK2, "axes.spines.top": False, "axes.spines.right": False})
fig, ax = plt.subplots(figsize=(6.5, 3.0))
xs = [i * 10 + 5 for i in range(10)]
for p in P:
    ys = S[p]["position_bins_ref_share"]
    ax.plot(xs, ys, color=C[p], linewidth=2, marker="o", markersize=4, markeredgecolor=SURF, markeredgewidth=0.8)
    ax.axhline(S[p]["base_ref_share"], color=C[p], linewidth=0.8, linestyle=(0, (3, 3)), alpha=0.8)
    ax.annotate(N[p], (xs[-1], ys[-1]), xytext=(6, {"checkstyle": -9, "mockito": 6, "jabref": 7}[p]),
                textcoords="offset points", va="center", fontsize=8.5, color=INK)
ax.set_xticks([0, 20, 40, 60, 80, 100])
ax.set_xticklabels(["0%\n(after release)", "20%", "40%", "60%", "80%", "100%\n(next release)"])
ax.set_xlim(0, 112); ax.set_ylim(0, 40)
ax.set_xlabel("Position of the commit within its release interval (release-day commits excluded)")
ax.set_ylabel("% of commits with refactoring")
ax.grid(axis="y", color=GRID, linewidth=0.6); ax.set_axisbelow(True); ax.tick_params(length=0)
fig.tight_layout()
fig.savefig(f"{out}/fig_release_interval.pdf"); fig.savefig(f"{out}/fig_release_interval.png", dpi=200)
