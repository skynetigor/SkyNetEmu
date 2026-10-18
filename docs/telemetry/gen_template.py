#!/usr/bin/env python3
"""Generates the Elasticsearch index template from schema/v1.json, so the mapping and the validation rules cannot drift apart.

  python3 docs/telemetry/gen_template.py            # writes docs/telemetry/elasticsearch/index-template.json
  python3 docs/telemetry/gen_template.py --check    # fails when the file on disk is not what the schema generates
Also writes the app's validation schema and checks every referenced field.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA = os.path.join(HERE, 'schema', 'v1.json')
OUT = os.path.join(HERE, 'elasticsearch', 'index-template.json')
APP_SCHEMA = os.path.abspath(os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'assets', 'telemetry-schema.json'))


def mapping_for(spec):
    kind = spec['type']
    if kind == 'date' and spec.get('epoch_millis'):
        return {'type': 'date', 'format': 'epoch_millis'}
    return {'type': kind}


def insert(tree, dotted, mapping):
    parts = dotted.split('.')
    node = tree
    for part in parts[:-1]:
        node = node.setdefault(part, {'properties': {}})['properties']
    node[parts[-1]] = mapping


def main():
    schema = json.load(open(SCHEMA))
    fields = schema['fields']
    used = set(schema['common']['fields']) | set(schema['added_by_elasticsearch'])
    for ds in schema['datasets'].values():
        used |= set(ds['fields'])
    missing = sorted(f for f in used if f not in fields)
    unused = sorted(f for f in fields if f not in used)
    if missing or unused:
        sys.exit(f'schema is inconsistent: used but not defined {missing}; defined but never used {unused}')
    for name in schema['common']['required']:
        if name not in fields:
            sys.exit(f'required field {name} is not defined')

    properties = {}
    for name, spec in sorted(fields.items()):
        insert(properties, name, mapping_for(spec))
    template = {
        'index_patterns': ['logs-skynet.*-*'],
        'data_stream': {},
        'priority': 200,
        'template': {
            'mappings': {'dynamic': 'false', 'properties': properties},
        },
        '_meta': {'description': 'Generated from docs/telemetry/schema/v1.json by gen_template.py. Do not edit by hand.'},
    }
    text = json.dumps(template, indent=2) + '\n'
    app_schema = json.dumps(schema, separators=(',', ':')) + '\n'
    if '--check' in sys.argv:
        current = open(OUT).read() if os.path.exists(OUT) else ''
        if current != text:
            sys.exit('index-template.json is out of date: run gen_template.py')
        if not os.path.exists(APP_SCHEMA) or open(APP_SCHEMA).read() != app_schema:
            sys.exit('app telemetry-schema.json is out of date: run gen_template.py')
        print('ok')
        return
    with open(OUT, 'w') as f:
        f.write(text)
    with open(APP_SCHEMA, 'w') as f:
        f.write(app_schema)
    print(f'{len(fields)} fields -> {OUT}')


main()
