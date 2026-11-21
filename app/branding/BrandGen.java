import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Generates banner, launcher icon and splash image from ONE png file.
 * Usage: java BrandGen.java <image.png> <output res dir>
 * Optional: a file "app_icon.png" next to <image.png> is used ONLY for the launcher icon (square, logo only);
 * banner and splash always come from <image.png>.
 * (Gradle runs it on every build - see task generateBrand in app/build.gradle.kts.)
 */
public class BrandGen {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File srcFile = new File(args[0]);
        File out = new File(args[1]);
        BufferedImage in = ImageIO.read(srcFile);
        if (in == null) throw new IllegalStateException("Cannot read PNG image: " + srcFile);

        BufferedImage src = toArgb(in);
        BufferedImage iconSrc = src;
        File iconFile = new File(srcFile.getAbsoluteFile().getParentFile(), "app_icon.png");
        if (iconFile.isFile()) {
            BufferedImage ii = ImageIO.read(iconFile);
            if (ii != null) iconSrc = toArgb(ii);
        }

        deleteDir(out);
        Color bg = sampleBg(src);

        // 1) Android TV banner 16:9 (960x540): image centered, side background stretched from the image edge (seamless)
        save(banner(src, 960, 540, bg), new File(out, "drawable-nodpi/banner.png"));
        // 2) Adaptive icon: foreground = image shrunk into the safe zone; background = colour sampled from the image corners
        save(foreground(iconSrc, 432, 0.68), new File(out, "drawable-nodpi/ic_launcher_fg.png"));
        // 3) Splash screen image
        save(fit(src, 800), new File(out, "drawable-nodpi/splash_logo.png"));
        // 4) Brand background colour (icon, window background at launch, splash screen)
        File colors = new File(out, "values/brand_colors.xml");
        colors.getParentFile().mkdirs();
        String hex = String.format("#%02X%02X%02X", bg.getRed(), bg.getGreen(), bg.getBlue());
        Files.write(colors.toPath(), ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <color name=\"brand_bg\">"
                + hex + "</color>\n</resources>\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("Generated banner/icon/splash from " + srcFile.getName() + " (background " + hex + ")");
    }

    static BufferedImage toArgb(BufferedImage in) {
        BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(in, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Background colour = average of the 4 image corners (transparent pixels count as white). */
    static Color sampleBg(BufferedImage img) {
        int n = Math.max(1, Math.min(6, Math.min(img.getWidth(), img.getHeight()) / 4));
        long r = 0, g = 0, b = 0, cnt = 0;
        int[] xs = {0, img.getWidth() - n};
        int[] ys = {0, img.getHeight() - n};
        for (int cx : xs) for (int cy : ys) for (int x = cx; x < cx + n; x++) for (int y = cy; y < cy + n; y++) {
            int p = img.getRGB(x, y);
            int a = (p >>> 24) & 0xFF;
            r += (((p >> 16) & 0xFF) * a + 255 * (255 - a)) / 255;
            g += (((p >> 8) & 0xFF) * a + 255 * (255 - a)) / 255;
            b += ((p & 0xFF) * a + 255 * (255 - a)) / 255;
            cnt++;
        }
        return new Color((int) (r / cnt), (int) (g / cnt), (int) (b / cnt));
    }

    static BufferedImage banner(BufferedImage src, int w, int h, Color bg) {
        double scale = Math.min((double) w / src.getWidth(), (double) h / src.getHeight());
        int iw = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int ih = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage icon = resize(src, iw, ih);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, w, h);
        int x0 = (w - iw) / 2, y0 = (h - ih) / 2;
        if (x0 > 0) {   // image narrower than the frame: stretch the outermost pixel columns to both sides
            g.drawImage(icon.getSubimage(0, 0, 1, ih), 0, y0, x0, ih, null);
            g.drawImage(icon.getSubimage(iw - 1, 0, 1, ih), x0 + iw, y0, w - x0 - iw, ih, null);
        }
        if (y0 > 0) {   // image shorter than the frame: stretch the outermost pixel rows up/down
            g.drawImage(icon.getSubimage(0, 0, iw, 1), x0, 0, iw, y0, null);
            g.drawImage(icon.getSubimage(0, ih - 1, iw, 1), x0, y0 + ih, iw, h - y0 - ih, null);
        }
        g.drawImage(icon, x0, y0, null);
        g.dispose();
        return out;
    }

    static BufferedImage foreground(BufferedImage src, int size, double fraction) {
        int side = (int) Math.round(size * fraction);
        double scale = Math.min((double) side / src.getWidth(), (double) side / src.getHeight());
        int iw = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int ih = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage icon = resize(src, iw, ih);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(icon, (size - iw) / 2, (size - ih) / 2, null);
        g.dispose();
        return out;
    }

    static BufferedImage fit(BufferedImage src, int max) {
        int m = Math.max(src.getWidth(), src.getHeight());
        if (m <= max) return src;
        double s = (double) max / m;
        return resize(src, Math.max(1, (int) Math.round(src.getWidth() * s)), Math.max(1, (int) Math.round(src.getHeight() * s)));
    }

    /** Downscale by halves, then to the exact size: smooth small images, no aliasing. */
    static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = step(cur, cur.getWidth() / 2, cur.getHeight() / 2, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        return step(cur, w, h, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    static BufferedImage step(BufferedImage src, int w, int h, Object interpolation) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static void save(BufferedImage img, File f) throws Exception {
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
    }

    static void deleteDir(File d) {
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) { if (k.isDirectory()) deleteDir(k); k.delete(); }
        d.delete();
    }
}
