package tv.dumbtv.home;

import java.util.Arrays;

/** Host-side checks for evening out odd banners, without a device or test framework. */
public final class BannerTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static int[] canvas(int w, int h, int bg) {
        int[] px = new int[w * h];
        Arrays.fill(px, bg);
        return px;
    }

    private static void paint(int[] px, int w, int l, int t, int r, int b, int color) {
        for (int y = t; y < b; y++) for (int x = l; x < r; x++) px[y * w + x] = color;
    }

    public static void main(String[] args) {
        int black = 0xFF000000, pink = 0xFFFF4C98;
        int[] splash = canvas(320, 180, black);
        paint(splash, 320, 120, 72, 200, 108, pink);
        check(Arrays.equals(Banner.content(splash, 320, 180), new int[] {120, 72, 200, 108}), "logo bounds");
        check(Banner.needsFit(320, 180, Banner.content(splash, 320, 180)), "splash with a small logo is fitted");

        int[] full = canvas(320, 180, black);
        paint(full, 320, 40, 30, 280, 150, pink);
        check(!Banner.needsFit(320, 180, Banner.content(full, 320, 180)), "a normal banner is left alone");

        int[] square = canvas(200, 200, 0xFF12253F);
        paint(square, 200, 60, 40, 140, 160, 0xFFFBF433);
        check(Banner.needsFit(200, 200, Banner.content(square, 200, 200)), "a square banner is fitted");

        check(Banner.content(canvas(320, 180, black), 320, 180) == null, "a plain banner has no logo");
        check(Banner.content(canvas(320, 180, 0x00000000), 320, 180) == null, "see-through corners are left alone");

        int[] wide = Banner.place(400, 100, 640, 360);
        check(wide[2] - wide[0] == Math.round(640 * Banner.BOX_W), "a wide logo fills the box's width");
        check(Math.abs(wide[1] + wide[3] - 360) <= 1, "centred vertically, to the pixel");
        int[] tall = Banner.place(100, 200, 640, 360);
        check(tall[3] - tall[1] == Math.round(360 * Banner.BOX_H), "a tall logo fills the box's height");
        check(Math.abs(tall[0] + tall[2] - 640) <= 1, "centred horizontally, to the pixel");
        System.out.println("BannerTest passed");
    }
}
