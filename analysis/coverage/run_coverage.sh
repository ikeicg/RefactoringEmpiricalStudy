#!/usr/bin/env bash
# Measures unit-test line coverage with JaCoCo at the analysis snapshot of one project.
# Run from the repository root:   ./analysis/coverage/run_coverage.sh <checkstyle|mockito|jabref> [work_dir]
# Needs: git, curl, a JDK 21+ (java on PATH), and for Checkstyle Maven 3.6.3+ (mvn on PATH).
# Mockito and JabRef use their own Gradle wrapper, which downloads the JDK version it needs.
# Output: analysis/data/coverage/<project>_jacoco.xml; the build log stays in the work folder.
set -euo pipefail
P=${1:?usage: run_coverage.sh <checkstyle|mockito|jabref> [work_dir]}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HERE="$ROOT/analysis/coverage"; OUT="$ROOT/analysis/data/coverage"
WORK=${2:-"$ROOT/analysis/.cache/coverage"}
JACOCO=0.8.15
case "$P" in
  checkstyle) URL=https://github.com/checkstyle/checkstyle.git; SNAP=0573dedd580e357c16e51b46a036547fc6caf305 ;;
  mockito)    URL=https://github.com/mockito/mockito.git;       SNAP=116a4d6231dc35f90543060af41dcde00fe4e2ae ;;
  jabref)     URL=https://github.com/JabRef/jabref.git;         SNAP=cd1f0ac836d0c71032f4293ac83b0eca1aef6f11 ;;
  *) echo "unknown project: $P"; exit 1 ;;
esac
mkdir -p "$WORK" "$OUT"
LOG="$WORK/${P}_build.log"   # kept out of the repository (can be large)

# 1. The project at exactly the snapshot commit used everywhere else in the study.
SRC="$WORK/$P"
if [ ! -d "$SRC/.git" ]; then
  git init -q "$SRC"; git -C "$SRC" remote add origin "$URL"
fi
git -C "$SRC" fetch -q --depth 1 origin "$SNAP"
git -C "$SRC" checkout -q --force FETCH_HEAD
[ "$P" = jabref ] && git -C "$SRC" submodule update -q --init --depth 1 || true

# 2. JaCoCo command-line tool, to turn the execution data into an XML report.
CLI="$WORK/jacococli-$JACOCO.jar"
[ -f "$CLI" ] || curl -fsSL -o "$CLI" \
  "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/$JACOCO/org.jacoco.cli-$JACOCO-nodeps.jar"

# 3. Run the unit tests with coverage. Failing tests are allowed; the coverage is still recorded.
echo "[$P] running the unit tests with JaCoCo (this takes a while; log: $LOG)"
cd "$SRC"
case "$P" in
  checkstyle)
    # The pom already instruments the classes for JaCoCo (offline mode) during the test phase.
    mvn -B test -Dmaven.test.failure.ignore=true \
        -Dcheckstyle.skip -Dpmd.skip -Dspotbugs.skip -Dforbiddenapis.skip -Dxml.skip > "$LOG" 2>&1 || echo "[$P] the build reported errors; checking for coverage anyway"
    EXEC=target/jacoco.exec
    CLASSES=target/generated-classes/jacoco/classes   # original (uninstrumented) classes
    [ -d "$CLASSES" ] || CLASSES=target/generated-classes/jacoco
    SOURCES=src/main/java ;;
  mockito)
    ./gradlew --no-configuration-cache -I "$HERE/jacoco-init.gradle" :mockito-core:test > "$LOG" 2>&1 || echo "[$P] the build reported errors; checking for coverage anyway"
    EXEC=mockito-core/build/jacoco/test.exec
    CLASSES=mockito-core/build/classes/java/main
    SOURCES=mockito-core/src/main/java ;;
  jabref)
    # Only the jablib module (the logic library); the GUI module needs a display to test.
    ./gradlew --no-configuration-cache -I "$HERE/jacoco-init.gradle" :jablib:test > "$LOG" 2>&1 || echo "[$P] the build reported errors; checking for coverage anyway"
    EXEC=jablib/build/jacoco/test.exec
    CLASSES=jablib/build/classes/java/main
    SOURCES=jablib/src/main/java ;;
esac
[ -f "$EXEC" ] || { echo "[$P] no coverage data was produced; see the end of $LOG"; tail -30 "$LOG"; exit 1; }

# 4. XML report: line counters per source file.
java -jar "$CLI" report "$EXEC" --classfiles "$CLASSES" --sourcefiles "$SOURCES" \
     --xml "$OUT/${P}_jacoco.xml" >> "$LOG" 2>&1
grep -E "Tests run:|tests completed|BUILD" "$LOG" | tail -3 || true
echo "[$P] done: $OUT/${P}_jacoco.xml"
