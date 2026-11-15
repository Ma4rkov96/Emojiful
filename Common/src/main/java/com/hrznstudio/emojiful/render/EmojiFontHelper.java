package com.hrznstudio.emojiful.render;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.hrznstudio.emojiful.Constants;
import com.hrznstudio.emojiful.api.Emoji;
import com.hrznstudio.emojiful.platform.Services;
import com.hrznstudio.emojiful.util.EmojiUtil;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Finds emoji codes in text and renders them.
 * <p>
 * Performance notes (this runs for every piece of text the game draws, every frame: GUI, chat,
 * F3, the player list, name tags, signs and text displays):
 * <ul>
 *     <li>Emoji codes are located by {@link EmojiMatcher} in one pass instead of ~2300 regular
 *     expressions per string.</li>
 *     <li>Results are cached by text content (bounded caches) and by sequence identity, so stable
 *     text such as chat lines and signs costs almost nothing per frame.</li>
 *     <li>Text without emojis returns the original sequence untouched and allocates nothing beyond
 *     the content key.</li>
 * </ul>
 */
public class EmojiFontHelper {

    public static final Vector3f SHADOW_OFFSET = new Vector3f(0.0F, 0.0F, 0.03F);
    public static final int PLACEHOLDER = 0x2603;
    public static final String PLACEHOLDER_STRING = new String(Character.toChars(PLACEHOLDER));
    public static final float EMOJI_ADVANCE = 10.0F;
    public static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    public static String SCAPED_STRING = "\\\\\\\\";

    private static final int CACHE_SIZE = 8192;
    private static final Constructor<Style> STYLE_CONSTRUCTOR = findStyleConstructor();
    private static final LinkedHashMap<Integer, Emoji> NO_EMOJIS = new LinkedHashMap<>();

    private static volatile EmojiMatcher matcher;

    /** Legacy string API: text with emoji codes replaced by a placeholder, and placeholder index -> emoji. */
    public static LoadingCache<String, Pair<String, HashMap<Integer, Emoji>>> RECENT_STRINGS = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterAccess(60, TimeUnit.SECONDS)
            .build(new CacheLoader<>() {
                @Override
                public Pair<String, HashMap<Integer, Emoji>> load(String key) {
                    return getEmojiFormattedString(key);
                }
            });

    /** Emoji positions per plain text content. */
    private static final LoadingCache<String, List<EmojiMatcher.Match>> MATCHES = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterAccess(60, TimeUnit.SECONDS)
            .build(new CacheLoader<>() {
                @Override
                public List<EmojiMatcher.Match> load(String key) {
                    return Services.CONFIG.renderEmoji() && !key.startsWith(SCAPED_STRING) ? matcher().findAll(key) : Collections.emptyList();
                }
            });

    /** Parse results per sequence object (weak, identity-keyed): chat lines, signs and text displays are reused every frame. */
    private static final Cache<FormattedCharSequence, ParsedSequence> PARSED = CacheBuilder.newBuilder()
            .weakKeys()
            .maximumSize(CACHE_SIZE)
            .build();

    /** Marker style -> emoji for placeholders produced by {@link #prepareForWrapping} (weak, identity-keyed). */
    private static final Cache<Style, Emoji> WRAPPED_EMOJIS = CacheBuilder.newBuilder().weakKeys().build();

    /** One marker style per (style, emoji), so wrapping the same text again does not create new markers. */
    private static final Cache<MarkerKey, Style> MARKERS = CacheBuilder.newBuilder().maximumSize(CACHE_SIZE).build();

    private record MarkerKey(Style style, Emoji emoji) {
    }

    public EmojiFontHelper() {
    }

    // ------------------------------------------------------------------------------------------------ matching

    private static EmojiMatcher matcher() {
        EmojiMatcher current = matcher;
        if (current == null) {
            current = rebuildMatcher();
        }
        return current;
    }

    private static synchronized EmojiMatcher rebuildMatcher() {
        List<Emoji> snapshot;
        // the loader thread and the recipe sync change the list under this lock
        synchronized (Constants.EMOJI_LIST) {
            snapshot = new ArrayList<>(Constants.EMOJI_LIST);
        }
        EmojiMatcher built = EmojiMatcher.build(snapshot);
        matcher = built;
        return built;
    }

    public static List<EmojiMatcher.Match> findEmojis(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        try {
            return MATCHES.get(text);
        } catch (ExecutionException | RuntimeException exception) {
            Constants.LOG.error("Could not parse emoji text", exception);
            return Collections.emptyList();
        }
    }

    /**
     * Legacy string form: every emoji code is replaced by {@link #PLACEHOLDER}; the map is keyed by the
     * placeholder's index in the returned string (the same index {@link StringDecomposer#iterateFormatted}
     * reports).
     */
    public static Pair<String, HashMap<Integer, Emoji>> getEmojiFormattedString(String text) {
        HashMap<Integer, Emoji> emojis = new LinkedHashMap<>();
        if (text == null || text.isEmpty() || !Services.CONFIG.renderEmoji() || text.startsWith(SCAPED_STRING)) {
            return Pair.of(text, emojis);
        }
        List<EmojiMatcher.Match> matches = findEmojis(text);
        if (matches.isEmpty()) {
            return Pair.of(text, emojis);
        }
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (EmojiMatcher.Match match : matches) {
            out.append(text, cursor, match.start());
            emojis.put(out.length(), match.emoji());
            out.append(PLACEHOLDER_STRING);
            cursor = match.end();
        }
        out.append(text, cursor, text.length());
        return Pair.of(out.toString(), emojis);
    }

    // ------------------------------------------------------------------------------------------------ sequences

    public static ParsedSequence parseSequence(FormattedCharSequence sequence) {
        ParsedSequence cached = PARSED.getIfPresent(sequence);
        if (cached != null) return cached;
        ParsedSequence parsed = parseUncached(sequence);
        PARSED.put(sequence, parsed);
        return parsed;
    }

    private static ParsedSequence parseUncached(FormattedCharSequence sequence) {
        // Pass 1: plain text only (cheap); most text in the game has no emojis at all.
        StringBuilder plain = new StringBuilder();
        boolean[] hasPlaceholder = {false};
        sequence.accept((position, style, codePoint) -> {
            plain.appendCodePoint(codePoint);
            if (codePoint == PLACEHOLDER && wrappedEmoji(style) != null) hasPlaceholder[0] = true;
            return true;
        });
        String source = plain.toString();

        if (source.startsWith(SCAPED_STRING)) {
            return new ParsedSequence(FormattedCharSequence.forward(source.substring(SCAPED_STRING.length()), Style.EMPTY), NO_EMOJIS, true);
        }
        List<EmojiMatcher.Match> matches = findEmojis(source);
        if (matches.isEmpty()) {
            // Placeholders from prepareForWrapping are recognised by their marker style while drawing.
            return new ParsedSequence(sequence, NO_EMOJIS, hasPlaceholder[0]);
        }

        // Pass 2: rebuild the sequence with each emoji code collapsed into one placeholder.
        int count = source.codePointCount(0, source.length());
        int[] codePoints = new int[count];
        Style[] styles = new Style[count];
        int[] offsets = new int[count];
        int[] index = {0};
        int[] offset = {0};
        sequence.accept((position, style, codePoint) -> {
            int i = index[0]++;
            codePoints[i] = codePoint;
            styles[i] = style;
            offsets[i] = offset[0];
            offset[0] += Character.charCount(codePoint);
            return true;
        });

        int[] outCodePoints = new int[count];
        Style[] outStyles = new Style[count];
        int[] outPositions = new int[count];
        LinkedHashMap<Integer, Emoji> emojis = new LinkedHashMap<>();
        int out = 0;
        int outputOffset = 0;
        int matchIndex = 0;
        int i = 0;
        while (i < count) {
            EmojiMatcher.Match match = matchIndex < matches.size() ? matches.get(matchIndex) : null;
            if (match != null && offsets[i] >= match.start()) {
                outCodePoints[out] = PLACEHOLDER;
                outStyles[out] = styles[i];
                outPositions[out] = outputOffset;
                emojis.put(outputOffset, match.emoji());
                out++;
                outputOffset++;
                while (i < count && offsets[i] < match.end()) i++;
                matchIndex++;
            } else {
                outCodePoints[out] = codePoints[i];
                outStyles[out] = styles[i];
                outPositions[out] = outputOffset;
                outputOffset += Character.charCount(codePoints[i]);
                out++;
                i++;
            }
        }
        return new ParsedSequence(new StyledText(Arrays.copyOf(outCodePoints, out), Arrays.copyOf(outStyles, out), Arrays.copyOf(outPositions, out)), emojis, true);
    }

    /** Compact replacement for a list of per-character sequences. */
    private record StyledText(int[] codePoints, Style[] styles, int[] positions) implements FormattedCharSequence {
        @Override
        public boolean accept(FormattedCharSink sink) {
            for (int i = 0; i < codePoints.length; i++) {
                if (!sink.accept(positions[i], styles[i], codePoints[i])) return false;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------------ wrapping

    /**
     * Before text is split into lines, each emoji code becomes one placeholder character carrying a
     * marker style, so line breaking measures it as {@link #EMOJI_ADVANCE} wide (see StringSplitterMixin).
     */
    public static FormattedText prepareForWrapping(FormattedText text) {
        if (text == null) return null;
        String whole = text.getString();
        if (whole.startsWith(SCAPED_STRING)) {
            return text;
        }
        // Matched on the whole text, like rendering does, so codes split over several components (gradient or
        // rainbow text with one component per character) are measured and wrapped as one emoji too.
        List<EmojiMatcher.Match> matches = findEmojis(whole);
        if (matches.isEmpty()) {
            return text;
        }
        List<FormattedText> parts = new ArrayList<>();
        int[] state = {0, 0}; // offset of the current segment in the whole text, index of the next match
        text.visit((style, contents) -> {
            if (contents != null) {
                addWrappedParts(parts, contents, style, state, matches);
                state[0] += contents.length();
            }
            return Optional.empty();
        }, Style.EMPTY);
        return FormattedText.composite(parts);
    }

    private static void addWrappedParts(List<FormattedText> parts, String contents, Style style, int[] state, List<EmojiMatcher.Match> matches) {
        int base = state[0];
        int length = contents.length();
        int cursor = 0;
        while (cursor < length) {
            EmojiMatcher.Match match = state[1] < matches.size() ? matches.get(state[1]) : null;
            int start = match == null ? length : Math.min(length, match.start() - base);
            if (start < cursor) {
                // the rest of an emoji whose placeholder was added in an earlier segment
                int end = match.end() - base;
                cursor = Math.min(length, end);
                if (end <= length) state[1]++;
                continue;
            }
            if (start > cursor) {
                parts.add(FormattedText.of(contents.substring(cursor, start), style));
            }
            if (match == null || start >= length) break;
            parts.add(FormattedText.of(PLACEHOLDER_STRING, marker(style, match.emoji())));
            int end = match.end() - base;
            cursor = Math.min(length, end);
            if (end <= length) state[1]++;
        }
    }

    private static Style marker(Style style, Emoji emoji) {
        try {
            return MARKERS.get(new MarkerKey(style, emoji), () -> {
                // A distinct instance with the same look, click, hover and insertion as the surrounding text. It
                // is recognised by identity, so no tooltip is attached and the text's own events are kept.
                // Its font is a per-emoji key so the marker is also unequal by value: vanilla (SubStringSource)
                // merges adjacent characters whose styles are equal() and keeps only the first instance, which
                // made ":a::b::c:" render as three ":a:". The font is never used to draw the placeholder.
                Style marker = cloneStyle(style, markerFont(emoji));
                WRAPPED_EMOJIS.put(marker, emoji);
                return marker;
            });
        } catch (ExecutionException exception) {
            return style;
        }
    }

    private static Constructor<Style> findStyleConstructor() {
        try {
            Constructor<Style> constructor = Style.class.getDeclaredConstructor(
                    TextColor.class, Boolean.class, Boolean.class, Boolean.class, Boolean.class, Boolean.class,
                    ClickEvent.class, HoverEvent.class, String.class, ResourceLocation.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException exception) {
            Constants.LOG.error("Could not access Minecraft text style constructor", exception);
            return null;
        }
    }

    private static ResourceLocation markerFont(Emoji emoji) {
        String code = null;
        try {
            code = emoji.getShorterString();
        } catch (RuntimeException ignored) {
        }
        if (code == null || code.isEmpty()) code = ":" + emoji.name + ":";
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "emoji_marker/" + EmojiTextureCache.hash(code));
    }

    private static Style cloneStyle(Style style, ResourceLocation font) {
        if (STYLE_CONSTRUCTOR == null) {
            return style.withFont(font);
        }
        try {
            return STYLE_CONSTRUCTOR.newInstance(
                    style.getColor(),
                    style.isBold() ? Boolean.TRUE : null,
                    style.isItalic() ? Boolean.TRUE : null,
                    style.isUnderlined() ? Boolean.TRUE : null,
                    style.isStrikethrough() ? Boolean.TRUE : null,
                    style.isObfuscated() ? Boolean.TRUE : null,
                    style.getClickEvent(),
                    style.getHoverEvent(),
                    style.getInsertion(),
                    font
            );
        } catch (ReflectiveOperationException exception) {
            Constants.LOG.error("Could not clone Minecraft text style", exception);
            return style;
        }
    }

    public static Emoji wrappedEmoji(Style style) {
        return style == null ? null : WRAPPED_EMOJIS.getIfPresent(style);
    }

    public static boolean isWrappedEmoji(int codePoint, Style style) {
        return codePoint == PLACEHOLDER && wrappedEmoji(style) != null;
    }

    // ------------------------------------------------------------------------------------------------ width

    public static int width(Font font, String original) {
        // Font#width(String) accepts null and treats it as an empty string.
        if (original == null) {
            return 0;
        }
        Pair<String, HashMap<Integer, Emoji>> parsed;
        try {
            parsed = RECENT_STRINGS.get(original);
        } catch (ExecutionException exception) {
            Constants.LOG.error("Could not parse emoji text", exception);
            return bypass(() -> font.width(original));
        }
        if (parsed.getRight().isEmpty()) {
            String text = parsed.getLeft().startsWith(SCAPED_STRING) ? parsed.getLeft().substring(SCAPED_STRING.length()) : parsed.getLeft();
            return bypass(() -> font.width(text));
        }
        Map<Integer, Emoji> emojis = parsed.getRight();
        float[] width = {0.0F};
        StringDecomposer.iterateFormatted(parsed.getLeft(), Style.EMPTY, (position, style, codePoint) -> {
            width[0] += emojis.containsKey(position) ? EMOJI_ADVANCE : advance(font, style, codePoint);
            return true;
        });
        return (int) Math.ceil(width[0]);
    }

    public static int width(Font font, FormattedCharSequence original) {
        if (original == null) return 0;
        ParsedSequence parsed = parseSequence(original);
        if (!parsed.customRender()) {
            // no text emojis; wrapped placeholders are measured by StringSplitterMixin
            return bypass(() -> font.width(original));
        }
        float[] width = {0.0F};
        parsed.sequence().accept((position, style, codePoint) -> {
            width[0] += parsed.emojis().containsKey(position) || isWrappedEmoji(codePoint, style) ? EMOJI_ADVANCE : advance(font, style, codePoint);
            return true;
        });
        return (int) Math.ceil(width[0]);
    }

    /** Styled width (bold etc. preserved), used by the player list, scoreboard and most GUI layout code. */
    public static int width(Font font, FormattedText text) {
        if (text == null) return 0;
        FormattedText prepared = prepareForWrapping(text);
        return bypass(() -> font.width(prepared));
    }

    private static float advance(Font font, Style style, int codePoint) {
        FontSet set = font.getFontSet(style.getFont());
        GlyphInfo glyph = set.getGlyphInfo(codePoint, font.filterFishyGlyphs);
        return glyph.getAdvance(style.isBold());
    }

    public static void clearCache() {
        rebuildMatcher();
        MATCHES.invalidateAll();
        RECENT_STRINGS.invalidateAll();
        PARSED.invalidateAll();
    }

    private static <T> T bypass(java.util.function.Supplier<T> action) {
        boolean previous = BYPASS.get();
        BYPASS.set(true);
        try {
            return action.get();
        } finally {
            BYPASS.set(previous);
        }
    }

    // ------------------------------------------------------------------------------------------------ rendering

    public static class CharacterProcessor implements FormattedCharSequence {

        public final int pos;
        public final Style style;
        public final int character;

        public CharacterProcessor(int pos, Style style, int character) {
            this.pos = pos;
            this.style = style;
            this.character = character;
        }

        @Override
        public boolean accept(FormattedCharSink iCharacterConsumer) {
            return iCharacterConsumer.accept(pos, style, character);
        }
    }

    public static class EmojiCharacterRenderer implements FormattedCharSink {
        final MultiBufferSource buffer;
        private final boolean dropShadow;
        private final float dimFactor;
        private final float r;
        private final float g;
        private final float b;
        private final float a;
        private final Matrix4f matrix;
        private final Font.DisplayMode displayMode;
        private final int packedLight;
        private float x;
        private final float y;
        private final Map<Integer, Emoji> emojis;
        /** Outline pass of glowing sign text: offset each glyph, force one colour, do not draw emojis. */
        private float outlineX;
        private float outlineY;
        private boolean outline;
        @Nullable
        private List<BakedGlyph.Effect> effects;

        public EmojiCharacterRenderer(Map<Integer, Emoji> emojis, MultiBufferSource buffer, float x, float y, int color, boolean dropShadow, Matrix4f matrix, Font.DisplayMode displayMode, int packedLight) {
            this.buffer = buffer;
            this.emojis = emojis;
            this.x = x;
            this.y = y;
            this.dropShadow = dropShadow;
            this.dimFactor = dropShadow ? 0.25F : 1.0F;
            this.r = (float) (color >> 16 & 255) / 255.0F * this.dimFactor;
            this.g = (float) (color >> 8 & 255) / 255.0F * this.dimFactor;
            this.b = (float) (color & 255) / 255.0F * this.dimFactor;
            this.a = (float) (color >> 24 & 255) / 255.0F;
            this.matrix = matrix;
            this.displayMode = displayMode;
            this.packedLight = packedLight;
        }

        /** Kept for API compatibility. */
        public EmojiCharacterRenderer(HashMap<Integer, Emoji> emojis, MultiBufferSource buffer, float x, float y, int color, boolean dropShadow, Matrix4f matrix, boolean seeThrough, int packedLight) {
            this(emojis, buffer, x, y, color, dropShadow, matrix, seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, packedLight);
        }

        public EmojiCharacterRenderer outline(float dx, float dy) {
            this.outline = true;
            this.outlineX = dx;
            this.outlineY = dy;
            return this;
        }

        private void addEffect(BakedGlyph.Effect effect) {
            if (this.effects == null) {
                this.effects = Lists.newArrayList();
            }
            this.effects.add(effect);
        }

        public boolean accept(int pos, Style style, int charInt) {
            Font font = Minecraft.getInstance().font;
            Emoji emoji = null;
            if (Services.CONFIG.renderEmoji()) {
                emoji = this.emojis.isEmpty() ? null : this.emojis.get(pos);
                if (emoji == null && charInt == PLACEHOLDER) emoji = wrappedEmoji(style);
            }
            if (emoji != null) {
                if (!this.dropShadow && !this.outline) {
                    EmojiUtil.renderEmoji(emoji, this.x, this.y, matrix, buffer, packedLight, this.a, displayMode);
                }
                this.x += EMOJI_ADVANCE;
                return true;
            }

            FontSet fontSet = font.getFontSet(style.getFont());
            GlyphInfo glyphInfo = fontSet.getGlyphInfo(charInt, font.filterFishyGlyphs);
            BakedGlyph glyph = style.isObfuscated() && charInt != 32 ? fontSet.getRandomGlyph(glyphInfo) : fontSet.getGlyph(charInt);
            boolean bold = style.isBold();
            float alpha = this.a;
            TextColor color = this.outline ? null : style.getColor();
            float red;
            float green;
            float blue;
            if (color != null) {
                int value = color.getValue();
                red = (float) (value >> 16 & 255) / 255.0F * this.dimFactor;
                green = (float) (value >> 8 & 255) / 255.0F * this.dimFactor;
                blue = (float) (value & 255) / 255.0F * this.dimFactor;
            } else {
                red = this.r;
                green = this.g;
                blue = this.b;
            }

            if (!(glyph instanceof EmptyGlyph)) {
                float boldOffset = bold ? glyphInfo.getBoldOffset() : 0.0F;
                float shadowOffset = this.dropShadow ? glyphInfo.getShadowOffset() : 0.0F;
                float gx = this.x + shadowOffset + this.outlineX * glyphInfo.getShadowOffset();
                float gy = this.y + shadowOffset + this.outlineY * glyphInfo.getShadowOffset();
                VertexConsumer consumer = this.buffer.getBuffer(glyph.renderType(displayMode));
                font.renderChar(glyph, bold, style.isItalic(), boldOffset, gx, gy, this.matrix, consumer, red, green, blue, alpha, this.packedLight);
            }

            float advance = glyphInfo.getAdvance(bold);
            float effectOffset = this.dropShadow ? 1.0F : 0.0F;
            if (!this.outline && style.isStrikethrough()) {
                this.addEffect(new BakedGlyph.Effect(this.x + effectOffset - 1.0F, this.y + effectOffset + 4.5F, this.x + effectOffset + advance, this.y + effectOffset + 4.5F - 1.0F, 0.01F, red, green, blue, alpha));
            }
            if (!this.outline && style.isUnderlined()) {
                this.addEffect(new BakedGlyph.Effect(this.x + effectOffset - 1.0F, this.y + effectOffset + 9.0F, this.x + effectOffset + advance, this.y + effectOffset + 9.0F - 1.0F, 0.01F, red, green, blue, alpha));
            }
            this.x += advance;
            return true;
        }

        public float finish(int backgroundColor, float startX) {
            if (backgroundColor != 0) {
                float a = (float) (backgroundColor >> 24 & 255) / 255.0F;
                float r = (float) (backgroundColor >> 16 & 255) / 255.0F;
                float g = (float) (backgroundColor >> 8 & 255) / 255.0F;
                float b = (float) (backgroundColor & 255) / 255.0F;
                this.addEffect(new BakedGlyph.Effect(startX - 1.0F, this.y + 9.0F, this.x + 1.0F, this.y - 1.0F, 0.01F, r, g, b, a));
            }
            if (this.effects != null) {
                FontSet fontSet = Minecraft.getInstance().font.getFontSet(Style.DEFAULT_FONT);
                BakedGlyph white = fontSet.whiteGlyph();
                VertexConsumer consumer = this.buffer.getBuffer(white.renderType(displayMode));
                for (BakedGlyph.Effect effect : this.effects) {
                    white.renderEffect(effect, this.matrix, consumer, this.packedLight);
                }
            }
            return this.x;
        }
    }

    /**
     * @param customRender true when the text must be drawn by {@link EmojiCharacterRenderer}: it contains
     *                     emojis (by code or as wrapped placeholders) or the escape prefix
     */
    public record ParsedSequence(FormattedCharSequence sequence, LinkedHashMap<Integer, Emoji> emojis, boolean customRender) {
    }
}
