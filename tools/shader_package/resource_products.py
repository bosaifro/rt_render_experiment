from pathlib import Path
import re

def products(root, schema):
    outputs = {}
    java = [
            'package dev.rt_render_experiment.engine.abi;', 'import java.util.List;',
            'import dev.rt_render_experiment.vulkan.VulkanDescriptors;',
            'public final class R2Resources {', '    private R2Resources() {}']
    factory = {'uniform':'uniform', 'storage':'storageBuffer', 'sampler':'sampler', 'storageImage':'storageImage', 'accelerationStructure':'accelerationStructure'}
    image_type = {'rgba16f':'float4', 'rg32f':'float2', 'rg16f':'float2', 'r32f':'float'}
    for kernel, facts in schema['kernels'].items():
        guard = 'R2_RESOURCES_' + kernel.upper()
        method = 'importResources' if kernel == 'import' else kernel
        lines = [ '#ifndef '+guard, '#define '+guard, '#include "abi/records.slang"']
        java += [f'    public static List<VulkanDescriptors.Binding> {method}(int stage) {{', '        return List.of(']
        bindings = sorted(facts['bindings'].items(), key=lambda item:item[1]['binding'])
        if [b['binding'] for _,b in bindings] != list(range(len(bindings))): raise ValueError('Push layouts require dense indices')
        for index, (name, binding) in enumerate(bindings):
            kind = binding['type']; value = binding.get('record', binding.get('element'))
            typ = {'uniform':f'ConstantBuffer<{value}>', 'storage':f'{"RW" if binding["access"]=="readWrite" else ""}StructuredBuffer<{value}>',
                   'sampler':'Sampler2D', 'accelerationStructure':'RaytracingAccelerationStructure', 'storageImage':f'RWTexture2D<{image_type.get(binding.get("format"))}>'}[kind]
            attributes = f'[[vk::binding(R2_BIND_{kernel.upper()}_{name.upper()}, 0)]]'
            if kind=='storageImage': attributes += f' [[vk::image_format("{binding["format"]}")]]'
            lines.append(f'{attributes} {typ} {name};')
            java.append(f'            VulkanDescriptors.{factory[kind]}("{name}", stage)' + (',' if index+1<len(bindings) else ');'))
        java += ['    }']
        if facts.get('textures'): lines.append('[[vk::binding(0, 1)]] Sampler2D Textures[];')
        if facts.get('pushConstant'): lines.append(f'[[vk::push_constant]] {facts["pushConstant"]} Dispatch;')
        lines += ['#endif']
        outputs[root/'shaders/abi/resources'/f'{kernel}.slang'] = '\n'.join(lines)+'\n'
    java += ['    public static List<VulkanDescriptors.Binding> forEntry(String entry, int stage) {', '        return switch (entry) {']
    for kernel, facts in schema['kernels'].items():
        entries = ', '.join('"'+entry+'"' for entry in facts['entries'])
        method = 'importResources' if kernel == 'import' else kernel
        java.append(f'            case {entries} -> {method}(stage);')
    java += ['            default -> throw new IllegalArgumentException("Unknown R2 entry " + entry);', '        };', '    }']
    for axis, index in [('X', 0), ('Y', 1), ('Z', 2)]:
        java += [f'    public static int workgroup{axis}(String entry) {{', '        return switch (entry) {']
        for facts in schema['kernels'].values():
            for entry, group in facts['workgroups'].items(): java.append(f'            case "{entry}" -> {group[index]};')
        java += ['            default -> throw new IllegalArgumentException("Not a compute entry " + entry);', '        };', '    }']
    java += ['}']
    outputs[root/'modules/engine/src/main/java/dev/rt_render_experiment/engine/abi/R2Resources.java'] = '\n'.join(java)+'\n'
    return outputs
