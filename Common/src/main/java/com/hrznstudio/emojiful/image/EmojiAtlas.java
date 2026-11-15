package com.hrznstudio.emojiful.image;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * All frames of one emoji packed into a single grid texture, with mip levels sized for the screen.
 * <p>
 * One mip level (the "GUI level") has cells of exactly {@link #target} pixels, the size an emoji covers on
 * screen in chat at the current GUI scale. There the texture is sampled 1:1, so chat emojis come out of a
 * single high quality resampling step (see {@link Resampler}) instead of a chain of filters. Larger levels
 * above it serve signs and name tags seen up close, smaller ones below it serve them from afar. Every
 * level is resampled straight from the source frame, not from the level above.
 * <p>
 * Each frame keeps its aspect ratio (centred, transparent padding); the padding is one pixel at the GUI
 * level and keeps linear/mipmap sampling from bleeding neighbouring frames into each other.
 */
public final class EmojiAtlas {

    public static final int MAX_FRAMES = 1024;
    /** Cells of the largest level are kept at or below this size, when the GUI level allows it. */
    private static final int TOP_CELL = 128;
    private static final int MAX_LEVELS_ABOVE = 3;
    /** Smallest cell of the levels below the GUI level. */
    private static final int MIN_CELL = 8;
    /** Pixel memory of the largest level of one emoji; long animations get fewer large levels instead. */
    private static final long TOP_LEVEL_BUDGET = 16L * 1024 * 1024;
    /** Source frames are box-halved while at least this many times the largest output. */
    private static final int REDUCING_GAP = 3;

    /** Cell size in pixels of the level drawn 1:1 in chat. */
    public final int target;
    /** Index of that level; levels above it are 2x, 4x... larger. */
    public final int guiLevel;
    public final int levelCount;
    public final int cols;
    public final int rows;
    public final int frameCount;
    public final int[] delays;
    /** Cumulative end time of each frame, in ms. */
    public final int[] frameEnds;
    public final int totalDuration;
    /** ARGB pixel data per mip level; level n is (width >> n) x (height >> n). */
    public int[][] levels;
    public final int width;
    public final int height;

    private EmojiAtlas(int target, int guiLevel, int levelCount, List<int[][]> cells, List<Integer> delayList) {
        this.target = target;
        this.guiLevel = guiLevel;
        this.levelCount = levelCount;
        this.frameCount = cells.size();
        this.cols = (int) Math.ceil(Math.sqrt(frameCount));
        this.rows = (frameCount + cols - 1) / cols;
        int top = cellSize(target, guiLevel, 0);
        this.width = cols * top;
        this.height = rows * top;
        this.delays = delayList.stream().mapToInt(Integer::intValue).toArray();
        this.frameEnds = new int[frameCount];
        int time = 0;
        for (int i = 0; i < frameCount; i++) {
            time += delays[i];
            frameEnds[i] = time;
        }
        this.totalDuration = time;

        this.levels = new int[levelCount][];
        for (int level = 0; level < levelCount; level++) {
            int cell = cellSize(target, guiLevel, level);
            int w = width >> level;
            int[] pixels = new int[w * (height >> level)];
            for (int i = 0; i < frameCount; i++) {
                int[] src = cells.get(i)[level];
                int cx = (i % cols) * cell;
                int cy = (i / cols) * cell;
                for (int y = 0; y < cell; y++) {
                    System.arraycopy(src, y * cell, pixels, (cy + y) * w + cx, cell);
                }
            }
            levels[level] = pixels;
        }
    }

    static int cellSize(int target, int guiLevel, int level) {
        return level <= guiLevel ? target << (guiLevel - level) : target >> (level - guiLevel);
    }

    public boolean animated() {
        return frameCount > 1;
    }

    /** Frame to show at the given time; all copies of an emoji stay in sync. */
    public int frameAt(long millis) {
        if (frameCount <= 1 || totalDuration <= 0) return 0;
        int t = (int) (millis % totalDuration);
        int lo = 0;
        int hi = frameCount - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (frameEnds[mid] > t) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /** Drops the CPU-side pixels once they have been handed to a texture; timing data stays. */
    public void releasePixels() {
        levels = null;
    }

    public long byteSize() {
        long size = 0;
        if (levels != null) for (int[] level : levels) size += level.length * 4L;
        return size;
    }

    /** Collects decoded frames and builds the atlas. */
    public static final class Builder implements FrameSink {
        private final int target;
        private int guiLevel;
        private final int below;
        private final List<int[][]> cells = new ArrayList<>();
        private final List<Integer> delays = new ArrayList<>();

        /** @param target size in pixels an emoji covers on screen in chat (GUI scale x emoji size) */
        public Builder(int target) {
            this.target = Math.max(MIN_CELL, Math.min(256, target));
            int above = 0;
            while (above < MAX_LEVELS_ABOVE && this.target << (above + 1) <= TOP_CELL) above++;
            this.guiLevel = above;
            int b = 0;
            while ((this.target >> b) % 2 == 0 && (this.target >> (b + 1)) >= MIN_CELL) b++;
            this.below = b;
        }

        private int levelCount() {
            return guiLevel + 1 + below;
        }

        @Override
        public boolean accept(BufferedImage canvas, int delayMs) {
            // Long animations: give up the largest level rather than use a lot of memory.
            while (guiLevel > 0 && (cells.size() + 1L) * square(cellSize(target, guiLevel, 0)) * 4 > TOP_LEVEL_BUDGET) {
                guiLevel--;
                for (int i = 0; i < cells.size(); i++) {
                    int[][] frame = cells.get(i);
                    cells.set(i, Arrays.copyOfRange(frame, 1, frame.length));
                }
            }
            cells.add(render(canvas, target, guiLevel, levelCount()));
            delays.add(Math.max(1, delayMs));
            return cells.size() < MAX_FRAMES;
        }

        public EmojiAtlas build() {
            if (cells.isEmpty()) throw new IllegalStateException("No frames decoded");
            return new EmojiAtlas(target, guiLevel, levelCount(), cells, delays);
        }
    }

    private static long square(int v) {
        return (long) v * v;
    }

    /** Renders one frame into a cell for every level. */
    static int[][] render(BufferedImage source, int target, int guiLevel, int levelCount) {
        int w = Math.max(1, source.getWidth());
        int h = Math.max(1, source.getHeight());
        int[] argb = source.getRGB(0, 0, w, h, null, 0, w);
        Resampler.Image image = new Resampler.Image(Resampler.premultiply(argb), w, h);

        // content box: one pixel of padding on each side at the GUI level, scaled with the level
        double guiBox = Math.max(1, target - 2);
        int[][] out = new int[levelCount][];
        for (int level = 0; level < levelCount; level++) {
            int cell = cellSize(target, guiLevel, level);
            double box = Math.max(1.0, guiBox * Math.pow(2, guiLevel - level));
            double scale = Math.min(box / w, box / h);
            int tw = Math.max(1, Math.min(cell, (int) Math.round(w * scale)));
            int th = Math.max(1, Math.min(cell, (int) Math.round(h * scale)));
            if (level == 0) image = Resampler.reduce(image, tw, th, REDUCING_GAP);
            int[] scaled = Resampler.resize(image, tw, th);

            int[] pixels = new int[cell * cell];
            int ox = (cell - tw) / 2;
            int oy = (cell - th) / 2;
            for (int y = 0; y < th; y++) {
                System.arraycopy(scaled, y * tw, pixels, (oy + y) * cell + ox, tw);
            }
            out[level] = pixels;
        }
        return out;
    }
}
