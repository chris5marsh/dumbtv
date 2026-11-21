package tv.dumbtv.home;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Evens out banners that do not look like the rest. Some apps ship their full-screen splash as
 * the banner, so the logo fills a fifth of the tile (BBC iPlayer, Channel 4); others ship a
 * square icon, which a 16:9 tile stretches (Channel 5). Either way the logo is found by what
 * differs from the banner's corner colour, then redrawn centred at a usual size on that colour.
 * Banners that are already 16:9 with a logo of ordinary size are left as they are.
 */
final class Banner {
    private static final int OUT_W = 640, OUT_H = 360;
    /** Sampling size for finding the logo: enough detail, little memory. */
    private static final int SAMPLE = 320;
    /** A logo narrower and shorter than this share of a 16:9 banner gets enlarged. */
    static final float SMALL = 0.4f;
    /** The box an enlarged logo fills, as shares of the tile's width and height. */
    static final float BOX_W = 0.62f, BOX_H = 0.55f;
    private static final int DIFFERENT = 48;

    private Banner() {}

    /** Returns {@code banner}, or an evened-out copy of it. Slow: off the UI thread. */
    static Drawable fit(Drawable banner) {
        int iw = banner.getIntrinsicWidth(), ih = banner.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) return banner;
        float scale = SAMPLE / (float) Math.max(iw, ih);
        int sw = Math.max(1, Math.round(iw * scale)), sh = Math.max(1, Math.round(ih * scale));
        Bitmap sample = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        banner.setBounds(0, 0, sw, sh);
        banner.draw(new Canvas(sample));
        int[] px = new int[sw * sh];
        sample.getPixels(px, 0, sw, 0, 0, sw, sh);
        int[] box = content(px, sw, sh);
        if (box == null || !needsFit(sw, sh, box)) {
            sample.recycle();
            return banner;
        }
        Bitmap out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(px[0]);
        int[] to = place(box[2] - box[0], box[3] - box[1], OUT_W, OUT_H);
        // Draw the logo from the full-size banner, not the sample, so it stays sharp.
        float up = 1 / scale;
        Rect from = new Rect(Math.round(box[0] * up), Math.round(box[1] * up),
                Math.round(box[2] * up), Math.round(box[3] * up));
        Bitmap logo = Bitmap.createBitmap(Math.max(1, from.width()), Math.max(1, from.height()),
                Bitmap.Config.ARGB_8888);
        Canvas lc = new Canvas(logo);
        lc.translate(-from.left, -from.top);
        banner.setBounds(0, 0, iw, ih);
        banner.draw(lc);
        c.drawBitmap(logo, null, new Rect(to[0], to[1], to[2], to[3]), new Paint(Paint.FILTER_BITMAP_FLAG));
        logo.recycle();
        sample.recycle();
        return new BitmapDrawable(Resources.getSystem(), out);
    }

    /**
     * The logo's bounds {left, top, right, bottom} in {@code px}: every pixel that differs from
     * the top-left corner. Null when the corner is see-through or nothing differs from it.
     */
    static int[] content(int[] px, int w, int h) {
        int bg = px[0];
        if ((bg >>> 24) != 0xFF) return null;
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (distance(px[y * w + x], bg) <= DIFFERENT) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        return right < 0 ? null : new int[] {left, top, right + 1, bottom + 1};
    }

    /** True for a banner that is not 16:9, or whose logo is small in both directions. */
    static boolean needsFit(int w, int h, int[] box) {
        boolean wide = Math.abs(w / (float) h - 16f / 9f) < 0.1f;
        boolean small = box[2] - box[0] < w * SMALL && box[3] - box[1] < h * SMALL;
        return !wide || small;
    }

    /** Where a {@code cw} by {@code ch} logo goes in a {@code w} by {@code h} tile: centred, fitted to the box. */
    static int[] place(int cw, int ch, int w, int h) {
        float s = Math.min(w * BOX_W / cw, h * BOX_H / ch);
        int dw = Math.round(cw * s), dh = Math.round(ch * s);
        int left = (w - dw) / 2, top = (h - dh) / 2;
        return new int[] {left, top, left + dw, top + dh};
    }

    private static int distance(int a, int b) {
        return Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)) + Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF)) + Math.abs((a >>> 24) - (b >>> 24));
    }
}
