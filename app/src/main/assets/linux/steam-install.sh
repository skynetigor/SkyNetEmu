#!/bin/bash
# Bootstrap only the native updater; Valve installs and verifies the full client.
# Runtime installer provenance: The412Banner/Bannerlator, tools/linuxfs/overlay/usr/local/bin/bannerlator-steam-install.
set -euo pipefail

stage() {
  printf '== SKY_STEAM stage=%s epoch_s=%s\n' "$1" "$(date -u +%s)"
}
fail() { echo "SkyNetEmu Steam installer: $*" >&2; exit 1; }

root="${XDG_DATA_HOME:-$HOME/.local/share}/Steam"
stamp="$root/package/skynet-bootstrap-v1"
installed() {
  [ -x "$root/steamrtarm64/steam" ] &&
    { [ -f "$stamp" ] || [ -f "$root/package/bannerlator-installed" ] ||
      [ -f "$root/package/steam_client_publicbeta_linuxarm64.installed" ]; }
}
repair_native_helpers() {
  # Older ZIP bootstraps lost executable modes; do not chmod libraries or arbitrary client data.
  local name
  for name in linuxarm64/steam-launch-wrapper steamrtarm64/reaper steamrtarm64/steamsysinfo \
      steamrtarm64/gldriverquery steamrtarm64/vulkandriverquery steamrtarm64/steamwebhelper \
      steamrtarm64/steamwebhelper.sh; do
    if [ -f "$root/$name" ] && [ ! -x "$root/$name" ]; then
      echo "SkyNetEmu Steam installer: restoring executable mode for $name" >&2
      chmod u+x "$root/$name"
    fi
  done
}
if installed; then repair_native_helpers; stage bootstrap_cached; exit 0; fi

cache="$HOME/.cache/skynet/steam-bootstrap"
mkdir -p "$cache"
exec 9>"$cache/install.lock"
flock -w 60 9 || fail "Another installer is still running; retry after it finishes."
if installed; then repair_native_helpers; stage bootstrap_cached; exit 0; fi
stage bootstrap_manifest
manifest="$cache/manifest"
curl -fLsS --connect-timeout 15 --max-time 120 --retry 2 \
  https://client-update.fastly.steamstatic.com/steam_client_publicbeta_linuxarm64 -o "$manifest.tmp"
mv -f "$manifest.tmp" "$manifest"
read -r file sum < <(awk -F'"' '
  /^\t"/ && NF >= 3 && $4 == "" { block = $2 }
  block == "bins_linuxarm64_linuxarm64" && $2 == "file" { file = $4 }
  block == "bins_linuxarm64_linuxarm64" && $2 == "sha2" { sha = $4 }
  END { print file, sha }
' "$manifest")
[[ "$file" =~ ^bins_linuxarm64_linuxarm64\.zip\.[a-f0-9]+$ ]] ||
  fail "Valve manifest has no supported ARM64 updater package."
[[ "$sum" =~ ^[a-f0-9]{64}$ ]] || fail "Valve manifest has no valid SHA-256."

archive="$cache/$sum.zip"
partial="$archive.part"
# One content-addressed package and its partial download, each limited to 256 MiB.
for old in "$cache/"*.zip "$cache/"*.part; do
  [ -f "$old" ] || continue
  if [ "$old" != "$archive" ] && [ "$old" != "$partial" ]; then rm -- "$old"; fi
done
if [ -f "$archive" ] && ! echo "$sum  $archive" | sha256sum -c --status; then
  echo "SkyNetEmu Steam installer: discarding corrupt cached package." >&2
  rm -- "$archive"
fi
if [ ! -f "$archive" ]; then
  stage bootstrap_download
  code=0
  curl -fLsS --connect-timeout 15 --max-time 600 --retry 2 --max-filesize 268435456 \
    -C - "https://client-update.fastly.steamstatic.com/$file" -o "$partial" || code=$?
  if [ "$code" = 33 ] || [ "$code" = 36 ]; then
    echo "SkyNetEmu Steam installer: server cannot resume; restarting package download." >&2
    rm -f -- "$partial"
    curl -fLsS --connect-timeout 15 --max-time 600 --retry 2 --max-filesize 268435456 \
      "https://client-update.fastly.steamstatic.com/$file" -o "$partial"
  elif [ "$code" != 0 ]; then
    fail "Download failed (curl $code); the partial package is retained for retry."
  fi
  stage bootstrap_verify
  if ! echo "$sum  $partial" | sha256sum -c --status; then
    rm -- "$partial"
    fail "Package SHA-256 mismatch; retry to download a fresh copy."
  fi
  mv -f "$partial" "$archive"
fi

stage bootstrap_extract
mkdir -p "$root/steamrtarm64" "$root/package" "$HOME/.steam"
updater="$root/steamrtarm64/steam"
trap 'rm -f -- "$updater.tmp" "$stamp.tmp"' EXIT
# Avoid extracting UI/webhelper assets that Valve's updater immediately installs again.
unzip -p "$archive" steamrtarm64/steam > "$updater.tmp" ||
  fail "Cannot extract the native ARM64 updater."
[ -s "$updater.tmp" ] || fail "The native ARM64 updater is empty."
chmod 755 "$updater.tmp"
mv -f "$updater.tmp" "$updater"
for link in root steam; do ln -sfn "$root" "$HOME/.steam/$link"; done
for link in bin64 binarm64; do ln -sfn "$root/steamrtarm64" "$HOME/.steam/$link"; done
ln -sfn "$root/linuxarm64" "$HOME/.steam/sdkarm64"
ln -sfn "$root/linux64" "$HOME/.steam/sdk64"
ln -sfn "$root/linux32" "$HOME/.steam/sdk32"
ln -sfn "$root/steamrtarm64" "$root/steamrtarm32"
# This is only our bootstrap marker, not a fabricated Steam installed manifest.
printf '%s\n' "$sum" > "$stamp.tmp"
mv -f "$stamp.tmp" "$stamp"
stage bootstrap_ready
