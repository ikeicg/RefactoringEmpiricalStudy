# release-dates

Release dates of Checkstyle, Mockito and JabRef, with the official source of each date.

| File | Releases |
|---|---|
| [`checkstyle_releases.csv`](checkstyle_releases.csv) | 218 |
| [`mockito_releases.csv`](mockito_releases.csv) | 508 |
| [`jabref_releases.csv`](jabref_releases.csv) | 52 (12 of them pre-releases) |

Each date is taken from an official project page where one gives it. The Git tag is used only
when no official page gives a usable date. Pre-releases are left out, except for JabRef, whose
pre-releases are kept and marked `(pre-release)` in `date_source`.

## Columns

| Column | Meaning |
|---|---|
| `version` | Released version. |
| `release_date` | Release date (`YYYY-MM-DD`). |
| `date_source` | Where the date comes from. |
| `citation_url` | Page where the date can be checked. |

## Sources

- Checkstyle: release notes on [checkstyle.org](https://checkstyle.org/release-notes.html) (8.12 on),
  the [SourceForge download area](https://sourceforge.net/projects/checkstyle/files/OldFiles/) (before 8.12),
  and the Git tag where neither gives a usable date.
- Mockito: [`doc/release-notes/official.md`](https://github.com/mockito/mockito/blob/main/doc/release-notes/official.md)
  (1.9.0 to 3.6.0), [GitHub Releases](https://github.com/mockito/mockito/releases) (3.6.1 on), the
  [Google Code release notes](https://code.google.com/archive/p/mockito/wikis/ReleaseNotes.wiki) (1.5 to 1.8.5),
  the release commit (1.0 to 1.4), and the Git tag where none of these gives a date.
- JabRef: [`CHANGELOG.md`](https://github.com/JabRef/jabref/blob/main/CHANGELOG.md) (5.0-alpha on) and
  [GitHub Releases](https://github.com/JabRef/jabref/releases) (2.8 to 4.3.1), checked against the
  [JabRef blog](https://blog.jabref.org/).
