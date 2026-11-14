package com.hrznstudio.emojiful.render;

import com.hrznstudio.emojiful.Constants;
import com.hrznstudio.emojiful.api.Emoji;
import com.hrznstudio.emojiful.image.EmojiAtlas;
import com.hrznstudio.emojiful.image.ImageDecoder;
import com.hrznstudio.emojiful.platform.Services;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads, caches, decodes and uploads emoji images, keyed by URL.
 * <ul>
 *     <li>A small shared thread pool instead of one thread per emoji.</li>
 *     <li>Connect/read timeouts, size limit and retries with back-off instead of hanging forever.</li>
 *     <li>Disk cache keyed by a hash of the URL (not the emoji name), written atomically, and
 *     revalidated once per game session with ETag / Last-Modified so replaced images update.</li>
 *     <li>Texture ids derived from the URL hash, so names with Cyrillic, upper case or symbols
 *     never collide.</li>
 *     <li>Entries outlive reconnects and recipe reloads, so nothing is re-downloaded or re-uploaded.</li>
 * </ul>
 */
public final class EmojiTextureCache {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 4;
    private static final long RETRY_BASE_MS = 15_000;
    private static final String USER_AGENT = "Emojiful (Minecraft mod; +https://github.com/InnovativeOnlineIndustries/Emojiful)";

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    /** URLs whose cached copy is known to be current in this session. */
    private static final java.util.Set<String> VALIDATED = ConcurrentHashMap.newKeySet();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "Emojiful Loader #" + THREADS.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    /** Coordinates prefetching and manifest refreshes. */
    private static final ExecutorService PREFETCH = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Emojiful Prefetch");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    /** Separate from the on-demand pool, so prefetching never delays emojis that are on screen. */
    private static final ExecutorService PREFETCH_WORKERS = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "Emojiful Prefetch #" + THREADS.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private EmojiTextureCache() {
    }

    /** UNLOADED: the texture was released after not being shown for a while; it is reloaded from disk on demand. */
    public enum State { LOADING, READY, FAILED, UNLOADED }

    private static final long SWEEP_INTERVAL_SECONDS = 30;
    /** Runs the unload check on a timer, so it also happens while no emoji is on screen. */
    private static final java.util.concurrent.ScheduledExecutorService SWEEPER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Emojiful Texture Sweeper");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    public static final class Entry {
        final String url;
        final String key;
        final ResourceLocation location;
        volatile State state = State.LOADING;
        volatile EmojiAtlas atlas;
        volatile ResourceLocation failureTexture = Emoji.error_texture;
        volatile long retryAt;
        volatile long lastUsed = System.currentTimeMillis();
        int attempts;

        Entry(String url) {
            this.url = url;
            this.key = hash(url);
            this.location = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "emoji/" + key);
        }

        public State state() {
            return state;
        }
    }

    public static EmojiRenderInfo renderInfo(String url) {
        if (url == null || url.isBlank()) return EmojiRenderInfo.full(Emoji.error_texture);
        Entry entry = get(url);
        entry.lastUsed = System.currentTimeMillis();
        EmojiAtlas atlas = entry.atlas;
        if (atlas != null) {
            int frame = Services.CONFIG.loadGifEmojis() ? atlas.frameAt(Util.getMillis()) : 0;
            float cw = 1f / atlas.cols;
            float ch = 1f / atlas.rows;
            float u = (frame % atlas.cols) * cw;
            float v = (frame / atlas.cols) * ch;
            return new EmojiRenderInfo(entry.location, u, v, u + cw, v + ch);
        }
        return EmojiRenderInfo.full(entry.state == State.FAILED ? entry.failureTexture : Emoji.loading_texture);
    }

    public static Entry get(String url) {
        Entry entry = ENTRIES.computeIfAbsent(url, created -> {
            Entry e = new Entry(created);
            schedule(e);
            return e;
        });
        if (entry.state == State.UNLOADED) {
            synchronized (entry) {
                if (entry.state == State.UNLOADED) {
                    entry.state = State.LOADING;
                    entry.attempts = 0;
                    schedule(entry);
                }
            }
        } else if (entry.state == State.FAILED && entry.attempts < MAX_ATTEMPTS && System.currentTimeMillis() >= entry.retryAt) {
            synchronized (entry) {
                if (entry.state == State.FAILED) {
                    entry.state = State.LOADING;
                    schedule(entry);
                }
            }
        }
        return entry;
    }

    /**
     * Frees the GPU memory of emojis that have not been drawn for a while (render thread only). The image
     * stays in the disk cache and is decoded again the next time the emoji is shown.
     */
    private static void unloadUnused(long now) {
        int minutes = Services.CONFIG.unloadAfterMinutes();
        if (minutes <= 0) return;
        long limit = minutes * 60_000L;
        int released = 0;
        for (Entry entry : ENTRIES.values()) {
            if (entry.state == State.READY && entry.atlas != null && now - entry.lastUsed > limit) {
                Minecraft.getInstance().getTextureManager().release(entry.location);
                entry.atlas = null;
                entry.state = State.UNLOADED;
                released++;
            }
        }
        if (released > 0) {
            Constants.LOG.debug("Unloaded {} emoji textures not shown for {} min", released, minutes);
        }
    }

    private static void schedule(Entry entry) {
        EXECUTOR.execute(() -> load(entry));
    }

    private static void load(Entry entry) {
        entry.attempts++;
        File file = cacheFile(entry.key);
        File meta = new File(file.getPath() + ".meta");
        try {
            byte[] cached = file.isFile() ? Files.readAllBytes(file.toPath()) : null;
            String expected = EmojiManifest.expectedHash(entry.url);
            boolean current = cached != null && (VALIDATED.contains(entry.url) || expected != null && expected.equals(sha1(cached)));

            boolean published = false;
            // Show the cached copy right away unless the manifest says it has been replaced.
            if (cached != null && (current || expected == null)) {
                try {
                    publish(entry, cached);
                    published = true;
                } catch (Exception broken) {
                    Constants.LOG.warn("Cached emoji image for {} is unreadable, downloading again", entry.url, broken);
                    file.delete();
                    meta.delete();
                    cached = null;
                    current = false;
                }
            }
            if (current && published) {
                VALIDATED.add(entry.url);
                return;
            }

            byte[] fresh;
            try {
                fresh = fetchToDisk(entry.url, entry.key);
            } catch (HttpStatusException status) {
                if (!published && cached != null) {
                    publish(entry, cached);
                } else if (!published) {
                    fail(entry, Emoji.noSignal_texture, "HTTP " + status.status);
                }
                return;
            }
            if (fresh != null) {
                if (!published || !Arrays.equals(fresh, cached)) publish(entry, fresh);
            } else if (!published && file.isFile()) {
                publish(entry, Files.readAllBytes(file.toPath()));
            }
        } catch (Exception exception) {
            if (entry.atlas == null) {
                fail(entry, Emoji.error_texture, exception.toString());
            } else {
                // We already show the cached image; a failed revalidation is not worth an error.
                Constants.LOG.debug("Could not revalidate emoji image {}", entry.url, exception);
            }
        }
    }

    private static final class HttpStatusException extends IOException {
        final int status;

        HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    /**
     * Makes sure the newest copy of the image is on disk. Returns the downloaded bytes, or null when the
     * cached copy is still current. One download per URL at a time; each URL is checked once per session.
     */
    private static byte[] fetchToDisk(String url, String key) throws IOException {
        Object lock = LOCKS.computeIfAbsent(url, u -> new Object());
        synchronized (lock) {
            File file = cacheFile(key);
            File meta = new File(file.getPath() + ".meta");
            if (VALIDATED.contains(url) && file.isFile()) return null;
            Download download = download(url, file.isFile() ? readMeta(meta) : null);
            if (download.notModified()) {
                VALIDATED.add(url);
                return null;
            }
            if (download.data() == null) throw new HttpStatusException(download.status());
            byte[] previous = file.isFile() ? Files.readAllBytes(file.toPath()) : null;
            boolean changed = previous == null || !Arrays.equals(previous, download.data());
            if (changed) {
                writeAtomically(file, download.data());
            }
            String validators = (download.etag() == null ? "" : download.etag()) + "\n" + (download.lastModified() == null ? "" : download.lastModified());
            writeAtomically(meta, validators.getBytes(StandardCharsets.UTF_8));
            String expected = EmojiManifest.expectedHash(url);
            if (expected == null || expected.equals(sha1(download.data()))) {
                VALIDATED.add(url);
            }
            // otherwise the image host's CDN lags behind the manifest; check again next session
            return changed ? download.data() : null;
        }
    }

    /**
     * Downloads the given images to the disk cache in the background (low priority, two at a time), so they
     * are shown without a loading icon later. Nothing is decoded or uploaded until an emoji is displayed.
     */
    public static void prefetch(Collection<String> urls) {
        if (!Services.CONFIG.prefetchServerEmojis()) return;
        List<String> pending = urls.stream().filter(u -> u != null && !u.isBlank() && !VALIDATED.contains(u)).distinct().toList();
        if (pending.isEmpty()) return;
        PREFETCH.execute(() -> {
            EmojiManifest.refreshIfStale();
            int[] downloaded = {0};
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(pending.size());
            java.util.concurrent.Semaphore parallel = new java.util.concurrent.Semaphore(3);
            for (String url : pending) {
                try {
                    parallel.acquire();
                } catch (InterruptedException interrupted) {
                    return;
                }
                PREFETCH_WORKERS.execute(() -> {
                    try {
                        String key = hash(url);
                        File file = cacheFile(key);
                        String expected = EmojiManifest.expectedHash(url);
                        if (file.isFile() && expected != null && expected.equals(sha1(Files.readAllBytes(file.toPath())))) {
                            VALIDATED.add(url);
                        } else if (fetchToDisk(url, key) != null) {
                            synchronized (downloaded) {
                                downloaded[0]++;
                            }
                        }
                    } catch (Exception exception) {
                        Constants.LOG.debug("Could not prefetch emoji image {}: {}", url, exception.toString());
                    } finally {
                        parallel.release();
                        done.countDown();
                    }
                });
            }
            try {
                done.await();
            } catch (InterruptedException ignored) {
            }
            Constants.LOG.info("Emoji prefetch: {} images checked, {} downloaded", pending.size(), downloaded[0]);
        });
    }

    /** Called once at startup so the manifest is ready before most emojis are shown. */
    public static void warmUp() {
        PREFETCH.execute(EmojiManifest::refreshIfStale);
        // GL resources may only be released on the render thread, so the timer just posts the check there.
        SWEEPER.scheduleWithFixedDelay(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) minecraft.execute(() -> unloadUnused(System.currentTimeMillis()));
        }, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void fail(Entry entry, ResourceLocation texture, String reason) {
        entry.failureTexture = texture;
        entry.retryAt = System.currentTimeMillis() + (RETRY_BASE_MS << Math.min(entry.attempts - 1, 4));
        entry.state = State.FAILED;
        Constants.LOG.warn("Could not load emoji image {} (attempt {}/{}): {}", entry.url, entry.attempts, MAX_ATTEMPTS, reason);
    }

    private static void publish(Entry entry, byte[] data) throws IOException {
        EmojiAtlas.Builder builder = new EmojiAtlas.Builder();
        ImageDecoder.decode(data, Services.CONFIG.loadGifEmojis(), builder);
        EmojiAtlas atlas = builder.build();
        // pixel conversion happens here, on the loader thread; the render thread only uploads
        EmojiTexture texture = new EmojiTexture(atlas);
        atlas.releasePixels();
        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().getTextureManager().register(entry.location, texture);
            entry.atlas = atlas;
            entry.state = State.READY;
        });
    }

    private record Download(int status, byte[] data, boolean notModified, String etag, String lastModified) {
    }

    private static Download download(String url, List<String> validators) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection(Minecraft.getInstance().getProxy());
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (validators != null) {
                if (!validators.get(0).isEmpty()) connection.setRequestProperty("If-None-Match", validators.get(0));
                if (validators.size() > 1 && !validators.get(1).isEmpty()) connection.setRequestProperty("If-Modified-Since", validators.get(1));
            }
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return new Download(status, null, true, null, null);
            }
            if (status / 100 != 2) {
                return new Download(status, null, false, null, null);
            }
            long length = connection.getContentLengthLong();
            if (length > MAX_BYTES) throw new IOException("Image is too large (" + length + " bytes)");
            ByteArrayOutputStream out = new ByteArrayOutputStream(length > 0 ? (int) length : 64 * 1024);
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_BYTES) throw new IOException("Image is too large");
                }
            }
            return new Download(status, out.toByteArray(), false, connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"));
        } finally {
            connection.disconnect();
        }
    }

    private static List<String> readMeta(File meta) {
        try {
            if (!meta.isFile()) return null;
            String[] lines = Files.readString(meta.toPath(), StandardCharsets.UTF_8).split("\n", -1);
            return List.of(lines[0].trim(), lines.length > 1 ? lines[1].trim() : "");
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeAtomically(File target, byte[] data) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File temp = new File(parent, target.getName() + ".tmp");
        Files.write(temp.toPath(), data);
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static File cacheFile(String key) {
        return new File(cacheRoot(), key);
    }

    /**
     * {@code <game directory>/emojiful/cache/v2}. Always relative to the game directory, not to the process
     * working directory, which some launchers set to a different folder.
     */
    public static File cacheRoot() {
        Minecraft minecraft = Minecraft.getInstance();
        File game = minecraft != null && minecraft.gameDirectory != null ? minecraft.gameDirectory : new File(".");
        return new File(game, "emojiful/cache/v2");
    }

    static String hash(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
