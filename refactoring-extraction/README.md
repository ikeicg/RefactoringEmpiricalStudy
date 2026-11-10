# refactoring-extraction

Mines a Git repository's full history with [RefactoringMiner](https://github.com/tsantalis/RefactoringMiner)
and writes a CSV of every refactoring instance, plus a release-dates CSV and a run log.

## Build

Requires Java 17+ and Maven.

```sh
mvn package
```

Produces `target/extract.jar` (a self-contained shaded jar — RefactoringMiner and JGit are bundled in).

## Run

```sh
java -jar target/extract.jar <github-url> [start-commit]
```

- `<github-url>` — e.g. `https://github.com/owner/repo.git`
- `[start-commit]` — optional. Branch, tag, or commit hash to walk back from. Omit it to start at
  the repo's current HEAD. Either way, mining walks all the way back to the first commit — full
  history, not a sample.

Example:

```sh
java -jar target/extract.jar https://github.com/owner/repo.git
```

## Output

Written to `<repo-name>_output/` next to wherever you ran the command:

| File | Contents |
|---|---|
| `<repo-name>_extraction.csv` | One row per refactoring instance: `project, commit, commit_date, author_name, author_email, refactoring_type, file_path, code_kind, has_test_file_proxy`. `code_kind` is `production` or `test`, by file path. `has_test_file_proxy` is `yes`/`no` — whether a correspondingly-named test file (`Foo.java` → `FooTest.java`) exists at that commit; a cheap stand-in for real test coverage, not a substitute for it. |
| `<repo-name>_releases.csv` | `tag, date, url` — every Git tag in the repo, with its commit date and a GitHub releases-page URL. |
| `run_log.txt` | What ran: commit counts, every RefactoringMiner timeout or error with its reason, start/end timestamps. |

## Behavior worth knowing

- **Skips merge commits.** Only non-merge commits are walked.
- **300-second timeout per commit.** A commit that times out or errors is logged in
  `run_log.txt` and skipped — never silently counted as "no refactorings".
- **Resumable.** Progress is tracked in `<repo-name>_output/.commits_done`; rerunning the exact
  same command picks up where it left off instead of re-mining everything. If a run is
  interrupted (closed terminal, machine sleep, Ctrl+C), just run the same command again.
- **Throughput varies by repository.** Most commits process in well under a second, but
  RefactoringMiner can take up to the full 300s timeout on an unusually large or complex one, so
  total run time for a full project history isn't reliably predictable from a short sample — a
  repo with a few pathological commits can take much longer overall than its average suggests.
