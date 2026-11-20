package io.github.jmlambo.vframegen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/** Everything kept of one real frame: its images and the camera it was rendered with. */
final class FrameSlot {
    final String name;

    GpuTexture world, noHand, depth, handDepth, finalImage;
    GpuTextureView worldView, noHandView, depthView, handDepthView, finalView;

    /** Images and camera are complete (world captured and the final image copied). */
    boolean valid;
    boolean hasFinal;
    boolean hasHand;
    boolean firstPerson;
    final Vector3d pos = new Vector3d();
    final Matrix4f viewRot = new Matrix4f();
    final Matrix4f proj = new Matrix4f();
    long extractNs;
    ClientLevel level;

    FrameSlot(String name) {
        this.name = name;
    }

    void create(int w, int h, GpuFormat color, GpuFormat depthFormat) {
        release();
        GpuDevice device = RenderSystem.getDevice();
        int sampled = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC;
        world = device.createTexture("VFrameGen " + name + " world", sampled, color, w, h, 1, 1);
        noHand = device.createTexture("VFrameGen " + name + " world without hand", sampled, color, w, h, 1, 1);
        finalImage = device.createTexture("VFrameGen " + name + " final", sampled, color, w, h, 1, 1);
        depth = device.createTexture("VFrameGen " + name + " depth", sampled, depthFormat, w, h, 1, 1);
        handDepth = device.createTexture("VFrameGen " + name + " hand depth", sampled, depthFormat, w, h, 1, 1);
        worldView = device.createTextureView(world);
        noHandView = device.createTextureView(noHand);
        finalView = device.createTextureView(finalImage);
        depthView = device.createTextureView(depth);
        handDepthView = device.createTextureView(handDepth);
        valid = false;
        hasFinal = false;
    }

    void release() {
        for (GpuTextureView v : new GpuTextureView[]{worldView, noHandView, finalView, depthView, handDepthView}) {
            if (v != null) v.close();
        }
        for (GpuTexture t : new GpuTexture[]{world, noHand, finalImage, depth, handDepth}) {
            if (t != null) t.close();
        }
        worldView = noHandView = finalView = depthView = handDepthView = null;
        world = noHand = finalImage = depth = handDepth = null;
        valid = false;
        hasFinal = false;
    }

    boolean usable() {
        return valid && hasFinal && world != null;
    }

    Quaternionf rotation(Quaternionf out) {
        return viewRot.getNormalizedRotation(out);
    }
}
