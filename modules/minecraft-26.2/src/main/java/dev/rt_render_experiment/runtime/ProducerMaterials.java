package dev.rt_render_experiment.runtime;


public final class ProducerMaterials {
    private static final ContentResolver RESOLVER=new ContentResolver(MaterialManifest.load());
    private ProducerMaterials() {}
    public static int block(String block,String sprite) { return RESOLVER.blockSurfaceMaterialId(block,sprite); }
    public static int block(String block) { return RESOLVER.blockMaterialId(block); }
    public static int fluid(String fluid) { return RESOLVER.fluidMaterialId(fluid); }
    public static int sprite(String sprite) { return RESOLVER.blockSurfaceMaterialId("rt_render_experiment:unclassified",sprite); }
}
