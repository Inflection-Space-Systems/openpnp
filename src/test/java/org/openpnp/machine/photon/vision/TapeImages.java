package org.openpnp.machine.photon.vision;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;

/** Test images of tape, and a camera that shows them. */
public final class TapeImages {
    /** The top camera's calibration on the machine the fixture photos came from. */
    public static final double PIXELS_PER_MM = 91.2;
    public static final int WIDTH = 1000;
    public static final int HEIGHT = 1089;

    private TapeImages() {
    }

    /** A camera that sees the given image wherever it is. */
    public static Camera cameraShowing(BufferedImage image) throws Exception {
        Camera camera = mock(Camera.class);
        when(camera.getWidth()).thenReturn(image.getWidth());
        when(camera.getHeight()).thenReturn(image.getHeight());
        Location unitsPerPixel = new Location(LengthUnit.Millimeters, 1 / PIXELS_PER_MM, 1 / PIXELS_PER_MM, 0, 0);
        when(camera.getUnitsPerPixel()).thenReturn(unitsPerPixel);
        when(camera.getUnitsPerPixelAtZ()).thenReturn(unitsPerPixel);
        when(camera.settleAndCapture(any())).thenReturn(image);
        when(camera.settleAndCapture()).thenReturn(image);
        when(camera.capture()).thenReturn(image);
        return camera;
    }

    /** Tape-coloured, with nothing on it. */
    public static BufferedImage blank() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 230, 240));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.dispose();
        return image;
    }

    /**
     * White 8 mm tape running along machine Y, sprocket holes to the left, with a pocket at the
     * given position relative to the image centre in machine axes (mm). Holes are in line with
     * that pocket, as on 2 mm pitch tape.
     */
    public static BufferedImage tapeWithPocketAt(double pocketX, double pocketY) {
        BufferedImage image = blank();
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(10, 10, 20));
        double cx = image.getWidth() / 2.0;
        double cy = image.getHeight() / 2.0;
        double r = TapeHoleLocator.HOLE_DIAMETER_MM / 2 * PIXELS_PER_MM;
        for (int i = -1; i <= 1; i++) {
            double x = cx + (pocketX - TapeHoleLocator.HOLE_TO_POCKET_MM) * PIXELS_PER_MM;
            double y = cy - (pocketY + TapeHoleLocator.HOLE_PITCH_MM * i) * PIXELS_PER_MM;
            g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
        }
        g.dispose();
        return image;
    }
}
