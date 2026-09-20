def products(root, schema):
    out = {}
    header = ''
    contract = root/'modules/contract/src/main/java/dev/rt_render_experiment/contract'
    def constants(name, values):
        lines=[header,'package dev.rt_render_experiment.contract;',f'public final class {name} {{',f'    private {name}() {{}}']
        lines += [f'    public static final int {k} = {v};' for k,v in values.items()]
        out[contract/(name+'.java')]='\n'.join(lines+['}'])+'\n'
    constants('SurfaceProperties',schema['surfaceProperties'])
    constants('MediumEncoding',dict(schema['constants']['mediumKinds'],CAPACITY=schema['sizes']['mediumCapacity']))
    p=[header,'package dev.rt_render_experiment.contract;',
       'public record Participation(int rayMask, boolean primaryVisible) {']
    p += [f'    public static final int {k} = {v};' for k,v in schema['constants']['rayMasks'].items()]
    p += ['    public static final int WORLD = CAMERA_PATH | REFLECTION | SHADOW | TRANSPORT;',
          '    public Participation {',
          '        if (rayMask <= 0 || rayMask > 255 || (rayMask & VIEWMODEL) != 0 && rayMask != VIEWMODEL)',
          '            throw new IllegalArgumentException("Invalid or mixed view/world ray participation");','    }','}']
    out[contract/'Participation.java']='\n'.join(p)+'\n'
    profiles=schema['mediumProfiles'];lit=lambda v:repr(float(v))+'f'
    p=[header,'package dev.rt_render_experiment.engine;','import dev.rt_render_experiment.contract.MediumInputs;', 'import dev.rt_render_experiment.contract.SceneInputs;',
       'import dev.rt_render_experiment.contract.SurfaceProperties;','final class MediumProfiles {','    private MediumProfiles() {}',
       '    static MediumInputs.Kind kind(int material,int properties) {',
       '        if((properties&SurfaceProperties.TRANSLUCENT)!=0)return null;', '        var kind=switch(material) {']
    for kind in profiles['profiles']:
        ids=[k for k,v in profiles['materialKinds'].items() if v==kind]
        if ids:p.append(f'            case {",".join(ids)} -> MediumInputs.Kind.{kind};')
    p += ['            default -> null;','        };','        return kind==MediumInputs.Kind.GLASS && (properties&SurfaceProperties.THIN)!=0?null:kind;','    }',
          '    static boolean potential(SceneInputs.Surface surface) {',
          '        return surface.boundary()!=SceneInputs.Boundary.UNQUALIFIED || surface.coverage()!=SceneInputs.Coverage.FILTER && kind(surface.material(),surface.properties())!=null;','    }',
          '    static boolean opaqueContact(SceneInputs.Surface surface) {',
          '        if(surface.boundary()==SceneInputs.Boundary.NESTED_VOLUME || surface.coverage()!=SceneInputs.Coverage.OPAQUE || surface.layer()!=0 || surface.layerSeparation()!=0',
          '            || (surface.properties()&(SurfaceProperties.THIN|SurfaceProperties.TRANSLUCENT))!=0)return false;',
          '        return switch(surface.material()) { case '+','.join(map(str,profiles['opaqueContacts']))+' -> true; default -> false; };','    }',
          '    static boolean sheet(int material) { return '+ ' || '.join(f'material=={x}' for x in profiles['glassSheets'])+'; }',
          '    static MediumInputs.Definition definition(MediumInputs.Kind kind,SceneInputs.Vec3 color) {','        return switch(kind) {']
    for kind,profile in profiles['profiles'].items():
        absorption=','.join(f'absorption({lit(x)},color.{axis}())' if profile['tinted'] else lit(x) for x,axis in zip(profile['absorption'],'xyz'))
        scattering=','.join(map(lit,profile['scattering']))
        p.append(f'            case {kind} -> new MediumInputs.Definition(kind,{lit(profile["ior"])},new SceneInputs.Vec3({absorption}),new SceneInputs.Vec3({scattering}));')
    p += ['        };','    }','    private static float absorption(float base,float color) {',
          f'        return base-(float)Math.log(Math.clamp(color,{lit(profiles["minimumTint"])},{lit(profiles["maximumTint"])}))*{lit(profiles["tintAbsorption"])};', '    }','}']
    out[root/'modules/engine/src/main/java/dev/rt_render_experiment/engine/MediumProfiles.java']='\n'.join(p)+'\n'
    return out
