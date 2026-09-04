package com.shinyhut.vernacular.client.rendering.renderers;

import android.graphics.Bitmap;

/** Helpers replacing java.awt Color / Graphics fill for Android Bitmaps. */
final class BitmapColors {

    private BitmapColors() {
    }

    static int rgb(int red, int green, int blue) {
        return 0xFF000000 | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    static int rgba(int red, int green, int blue, int alpha) {
        return ((alpha & 0xFF) << 24)
                | ((red & 0xFF) << 16)
                | ((green & 0xFF) << 8)
                | (blue & 0xFF);
    }

    static void fillRect(Bitmap destination, int color, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(destination.getWidth(), x + width);
        int bottom = Math.min(destination.getHeight(), y + height);
        if (left >= right || top >= bottom) {
            return;
        }
        int w = right - left;
        int h = bottom - top;
        int[] row = new int[w];
        for (int i = 0; i < w; i++) {
            row[i] = color;
        }
        for (int rowY = top; rowY < bottom; rowY++) {
            destination.setPixels(row, 0, w, left, rowY, w, 1);
        }
        // silence unused
        if (h < 0) {
            throw new AssertionError();
        }
    }
}
