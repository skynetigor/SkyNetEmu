#!/usr/bin/env python3
"""Check packaged Linux drivers against their external build reports and app import format."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def check(package, report_file):
    report = json.loads(report_file.read_text())
    assert hashlib.sha256(package.read_bytes()).hexdigest() == report['zip_sha256'], 'ZIP hash mismatch'
    assert package.stat().st_size == report['zip_size'], 'ZIP size mismatch'
    with zipfile.ZipFile(package) as z:
        assert z.testzip() is None, 'ZIP CRC failure'
        elf = z.read('libvulkan_freedreno.so')
        meta = json.loads(z.read('meta.json'))
        embedded = json.loads(z.read('build-report.json'))
        icd = json.loads(z.read('freedreno_icd.aarch64.json'))
        # Same architecture/libc conditions the Android LinuxDriverManager importer checks.
        assert elf[:6] == b'\x7fELF\x02\x01' and int.from_bytes(elf[18:20], 'little') == 183
        assert b'libc.so.6' in elf
        assert hashlib.sha256(elf).hexdigest() == report['library_sha256'], 'library hash mismatch'
        assert meta['kind'] == 'linux-vulkan-icd' and meta['libc'] == 'glibc'
        assert 'libraryName' not in meta, 'Linux package must not masquerade as an AdrenoTools package'
        assert meta['source'] == report['source'] == embedded['source']
        assert meta['minGlibc'] == report['min_glibc']
        assert icd['ICD']['library_path'] == './libvulkan_freedreno.so'
        assert icd['ICD']['api_version'] == report['vulkan_api']
        assert report['source']['mesa_version'].encode() in elf
        if 'commit' in report['source']:
            assert report['source']['commit'][:10].encode() in elf
        for p in report['source']['patches']:
            assert z.read('source-patches/' + p)
        assert z.read('LICENSE-Mesa.txt')
    print(f"PASS {package.name}: AArch64/glibc, version/provenance, checksums and app import format; min glibc {report['min_glibc']}")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    packages = sorted(args.directory.glob('turnip-*-linux-aarch64.zip'))
    assert packages, 'no Linux Turnip packages found'
    for p in packages:
        recipe = p.name.removeprefix('turnip-').removesuffix('-linux-aarch64.zip')
        check(p, args.directory / (recipe + '-build-report.json'))
