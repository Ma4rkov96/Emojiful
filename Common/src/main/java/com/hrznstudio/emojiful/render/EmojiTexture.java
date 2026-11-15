package com.hrznstudio.emojiful.render;

import com.hrznstudio.emojiful.image.EmojiAtlas;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;

/**
 * One GL texture holding every frame of an emoji (see {@link EmojiAtlas}), with mipmaps.
 * <p>
 * The pixel data is converted to native images on the loader thread (constructor), so the render
 * thread only performs the GL upload. The native images are kept so the texture survives resource
 * reloads (F3+T), which call {@link #load} again, and are freed in {@link #close}.
 */
public class EmojiTexture extends AbstractTexture {

    private final int width;
    private final int height;
    private NativeImage[] levels;

    /** Safe to call off the render thread. */
    public EmojiTexture(EmojiAtlas atlas) {
        this.width = atlas.width;
        this.height = atlas.height;
        this.levels = new NativeImage[atlas.levelCount];
        for (int level = 0; level < atlas.levelCount; level++) {
            int w = width >> level;
            int h = height >> level;
            int[] argb = atlas.levels[level];
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = argb[y * w + x];
                    // NativeImage stores ABGR
                    image.setPixelRGBA(x, y, (p & 0xFF00FF00) | (p >> 16 & 0xFF) | (p & 0xFF) << 16);
                }
            }
            levels[level] = image;
        }
    }

    @Override
    public void load(ResourceManager resourceManager) {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(this::upload);
        } else {
            upload();
        }
    }

    private void upload() {
        NativeImage[] images = levels;
        if (images == null) return;
        TextureUtil.prepareImage(getId(), images.length - 1, width, height);
        for (int level = 0; level < images.length; level++) {
            images[level].upload(level, 0, 0, 0, 0, width >> level, height >> level, false, true, true, false);
        }
        applyFilter();
    }

    /**
     * Render types call this before drawing; always trilinear. In chat the texture is sampled 1:1 at the
     * GUI level of the atlas, so this filtering is exact there; it only matters for signs and name tags.
     * Pixel art is already enlarged with hard pixels on the CPU, so linear magnification stays crisp.
     */
    @Override
    public void setFilter(boolean blur, boolean mipmap) {
        RenderSystem.assertOnRenderThreadOrInit();
        bind();
        applyFilter();
    }

    private static void applyFilter() {
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    }

    @Override
    public void close() {
        NativeImage[] images = levels;
        levels = null;
        if (images != null) {
            for (NativeImage image : images) image.close();
        }
        releaseId();
    }
}
