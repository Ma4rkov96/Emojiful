package com.hrznstudio.emojiful.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Picks a decoder from the file contents (magic bytes) rather than the URL extension, so links
 * with query strings or a wrong extension still decode correctly.
 */
public final class ImageDecoder {

    public enum Format { GIF, WEBP, PNG, JPEG, BMP, UNKNOWN }

    private ImageDecoder() {
    }

    public static Format sniff(byte[] data) {
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return Format.GIF;
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return Format.WEBP;
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return Format.PNG;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return Format.JPEG;
        if (data.length >= 2 && data[0] == 'B' && data[1] == 'M') return Format.BMP;
        return Format.UNKNOWN;
    }

    public static void decode(byte[] data, boolean animate, FrameSink sink) throws IOException {
        switch (sniff(data)) {
            case GIF -> GifDecoder.decode(data, animate, sink);
            case WEBP -> WebPDecoder.decode(data, animate, sink);
            default -> {
                BufferedImage image;
                try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                    Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) {
                        throw new IOException("Unsupported image format (" + sniff(data) + ")");
                    }
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(input, true, true);
                        checkSize(reader.getWidth(0), reader.getHeight(0));
                        image = reader.read(0);
                    } finally {
                        reader.dispose();
                    }
                }
                sink.accept(WebPDecoder.toArgb(image), GifDecoder.DEFAULT_DELAY_MS);
            }
        }
    }

    /** Largest accepted side of a decoded image or canvas; larger files could exhaust memory when decoded. */
    public static final int MAX_SIDE = 4096;

    /** Rejects images whose decoded size would need excessive memory, before anything is allocated. */
    public static void checkSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) {
            throw new IOException("Image is too large (" + width + "x" + height + ", max " + MAX_SIDE + ")");
        }
    }
}
