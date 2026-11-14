package com.hrznstudio.emojiful.image;

import java.io.IOException;

/**
 * Decoder for the WebP lossless bitstream (VP8L), following RFC 9649 section 3-5.
 * <p>
 * Written because the TwelveMonkeys reader loses the alpha channel of lossless images that use a
 * 17-256 colour palette, which is common for small emoji and pixel art.
 */
public final class Vp8lDecoder {

    private static final int PREDICTOR_TRANSFORM = 0;
    private static final int CROSS_COLOR_TRANSFORM = 1;
    private static final int SUBTRACT_GREEN_TRANSFORM = 2;
    private static final int COLOR_INDEXING_TRANSFORM = 3;

    private static final int NUM_LITERAL_CODES = 256;
    private static final int NUM_LENGTH_CODES = 24;
    private static final int NUM_DISTANCE_CODES = 40;
    private static final int CODE_LENGTH_CODES = 19;
    private static final int[] CODE_LENGTH_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    private static final int[] DISTANCE_MAP = {
            0, 1, 1, 0, 1, 1, -1, 1, 0, 2, 2, 0, 1, 2, -1, 2,
            2, 1, -2, 1, 2, 2, -2, 2, 0, 3, 3, 0, 1, 3, -1, 3,
            3, 1, -3, 1, 2, 3, -2, 3, 3, 2, -3, 2, 0, 4, 4, 0,
            1, 4, -1, 4, 4, 1, -4, 1, 3, 3, -3, 3, 2, 4, -2, 4,
            4, 2, -4, 2, 0, 5, 3, 4, -3, 4, 4, 3, -4, 3, 5, 0,
            1, 5, -1, 5, 5, 1, -5, 1, 2, 5, -2, 5, 5, 2, -5, 2,
            4, 4, -4, 4, 3, 5, -3, 5, 5, 3, -5, 3, 0, 6, 6, 0,
            1, 6, -1, 6, 6, 1, -6, 1, 2, 6, -2, 6, 6, 2, -6, 2,
            4, 5, -4, 5, 5, 4, -5, 4, 3, 6, -3, 6, 6, 3, -6, 3,
            0, 7, 7, 0, 1, 7, -1, 7, 5, 5, -5, 5, 7, 1, -7, 1,
            4, 6, -4, 6, 6, 4, -6, 4, 2, 7, -2, 7, 7, 2, -7, 2,
            3, 7, -3, 7, 7, 3, -7, 3, 5, 6, -5, 6, 6, 5, -6, 5,
            8, 0, 4, 7, -4, 7, 7, 4, -7, 4, 8, 1, 8, 2, 6, 6,
            -6, 6, 8, 3, 5, 7, -5, 7, 7, 5, -7, 5, 8, 4, 6, 7,
            -6, 7, 7, 6, -7, 6, 8, 5, 7, 7, -7, 7, 8, 6, 8, 7
    };

    private final BitReader in;

    private Vp8lDecoder(byte[] data, int offset, int length) {
        this.in = new BitReader(data, offset, length);
    }

    /** Decodes a complete VP8L chunk payload (with the 5 byte header) to ARGB pixels. */
    public static Result decode(byte[] data, int offset, int length) throws IOException {
        Vp8lDecoder decoder = new Vp8lDecoder(data, offset, length);
        if (decoder.in.read(8) != 0x2F) throw new IOException("Bad VP8L signature");
        int width = decoder.in.read(14) + 1;
        int height = decoder.in.read(14) + 1;
        decoder.in.read(1); // alpha_is_used: only a hint
        if (decoder.in.read(3) != 0) throw new IOException("Unsupported VP8L version");
        return new Result(width, height, decoder.decodeImageStream(width, height, true));
    }

    /** Decodes a header-less VP8L stream of known size (used by compressed ALPH chunks). */
    public static int[] decodeHeaderless(byte[] data, int offset, int length, int width, int height) throws IOException {
        return new Vp8lDecoder(data, offset, length).decodeImageStream(width, height, true);
    }

    public record Result(int width, int height, int[] argb) {
    }

    private record Transform(int type, int bits, int xsize, int[] data) {
    }

    private int[] decodeImageStream(int xsize, int ysize, boolean level0) throws IOException {
        Transform[] transforms = new Transform[4];
        int count = 0;
        int width = xsize;
        if (level0) {
            boolean[] seen = new boolean[4];
            while (in.read(1) == 1) {
                int type = in.read(2);
                if (seen[type]) throw new IOException("Duplicate VP8L transform");
                seen[type] = true;
                switch (type) {
                    case PREDICTOR_TRANSFORM, CROSS_COLOR_TRANSFORM -> {
                        int bits = in.read(3) + 2;
                        int[] sub = decodeImageStream(divRoundUp(width, 1 << bits), divRoundUp(ysize, 1 << bits), false);
                        transforms[count++] = new Transform(type, bits, width, sub);
                    }
                    case SUBTRACT_GREEN_TRANSFORM -> transforms[count++] = new Transform(type, 0, width, null);
                    case COLOR_INDEXING_TRANSFORM -> {
                        int size = in.read(8) + 1;
                        int[] palette = decodeImageStream(size, 1, false);
                        for (int i = 1; i < size; i++) palette[i] = addPixels(palette[i], palette[i - 1]);
                        int bits = size > 16 ? 0 : size > 4 ? 1 : size > 2 ? 2 : 3;
                        transforms[count++] = new Transform(type, bits, width, palette);
                        width = divRoundUp(width, 1 << bits);
                    }
                    default -> throw new IOException("Unknown VP8L transform");
                }
            }
        }

        int cacheBits = 0;
        if (in.read(1) == 1) {
            cacheBits = in.read(4);
            if (cacheBits < 1 || cacheBits > 11) throw new IOException("Bad VP8L color cache size");
        }

        int prefixBits = 0;
        int prefixWidth = 0;
        int[] entropyImage = null;
        int groups = 1;
        if (level0 && in.read(1) == 1) {
            prefixBits = in.read(3) + 2;
            prefixWidth = divRoundUp(width, 1 << prefixBits);
            entropyImage = decodeImageStream(prefixWidth, divRoundUp(ysize, 1 << prefixBits), false);
            for (int i = 0; i < entropyImage.length; i++) {
                int group = (entropyImage[i] >> 8) & 0xFFFF;
                entropyImage[i] = group;
                groups = Math.max(groups, group + 1);
            }
        }

        int cacheSize = cacheBits > 0 ? 1 << cacheBits : 0;
        Huffman[][] codes = new Huffman[groups][];
        for (int g = 0; g < groups; g++) {
            codes[g] = new Huffman[]{
                    readCode(NUM_LITERAL_CODES + NUM_LENGTH_CODES + cacheSize),
                    readCode(NUM_LITERAL_CODES),
                    readCode(NUM_LITERAL_CODES),
                    readCode(NUM_LITERAL_CODES),
                    readCode(NUM_DISTANCE_CODES)
            };
        }

        int[] pixels = decodePixels(width, ysize, codes, entropyImage, prefixBits, prefixWidth, cacheBits);

        for (int i = count - 1; i >= 0; i--) {
            pixels = inverse(transforms[i], pixels, ysize);
        }
        return pixels;
    }

    private int[] decodePixels(int width, int height, Huffman[][] codes, int[] entropyImage, int prefixBits, int prefixWidth, int cacheBits) throws IOException {
        int total = width * height;
        int[] pixels = new int[total];
        int[] cache = cacheBits > 0 ? new int[1 << cacheBits] : null;
        int cacheShift = 32 - cacheBits;
        int pos = 0;
        int lastCached = 0;
        while (pos < total) {
            int x = pos % width;
            int y = pos / width;
            Huffman[] group = entropyImage == null ? codes[0] : codes[entropyImage[(y >> prefixBits) * prefixWidth + (x >> prefixBits)]];
            int symbol = group[0].read(in);
            if (symbol < NUM_LITERAL_CODES) {
                int red = group[1].read(in);
                int blue = group[2].read(in);
                int alpha = group[3].read(in);
                pixels[pos++] = alpha << 24 | red << 16 | symbol << 8 | blue;
            } else if (symbol < NUM_LITERAL_CODES + NUM_LENGTH_CODES) {
                int length = prefixValue(symbol - NUM_LITERAL_CODES);
                int distanceSymbol = group[4].read(in);
                int distance = planeDistance(width, prefixValue(distanceSymbol));
                if (distance > pos || pos + length > total) throw new IOException("Invalid VP8L backward reference");
                for (int i = 0; i < length; i++, pos++) {
                    pixels[pos] = pixels[pos - distance];
                }
            } else {
                if (cache == null) throw new IOException("VP8L color cache symbol without a cache");
                // bring the cache up to date before reading from it
                while (lastCached < pos) {
                    int p = pixels[lastCached++];
                    cache[(0x1E35A7BD * p) >>> cacheShift] = p;
                }
                int index = symbol - NUM_LITERAL_CODES - NUM_LENGTH_CODES;
                if (index >= cache.length) throw new IOException("Invalid VP8L color cache index");
                pixels[pos++] = cache[index];
            }
            if (cache != null) {
                while (lastCached < pos) {
                    int p = pixels[lastCached++];
                    cache[(0x1E35A7BD * p) >>> cacheShift] = p;
                }
            }
        }
        return pixels;
    }

    private int prefixValue(int prefix) throws IOException {
        if (prefix < 4) return prefix + 1;
        int extraBits = (prefix - 2) >> 1;
        int offset = (2 + (prefix & 1)) << extraBits;
        return offset + in.read(extraBits) + 1;
    }

    private static int planeDistance(int width, int code) {
        if (code > 120) return code - 120;
        int dx = DISTANCE_MAP[(code - 1) * 2];
        int dy = DISTANCE_MAP[(code - 1) * 2 + 1];
        int distance = dx + dy * width;
        return Math.max(1, distance);
    }

    // ---------------------------------------------------------------- prefix codes

    private Huffman readCode(int alphabetSize) throws IOException {
        int[] lengths = new int[alphabetSize];
        if (in.read(1) == 1) {
            int symbols = in.read(1) + 1;
            int first = in.read(in.read(1) == 0 ? 1 : 8);
            if (first >= alphabetSize) throw new IOException("Invalid VP8L simple code");
            lengths[first] = 1;
            if (symbols == 2) {
                int second = in.read(8);
                if (second >= alphabetSize) throw new IOException("Invalid VP8L simple code");
                lengths[second] = 1;
            }
            return new Huffman(lengths);
        }

        int[] codeLengthLengths = new int[CODE_LENGTH_CODES];
        int numCodes = in.read(4) + 4;
        if (numCodes > CODE_LENGTH_CODES) throw new IOException("Invalid VP8L code length count");
        for (int i = 0; i < numCodes; i++) {
            codeLengthLengths[CODE_LENGTH_ORDER[i]] = in.read(3);
        }
        Huffman codeLengthCode = new Huffman(codeLengthLengths);

        int maxSymbol;
        if (in.read(1) == 1) {
            int lengthBits = 2 + 2 * in.read(3);
            maxSymbol = 2 + in.read(lengthBits);
            if (maxSymbol > alphabetSize) throw new IOException("Invalid VP8L max symbol");
        } else {
            maxSymbol = alphabetSize;
        }

        int previous = 8;
        int symbol = 0;
        while (symbol < alphabetSize) {
            if (maxSymbol-- == 0) break;
            int code = codeLengthCode.read(in);
            if (code < 16) {
                lengths[symbol++] = code;
                if (code != 0) previous = code;
            } else {
                int repeat;
                int value;
                if (code == 16) {
                    repeat = 3 + in.read(2);
                    value = previous;
                } else if (code == 17) {
                    repeat = 3 + in.read(3);
                    value = 0;
                } else {
                    repeat = 11 + in.read(7);
                    value = 0;
                }
                if (symbol + repeat > alphabetSize) throw new IOException("Invalid VP8L code lengths");
                for (int i = 0; i < repeat; i++) lengths[symbol++] = value;
            }
        }
        return new Huffman(lengths);
    }

    /** Canonical prefix code, decoded bit by bit (codes are at most 15 bits, images are tiny). */
    private static final class Huffman {
        private final int[] counts = new int[16];
        private final int[] symbols;
        private final int single;

        Huffman(int[] lengths) throws IOException {
            int used = 0;
            int last = 0;
            for (int i = 0; i < lengths.length; i++) {
                if (lengths[i] > 15) throw new IOException("Invalid VP8L code length");
                if (lengths[i] != 0) {
                    counts[lengths[i]]++;
                    used++;
                    last = i;
                }
            }
            // A code with a single used symbol takes no bits at all.
            single = used <= 1 ? last : -1;
            int[] offsets = new int[16];
            for (int len = 1; len < 16; len++) offsets[len] = offsets[len - 1] + counts[len - 1];
            counts[0] = 0;
            symbols = new int[Math.max(1, used)];
            for (int i = 0; i < lengths.length; i++) {
                if (lengths[i] != 0) symbols[offsets[lengths[i]]++] = i;
            }
        }

        int read(BitReader in) throws IOException {
            if (single >= 0) return single;
            int code = 0;
            int first = 0;
            int index = 0;
            for (int len = 1; len < 16; len++) {
                code |= in.read(1);
                int count = counts[len];
                if (code - count < first) return symbols[index + (code - first)];
                index += count;
                first += count;
                first <<= 1;
                code <<= 1;
            }
            throw new IOException("Invalid VP8L prefix code");
        }
    }

    // ---------------------------------------------------------------- transforms

    private static int[] inverse(Transform t, int[] pixels, int height) {
        int width = t.xsize();
        switch (t.type()) {
            case SUBTRACT_GREEN_TRANSFORM -> {
                for (int i = 0; i < pixels.length; i++) {
                    int p = pixels[i];
                    int g = p >> 8 & 0xFF;
                    int r = ((p >> 16) + g) & 0xFF;
                    int b = (p + g) & 0xFF;
                    pixels[i] = (p & 0xFF00FF00) | r << 16 | b;
                }
                return pixels;
            }
            case PREDICTOR_TRANSFORM -> {
                int blockWidth = divRoundUp(width, 1 << t.bits());
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int pos = y * width + x;
                        int prediction;
                        if (y == 0) {
                            prediction = x == 0 ? 0xFF000000 : pixels[pos - 1];
                        } else if (x == 0) {
                            prediction = pixels[pos - width];
                        } else {
                            int mode = (t.data()[(y >> t.bits()) * blockWidth + (x >> t.bits())] >> 8) & 0xF;
                            prediction = predict(mode, pixels, pos, width);
                        }
                        pixels[pos] = addPixels(pixels[pos], prediction);
                    }
                }
                return pixels;
            }
            case CROSS_COLOR_TRANSFORM -> {
                int blockWidth = divRoundUp(width, 1 << t.bits());
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int element = t.data()[(y >> t.bits()) * blockWidth + (x >> t.bits())];
                        byte greenToRed = (byte) element;
                        byte greenToBlue = (byte) (element >> 8);
                        byte redToBlue = (byte) (element >> 16);
                        int pos = y * width + x;
                        int p = pixels[pos];
                        byte green = (byte) (p >> 8);
                        int red = (p >> 16) & 0xFF;
                        int blue = p & 0xFF;
                        red = (red + colorDelta(greenToRed, green)) & 0xFF;
                        blue = (blue + colorDelta(greenToBlue, green)) & 0xFF;
                        blue = (blue + colorDelta(redToBlue, (byte) red)) & 0xFF;
                        pixels[pos] = (p & 0xFF00FF00) | red << 16 | blue;
                    }
                }
                return pixels;
            }
            case COLOR_INDEXING_TRANSFORM -> {
                int bits = t.bits();
                int[] palette = t.data();
                int packedWidth = divRoundUp(width, 1 << bits);
                int bitsPerPixel = 8 >> bits;
                int mask = (1 << bitsPerPixel) - 1;
                int[] out = new int[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int packed = (pixels[y * packedWidth + (x >> bits)] >> 8) & 0xFF;
                        int index = (packed >> ((x & ((1 << bits) - 1)) * bitsPerPixel)) & mask;
                        out[y * width + x] = index < palette.length ? palette[index] : 0;
                    }
                }
                return out;
            }
            default -> {
                return pixels;
            }
        }
    }

    private static int predict(int mode, int[] p, int pos, int width) {
        int left = p[pos - 1];
        int top = p[pos - width];
        int topLeft = p[pos - width - 1];
        int topRight = p[pos - width + 1];
        return switch (mode) {
            case 1 -> left;
            case 2 -> top;
            case 3 -> topRight;
            case 4 -> topLeft;
            case 5 -> average(average(left, topRight), top);
            case 6 -> average(left, topLeft);
            case 7 -> average(left, top);
            case 8 -> average(topLeft, top);
            case 9 -> average(top, topRight);
            case 10 -> average(average(left, topLeft), average(top, topRight));
            case 11 -> select(left, top, topLeft);
            case 12 -> clampAddSubtractFull(left, top, topLeft);
            case 13 -> clampAddSubtractHalf(average(left, top), topLeft);
            default -> 0xFF000000;
        };
    }

    private static int average(int a, int b) {
        return (((a ^ b) & 0xFEFEFEFE) >>> 1) + (a & b);
    }

    private static int select(int left, int top, int topLeft) {
        int distanceToLeft = 0;
        int distanceToTop = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int l = left >>> shift & 0xFF;
            int t = top >>> shift & 0xFF;
            int tl = topLeft >>> shift & 0xFF;
            int estimate = l + t - tl;
            distanceToLeft += Math.abs(estimate - l);
            distanceToTop += Math.abs(estimate - t);
        }
        return distanceToLeft < distanceToTop ? left : top;
    }

    private static int clampAddSubtractFull(int a, int b, int c) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int v = (a >>> shift & 0xFF) + (b >>> shift & 0xFF) - (c >>> shift & 0xFF);
            out |= clamp(v) << shift;
        }
        return out;
    }

    private static int clampAddSubtractHalf(int a, int b) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ac = a >>> shift & 0xFF;
            int bc = b >>> shift & 0xFF;
            out |= clamp(ac + (ac - bc) / 2) << shift;
        }
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static int colorDelta(byte transform, byte color) {
        return (transform * color) >> 5;
    }

    private static int addPixels(int a, int b) {
        int alphaGreen = (a & 0xFF00FF00) + (b & 0xFF00FF00);
        int redBlue = (a & 0x00FF00FF) + (b & 0x00FF00FF);
        return (alphaGreen & 0xFF00FF00) | (redBlue & 0x00FF00FF);
    }

    private static int divRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    /** Little-endian bit reader. */
    private static final class BitReader {
        private final byte[] data;
        private final int end;
        private int pos;
        private long buffer;
        private int bits;

        BitReader(byte[] data, int offset, int length) {
            this.data = data;
            this.pos = offset;
            this.end = Math.min(data.length, offset + length);
        }

        int read(int count) throws IOException {
            if (count == 0) return 0;
            while (bits < count) {
                if (pos >= end) throw new IOException("Truncated VP8L stream");
                buffer |= (long) (data[pos++] & 0xFF) << bits;
                bits += 8;
            }
            int value = (int) (buffer & ((1L << count) - 1));
            buffer >>>= count;
            bits -= count;
            return value;
        }
    }
}
