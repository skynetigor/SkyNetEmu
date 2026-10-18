#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Adapted from The412Banner/Banners-Turnip cb840f547d4866d9a1a2cb04258da0e16a19e84d
# build_turnip_linux.sh. See LICENSE.builder and README.md.
fetch(){	# <url> <out>
	curl -fsSL --retry 6 --retry-delay 10 --retry-all-errors "$1" -o "$2" || die "download failed: $1"
}

# --- the sysroot: Arch Linux ARM aarch64 packages, dependency closure over the repo databases ---
build_sysroot(){
	mkdir -p "$workdir/db" "$workdir/pkgs" && cd "$workdir"
	log "fetching Arch Linux ARM package databases"
	local repo_name
	for repo_name in core extra alarm; do
		fetch "$mirror/$repo_name/$repo_name.db" "db/$repo_name.db"
		rm -rf "db/x_$repo_name" && mkdir -p "db/x_$repo_name"
		tar -xzf "db/$repo_name.db" -C "db/x_$repo_name" || die "$repo_name.db is not a package database"
	done

	# %DEPENDS% lives in a separate `depends` file and several names are virtual (%PROVIDES%).
	python3 - $sysroot_seeds > pkglist.txt <<'PY' || die "package closure failed"
import os, sys, collections
pkgs, provides = {}, collections.defaultdict(list)
def strip(d):
    for op in (">=", "<=", "==", ">", "<", "="):
        if op in d: return d.split(op)[0]
    return d
for repo in ("core", "extra", "alarm"):
    base = os.path.join("db", "x_" + repo)
    for entry in os.listdir(base):
        fields, key = {}, None
        for name in ("desc", "depends"):
            path = os.path.join(base, entry, name)
            if not os.path.exists(path): continue
            for line in open(path, encoding="utf-8", errors="replace"):
                line = line.rstrip("\n")
                if line.startswith("%") and line.endswith("%"): key = line.strip("%"); fields[key] = []
                elif line == "": key = None
                elif key: fields[key].append(line)
        n = fields.get("NAME", [None])[0]
        if not n: continue
        pkgs[n] = {"repo": repo, "file": fields["FILENAME"][0],
                   "depends": [strip(d) for d in fields.get("DEPENDS", [])],
                   "provides": [strip(p) for p in fields.get("PROVIDES", [])]}
        provides[n].append(n)
        for p in pkgs[n]["provides"]: provides[p].append(n)
seen, queue, missing = set(), list(sys.argv[1:]), []
while queue:
    want = queue.pop()
    real = want if want in pkgs else (provides.get(want) or [None])[0]
    if real is None: missing.append(want); continue
    if real in seen: continue
    seen.add(real)
    queue.extend(pkgs[real]["depends"])
if missing: sys.exit("unresolved: " + " ".join(missing))
for n in sorted(seen): print(pkgs[n]["repo"] + "/" + pkgs[n]["file"])
PY
	log "$(wc -l < pkglist.txt) packages in the sysroot closure"

	rm -rf "$sysroot" && mkdir -p "$sysroot"
	: > "$workdir/sysroot-packages.txt"
	local entry file
	while read -r entry; do
		file="${entry#*/}"
		# A mirror error page is not a package; check before extracting, not after.
		if ! tar -tf "pkgs/$file" >/dev/null 2>&1; then
			rm -f "pkgs/$file"
			fetch "$mirror/$entry" "pkgs/$file"
			tar -tf "pkgs/$file" >/dev/null || die "$file is not a package"
		fi
		tar -xf "pkgs/$file" -C "$sysroot" --no-same-owner --no-same-permissions \
			--exclude=.PKGINFO --exclude=.MTREE --exclude=.INSTALL --exclude=.BUILDINFO --exclude=.CHANGELOG \
			|| die "cannot unpack $file"
		echo "$file" >> "$workdir/sysroot-packages.txt"
	done < pkglist.txt
	chmod -R u+rwX "$sysroot"

	# glibc pulls in `filesystem`, so /lib -> /usr/lib is there and the linker script inside
	# libc.so (which names absolute /usr/lib paths, resolved against --sysroot) works. Assert what
	# the build actually opens rather than trust the closure: a mirror that moved a library into
	# another package would otherwise fail 20 minutes later inside meson.
	for f in usr/lib/libc.so.6 usr/lib/libdrm.so usr/lib/libwayland-client.so usr/lib/libxcb.so \
	         usr/lib/pkgconfig/libdrm.pc usr/lib/pkgconfig/wayland-client.pc usr/lib/pkgconfig/xcb.pc \
	         usr/share/pkgconfig/wayland-protocols.pc usr/include/xf86drm.h; do
		[ -e "$sysroot/$f" ] || die "sysroot is missing $f"
	done
	log "sysroot glibc: $(basename "$(readlink -f "$sysroot/usr/lib/libc.so.6")")"
}

# --- host wayland-scanner, built from the pinned release (seconds; no libraries, no docs) ---
build_host_scanner(){
	cd "$workdir"
	fetch "https://gitlab.freedesktop.org/wayland/wayland/-/releases/$wl_version/downloads/wayland-$wl_version.tar.xz" "wayland-$wl_version.tar.xz"
	echo "$wl_sha256  wayland-$wl_version.tar.xz" | sha256sum -c --quiet || die "wayland-$wl_version.tar.xz checksum mismatch"
	rm -rf "wayland-$wl_version" wayland-build && tar -xJf "wayland-$wl_version.tar.xz"
	meson setup wayland-build "wayland-$wl_version" --prefix "$hosttools" --libdir lib --buildtype release \
		-Dlibraries=false -Dscanner=true -Dtests=false -Ddocumentation=false -Ddtd_validation=false \
		|| die "host wayland-scanner: meson setup failed"
	ninja -C wayland-build install || die "host wayland-scanner: build failed"
	[ -x "$hosttools/bin/wayland-scanner" ] || die "host wayland-scanner was not installed"
	"$hosttools/bin/wayland-scanner" --version
}


# Invoked with a dedicated cache directory; never modifies the host toolchain.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    set -euo pipefail
    workdir="$(mkdir -p "${1:?cache directory}" && cd "$1" && pwd)"
    sysroot="$workdir/sysroot"
    hosttools="$workdir/host"
    mirror="https://de3.mirror.archlinuxarm.org/aarch64"
    sysroot_seeds="glibc linux-api-headers libdrm wayland wayland-protocols libxcb libx11 libxshmfence libxext libxfixes libxrandr zlib zstd expat gcc-libs"
    wl_version=1.26.0
    wl_sha256=64176eaa46e4969903e286f8e5ef8331affc17fdf03ac9b58381d2b23162b7a3
    die() { echo "$*" >&2; exit 1; }
    log() { echo "[sysroot] $*"; }
    if [ ! -f "$workdir/.ready-v1" ]; then
        build_sysroot
        build_host_scanner
        touch "$workdir/.ready-v1"
    fi
fi
