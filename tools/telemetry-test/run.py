#!/usr/bin/env python3
"""Verify diagnostic retention/privacy and consistency with the ingest schema, without Android."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
java = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path('/usr/bin')
schema = json.loads((root / 'docs/telemetry/schema/v1.json').read_text())
for field, limit in {'skynet.renderer.diagnostics': 2048, 'skynet.linux.logs': 6000,
                     'skynet.linux.setup': 1000, 'error.stack_trace': 8192, 'error.message': 300}.items():
    assert schema['fields'][field]['max'] == limit, field
for dataset in ('skynet.session', 'skynet.error'):
    assert {'skynet.renderer.diagnostics', 'skynet.linux.logs', 'skynet.linux.setup',
            'skynet.linux.seconds_running'} <= set(schema['datasets'][dataset]['fields'])
assert 'linux_renderer' in schema['datasets']['skynet.session']['actions']
with tempfile.TemporaryDirectory(prefix='telemetry-test-') as work:
    sources = root / 'app/src/main/java/com/winlator/cmod/telemetry'
    subprocess.run([str(java / 'javac'), '-d', work, str(sources / 'Scrub.java'),
                    str(sources / 'RendererLog.java'), str(Path(__file__).with_name('TelemetryTextTest.java'))], check=True)
    subprocess.run([str(java / 'java'), '-cp', work, 'com.winlator.cmod.telemetry.TelemetryTextTest'], check=True)
subprocess.run(['python3', str(root / 'docs/telemetry/gen_template.py'), '--check'], check=True)
