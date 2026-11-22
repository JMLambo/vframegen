package io.github.jmlambo.vframegen;

/** LevelRenderer (LevelRendererMixin): draws the entities held back from the terrain's pass, if any. */
public interface DeferredSolidAccess {
    void vframegen$runDeferredSolid();
}
