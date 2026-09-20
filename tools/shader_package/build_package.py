
"""Compile, independently verify and package the refounded shader family (rt_render_experiment-r2).

Every entry is compiled from shaders/ with the production policy, its final SPIR-V is parsed
here (not through the compiler's reflection) and checked against shaders/abi/schema.json for
record layouts, binding storage classes and element types, admitted capabilities, forbidden
opcodes and the execution model. No include may resolve outside shaders/.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/shader_package'))
sys.path.insert(0, str(ROOT / 'tools/shaders'))
from generate_abi import generate
from spirv_validation import spirv_validate

TREE = ROOT / 'shaders'

def declared_sources():
    schemas = [json.loads((TREE / 'abi' / name).read_text()) for name in ('schema.json', 'producer-schema.json')]
    return {entry: path for schema in schemas for kernel in schema['kernels'].values() for entry, path in kernel['sources'].items()}

FULL_SOURCES = declared_sources()

def source_map(profile):
    if profile.get('program', 'full') != 'full':
        raise ValueError('Only the complete R2 optical program is supported')
    return FULL_SOURCES


IMAGE_FORMATS = {'rgba16f': 2, 'r32f': 3, 'rg32f': 6, 'rg16f': 7}
MODELS = {'compute': 5, 'vertex': 0, 'fragment': 4}
CAPABILITIES = 'spvRayQueryKHR+spvShaderNonUniform+SPV_KHR_non_semantic_info+SPV_GOOGLE_user_type+spvDerivativeControl+spvImageQuery+spvImageGatherExtended+spvSparseResidency+spvMinLod+spvFragmentFullyCoveredEXT'



ADMITTED = {1, 11, 49, 50, 4427, 4472, 5301, 5302, 5307, 5347}
RAY_QUERY = 4472
FORBIDDEN_OPCODES = {4445: 'OpTraceRayKHR', 4446: 'OpExecuteCallableKHR', 5334: 'OpReportIntersectionKHR', 4448: 'OpIgnoreIntersectionKHR', 4449: 'OpTerminateRayKHR'}
FORBIDDEN_CAPABILITIES = {4479: 'RayTracingKHR'}
EXPECTED_TYPES = {'float': 'float32', 'uint': 'uint32', 'int': 'int32', 'uint64_t': 'uint64', 'float2': 'float32x2',
                  'float4': 'float32x4', 'uint4': 'uint32x4', 'int4': 'int32x4'}


def string(words):
    return struct.pack('<' + 'I' * len(words), *words).split(b'\0', 1)[0].decode()


def family_of(schema, entry):
    for name, facts in schema['kernels'].items():
        if entry in facts['entries']:
            requirements = facts.get('entryRequirements', {})
            if not set(requirements) <= set(facts['entries']):
                raise ValueError(f'{name}: requirements name an absent entry')
            for declaration in requirements.values():
                if not set(declaration) <= {'rayQuery', 'textures'} or any(type(v) is not bool for v in declaration.values()):
                    raise ValueError(f'{name}: invalid entry requirements')
            return name, {**facts, **requirements.get(entry, {})}
    raise ValueError(f'Entry {entry} belongs to no kernel family')


def verify(path, entry, schema, records):
    data = path.read_bytes()
    words = struct.unpack('<' + 'I' * (len(data) // 4), data)
    if words[0] != 0x07230203:
        raise ValueError('Invalid SPIR-V header')
    if words[1] != 0x10500:
        raise ValueError('Unexpected SPIR-V target version')
    kernel, facts = family_of(schema, entry)
    names, members, offsets, arrays, strides, structures, bindings, sets, capabilities = {}, {}, {}, {}, {}, {}, {}, {}, set()
    types, constants, variables, opcodes = {}, {}, {}, set()
    entry_found = False
    workgroup = None
    index = 5
    while index < len(words):
        count, op = words[index] >> 16, words[index] & 65535
        if count < 1 or index + count > len(words):
            raise ValueError('Invalid SPIR-V instruction')
        a = words[index + 1:index + count]
        opcodes.add(op)
        if op in range(21, 33) or op == 5341:
            types[a[0]] = (op, a[1:])
        if op == 43:
            constants[a[1]] = a[2]
        if op == 59:
            variables[a[1]] = (a[0], a[2])
        if op == 5:
            names[a[0]] = string(a[1:])
        elif op == 6:
            members[a[0], a[1]] = string(a[2:])
        elif op == 15:
            if a[0] != MODELS[facts['entries'][entry]] or string(a[2:]) != 'main':
                raise ValueError(f'{entry}: unexpected execution model or entry name')
            entry_found = True
        elif op == 16 and a[1] == 17:
            workgroup = list(a[2:5])
        elif op == 17:
            capabilities.add(a[0])
        elif op in (28, 29):
            arrays[a[0]] = a[1]
        elif op == 30:
            structures[a[0]] = a[1:]
        elif op == 71 and a[1] == 6:
            strides[a[0]] = a[2]
        elif op == 71 and a[1] == 33:
            bindings[a[0]] = a[2]
        elif op == 71 and a[1] == 34:
            sets[a[0]] = a[2]
        elif op == 72 and a[2] == 35:
            offsets[a[0], a[1]] = a[3]
        index += count
    if facts['entries'][entry] == 'compute' and workgroup != facts['workgroups'][entry]:
        raise ValueError(f'{entry}: compiled workgroup differs from pipeline authority')
    if not entry_found:
        raise ValueError(f'{entry}: entry point missing')
    for op, name in FORBIDDEN_OPCODES.items():
        if op in opcodes:
            raise ValueError(f'{entry}: forbidden RT pipeline instruction {name}')
    for cap, name in FORBIDDEN_CAPABILITIES.items():
        if cap in capabilities:
            raise ValueError(f'{entry}: forbidden capability {name}')
    if not capabilities <= ADMITTED:
        raise ValueError(f'{entry}: unadmitted capabilities {sorted(capabilities - ADMITTED)}')
    if facts['rayQuery'] != (RAY_QUERY in capabilities):
        raise ValueError(f'{entry}: ray query capability disagrees with the kernel declaration')

    pushed = [pointer for pointer, storage in variables.values() if storage == 9]
    if len(pushed) > 1:
        raise ValueError(f'{entry}: more than one push constant block')
    if pushed and not facts.get('pushConstant'):
        raise ValueError(f'{entry}: undeclared push constant block')
    if pushed and not names.get(types[pushed[0]][1][1], '').startswith(facts['pushConstant'] + '_'):
        raise ValueError(f'{entry}: push constant record differs')

    def value_type(ident):
        op, a = types[ident]
        if op == 21:
            return ('int' if a[1] else 'uint') + str(a[0])
        if op == 22:
            return 'float' + str(a[0])
        if op == 23:
            return value_type(a[0]) + 'x' + str(a[1])
        raise ValueError(f'Unexpected scalar/vector type {op}')
    checked = set()
    for ident, fields in structures.items():
        name = re.sub(r'_(std140|std430|natural)$', '', names.get(ident, ''))
        if name not in records:
            continue
        record = records[name]
        if len(fields) != len(record['members']):
            raise ValueError(f'{entry}: {name}: member count differs')
        if not any((ident, n) in offsets for n in range(len(fields))):
            continue
        for ordinal, (typ, member) in enumerate(zip(fields, record['members'])):
            if offsets.get((ident, ordinal)) != member['offset']:
                raise ValueError(f'{entry}: {name}.{member["name"]}: expected offset {member["offset"]}, got {offsets.get((ident, ordinal))}')
            storage_type = typ
            if member['count'] > 1:
                array_type = typ
                if array_type in structures:
                    wrapper = structures[array_type]
                    if len(wrapper) != 1 or offsets.get((array_type, 0)) != 0:
                        raise ValueError(f'{entry}: unsupported array storage wrapper')
                    array_type = wrapper[0]
                if strides.get(array_type) != member['stride']:
                    raise ValueError(f'{entry}: {name}.{member["name"]}: array stride differs')
                op, array = types[array_type]
                if op != 28 or constants.get(array[1]) != member['count']:
                    raise ValueError(f'{entry}: {name}.{member["name"]}: array length differs')
                storage_type = array[0]
            if value_type(storage_type) != EXPECTED_TYPES[member['type']]:
                raise ValueError(f'{entry}: {name}.{member["name"]}: scalar/vector encoding differs')
        checked.add(name)
    for array, element in arrays.items():
        name = re.sub(r'_(std140|std430|natural)$', '', names.get(element, ''))
        if name in records and array in strides and strides[array] != records[name]['size']:
            raise ValueError(f'{entry}: {name} storage array stride {strides[array]} differs from {records[name]["size"]}')
    declared = {binding['binding']: (name, binding) for name, binding in facts['bindings'].items()}
    descriptor_facts = []
    for variable, binding in bindings.items():
        descriptor_set = sets.get(variable)
        pointer, storage = variables[variable]
        op, point = types[pointer]
        if op != 32 or point[0] != storage:
            raise ValueError('Descriptor pointer/storage mismatch')
        target = point[1]
        op, a = types[target]
        if descriptor_set == 1 and binding == 0:
            if not facts['textures']:
                raise ValueError(f'{entry}: texture table bound by a kernel that declares none')
            if storage != 0 or op != 29:
                raise ValueError('Texture table descriptor differs')
            sampled_op, sampled = types[a[0]]
            if sampled_op != 27:
                raise ValueError('Texture table is not combined image/sampler')
            image_op, image = types[sampled[0]]
            if image_op != 25 or image[1] != 1 or image[3] != 0 or image[4] != 0 or image[5] != 1 or image[6] != 0:
                raise ValueError('Texture table format/dimension differs')
        elif descriptor_set == 0 and binding in declared:
            name, spec = declared[binding]
            kind = spec['type']
            if kind == 'uniform':
                if storage != 2 or not names.get(target, '').startswith(spec['record'] + '_'):
                    raise ValueError(f'{entry}: uniform {name} differs')
            elif kind == 'storage':
                if storage != 12 or op != 30 or len(a) != 1:
                    raise ValueError(f'{entry}: storage wrapper {name} differs')
                array_op, array = types[a[0]]
                if array_op != 29:
                    raise ValueError(f'{entry}: storage {name} is not a runtime array')
                if 'record' in spec:
                    if not names.get(array[0], '').startswith(spec['record'] + '_'):
                        raise ValueError(f'{entry}: storage {name} element record differs')
                elif value_type(array[0]) != EXPECTED_TYPES[spec['element']] or strides.get(a[0]) != {'float': 4, 'uint': 4, 'float2': 8, 'float4': 16}[spec['element']]:
                    raise ValueError(f'{entry}: storage {name} element encoding differs')
            elif kind == 'accelerationStructure':
                if storage != 0 or op != 5341:
                    raise ValueError(f'{entry}: acceleration structure {name} differs')
            elif kind == 'sampler':
                if storage != 0 or op != 27:
                    raise ValueError(f'{entry}: sampler {name} differs')
            elif kind == 'storageImage':
                if storage != 0 or op != 25 or a[1] != 1 or a[5] != 2 or a[6] != IMAGE_FORMATS[spec['format']]:
                    raise ValueError(f'{entry}: storage image {name} format/dimension differs')
            else:
                raise ValueError(f'Unknown binding kind {kind}')
        else:
            raise ValueError(f'{entry}: undeclared binding {descriptor_set}:{binding}')
        descriptor_facts.append([descriptor_set, binding])
    return dict(sha256=hashlib.sha256(data).hexdigest(), bytes=len(data), records=sorted(checked), capabilities=sorted(capabilities),
                bindings=sorted(descriptor_facts), kernel=kernel)


def dependencies(path, seen=None):
    seen = set() if seen is None else seen
    if path in seen:
        return seen
    seen.add(path)
    for include in re.findall(r'#include\s+"([^"]+)"', path.read_text()):
        child = (TREE / include).resolve()
        if not child.is_relative_to(TREE) or not child.exists():
            raise ValueError(f'Include outside shaders/ or missing: {path}: {include}')
        dependencies(child, seen)
    return seen


def build(destination, jobs=3, optimization='O2', overrides=None):
    overrides = overrides or {}
    schema, records = generate(check=True)
    profile_path = TREE / 'profile.json'
    profile = json.loads(profile_path.read_text())
    program = profile.get('program', 'full')
    selected = source_map(profile)
    for kernel, facts in schema['kernels'].items():
        for entry in facts['entries']:
            if entry not in FULL_SOURCES:
                raise ValueError(f'Schema entry {entry} has no source mapping')
    sources = set()
    for source in set(selected.values()):
        dependencies((TREE / source).resolve(), sources)
    sources = sorted(sources)
    producer_schema = TREE / 'abi/producer-schema.json'
    source_hash = hashlib.sha256(b''.join(str(p.relative_to(ROOT)).encode() + b'\0' + p.read_bytes() for p in sources)
                                 + profile_path.read_bytes() + (TREE / 'abi/schema.json').read_bytes() + producer_schema.read_bytes()).hexdigest()
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='r2-shaders-', dir=destination.parent) as scratch:
        scratch = Path(scratch)

        def compile_entry(entry):
            output = scratch / (entry + '.spv')
            command = ['slangc', str(TREE / selected[entry]), '-I', str(TREE), '-target', 'spirv', '-profile', 'spirv_1_5',
                       '-capability', CAPABILITIES, '-matrix-layout-row-major', '-warnings-as-errors', 'all', '-' + overrides.get(entry, optimization), '-entry', entry, '-o', str(output)]
            run = subprocess.run(command, capture_output=True, text=True)
            if run.returncode:
                raise ValueError(f'{entry}:\n{run.stdout}{run.stderr}')
            result = verify(output, entry, schema, records)
            result['validator'] = spirv_validate(output, None)
            return entry, result
        with ThreadPoolExecutor(max_workers=jobs) as pool:
            products = dict(pool.map(compile_entry, list(selected)))
        checked = set().union(*(set(p['records']) for p in products.values()))
        unreached = []
        manifest = {'family': schema['family'], 'abi': schema['abi'], 'semanticApi': schema['semanticApi'],
                    'schema': hashlib.sha256((TREE / 'abi/schema.json').read_bytes()).hexdigest(), 'source': source_hash,
                    'compiler': subprocess.check_output(['slangc', '-v'], stderr=subprocess.STDOUT, text=True).strip(),
                    'policy': {'profile': 'spirv_1_5', 'capabilities': CAPABILITIES, 'optimization': optimization, 'entryOptimization': overrides, 'matrixLayout': 'row-major', 'warnings': 'errors',
                               'executionModel': 'compute+rayQuery; no RT pipeline stages'},
                    'modules': products, 'records': records, 'sources': [str(p.relative_to(ROOT)) for p in sources],
                    'unreachedRecords': unreached, 'renderingProfile': profile, 'program': program,
                    'producerApi': 1, 'producerSchema': hashlib.sha256(producer_schema.read_bytes()).hexdigest()}
        (scratch / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        properties = [f'family={manifest["family"]}', f'abi={manifest["abi"]}', f'semanticApi={manifest["semanticApi"]}',
                      f'schema={manifest["schema"]}', f'source={source_hash}', f'profile={profile["name"]}', f'program={program}',
                      f'producerApi={manifest["producerApi"]}', f'producerSchema={manifest["producerSchema"]}']
        properties += [f'{entry}.sha256={info["sha256"]}' for entry, info in products.items()]
        (scratch / 'package.properties').write_text('\n'.join(properties) + '\n')
        previous = destination.with_name(destination.name + '.previous')
        if previous.exists():
            shutil.rmtree(previous)
        if destination.exists():
            destination.rename(previous)
        try:
            scratch.rename(destination)
        except BaseException:
            if previous.exists():
                previous.rename(destination)
            raise
    sizes = ', '.join(f'{e}={i["bytes"]}' for e, i in products.items())
    print(f'R2 package verified: {len(products)} modules, {len(checked)} storage records, {len(sources)} source files; bytes {sizes}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'engine/build/r2-shaders')
    parser.add_argument('--jobs', type=int, default=3)
    parser.add_argument('--optimization', choices=['O0', 'O1', 'O2', 'O3'], default='O2', help='slangc optimization level (O2 is the production policy)')
    parser.add_argument('--entry-optimization', action='append', default=[], metavar='ENTRY=LEVEL', help='optimization level of one entry (experiments only)')
    arguments = parser.parse_args()
    build(arguments.output.resolve(), arguments.jobs, arguments.optimization, dict(item.split('=', 1) for item in arguments.entry_optimization))
