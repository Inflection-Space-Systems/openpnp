package org.openpnp.machine.photon.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.photon.exceptions.TapeHolesNotFoundException;
import org.openpnp.model.Configuration;
import org.openpnp.model.Point;
import org.openpnp.spi.Camera;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;

import com.google.common.io.Files;

public class TapeHoleOverlayTest {
    private static final double PX = TapeImages.PIXELS_PER_MM;
    private static final double CX = TapeImages.WIDTH / 2.0;
    private static final double CY = TapeImages.HEIGHT / 2.0;

    @BeforeAll
    public static void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp"));
        Configuration.get().load();
    }

    private static Color at(BufferedImage image, double x, double y) {
        return new Color(image.getRGB((int) Math.round(x), (int) Math.round(y)));
    }

    /** Whether the colour is drawn somewhere within a pixel or two of the point. */
    private static boolean near(BufferedImage image, double x, double y, Color colour) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (at(image, x + dx, y + dy).equals(colour)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static CvStage.Result.Circle circleAtMm(double x, double y) {
        return new CvStage.Result.Circle(CX + x * PX, CY - y * PX, 1.5 * PX);
    }

    @Test
    public void usedHolesPocketAndPickLocationAreDrawn() throws Exception {
        BufferedImage image = TapeImages.tapeWithPocketAt(0.30, 0.40);
        Camera camera = TapeImages.cameraShowing(image);
        List<Point> holes = Arrays.asList(new Point(-3.2, -3.6), new Point(-3.2, 0.4), new Point(-3.2, 4.4));
        List<CvStage.Result.Circle> circles = new ArrayList<>();
        for (Point hole : holes) {
            circles.add(circleAtMm(hole.x, hole.y));
        }
        CvStage.Result.Circle stray = circleAtMm(2.5, -3.0);
        List<CvStage.Result.Circle> all = new ArrayList<>(circles);
        all.add(stray);
        TapeHoleLocator.Measurement measurement = TapeHoleLocator.locate(holes, 2);

        BufferedImage overlay = TapeHoleOverlay.draw(image, camera, all, circles, measurement);

        double r = 0.75 * PX;
        for (CvStage.Result.Circle circle : circles) {
            assertTrue(near(overlay, circle.x + r, circle.y, TapeHoleOverlay.USED_HOLE));
            assertTrue(near(overlay, circle.x, circle.y - r, TapeHoleOverlay.USED_HOLE));
        }
        // The row line, between two holes.
        assertTrue(near(overlay, circles.get(0).x, (circles.get(0).y + circles.get(1).y) / 2, TapeHoleOverlay.USED_HOLE));
        assertTrue(near(overlay, stray.x + r, stray.y, TapeHoleOverlay.UNUSED_CIRCLE));
        // The pocket cross is at +0.30, +0.40 mm: right of and above the centre in the image.
        double pocketX = CX + 0.30 * PX;
        double pocketY = CY - 0.40 * PX;
        assertTrue(near(overlay, pocketX + 12, pocketY, TapeHoleOverlay.POCKET));
        assertTrue(near(overlay, pocketX, pocketY - 12, TapeHoleOverlay.POCKET));
        // The pick location cross, on the arm pointing away from the pocket.
        assertTrue(near(overlay, CX - 12, CY, TapeHoleOverlay.PICK_LOCATION));
        assertEquals("Pocket at X +0.30, Y +0.40 mm from 3 sprocket holes", TapeHoleOverlay.describe(measurement));
    }

    @Test
    public void pocketIsDrawnInImagePixelsWhenTheCameraScaleIsOff() throws Exception {
        // The camera believes there are 91.2 px/mm; the tape is really imaged at 95.
        double real = 95.0;
        List<Point> holes = new ArrayList<>();
        List<CvStage.Result.Circle> circles = new ArrayList<>();
        for (int i = -1; i <= 1; i++) {
            double x = (0.5 - 3.5) * real;
            double y = (0.2 + 4.0 * i) * real;
            circles.add(new CvStage.Result.Circle(CX + x, CY - y, 1.5 * real));
            holes.add(new Point(x / PX, y / PX));
        }
        BufferedImage image = TapeImages.blank();
        TapeHoleLocator.Measurement measurement = TapeHoleLocator.locate(holes, 2);
        assertEquals(0.5, measurement.getOffsetX(), 1e-6);

        BufferedImage overlay = TapeHoleOverlay.draw(image, TapeImages.cameraShowing(image), circles, circles, measurement);

        assertTrue(near(overlay, CX + 0.5 * real + 12, CY - 0.2 * real, TapeHoleOverlay.POCKET));
    }

    @Test
    public void capturedImageIsNotDrawnOn() throws Exception {
        BufferedImage image = TapeImages.blank();
        int before = image.getRGB((int) CX - 12, (int) CY);

        BufferedImage overlay = TapeHoleOverlay.draw(image, TapeImages.cameraShowing(image),
                Collections.<CvStage.Result.Circle>emptyList(), Collections.<CvStage.Result.Circle>emptyList(), null);

        assertEquals(before, image.getRGB((int) CX - 12, (int) CY));
        assertNotEquals(before, overlay.getRGB((int) CX - 12, (int) CY));
    }

    @Test
    public void failedMeasurementShowsTheCirclesThatWereFoundAndNoPocket() throws Exception {
        BufferedImage image = TapeImages.blank();
        CvStage.Result.Circle lone = circleAtMm(1.0, 1.0);

        BufferedImage overlay = TapeHoleOverlay.draw(image, TapeImages.cameraShowing(image),
                Arrays.asList(lone), Collections.<CvStage.Result.Circle>emptyList(), null);

        assertTrue(near(overlay, lone.x + 0.75 * PX, lone.y, TapeHoleOverlay.UNUSED_CIRCLE));
        for (int x = 0; x < overlay.getWidth(); x += 3) {
            for (int y = 0; y < overlay.getHeight(); y += 3) {
                assertNotEquals(TapeHoleOverlay.POCKET, at(overlay, x, y));
            }
        }
        assertEquals("No sprocket holes found", TapeHoleOverlay.describe(null));
    }

    @Test
    public void measureHandsTheOverlayToTheDisplay() throws Exception {
        BufferedImage image = TapeImages.tapeWithPocketAt(0.30, 0.40);
        List<BufferedImage> shown = new ArrayList<>();
        List<String> texts = new ArrayList<>();

        try (CvPipeline pipeline = TapeHoleVision.createDefaultPipeline()) {
            TapeHoleVision.measure(TapeImages.cameraShowing(image), pipeline, 2, (overlay, text) -> {
                shown.add(overlay);
                texts.add(text);
            });
        }

        assertEquals(1, shown.size());
        assertEquals(image.getWidth(), shown.get(0).getWidth());
        assertTrue(texts.get(0).startsWith("Pocket at X +0.3"), texts.get(0));
        assertTrue(near(shown.get(0), CX + 0.30 * PX + 12, CY - 0.40 * PX, TapeHoleOverlay.POCKET));
    }

    @Test
    public void measureHandsTheOverlayToTheDisplayWhenNoHolesAreFound() throws Exception {
        List<String> texts = new ArrayList<>();

        try (CvPipeline pipeline = TapeHoleVision.createDefaultPipeline()) {
            assertThrows(TapeHolesNotFoundException.class, () -> TapeHoleVision.measure(
                    TapeImages.cameraShowing(TapeImages.blank()), pipeline, 2, (overlay, text) -> {
                        assertNotNull(overlay);
                        texts.add(text);
                    }));
        }

        assertEquals(Arrays.asList("No sprocket holes found"), texts);
    }

    /** Not an assertion of anything: writes overlays of the fixture photos for a person to look at. */
    @Test
    public void writeOverlaysOfTheFixturePhotosWhenAsked() throws Exception {
        String directory = System.getenv("PHOTON_OVERLAY_DIR");
        if (directory == null) {
            return;
        }
        for (String name : new String[] {"173402.272_slot5_N1", "173706.831_slot2_N1", "174738.625_slot2_N2"}) {
            BufferedImage image = ImageIO.read(getClass().getResource("/photon-feeder-vision/" + name + ".jpg"));
            try (CvPipeline pipeline = TapeHoleVision.createDefaultPipeline()) {
                TapeHoleVision.measure(TapeImages.cameraShowing(image), pipeline, 2, (overlay, text) -> {
                    try {
                        ImageIO.write(overlay, "png", new File(directory, name + ".png"));
                        System.out.println("OVERLAY " + name + ": " + text);
                    }
                    catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }
}
