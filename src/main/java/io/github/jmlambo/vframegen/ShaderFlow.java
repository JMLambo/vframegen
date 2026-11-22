package io.github.jmlambo.vframegen;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import java.util.Optional;

/**
 * Optical flow without optical flow hardware (experimental; AMD, Intel, or NVIDIA on OpenGL): a
 * coarse-to-fine block search in draw passes, in the spirit of FSR 3's optical flow (the game's
 * renderer has no compute shaders). Each real frame's world image becomes a brightness pyramid;
 * between it and the previous frame's, the motion of every 4x4 block is searched from the
 * coarsest level down, in both directions. The result goes into the same {@link OpticalFlow.Pair}
 * the hardware path fills, so reproject.fsh uses either the same way. Render thread only.
 */
public final class ShaderFlow {
    private static final int BLOCK = 4;
    // Measuring resolution: the world image halved, at most maxWidth wide. maxWidth follows the
    // GPU time the flow takes (see adapt): weak GPUs measure smaller.
    private static final int MAX_WIDTH = 960, MIN_WIDTH = 240, START_WIDTH = 640;
    private static final double SLOW_MS = 1.0, ROOMY_MS = 0.35;
    private static final int MIN_LEVEL_WIDTH = 48;
    private static int maxWidth = START_WIDTH;

    private static RenderPipeline lumaPipeline, downPipeline, matchPipeline, smoothPipeline;
    private static boolean broken;
    private static int failures;

    private static int worldW, worldH;
    private static int levels;
    private static int[] levelW, levelH;
    // Two brightness pyramids (this frame and the last), the flow of every level (reused by each
    // search), and one uniform buffer per level.
    private static final GpuTexture[][] pyramid = new GpuTexture[2][];
    private static final GpuTextureView[][] pyramidView = new GpuTextureView[2][];
    private static GpuTexture[] grid, rawGrid;          // per level: smoothed, and as searched
    private static GpuTextureView[] gridView, rawGridView;
    private static GpuBuffer[] ubo;
    private static int current = -1;
    private static GpuTexture lastWorld;

    private static final OpticalFlow.Pair[] pairs = {new OpticalFlow.Pair(), new OpticalFlow.Pair(), new OpticalFlow.Pair()};
    private static int nextPair;

    private ShaderFlow() {
    }

    // GPU time of the flow passes, from timestamp queries read back a few frames later.
    private static final java.util.ArrayDeque<GpuQueryPool> timers = new java.util.ArrayDeque<>();
    private static boolean timersUnsupported;
    private static long timedNs;
    private static int timedCount;

    private static void readTimers() {
        while (!timers.isEmpty()) {
            GpuQueryPool pool = timers.peek();
            var t0 = pool.getValue(0);
            var t1 = pool.getValue(1);
            if (t0.isEmpty() || t1.isEmpty()) return;
            timers.poll();
            pool.close();
            timedNs += Math.max(0, t1.getAsLong() - t0.getAsLong());
            if (++timedCount == 60) {
                adapt(timedNs / 60 / 1e6);
                timedNs = 0;
                timedCount = 0;
            }
        }
    }

    /** Every 60 measurements: measure smaller when the flow is slow, larger again when there is room. */
    private static void adapt(double ms) {
        int was = maxWidth;
        if (ms > SLOW_MS && maxWidth > MIN_WIDTH) {
            maxWidth = Math.max(MIN_WIDTH, maxWidth * 3 / 4);
        } else if (ms < ROOMY_MS && maxWidth < MAX_WIDTH && levelW != null && levelW[0] >= maxWidth) {
            // Only while the cap is what limits it (a small world image is measured whole anyway).
            maxWidth = Math.min(MAX_WIDTH, maxWidth * 5 / 4);
        }
        if (maxWidth != was) {
            OpticalFlow.LOGGER.info("Shader optical flow: {} ms per real frame, measuring up to {} px wide (was {})",
                    String.format(java.util.Locale.ROOT, "%.2f", ms), maxWidth, was);
            worldW = -1; // recreated at the next measurement
        }
    }

    public static boolean available() {
        return !broken;
    }

    /** The flow measured between these two world images (FrameSlot.noHand), or null. */
    public static OpticalFlow.Pair forPair(GpuTexture older, GpuTexture newer) {
        for (OpticalFlow.Pair p : pairs) if (p.valid && p.older == older && p.newer == newer) return p;
        return null;
    }

    /** Render thread, once a real frame's world image (without the hand) is captured. */
    public static void measure(GpuTexture world) {
        if (broken) return;
        try {
            measure0(world);
            failures = 0;
        } catch (RuntimeException e) {
            // Shaders are not loaded yet during startup and resource reloads: try again next frame.
            // If it keeps failing, it is off for good (the setting greys out).
            current = -1;
            if (++failures >= 600) {
                broken = true;
                OpticalFlow.LOGGER.error("Shader optical flow keeps failing; turned off", e);
            } else if (failures == 1) {
                OpticalFlow.LOGGER.debug("Shader optical flow not ready: {}", e.toString());
            }
        }
    }

    private static void measure0(GpuTexture world) {
        int w = world.getWidth(0), h = world.getHeight(0);
        if (w != worldW || h != worldH || grid == null) create(w, h);
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        int next = current < 0 ? 0 : 1 - current;
        readTimers();
        GpuQueryPool timer = null;
        if (!timersUnsupported && timers.size() < 8) {
            try {
                timer = device.createTimestampQueryPool(2);
                encoder.writeTimestamp(timer, 0);
            } catch (RuntimeException e) {
                if (timer != null) timer.close();
                timer = null;
                timersUnsupported = true; // then the resolution stays at its start value
            }
        }

        // Brightness pyramid of this frame.
        try (GpuTextureView src = device.createTextureView(world)) {
            pass(encoder, "VFrameGen flow luma", lumaPipeline, pyramidView[next][0], p -> p.setUniform("Src", src, linear));
        }
        for (int l = 1; l < levels; l++) {
            GpuTextureView src = pyramidView[next][l - 1];
            pass(encoder, "VFrameGen flow pyramid", downPipeline, pyramidView[next][l], p -> p.setUniform("Src", src, nearest));
        }

        for (OpticalFlow.Pair p : pairs) if (p.older == world || p.newer == world) p.valid = false;
        GpuTexture older = lastWorld;
        lastWorld = world;
        int prev = current;
        current = next;
        if (prev < 0 || older == null) return;

        OpticalFlow.Pair pair = pairs[nextPair];
        nextPair = (nextPair + 1) % pairs.length;
        pair.valid = false;
        int gw = levelW[0] / BLOCK, gh = levelH[0] / BLOCK;
        if (pair.forward == null || pair.forward.getWidth(0) != gw || pair.forward.getHeight(0) != gh) {
            if (pair.forward != null) {
                pair.forwardView.close();
                pair.backwardView.close();
                pair.forward.close();
                pair.backward.close();
            }
            pair.forward = flowTexture("forward", gw, gh);
            pair.backward = flowTexture("backward", gw, gh);
            pair.forwardView = device.createTextureView(pair.forward);
            pair.backwardView = device.createTextureView(pair.backward);
        }
        // Forward: for this frame's blocks, where they were in the last one; backward: the reverse.
        search(encoder, next, prev, pair.forwardView, linear, nearest);
        search(encoder, prev, next, pair.backwardView, linear, nearest);
        if (timer != null) {
            encoder.writeTimestamp(timer, 1);
            timers.add(timer);
        }
        pair.pixelsPerUnit = 1f;
        pair.width = w;
        pair.height = h;
        pair.older = older;
        pair.newer = world;
        pair.copied = true;
        pair.valid = true;
    }

    private static void search(CommandEncoder encoder, int cur, int ref, GpuTextureView out, GpuSampler linear, GpuSampler nearest) {
        for (int l = levels - 1; l >= 0; l--) {
            int level = l;
            GpuTextureView coarser = l + 1 < levels ? gridView[l + 1] : gridView[l]; // unread at the top
            pass(encoder, "VFrameGen flow search", matchPipeline, rawGridView[l], p -> {
                p.setUniform("Cur", pyramidView[cur][level], nearest);
                p.setUniform("Ref", pyramidView[ref][level], nearest);
                p.setUniform("Prev", coarser, linear);
                p.setUniform("FlowMatch", ubo[level]);
            });
            // Smoothed before the next level uses it as its prediction.
            GpuTextureView raw = rawGridView[l];
            pass(encoder, "VFrameGen flow smooth", smoothPipeline, l == 0 ? out : gridView[l], p -> p.setUniform("Src", raw, nearest));
        }
    }

    private interface Binder {
        void bind(RenderPass pass);
    }

    private static void pass(CommandEncoder encoder, String name, RenderPipeline pipeline, GpuTextureView target, Binder binder) {
        try (RenderPass pass = encoder.createRenderPass(() -> name, target, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            binder.bind(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static void create(int w, int h) {
        release();
        GpuDevice device = RenderSystem.getDevice();
        if (lumaPipeline == null) createPipelines();
        worldW = w;
        worldH = h;
        int w0 = Math.max(BLOCK * 8, Math.min(maxWidth, w / 2));
        int h0 = Math.max(BLOCK * 8, Math.round((float) h * w0 / w));
        int n = 1;
        while ((w0 >> n) >= MIN_LEVEL_WIDTH && (h0 >> n) >= BLOCK * 4) n++;
        levels = n;
        levelW = new int[n];
        levelH = new int[n];
        for (int l = 0; l < n; l++) {
            levelW[l] = Math.max(BLOCK, w0 >> l);
            levelH[l] = Math.max(BLOCK, h0 >> l);
        }
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        for (int f = 0; f < 2; f++) {
            pyramid[f] = new GpuTexture[n];
            pyramidView[f] = new GpuTextureView[n];
            for (int l = 0; l < n; l++) {
                pyramid[f][l] = device.createTexture("VFrameGen flow brightness", usage, GpuFormat.R8_UNORM, levelW[l], levelH[l], 1, 1);
                pyramidView[f][l] = device.createTextureView(pyramid[f][l]);
            }
        }
        grid = new GpuTexture[n];
        gridView = new GpuTextureView[n];
        rawGrid = new GpuTexture[n];
        rawGridView = new GpuTextureView[n];
        ubo = new GpuBuffer[n];
        for (int l = 0; l < n; l++) {
            grid[l] = device.createTexture("VFrameGen flow level", usage | GpuTexture.USAGE_COPY_SRC, GpuFormat.RG16_FLOAT,
                    Math.max(1, levelW[l] / BLOCK), Math.max(1, levelH[l] / BLOCK), 1, 1);
            gridView[l] = device.createTextureView(grid[l]);
            rawGrid[l] = device.createTexture("VFrameGen flow level (searched)", usage, GpuFormat.RG16_FLOAT,
                    Math.max(1, levelW[l] / BLOCK), Math.max(1, levelH[l] / BLOCK), 1, 1);
            rawGridView[l] = device.createTextureView(rawGrid[l]);
            ubo[l] = device.createBuffer(() -> "VFrameGen flow search uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
            boolean top = l == n - 1;
            // The coarsest level searches widest (it has no prediction); the finest writes
            // measured-image (world) pixels.
            float scale = l == 0 ? (float) w / levelW[0] : 1f;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                device.createCommandEncoder().writeToBuffer(ubo[l].slice(),
                        Std140Builder.onStack(stack, 16).putVec4(top ? 0f : 1f, top ? 4f : 2f, scale, 0f).get());
            }
        }
        current = -1;
        lastWorld = null;
        for (OpticalFlow.Pair p : pairs) p.valid = false;
        OpticalFlow.LOGGER.info("Shader optical flow: measuring {}x{} at {}x{}, {} levels, {}x{} vectors",
                w, h, levelW[0], levelH[0], n, levelW[0] / BLOCK, levelH[0] / BLOCK);
    }

    private static GpuTexture flowTexture(String name, int w, int h) {
        return RenderSystem.getDevice().createTexture("VFrameGen shader flow " + name,
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RG16_FLOAT, w, h, 1, 1);
    }

    private static void createPipelines() {
        lumaPipeline = pipeline("flow_luma", GpuFormat.R8_UNORM, BindGroupLayout.builder()
                .withUniform("Src", UniformType.COMBINED_IMAGE_SAMPLER).build());
        downPipeline = pipeline("flow_down", GpuFormat.R8_UNORM, BindGroupLayout.builder()
                .withUniform("Src", UniformType.COMBINED_IMAGE_SAMPLER).build());
        matchPipeline = pipeline("flow_match", GpuFormat.RG16_FLOAT, BindGroupLayout.builder()
                .withUniform("Cur", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("Ref", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("Prev", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("FlowMatch", UniformType.UNIFORM_BUFFER).build());
        smoothPipeline = pipeline("flow_smooth", GpuFormat.RG16_FLOAT, BindGroupLayout.builder()
                .withUniform("Src", UniformType.COMBINED_IMAGE_SAMPLER).build());
    }

    private static RenderPipeline pipeline(String shader, GpuFormat format, BindGroupLayout layout) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "pipeline/" + shader))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath(VFrameGen.MOD_ID, "core/" + shader))
                .withBindGroupLayout(layout)
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build();
    }

    private static void release() {
        for (int f = 0; f < 2; f++) {
            if (pyramid[f] == null) continue;
            for (GpuTextureView v : pyramidView[f]) v.close();
            for (GpuTexture t : pyramid[f]) t.close();
            pyramid[f] = null;
            pyramidView[f] = null;
        }
        if (grid != null) {
            for (GpuTextureView v : gridView) v.close();
            for (GpuTexture t : grid) t.close();
            for (GpuTextureView v : rawGridView) v.close();
            for (GpuTexture t : rawGrid) t.close();
            for (GpuBuffer b : ubo) b.close();
        }
        grid = rawGrid = null;
        gridView = rawGridView = null;
        ubo = null;
    }
}
