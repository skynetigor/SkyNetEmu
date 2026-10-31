#!/bin/bash
# Native Linux Steam compatibility tool for the existing proot runtime.
# x86-64 execution uses libskyshim and the selected Box64/FEX; i386 uses FEX with 32-bit thunks.
log_dir="${SKY_STEAM_LOG_DIR:-/storage/emulated/0/Winlator/logs}"
mkdir -p "$log_dir" || exit 1
log="$log_dir/steam-game-launch.log"
exec >> "$log" 2>&1
unset LD_PRELOAD
result_dir="$HOME/.skynet/steam-results"
mkdir -p "$result_dir" || exit 1
app_id="${SteamAppId:-${STEAM_COMPAT_APP_ID:-}}"
cancelled=0
[[ "$app_id" =~ ^[0-9]+$ ]] || app_id=unknown
report_exit() {
    status=$?
    trap - EXIT
    printf 'exit: %s (%s) appid=%s\n' "$status" "$(date +%T)" "$app_id"
    if [ "$status" != 0 ] && [ "$cancelled" != 1 ]; then
        printf '%s\n' "$status" > "$result_dir/$app_id.tmp" &&
            mv -f "$result_dir/$app_id.tmp" "$result_dir/$app_id.error"
    fi
}
trap report_exit EXIT
fail() { echo "SkyNetEmu: $*" >&2; exit 126; }
printf '== %s skynet-launcher appid=%s\n' "$(date)" "$app_id"
args=("$@")
# drop "run" / "waitforexitandrun" and the "--" after it
case "${args[0]}" in run|waitforexitandrun) args=("${args[@]:1}");; esac
[ "${args[0]}" = "--" ] && args=("${args[@]:1}")
cmd=("${args[@]}")
# An old game (built against Steam's scout runtime) comes wrapped in that runtime's entry point, a container that proot cannot
# make. The game runs straight instead, with its own libraries on the path (an old game ships the ones the runtime would have
# given it, libssl.so.1.0.0 among them), and the runtime's libraries that the root filesystem lacks. The runtime's other
# libraries stay off the path: they are as old as the game, and a modern Mesa in the root filesystem cannot load next to them
# (the runtime's libgcc_s lacks the symbol versions Mesa needs).
while [ "${#cmd[@]}" -gt 0 ]; do
    case "${cmd[0]}" in
        */SteamLinuxRuntime*/_v2-entry-point|*/SteamLinuxRuntime*/scout-on-soldier-entry-point-v2|*/SteamLinuxRuntime/run-in-scout*)
            [ -d "$(dirname "${cmd[0]}")/steam-runtime" ] &&
                scout="$(dirname "${cmd[0]}")/steam-runtime"
            cmd=("${cmd[@]:1}")
            while [ "${#cmd[@]}" -gt 0 ] && [ "${cmd[0]}" != "--" ]; do
                case "${cmd[0]}" in
                    --verb=*|--verb|waitforexitandrun|run) cmd=("${cmd[@]:1}") ;;
                    *) fail "Unrecognized Steam runtime argument: ${cmd[0]}" ;;
                esac
            done
            [ "${cmd[0]}" = "--" ] || fail "Steam runtime command has no separator."
            cmd=("${cmd[@]:1}")
            ;;
        *) break ;;
    esac
done
[ "${#cmd[@]}" -gt 0 ] || fail "Steam supplied no game command."
# The emulator of this game: the shortcut that started it left the variables (LinuxSession.writeSteamEmulatorEnv); the Steam client
# may be running with the ones of another game's session.
export SKY_GPU_MEMORY_MB=0
[ -f "$HOME/.skynet/steam-emulator/$app_id.env" ] && . "$HOME/.skynet/steam-emulator/$app_id.env"
[ -z "${SKY_GAME_SETUP_ERROR:-}" ] || fail "$SKY_GAME_SETUP_ERROR"
# The Steam runtime wraps every x86 game, 32-bit or 64-bit: what the game needs depends on the class of its executables
# (game32 = it has 32-bit ones and no 64-bit one).
game32=""
game_dir="$(dirname "${cmd[0]}")"
if [ -f "${cmd[0]}" ]; then
    has32=""; has64=""
    for f in "${cmd[0]}" "$game_dir"/bin/*; do
        [ -f "$f" ] && [ -x "$f" ] || continue
        case "$f" in *.so|*.so.*) continue ;; esac
        case "$(od -An -tx1 -j0 -N5 "$f" 2>/dev/null | tr -d ' \n')" in
            7f454c4601) has32=1 ;;
            7f454c4602) has64=1 ;;
        esac
    done
    [ -n "$has32" ] && [ -z "$has64" ] && game32=1
fi
if [ "$game32" = 1 ] && [ -z "$SKY_X86_32_EMULATOR" ]; then
    fail "This 32-bit Linux game needs FEX and its root filesystem. Install them in Settings > Steam > 32-bit games."
fi
if [ "$game32" = 1 ]; then
    libs=""
    for d in "$game_dir/lib/i686" "$game_dir/bin"; do
        [ -d "$d" ] && libs="${libs:+$libs:}$d"
    done
    # The runtime's 32-bit libraries that the root filesystem does not have (SDL2_image, for one), linked into one folder.
    extra="$HOME/.skynet/lib32/$app_id"
    mkdir -p "$extra" || fail "Cannot prepare 32-bit runtime library directory."
    scout="${scout:-${XDG_DATA_HOME:-$HOME/.local/share}/Steam/steamapps/common/SteamLinuxRuntime/steam-runtime}"
    for d in "$scout/lib/i386-linux-gnu" "$scout/usr/lib/i386-linux-gnu"; do
        for f in "$d"/*.so*; do
            [ -e "$f" ] || continue
            name="${f##*/}"
            [ -e "$FEX_ROOTFS/usr/lib/i386-linux-gnu/$name" ] || [ -e "$extra/$name" ] || ln -s "$f" "$extra/$name"
        done
    done
    libs="${libs:+$libs:}$extra"
    export LD_LIBRARY_PATH="$libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi
if [ "$app_id" = 391220 ] && [ "$game32" != 1 ]; then
    # Rise's old CEF needs GConf, removed from modern root filesystems. Do not expose scout's libc/libgcc to Mesa.
    extra="$HOME/.skynet/lib64/$app_id"
    scout="${scout:-${XDG_DATA_HOME:-$HOME/.local/share}/Steam/steamapps/common/SteamLinuxRuntime/steam-runtime}"
    mkdir -p "$extra" || fail "Cannot prepare Rise's legacy library directory."
    for name in libgconf-2.so.4 libdbus-glib-1.so.2; do
        source="$scout/usr/lib/x86_64-linux-gnu/$name"
        [ -f "$source" ] || fail "Rise needs $name from the installed Steam Linux Runtime (scout)."
        ln -sfn "$source" "$extra/$name" || fail "Cannot link Rise's $name."
    done
    export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:+$LD_LIBRARY_PATH:}$extra"
fi
# A 32-bit game finds Steam's client library at ~/.steam/sdk32 (the session links sdk64 for 64-bit ones).
steam_root="${XDG_DATA_HOME:-$HOME/.local/share}/Steam"
if [ -f "$steam_root/linux32/steamclient.so" ]; then
    mkdir -p "$HOME/.steam"
    ln -sfn "$steam_root/linux32" "$HOME/.steam/sdk32"
fi
# A 32-bit game on the OpenGL thunk: its Mesa version and vendor (skynet-gl32.sh). Set before the launch options, which may override.
if [ "$game32" = 1 ] && [ -f "$SKY_FEX_GL32_SCRIPT" ]; then
    . "$SKY_FEX_GL32_SCRIPT"
    sky_gl32_mesa_env
fi
# the Steam runtime's overlay libraries are not there; Steam's own preload would only produce warnings
# The frame probe is a native aarch64 layer. A 32-bit game's loader cannot load it.
if [ "$game32" = 1 ] && [ -n "$SKY_FRAME_PROBE_DIR" ]; then
    sky_probe_layers=""
    IFS=: read -r -a sky_probe_layer_list <<< "${VK_INSTANCE_LAYERS:-}"
    for sky_probe_layer in "${sky_probe_layer_list[@]}"; do
        [ "$sky_probe_layer" = VK_LAYER_SKYNET_frame_probe ] && continue
        [ -n "$sky_probe_layer" ] && sky_probe_layers="${sky_probe_layers:+$sky_probe_layers:}$sky_probe_layer"
    done
    if [ -n "$sky_probe_layers" ]; then export VK_INSTANCE_LAYERS="$sky_probe_layers"; else unset VK_INSTANCE_LAYERS; fi
    unset SKY_FRAME_PROBE_DIR sky_probe_layer_list sky_probe_layer sky_probe_layers
fi

# the client draws in software; the game gets the container's OpenGL driver (Zink on Turnip) back
# (A 32-bit game gets its own thunk selection (SKY_FEX_THUNKCONFIG32: none of the 64-bit guests' thunks). With the OpenGL one on
# (SKY_FEX_GL32) its OpenGL is the runtime's own Mesa, so it gets Zink too. Without it the game keeps the software OpenGL of the
# root filesystem.)
if [ "$game32" = 1 ] && [ -n "$SKY_FEX_THUNKCONFIG32" ]; then
    export FEX_THUNKCONFIG="$SKY_FEX_THUNKCONFIG32"
elif [ "$game32" != 1 ] && [ -n "$SKY_FEX_THUNKCONFIG64" ]; then
    export FEX_THUNKCONFIG="$SKY_FEX_THUNKCONFIG64"
fi
if [ "$SKY_GAME_GL" = zink ] && { [ "$game32" != 1 ] || [ "$SKY_FEX_GL32" = 1 ]; }; then
    export MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink
    unset LIBGL_ALWAYS_SOFTWARE
fi
# A game that ships mimalloc (BeamNG) has it take over malloc and free for the whole process, which Box64 cannot follow: a block
# mimalloc allocated reaches glibc's free and the game dies at once ("free(): invalid size"). The app preloads a stand-in for
# the game's own copy when it starts the program itself (LinuxSession.guestEnv); this does the same for a game the client starts.
if [ -n "$SKY_MIMALLOC_STUB" ] && [ -f "$SKY_MIMALLOC_STUB" ]; then
    for word in "${cmd[@]}"; do
        case "$word" in
            /*) if [ -f "$(dirname "$word")/libmimalloc.so.3" ]; then
                    case ":$BOX64_LD_PRELOAD:" in
                        *":$SKY_MIMALLOC_STUB:"*) ;;
                        *) export BOX64_LD_PRELOAD="${BOX64_LD_PRELOAD:+$BOX64_LD_PRELOAD:}$SKY_MIMALLOC_STUB" ;;
                    esac
                    break
                fi ;;
        esac
    done
fi
# Apply the game's profile and shortcut options after all runtime defaults. Explicit environment variables from the
# shortcut editor win over launch-option assignments too (LinuxSession.writeSteamLaunchOptions merges them).
sky_wrap=(); sky_args=()
[ -f "$HOME/.skynet/steam-emulator/$app_id.launch" ] && . "$HOME/.skynet/steam-emulator/$app_id.launch"
if [ -f "${SKY_GPU_MEMORY_SCRIPT:-}" ]; then
    . "$SKY_GPU_MEMORY_SCRIPT"
    sky_gpu_memory_apply || exit 1
fi
# A Feral game starts at the full-screen size saved in its preferences, not at the X screen's (the shortcut's resolution), and
# the renderer then has to scale a frame of the wrong size. Its saved size follows the X screen instead.
sky_feral_resolution() {
    local settings="$game_dir/config/game-settings.sh" dir prefs size w h
    [ -f "$settings" ] || return 0
    dir="$(sed -n 's/^FERAL_PREFERENCES_DIR="\([^"]*\)".*/\1/p' "$settings" | head -n 1)"
    prefs="${XDG_DATA_HOME:-$HOME/.local/share}/$dir/preferences"
    [ -n "$dir" ] && [ -f "$prefs" ] || return 0
    size="$(xrandr --current 2>/dev/null | sed -n 's/.* current \([0-9]*\) x \([0-9]*\),.*/\1 \2/p' | head -n 1)"
    read -r w h <<< "$size"
    [[ "$w" =~ ^[0-9]+$ && "$h" =~ ^[0-9]+$ ]] || { echo "SkyNetEmu: X screen size unknown; Feral resolution unchanged"; return 0; }
    sed -i -E \
        -e "s#(<value name=\"(FullscreenWidth|UserWindowWidth|WindowWidth|ScreenW)\" type=\"integer\">)[0-9]+#\\1$w#" \
        -e "s#(<value name=\"(FullscreenHeight|UserWindowHeight|WindowHeight|ScreenH)\" type=\"integer\">)[0-9]+#\\1$h#" \
        "$prefs" && echo "SkyNetEmu: Feral resolution ${w}x${h}"
}
sky_feral_resolution
if [ "$app_id" = 391220 ] && [ -n "${SKY_DIRECT_FEX_SOCKET:-}" ]; then
    [ "$game32" != 1 ] || fail "Direct FEX supports only Rise's native 64-bit port."
    [ "${#sky_wrap[@]}" = 0 ] || fail "Direct FEX does not support command wrappers."
    [ -x "${SKY_DIRECT_FEX_CLIENT:-}" ] || fail "Direct FEX launch client is unavailable."
    [ "${#sky_args[@]}" -gt 0 ] && cmd=("${cmd[@]}" "${sky_args[@]}")
    "$SKY_DIRECT_FEX_CLIENT" "${cmd[@]}" &
    game=$!
    trap 'cancelled=1; kill -TERM "$game" 2>/dev/null' TERM INT
    wait "$game"
    exit $?
fi
[ "${#sky_wrap[@]}" -gt 0 ] && cmd=("${sky_wrap[@]}" "${cmd[@]}")
[ "${#sky_args[@]}" -gt 0 ] && cmd=("${cmd[@]}" "${sky_args[@]}")
{
    echo "args: $*"
    echo "run: ${cmd[*]}"
} >> "$log" 2>&1
# Not exec: the game's exit status goes to the log (a signal shows as 128+N), and Steam's stop request still reaches it.
"${cmd[@]}" >> "$log" 2>&1 &
game=$!
trap 'cancelled=1; kill -TERM "$game" 2>/dev/null' TERM INT
wait "$game"; status=$?
[ "$status" -gt 128 ] && kill -0 "$game" 2>/dev/null && { wait "$game"; status=$?; }
exit "$status"
