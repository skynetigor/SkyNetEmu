# Sourced (not run) by skynet-launcher.sh (a game Steam starts) and by the wrapper that starts a program from a shortcut: what a 32-bit
# game on FEX's OpenGL thunk (SKY_FEX_GL32=1) needs in its environment to draw on the GPU.
#
# * A game that makes its context with glXCreateContext asks for a compatibility profile; the container's usual
#   MESA_GL_VERSION_OVERRIDE (3.3) is a core one, which fails as GLXBadFBConfig.
# * Feral's ports choose their workarounds and defaults by GL_VENDOR / GL_RENDERER: as "Mesa / zink / Adreno" Tomb Raider (2013) ran
#   at its lowest settings and drew black patches on lit surfaces; as an NVIDIA card it draws as intended. Mesa's driconf can change
#   the strings (force_gl_vendor, force_gl_renderer); DRIRC_CONFIGDIR makes it read ours for this process only.
sky_gl32_mesa_env() {
    [ "$SKY_FEX_GL32" = 1 ] || return 0
    export MESA_GL_VERSION_OVERRIDE=4.6COMPAT MESA_GLSL_VERSION_OVERRIDE=460
    local vendor renderer
    case "$SKY_FEX_GL_VENDOR" in
        nvidia) vendor="NVIDIA Corporation"; renderer="NVIDIA GeForce GTX 1060 6GB/PCIe/SSE2" ;;
        amd)    vendor="AMD"; renderer="AMD Radeon RX 580 Series (radeonsi, polaris10, LLVM 19.1.1, DRM 3.59, 6.8.0)" ;;
        *)      return 0 ;;
    esac
    mkdir -p "$HOME/.skynet/drirc"
    cat > "$HOME/.skynet/drirc/99-skynet-vendor.conf" <<EOF
<driconf>
  <device>
    <application name="skynet">
      <option name="force_gl_vendor" value="$vendor"/>
      <option name="force_gl_renderer" value="$renderer"/>
    </application>
  </device>
</driconf>
EOF
    export DRIRC_CONFIGDIR="$HOME/.skynet/drirc"
}
