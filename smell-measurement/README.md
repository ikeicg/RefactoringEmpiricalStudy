# smell-measurement

Records code smells before and after refactoring commits. It reads the refactoring CSV written by the
[`refactoring-extraction`](../refactoring-extraction/) tool, picks refactoring commits at random with a fixed
seed, and runs two detectors on the project's production code at each commit (after) and at its parent (before):

- [DesigniteJava](https://github.com/tushartushar/DesigniteJava) (open source, commit `307ddf7`): its 17 design smells, at the tool's default thresholds.
- [PMD](https://pmd.github.io/) 7.17.0: rule violations from its bundled Design category, `category/java/design.xml`,
  unmodified (complexity, coupling and size rules such as Cyclomatic Complexity, God Class and Coupling Between
  Objects). PMD switches off one rule of the category, Loose Package Coupling, because it needs a list of packages.

Both tools read source code, so the project does not have to be built at any commit.

## Requirements

Java 17+, Maven and Git.

PMD: download `pmd-dist-7.17.0-bin.zip` from the [PMD releases page](https://github.com/pmd/pmd/releases/tag/pmd_releases%2F7.17.0) and unzip it.

DesigniteJava: build it at the pinned commit. Later commits set a debug flag that makes the tool ignore its
input and output options.

```sh
git clone https://github.com/tushartushar/DesigniteJava.git
cd DesigniteJava
git checkout 307ddf7
mvn -DskipTests package          # produces target/DesigniteJava.jar
```

## Build

```sh
mvn package
```

Produces `target/smells.jar`.

## Run

```sh
java -jar smells.jar --csv <project>_extraction.csv --repo <github-url> \
     --pmd pmd-bin-7.17.0 --designite DesigniteJava/target/DesigniteJava.jar
```

| Option | Meaning |
|---|---|
| `--csv` | The refactoring CSV from the `refactoring-extraction` tool. |
| `--repo` | The same repository: a clone URL, or a local clone with full history (for example the `repo/` folder the extraction tool left behind). |
| `--pmd` | The unzipped PMD folder (it contains `bin/pmd`). |
| `--designite` | The DesigniteJava jar. |
| `--seed` | Random seed that fixes which commits are sampled. Default `6491`. |
| `--commits` | How many refactoring commits to record. Default `100`. |
| `--threads` | How many commits are analysed at the same time. Default `1`. Each one runs both tools on the whole project, so raise it only with enough memory. |
| `--out` | Output folder. Default `<project>_smells_output`. |

## What it does

1. Reads the CSV and keeps the commits with at least one refactoring in production code. This is the sampling frame.
2. Shuffles the frame with the seed. Every later step takes commits in that order.
3. For a commit, it exports the production `.java` files at the commit and at its parent (test code is left out),
   and runs DesigniteJava and PMD on each export.

It then fills two parts, each from the start of the same order:

- **Commit totals.** The first `--commits` commits whose two snapshots both tools could analyse. For each, the
  project-wide totals of both tools, before and after, overall and per smell or rule.
- **File pairs.** The first `--commits` commits that also hold a usable pair of files:
  - a **refactored file**: a production file the CSV names in that commit;
  - a **control file**: a file changed in the same commit that no refactoring of the commit names, with the
    closest churn (lines added + deleted) to the refactored file.

  Both files must exist at the same path before and after, hold exactly one top-level class or interface
  (DesigniteJava does not model enums, records or annotation types), and be analysed by both tools. Commits
  that change no such control file are skipped in this part only.

The two parts overlap but are not identical: a commit with no control file is in the commit totals but not in
the file pairs, so the file pairs reach further down the order. Every skipped commit is logged with its reason.
A tool that failed is never counted as a tool that found nothing.

## Output

Written to the output folder:

| File | Contents |
|---|---|
| `<project>_commit_smells.csv` | Commit totals: one row per commit, project-wide totals before and after, for both tools. |
| `<project>_commit_detections.csv` | The same totals per smell or rule: one row per commit, snapshot, tool and smell or rule. |
| `<project>_master_table.csv` | File pairs: two rows per pair (refactored and control file), with each file's counts before and after. |
| `<project>_pair_detections.csv` | The pair counts per smell or rule. |
| `<project>_sample.csv` | Every commit examined, in sampled order, and for each part whether it was recorded or why it was skipped. |
| `run_log.txt` | Settings, tool versions, counts of skipped commits and files by reason, and a final result line. |
| `raw/` | The raw output of both tools for every snapshot. Reused by later runs. |
| `repo/` | The clone, when `--repo` is a URL. |

Columns of `<project>_commit_smells.csv`:

| Column | Meaning |
|---|---|
| `sample_id` | `c001`, `c002`, ... in the order commits were recorded. Links to `<project>_commit_detections.csv`. |
| `project`, `commit`, `parent_commit`, `commit_date` | The commit and its parent (the "before" version). |
| `production_refactorings`, `refactoring_types` | Refactorings the CSV lists for the commit in production code. |
| `java_files_before`, `java_files_after` | Production `.java` files analysed at the parent and at the commit. |
| `designite_before`, `designite_after` | DesigniteJava design smells in the whole project. |
| `pmd_before`, `pmd_after` | PMD rule violations in the whole project. |
| `pmd_unparsed_files_before`, `pmd_unparsed_files_after` | Files PMD could not parse, whose violations are therefore not counted. |

Columns of `<project>_master_table.csv`: `pair_id` (`p001`, `p002`, ...), `project`, `commit`, `parent_commit`,
`file_path`, `unit_name`, `group` (`treated` or `control`), `refactoring_types`, `lines_added`, `lines_deleted`,
`pmd_before`, `pmd_after`, `dj_before`, `dj_after`, `snapshot_before`, `snapshot_after` (the `raw/` folders
holding that snapshot's tool output).

## Behavior worth knowing

- **Same result every time.** The seed fixes the order of commits, and results are taken in that order even
  when several commits are analysed at once.
- **Resumable.** Tool output is kept in `raw/`. Running the same command again reuses it and finishes with
  the same files. A snapshot whose analysis was interrupted is analysed again. A snapshot analysed with another PMD
  ruleset keeps its DesigniteJava output, and only PMD is run again.
- **PMD settings.** PMD runs with a larger thread stack (`-Xss16m`), because its default stack overflows on some
  very long expressions. When PMD finishes but cannot analyse single files (for example a type it cannot
  resolve), those files are listed in its report, counted in `pmd_unparsed_files_*`, and never used in a pair.
- **Test code.** Found by path: `/test/`, `/tests/`, `/testFixtures/`, `/androidTest/`, `/integrationTest/`,
  `/src/it/`, `/src/xdocs-examples/`, `/src/testinputs/` and `/src/databaseTest/`. A CSV row counts as
  production code only if the CSV says so and its path matches none of these.
- **Nested types.** DesigniteJava reports a nested class under its simple name. Such smells count in the
  project totals but are not attributed to a file.
- **One run per folder at a time.** A second copy started on the same folder is refused.
