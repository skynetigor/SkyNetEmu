#!/usr/bin/env python3
"""Filesystem migration regression test; no Android SDK or device required."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
java = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path('/usr/bin')
with tempfile.TemporaryDirectory(prefix='shared-linux-test-') as work:
    subprocess.run([str(java / 'javac'), '-d', work,
                    str(root / 'app/src/main/java/com/winlator/cmod/linux/SharedLinuxHome.java'),
                    str(Path(__file__).with_name('SharedLinuxHomeTest.java'))], check=True)
    subprocess.run([str(java / 'java'), '-cp', work, 'SharedLinuxHomeTest'], check=True)
