#!/bin/bash
set -euo pipefail
root="${XDG_DATA_HOME:-$HOME/.local/share}/Steam"
tool="$root/compatibilitytools.d/skynet-linux"
mkdir -p "$tool"
[ -f "${SKY_LAUNCHER:-}" ] || { echo "SkyNetEmu game launcher is missing" >&2; exit 1; }
if ! cmp -s "$SKY_LAUNCHER" "$tool/launch"; then
    cp "$SKY_LAUNCHER" "$tool/launch.tmp"
    chmod 755 "$tool/launch.tmp"
    mv -f "$tool/launch.tmp" "$tool/launch"
fi
cat > "$tool/compatibilitytool.vdf" <<'EOF'
"compatibilitytools"
{
  "compat_tools"
  {
    "skynet-linux"
    {
      "install_path" "."
      "display_name" "SkyNetEmu Linux (Box64 / FEX)"
      "from_oslist" "linux"
      "to_oslist" "linux"
    }
  }
}
EOF
cat > "$tool/toolmanifest.vdf" <<'EOF'
"manifest"
{
  "version" "2"
  "commandline" "/launch %verb% --"
  "compatmanager_layer_name" "container-runtime"
  "use_tool_subprocess_reaper" "1"
}
EOF
# Undo only our previous replacement, retaining Valve's original binary and metadata.
legacy="$root/steamapps/common/FEX-Emu/fex-compat-tool"
if [ -f "$legacy.orig" ] && grep -q 'skynet-launcher' "$legacy"; then
    cp "$legacy.orig" "$legacy"
    chmod 755 "$legacy"
fi
