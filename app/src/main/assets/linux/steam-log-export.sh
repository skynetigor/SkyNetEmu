#!/bin/bash
set -euo pipefail
source_dir="$1"
destination="$2"
mkdir -p "$destination"
# Preserve bounded startup/error evidence without recopying an ever-growing log tree.
for name in bootstrap_log.txt cef_log.txt webhelper_gpu.txt console_log.txt \
    connection_log.txt steamui_login.txt steamui_update.txt webhelper_js.txt steamwebhelper.log; do
  source="$source_dir/$name"
  target="$destination/$name"
  [ -f "$source" ] || continue
  if [ ! -f "$target" ] || [ "$source" -nt "$target" ]; then
    tail -c 65536 "$source" > "$target.tmp"
    mv -f "$target.tmp" "$target"
  fi
done
