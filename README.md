# rt_render_experiment

`rt_render_experiment` is an experimental Fabric client renderer for Minecraft 26.2. It takes geometry
from the running Minecraft producer, decodes it into its own GPU scene, and traces that scene with a
minimal Vulkan ray-query shader template written in Slang.

**Experimental status:** this is research software. Compatibility, stability and performance are not
guaranteed, and the rendering contract may change without notice.

## Requirements

- Minecraft 26.2 with Fabric Loader 0.19.3 or newer, running the Vulkan graphics backend
- Java 25
- A GPU and driver exposing Vulkan 1.2 with `VK_KHR_ray_query`, `VK_KHR_acceleration_structure`
  and buffer device addresses
- `slangc` (Slang 2026.11 or compatible) and `python3` on `PATH` to build the shader package
- SPIR-V Tools (`spirv-val`, or `libSPIRV-Tools`) to validate the compiled modules

## Building

```sh
./gradlew clean check build
```

`check` compiles the shader package from the committed Slang sources, validates every produced SPIR-V
module against `shaders/abi/schema.json`, and runs the engine test suite, which includes GPU tests that
require a working Vulkan device.

The release jar is written to `build/libs/rt_render_experiment.jar`. Install it in a Fabric 26.2 client
started with the Vulkan backend. `./gradlew runClient` starts a development client directly.

To measure the renderer at native resolution on the current device:

```sh
./gradlew -p engine test --tests '*R2ProgramGpuTest*' -Prt_render_experimentMeasure=true
```

## Shader template

Start in [`shaders/template.slang`](shaders/template.slang). It contains three editable hooks:

| Hook | Default |
| --- | --- |
| `r2TemplateBackground(direction)` | neutral gray background |
| `r2TemplateLighting(position, normal, viewDirection)` | unit lighting, showing the decoded base texture and emission |
| `r2TemplateDisplay(radiance)` | clamp linear RGB to the display range |

Colors are linear RGB. Surface positions use the engine's scene-relative coordinates. The engine
adapter handles the primary ray, material decoding, depth, motion and final sRGB encoding.
Rebuild with `./gradlew check build` after editing the template.

The package retains the `rt_render_experiment-r2` entry points required by the engine:

| Pass | Work |
| --- | --- |
| `r2PrimaryVisibility` | primary ray, surface decode, template hooks and engine outputs |
| `r2Temporal` | copy current signals into the history record; placeholder for temporal reconstruction |
| `r2Atrous` | combine current signals; placeholder for spatial filtering |
| `r2Meter`, `r2Display` | fixed exposure, template display hook and sRGB encoding |

Scene, material, light and atmosphere tables are compiled by `r2CompileMaterials`,
`r2CompileAtmosphere` and `r2DecodeProducer`, and reach the tracing pass through buffer device
addresses declared in `shaders/abi/schema.json`.

## Settings

- `-Drt_render_experiment.disabled=true` disables the renderer and leaves the host path untouched.
- `-Drt_render_experiment.counters`, `.timings`, `.profileDispatches`, `.pipelineStatistics` and
  `.pipelineRepresentations` enable optional diagnostics. They are off by default and are only useful
  when investigating the renderer itself.

## Known boundaries

- Client only, Vulkan backend only, Minecraft 26.2 only. There is no OpenGL path and no server component.
- The renderer replaces world shading. Host-drawn content is composited back through the frame graph.
- Lighting, shadows, reflections, sky effects and denoising are left for the shader author to implement.

## License

First-party project material is dedicated to the public domain under
[CC0-1.0](LICENSE) (`LICENSE` holds the complete legal code).

The Gradle wrapper files `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are
third-party material, Copyright the original Gradle authors, licensed under Apache-2.0 and redistributed
under those terms. They are **not** covered by the CC0-1.0 dedication. `gradle-wrapper.jar` carries its
own `META-INF/LICENSE`.

CC0-1.0 waives copyright and related rights. It does not grant patent or trademark rights.
Dependencies resolved at build time keep their own licenses and are not relicensed by this project.
