#!/usr/bin/env python3
"""Build a named, pinned glibc Turnip recipe. Run inside the companion Docker image."""
import argparse
import hashlib
import json
import os
import platform
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
RECIPES = json.loads((HERE / 'recipes.json').read_text())


def run(*args, cwd=None):
    print('+', *map(str, args), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, check=True)


def output(*args, cwd=None):
    return subprocess.check_output(list(map(str, args)), cwd=cwd, text=True)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def download(url, path, expected):
    if not path.exists() or sha(path) != expected:
        run('curl', '-fL', '--retry', '4', '--retry-delay', '2', url, '-o', path)
    if sha(path) != expected:
        raise RuntimeError('SHA256 mismatch: ' + str(path))


def build(args):
    recipe = RECIPES[args.recipe]
    work = args.work.resolve() / args.recipe
    work.mkdir(parents=True, exist_ok=True)
    cache = args.work.resolve() / 'sysroot-cache'
    run('bash', HERE / 'sysroot.sh', cache)
    source = work / 'mesa'
    source_recipe = {k: v for k, v in recipe.items() if k != 'meson_options'}
    fingerprint = hashlib.sha256(json.dumps(source_recipe, sort_keys=True).encode())
    for p in recipe['patches']:
        fingerprint.update((HERE / 'patches' / p).read_bytes())
    fingerprint = fingerprint.hexdigest()
    marker = work / 'source-fingerprint'
    if not marker.exists() or marker.read_text() != fingerprint:
        if (work / 'build').exists():
            shutil.rmtree(work / 'build')
        if source.exists():
            shutil.rmtree(source)
        if 'commit' in recipe:
            run('git', 'init', source)
            run('git', 'remote', 'add', 'origin', recipe['repository'], cwd=source)
            run('git', 'fetch', '--depth=1', 'origin', recipe['commit'], cwd=source)
            run('git', 'checkout', '--detach', 'FETCH_HEAD', cwd=source)
            assert output('git', 'rev-parse', 'HEAD', cwd=source).strip() == recipe['commit']
        else:
            archive = work / 'mesa.tar.xz'
            download(recipe['archive_url'], archive, recipe['archive_sha256'])
            source.mkdir()
            # tar's strip-components is applied only to the hash-verified upstream archive.
            run('tar', '-xJf', archive, '--strip-components=1', '-C', source)
        assert (source / 'VERSION').read_text().strip() == recipe['mesa_version']
        for patch in recipe['patches']:
            run('patch', '--batch', '--fuzz=0', '--no-backup-if-mismatch', '-p1', '-i', HERE / 'patches' / patch, cwd=source)
        if 'tu_gen8_version' in recipe:
            (source / 'src/freedreno/vulkan/tu_version.h').write_text(
                '#define TUGEN8_DRV_VERSION ' + json.dumps(recipe['tu_gen8_version']) + '\n')
        marker.write_text(fingerprint)
    sysroot = cache / 'sysroot'
    cross = work / 'cross.ini'
    native = work / 'native.ini'
    cross.write_text(f'''[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
pkg-config = 'pkg-config'
[properties]
sys_root = '{sysroot}'
pkg_config_libdir = '{sysroot}/usr/lib/pkgconfig:{sysroot}/usr/share/pkgconfig'
[built-in options]
c_args = ['--sysroot={sysroot}']
cpp_args = ['--sysroot={sysroot}']
c_link_args = ['--sysroot={sysroot}', '-L{sysroot}/usr/lib']
cpp_link_args = ['--sysroot={sysroot}', '-L{sysroot}/usr/lib']
[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
''')
    native.write_text(f'''[binaries]
c = 'gcc'
cpp = 'g++'
pkg-config = '/usr/bin/pkg-config'
cmake = '/usr/bin/cmake'
wayland-scanner = '{cache}/host/bin/wayland-scanner'
glslangValidator = '{shutil.which('glslangValidator')}'
[built-in options]
pkg_config_path = '{cache}/host/lib/pkgconfig'
''')
    build_dir = work / 'build'
    setup = ['meson', 'setup']
    if (build_dir / 'build.ninja').exists():
        setup.append('--reconfigure')
    run(*setup, build_dir, source, '--cross-file', cross, '--native-file', native,
            '--buildtype=release', '--prefix=/usr', '--libdir=lib',
            '-Dvulkan-drivers=freedreno', '-Dfreedreno-kmds=msm,kgsl', '-Dgallium-drivers=',
            '-Dplatforms=wayland,x11', '-Dopengl=false', '-Dgbm=disabled', '-Dglx=disabled',
        '-Degl=disabled', '-Dllvm=disabled', '-Dvulkan-layers=', '-Dtools=', '-Dxmlconfig=disabled',
        *recipe.get('meson_options', []))
    run('ninja', '-C', build_dir, '-j', args.jobs, 'src/freedreno/vulkan/libvulkan_freedreno.so')
    stage = work / 'stage'
    stage.mkdir(exist_ok=True)
    library = stage / 'libvulkan_freedreno.so'
    run('aarch64-linux-gnu-strip', '-o', library, build_dir / 'src/freedreno/vulkan/libvulkan_freedreno.so')
    header = output('aarch64-linux-gnu-readelf', '-hW', library)
    dynamic = output('aarch64-linux-gnu-readelf', '-dW', library)
    symbols = output('aarch64-linux-gnu-readelf', '--dyn-syms', '-W', library)
    versions = output('aarch64-linux-gnu-readelf', '-VW', library)
    assert 'AArch64' in header and 'ELF64' in header and 'little endian' in header
    assert 'libc.so.6' in dynamic and '[libc.so]' not in dynamic
    for name in ['vk_icdGetInstanceProcAddr', 'vk_icdNegotiateLoaderICDInterfaceVersion']:
        assert name in symbols, 'missing export ' + name
    needed = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
    for name in needed:
        assert (sysroot / 'usr/lib' / name).exists(), 'missing sysroot dependency ' + name
    glibc_versions = set(re.findall(r'GLIBC_(\d+\.\d+(?:\.\d+)?)', versions))
    glibc_min = max(glibc_versions, key=lambda s: tuple(map(int, s.split('.'))))
    load_tested = False
    if platform.machine() in ('aarch64', 'arm64'):
        probe = work / 'probe'
        run('gcc', '-Wall', '-Wextra', '-Werror', HERE / 'probe.c', '-ldl', '-o', probe)
        run(sysroot / 'usr/lib/ld-linux-aarch64.so.1', '--library-path', sysroot / 'usr/lib', probe, library)
        load_tested = True
    vulkan_patch = re.search(r'^#define VK_HEADER_VERSION\s+(\d+)',
                             (source / 'include/vulkan/vulkan_core.h').read_text(), re.M)[1]
    api_match = re.search(r'#define TU_API_VERSION\s+VK_MAKE_VERSION\(\s*1,\s*(\d+)',
                          (source / 'src/freedreno/vulkan/tu_device.cc').read_text())
    assert api_match, 'cannot determine Vulkan API version'
    vulkan = '1.' + api_match[1] + '.' + vulkan_patch
    meta = dict(schemaVersion=1, name=recipe['name'], vendor='Mesa', author='SkyNetEmu',
                driverVersion=recipe['mesa_version'], packageVersion='1', libc='glibc',
                kind='linux-vulkan-icd', minGlibc=glibc_min, source=recipe,
                description='Experimental Linux KGSL build; not yet device-validated.')
    (stage / 'meta.json').write_text(json.dumps(meta, indent=2) + '\n')
    (stage / 'freedreno_icd.aarch64.json').write_text(json.dumps({
        'file_format_version': '1.0.0', 'ICD': {'library_path': './libvulkan_freedreno.so', 'api_version': vulkan}
    }, indent=2) + '\n')
    args.out.mkdir(parents=True, exist_ok=True)
    report = dict(recipe=args.recipe, source=recipe, recipe_fingerprint=fingerprint,
                  library_sha256=sha(library), min_glibc=glibc_min, needed=needed,
                  vulkan_api=vulkan, compiler=output('aarch64-linux-gnu-gcc', '--version').splitlines()[0],
                  meson=output('meson', '--version').strip(),
                  sysroot_packages=(cache / 'sysroot-packages.txt').read_text().splitlines(),
                  loader_smoke_test=load_tested,
                  device_validated=False)
    if 'renderer_url' in recipe:
        renderer = args.out / 'Turnip_Gen8_V30-Android.zip'
        download(recipe['renderer_url'], renderer, recipe['renderer_sha256'])
        with zipfile.ZipFile(renderer) as z:
            renderer_elf = z.read('libvulkan_freedreno.so')
            assert recipe['commit'][:10].encode() in renderer_elf
            assert recipe['mesa_version'].encode() in renderer_elf
        report['renderer_sha256'] = sha(renderer)
    zip_path = args.out / ('turnip-' + args.recipe + '-linux-aarch64.zip')
    with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        for p in sorted(stage.iterdir()):
            z.write(p, p.name)
        z.writestr('build-report.json', json.dumps(report, indent=2) + '\n')
        for p in recipe['patches']:
            z.write(HERE / 'patches' / p, 'source-patches/' + p)
        license_file = source / 'docs/license.rst'
        if license_file.exists():
            z.write(license_file, 'LICENSE-Mesa.txt')
    report['zip_sha256'] = sha(zip_path)
    report['zip_size'] = zip_path.stat().st_size
    (args.out / (args.recipe + '-build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2), flush=True)
    print('BUILT', zip_path, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recipe', choices=sorted(RECIPES))
    parser.add_argument('--work', type=Path, default=Path('/work/build'))
    parser.add_argument('--out', type=Path, default=Path('/work/out'))
    parser.add_argument('--jobs', type=int, default=4)
    build(parser.parse_args())
