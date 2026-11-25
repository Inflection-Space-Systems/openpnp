package org.openpnp.machine.photon.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.openpnp.machine.photon.exceptions.TapeHolesNotFoundException;
import org.openpnp.model.Point;

/**
 * Works out where the nearest tape pocket is from the sprocket holes seen around a pick point.
 *
 * This is geometry only: no camera, no machine. All coordinates are millimetres in machine axes,
 * relative to the point the camera was centred on (the feeder's saved pick location).
 *
 * EIA-481 fixes everything needed:
 * <ul>
 * <li>sprocket holes are 4 mm apart;</li>
 * <li>pockets on a 2 mm (or 1 mm) pitch sit in line with the holes and between them, pockets on
 * a 4 mm or larger pitch sit halfway between two holes;</li>
 * <li>the pocket row is 3.5 mm from the hole row on 8 mm tape, and 2 mm further for each wider
 * tape (5.5 mm on 12 mm tape, 7.5 mm on 16 mm, 11.5 mm on 24 mm).</li>
 * </ul>
 *
 * The known hole pitch also gives the image scale, so a camera whose units per pixel are slightly
 * off at tape height does not bias the result.
 */
public final class TapeHoleLocator {
    public static final double HOLE_PITCH_MM = 4.0;
    public static final double HOLE_DIAMETER_MM = 1.5;
    /** Hole row to pocket row on 8 mm tape. */
    public static final double HOLE_TO_POCKET_MM = 3.5;
    /** Hole row to pocket row grows by this much per tape width step. */
    public static final double HOLE_TO_POCKET_STEP_MM = 2.0;

    /** Two holes count as neighbours on a row if their pitch is within this fraction of 4 mm. */
    static final double PITCH_TOLERANCE = 0.10;
    /** How far a hole may be from the fitted row, and from its 4 mm station on it. */
    static final double ROW_TOLERANCE_MM = 0.3;
    /** A row closer to the pick point than this cannot be the sprocket holes of this tape. */
    static final double MIN_ROW_DISTANCE_MM = HOLE_TO_POCKET_MM - HOLE_TO_POCKET_STEP_MM / 2;

    private TapeHoleLocator() {
    }

    /** The outcome of one measurement. Immutable. */
    public static final class Measurement {
        private final double offsetX;
        private final double offsetY;
        private final int holeCount;
        private final double scale;
        private final double holeToPocket;
        private final double tapeAngle;
        private final List<Point> holes;

        Measurement(double offsetX, double offsetY, List<Point> holes, double scale,
                double holeToPocket, double tapeAngle) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.holes = Collections.unmodifiableList(new ArrayList<>(holes));
            this.holeCount = holes.size();
            this.scale = scale;
            this.holeToPocket = holeToPocket;
            this.tapeAngle = tapeAngle;
        }

        /** Where the pocket is relative to the measured point, machine X, in mm. */
        public double getOffsetX() {
            return offsetX;
        }

        /** Where the pocket is relative to the measured point, machine Y, in mm. */
        public double getOffsetY() {
            return offsetY;
        }

        public double getDistance() {
            return Math.hypot(offsetX, offsetY);
        }

        /** Number of sprocket holes on the row the result is based on. */
        public int getHoleCount() {
            return holeCount;
        }

        /**
         * True size divided by the size the camera reported, from the 4 mm hole pitch. Exactly 1
         * when only one hole was seen.
         */
        public double getScale() {
            return scale;
        }

        /**
         * The holes the result is based on: the same objects that were passed to locate(), in
         * order along the tape.
         */
        public List<Point> getHoles() {
            return holes;
        }

        /** The hole row to pocket row distance that was assumed, in mm (3.5 on 8 mm tape). */
        public double getHoleToPocket() {
            return holeToPocket;
        }

        /** Direction of the tape in machine axes, in degrees, in the range [0, 180). */
        public double getTapeAngle() {
            return tapeAngle;
        }

        @Override
        public String toString() {
            return String.format("offset (%+.3f, %+.3f) mm from %d hole%s, scale %.3f, hole row %.1f mm away, tape at %.1f°",
                    offsetX, offsetY, holeCount, holeCount == 1 ? "" : "s", scale, holeToPocket, tapeAngle);
        }
    }

    /**
     * The spacing of possible pocket positions along the tape. A correction can never be told
     * apart from one that is a whole number of these steps larger.
     */
    public static double getPocketLattice(int partPitchMm) {
        if (partPitchMm <= 0) {
            throw new IllegalArgumentException("Part pitch must be positive, got " + partPitchMm);
        }
        return Math.min(partPitchMm, HOLE_PITCH_MM);
    }

    /** The largest correction along the tape that can be measured without ambiguity. */
    public static double getMaxUnambiguousCorrection(int partPitchMm) {
        return getPocketLattice(partPitchMm) / 2;
    }

    /**
     * @param holes centres of the circles found, in mm relative to the measured point. Circles
     *        that are not sprocket holes are tolerated: only a row on the 4 mm pitch is used.
     * @param partPitchMm pocket pitch of the tape.
     * @return where the nearest pocket is relative to the measured point.
     * @throws TapeHolesNotFoundException if there is no row of sprocket holes at a plausible
     *         distance from the measured point. A single hole is accepted only under the
     *         conditions given at {@link #rowFromSingleHole(Point)}.
     */
    public static Measurement locate(List<Point> holes, int partPitchMm)
            throws TapeHolesNotFoundException {
        double lattice = getPocketLattice(partPitchMm);
        // Pockets on a 4 mm or larger pitch are halfway between holes, smaller ones are in line.
        double phase = partPitchMm >= HOLE_PITCH_MM ? HOLE_PITCH_MM / 2 : 0;

        Row row = findRow(holes);
        if (row == null && holes.size() == 1) {
            row = rowFromSingleHole(holes.get(0));
        }
        if (row == null) {
            throw new TapeHolesNotFoundException(String.format(
                    "No row of sprocket holes on a %.0f mm pitch found (%d candidate circle%s seen).",
                    HOLE_PITCH_MM, holes.size(), holes.size() == 1 ? "" : "s"));
        }

        double scale = HOLE_PITCH_MM / row.pitch;
        double ux = row.ux;
        double uy = row.uy;
        // The normal to the tape direction.
        double nx = -uy;
        double ny = ux;

        double across = 0;
        List<Double> alongEstimates = new ArrayList<>();
        for (Point hole : row.holes) {
            across += (hole.x * nx + hole.y * ny) * scale;
            double along = (hole.x * ux + hole.y * uy) * scale;
            alongEstimates.add(wrap(along + phase, lattice));
        }
        across /= row.holes.size();
        double along = wrappedMean(alongEstimates, lattice);

        double steps = Math.max(0, Math.round((Math.abs(across) - HOLE_TO_POCKET_MM) / HOLE_TO_POCKET_STEP_MM));
        double holeToPocket = HOLE_TO_POCKET_MM + steps * HOLE_TO_POCKET_STEP_MM;
        // The pocket row is on the same side of the hole row as the measured point.
        double pocketAcross = across - Math.signum(across) * holeToPocket;

        double angle = Math.toDegrees(Math.atan2(uy, ux));
        angle = ((angle % 180) + 180) % 180;
        return new Measurement(along * ux + pocketAcross * nx, along * uy + pocketAcross * ny,
                row.holes, scale, holeToPocket, angle);
    }

    private static final class Row {
        final List<Point> holes;
        final double ux;
        final double uy;
        final double pitch;
        final double distance;

        Row(List<Point> holes, double ux, double uy, double pitch, double distance) {
            this.holes = holes;
            this.ux = ux;
            this.uy = uy;
            this.pitch = pitch;
            this.distance = distance;
        }
    }

    /**
     * Find the row with the most holes on the 4 mm pitch. If two rows tie the nearer one wins,
     * so that a neighbouring feeder's tape is not picked up.
     */
    private static Row findRow(List<Point> holes) {
        Row best = null;
        for (int i = 0; i < holes.size(); i++) {
            for (int j = i + 1; j < holes.size(); j++) {
                Row row = rowThrough(holes.get(i), holes.get(j), holes);
                if (row == null) {
                    continue;
                }
                if (best == null || row.holes.size() > best.holes.size()
                        || (row.holes.size() == best.holes.size() && row.distance < best.distance)) {
                    best = row;
                }
            }
        }
        return best;
    }

    /**
     * With one hole in view there is nothing to take the tape direction or the image scale from.
     * The scale is then trusted as it is, and the tape is taken to run along machine X or Y,
     * whichever puts the hole row at a possible distance from the measured point. Only done when
     * the hole is the only circle seen and only one of the two directions is possible.
     */
    private static Row rowFromSingleHole(Point hole) {
        boolean alongY = Math.abs(hole.x) >= MIN_ROW_DISTANCE_MM;
        boolean alongX = Math.abs(hole.y) >= MIN_ROW_DISTANCE_MM;
        if (alongX == alongY) {
            return null;
        }
        return new Row(Collections.singletonList(hole), alongX ? 1 : 0, alongY ? 1 : 0,
                HOLE_PITCH_MM, alongY ? Math.abs(hole.x) : Math.abs(hole.y));
    }

    private static Row rowThrough(Point a, Point b, List<Point> holes) {
        double separation = Math.hypot(b.x - a.x, b.y - a.y);
        long stations = Math.round(separation / HOLE_PITCH_MM);
        if (stations < 1) {
            return null;
        }
        double pitch = separation / stations;
        if (Math.abs(pitch - HOLE_PITCH_MM) > HOLE_PITCH_MM * PITCH_TOLERANCE) {
            return null;
        }
        final double ux = (b.x - a.x) / separation;
        final double uy = (b.y - a.y) / separation;
        // Signed distance from the measured point (the origin) to the row.
        double distance = Math.abs(-a.x * -uy + -a.y * ux);
        if (distance < MIN_ROW_DISTANCE_MM) {
            return null;
        }

        List<Point> members = new ArrayList<>();
        for (Point hole : holes) {
            double dx = hole.x - a.x;
            double dy = hole.y - a.y;
            double offLine = Math.abs(dx * -uy + dy * ux);
            double alongLine = dx * ux + dy * uy;
            double offStation = Math.abs(alongLine - Math.round(alongLine / pitch) * pitch);
            if (offLine <= ROW_TOLERANCE_MM && offStation <= ROW_TOLERANCE_MM) {
                members.add(hole);
            }
        }
        Collections.sort(members, Comparator.comparingDouble((Point p) -> p.x * ux + p.y * uy));

        // Refit direction and pitch over the full length of the row.
        Point first = members.get(0);
        Point last = members.get(members.size() - 1);
        double length = Math.hypot(last.x - first.x, last.y - first.y);
        long steps = Math.round(length / pitch);
        if (steps < 1) {
            return null;
        }
        return new Row(members, (last.x - first.x) / length, (last.y - first.y) / length,
                length / steps, distance);
    }

    /** Wrap a value into (-lattice/2, lattice/2]. */
    static double wrap(double value, double lattice) {
        double wrapped = value - lattice * Math.round(value / lattice);
        if (wrapped <= -lattice / 2) {
            wrapped += lattice;
        }
        return wrapped;
    }

    /** Mean of values that are only known modulo the lattice. */
    private static double wrappedMean(List<Double> values, double lattice) {
        double reference = values.get(0);
        double sum = 0;
        for (double value : values) {
            sum += reference + wrap(value - reference, lattice);
        }
        return wrap(sum / values.size(), lattice);
    }
}
