package io.github.jmlambo.vframegen;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Soft checks for other mods, without compiling against them. */
public final class Compat {
    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");
    private static MethodHandle irisInstance;
    private static MethodHandle irisShaderPackInUse;
    private static boolean irisLookupFailed;

    private Compat() {
    }

    /** True when an Iris shader pack is rendering the world. */
    public static boolean irisShadersActive() {
        if (!IRIS || irisLookupFailed) return false;
        try {
            if (irisInstance == null) {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                irisInstance = lookup.findStatic(api, "getInstance", MethodType.methodType(api));
                irisShaderPackInUse = lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class));
            }
            return (boolean) irisShaderPackInUse.invoke(irisInstance.invoke());
        } catch (Throwable t) {
            irisLookupFailed = true;
            VFrameGen.LOGGER.warn("Iris is installed but its API could not be queried; assuming no shader pack", t);
            return false;
        }
    }

    private static java.lang.reflect.Field irisRenderTargets;
    private static java.lang.reflect.Method irisNoTranslucents;
    private static boolean irisDepthLookupFailed;

    /**
     * The shader pack's depth from before translucents (what packs read as depthtex1), or null.
     * Iris internals, so looked up by reflection and given up on quietly if they change.
     */
    public static com.mojang.renderpearl.api.textures.GpuTexture irisDepthNoTranslucents() {
        if (!IRIS || irisDepthLookupFailed) return null;
        try {
            Object pipeline = Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
            pipeline = pipeline.getClass().getMethod("getPipelineNullable").invoke(pipeline);
            if (pipeline == null) return null;
            if (irisRenderTargets == null) {
                Class<?> irisPipeline = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
                irisRenderTargets = irisPipeline.getDeclaredField("renderTargets");
                irisRenderTargets.setAccessible(true);
                irisNoTranslucents = Class.forName("net.irisshaders.iris.targets.RenderTargets")
                        .getMethod("getDepthTextureNoTranslucents");
            }
            if (!irisRenderTargets.getDeclaringClass().isInstance(pipeline)) return null;
            Object targets = irisRenderTargets.get(pipeline);
            return targets == null ? null : (com.mojang.renderpearl.api.textures.GpuTexture) irisNoTranslucents.invoke(targets);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            irisDepthLookupFailed = true;
            VFrameGen.LOGGER.warn("Could not read Iris' depth before translucents; blocks behind water may shake with shaders", e);
            return null;
        }
    }
}
