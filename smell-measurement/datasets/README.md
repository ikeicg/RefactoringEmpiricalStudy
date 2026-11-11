# datasets

Design smells (DesigniteJava) and design rule violations (PMD, Design category) before and after refactoring commits, produced with
the [`smell-measurement`](../) tool from the refactoring CSVs in
[`refactoring-extraction/datasets`](../../refactoring-extraction/datasets/). Seed `6491`, 100 commits per project.

| Project | Sampling frame | Commit totals: examined / recorded | File pairs: examined / recorded | Snapshots analysed | Failed snapshots |
|---|---|---|---|---|---|
| [checkstyle](checkstyle/) | 2,220 | 100 / 100 | 354 / 100 | 335 | 0 |
| [mockito](mockito/) | 1,221 | 100 / 100 | 198 / 100 | 298 | 0 |
| [jabref](jabref/) | 5,085 | 100 / 100 | 173 / 100 | 284 | 0 |

The sampling frame is every commit with at least one refactoring in production code. Commits are taken in
the seeded order. Every commit examined could be recorded for the commit totals. For the file pairs, a
commit is skipped when it changes no usable control file, or no usable refactored file; each skipped commit
and its reason are in `<project>_sample.csv`, and the counts per reason are in `run_log.txt`.

## Files per project

| File | Contents |
|---|---|
| `<project>_commit_smells.csv` | Commit totals: one row per commit, with project-wide counts before and after for both tools. |
| `<project>_commit_detections.csv` | The commit totals per smell or rule. |
| `<project>_master_table.csv` | File pairs: a refactored file and a control file from the same commit, with each file's counts before and after. |
| `<project>_pair_detections.csv` | The file pair counts per smell or rule. |
| `<project>_sample.csv` | Every commit examined, in seeded order, and for each part whether it was recorded or why it was skipped. |
| `run_log.txt` | Settings, tool versions, counts of skipped commits and files by reason, and the final result. |

The columns are described in the tool's [README](../README.md#output). The raw output of both tools for each
snapshot is not included; running the tool with the same CSV and seed produces it again.

## Known limits

- In some Checkstyle versions PMD cannot analyse `ConfigurationLoader.java`, `XpathFilter.java`,
  `XpathFilterElement.java` and `XpathUtil.java` (a type it cannot resolve). These files
  are counted in `pmd_unparsed_files_before` / `_after`, their violations are not counted, and they are never
  used in a file pair.
- DesigniteJava reports nested classes under their simple name. Their smells count in the commit totals
  but are not attributed to a file in the file pairs.
