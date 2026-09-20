#!/usr/bin/env python3
"""One storage authority for the refounded shader package (family rt_render_experiment-r2).

Reads the active optical and ingress schemas. Generates records, resources, descriptor layouts,
formats, dispatch constants and CPU semantic mirrors. --check rejects any stale mirror. The frozen
"""
import argparse
import hashlib
import json
from resource_products import products as resource_products
from contract_products import products as contract_products
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / 'shaders/abi/schema.json'
PRODUCER_SCHEMA = ROOT / 'shaders/abi/producer-schema.json'
SLANG_OUT = ROOT / 'shaders/abi/records.slang'
JAVA_OUT = ROOT / 'modules/engine/src/main/java/dev/rt_render_experiment/engine/abi/R2Abi.java'
TYPES = {'float': (4, 4, 'Float'), 'uint': (4, 4, 'Int'), 'int': (4, 4, 'Int'), 'uint64_t': (8, 8, 'Long'),
         'float2': (8, 8, 'Float'), 'float4': (16, 16, 'Float'), 'uint4': (16, 16, 'Int'), 'int4': (16, 16, 'Int')}


def layout_fields(fields):
    offset, alignment, members, padding = 0, 1, [], 0

    def pad_to(target):
        nonlocal offset, padding
        while offset < target:
            members.append(dict(name=f'_padding{padding}', type='uint', offset=offset, count=1, stride=4, writer='Int'))
            offset += 4
            padding += 1
    for field in fields:
        kind, name, *length = field
        size, align, writer = TYPES[kind]
        pad_to((offset + align - 1) // align * align)
        count = length[0] if length else 1
        members.append(dict(name=name, type=kind, offset=offset, count=count, stride=size, writer=writer))
        offset += size * count
        alignment = max(alignment, align)
    pad_to((offset + alignment - 1) // alignment * alignment)
    return dict(size=offset, alignment=alignment, members=members)


def layout(schema):
    result = {}
    for name, record in schema['records'].items():
        entry = layout_fields(record['fields'])
        entry['layout'] = record['layout']
        entry['scene'] = record.get('scene', False)
        result[name] = entry
    return result


def constant_prefix(group):
    return {'coverageKinds': 'COVERAGE', 'sourceKinds': 'SOURCE', 'viewDomains': 'VIEW', 'rayMasks': 'RAY', 'geometryFlags': 'GEOMETRY', 'layerFlags': 'LAYER', 'materialFlags': 'MATERIAL',
            'boundaryRoles': 'ROLE', 'mediumKinds': 'MEDIUM', 'hitFlags': 'HIT', 'frameFlags': 'FRAME', 'guideFlags': 'GUIDE'}[group]


def literal(x):
    return repr(float(x))


def generate(check=False):
    schema = json.loads(SCHEMA.read_text())
    producer = json.loads(PRODUCER_SCHEMA.read_text())
    if producer['api'] != 1 or set(producer['kernels']) & set(schema['kernels']):
        raise SystemExit('Invalid producer decoder contract')
    schema['kernels'].update(producer['kernels'])
    records = layout(schema)
    if 'PathResultRecord' in records and records['PathResultRecord']['size'] != records['FilterRecord']['size']:
        raise SystemExit('Path-result/filter scratch stride mismatch')
    if 'PathMediumRecord' in records:
        fields={f[1]:f for f in schema['records']['PathMediumRecord']['fields']}
        capacity=schema['sizes']['mediumCapacity']
        if fields['optical'][2]!=2*(capacity+1) or fields['parents'][2]!=(capacity+1)//2:
            raise SystemExit('Path medium storage differs from medium capacity')
    if 'PrimaryWorkRecord' in records:
        work_record, guide_record = records['PrimaryWorkRecord'], records['GuideRecord']
        work_policy = next(m['offset'] for m in work_record['members'] if m['name'] == 'policy')
        guide_policy = next(m['offset'] for m in guide_record['members'] if m['name'] == 'path')
        if work_record['size'] != guide_record['size'] or work_policy != guide_policy:
            raise SystemExit('Primary/guide lifetime alias layout mismatch')
    for name in ('initialMediumIdentity', 'initialMediumOptical', 'initialMediumScattering'):
        field = next(f for f in schema['records']['FrameRecord']['fields'] if f[1] == name)
        if field[2] != schema['sizes']['mediumCapacity']:
            raise SystemExit(f'Initial medium array differs from the medium capacity: {name}')
    digest = hashlib.sha256(SCHEMA.read_bytes()).hexdigest()
    slang = [
             '#ifndef R2_ABI_RECORDS', '#define R2_ABI_RECORDS',
             f'static const uint R2_ABI_VERSION = {schema["abi"]}u;',
             f'static const uint R2_SEMANTIC_API_VERSION = {schema["semanticApi"]}u;']
    java = [
            'package dev.rt_render_experiment.engine.abi;', 'import java.nio.ByteBuffer;', 'import java.util.List;',
'public final class R2Abi {',
            '    private R2Abi() {}', f'    public static final String FAMILY = "{schema["family"]}";',
            f'    public static final int VERSION = {schema["abi"]};', f'    public static final int SEMANTIC_API_VERSION = {schema["semanticApi"]};',
            f'    public static final String SCHEMA_SHA256 = "{digest}";']
    java.extend([f'    public static final int PRODUCER_API_VERSION = {producer["api"]};',
                 f'    public static final String PRODUCER_SCHEMA_SHA256 = "{hashlib.sha256(PRODUCER_SCHEMA.read_bytes()).hexdigest()}";'])
    for name, record in records.items():
        slang.append(f'struct {name} {{')
        java.extend([f'    public static final class {name} {{', f'        private {name}() {{}}', f'        public static final int SIZE = {record["size"]};'])
        for member in record['members']:
            m, t, count, stride, offset = (member[k] for k in ('name', 'type', 'count', 'stride', 'offset'))
            array = f'[{count}]' if count > 1 else ''
            slang.append(f'    {t} {m}{array};')
            if m.startswith('_padding'):
                continue
            java.append(f'        public static final int {m.upper()} = {offset};')
            primitive = 'long' if t == 'uint64_t' else ('int' if t.startswith(('uint', 'int')) else 'float')
            component_bytes = 8 if primitive == 'long' else 4
            components = stride // component_bytes * count
            java.extend([f'        public static void {m}(ByteBuffer target, int base, {primitive}... values) {{',
                         f'            if (values.length != {components}) throw new IllegalArgumentException("{name}.{m} requires {components} components");',
                         '            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");',
                         f'            for (int i = 0; i < values.length; i++) target.put{member["writer"]}(base + {offset} + i * {component_bytes}, values[i]);', '        }'])
        java.append('    }')
        slang.append('};')
    for group, values in schema['constants'].items():
        prefix = constant_prefix(group)
        for name, value in values.items():
            slang.append(f'static const uint R2_{prefix}_{name} = {value}u;')
            java.append(f'    public static final int {prefix}_{name} = {value};')
    automatic_hit_mask = sum(1 << schema['constants']['coverageKinds'][name] for name in schema['acceleration']['automaticHitCoverage'])
    slang.append(f'static const uint R2_AUTOMATIC_HIT_COVERAGE_MASK = {automatic_hit_mask}u;')
    java.append(f'    public static final int AUTOMATIC_HIT_COVERAGE_MASK = {automatic_hit_mask};')
    java.extend(['    public static boolean automaticHitCoverage(int coverage, boolean doubleSided) {',
                 '        return doubleSided && coverage >= 0 && coverage < 32 && (AUTOMATIC_HIT_COVERAGE_MASK & (1 << coverage)) != 0;', '    }'])
    sizes = schema['sizes']
    for key, slang_name, java_name in [('materialDefinitions', 'R2_MATERIAL_COUNT', 'MATERIAL_COUNT'), ('mediumCapacity', 'R2_MEDIUM_CAPACITY', 'MEDIUM_CAPACITY'),
                                       ('atmosphereWidth', 'R2_ATMOSPHERE_WIDTH', 'ATMOSPHERE_WIDTH'), ('atmosphereHeight', 'R2_ATMOSPHERE_HEIGHT', 'ATMOSPHERE_HEIGHT')]:
        slang.append(f'static const uint {slang_name} = {sizes[key]}u;')
        java.append(f'    public static final int {java_name} = {sizes[key]};')
    for name, value in schema['surfaceProperties'].items():
        slang.append(f'static const uint R2_STATE_{name} = {value}u;')
    profiles = schema['mediumProfiles']
    for kind, profile in profiles['profiles'].items():
        for key in ('ior', 'absorption', 'scattering'):
            value = profile[key]
            vector = isinstance(value, list)
            text = 'float3(' + ','.join(literal(x) for x in value) + ')' if vector else literal(value)
            slang.append(f'static const {"float3" if vector else "float"} R2_{kind}_{key.upper()} = {text};')
    for key, name in [('minimumTint', 'MINIMUM_TINT'), ('maximumTint', 'MAXIMUM_TINT'), ('tintAbsorption', 'TINT_ABSORPTION')]:
        slang.append(f'static const float R2_MEDIUM_{name} = {literal(profiles[key])};')
    catalog = [ '#ifndef R2_CATALOG_ADMISSION', '#define R2_CATALOG_ADMISSION']
    catalog.append('bool r2OpaqueContactDefinition(uint material) {')
    catalog.append('    switch(material) { ' + ''.join(f'case {i}u: ' for i in profiles['opaqueContacts']) + 'return true; default: return false; }')
    catalog.append('}')
    catalog.append('bool r2GlassSheetDefinition(uint material) { return ' + ' || '.join(f'material=={x}u' for x in profiles['glassSheets']) + '; }')
    slang.append(f'static const uint R2_RECONSTRUCTION_ITERATIONS = {schema["execution"]["reconstructionIterations"]}u;')
    java.append(f'    public static final int RECONSTRUCTION_ITERATIONS = {schema["execution"]["reconstructionIterations"]};')
    slang.append(f'static const uint R2_SOURCE_PREPARATION_COLUMNS = {schema["execution"]["sourcePreparationColumns"]}u;')
    java.append(f'    public static final int SOURCE_PREPARATION_COLUMNS = {schema["execution"]["sourcePreparationColumns"]};')
    for name, key in [('PATH_QUEUE_COLUMNS','pathQueueColumns'),('PATH_BOUNCES','pathBounces')]:
        slang.append(f'static const uint R2_{name} = {schema["execution"][key]}u;')
        java.append(f'    public static final int {name} = {schema["execution"][key]};')
    java.append('    public static final class Formats { private Formats() {}')
    for name, value in schema['formats'].items():
        java.append(f'        public static final int {name} = org.lwjgl.vulkan.VK12.VK_FORMAT_{value};')
    java.append('    }')
    for kernel, facts in schema['kernels'].items():
        for entry, group in facts['workgroups'].items():
            for axis, value in zip('XYZ', group):
                slang.append(f'#define R2_THREADS_{entry.upper()}_{axis} {value}')
        java.append(f'    public static final class {kernel[0].upper()+kernel[1:]} {{')
        java.append(f'        private {kernel[0].upper()+kernel[1:]}() {{}}')
        java.append('        public static final List<String> ENTRIES = List.of(' + ', '.join(f'"{e}"' for e in facts['entries']) + ');')
        for name, binding in facts['bindings'].items():
            slang.append(f'#define R2_BIND_{kernel.upper()}_{name.upper()} {binding["binding"]}')
            java.append(f'        public static final int {name.upper()} = {binding["binding"]};')
        java.append('    }')
    optical_entries = [entry for kernel, facts in schema['kernels'].items() if kernel not in producer['kernels'] for entry in facts['entries']]
    java.append('    public static final List<String> OPTICAL_ENTRIES = List.of(' + ', '.join(f'"{entry}"' for entry in optical_entries) + ');')
    slang.append('#endif')
    java.append('}')
    products = {SLANG_OUT: '\n'.join(slang) + '\n', JAVA_OUT: '\n'.join(java) + '\n',
                ROOT / 'shaders/materials/catalog_admission.slang': '\n'.join(catalog + ['#endif']) + '\n'}
    products.update(resource_products(ROOT, schema))
    products.update(contract_products(ROOT, schema))
    for path, content in products.items():
        if check:
            if not path.exists() or path.read_text() != content:
                raise SystemExit(f'Stale generated R2 ABI: {path}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
    print(f'R2 GPU ABI {schema["abi"]} / semantic API {schema["semanticApi"]}: {len(records)} records; schema {digest}')
    return schema, records


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    generate(parser.parse_args().check)
