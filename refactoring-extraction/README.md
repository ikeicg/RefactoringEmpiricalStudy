# refactoring-extraction

Mines a Git repository's full history with [RefactoringMiner](https://github.com/tsantalis/RefactoringMiner)
and writes a CSV of every refactoring instance, plus a CSV of the repository's Git tags and a run log.

The results for Checkstyle, Mockito and JabRef are in [`datasets/`](datasets/).

## Build

Requires Java 17+ and Maven.

```sh
mvn package
```

Produces `target/extract.jar` (a self-contained shaded jar — RefactoringMiner 3.0.9 and JGit are bundled in).

## Run

```sh
java -jar extract.jar <github-url> [start-commit]
```

- `<github-url>` — e.g. `https://github.com/owner/repo.git`
- `[start-commit]` — optional. Branch, tag, or commit hash to walk back from. Omit it to start at
  the repo's current HEAD. Either way, mining walks all the way back to the first commit — full
  history, not a sample.

For very large repositories you can give it more memory: `java -Xmx4g -jar extract.jar ...`.

## Output

Written to `<repo-name>_output/` next to wherever you ran the command:

| File | Contents |
|---|---|
| `<repo-name>_extraction.csv` | One row per refactoring instance (see columns below). |
| `<repo-name>_tags.csv` | `tag, tagged_commit_date, url` — every Git tag in the repo, with the date of the commit it points to and its GitHub tag URL. A tag is not necessarily a release: tags also mark branches, re-tags, builds and packaging, and the tagged commit date can differ from the official release date. Check tags against the project's official release notes before treating them as releases. |
| `run_log.txt` | The start commit as given and as resolved, how many commits were listed, every timeout or error with its reason, and a final summary. |
| `refactoringminer_messages.log` | RefactoringMiner's own messages, including the stack trace of any commit it could not analyze. |
| `repo/` | The clone (Git history only, no checked-out files). Reused by later runs. |
| `.run_meta`, `.commits_done` | Progress records used for resuming. Don't edit them. |

CSV columns:

| Column | Meaning |
|---|---|
| `project` | Repository name. |
| `commit`, `commit_date` | Commit hash and its commit date (UTC, `YYYY-MM-DD`). |
| `author_name`, `author_email` | Commit author. |
| `refactoring_type` | RefactoringMiner's type name, e.g. `Extract Method`. |
| `file_path` | File of the refactored code before the change (after the change, for refactorings that have no "before" side). |
| `code_kind` | `production` or `test`, by file path (`/test/`, `/tests/`, `/src/it/`, ...). |
| `has_test_file_proxy` | `yes`/`no` — whether a correspondingly-named test file (`Foo.java` → `FooTest.java` or `TestFoo.java`) exists at that commit. A cheap stand-in for test coverage, not a substitute for it. |
| `refactoring_description` | RefactoringMiner's full description of the instance, so each row can be checked by hand. |

## The final summary

Every run ends by printing (and appending to `run_log.txt`) a summary like this:

```
SUMMARY for all runs in this folder:
  non-merge commits in history:          400
  analyzed by RefactoringMiner:          399  (with at least one refactoring: 95)
  first commit (no parent to compare):   1
  timed out:                             0
  errors:                                0
  not attempted yet:                     0
  accounted for:                         400 of 400
  refactoring rows in CSV:               802  (matches the per-commit counts exactly)
  STATUS: COMPLETE - every commit in the history was mined.
```

- "non-merge commits in history" should equal `git rev-list --no-merges --count <start-commit>`
  run in any clone of the repository.
- The CSV row count is re-counted from the file itself and checked against what was recorded for
  each commit.
- The first commit of a repository has no parent, so RefactoringMiner cannot compare it; it is
  counted on its own line, not as an error.

## Behavior worth knowing

- **Skips merge commits.** Only non-merge commits are mined.
- **300-second limit per commit.** RefactoringMiner runs in a separate worker process. If a commit
  takes longer, the worker is stopped and restarted, the commit is logged as a timeout, and mining
  moves on. A commit that times out or errors is never counted as "no refactorings".
- **Resumable.** If a run is interrupted (closed terminal, machine sleep, Ctrl+C, crash), run the
  same command again: it continues from the last finished commit. Rows from a commit that was
  being written when the run stopped are removed and that commit is mined again, so the CSV never
  holds duplicates or partial results.
- **Retries failures.** Rerunning after a finished run retries only the commits that timed out or
  errored. Some commits fail inside RefactoringMiner every time; those stay listed in the log.
- **One start commit per folder.** A rerun with a different start commit in the same output
  folder is refused, so results from different runs are never mixed. To start over, delete
  everything in `<repo-name>_output/` except `repo/`.
- **One run per folder at a time.** A second copy started on the same folder is refused.
- **Throughput varies by repository.** Most commits take well under a second, but some take much
  longer, so total time for a full history isn't reliably predictable from a short sample. The
  progress line shows a running estimate.
