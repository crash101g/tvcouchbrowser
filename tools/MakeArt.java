import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Draws the TV banner (320x180 dp at xhdpi) and the launcher icon. Run: java tools/MakeArt.java */
public class MakeArt {
    public static void main(String[] a) throws Exception {
        String res = "app/src/main/res/";
        ImageIO.write(banner(640, 360), "png", new File(res + "drawable-xhdpi/banner.png"));
        ImageIO.write(icon(96), "png", new File(res + "mipmap-xhdpi/ic_launcher.png"));
    }

    static Graphics2D g(BufferedImage im) {
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    static void couch(Graphics2D g, double x, double y, double s) {
        g.setColor(new Color(0x4DA3FF));
        g.fill(new RoundRectangle2D.Double(x + 0.12 * s, y, 0.76 * s, 0.42 * s, 0.12 * s, 0.12 * s));
        g.setColor(new Color(0x2F7FE0));
        g.fill(new RoundRectangle2D.Double(x, y + 0.28 * s, 0.24 * s, 0.38 * s, 0.1 * s, 0.1 * s));
        g.fill(new RoundRectangle2D.Double(x + 0.76 * s, y + 0.28 * s, 0.24 * s, 0.38 * s, 0.1 * s, 0.1 * s));
        g.setColor(new Color(0x7FBFFF));
        g.fill(new RoundRectangle2D.Double(x + 0.18 * s, y + 0.38 * s, 0.64 * s, 0.2 * s, 0.06 * s, 0.06 * s));
        g.setColor(new Color(0x2F7FE0));
        g.fill(new Rectangle2D.Double(x + 0.08 * s, y + 0.66 * s, 0.06 * s, 0.1 * s));
        g.fill(new Rectangle2D.Double(x + 0.86 * s, y + 0.66 * s, 0.06 * s, 0.1 * s));
    }

    static BufferedImage banner(int w, int h) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(im);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1B2230), w, h, new Color(0x0C0F14)));
        g.fillRect(0, 0, w, h);
        couch(g, 40, 120, 150);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
        g.drawString("TVCouch", 220, 170);
        g.setColor(new Color(0x9FB3C8));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 54));
        g.drawString("Browser", 222, 240);
        return im;
    }

    static BufferedImage icon(int s) {
        BufferedImage im = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(im);
        g.setColor(new Color(0x1B2230));
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, s * 0.25, s * 0.25));
        couch(g, s * 0.14, s * 0.26, s * 0.72);
        return im;
    }
}
