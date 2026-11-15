package com.hrznstudio.emojiful.image;

import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * WebP decoder (static and animated).
 * <ul>
 *     <li>RIFF container, animation frames, blending and disposal: handled here.</li>
 *     <li>Lossless (VP8L) bitstreams and the ALPH alpha channel: {@link Vp8lDecoder}.</li>
 *     <li>Lossy (VP8) colour data: the bundled TwelveMonkeys reader.</li>
 * </ul>
 */
public final class WebPDecoder {

    private static final int FLAG_ANIMATION = 0x02;

    private WebPDecoder() {
    }

    public static void decode(byte[] data, boolean animate, FrameSink sink) throws IOException {
        if (data.length < 20 || !fourCC(data, 0).equals("RIFF") || !fourCC(data, 8).equals("WEBP")) {
            throw new IOException("Not a WebP file");
        }
        int end = (int) Math.min(data.length, 8L + (u32(data, 4) & 0xFFFFFFFFL));

        int pos = 12;
        if (!fourCC(data, pos).equals("VP8X")) {
            sink.accept(decodeBitstream(data, pos, end, -1, -1), GifDecoder.DEFAULT_DELAY_MS);
            return;
        }

        int vp8x = pos + 8;
        int canvasWidth = 1 + u24(data, vp8x + 4);
        int canvasHeight = 1 + u24(data, vp8x + 7);
        ImageDecoder.checkSize(canvasWidth, canvasHeight);
        if ((data[vp8x] & FLAG_ANIMATION) == 0) {
            BufferedImage image = decodeBitstream(data, chunkEnd(data, pos), end, canvasWidth, canvasHeight);
            sink.accept(image, GifDecoder.DEFAULT_DELAY_MS);
            return;
        }

        BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        int decoded = 0;
        while (pos + 8 <= end) {
            String id = fourCC(data, pos);
            int size = u32(data, pos + 4);
            int payload = pos + 8;
            if (size < 0 || payload + size > end) break;

            if (id.equals("ANMF") && size >= 16) {
                int x = 2 * u24(data, payload);
                int y = 2 * u24(data, payload + 3);
                int width = 1 + u24(data, payload + 6);
                int height = 1 + u24(data, payload + 9);
                int duration = u24(data, payload + 12);
                int flags = data[payload + 15] & 0xFF;
                boolean noBlend = (flags & 0x02) != 0;
                boolean disposeToBackground = (flags & 0x01) != 0;

                BufferedImage frame;
                try {
                    frame = decodeBitstream(data, payload + 16, payload + size, width, height);
                } catch (IOException | RuntimeException broken) {
                    if (decoded == 0) throw broken instanceof IOException io ? io : new IOException(broken);
                    break;
                }

                Graphics2D graphics = canvas.createGraphics();
                graphics.setComposite(noBlend ? AlphaComposite.Src : AlphaComposite.SrcOver);
                graphics.drawImage(frame, x, y, null);
                graphics.dispose();

                decoded++;
                int delay = duration <= 10 ? GifDecoder.DEFAULT_DELAY_MS : duration;
                if (!sink.accept(canvas, delay) || !animate) return;

                if (disposeToBackground) {
                    Graphics2D clear = canvas.createGraphics();
                    clear.setComposite(AlphaComposite.Clear);
                    clear.fillRect(x, y, width, height);
                    clear.dispose();
                }
            }
            pos = chunkEnd(data, pos);
        }
        if (decoded == 0) throw new IOException("Animated WebP contains no frames");
    }

    /**
     * Decodes the image chunks (ALPH, VP8, VP8L) found between {@code from} and {@code to}.
     * Width and height are only needed for ALPH; pass -1 when unknown.
     */
    private static BufferedImage decodeBitstream(byte[] data, int from, int to, int width, int height) throws IOException {
        int alphaStart = -1;
        int alphaSize = 0;
        for (int pos = from; pos + 8 <= to; pos = chunkEnd(data, pos)) {
            String id = fourCC(data, pos);
            int size = u32(data, pos + 4);
            int payload = pos + 8;
            if (size < 0 || payload + size > to) throw new IOException("Truncated WebP chunk " + id);
            switch (id) {
                case "ALPH" -> {
                    alphaStart = payload;
                    alphaSize = size;
                }
                case "VP8L" -> {
                    Vp8lDecoder.Result result = Vp8lDecoder.decode(data, payload, size);
                    BufferedImage image = new BufferedImage(result.width(), result.height(), BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0, 0, result.width(), result.height(), result.argb(), 0, result.width());
                    return image;
                }
                case "VP8 " -> {
                    BufferedImage color = toArgb(readLossy(data, pos, Math.min(to, chunkEnd(data, pos))));
                    if (alphaStart >= 0) {
                        applyAlpha(color, data, alphaStart, alphaSize);
                    }
                    return color;
                }
                default -> {
                }
            }
        }
        throw new IOException("WebP frame has no image data");
    }

    /** Decodes a lossy VP8 chunk by wrapping it in a minimal RIFF container for the TwelveMonkeys reader. */
    private static BufferedImage readLossy(byte[] data, int chunkStart, int chunkEnd) throws IOException {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeU32(file, 4 + (chunkEnd - chunkStart));
        file.write("WEBP".getBytes(StandardCharsets.US_ASCII));
        file.write(data, chunkStart, chunkEnd - chunkStart);

        ImageReader reader;
        try {
            reader = new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi().createReaderInstance(null);
        } catch (LinkageError missing) {
            throw new IOException("WebP support library is missing", missing);
        }
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(file.toByteArray()))) {
            reader.setInput(input, true, true);
            ImageDecoder.checkSize(reader.getWidth(0), reader.getHeight(0));
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    /** ALPH chunk: optional prediction filter over raw or VP8L-compressed alpha values. */
    private static void applyAlpha(BufferedImage image, byte[] data, int offset, int size) throws IOException {
        if (size < 1) return;
        int width = image.getWidth();
        int height = image.getHeight();
        int header = data[offset] & 0xFF;
        int compression = header & 0x03;
        int filter = (header >> 2) & 0x03;

        int[] alpha = new int[width * height];
        if (compression == 0) {
            if (size - 1 < alpha.length) throw new IOException("Truncated WebP alpha");
            for (int i = 0; i < alpha.length; i++) alpha[i] = data[offset + 1 + i] & 0xFF;
        } else if (compression == 1) {
            int[] argb = Vp8lDecoder.decodeHeaderless(data, offset + 1, size - 1, width, height);
            for (int i = 0; i < alpha.length; i++) alpha[i] = (argb[i] >> 8) & 0xFF;
        } else {
            throw new IOException("Unknown WebP alpha compression");
        }

        if (filter != 0) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = y * width + x;
                    int prediction;
                    if (x == 0 && y == 0) {
                        prediction = 0;
                    } else if (y == 0) {
                        prediction = alpha[i - 1];
                    } else if (x == 0) {
                        prediction = alpha[i - width];
                    } else {
                        int left = alpha[i - 1];
                        int top = alpha[i - width];
                        prediction = switch (filter) {
                            case 1 -> left;
                            case 2 -> top;
                            default -> Math.max(0, Math.min(255, left + top - alpha[i - width - 1]));
                        };
                    }
                    alpha[i] = (alpha[i] + prediction) & 0xFF;
                }
            }
        }

        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                row[x] = (row[x] & 0x00FFFFFF) | alpha[y * width + x] << 24;
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
    }

    static BufferedImage toArgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) return image;
        return GifDecoder.copy(image);
    }

    private static int chunkEnd(byte[] data, int pos) {
        int size = u32(data, pos + 4);
        return pos + 8 + size + (size & 1);
    }

    private static String fourCC(byte[] data, int pos) {
        if (pos + 4 > data.length) return "";
        return new String(data, pos, 4, StandardCharsets.US_ASCII);
    }

    private static int u24(byte[] data, int pos) {
        return (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8 | (data[pos + 2] & 0xFF) << 16;
    }

    private static int u32(byte[] data, int pos) {
        return (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8 | (data[pos + 2] & 0xFF) << 16 | (data[pos + 3] & 0xFF) << 24;
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write(value >> 8 & 0xFF);
        out.write(value >> 16 & 0xFF);
        out.write(value >> 24 & 0xFF);
    }
}
