#!/usr/bin/env python3
"""Cross-build opt-in glibc Vulkan layer using Zig and the installed NDK Vulkan headers."""
import argparse, pathlib, shutil, subprocess, tempfile, os
p=argparse.ArgumentParser();p.add_argument("--test",action="store_true");p.add_argument('--headers',default=os.environ.get('VULKAN_HEADERS'));a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[2]
headers=pathlib.Path(a.headers) if a.headers else pathlib.Path('/opt/homebrew/share/android-commandlinetools/ndk/29.0.14206865/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/include')
with tempfile.TemporaryDirectory(prefix='skynet-vk-headers-') as tmp:
    include=pathlib.Path(tmp)
    for name in ['vulkan','vk_video']:shutil.copytree(headers/name,include/name)
    subprocess.run(['zig','cc','-target','aarch64-linux-gnu.2.35','-shared','-fPIC','-O2','-Wall','-Wextra','-fvisibility=hidden','-Wl,-Bsymbolic','-I',str(include),str(root/'tools/frame-probe/frame_probe.c'),'-lpthread','-o',str(root/'app/src/main/assets/linux/libVkLayer_skynet_frame_probe.so')],check=True)

    if a.test:
        test=include/'layer_test'
        subprocess.run(['clang','-std=c11','-Wno-incompatible-function-pointer-types','-I',str(include),str(root/'tools/frame-probe/layer_test.c'),'-lpthread','-o',str(test)],check=True)
        report_dir=include/'reports';report_dir.mkdir()
        subprocess.run([str(test)],env={**os.environ,'SKY_FRAME_PROBE_DIR':str(report_dir)},check=True)
        import json
        reports=[json.loads(f.read_text()) for f in report_dir.glob('*.json')]
        assert len(reports)==1 and reports[0]['queued']==2 and reports[0]['failures']==1 and reports[0]['window']==42 and reports[0]['alive']==0
        print('PASS bounded atomic numeric report parses after destruction')

        assets=root/'app/src/androidTest/assets';assets.mkdir(parents=True,exist_ok=True)
        subprocess.run(['zig','cc','-target','aarch64-linux-gnu.2.35','-O2','-I',str(include),str(root/'tools/frame-probe/smoke.c'),'-ldl','-o',str(assets/'frame_probe_smoke')],check=True)
