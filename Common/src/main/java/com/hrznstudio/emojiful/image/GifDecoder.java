package com.hrznstudio.emojiful.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * GIF decoder that composites frames the way browsers do: frames are placed at their offset on a
 * logical screen, disposal methods are honoured and tiny delays are clamped to 100 ms.
 * <p>
 * The previous implementation drew every raw frame stretched to the full emoji, so optimized GIFs
 * (where a frame only stores the changed region or uses transparency for unchanged pixels)
 * appeared torn and jittery, and frames with a 0 delay were dropped entirely.
 */
public final class GifDecoder {

    /** Browsers treat delays of 0 or 1 centisecond as 100 ms; we do the same. */
    public static final int MIN_DELAY_MS = 20;
    public static final int DEFAULT_DELAY_MS = 100;

    private static final String STREAM_FORMAT = "javax_imageio_gif_stream_1.0";
    private static final String IMAGE_FORMAT = "javax_imageio_gif_image_1.0";

    private GifDecoder() {
    }

    public static void decode(byte[] data, boolean animate, FrameSink sink) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) throw new IOException("No GIF reader available");
        ImageReader reader = readers.next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(input, false, false);

            int width = 0;
            int height = 0;
            IIOMetadata streamMetadata = reader.getStreamMetadata();
            if (streamMetadata != null) {
                IIOMetadataNode screen = child((IIOMetadataNode) streamMetadata.getAsTree(STREAM_FORMAT), "LogicalScreenDescriptor");
                if (screen != null) {
                    width = parseInt(screen.getAttribute("logicalScreenWidth"), 0);
                    height = parseInt(screen.getAttribute("logicalScreenHeight"), 0);
                }
            }

            BufferedImage canvas = null;
            int decoded = 0;
            for (int index = 0; ; index++) {
                BufferedImage frame;
                IIOMetadataNode metadata;
                try {
                    ImageDecoder.checkSize(reader.getWidth(index), reader.getHeight(index));
                    frame = reader.read(index);
                    metadata = (IIOMetadataNode) reader.getImageMetadata(index).getAsTree(IMAGE_FORMAT);
                } catch (IndexOutOfBoundsException end) {
                    break;
                } catch (IOException | RuntimeException broken) {
                    // A corrupt or truncated frame: keep what we already have instead of failing the whole emoji.
                    if (decoded == 0) throw broken instanceof IOException io ? io : new IOException(broken);
                    break;
                }

                IIOMetadataNode descriptor = child(metadata, "ImageDescriptor");
                IIOMetadataNode control = child(metadata, "GraphicControlExtension");
                int x = descriptor == null ? 0 : parseInt(descriptor.getAttribute("imageLeftPosition"), 0);
                int y = descriptor == null ? 0 : parseInt(descriptor.getAttribute("imageTopPosition"), 0);
                String disposal = control == null ? "none" : control.getAttribute("disposalMethod");
                int delay = control == null ? DEFAULT_DELAY_MS : delayMs(parseInt(control.getAttribute("delayTime"), 10));

                if (canvas == null) {
                    if (width <= 0 || height <= 0) {
                        width = x + frame.getWidth();
                        height = y + frame.getHeight();
                    }
                    ImageDecoder.checkSize(width, height);
                    canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                }

                BufferedImage previous = null;
                if ("restoreToPrevious".equals(disposal)) {
                    previous = copy(canvas);
                }

                Graphics2D graphics = canvas.createGraphics();
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.drawImage(frame, x, y, null);
                graphics.dispose();

                decoded++;
                if (!sink.accept(canvas, delay) || !animate) break;

                Graphics2D dispose = canvas.createGraphics();
                if ("restoreToBackgroundColor".equals(disposal)) {
                    // Browsers clear to transparent rather than to the background colour.
                    dispose.setComposite(AlphaComposite.Clear);
                    dispose.fillRect(x, y, frame.getWidth(), frame.getHeight());
                } else if (previous != null) {
                    dispose.setComposite(AlphaComposite.Src);
                    dispose.drawImage(previous, 0, 0, null);
                }
                dispose.dispose();
            }
            if (decoded == 0) throw new IOException("GIF contains no frames");
        } finally {
            reader.dispose();
        }
    }

    static int delayMs(int centiseconds) {
        int ms = centiseconds * 10;
        return ms < MIN_DELAY_MS ? DEFAULT_DELAY_MS : ms;
    }

    static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    private static IIOMetadataNode child(IIOMetadataNode node, String name) {
        if (node == null) return null;
        for (int i = 0; i < node.getLength(); i++) {
            if (node.item(i).getNodeName().equals(name)) return (IIOMetadataNode) node.item(i);
        }
        return null;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
