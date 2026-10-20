#!/usr/bin/env python3
"""Build the memory-reporting layer for host, FEX guest and experimental Bionic loaders."""
import argparse, pathlib, shutil, subprocess, tempfile, os
p=argparse.ArgumentParser();p.add_argument('--test',action='store_true');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[2]; here=pathlib.Path(__file__).parent
ndk=pathlib.Path(os.environ.get('ANDROID_NDK_HOME','/opt/homebrew/share/android-commandlinetools/ndk/29.0.14206865'))
toolchain=ndk/'toolchains/llvm/prebuilt/darwin-x86_64'; assets=root/'app/src/main/assets/linux'
with tempfile.TemporaryDirectory(prefix='skynet-gpu-memory-') as tmp:
    inc=pathlib.Path(tmp)
    for name in ['vulkan','vk_video']:shutil.copytree(toolchain/'sysroot/usr/include'/name,inc/name)
    common=['-shared','-fPIC','-O2','-Wall','-Wextra','-Werror','-fvisibility=hidden','-Wl,-Bsymbolic','-I',str(inc),str(here/'gpu_memory.c'),'-lpthread']
    for abi,target in [('aarch64','aarch64-linux-gnu.2.35'),('x86_64','x86_64-linux-gnu.2.35'),('i686','x86-linux-gnu.2.35')]:
        subprocess.run(['zig','cc','-target',target,*common,'-o',str(assets/f'libVkLayer_skynet_gpu_memory_{abi}.so')],check=True)
    subprocess.run([str(toolchain/'bin/aarch64-linux-android28-clang'),*[x for x in common if x!='-lpthread'],'-o',str(assets/'libVkLayer_skynet_gpu_memory_bionic.so')],check=True)
    if a.test:
        test=inc/'gpu-memory-test'
        subprocess.run(['clang','-std=c11','-Wall','-Wextra','-Werror','-I',str(inc),str(here/'layer_test.c'),'-lpthread','-o',str(test)],check=True)
        subprocess.run([str(test)],check=True)
        test_assets=root/'app/src/androidTest/assets';test_assets.mkdir(parents=True,exist_ok=True)
        for abi,target in [('aarch64','aarch64-linux-gnu.2.35'),('x86_64','x86_64-linux-gnu.2.35')]:
            subprocess.run(['zig','cc','-target',target,'-O2','-I',str(inc),str(here/'smoke.c'),'-ldl','-o',str(test_assets/f'gpu_memory_smoke_{abi}')],check=True)
        subprocess.run([str(toolchain/'bin/aarch64-linux-android28-clang'),'-O2','-I',str(inc),str(here/'smoke.c'),'-ldl','-o',str(test_assets/'gpu_memory_smoke_bionic')],check=True)
