# Refactoring Empirical Study

Scripts and tools for the team's empirical study of refactoring activity across three open-source
Java projects: Checkstyle, Mockito, JabRef.

## Contents

- [`refactoring-extraction/`](refactoring-extraction/) — standalone tool that mines a repo's full
  history with RefactoringMiner and writes a CSV of every refactoring instance. Its
  [`datasets/`](refactoring-extraction/datasets/) folder holds the extracted refactorings, Git tags and
  run logs for each project.
- [`smell-measurement/`](smell-measurement/) — standalone tool that reads that CSV and records code smells
  (DesigniteJava) and design rule violations (PMD) before and after a random sample of refactoring commits. Its
  [`datasets/`](smell-measurement/datasets/) folder holds the results for each project.
- [`release-dates/`](release-dates/) — release dates of each project, with the official source of each date.
- [`analysis/`](analysis/) — scripts and data behind Section 5 (refactoring activity summary), Section 6.1
  (RQ1: timing of refactoring) and Section 6.2 (RQ2: refactoring motivations, including the 300-commit labeling
  sample). `./analysis/run_all.sh` reproduces all of it.
