#!/bin/bash
# Builds the XFCE desktop bundle the app installs into a Linux runtime (LinuxDesktop, linux.json "desktops").
#
#   tools/xfce-bundle/build.sh [output-dir]      needs: curl, bsdtar, zstd, python3, shasum
#
# It downloads the packages of packages.txt from Arch Linux ARM (the runtime is Arch Linux ARM, so the libraries match), unpacks
# them into one tree and writes xfce-<version>.tar.zst plus the JSON for the "desktops" list of the registry's linux.json.
# The app adds the files to the runtime without replacing any, so only files the runtime lacks matter.
#
# After a package update, check that nothing the programs need is missing from runtime + bundle:
#   llvm-readelf -d <bin> | grep NEEDED     (the Android NDK has llvm-readelf)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$(cd "${1:-$here/out}" 2>/dev/null && pwd || (mkdir -p "${1:-$here/out}" && cd "${1:-$here/out}" && pwd))"
mirror="${MIRROR:-http://mirror.archlinuxarm.org/aarch64}"
work="$out/work"; rm -rf "$work"; mkdir -p "$work/db" "$work/pkgs" "$work/stage"

for repo in core extra; do
  curl -sSfL -o "$work/db/$repo.db" "$mirror/$repo/$repo.db"
  mkdir -p "$work/db/$repo"; bsdtar -xf "$work/db/$repo.db" -C "$work/db/$repo"
done

python3 -I - "$work" "$here/packages.txt" > "$work/files.txt" <<'PY'
import os, sys
work, names = sys.argv[1], [l.strip() for l in open(sys.argv[2]) if l.strip() and not l.startswith('#')]
found = {}
for repo in ('core', 'extra'):
    root = os.path.join(work, 'db', repo)
    for d in os.listdir(root):
        f = os.path.join(root, d, 'desc')
        if not os.path.isfile(f): continue
        lines, cur, m = open(f).read().split('\n'), None, {}
        for line in lines:
            if line.startswith('%') and line.endswith('%'): cur = line; m[cur] = []
            elif line and cur: m[cur].append(line)
        if '%NAME%' not in m: continue
        found.setdefault(m['%NAME%'][0], (repo, m['%FILENAME%'][0], m['%VERSION%'][0]))
for n in names:
    if n not in found: sys.exit('not in the repositories: ' + n)
    print(*found[n])
PY

while read -r repo file version; do
  [ -f "$work/pkgs/$file" ] || curl -sSfL -o "$work/pkgs/$file" "$mirror/$repo/$file"
  bsdtar -xf "$work/pkgs/$file" -C "$work/stage"
done < "$work/files.txt"
rm -f "$work"/stage/.PKGINFO "$work"/stage/.MTREE "$work"/stage/.BUILDINFO "$work"/stage/.INSTALL
# Documentation, translations and man pages are not needed on a phone.
rm -rf "$work"/stage/usr/share/{man,doc,info,locale,licenses,gtk-doc}

version="$(awk '$2 ~ /^xfce4-session-/ {split($3,a,"-"); print a[1]}' "$work/files.txt" | head -1)"
[ -n "$version" ] || version="$(awk '/xfce4-session/ {print $3}' "$work/files.txt" | cut -d- -f1)"
archive="$out/xfce-${version:-dev}.tar.zst"
( cd "$work/stage" && COPYFILE_DISABLE=1 bsdtar --no-xattrs -cf - . ) | zstd -19 -T0 -q -f -o "$archive"
sha="$(shasum -a 256 "$archive" | cut -d' ' -f1)"; size="$(wc -c < "$archive" | tr -d ' ')"
echo "wrote $archive ($size bytes)"
cat <<JSON
Add to the "desktops" list of linux.json (the url is where you publish the file):
{"id": "xfce-${version:-dev}", "name": "XFCE", "version": "${version:-dev}", "url": "https://.../xfce-${version:-dev}.tar.zst", "sha256": "$sha", "size": $size, "channel": "stable"}
JSON
