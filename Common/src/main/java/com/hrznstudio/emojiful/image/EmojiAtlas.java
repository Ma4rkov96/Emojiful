package com.hrznstudio.emojiful.image;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * All frames of one emoji packed into a single grid texture, with mip levels.
 * <p>
 * Every frame is scaled into a {@value #CELL}x{@value #CELL} cell with its aspect ratio preserved
 * (centred, transparent padding) instead of being stretched to a square. Small images are enlarged
 * by an integer factor with nearest-neighbour so pixel art stays crisp; large images are reduced with
 * progressive bilinear filtering. A transparent gutter of {@value #PAD} pixels around each cell keeps
 * linear/mipmap sampling from bleeding neighbouring frames into each other.
 */
public final class EmojiAtlas {

    public static final int CELL = 64;
    public static final int PAD = 2;
    public static final int MAX_LEVEL = 2;
    public static final int MAX_FRAMES = 1024;

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

    private EmojiAtlas(List<int[]> cells, List<Integer> delayList) {
        this.frameCount = cells.size();
        this.cols = (int) Math.ceil(Math.sqrt(frameCount));
        this.rows = (frameCount + cols - 1) / cols;
        this.width = cols * CELL;
        this.height = rows * CELL;
        this.delays = delayList.stream().mapToInt(Integer::intValue).toArray();
        this.frameEnds = new int[frameCount];
        int time = 0;
        for (int i = 0; i < frameCount; i++) {
            time += delays[i];
            frameEnds[i] = time;
        }
        this.totalDuration = time;

        int[] base = new int[width * height];
        for (int i = 0; i < frameCount; i++) {
            int[] cell = cells.get(i);
            int cx = (i % cols) * CELL;
            int cy = (i / cols) * CELL;
            for (int y = 0; y < CELL; y++) {
                System.arraycopy(cell, y * CELL, base, (cy + y) * width + cx, CELL);
            }
        }
        this.levels = new int[MAX_LEVEL + 1][];
        levels[0] = base;
        for (int level = 1; level <= MAX_LEVEL; level++) {
            levels[level] = downsample(levels[level - 1], width >> (level - 1), height >> (level - 1));
        }
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
        private final List<int[]> cells = new ArrayList<>();
        private final List<Integer> delays = new ArrayList<>();

        @Override
        public boolean accept(BufferedImage canvas, int delayMs) {
            cells.add(fitIntoCell(canvas));
            delays.add(Math.max(1, delayMs));
            return cells.size() < MAX_FRAMES;
        }

        public EmojiAtlas build() {
            if (cells.isEmpty()) throw new IllegalStateException("No frames decoded");
            return new EmojiAtlas(cells, delays);
        }
    }

    static int[] fitIntoCell(BufferedImage source) {
        int box = CELL - 2 * PAD;
        int w = Math.max(1, source.getWidth());
        int h = Math.max(1, source.getHeight());
        double scale = Math.min(box / (double) w, box / (double) h);

        BufferedImage scaled;
        if (scale >= 1) {
            int factor = (int) Math.floor(scale);
            scaled = resize(source, w * factor, h * factor, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        } else {
            int tw = Math.max(1, (int) Math.round(w * scale));
            int th = Math.max(1, (int) Math.round(h * scale));
            scaled = source;
            int cw = w;
            int ch = h;
            while (cw / 2 >= tw && ch / 2 >= th) {
                cw /= 2;
                ch /= 2;
                scaled = resize(scaled, cw, ch, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }
            if (cw != tw || ch != th) {
                scaled = resize(scaled, tw, th, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }
        }

        int[] cell = new int[CELL * CELL];
        int sw = scaled.getWidth();
        int sh = scaled.getHeight();
        int ox = (CELL - sw) / 2;
        int oy = (CELL - sh) / 2;
        int[] row = new int[sw];
        for (int y = 0; y < sh; y++) {
            scaled.getRGB(0, y, sw, 1, row, 0, sw);
            System.arraycopy(row, 0, cell, (oy + y) * CELL + ox, sw);
        }
        return cell;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height, Object interpolation) {
        // Premultiplied alpha avoids dark fringes around transparent edges when filtering.
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = target.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    /** 2x2 box filter with alpha weighting. */
    static int[] downsample(int[] src, int width, int height) {
        int w = width / 2;
        int h = height / 2;
        int[] dst = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = src[(y * 2 + dy) * width + x * 2 + dx];
                        int pa = p >>> 24;
                        a += pa;
                        r += (p >> 16 & 0xFF) * pa;
                        g += (p >> 8 & 0xFF) * pa;
                        b += (p & 0xFF) * pa;
                    }
                }
                if (a == 0) {
                    dst[y * w + x] = 0;
                } else {
                    dst[y * w + x] = (a / 4) << 24 | (r / a) << 16 | (g / a) << 8 | (b / a);
                }
            }
        }
        return dst;
    }
}
