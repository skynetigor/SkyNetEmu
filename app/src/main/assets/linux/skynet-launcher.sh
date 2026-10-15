#!/bin/bash
# Stands in for Valve's fex-compat-tool in a Winlator skyNET Linux container that runs x86-64 programs with Box64.
#
# The Steam client for arm64 starts an x86 game through that tool: FEX in a Steam Linux Runtime container (pressure-vessel),
# which needs user namespaces that proot does not give. Steam calls it as
#   fex-compat-tool run -- <runtime>/_v2-entry-point --verb=waitforexitandrun -- <the game's command>
# This script runs the game's command itself. A native program runs as it is; an x86-64 one is handed to Box64 by
# libskyshim's exec functions (SKY_X86_EMULATOR), so it needs nothing here. A 32-bit x86 program cannot run this way.
mkdir -p /storage/emulated/0/Winlator/logs 2>/dev/null
log=/storage/emulated/0/Winlator/logs/steam-game-launch.log
args=("$@")
# drop "run" / "waitforexitandrun" and the "--" after it
case "${args[0]}" in run|waitforexitandrun) args=("${args[@]:1}");; esac
[ "${args[0]}" = "--" ] && args=("${args[@]:1}")
# what follows the runtime's entry point and its own "--" is the game's command
cmd=("${args[@]}")
for i in "${!args[@]}"; do
    if [ "${args[$i]}" = "--" ]; then cmd=("${args[@]:$((i + 1))}"); break; fi
done
# An old game (built against Steam's scout runtime) comes wrapped in that runtime's entry point, a container that proot cannot
# make. The game runs straight instead, with its own libraries on the path (an old game ships the ones the runtime would have
# given it, libssl.so.1.0.0 among them), and the runtime's libraries that the root filesystem lacks. The runtime's other
# libraries stay off the path: they are as old as the game, and a modern Mesa in the root filesystem cannot load next to them
# (the runtime's libgcc_s lacks the symbol versions Mesa needs).
while [ "${#cmd[@]}" -gt 0 ]; do
    case "${cmd[0]}" in
        */SteamLinuxRuntime/*entry-point*|*/SteamLinuxRuntime/run-in-scout*)
            scout="$(dirname "${cmd[0]}")/steam-runtime"
            cmd=("${cmd[@]:1}")
            [ "${cmd[0]}" = "--" ] && cmd=("${cmd[@]:1}")
            ;;
        *) break ;;
    esac
done
# The emulator of this game: the shortcut that started it left the variables (LinuxSession.writeSteamEmulatorEnv); the Steam client
# may be running with the ones of another game's session.
[ -n "$SteamAppId" ] && [ -f "$HOME/.skynet/steam-emulator/$SteamAppId.env" ] && . "$HOME/.skynet/steam-emulator/$SteamAppId.env"
# The Steam runtime wraps every x86 game, 32-bit or 64-bit: what the game needs depends on the class of its executables
# (game32 = it has 32-bit ones and no 64-bit one).
game32=""
if [ -n "$scout" ]; then
    game_dir="$(dirname "${cmd[0]}")"
    has32=""; has64=""
    for f in "$game_dir"/bin/*; do
        [ -f "$f" ] && [ -x "$f" ] || continue
        case "$f" in *.so|*.so.*) continue ;; esac
        case "$(od -An -tx1 -j0 -N5 "$f" 2>/dev/null | tr -d ' \n')" in
            7f454c4601) has32=1 ;;
            7f454c4602) has64=1 ;;
        esac
    done
    [ -n "$has32" ] && [ -z "$has64" ] && game32=1
fi
if [ "$game32" = 1 ]; then
    libs=""
    for d in "$game_dir/lib/i686" "$game_dir/bin"; do
        [ -d "$d" ] && libs="${libs:+$libs:}$d"
    done
    # The runtime's 32-bit libraries that the root filesystem does not have (SDL2_image, for one), linked into one folder.
    extra="$HOME/.skynet/lib32"
    rm -rf "$extra"; mkdir -p "$extra"
    for d in "$scout/lib/i386-linux-gnu" "$scout/usr/lib/i386-linux-gnu"; do
        for f in "$d"/*.so*; do
            [ -e "$f" ] || continue
            name="$(basename "$f")"
            [ -e "$FEX_ROOTFS/usr/lib/i386-linux-gnu/$name" ] || [ -e "$extra/$name" ] || ln -s "$f" "$extra/$name"
        done
    done
    libs="${libs:+$libs:}$extra"
    export LD_LIBRARY_PATH="$libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi
# A 32-bit game finds Steam's client library at ~/.steam/sdk32 (the session links sdk64 for 64-bit ones).
steam_root="${XDG_DATA_HOME:-$HOME/.local/share}/Steam"
if [ -f "$steam_root/linux32/steamclient.so" ]; then
    mkdir -p "$HOME/.steam"
    ln -sfn "$steam_root/linux32" "$HOME/.steam/sdk32"
fi
# A 32-bit game on the OpenGL thunk: its glXCreateContext asks for a compatibility profile, which the container's usual
# MESA_GL_VERSION_OVERRIDE (3.3, a core one) refuses as GLXBadFBConfig. Set before the launch options, which may override it.
if [ "$game32" = 1 ] && [ "$SKY_FEX_GL32" = 1 ]; then
    export MESA_GL_VERSION_OVERRIDE=4.6COMPAT MESA_GLSL_VERSION_OVERRIDE=460
    # Feral's ports choose their workarounds and defaults by GL_VENDOR / GL_RENDERER: as "Mesa / zink / Adreno" Tomb Raider (2013)
    # ran at its lowest settings and drew black patches on lit surfaces; as an NVIDIA card it draws as intended. Mesa's driconf can
    # change the strings (force_gl_vendor, force_gl_renderer); DRIRC_CONFIGDIR makes it read ours for this process only.
    case "$SKY_FEX_GL_VENDOR" in
        nvidia) sky_vendor="NVIDIA Corporation"; sky_renderer="NVIDIA GeForce GTX 1060 6GB/PCIe/SSE2" ;;
        amd)    sky_vendor="AMD"; sky_renderer="AMD Radeon RX 580 Series (radeonsi, polaris10, LLVM 19.1.1, DRM 3.59, 6.8.0)" ;;
        *)      sky_vendor="" ;;
    esac
    if [ -n "$sky_vendor" ]; then
        mkdir -p "$HOME/.skynet/drirc"
        cat > "$HOME/.skynet/drirc/99-skynet-vendor.conf" <<EOF
<driconf>
  <device>
    <application name="skynet">
      <option name="force_gl_vendor" value="$sky_vendor"/>
      <option name="force_gl_renderer" value="$sky_renderer"/>
    </application>
  </device>
</driconf>
EOF
        export DRIRC_CONFIGDIR="$HOME/.skynet/drirc"
    fi
fi
# The shortcut's launch options for this game (LinuxSession.writeSteamLaunchOptions): variables, a wrapper before the command, arguments after.
sky_wrap=(); sky_args=()
[ -n "$SteamAppId" ] && [ -f "$HOME/.skynet/steam-emulator/$SteamAppId.launch" ] && . "$HOME/.skynet/steam-emulator/$SteamAppId.launch"
[ "${#sky_wrap[@]}" -gt 0 ] && cmd=("${sky_wrap[@]}" "${cmd[@]}")
[ "${#sky_args[@]}" -gt 0 ] && cmd=("${cmd[@]}" "${sky_args[@]}")
{
    echo "== $(date) skynet-launcher"
    echo "args: $*"
    echo "run: ${cmd[*]}"
} >> "$log" 2>&1
# the Steam runtime's overlay libraries are not there; Steam's own preload would only produce warnings
unset LD_PRELOAD
# the client draws in software; the game gets the container's OpenGL driver (Zink on Turnip) back
# (A 32-bit game gets its own thunk selection (SKY_FEX_THUNKCONFIG32: none of the 64-bit guests' thunks). With the OpenGL one on
# (SKY_FEX_GL32) its OpenGL is the runtime's own Mesa, so it gets Zink too. Without it the game keeps the software OpenGL of the
# root filesystem.)
if [ "$game32" = 1 ] && [ -n "$SKY_FEX_THUNKCONFIG32" ]; then
    export FEX_THUNKCONFIG="$SKY_FEX_THUNKCONFIG32"
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
# Not exec: the game's exit status goes to the log (a signal shows as 128+N), and Steam's stop request still reaches it.
"${cmd[@]}" >> "$log" 2>&1 &
game=$!
trap 'kill -TERM "$game" 2>/dev/null' TERM INT
wait "$game"; status=$?
[ "$status" -gt 128 ] && kill -0 "$game" 2>/dev/null && { wait "$game"; status=$?; }
echo "exit: $status ($(date +%T))" >> "$log"
exit "$status"
