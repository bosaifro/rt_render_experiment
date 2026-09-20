package dev.rt_render_experiment.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import dev.rt_render_experiment.engine.abi.R2Abi;


public final class R2ShaderPackage implements ShaderModules {
    public static final String FAMILY = R2Abi.FAMILY;
    public static final List<String> ENTRIES = R2Abi.OPTICAL_ENTRIES;
    private static void requireProgram(Properties properties) throws IOException {
        if (!"full".equals(properties.getProperty("program"))) throw new IOException("The complete R2 optical program is required");
    }
    private final Map<String, byte[]> modules;
    private final String sourceIdentity;
    private final String executionIdentity;
    private final String profile;
    private R2ShaderPackage(Map<String, byte[]> modules, String sourceIdentity, String profile) {
        this.modules = Map.copyOf(modules); this.sourceIdentity = sourceIdentity;
        this.profile = profile;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((FAMILY + "\n" + R2Abi.VERSION + "\n" + R2Abi.SEMANTIC_API_VERSION + "\n" + R2Abi.SCHEMA_SHA256).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (String entry : ENTRIES) { digest.update(entry.getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte)0); digest.update(modules.get(entry)); }
            for (String entry : R2Abi.ProducerDecode.ENTRIES) if (modules.containsKey(entry)) {
                digest.update(R2Abi.PRODUCER_SCHEMA_SHA256.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(entry.getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte)0); digest.update(modules.get(entry));
            }
            executionIdentity = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static R2ShaderPackage read(Path directory) throws IOException {
        Properties properties = new Properties();
        try (var stream = Files.newInputStream(directory.resolve("package.properties"))) { properties.load(stream); }
        Map<String, byte[]> modules = new LinkedHashMap<>();
        for (String entry : entries(properties)) modules.put(entry, Files.readAllBytes(directory.resolve(entry + ".spv")));
        return verified(properties, modules);
    }
    public static R2ShaderPackage embedded() throws IOException {
        String diagnostic=System.getProperty("rt_render_experiment.counterPackage");
        if(diagnostic!=null) {
            Path path=Path.of(diagnostic);
            if(!Files.isRegularFile(path.resolve("counter-schema.json")))throw new IOException("Missing diagnostic counter contract");
            return read(path);
        }
        Properties properties = new Properties();
        try (var stream = R2ShaderPackage.class.getResourceAsStream("/rt_render_experiment/r2-shaders/package.properties")) {
            if (stream == null) throw new IOException("R2 shader package is not embedded"); properties.load(stream);
        }
        Map<String, byte[]> modules = new LinkedHashMap<>();
        for (String entry : entries(properties)) try (var stream = R2ShaderPackage.class.getResourceAsStream("/rt_render_experiment/r2-shaders/" + entry + ".spv")) {
            if (stream == null) throw new IOException("Missing embedded shader " + entry); modules.put(entry, stream.readAllBytes());
        }
        return verified(properties, modules);
    }
    private static R2ShaderPackage verified(Properties properties, Map<String, byte[]> modules) throws IOException {
        if (!FAMILY.equals(properties.getProperty("family")) || !Integer.toString(R2Abi.VERSION).equals(properties.getProperty("abi"))
            || !Integer.toString(R2Abi.SEMANTIC_API_VERSION).equals(properties.getProperty("semanticApi"))
            || !R2Abi.SCHEMA_SHA256.equals(properties.getProperty("schema"))) throw new IOException("Incompatible R2 shader generation");
        requireProgram(properties);
        for (String entry : entries(properties)) {
            byte[] data = modules.get(entry);
            if (data.length < 20 || !hash(data).equals(properties.getProperty(entry + ".sha256"))) throw new IOException("Incomplete or changed shader module " + entry);
        }
        String identity = properties.getProperty("source");
        if (identity == null || !identity.matches("[0-9a-f]{64}")) throw new IOException("Missing shader source identity");
        String profile = properties.getProperty("profile", "rt_render_experiment-r2");
        if (!profile.matches("[a-z][a-z0-9_-]{0,47}")) throw new IOException("Invalid rendering profile");
        return new R2ShaderPackage(modules, identity, profile);
    }
    public boolean hasProducerIngress() { return modules.keySet().containsAll(R2Abi.ProducerDecode.ENTRIES); }
    private static List<String> entries(Properties properties) throws IOException {
        var entries = new java.util.ArrayList<>(ENTRIES);
        requireProgram(properties);
        String api = properties.getProperty("producerApi");
        {
            if (!Integer.toString(R2Abi.PRODUCER_API_VERSION).equals(api)
                || !R2Abi.PRODUCER_SCHEMA_SHA256.equals(properties.getProperty("producerSchema")))
                throw new IOException("Incompatible producer decoder generation");
            entries.addAll(R2Abi.ProducerDecode.ENTRIES);
        }
        return List.copyOf(entries);
    }
    @Override public String family() { return FAMILY; }
    @Override public String profile() { return profile; }
    @Override public String sourceIdentity() { return sourceIdentity; }
    @Override public String executionIdentity() { return executionIdentity; }
    @Override public String entry(Role role) {
        return switch (role) {
            case COMPILE_MATERIALS -> R2Abi.Materials.ENTRIES.getFirst();
            case COMPILE_ATMOSPHERE -> R2Abi.Atmosphere.ENTRIES.getFirst();
            case TEXTURE_COPY -> R2Abi.TextureCopy.ENTRIES.getFirst();
            case TEXTURE_COPY_FLOAT -> R2Abi.TextureCopyFloat.ENTRIES.getFirst();
        };
    }
    @Override public ByteBuffer module(String entry) {
        byte[] data = modules.get(entry);
        if (data == null) throw new IllegalArgumentException("Unknown R2 entry " + entry);
        return ByteBuffer.wrap(data).asReadOnlyBuffer();
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
