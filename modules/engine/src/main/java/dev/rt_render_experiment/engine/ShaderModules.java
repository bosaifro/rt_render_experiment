package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;


public interface ShaderModules {
    enum Role { COMPILE_MATERIALS, COMPILE_ATMOSPHERE, TEXTURE_COPY, TEXTURE_COPY_FLOAT }
    String family();

    default String profile() { return family(); }
    String sourceIdentity();

    String executionIdentity();
    ByteBuffer module(String entry);
    String entry(Role role);
    default ByteBuffer module(Role role) { return module(entry(role)); }
}
