package com.hrznstudio.emojiful.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hrznstudio.emojiful.CommonClass;
import com.hrznstudio.emojiful.Constants;
import com.hrznstudio.emojiful.platform.Services;

import java.util.HashMap;
import java.util.Map;

/**
 * Optional list of emoji files with their SHA-1, published next to the images, e.g.
 * <pre>{"base": "https://raw.githubusercontent.com/owner/repo/main/", "files": {"Cats/huhcat.gif": "3f2a..."}}</pre>
 * When a cached image's SHA-1 matches the manifest, it is used without contacting the image host at all,
 * so one request per session replaces one revalidation request per emoji. A changed hash means the file was
 * replaced, and it is downloaded again. Images not listed fall back to per-file ETag revalidation.
 */
public final class EmojiManifest {

    private static final long REFRESH_INTERVAL_MS = 5 * 60_000;

    private static volatile Map<String, String> hashes = Map.of();
    private static volatile long loadedAt;

    private EmojiManifest() {
    }

    /** SHA-1 (lower-case hex) the manifest lists for this URL, or null when unknown. */
    public static String expectedHash(String url) {
        return hashes.get(url);
    }

    public static int size() {
        return hashes.size();
    }

    /** Blocking; call from a background thread. Refreshes at most every few minutes. */
    public static synchronized void refreshIfStale() {
        if (System.currentTimeMillis() - loadedAt < REFRESH_INTERVAL_MS && !hashes.isEmpty()) return;
        String url = Services.CONFIG.manifestUrl();
        if (url == null || url.isBlank()) {
            hashes = Map.of();
            return;
        }
        // fetched with timeouts and the game's proxy; the last good copy is used when the host is unreachable
        String text = CommonClass.readStringFromURL(url);
        try {
            if (!text.isEmpty()) {
                hashes = parse(text, url);
                Constants.LOG.info("Emoji manifest: {} files", hashes.size());
            }
        } catch (Exception exception) {
            Constants.LOG.warn("Emoji manifest {} is invalid: {}", url, exception.toString());
        }
        loadedAt = System.currentTimeMillis();
    }

    static Map<String, String> parse(String text, String manifestUrl) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        String base = root.has("base") ? root.get("base").getAsString() : manifestUrl.substring(0, manifestUrl.lastIndexOf('/') + 1);
        if (!base.endsWith("/")) base += "/";
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> file : root.getAsJsonObject("files").entrySet()) {
            String path = file.getKey();
            String hash = file.getValue().getAsString().toLowerCase();
            result.put(base + path, hash);
            // the built-in set refers to files with spaces and '&' URL-encoded
            result.put(base + path.replace(" ", "%20").replace("&", "%26"), hash);
        }
        return Map.copyOf(result);
    }
}
