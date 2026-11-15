# analysis

Scripts and data behind **Section 5** (refactoring activity summary), **Section 6.1** (RQ1: timing of
refactoring activity) and **Section 6.2** (RQ2: refactoring motivations) of the Milestone 3 report.
Every number in those sections comes from the files in [`data/`](data/), and every file in `data/`
is produced by the scripts here.

## Inputs

- [`../refactoring-extraction/datasets/`](../refactoring-extraction/datasets/): the refactoring CSV of each project.
- [`../release-dates/`](../release-dates/): the release tables (Section 6.1 only).
- The Git history of each project at its analysis snapshot commit: commit list and commit messages.
  [`export_git_history.sh`](export_git_history.sh) clones the three repositories and exports them.

## Reproduce

From the repository root (needs `git`, Python 3.10+ and `matplotlib`):

```
./analysis/run_all.sh
```

The first run clones the three repositories into `analysis/.cache/` (not committed), which takes a few
minutes. The run overwrites `analysis/data/`. It uses the same random seed as the smell measurement
(6491), so it always draws the same sample.

## Data

### `data/section5/`: refactoring activity summary (Section 5)

| File | Contents |
|---|---|
| `stats.json` | All statistics of Section 5, per project: totals, types, families, production/test split, density per commit, developers, files, test-file proxy. |
| `<project>_per_year.csv` | Per year: non-merge commits, refactoring commits, refactoring instances, % of commits with a refactoring. |
| `<project>_monthly.csv` | Per calendar month: refactoring instances at the low, medium and high level of Murphy-Hill et al., and the total. |
| `levels.json` | Instances and share per level and project, and which refactoring types belong to each level. |
| `fig_*.pdf`, `fig_*.png` | The figures of Section 5, including the monthly activity chart (`fig_activity_monthly`). |

### `data/section6_1_rq1/`: timing of refactoring activity (Section 6.1)

| File | Contents |
|---|---|
| `rq1_stats.json` | All statistics of Section 6.1: release-window lifts, position within the release interval, dispersion and burstiness. |
| `<project>_weekly.csv` | Weekly series from the first commit: commits, refactoring commits, refactoring instances. |
| `<project>_intervals.csv` | One row per interval between consecutive minor releases with at least 10 commits: start, end, length, commits, refactoring commits, instances. Release-day commits are excluded. |
| `fig_release_interval.pdf`, `.png` | The figure of Section 6.1. |

### `data/section6_2_rq2/`: refactoring motivations (Section 6.2)

| File | Contents |
|---|---|
| `rq2_stats.json` | All statistics of Section 6.2: issue and pull-request links, message content, SAR patterns. |
| `rq2_labeling_sample.csv` | The 300-commit labeling sample (100 per project). Each row has the commit link, date, refactorings and main types, issue / pull-request / other `#N` references, and the message. The empty `motivation_rater1`, `motivation_rater2`, `agreed_motivation` and `notes` columns are for Milestone 4. |
| `<project>_commit_messages.csv` | Every non-merge commit up to the snapshot with its message data: `is_bot`, number of refactorings (0 = none detected), issue / pull-request / other references, `refactor_word`, `sar_any` (any of the 87 SAR patterns of AlOmar et al.), `sar_specific` (operation-specific subset), `mentions_fix`, `mentions_feature`, informative words in the subject, `has_body`, subject and body. |

Author names and e-mail addresses are not repeated here (they are in the extraction CSVs). In the message text, sign-off and co-author trailer lines are removed and any remaining e-mail address is shown as `[e-mail]`; the statistics are computed on the full, unedited messages.

## Scripts

| Script | Produces |
|---|---|
| `scripts/section5_stats.py` | `data/section5/stats.json`, `<project>_per_year.csv` |
| `scripts/section5_figures.py` | the Section 5 figures (per year, families, share of commits) |
| `scripts/section5_activity.py` | `<project>_monthly.csv`, `levels.json`, `fig_activity_monthly` |
| `scripts/rq1_data.py` | `data/section6_1_rq1/` (all but the figure) |
| `scripts/rq1_figure.py` | the Section 6.1 figure |
| `scripts/rq2_data.py` | `data/section6_2_rq2/` |
| `export_git_history.sh` | commit lists and messages at each snapshot (inputs, kept in `.cache/`) |
| `run_all.sh` | runs everything above in order |

## Unit-test coverage with JaCoCo (requirement 1c, Section 5.8)

Historical versions cannot be built any more, so coverage is measured once, at the analysis snapshot,
and linked to the production files that were refactored. Measured: Checkstyle (whole project, 6,874 tests)
and Mockito (`mockito-core`). JabRef was not completed: its suite also needs JavaFX, a database and
external services. To reproduce (needs internet, Maven, and a JDK 21 for Checkstyle):

```bash
./analysis/coverage/run_coverage.sh mockito      # about 5 min
./analysis/coverage/run_coverage.sh checkstyle   # about 10 min, needs JDK 21
python3 analysis/coverage/coverage_match.py
```

| File | Contents |
|---|---|
| `data/coverage/<project>_jacoco.xml` | JaCoCo line coverage per source file at the snapshot. |
| `data/coverage/<project>_file_coverage.csv` | Per refactored production file: refactorings (all / last 12 months / with a matching test file), status (`covered`, `not covered`, `outside measured module`, `not present at snapshot`) and line coverage. |
| `data/coverage/coverage_stats.json` | Per project: line coverage, share of refactored files and refactorings in covered files, the last-12-months share, and agreement with the test-file proxy. |

Failing tests do not stop the run. Coverage describes the files as they are at the snapshot, not at the
time of each refactoring, which is why the last-12-months share is reported separately.

## Definitions shared by all scripts

- **Bots**: authors whose name or e-mail contains `[bot]`, `dependabot`, `github-actions` or `renovate`.
  They are excluded from developer counts (Section 5) and from Sections 6.1 and 6.2.
- **Test code**: the extraction CSV's `code_kind`, plus the folders the smell-measurement tool also
  treats as test code (`src/testinputs/`, `src/databaseTest/`, ...). This reclassifies 193 Checkstyle and
  34 JabRef rows, and makes the number of commits with production refactorings match Section 6.3
  (2,220 / 1,221 / 5,085).
- **Developers**: author entries that share a name or an e-mail address are merged into one person.
- **Refactoring levels** (Section 5.4), after Murphy-Hill, Parnin and Black (ICSE 2009): *high* changes signatures
  (rename, move, change type, parameters, modifiers, annotations); *medium* changes signatures and code
  blocks (extract/inline method, pull up/push down, extract class, extract/inline attribute); *low* changes only
  code inside method bodies (local variables, conditionals, lambdas, loops). The full list is in `levels.json`.
- **Release milestones** (Section 6.1): final releases whose version is `x.y` or `x.y.0`.
