package org.openpnp.machine.photon.vision;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.apache.commons.io.IOUtils;
import org.openpnp.machine.photon.PhotonFeeder;
import org.openpnp.machine.photon.exceptions.TapeHolesNotFoundException;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Point;
import org.openpnp.spi.Camera;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.pmw.tinylog.Logger;

/**
 * Finds the tape's sprocket holes in the camera image and measures where the pocket is relative
 * to the point the camera is centred on. The camera must already be over the pick location.
 */
public final class TapeHoleVision {
    /** Name of the property group the pipeline's hole detection stage reads its overrides from. */
    public static final String HOLE_PROPERTY = "sprocketHole";

    /**
     * How far from the image centre holes are looked for. On 8 mm and 12 mm tape the hole row is
     * 3.5 mm or 5.5 mm away, so this takes in two or three holes of this tape and little else.
     * It does not reach the hole row of 16 mm or wider tape: the locator's geometry covers those,
     * but this search distance (and most cameras' field of view) does not.
     */
    static final Length SEARCH_DISTANCE = new Length(7.5, LengthUnit.Millimeters);

    /** Circles whose diameter is further than this from 1.5 mm are not sprocket holes. */
    static final double DIAMETER_TOLERANCE_MM = 0.45;

    private TapeHoleVision() {
    }

    public static CvPipeline createDefaultPipeline() {
        try {
            String xml = IOUtils.toString(
                    PhotonFeeder.class.getResource("PhotonFeeder-DefaultPipeline.xml"),
                    StandardCharsets.UTF_8);
            return new CvPipeline(xml);
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }

    /**
     * @param pipeline run as it is, apart from the camera and the hole size and search distance.
     *        Its "results" stage must produce circles.
     * @throws TapeHolesNotFoundException if the image has no usable row of sprocket holes.
     */
    public static TapeHoleLocator.Measurement measure(Camera camera, CvPipeline pipeline,
            int partPitchMm) throws Exception {
        return measure(camera, pipeline, partPitchMm, null);
    }

    /**
     * @param display if not null, given the captured image with what was found drawn on it and
     *        a line of text, whether or not the measurement succeeds.
     */
    public static TapeHoleLocator.Measurement measure(Camera camera, CvPipeline pipeline,
            int partPitchMm, BiConsumer<BufferedImage, String> display) throws Exception {
        preparePipeline(pipeline, camera);
        pipeline.process();

        CvStage.Result result = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME);
        if (result == null) {
            throw new Exception("The Photon feeder vision pipeline has no stage named \""
                    + VisionUtils.PIPELINE_RESULTS_NAME + "\".");
        }
        List<CvStage.Result.Circle> circles = toCircles(result.getModel());
        Map<Point, CvStage.Result.Circle> holes = toHoleOffsets(camera, circles);
        TapeHoleLocator.Measurement measurement = null;
        try {
            measurement = TapeHoleLocator.locate(new ArrayList<>(holes.keySet()), partPitchMm);
            Logger.debug("Photon feeder tape vision: {}", measurement);
            return measurement;
        }
        finally {
            if (display != null && pipeline.getLastCapturedImage() != null) {
                List<CvStage.Result.Circle> used = new ArrayList<>();
                if (measurement != null) {
                    for (Point hole : measurement.getHoles()) {
                        used.add(holes.get(hole));
                    }
                }
                display.accept(
                        TapeHoleOverlay.draw(pipeline.getLastCapturedImage(), camera, circles, used, measurement),
                        TapeHoleOverlay.describe(measurement));
            }
        }
    }

    private static List<CvStage.Result.Circle> toCircles(Object model) {
        List<CvStage.Result.Circle> circles = new ArrayList<>();
        if (model instanceof List) {
            for (Object item : (List<?>) model) {
                if (item instanceof CvStage.Result.Circle) {
                    circles.add((CvStage.Result.Circle) item);
                }
            }
        }
        return circles;
    }

    /** Give the pipeline its camera and the size and search distance of the holes. */
    public static void preparePipeline(CvPipeline pipeline, Camera camera) {
        pipeline.setProperty("camera", camera);
        pipeline.setProperty(HOLE_PROPERTY + ".diameter",
                new Length(TapeHoleLocator.HOLE_DIAMETER_MM, LengthUnit.Millimeters));
        pipeline.setProperty(HOLE_PROPERTY + ".maxDistance", SEARCH_DISTANCE);
    }

    /**
     * Circles of about the right size, keyed by their centre in mm relative to the image centre,
     * in machine axes, in the order the circles were found.
     */
    private static Map<Point, CvStage.Result.Circle> toHoleOffsets(Camera camera,
            List<CvStage.Result.Circle> circles) {
        Map<Point, CvStage.Result.Circle> holes = new LinkedHashMap<>();
        for (CvStage.Result.Circle circle : circles) {
            Location size = VisionUtils.getPixelOffsets(camera, circle.diameter, circle.diameter)
                    .convertToUnits(LengthUnit.Millimeters);
            double diameter = (Math.abs(size.getX()) + Math.abs(size.getY())) / 2;
            if (Math.abs(diameter - TapeHoleLocator.HOLE_DIAMETER_MM) > DIAMETER_TOLERANCE_MM) {
                Logger.trace("Photon feeder tape vision: ignoring circle of {} mm diameter", diameter);
                continue;
            }
            Location offset = VisionUtils.getPixelCenterOffsets(camera, circle.x, circle.y)
                    .convertToUnits(LengthUnit.Millimeters);
            holes.put(new Point(offset.getX(), offset.getY()), circle);
        }
        return holes;
    }
}
