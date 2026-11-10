package com.classcontrol.ui.app;

import javax.swing.ImageIcon;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

public final class ImageUtil {

    private ImageUtil() {}

    public static ImageIcon roundedIcon(ImageIcon source, int radius) {
        int w = source.getIconWidth();
        int h = source.getIconHeight();
        BufferedImage rounded = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = rounded.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setClip(new RoundRectangle2D.Float(0, 0, w, h, radius, radius));
        g2.drawImage(source.getImage(), 0, 0, null);
        g2.dispose();
        return new ImageIcon(rounded);
    }
}
