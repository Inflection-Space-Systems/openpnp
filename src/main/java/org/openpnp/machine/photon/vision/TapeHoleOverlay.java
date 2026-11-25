package org.openpnp.machine.photon.vision;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.vision.pipeline.CvStage;

/**
 * Draws what tape vision found over the camera image, for showing in the camera view:
 * <ul>
 * <li>green circles and a green line: the sprocket holes the result is based on, and their row;</li>
 * <li>orange circles: other circles that were found and not used;</li>
 * <li>white cross: the configured pick location (the image centre);</li>
 * <li>magenta cross and line: where the pocket was found, and the correction from the pick
 * location to it.</li>
 * </ul>
 */
public final class TapeHoleOverlay {
    static final Color USED_HOLE = new Color(0, 230, 0);
    static final Color UNUSED_CIRCLE = new Color(255, 150, 0);
    static final Color PICK_LOCATION = Color.WHITE;
    static final Color POCKET = new Color(255, 0, 255);

    private TapeHoleOverlay() {
    }

    /** One line saying what was measured, to go with the picture. */
    public static String describe(TapeHoleLocator.Measurement measurement) {
        if (measurement == null) {
            return "No sprocket holes found";
        }
        return String.format("Pocket at X %+.2f, Y %+.2f mm from %d sprocket hole%s",
                measurement.getOffsetX(), measurement.getOffsetY(), measurement.getHoleCount(),
                measurement.getHoleCount() == 1 ? "" : "s");
    }

    /**
     * @param image the captured image. It is not changed: a copy is drawn on.
     * @param circles every circle the pipeline found, in pixels.
     * @param used those of them the measurement is based on.
     * @param measurement null if nothing could be measured.
     */
    public static BufferedImage draw(BufferedImage image, Camera camera,
            List<CvStage.Result.Circle> circles, List<CvStage.Result.Circle> used,
            TapeHoleLocator.Measurement measurement) {
        BufferedImage overlay = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = overlay.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float thin = Math.max(1f, image.getWidth() / 600f);
            float thick = thin * 2;
            double centreX = image.getWidth() / 2.0;
            double centreY = image.getHeight() / 2.0;

            g.setStroke(new BasicStroke(thin));
            g.setColor(UNUSED_CIRCLE);
            for (CvStage.Result.Circle circle : circles) {
                if (!used.contains(circle)) {
                    drawCircle(g, circle);
                }
            }

            g.setColor(USED_HOLE);
            if (used.size() >= 2) {
                CvStage.Result.Circle first = used.get(0);
                CvStage.Result.Circle last = used.get(used.size() - 1);
                g.draw(new Line2D.Double(first.x, first.y, last.x, last.y));
            }
            g.setStroke(new BasicStroke(thick));
            for (CvStage.Result.Circle circle : used) {
                drawCircle(g, circle);
            }

            double arm = image.getWidth() / 50.0;
            g.setStroke(new BasicStroke(thin));
            g.setColor(PICK_LOCATION);
            drawCross(g, centreX, centreY, arm);

            if (measurement != null) {
                // The measurement is in true millimetres; the image is in the camera's.
                Location unitsPerPixel = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
                double pocketX = centreX + measurement.getOffsetX() / measurement.getScale() / unitsPerPixel.getX();
                double pocketY = centreY - measurement.getOffsetY() / measurement.getScale() / unitsPerPixel.getY();
                g.setColor(POCKET);
                g.setStroke(new BasicStroke(thick));
                g.draw(new Line2D.Double(centreX, centreY, pocketX, pocketY));
                drawCross(g, pocketX, pocketY, arm * 1.5);
            }
            return overlay;
        }
        finally {
            g.dispose();
        }
    }

    private static void drawCircle(Graphics2D g, CvStage.Result.Circle circle) {
        double r = circle.diameter / 2;
        g.draw(new Ellipse2D.Double(circle.x - r, circle.y - r, circle.diameter, circle.diameter));
    }

    private static void drawCross(Graphics2D g, double x, double y, double arm) {
        g.draw(new Line2D.Double(x - arm, y, x + arm, y));
        g.draw(new Line2D.Double(x, y - arm, x, y + arm));
    }
}
