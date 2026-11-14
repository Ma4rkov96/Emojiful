package com.hrznstudio.emojiful.mixin;

import com.hrznstudio.emojiful.api.Emoji;
import com.hrznstudio.emojiful.render.EmojiFontHelper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.concurrent.ExecutionException;


@Mixin(Font.class)
public class FontRendererMixin {

    @ModifyVariable(
            method = "split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;",
            at = @At("HEAD"),
            argsOnly = true
    )
    private FormattedText emojiful$prepareTextForWrapping(FormattedText text) {
        return EmojiFontHelper.prepareForWrapping(text);
    }

    /** Glowing sign text: 8 outline passes (emojis skipped, as vanilla skips nothing but draws them in one colour) and a main pass. */
    @Inject(method = "drawInBatch8xOutline", at = @At("HEAD"), cancellable = true)
    private void drawInBatch8xOutline(FormattedCharSequence sequence, float x, float y, int color, int outlineColor, Matrix4f matrix, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (sequence == null) return;
        EmojiFontHelper.ParsedSequence parsed = EmojiFontHelper.parseSequence(sequence);
        if (!parsed.customRender()) return;

        int outline = adjustColor(outlineColor);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                EmojiFontHelper.EmojiCharacterRenderer pass = new EmojiFontHelper.EmojiCharacterRenderer(parsed.emojis(), buffer, x, y, outline, false, matrix, Font.DisplayMode.NORMAL, packedLight).outline(dx, dy);
                parsed.sequence().accept(pass);
            }
        }
        EmojiFontHelper.EmojiCharacterRenderer main = new EmojiFontHelper.EmojiCharacterRenderer(parsed.emojis(), buffer, x, y, adjustColor(color), false, matrix, Font.DisplayMode.POLYGON_OFFSET, packedLight);
        parsed.sequence().accept(main);
        main.finish(0, x);
        ci.cancel();
    }

    @Inject(method = "renderText(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)F", at = @At("HEAD"), cancellable = true)
    private void renderText(String text, float x, float y, int color, boolean isShadow, Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode displayMode, int colorBackgroundIn, int packedLight, CallbackInfoReturnable<Float> cir) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Pair<String, HashMap<Integer, Emoji>> parsed;
        try {
            parsed = EmojiFontHelper.RECENT_STRINGS.get(text);
        } catch (ExecutionException e) {
            return;
        }
        String shown = parsed.getLeft();
        if (!parsed.getRight().isEmpty() || shown.startsWith(EmojiFontHelper.SCAPED_STRING)) {
            if (shown.startsWith(EmojiFontHelper.SCAPED_STRING)) shown = shown.substring(EmojiFontHelper.SCAPED_STRING.length());
            EmojiFontHelper.EmojiCharacterRenderer renderer = new EmojiFontHelper.EmojiCharacterRenderer(parsed.getRight(), buffer, x, y, color, isShadow, matrix, displayMode, packedLight);
            StringDecomposer.iterateFormatted(shown, Style.EMPTY, renderer);
            cir.setReturnValue(renderer.finish(colorBackgroundIn, x));
        }
    }

    @Inject(method = "drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I", at = @At("HEAD"), cancellable = true)
    public void drawInBatch(FormattedCharSequence sequence, float x, float y, int color, boolean isShadow, Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode displayMode, int colorBackgroundIn, int packedLight, CallbackInfoReturnable<Integer> cir) {
        if (sequence == null) return;
        EmojiFontHelper.ParsedSequence parsed = EmojiFontHelper.parseSequence(sequence);
        if (!parsed.customRender()) return;

        color = adjustColor(color);
        Matrix4f matrix4f = new Matrix4f(matrix);
        if (isShadow) {
            EmojiFontHelper.EmojiCharacterRenderer shadow = new EmojiFontHelper.EmojiCharacterRenderer(parsed.emojis(), buffer, x, y, color, true, matrix4f, displayMode, packedLight);
            parsed.sequence().accept(shadow);
            shadow.finish(colorBackgroundIn, x);
            matrix4f.translate(EmojiFontHelper.SHADOW_OFFSET);
        }
        EmojiFontHelper.EmojiCharacterRenderer renderer = new EmojiFontHelper.EmojiCharacterRenderer(parsed.emojis(), buffer, x, y, color, false, matrix4f, displayMode, packedLight);
        parsed.sequence().accept(renderer);
        cir.setReturnValue((int) renderer.finish(colorBackgroundIn, x) + (isShadow ? 1 : 0));
    }

    @Inject(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private void widthString(String text, CallbackInfoReturnable<Integer> cir) {
        if (!EmojiFontHelper.BYPASS.get()) {
            cir.setReturnValue(EmojiFontHelper.width((Font) (Object) this, text));
        }
    }

    @Inject(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I", at = @At("HEAD"), cancellable = true)
    private void widthFormattedCharSeq(FormattedCharSequence processor, CallbackInfoReturnable<Integer> cir) {
        if (!EmojiFontHelper.BYPASS.get()) {
            cir.setReturnValue(EmojiFontHelper.width((Font) (Object) this, processor));
        }
    }

    @Inject(method = "width(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("HEAD"), cancellable = true)
    private void widthFormattedText(FormattedText formattedText, CallbackInfoReturnable<Integer> cir) {
        if (!EmojiFontHelper.BYPASS.get()) {
            cir.setReturnValue(EmojiFontHelper.width((Font) (Object) this, formattedText));
        }
    }

    private static int adjustColor(int color) {
        return (color & 0xFC000000) == 0 ? color | 0xFF000000 : color;
    }
}
