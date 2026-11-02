#!/bin/bash
# Stands in for taskset in the Steam client's session (the app puts it first in PATH, see LinuxSession.steamCommand).
#
# The client starts its web helper (Chromium) through steamwebhelper.sh as "taskset <mask> steamwebhelper <flags>", with
# --disable-gpu and --disable-gpu-compositing on the list: the whole interface is then drawn on the CPU. Here the helper is
# started without those two and with ANGLE on Vulkan, so its GPU process draws on the phone's GPU through Turnip. OpenGL is
# no way for it: the X server has no GLX the way ANGLE needs it (a pbuffer, GLX_EXT_create_context_es_profile).
# The GPU process also has to log to stderr (--enable-logging=stderr): Valve's default sends Chromium's log to a file
# (--log-file=.../cef_log.txt), and with that the GPU process aborts within 0.1 s with "Crashing due to FD ownership violation"
# (exit code 5) on every start. Three crashes make Chromium give up and the whole interface falls back to the CPU: "Disabling GPU
# acceleration: Disabled/CrashCount", very laggy. Measured on the Fold8 (Adreno 840): file logging 8 GPU-process launches and the
# fallback in 4 of 4 starts (the log file's location makes no difference), stderr logging 1 launch and no fallback in 6 of 6.
# The helper's own log then goes to steamwebhelper.log, which the app already exports.
# SKY_STEAM_GPU=0 (the container's "linuxSteamGpu" extra) leaves the helper as the client asked for it.
if [[ "$2" == */steamwebhelper ]]; then
  printf '== SKY_STEAM stage=webhelper_start epoch_s=%s gpu=%s\n' "$(date -u +%s)" "${SKY_STEAM_GPU:-1}"
fi
if [ "$SKY_STEAM_GPU" != 0 ] && [[ "$2" == */steamwebhelper ]]; then
  args=()
  for a in "${@:3}"; do
    case "$a" in
      --disable-gpu|--disable-gpu-compositing) ;;
      *) args+=("$a") ;;
    esac
  done
  exec /usr/bin/taskset "$1" "$2" "${args[@]}" \
    --use-gl=angle --use-angle=vulkan --enable-features=Vulkan,VulkanFromANGLE,DefaultANGLEVulkan \
    --ignore-gpu-blocklist --disable-gpu-memory-buffer-compositor-resources --disable-zero-copy --enable-logging=stderr
fi
exec /usr/bin/taskset "$@"
