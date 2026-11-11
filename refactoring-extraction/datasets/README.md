# datasets

Every refactoring detected in the full history of each project, produced with the
[`refactoring-extraction`](../) tool (RefactoringMiner 3.0.9).

| Project | Snapshot commit | Non-merge commits | Analyzed | Not analyzed | Commits with refactorings | Refactoring instances |
|---|---|---|---|---|---|---|
| [checkstyle](checkstyle/) | `0573dedd580e357c16e51b46a036547fc6caf305` | 17,841 | 17,830 | 10 | 3,868 | 37,893 |
| [mockito](mockito/) | `116a4d6231dc35f90543060af41dcde00fe4e2ae` | 5,872 | 5,871 | 0 | 1,600 | 14,334 |
| [jabref](jabref/) | `cd1f0ac836d0c71032f4293ac83b0eca1aef6f11` | 22,141 | 22,121 | 14 | 5,538 | 78,325 |

"Not analyzed" are commits that timed out (300 s) or failed inside RefactoringMiner; each is
listed with its reason in that project's `run_log.txt`. Root commits (no parent) are not counted
in either column: 1 in Checkstyle and Mockito, 6 in JabRef.

## Files per project

| File | Contents |
|---|---|
| `<project>_extraction.csv` | One row per refactoring instance. |
| `<project>_tags.csv` | Every Git tag in the repository, the date of the commit it points to, and its GitHub URL. A tag is not necessarily a release. |
| `run_log.txt` | Snapshot commit, commit count, every timeout or error, and the final summary of each run. |

## Columns of `<project>_extraction.csv`

| Column | Meaning |
|---|---|
| `project` | Project name. |
| `commit`, `commit_date` | Commit hash and commit date (UTC, `YYYY-MM-DD`). |
| `author_name`, `author_email` | Commit author. |
| `refactoring_type` | RefactoringMiner type, e.g. `Extract Method`. |
| `file_path` | File of the refactored code before the change. |
| `code_kind` | `production` or `test`, by file path. |
| `has_test_file_proxy` | `yes` if `FooTest.java` or `TestFoo.java` existed at that commit for `Foo.java`. A proxy for test coverage. |
| `refactoring_description` | RefactoringMiner's description of the instance. |

Two rows can carry the same description when RefactoringMiner reports the same refactoring at
different places in the code, for example a method extracted from several call sites.
