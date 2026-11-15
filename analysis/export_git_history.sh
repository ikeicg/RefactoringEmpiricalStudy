#!/usr/bin/env bash
# Exports the commit list and commit messages of each project at its analysis snapshot.
# Usage: ./export_git_history.sh <clone_dir> <work_dir>
#   clone_dir: where bare clones are kept (created if missing; reused if present)
#   work_dir:  where <project>_commits.txt and <project>_messages.txt are written
set -euo pipefail
CLONES=${1:?clone dir}; WORK=${2:?work dir}
mkdir -p "$CLONES" "$WORK"
declare -A REPO=([checkstyle]=checkstyle/checkstyle [mockito]=mockito/mockito [jabref]=JabRef/jabref)
declare -A SNAP=([checkstyle]=0573dedd580e357c16e51b46a036547fc6caf305
                 [mockito]=116a4d6231dc35f90543060af41dcde00fe4e2ae
                 [jabref]=cd1f0ac836d0c71032f4293ac83b0eca1aef6f11)
for p in checkstyle mockito jabref; do
  if [ ! -d "$CLONES/$p.git" ]; then
    git clone --quiet --bare --filter=blob:none "https://github.com/${REPO[$p]}" "$CLONES/$p.git"
  fi
  # hash|commit date (UTC)|author name|author e-mail, non-merge commits only
  TZ=UTC git -C "$CLONES/$p.git" log --no-merges --date=format-local:%Y-%m-%d \
      --format='%H|%cd|%aN|%aE' "${SNAP[$p]}" > "$WORK/${p}_commits.txt"
  # hash, name, e-mail, subject, body: fields separated by 0x1f, records by 0x1e
  git -C "$CLONES/$p.git" log --no-merges --format='%H%x1f%aN%x1f%aE%x1f%s%x1f%b%x1e' \
      "${SNAP[$p]}" > "$WORK/${p}_messages.txt"
  echo "$p: $(wc -l < "$WORK/${p}_commits.txt") non-merge commits"
done
