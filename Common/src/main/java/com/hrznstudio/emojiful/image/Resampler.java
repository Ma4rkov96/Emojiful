package com.hrznstudio.emojiful.image;

/**
 * High quality image resizing with premultiplied alpha, in float.
 * <ul>
 *     <li>Reduction: separable Lanczos-3 widened by the scale factor, after exact 2x box halving while the
 *     source is much larger than the target (as Pillow's {@code reducing_gap}); one sharp resampling step
 *     instead of a chain of bilinear ones.</li>
 *     <li>Enlargement: area coverage of the nearest-neighbour image, so pixel art keeps hard pixels at any
 *     (also non-integer) factor, with at most one blended pixel at each pixel boundary.</li>
 * </ul>
 * Premultiplied alpha keeps transparent pixels from bleeding dark fringes into edges.
 */
public final class Resampler {

    /** Lanczos lobes. */
    static final double LOBES = 3.0;

    private Resampler() {
    }

    /** Converts ARGB (straight alpha) to premultiplied RGBA floats in 0..1. */
    public static float[] premultiply(int[] argb) {
        float[] out = new float[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            float a = (p >>> 24) / 255f;
            out[i * 4] = (p >> 16 & 0xFF) / 255f * a;
            out[i * 4 + 1] = (p >> 8 & 0xFF) / 255f * a;
            out[i * 4 + 2] = (p & 0xFF) / 255f * a;
            out[i * 4 + 3] = a;
        }
        return out;
    }

    /** Halves the image exactly (2x2 box) while it stays at least {@code gap} times the target size. */
    public static Image reduce(Image image, int targetW, int targetH, int gap) {
        while (image.w % 2 == 0 && image.h % 2 == 0 && image.w / 2 >= targetW * gap && image.h / 2 >= targetH * gap) {
            int w = image.w / 2;
            int h = image.h / 2;
            float[] dst = new float[w * h * 4];
            float[] src = image.px;
            int sw = image.w;
            for (int y = 0; y < h; y++) {
                int r0 = (y * 2) * sw;
                int r1 = r0 + sw;
                for (int x = 0; x < w; x++) {
                    int a = (r0 + x * 2) * 4;
                    int b = (r1 + x * 2) * 4;
                    int o = (y * w + x) * 4;
                    for (int c = 0; c < 4; c++) {
                        dst[o + c] = (src[a + c] + src[a + 4 + c] + src[b + c] + src[b + 4 + c]) * 0.25f;
                    }
                }
            }
            image = new Image(dst, w, h);
        }
        return image;
    }

    /** Resizes premultiplied pixels to {@code dw x dh} and returns straight-alpha ARGB. */
    public static int[] resize(Image src, int dw, int dh) {
        Weights wx = weights(src.w, dw);
        Weights wy = weights(src.h, dh);

        // horizontal pass: src.h rows of dw pixels
        float[] tmp = new float[dw * src.h * 4];
        float[] in = src.px;
        for (int y = 0; y < src.h; y++) {
            int row = y * src.w;
            for (int x = 0; x < dw; x++) {
                float r = 0, g = 0, b = 0, a = 0;
                int start = wx.start[x];
                int base = wx.offset[x];
                for (int k = 0; k < wx.count[x]; k++) {
                    float w = wx.w[base + k];
                    int p = (row + start + k) * 4;
                    r += in[p] * w;
                    g += in[p + 1] * w;
                    b += in[p + 2] * w;
                    a += in[p + 3] * w;
                }
                int o = (y * dw + x) * 4;
                tmp[o] = r;
                tmp[o + 1] = g;
                tmp[o + 2] = b;
                tmp[o + 3] = a;
            }
        }

        // vertical pass
        int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            int start = wy.start[y];
            int base = wy.offset[y];
            for (int x = 0; x < dw; x++) {
                float r = 0, g = 0, b = 0, a = 0;
                for (int k = 0; k < wy.count[y]; k++) {
                    float w = wy.w[base + k];
                    int p = ((start + k) * dw + x) * 4;
                    r += tmp[p] * w;
                    g += tmp[p + 1] * w;
                    b += tmp[p + 2] * w;
                    a += tmp[p + 3] * w;
                }
                out[y * dw + x] = toArgb(r, g, b, a);
            }
        }
        return out;
    }

    private static int toArgb(float r, float g, float b, float a) {
        if (a <= 0.5f / 255f) return 0;
        if (a > 1f) a = 1f;
        // colour can not exceed coverage in premultiplied space; clamping also removes Lanczos ringing
        r = Math.max(0f, Math.min(r, a));
        g = Math.max(0f, Math.min(g, a));
        b = Math.max(0f, Math.min(b, a));
        int ia = Math.round(a * 255f);
        int ir = Math.round(r / a * 255f);
        int ig = Math.round(g / a * 255f);
        int ib = Math.round(b / a * 255f);
        return ia << 24 | ir << 16 | ig << 8 | ib;
    }

    static double lanczos(double x) {
        x = Math.abs(x);
        if (x < 1e-7) return 1.0;
        if (x >= LOBES) return 0.0;
        double px = Math.PI * x;
        return LOBES * Math.sin(px) * Math.sin(px / LOBES) / (px * px);
    }

    /** Per output pixel: contiguous source range and normalised weights. */
    static final class Weights {
        final int[] start;
        final int[] count;
        final int[] offset;
        final float[] w;

        Weights(int[] start, int[] count, int[] offset, float[] w) {
            this.start = start;
            this.count = count;
            this.offset = offset;
            this.w = w;
        }
    }

    static Weights weights(int src, int dst) {
        double scale = (double) dst / src;
        int[] start = new int[dst];
        int[] count = new int[dst];
        int[] offset = new int[dst];
        double[][] rows = new double[dst][];
        int total = 0;
        for (int i = 0; i < dst; i++) {
            int lo;
            double[] w;
            if (scale < 1.0) {
                double support = LOBES / scale;
                double center = (i + 0.5) / scale;
                lo = (int) Math.floor(center - support);
                int hi = (int) Math.ceil(center + support);
                w = new double[hi - lo + 1];
                for (int j = lo; j <= hi; j++) {
                    w[j - lo] = lanczos(((j + 0.5) - center) * scale);
                }
            } else {
                // coverage of the output pixel's footprint by each source pixel
                double x0 = i / scale;
                double x1 = (i + 1) / scale;
                lo = (int) Math.floor(x0);
                int hi = Math.max(lo, (int) Math.ceil(x1) - 1);
                w = new double[hi - lo + 1];
                for (int j = lo; j <= hi; j++) {
                    w[j - lo] = Math.max(0.0, Math.min(x1, j + 1) - Math.max(x0, j));
                }
            }
            // fold samples outside the image onto the edge pixels (clamp-to-edge)
            int clampedLo = Math.max(0, lo);
            int clampedHi = Math.min(src - 1, lo + w.length - 1);
            double[] folded = new double[clampedHi - clampedLo + 1];
            double sum = 0;
            for (int k = 0; k < w.length; k++) {
                int j = Math.min(src - 1, Math.max(0, lo + k));
                folded[j - clampedLo] += w[k];
                sum += w[k];
            }
            if (sum != 0) for (int k = 0; k < folded.length; k++) folded[k] /= sum;
            start[i] = clampedLo;
            count[i] = folded.length;
            offset[i] = total;
            rows[i] = folded;
            total += folded.length;
        }
        float[] flat = new float[total];
        for (int i = 0; i < dst; i++) {
            for (int k = 0; k < rows[i].length; k++) flat[offset[i] + k] = (float) rows[i][k];
        }
        return new Weights(start, count, offset, flat);
    }

    /** Premultiplied RGBA float image. */
    public static final class Image {
        public final float[] px;
        public final int w;
        public final int h;

        public Image(float[] px, int w, int h) {
            this.px = px;
            this.w = w;
            this.h = h;
        }
    }
}
