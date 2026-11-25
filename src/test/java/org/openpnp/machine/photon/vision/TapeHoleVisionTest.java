package org.openpnp.machine.photon.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.photon.exceptions.TapeHolesNotFoundException;
import org.openpnp.model.Configuration;
import org.openpnp.vision.pipeline.CvPipeline;

import com.google.common.io.Files;

/**
 * Runs the default pipeline on photos taken by a LumenPnP top camera over Photon feeders straight
 * after a feed, and compares the result with what an independent prototype (OpenCV HoughCircles
 * plus a centroid refinement, in a Jython script) logged for the same photos.
 */
public class TapeHoleVisionTest {
    private static final String FIXTURES = "/photon-feeder-vision/";
    /**
     * Agreement required with the prototype. Along the tape the two agree closely. Across it they
     * differ by up to 0.15 mm on some photos: the prototype takes the centroid of the dark disc,
     * which the shadow on one side of a hole in thick paper tape pulls sideways.
     */
    private static final double TOLERANCE_ALONG_MM = 0.06;
    private static final double TOLERANCE_ACROSS_MM = 0.16;

    private static class Expected {
        String file;
        double dx;
        double dy;
        int holes;
        String tape;
    }

    @BeforeAll
    public static void setUp() throws Exception {
        File workingDirectory = new File(Files.createTempDir(), ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();
    }

    private static List<Expected> readExpected() throws Exception {
        List<Expected> expected = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                TapeHoleVisionTest.class.getResourceAsStream(FIXTURES + "expected.tsv"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.trim().isEmpty()) {
                    continue;
                }
                String[] fields = line.split("\t");
                Expected e = new Expected();
                e.file = fields[0];
                e.dx = Double.parseDouble(fields[1]);
                e.dy = Double.parseDouble(fields[2]);
                e.holes = Integer.parseInt(fields[3]);
                e.tape = fields[5];
                expected.add(e);
            }
        }
        return expected;
    }

    private static TapeHoleLocator.Measurement measure(BufferedImage image, int partPitch) throws Exception {
        try (CvPipeline pipeline = TapeHoleVision.createDefaultPipeline()) {
            return TapeHoleVision.measure(TapeImages.cameraShowing(image), pipeline, partPitch);
        }
    }

    @Test
    public void realPhotosAgreeWithThePrototypeMeasurement() throws Exception {
        List<Expected> expected = readExpected();
        assertFalse(expected.isEmpty());
        boolean paper = false;
        boolean embossed = false;
        boolean singleHole = false;
        boolean fourMillimetrePitch = false;
        boolean clear = false;
        StringBuilder failures = new StringBuilder();
        for (Expected e : expected) {
            BufferedImage image = ImageIO.read(TapeHoleVisionTest.class.getResource(FIXTURES + e.file));
            long start = System.nanoTime();
            int pitch = e.tape.contains("4 mm pitch") ? 4 : 2;
            TapeHoleLocator.Measurement m = measure(image, pitch);
            long millis = (System.nanoTime() - start) / 1000000;
            System.out.println(String.format("%s [%s]: expected (%+.3f, %+.3f), got %s in %d ms",
                    e.file, e.tape, e.dx, e.dy, m, millis));
            // The tape runs along Y in these photos.
            if (Math.abs(m.getOffsetX() - e.dx) > TOLERANCE_ACROSS_MM || Math.abs(m.getOffsetY() - e.dy) > TOLERANCE_ALONG_MM) {
                failures.append(String.format("%s: expected (%+.3f, %+.3f), got (%+.3f, %+.3f)%n",
                        e.file, e.dx, e.dy, m.getOffsetX(), m.getOffsetY()));
            }
            assertEquals(e.tape.contains("one whole hole") ? 1 : 2, Math.min(2, m.getHoleCount()), e.file);
            assertEquals(3.5, m.getHoleToPocket(), 1e-9, e.file + " is 8 mm tape");
            // The feeders on this machine run along Y.
            assertEquals(90, m.getTapeAngle(), 3, e.file);
            paper |= e.tape.contains("paper");
            embossed |= e.tape.contains("embossed");
            singleHole |= m.getHoleCount() == 1;
            fourMillimetrePitch |= pitch == 4;
            clear |= e.tape.contains("clear");
        }
        assertEquals("", failures.toString());
        assertTrue(paper && embossed, "fixtures must cover paper and embossed tape");
        assertTrue(singleHole, "fixtures must cover a photo with one whole hole in view");
        assertTrue(fourMillimetrePitch, "fixtures must cover 4 mm pitch tape");
        assertTrue(clear, "fixtures must cover clear tape");
    }

    @Test
    public void imageWithoutHolesIsReportedAsNoHolesFound() {
        assertThrows(TapeHolesNotFoundException.class, () -> measure(TapeImages.blank(), 2));
    }

    @Test
    public void imageWithoutHolesIsKeptWhenDebugLoggingIsOn() throws Exception {
        File directory = new File(Configuration.get().getConfigurationDirectory(),
                TapeHoleVision.class.getCanonicalName());
        int before = directory.exists() ? directory.list().length : 0;
        org.pmw.tinylog.Level level = org.pmw.tinylog.Logger.getLevel();
        try {
            org.pmw.tinylog.Configurator.currentConfig().level(org.pmw.tinylog.Level.INFO).activate();
            assertThrows(TapeHolesNotFoundException.class, () -> measure(TapeImages.blank(), 2));
            assertEquals(before, directory.exists() ? directory.list().length : 0);

            org.pmw.tinylog.Configurator.currentConfig().level(org.pmw.tinylog.Level.DEBUG).activate();
            assertThrows(TapeHolesNotFoundException.class, () -> measure(TapeImages.blank(), 2));
            assertEquals(before + 1, directory.list().length);
            assertTrue(directory.list()[0].startsWith("no_holes_"));
        }
        finally {
            org.pmw.tinylog.Configurator.currentConfig().level(level).activate();
        }
    }

    @Test
    public void drawnTapeIsMeasuredAtAKnownOffset() throws Exception {
        TapeHoleLocator.Measurement m = measure(TapeImages.tapeWithPocketAt(0.30, 0.40), 2);

        assertEquals(0.30, m.getOffsetX(), 0.03);
        assertEquals(0.40, m.getOffsetY(), 0.03);
        assertEquals(3, m.getHoleCount());
    }

    @Test
    public void pipelineWithoutAResultsStageIsAnError() throws Exception {
        try (CvPipeline pipeline = new CvPipeline("<cv-pipeline><stages/></cv-pipeline>")) {
            Exception e = assertThrows(Exception.class, () -> TapeHoleVision.measure(
                    TapeImages.cameraShowing(TapeImages.blank()), pipeline, 2));
            assertFalse(e instanceof TapeHolesNotFoundException);
        }
    }
}
