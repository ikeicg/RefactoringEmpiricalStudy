# smell-measurement

Records code smells before and after refactoring commits. It reads the refactoring CSV written by the
[`refactoring-extraction`](../refactoring-extraction/) tool, picks refactoring commits at random with a fixed
seed, and runs two detectors on the project's production code at each commit (after) and at its parent (before):

- [DesigniteJava](https://github.com/tushartushar/DesigniteJava) (open source, commit `307ddf7`): its 17 design smells, at the tool's default thresholds.
- [PMD](https://pmd.github.io/) 7.17.0: rule violations from its bundled `rulesets/java/quickstart.xml`, unmodified.

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
2. Shuffles the frame with the seed and takes commits in that order until `--commits` are recorded.
3. For each commit, it exports the production `.java` files at the commit and at its parent (test code is left
   out), and runs DesigniteJava and PMD on each export.
4. It records the project-wide totals of both tools, before and after, overall and per smell or rule.
5. It also records one pair of files from the commit:
   - a **refactored file**: a production file the CSV names in that commit;
   - a **control file**: a file changed in the same commit that no refactoring of the commit names, with the
     closest churn (lines added + deleted) to the refactored file.

   Both files must exist at the same path before and after, hold exactly one top-level class or interface
   (DesigniteJava does not model enums, records or annotation types), and be analysed by both tools.

A commit is skipped, with its reason logged, when no such pair exists or when a tool fails on one of its
snapshots. A tool that failed is never counted as a tool that found nothing.

## Output

Written to the output folder:

| File | Contents |
|---|---|
| `<project>_commit_smells.csv` | One row per recorded commit: project-wide totals before and after, for both tools. |
| `<project>_commit_detections.csv` | The same totals per smell or rule: one row per commit, snapshot, tool and smell or rule. |
| `<project>_master_table.csv` | The file pairs: two rows per recorded commit (refactored and control file), with each file's counts before and after. |
| `<project>_pair_detections.csv` | The pair counts per smell or rule. |
| `<project>_sample.csv` | Every commit examined, in sampled order, and whether it was recorded or why it was skipped. |
| `run_log.txt` | Settings, tool versions, counts of skipped commits and files by reason, and a final result line. |
| `raw/` | The raw output of both tools for every snapshot. Reused by later runs. |
| `repo/` | The clone, when `--repo` is a URL. |

Columns of `<project>_commit_smells.csv`:

| Column | Meaning |
|---|---|
| `sample_id` | `c001`, `c002`, ... in the order commits were recorded. Links to the other files. |
| `project`, `commit`, `parent_commit`, `commit_date` | The commit and its parent (the "before" version). |
| `production_refactorings`, `refactoring_types` | Refactorings the CSV lists for the commit in production code. |
| `java_files_before`, `java_files_after` | Production `.java` files analysed at the parent and at the commit. |
| `designite_before`, `designite_after` | DesigniteJava design smells in the whole project. |
| `pmd_before`, `pmd_after` | PMD rule violations in the whole project. |
| `pmd_unparsed_files_before`, `pmd_unparsed_files_after` | Files PMD could not parse, whose violations are therefore not counted. |

Columns of `<project>_master_table.csv`: `pair_id` (the `sample_id`), `project`, `commit`, `parent_commit`,
`file_path`, `unit_name`, `group` (`treated` or `control`), `refactoring_types`, `lines_added`, `lines_deleted`,
`pmd_before`, `pmd_after`, `dj_before`, `dj_after`, `snapshot_before`, `snapshot_after` (the `raw/` folders
holding that snapshot's tool output).

## Behavior worth knowing

- **Same result every time.** The seed fixes the order of commits, and results are taken in that order even
  when several commits are analysed at once.
- **Resumable.** Tool output is kept in `raw/`. Running the same command again reuses it and finishes with
  the same files. A snapshot whose analysis was interrupted is analysed again.
- **Nested types.** DesigniteJava reports a nested class under its simple name. Such smells count in the
  project totals but are not attributed to a file.
- **One run per folder at a time.** A second copy started on the same folder is refused.
