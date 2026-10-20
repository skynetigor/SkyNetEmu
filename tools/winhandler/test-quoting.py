#!/usr/bin/env python3
"""Check the real helper argument serializer with a host C compiler, no Wine needed."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[2]
source = (repo / 'app/src/main/cpp/winlator/winhandler.c').read_text()
function = source[source.index('static char *quoteArguments'):source.index('SOCKET sock;')]
cases = [
    ([], ''),
    ([''], '""'),
    (['plain', 'path with spaces'], 'plain "path with spaces"'),
    (['C:\\dir\\'], 'C:\\dir\\'),
    (['a"b'], '"a\\"b"'),
    (['slash\\"quote'], '"slash\\\\\\"quote"'),
    (['space slash \\'], '"space slash \\\\"'),
    (['a\tb'], '"a\tb"'),
    (['-gfx', 'vk', '-nosteam', '-userpath', 'C:/beamng-arm64-user', '-level', 'gridmap_v2'],
     '-gfx vk -nosteam -userpath C:/beamng-arm64-user -level gridmap_v2'),
]
body = ['#include <stdint.h>\n#include <stdio.h>\n#include <stdlib.h>\n'
        '#include <string.h>\n#include <assert.h>\n', function, 'int main(void) {']
for index, (arguments, expected) in enumerate(cases):
    values = ', '.join(json.dumps(arg) for arg in arguments) or 'NULL'
    body.append(f'char *args{index}[] = {{{values}}};')
    body.append(f'char *result{index} = quoteArguments({len(arguments)}, args{index}, 0);')
    body.append(f'assert(result{index} && strcmp(result{index}, {json.dumps(expected)}) == 0);')
    body.append(f'free(result{index});')
body.append('''
char large[10001]; memset(large, 'x', 10000); large[10000] = 0;
char *args[] = {large}; char *result = quoteArguments(1, args, 0);
assert(result && strlen(result) == 10000); free(result);
puts("PASS helper quoting: empty, spaces, quotes, backslashes, BeamNG switches, long argument");
return 0;
}
''')
with tempfile.TemporaryDirectory(prefix='skynet-winhandler-') as directory:
    path = Path(directory)
    (path / 'quoting.c').write_text('\n'.join(body))
    compiler = shlex.split(os.environ.get('CC', 'cc'))
    flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
    if os.environ.get('SANITIZE') == '1':
        flags.append('-fsanitize=address,undefined')
    subprocess.run(compiler + flags + [str(path / 'quoting.c'), '-o', str(path / 'quoting')], check=True)
    subprocess.run([str(path / 'quoting')], check=True)
