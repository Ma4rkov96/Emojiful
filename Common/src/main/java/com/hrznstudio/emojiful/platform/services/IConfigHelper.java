package com.hrznstudio.emojiful.platform.services;

public interface IConfigHelper {

    boolean getProfanityFilter();

    String getReplacementString();

    boolean showEmojiAutocomplete();

    boolean showEmojiSelector();

    boolean renderEmoji();

    boolean loadGifEmojis();

    boolean shortEmojiReplacement();

    boolean loadTwemoji();

    boolean loadCustom();

    boolean loadDatapack();

    /** Base URL of the built-in emoji set (Categories.yml, category files and their images). */
    default String customAssetsUrl() {
        return com.hrznstudio.emojiful.Constants.DEFAULT_ASSETS_URL;
    }

    /** Optional manifest listing emoji files and their SHA-1, so caches validate with one request. Empty disables it. */
    default String manifestUrl() {
        return com.hrznstudio.emojiful.Constants.DEFAULT_MANIFEST_URL;
    }

    /** Download all server emojis in the background after joining, so they show up without a loading icon. */
    default boolean prefetchServerEmojis() {
        return true;
    }
}
