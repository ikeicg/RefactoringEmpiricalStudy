#!/usr/bin/env bash
# Reproduces every number, table input and figure of Sections 5, 6.1 and 6.2.
# Run from the repository root:  ./analysis/run_all.sh [clone_dir]
# Needs git, Python 3.10+ and matplotlib (pip install matplotlib).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
A="$ROOT/analysis"; S="$A/scripts"
CLONES=${1:-"$A/.cache/clones"}; WORK="$A/.cache/git"
DS="$ROOT/refactoring-extraction/datasets"; REL="$ROOT/release-dates"
"$A/export_git_history.sh" "$CLONES" "$WORK"
mkdir -p "$A/data/section5" "$A/data/section6_1_rq1" "$A/data/section6_2_rq2"
python3 -I "$S/section5_stats.py"   "$DS" "$WORK" "$A/data/section5"        > /dev/null
python3 -I "$S/section5_figures.py" "$A/data/section5"
python3 -I "$S/rq1_data.py"         "$DS" "$REL" "$WORK" "$A/data/section6_1_rq1" > /dev/null
python3 -I "$S/rq1_figure.py"       "$A/data/section6_1_rq1"
python3 -I "$S/rq2_data.py"         "$DS" "$WORK" "$A/data/section6_2_rq2"  > /dev/null
echo "Done. Outputs are in analysis/data/."
