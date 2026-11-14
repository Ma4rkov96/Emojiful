package com.hrznstudio.emojiful.render;

import net.minecraft.resources.ResourceLocation;

/** A texture plus the UV rectangle of the frame to draw. */
public record EmojiRenderInfo(ResourceLocation texture, float u0, float v0, float u1, float v1) {

    public static EmojiRenderInfo full(ResourceLocation texture) {
        return new EmojiRenderInfo(texture, 0, 0, 1, 1);
    }
}
