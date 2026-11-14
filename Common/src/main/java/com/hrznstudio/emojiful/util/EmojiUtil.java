package com.hrznstudio.emojiful.util;

import com.hrznstudio.emojiful.api.Emoji;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import com.hrznstudio.emojiful.render.EmojiRenderInfo;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import com.hrznstudio.emojiful.render.EmojiFontHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.GlyphRenderTypes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class EmojiUtil extends RenderType {

    private EmojiUtil(String string, VertexFormat vertexFormat, VertexFormat.Mode mode, int i, boolean bl, boolean bl2, Runnable runnable, Runnable runnable2) {
        super(string, vertexFormat, mode, i, bl, bl2, runnable, runnable2);
    }

    private static final Map<ResourceLocation, GlyphRenderTypes> RENDER_TYPES = new ConcurrentHashMap<>();

    /**
     * Emojis use exactly the render types of vanilla text with their own texture, so they share depth
     * behaviour with the text around them: normal GUI/world text, see-through name tags, and the
     * polygon offset used by signs and text displays (prevents z-fighting with the sign board).
     */
    public static RenderType renderType(ResourceLocation texture, Font.DisplayMode mode) {
        return RENDER_TYPES.computeIfAbsent(texture, GlyphRenderTypes::createForColorTexture).select(mode);
    }

    public static RenderType renderType(ResourceLocation texture) {
        return renderType(texture, Font.DisplayMode.NORMAL);
    }

    /** Kept for API compatibility. */
    public static RenderType createRenderType(Emoji emoji) {
        return renderType(emoji.getRenderInfo().texture());
    }

    public static float renderEmoji(Emoji emoji, float x, float y, Matrix4f matrix, MultiBufferSource buffer, int packedLight) {
        return renderEmoji(emoji, x, y, matrix, buffer, packedLight, 1.0F, Font.DisplayMode.NORMAL);
    }

    /**
     * @param alpha text alpha, so emojis fade out with chat text and are faint in the see-through name tag pass
     */
    public static float renderEmoji(Emoji emoji, float x, float y, Matrix4f matrix, MultiBufferSource buffer, int packedLight, float alpha, Font.DisplayMode mode) {
        float size = EmojiFontHelper.EMOJI_ADVANCE;
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        if (a <= 3) return size;
        EmojiRenderInfo info = emoji.getRenderInfo();
        float top = y - 1.0F;
        float bottom = top + size;

        VertexConsumer builder = buffer.getBuffer(renderType(info.texture(), mode));
        builder.addVertex(matrix, x, top, 0.0F).setColor(255, 255, 255, a).setUv(info.u0(), info.v0()).setLight(packedLight);
        builder.addVertex(matrix, x, bottom, 0.0F).setColor(255, 255, 255, a).setUv(info.u0(), info.v1()).setLight(packedLight);
        builder.addVertex(matrix, x + size, bottom, 0.0F).setColor(255, 255, 255, a).setUv(info.u1(), info.v1()).setLight(packedLight);
        builder.addVertex(matrix, x + size, top, 0.0F).setColor(255, 255, 255, a).setUv(info.u1(), info.v0()).setLight(packedLight);
        return size;
    }

    /** Escapes a literal string for use inside a regular expression. */
    public static String cleanStringForRegex(String string) {
        return Pattern.quote(string);
    }

}
