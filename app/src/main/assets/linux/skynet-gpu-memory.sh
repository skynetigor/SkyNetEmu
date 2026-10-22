# Sourced by the Steam game launcher after its per-game environment and launch options.
# Keep our layer isolated even when Steam itself inherited another game's settings.
sky_gpu_memory_apply() {
    local key value part clean
    for key in VK_INSTANCE_LAYERS VK_ADD_LAYER_PATH VK_LAYER_PATH; do
        value="${!key:-}"; clean=""
        local -a parts=()
        IFS=: read -r -a parts <<< "$value"
        for part in "${parts[@]}"; do
            [ -z "$part" ] && continue
            [ "$key" = VK_INSTANCE_LAYERS ] && [ "$part" = VK_LAYER_SKYNET_gpu_memory ] && continue
            if [ "$key" != VK_INSTANCE_LAYERS ] && [ -n "$SKY_GPU_MEMORY_LAYER_ROOT" ]; then
                case "$part" in "$SKY_GPU_MEMORY_LAYER_ROOT"/*) continue ;; esac
            fi
            clean="${clean:+$clean:}$part"
        done
        if [ -n "$clean" ]; then export "$key=$clean"; else unset "$key"; fi
    done
    case "${SKY_GPU_MEMORY_MB:-0}" in
        512|1024|2048|4096|8192|12288|16384) ;;
        *) export SKY_GPU_MEMORY_MB=0; return ;;
    esac
    local abi="${SKY_GPU_MEMORY_VARIANT:-aarch64}"
    # 32-bit OpenGL thunks use host Zink; an unthunked 32-bit loader needs its own ELF class.
    if [ "${game32:-}" = 1 ]; then
        if [ "${SKY_FEX_GL32:-}" = 1 ]; then abi=aarch64; else abi=i686; fi
    fi
    local manifest="$SKY_GPU_MEMORY_LAYER_ROOT/$abi/VkLayer_skynet_gpu_memory.json"
    if [ ! -f "$manifest" ]; then echo "GPU memory budget: missing layer $manifest" >&2; return 1; fi
    export VK_ADD_LAYER_PATH="${VK_ADD_LAYER_PATH:+$VK_ADD_LAYER_PATH:}$SKY_GPU_MEMORY_LAYER_ROOT/$abi"
    export VK_INSTANCE_LAYERS="${VK_INSTANCE_LAYERS:+$VK_INSTANCE_LAYERS:}VK_LAYER_SKYNET_gpu_memory"
}
