package com.hrznstudio.emojiful;

import com.hrznstudio.emojiful.api.Emoji;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Constants {

    public static final String MOD_ID = "emojiful";
    public static final String MOD_NAME = "Emojiful";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    public static final Map<String, List<Emoji>> EMOJI_MAP = new HashMap<>();
    public static final List<Emoji> EMOJI_LIST = new ArrayList<>();
    public static boolean error = false;

    /** Built-in emoji set of the original mod (CC-BY-4.0). Can be pointed at a mirror with the custom_assets_url option. */
    public static final String DEFAULT_ASSETS_URL = "https://raw.githubusercontent.com/InnovativeOnlineIndustries/emojiful-assets/1.20-plus/";
    /** No manifest by default; set manifest_url to enable single-request cache validation. */
    public static final String DEFAULT_MANIFEST_URL = "";

    public static String assetsUrl() {
        String url = com.hrznstudio.emojiful.platform.Services.CONFIG.customAssetsUrl();
        if (url == null || url.isBlank()) url = DEFAULT_ASSETS_URL;
        return url.endsWith("/") ? url : url + "/";
    }
}