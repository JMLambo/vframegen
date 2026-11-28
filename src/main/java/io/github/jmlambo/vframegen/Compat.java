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

    /**
     * True when a shader pack is drawing the world, whichever loader draws it (Iris on OpenGL,
     * Vitrail or Aetherium on Vulkan). Code that must step aside for a pack's own targets and passes asks this;
     * code that reads Iris' own textures keeps asking {@link #irisShadersActive()}.
     */
    public static boolean shaderPackActive() {
        return irisShadersActive() || vitrailShadersActive() || aetheriumShadersActive();
    }

    private static final boolean VITRAIL = FabricLoader.getInstance().isModLoaded("vitrail");
    private static MethodHandle vitrailDrawingPack;
    private static boolean vitrailLookupFailed;

    /**
     * True when Vitrail is drawing the world with a shader pack.
     *
     * <p>Vitrail replaces the level's one render pass with its own {@code LevelPass}, which steps
     * aside for the pack's passes and expects the game's own pass to stay where it is. VFrameGen's
     * deferred classic transparency opens a pass of its own around the game's translucent phase
     * (to keep the opaque depth); Vitrail then opens its pack pass inside that one and the game
     * stops with "Close the existing render pass before creating a new one". So with a pack,
     * classic transparency is left where it is, as with Iris.
     */
    public static boolean vitrailShadersActive() {
        if (!VITRAIL) return false;
        if (vitrailLookupFailed) return true; // cannot tell: assume it owns the pass, never crash
        try {
            if (vitrailDrawingPack == null) {
                Class<?> packChain = Class.forName("dev.vitrail.render.PackChain");
                vitrailDrawingPack = MethodHandles.publicLookup()
                        .findStatic(packChain, "drawingPack", MethodType.methodType(boolean.class));
            }
            return (boolean) vitrailDrawingPack.invoke();
        } catch (Throwable t) {
            vitrailLookupFailed = true;
            VFrameGen.LOGGER.warn("Vitrail is installed but its state could not be queried; "
                    + "assuming a shader pack is drawing", t);
            return true;
        }
    }

    private static final boolean AETHERIUM = FabricLoader.getInstance().isModLoaded("aetherium");
    private static MethodHandle aetheriumEngine;
    private static MethodHandle aetheriumWorldActive;
    private static boolean aetheriumLookupFailed;
    private static boolean aetheriumDrewFrame;

    /** A new frame: Aetherium has drawn nothing of it yet. */
    static void aetheriumFrameStart() {
        aetheriumDrewFrame = false;
    }

    /**
     * Aetherium is drawing this frame's world with a pack. It reports itself inactive again before
     * the held item and the finished image are captured, so this is remembered for the frame.
     */
    public static void aetheriumDrawing() {
        aetheriumDrewFrame = true;
    }

    /**
     * True when Aetherium is drawing the world with a shader pack.
     *
     * <p>Aetherium shades the opaque world as soon as the level's pass is closed and holds the
     * translucents back until after that itself. Entities VFrameGen holds back for a pass of its
     * own are therefore drawn before that shading (AetheriumEngineMixin: drawn after it, they
     * looked see-through), and the translucents are left to Aetherium.
     */
    public static boolean aetheriumShadersActive() {
        if (!AETHERIUM) return false;
        if (aetheriumDrewFrame) return true;
        if (aetheriumLookupFailed) return true; // cannot tell: assume it is drawing, move nothing
        try {
            if (aetheriumWorldActive == null) {
                Class<?> engine = Class.forName("dev.spacebod.aetherium.shaders.engine.ShaderPackEngine");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                aetheriumEngine = lookup.findStatic(engine, "get", MethodType.methodType(engine));
                aetheriumWorldActive = lookup.findVirtual(engine, "worldActive", MethodType.methodType(boolean.class));
            }
            return (boolean) aetheriumWorldActive.invoke(aetheriumEngine.invoke());
        } catch (Throwable t) {
            aetheriumLookupFailed = true;
            VFrameGen.LOGGER.warn("Aetherium is installed but its state could not be queried; "
                    + "assuming a shader pack is drawing", t);
            return true;
        }
    }

    private static java.lang.reflect.Method irisPipelineManager;
    private static java.lang.reflect.Method irisPipelineNullable;
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
            if (irisRenderTargets == null) {
                irisPipelineManager = Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager");
                irisPipelineNullable = Class.forName("net.irisshaders.iris.pipeline.PipelineManager").getMethod("getPipelineNullable");
                Class<?> irisPipeline = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
                irisRenderTargets = irisPipeline.getDeclaredField("renderTargets");
                irisRenderTargets.setAccessible(true);
                irisNoTranslucents = Class.forName("net.irisshaders.iris.targets.RenderTargets")
                        .getMethod("getDepthTextureNoTranslucents");
            }
            Object pipeline = irisPipelineNullable.invoke(irisPipelineManager.invoke(null));
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
