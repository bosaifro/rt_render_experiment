package dev.rt_render_experiment.engine.abi;
import java.util.List;
import dev.rt_render_experiment.vulkan.VulkanDescriptors;
public final class R2Resources {
    private R2Resources() {}
    public static List<VulkanDescriptors.Binding> transport(int stage) {
        return List.of(
            VulkanDescriptors.uniform("Frame", stage),
            VulkanDescriptors.accelerationStructure("WorldAS", stage),
            VulkanDescriptors.storageBuffer("Hits", stage),
            VulkanDescriptors.storageBuffer("Signals", stage),
            VulkanDescriptors.storageBuffer("Guides", stage),
            VulkanDescriptors.storageBuffer("HostDepth", stage),
            VulkanDescriptors.storageBuffer("Output", stage),
            VulkanDescriptors.storageBuffer("PreviousLightHistory", stage),
            VulkanDescriptors.storageBuffer("CurrentLightHistory", stage));
    }
    public static List<VulkanDescriptors.Binding> materials(int stage) {
        return List.of(
            VulkanDescriptors.storageBuffer("Definitions", stage));
    }
    public static List<VulkanDescriptors.Binding> atmosphere(int stage) {
        return List.of(
            VulkanDescriptors.storageBuffer("Cells", stage));
    }
    public static List<VulkanDescriptors.Binding> textureCopy(int stage) {
        return List.of(
            VulkanDescriptors.uniform("Copy", stage),
            VulkanDescriptors.sampler("Source", stage),
            VulkanDescriptors.storageBuffer("Pixels", stage));
    }
    public static List<VulkanDescriptors.Binding> textureCopyFloat(int stage) {
        return List.of(
            VulkanDescriptors.uniform("Copy", stage),
            VulkanDescriptors.sampler("Source", stage),
            VulkanDescriptors.storageBuffer("Pixels", stage));
    }
    public static List<VulkanDescriptors.Binding> reconstruction(int stage) {
        return List.of(
            VulkanDescriptors.uniform("Frame", stage),
            VulkanDescriptors.storageBuffer("Signals", stage),
            VulkanDescriptors.storageBuffer("Guides", stage),
            VulkanDescriptors.storageBuffer("Previous", stage),
            VulkanDescriptors.storageBuffer("History", stage),
            VulkanDescriptors.storageBuffer("Output", stage));
    }
    public static List<VulkanDescriptors.Binding> presentation(int stage) {
        return List.of(
            VulkanDescriptors.uniform("Frame", stage),
            VulkanDescriptors.storageBuffer("Composite", stage),
            VulkanDescriptors.storageBuffer("PreviousExposure", stage),
            VulkanDescriptors.storageBuffer("Exposure", stage),
            VulkanDescriptors.storageBuffer("DisplayOutput", stage));
    }
    public static List<VulkanDescriptors.Binding> handoff(int stage) {
        return List.of(
            VulkanDescriptors.uniform("Frame", stage),
            VulkanDescriptors.storageBuffer("Color", stage),
            VulkanDescriptors.storageBuffer("HostDepth", stage),
            VulkanDescriptors.storageBuffer("Guides", stage));
    }
    public static List<VulkanDescriptors.Binding> resolve(int stage) {
        return List.of(
            VulkanDescriptors.uniform("Frame", stage),
            VulkanDescriptors.storageBuffer("Input", stage),
            VulkanDescriptors.storageBuffer("Output", stage));
    }
    public static List<VulkanDescriptors.Binding> importResources(int stage) {
        return List.of(
            VulkanDescriptors.uniform("Frame", stage),
            VulkanDescriptors.storageImage("Composition", stage),
            VulkanDescriptors.storageBuffer("Color", stage));
    }
    public static List<VulkanDescriptors.Binding> sourcePreparation(int stage) {
        return List.of(
            VulkanDescriptors.uniform("Frame", stage));
    }
    public static List<VulkanDescriptors.Binding> producerDecode(int stage) {
        return List.of(
            VulkanDescriptors.uniform("Decode", stage),
            VulkanDescriptors.storageBuffer("Source", stage),
            VulkanDescriptors.storageBuffer("Tint", stage),
            VulkanDescriptors.storageBuffer("Facts", stage),
            VulkanDescriptors.storageBuffer("Surfaces", stage),
            VulkanDescriptors.storageBuffer("Positions", stage),
            VulkanDescriptors.storageBuffer("Corners", stage),
            VulkanDescriptors.storageBuffer("Indices", stage),
            VulkanDescriptors.storageBuffer("Triangles", stage));
    }
    public static List<VulkanDescriptors.Binding> forEntry(String entry, int stage) {
        return switch (entry) {
            case "r2PrimaryVisibility" -> transport(stage);
            case "r2CompileMaterials" -> materials(stage);
            case "r2CompileAtmosphere" -> atmosphere(stage);
            case "r2TextureCopy" -> textureCopy(stage);
            case "r2TextureCopyFloat" -> textureCopyFloat(stage);
            case "r2Temporal", "r2Atrous" -> reconstruction(stage);
            case "r2Meter", "r2Display" -> presentation(stage);
            case "r2HandoffVertex", "r2WorldHandoff", "r2DisplayHandoff", "r2ViewmodelHandoff" -> handoff(stage);
            case "r2Resolve" -> resolve(stage);
            case "r2Import" -> importResources(stage);
            case "r2PrepareSources" -> sourcePreparation(stage);
            case "r2DecodeProducer" -> producerDecode(stage);
            default -> throw new IllegalArgumentException("Unknown R2 entry " + entry);
        };
    }
    public static int workgroupX(String entry) {
        return switch (entry) {
            case "r2PrimaryVisibility" -> 8;
            case "r2CompileMaterials" -> 64;
            case "r2CompileAtmosphere" -> 64;
            case "r2TextureCopy" -> 8;
            case "r2TextureCopyFloat" -> 8;
            case "r2Temporal" -> 8;
            case "r2Atrous" -> 8;
            case "r2Meter" -> 256;
            case "r2Display" -> 8;
            case "r2Resolve" -> 8;
            case "r2Import" -> 8;
            case "r2PrepareSources" -> 64;
            case "r2DecodeProducer" -> 64;
            default -> throw new IllegalArgumentException("Not a compute entry " + entry);
        };
    }
    public static int workgroupY(String entry) {
        return switch (entry) {
            case "r2PrimaryVisibility" -> 8;
            case "r2CompileMaterials" -> 1;
            case "r2CompileAtmosphere" -> 1;
            case "r2TextureCopy" -> 8;
            case "r2TextureCopyFloat" -> 8;
            case "r2Temporal" -> 8;
            case "r2Atrous" -> 8;
            case "r2Meter" -> 1;
            case "r2Display" -> 8;
            case "r2Resolve" -> 8;
            case "r2Import" -> 8;
            case "r2PrepareSources" -> 1;
            case "r2DecodeProducer" -> 1;
            default -> throw new IllegalArgumentException("Not a compute entry " + entry);
        };
    }
    public static int workgroupZ(String entry) {
        return switch (entry) {
            case "r2PrimaryVisibility" -> 1;
            case "r2CompileMaterials" -> 1;
            case "r2CompileAtmosphere" -> 1;
            case "r2TextureCopy" -> 1;
            case "r2TextureCopyFloat" -> 1;
            case "r2Temporal" -> 1;
            case "r2Atrous" -> 1;
            case "r2Meter" -> 1;
            case "r2Display" -> 1;
            case "r2Resolve" -> 1;
            case "r2Import" -> 1;
            case "r2PrepareSources" -> 1;
            case "r2DecodeProducer" -> 1;
            default -> throw new IllegalArgumentException("Not a compute entry " + entry);
        };
    }
}
