package com.hrznstudio.emojiful.image;

import java.awt.image.BufferedImage;

/**
 * Receives fully composited animation frames from a decoder, one at a time, so that large
 * animations never have to be held in memory at their original resolution.
 */
public interface FrameSink {

    /**
     * @param canvas  the full, composited frame. Only valid during this call; copy what you need.
     * @param delayMs how long this frame is shown, in milliseconds (already clamped to a sane minimum)
     * @return false to stop decoding (e.g. frame limit reached)
     */
    boolean accept(BufferedImage canvas, int delayMs);
}
