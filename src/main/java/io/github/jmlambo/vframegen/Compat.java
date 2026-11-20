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
}
