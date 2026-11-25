package org.openpnp.machine.photon.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.photon.exceptions.TapeHolesNotFoundException;
import org.openpnp.model.Point;

public class TapeHoleLocatorTest {
    private static final double EPS = 1e-6;

    /**
     * Sprocket holes as a camera centred on the origin would see them, for a tape whose nearest
     * pocket is at (pocketX, pocketY).
     *
     * @param angle direction of the tape in degrees
     * @param holeToPocket hole row to pocket row distance; the holes are to the left of the tape
     *        direction
     * @param alongHoleToPocket how far along the tape the pocket is from a hole: 0 for 2 mm pitch
     *        tape, 2 for 4 mm pitch tape
     * @param stations which holes are in view, as multiples of 4 mm from the first one
     */
    private static List<Point> holes(double pocketX, double pocketY, double angle, double holeToPocket,
            double alongHoleToPocket, int... stations) {
        double ux = Math.cos(Math.toRadians(angle));
        double uy = Math.sin(Math.toRadians(angle));
        double nx = -uy;
        double ny = ux;
        List<Point> holes = new ArrayList<>();
        for (int station : stations) {
            double along = 4.0 * station - alongHoleToPocket;
            holes.add(new Point(pocketX + along * ux + holeToPocket * nx,
                    pocketY + along * uy + holeToPocket * ny));
        }
        return holes;
    }

    private static List<Point> scaled(List<Point> holes, double factor) {
        List<Point> scaled = new ArrayList<>();
        for (Point hole : holes) {
            scaled.add(new Point(hole.x * factor, hole.y * factor));
        }
        return scaled;
    }

    @Test
    public void pocketExactlyAtThePickPointGivesNoOffset() throws Exception {
        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(holes(0, 0, 90, 3.5, 0, -1, 0, 1), 2);

        assertEquals(0, m.getOffsetX(), EPS);
        assertEquals(0, m.getOffsetY(), EPS);
        assertEquals(3, m.getHoleCount());
        assertEquals(1.0, m.getScale(), EPS);
        assertEquals(3.5, m.getHoleToPocket(), EPS);
        assertEquals(90, m.getTapeAngle(), EPS);
    }

    @Test
    public void offsetIsReportedInMachineAxesForTapeAlongY() throws Exception {
        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(holes(0.12, -0.63, 90, 3.5, 0, -1, 0, 1), 2);

        assertEquals(0.12, m.getOffsetX(), EPS);
        assertEquals(-0.63, m.getOffsetY(), EPS);
        assertEquals(Math.hypot(0.12, 0.63), m.getDistance(), EPS);
    }

    @Test
    public void offsetIsReportedInMachineAxesForTapeAlongX() throws Exception {
        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(holes(-0.4, 0.25, 0, 3.5, 0, 0, 1), 2);

        assertEquals(-0.4, m.getOffsetX(), EPS);
        assertEquals(0.25, m.getOffsetY(), EPS);
        assertEquals(0, m.getTapeAngle(), EPS);
    }

    @Test
    public void holesOnEitherSideOfTheTapeWork() throws Exception {
        // A feeder at the back of the machine is the same tape turned by 180 degrees.
        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(holes(0.2, 0.3, 270, 3.5, 0, -1, 0), 2);

        assertEquals(0.2, m.getOffsetX(), EPS);
        assertEquals(0.3, m.getOffsetY(), EPS);
        assertEquals(90, m.getTapeAngle(), EPS);
    }

    @Test
    public void tiltedTapeIsMeasuredAlongItsOwnDirection() throws Exception {
        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(holes(0.3, -0.2, 93.5, 3.5, 0, -1, 0, 1), 2);

        assertEquals(0.3, m.getOffsetX(), EPS);
        assertEquals(-0.2, m.getOffsetY(), EPS);
        assertEquals(93.5, m.getTapeAngle(), 1e-4);
    }

    @Test
    public void fourMillimetrePitchPocketsAreHalfwayBetweenHoles() throws Exception {
        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(holes(0.1, 0.9, 90, 3.5, 2, 0, 1, 2), 4);

        assertEquals(0.1, m.getOffsetX(), EPS);
        assertEquals(0.9, m.getOffsetY(), EPS);
    }

    @Test
    public void fourMillimetrePitchAllowsCorrectionsBeyondOneMillimetre() throws Exception {
        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(holes(0, 1.6, 90, 3.5, 2, -1, 0, 1, 2), 4);

        assertEquals(1.6, m.getOffsetY(), EPS);
        assertEquals(2.0, TapeHoleLocator.getMaxUnambiguousCorrection(4), EPS);
        assertEquals(2.0, TapeHoleLocator.getMaxUnambiguousCorrection(8), EPS);
        assertEquals(1.0, TapeHoleLocator.getMaxUnambiguousCorrection(2), EPS);
    }

    @Test
    public void twoMillimetrePitchSnapsToTheNearestPocket() throws Exception {
        // A pocket 1.3 mm ahead means its neighbour is 0.7 mm behind, and that one is nearer.
        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(holes(0, 1.3, 90, 3.5, 0, -1, 0, 1), 2);

        assertEquals(-0.7, m.getOffsetY(), EPS);
    }

    @Test
    public void twelveMillimetreTapeIsRecognisedByTheHoleRowDistance() throws Exception {
        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(holes(-0.25, 0.4, 90, 5.5, 2, 0, 1), 4);

        assertEquals(5.5, m.getHoleToPocket(), EPS);
        assertEquals(-0.25, m.getOffsetX(), EPS);
        assertEquals(0.4, m.getOffsetY(), EPS);
    }

    @Test
    public void wideTapesAreRecognisedByTheHoleRowDistance() throws Exception {
        assertEquals(7.5, TapeHoleLocator.locate(holes(0.3, 0, 90, 7.5, 2, 0, 1), 8).getHoleToPocket(), EPS);
        assertEquals(11.5, TapeHoleLocator.locate(holes(-0.3, 0, 90, 11.5, 2, 0, 1), 12).getHoleToPocket(), EPS);
    }

    @Test
    public void imageScaleIsTakenFromTheHolePitch() throws Exception {
        // The camera reports everything 4 % too large.
        List<Point> seen = scaled(holes(0.5, -0.5, 90, 3.5, 0, -1, 0, 1), 1.04);

        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(seen, 2);

        assertEquals(1 / 1.04, m.getScale(), EPS);
        assertEquals(0.5, m.getOffsetX(), EPS);
        assertEquals(-0.5, m.getOffsetY(), EPS);
    }

    @Test
    public void circlesThatAreNotOnTheHoleRowAreIgnored() throws Exception {
        List<Point> seen = new ArrayList<>(holes(0.2, 0.1, 90, 3.5, 0, -1, 0, 1));
        seen.add(new Point(1.9, -2.6));
        seen.add(new Point(-1.1, 5.2));
        seen.add(new Point(0.2, 0.1));

        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(seen, 2);

        assertEquals(0.2, m.getOffsetX(), EPS);
        assertEquals(0.1, m.getOffsetY(), EPS);
        assertEquals(3, m.getHoleCount());
    }

    @Test
    public void rowThroughThePickPointIsNotTakenForSprocketHoles() {
        // Round marks in the pockets themselves, 4 mm apart, right under the camera.
        List<Point> seen = Arrays.asList(new Point(0.1, -4.0), new Point(0.1, 0.0), new Point(0.1, 4.0));

        assertThrows(TapeHolesNotFoundException.class, () -> TapeHoleLocator.locate(seen, 2));
    }

    @Test
    public void nearerOfTwoEquallyLongRowsWins() throws Exception {
        List<Point> seen = new ArrayList<>(holes(0.1, 0.2, 90, 3.5, 0, 0, 1));
        // The neighbouring feeder's tape, further away on the other side.
        seen.add(new Point(6.9, -1.0));
        seen.add(new Point(6.9, 3.0));

        TapeHoleLocator.Measurement m = TapeHoleLocator.locate(seen, 2);

        assertEquals(0.1, m.getOffsetX(), EPS);
        assertEquals(0.2, m.getOffsetY(), EPS);
    }

    @Test
    public void noCirclesMeansNoHolesFound() {
        assertThrows(TapeHolesNotFoundException.class,
                () -> TapeHoleLocator.locate(Collections.<Point>emptyList(), 2));
    }

    @Test
    public void singleHoleIsUsedWithTheTapeTakenAsAxisParallel() throws Exception {
        TapeHoleLocator.Measurement alongY = TapeHoleLocator.locate(holes(0.15, -0.3, 90, 3.5, 0, 0), 2);

        assertEquals(0.15, alongY.getOffsetX(), EPS);
        assertEquals(-0.3, alongY.getOffsetY(), EPS);
        assertEquals(1, alongY.getHoleCount());
        assertEquals(1.0, alongY.getScale(), EPS);
        assertEquals(90, alongY.getTapeAngle(), EPS);

        TapeHoleLocator.Measurement alongX = TapeHoleLocator.locate(holes(-0.2, 0.25, 0, 3.5, 0, 0), 2);

        assertEquals(-0.2, alongX.getOffsetX(), EPS);
        assertEquals(0.25, alongX.getOffsetY(), EPS);
        assertEquals(0, alongX.getTapeAngle(), EPS);
    }

    @Test
    public void singleHoleThatFitsBothDirectionsIsNotUsed() {
        // 3.5 mm away in X and 4 mm away in Y: the tape could run either way.
        assertThrows(TapeHolesNotFoundException.class,
                () -> TapeHoleLocator.locate(holes(0, 0, 90, 3.5, 0, 1), 2));
    }

    @Test
    public void singleHoleTooCloseToThePickPointIsNotUsed() {
        assertThrows(TapeHolesNotFoundException.class,
                () -> TapeHoleLocator.locate(Arrays.asList(new Point(-1.2, 0.8)), 2));
    }

    @Test
    public void singleHoleIsNotUsedWhenOtherCirclesAreSeen() {
        List<Point> seen = new ArrayList<>(holes(0.15, -0.3, 90, 3.5, 0, 0));
        seen.add(new Point(1.0, 2.2));

        assertThrows(TapeHolesNotFoundException.class, () -> TapeHoleLocator.locate(seen, 2));
    }

    @Test
    public void circlesAtTheWrongPitchAreNotARow() {
        List<Point> seen = Arrays.asList(new Point(-3.5, 0), new Point(-3.5, 3.0), new Point(-3.5, 5.4));

        assertThrows(TapeHolesNotFoundException.class, () -> TapeHoleLocator.locate(seen, 2));
    }

    @Test
    public void nonPositivePartPitchIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TapeHoleLocator.locate(holes(0, 0, 90, 3.5, 0, 0, 1), 0));
    }

    @Test
    public void wrapKeepsValuesWithinHalfALattice() {
        assertEquals(0.4, TapeHoleLocator.wrap(2.4, 2), EPS);
        assertEquals(-0.4, TapeHoleLocator.wrap(1.6, 2), EPS);
        assertEquals(1.0, TapeHoleLocator.wrap(1.0, 2), EPS);
        assertEquals(1.0, TapeHoleLocator.wrap(-1.0, 2), EPS);
    }
}
