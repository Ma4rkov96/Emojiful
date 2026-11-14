package com.hrznstudio.emojiful.api;

import com.hrznstudio.emojiful.Constants;
import com.hrznstudio.emojiful.render.EmojiRenderInfo;
import com.hrznstudio.emojiful.render.EmojiTextureCache;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class Emoji implements Predicate<String> {
    public static final ResourceLocation loading_texture = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/26a0.png");
    public static final ResourceLocation noSignal_texture = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/26d4.png");
    public static final ResourceLocation error_texture = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/26d4.png");

    public String name;
    public List<String> strings = new ArrayList<>();
    public List<String> texts = new ArrayList<>();
    public String location;
    public int version = 1;
    public int sort = 0;
    public boolean worldBased = false;
    private String shortString;
    private String regex;
    private Pattern regexPattern;
    private String textRegex;

    /**
     * Texture and UV rectangle to draw right now. Images are downloaded, decoded and uploaded
     * asynchronously by {@link EmojiTextureCache}; until then a loading or error icon is returned.
     */
    public EmojiRenderInfo getRenderInfo() {
        return EmojiTextureCache.renderInfo(getUrl());
    }

    /** Kept for API compatibility; prefer {@link #getRenderInfo()}, which also carries the frame UVs. */
    public ResourceLocation getResourceLocationForBinding() {
        return getRenderInfo().texture();
    }

    @Override
    public boolean test(String s) {
        for (String text : strings)
            if (s.equalsIgnoreCase(text))
                return true;
        return false;
    }

    public boolean worldBased() {
        return worldBased;
    }

    public String getShorterString() {
        if (shortString != null) return shortString;
        shortString = strings.get(0);
        for (String string : strings) {
            if (string.length() < shortString.length()) {
                shortString = string;
            }
        }
        return shortString;
    }

    public Pattern getRegex() {
        if (regexPattern != null) return regexPattern;
        regexPattern = Pattern.compile(getRegexString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return regexPattern;
    }

    public String getRegexString() {
        if (regex != null) return regex;
        List<String> processed = new ArrayList<>();
        for (String string : strings) {
            if (string == null || string.isEmpty()) continue;
            // Quote the whole alias so characters like . + ? [ $ in emoji names are matched literally.
            String s = Pattern.quote(string);
            char last = string.toLowerCase().charAt(string.length() - 1);
            if (last >= 'a' && last <= 'z') {
                s = s + "\\b";
            }
            char first = string.toLowerCase().charAt(0);
            if (first >= 'a' && first <= 'z') {
                s = "\\b" + s;
            }
            processed.add(s);
        }
        regex = processed.isEmpty() ? "(?!)" : String.join("|", processed);
        return regex;
    }

    public String getTextRegex() {
        if (textRegex != null) return textRegex;
        List<String> processed = new ArrayList<>();
        for (String string : texts) {
            processed.add(Pattern.quote(string));
        }

        // (?<=^|\s) ensures the character before the text is either the start of the string or a whitespace
        // (?=$|\s) ensures the character after the text is either the end of the string or a whitespace
        textRegex = "(?<=^|\\s)(" + String.join("|", processed) + ")(?=$|\\s)";
        return textRegex;
    }

    public String getUrl() {
        if (location != null && (location.startsWith("https://") || location.startsWith("http://"))) return location;
        return Constants.assetsUrl() + location;
    }
}
