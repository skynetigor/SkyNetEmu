#!/bin/bash
set -eu
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
export SKY_GPU_MEMORY_LAYER_ROOT="$root"
for abi in aarch64 i686 x86_64; do mkdir -p "$root/$abi"; touch "$root/$abi/VkLayer_skynet_gpu_memory.json"; done
. "$(dirname "$0")/../../app/src/main/assets/linux/skynet-gpu-memory.sh"
export VK_INSTANCE_LAYERS=VK_LAYER_LSFG:VK_LAYER_SKYNET_gpu_memory:VK_LAYER_SKYNET_frame_probe
export VK_ADD_LAYER_PATH="/other:$root/x86_64"
export VK_LAYER_PATH="/keep:$root/i686"
export SKY_GPU_MEMORY_MB=0
sky_gpu_memory_apply
[ "$VK_INSTANCE_LAYERS" = VK_LAYER_LSFG:VK_LAYER_SKYNET_frame_probe ]
[ "$VK_ADD_LAYER_PATH" = /other ] && [ "$VK_LAYER_PATH" = /keep ]
export SKY_GPU_MEMORY_MB=2048 SKY_GPU_MEMORY_VARIANT=x86_64
game32=1; SKY_FEX_GL32=1
sky_gpu_memory_apply
[ "$VK_ADD_LAYER_PATH" = "/other:$root/aarch64" ]
SKY_FEX_GL32=0
sky_gpu_memory_apply
[ "$VK_ADD_LAYER_PATH" = "/other:$root/i686" ]
game32=0
sky_gpu_memory_apply
[ "$VK_ADD_LAYER_PATH" = "/other:$root/x86_64" ]
export SKY_GPU_MEMORY_MB=invalid
sky_gpu_memory_apply
[ "$SKY_GPU_MEMORY_MB" = 0 ] && [ "$VK_ADD_LAYER_PATH" = /other ]
[ "$VK_INSTANCE_LAYERS" = VK_LAYER_LSFG:VK_LAYER_SKYNET_frame_probe ]
echo 'PASS Steam budget isolation, Default cleanup, ABI selection and other layer preservation'
