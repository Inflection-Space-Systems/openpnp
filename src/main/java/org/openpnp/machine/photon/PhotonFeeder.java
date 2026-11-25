package org.openpnp.machine.photon;

import org.openpnp.ConfigurationListener;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.photon.exceptions.FeedFailureException;
import org.openpnp.machine.photon.exceptions.FeederHasNoLocationOffsetException;
import org.openpnp.machine.photon.exceptions.NoSlotAddressException;
import org.openpnp.machine.photon.exceptions.TapeHolesNotFoundException;
import org.openpnp.machine.photon.exceptions.UnconfiguredSlotException;
import org.openpnp.machine.photon.exceptions.VisionCorrectionTooLargeException;
import org.openpnp.machine.photon.protocol.ErrorTypes;
import org.openpnp.machine.photon.protocol.PhotonBus;
import org.openpnp.machine.photon.protocol.PhotonBusInterface;
import org.openpnp.machine.photon.protocol.commands.*;
import org.openpnp.machine.photon.sheets.FeederPropertySheet;
import org.openpnp.machine.photon.sheets.GlobalConfigPropertySheet;
import org.openpnp.machine.photon.vision.TapeHoleLocator;
import org.openpnp.machine.photon.vision.TapeHoleVision;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Solutions;
import org.openpnp.spi.*;
import org.openpnp.util.MovableUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

import javax.swing.*;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PhotonFeeder extends ReferenceFeeder {
    public static final String ACTUATOR_DATA_NAME = "PhotonFeederData";
    /** How long the camera view shows what vision calibration found. */
    static final long VISION_RESULT_DISPLAY_MILLISECONDS = 1500;
    PhotonProperties photonProperties;

    @Attribute(required = false)
    protected String hardwareId;

    protected Integer slotAddress = null;

    @Attribute(required = false)
    protected int partPitch = 4;

    protected boolean initialized = false;

    @Element(required = false)
    private Location offset;

    private static PhotonBusInterface photonBus;

    @Attribute(required = false)
    protected boolean moveWhileFeeding = true;

    /**
     * When the tape's sprocket holes are looked at to correct the pick location. The tape in a
     * Photon feeder is positioned by the feeder alone, so without this the pick location is the
     * slot location plus a fixed offset however far the tape has drifted.
     */
    public enum VisionCalibration {
        Off,
        EveryFeed,
        EveryNFeeds,
        AfterFailedPick
    }

    @Attribute(required = false)
    protected VisionCalibration visionCalibration = VisionCalibration.Off;

    @Attribute(required = false)
    protected int visionCalibrationInterval = 10;

    @Element(required = false)
    protected Length visionMaxCorrection = new Length(0.75, LengthUnit.Millimeters);

    @Attribute(required = false)
    protected boolean visionStopWhenHolesNotFound = true;

    /** Null until vision calibration is used or the pipeline is edited: then the default is created. */
    @Element(required = false)
    protected CvPipeline visionPipeline;

    /**
     * Where vision found the pocket, relative to visionOffsetBase. Never saved, and replaced (not
     * added to) by each measurement, so it cannot accumulate into the configured offset.
     */
    protected Location visionOffset;
    /** The uncorrected pick location visionOffset was measured at. A changed slot or offset voids it. */
    protected Location visionOffsetBase;
    protected int feedsSinceVisionCalibration = 0;
    protected int picksSinceFeed = 0;
    protected boolean pickFailedSinceVisionCalibration = false;
    /** The tape was advanced but the measurement that should follow has not succeeded yet. */
    protected boolean visionCalibrationPending = false;

    public PhotonFeeder() {
        Configuration.get().addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationLoaded(Configuration configuration) {
                photonProperties = new PhotonProperties(configuration.getMachine());

                // Ensure actuators are added to the machine when it has PhotonFeeders
                getDataActuator();
                populatePhotonBus();
            }
        });
    }

    public static void setBus(PhotonBusInterface bus) {
        photonBus = bus;
    }

    public static PhotonBusInterface getBus() {
        populatePhotonBus();
        return photonBus;
    }

    private static void populatePhotonBus() {
        if (photonBus != null) {
            return;
        }

        photonBus = new PhotonBus(0, getDataActuator());
    }

    @Override
    public Location getPickLocation() throws Exception {
        Location location = getUncorrectedPickLocation();
        Location correction = getVisionOffset();
        if (correction != null) {
            location = location.add(correction);
        }
        return location;
    }

    /**
     * The pick location as configured: slot location plus offset, without any vision correction.
     */
    public Location getUncorrectedPickLocation() throws NoSlotAddressException,
            UnconfiguredSlotException, FeederHasNoLocationOffsetException {
        verifyFeederLocationIsFullyConfigured();

        return offset.offsetWithRotationFrom(getSlot().getLocation());
    }

    private void verifyFeederLocationIsFullyConfigured() throws NoSlotAddressException,
            UnconfiguredSlotException, FeederHasNoLocationOffsetException {
        if (slotAddress == null) {
            throw new NoSlotAddressException(
                    String.format("Photon Feeder with address %s has no address. Is it inserted?", hardwareId)
            );
        }

        if (getSlot().getLocation() == null) {
            throw new UnconfiguredSlotException(
                    String.format("The slot at address %s has no location configured.", slotAddress)
            );
        }

        if (offset == null) {
            throw new FeederHasNoLocationOffsetException(
                    String.format("Photon Feeder with address %s has no location offset.", hardwareId)
            );
        }
    }

    public void setOffset(Location offsets) {
        Object oldValue = this.offset;
        this.offset = offsets;
        firePropertyChange("offsets", oldValue, offsets);
    }

    public Location getOffset() {
        return offset;
    }

    public boolean getMoveWhileFeeding() {
        return moveWhileFeeding;
    }

    public void setMoveWhileFeeding(boolean moveWhileFeeding) {
        this.moveWhileFeeding = moveWhileFeeding;
    }

    @Override
    public void findIssues(Solutions solutions) {
        super.findIssues(solutions);

        if (hardwareId == null) {
            return;
        }

        if (slotAddress != null && getSlot().getLocation() == null) {
            solutions.add(new Solutions.PlainIssue(
                    this,
                    "Feeder slot has no configured location",
                    "Select the feeder in the Feeders tab and make sure the slot has a set location",
                    Solutions.Severity.Error,
                    "https://github.com/openpnp/openpnp/wiki/Photon-Feeder#slots-and-feeder-locations"
            ));
        }

        double maxUnambiguous = TapeHoleLocator.getMaxUnambiguousCorrection(Math.max(1, partPitch));
        if (visionCalibration != VisionCalibration.Off
                && visionMaxCorrection.convertToUnits(LengthUnit.Millimeters).getValue() >= maxUnambiguous) {
            solutions.add(new Solutions.PlainIssue(
                    this,
                    "Feeder vision calibration maximum correction is too large for the part pitch",
                    String.format("With a part pitch of %d mm a pocket further than %.1f mm from the pick location "
                            + "cannot be told from its neighbour. Set the maximum correction below that.",
                            partPitch, maxUnambiguous),
                    Solutions.Severity.Warning,
                    "https://github.com/openpnp/openpnp/wiki/Photon-Feeder"
            ));
        }

        if (offset == null) {
            solutions.add(new Solutions.PlainIssue(
                    this,
                    "Feeder has no configured offset",
                    "Select the feeder in the Feeders tab and make sure the feeder has an offset location from the slot",
                    Solutions.Severity.Error,
                    "https://github.com/openpnp/openpnp/wiki/Photon-Feeder#slots-and-feeder-locations"
            ));
        }
    }

    @Override
    public void prepareForJob(boolean visit) throws Exception {
        for (int i = 0; i <= photonProperties.getFeederCommunicationMaxRetry(); i++) {
            findSlotAddressIfNeeded();

            initializeIfNeeded();

            if (initialized) {
                verifyFeederLocationIsFullyConfigured();

                super.prepareForJob(visit);
                return;
            }
        }

        throw new Exception("Failed to find and initialize the feeder");
    }

    public void findSlotAddress() throws Exception {
        findSlotAddress(true);
    }

    private void findSlotAddress(boolean force) throws Exception {
        if (slotAddress != null && !force) {
            return;
        }

        GetFeederAddress getFeederAddress = new GetFeederAddress(hardwareId);
        GetFeederAddress.Response response = getFeederAddress.send(photonBus);

        if (response == null) {
            setSlotAddress(null);
            return;
        }
        setSlotAddress(response.fromAddress);
    }

    private void findSlotAddressIfNeeded() throws Exception {
        findSlotAddress(false);
    }

    public void initializeIfNeeded() throws Exception {
        if (initialized || slotAddress == null) {
            return;
        }

        InitializeFeeder initializeFeeder = new InitializeFeeder(slotAddress, hardwareId);
        InitializeFeeder.Response response = initializeFeeder.send(photonBus);

        if (response == null) {
            slotAddress = null;
        } else if (response.error == ErrorTypes.WRONG_FEEDER_UUID) {
            PhotonFeeder otherFeeder = findByHardwareId(response.uuid);

            // If we don't know about that feeder, let's go ahead and create it
            if (otherFeeder == null) {
                otherFeeder = new PhotonFeeder();
                otherFeeder.setHardwareId(response.uuid);
                Configuration.get().getMachine().addFeeder(otherFeeder);
            }

            // This other feeder is in the slot we thought we were
            otherFeeder.setSlotAddress(response.fromAddress);
        } else {
            initialized = true;
            // The feeder may have been taken out and reloaded: what was measured before is void.
            resetVisionCalibration();
        }
    }

    static Actuator getDataActuator() {
        Machine machine = Configuration.get().getMachine();

        Actuator actuator = machine.getActuatorByName(ACTUATOR_DATA_NAME);

        if (actuator == null) {
            actuator = createDefaultActuator(machine);
        }

        return actuator;
    }

    private static Actuator createDefaultActuator(Machine machine) {
        Actuator actuator;
        actuator = new ReferenceActuator();
        actuator.setName(ACTUATOR_DATA_NAME);

        for (Driver driver : machine.getDrivers()) {
            if(! (driver instanceof GcodeDriver)) {
                continue;
            }
            GcodeDriver gcodeDriver = (GcodeDriver) driver;
            gcodeDriver.setCommand(actuator, GcodeDriver.CommandType.ACTUATOR_READ_COMMAND, "M485 {Value}");
            gcodeDriver.setCommand(actuator, GcodeDriver.CommandType.ACTUATOR_READ_REGEX, "rs485-reply: (?<Value>.*)");
            break;  // Only set this on 1 GCodeDriver
        }

        try {
            machine.addActuator(actuator);
        } catch (Exception exception) {
            exception.printStackTrace(); // TODO Probably need to log this, figure out why it can happen first
        }
        return actuator;
    }

    private void feed(Nozzle nozzle, int distance_mm) throws Exception {
        feed(nozzle, distance_mm, false);
    }

    /**
     * @param cameraOverPickLocation while the tape moves, bring the camera rather than the nozzle
     *        over the pick location, ready to look at the tape as soon as it stops.
     */
    private void feed(Nozzle nozzle, int distance_mm, boolean cameraOverPickLocation) throws Exception {
        for (int i = 0; i <= photonProperties.getFeederCommunicationMaxRetry(); i++) {
            findSlotAddressIfNeeded();
            initializeIfNeeded();

            if (!initialized) {
                continue;
            }

            verifyFeederLocationIsFullyConfigured();

            MoveFeedForward moveFeedForward = new MoveFeedForward(slotAddress, distance_mm * 10);
            MoveFeedForward.Response moveFeedForwardResponse = moveFeedForward.send(photonBus);

            if (moveFeedForwardResponse == null) {
                slotAddress = null;
                initialized = false;
                throw new FeedFailureException("Feed command timed out");
            } else if (moveFeedForwardResponse.error == ErrorTypes.UNINITIALIZED_FEEDER) {
                slotAddress = null;
                initialized = false;
                continue;  // We'll initialize it on a retry
            }

            // The feeder gives us expectedTimeToFeed, but it is way too conservative.
            // Use expectedTimeToFeed to bound how long we will wait,
            // but use polling to check the status of the feed.
            Duration expectedFeedDuration = Duration.ofMillis(moveFeedForwardResponse.expectedTimeToFeed);
            long endTimeNanos = System.nanoTime() + expectedFeedDuration.toNanos() * 3;
            for (int j = 0; j <= photonProperties.getFeederCommunicationMaxRetry() || System.nanoTime() <= endTimeNanos; j++) {
                Thread.sleep(50); // MAGIC: this feels like a good number, there is no particular reason it is this way.

                if (j == 0 && nozzle != null && Configuration.get().getMachine().isHomed() && getMoveWhileFeeding()) {
                    if (cameraOverPickLocation) {
                        moveCameraTo(nozzle.getHead().getDefaultCamera(), getUncorrectedPickLocation());
                    }
                    else {
                        MovableUtils.moveToLocationAtSafeZ(nozzle, getPickLocation().deriveLengths(null, null, nozzle.getEffectiveSafeZ(), null));
                    }
                }

                MoveFeedStatus moveFeedStatus = new MoveFeedStatus(slotAddress);
                MoveFeedStatus.Response moveFeedStatusResponse = moveFeedStatus.send(photonBus);

                if (moveFeedStatusResponse == null) {
                    continue; // Timeout. retry after delay.
                }

                if (moveFeedStatusResponse.error == ErrorTypes.NONE) {
                    return;
                } else if (moveFeedStatusResponse.error == ErrorTypes.COULD_NOT_REACH) {
                    throw new FeedFailureException("Feeder could not reach its destination.");
                }
            }

            throw new FeedFailureException("Feeder timed out when we requested a feed status update.");
        }

        throw new FeedFailureException("Failed to feed for an unknown reason. Is the feeder inserted?");
    }

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        switch (getFeedOptions()) {
        case Normal:
            break;
        case SkipNext:
            setFeedOptions(FeedOptions.Normal);
            // The part that was put back is picked again: that is not a retry of a failed pick.
            picksSinceFeed = 0;
            return;
        case Disable:
            return;
        }

        if (nozzle == null) {
            // A feed by hand. The tape moves whatever happened before, and what was measured
            // no longer describes the pocket now at the pick location.
            feed(null, partPitch);
            resetVisionCalibration();
            return;
        }

        if (!visionCalibrationPending) {
            feed(nozzle, partPitch, isVisionCalibrationDue(feedsSinceVisionCalibration + 1));
            feedsSinceVisionCalibration++;
            picksSinceFeed = 0;
        }
        // Otherwise the last call advanced the tape and then failed to measure it. The job
        // processor retries a failed feed: only the measurement is repeated, not the advance,
        // so no parts are skipped.

        // Decided after the advance: finding the feeder re-inserted voids the correction.
        if (isVisionCalibrationDue(feedsSinceVisionCalibration)) {
            visionCalibrationPending = true;
            calibrateVision(nozzle.getHead().getDefaultCamera());
            visionCalibrationPending = false;
        }
    }

    @Override
    public void postPick(Nozzle nozzle) throws Exception {
        super.postPick(nozzle);
        if (visionCalibration != VisionCalibration.AfterFailedPick) {
            return;
        }
        picksSinceFeed++;
        if (picksSinceFeed > 1) {
            // A second pick from the same pocket: the job processor is retrying a failed pick.
            pickFailedSinceVisionCalibration = true;
        }
        else if (nozzle != null && nozzle.isPartOnEnabled(Nozzle.PartOnStep.AfterPick)) {
            try {
                if (!nozzle.isPartOn()) {
                    pickFailedSinceVisionCalibration = true;
                }
            }
            catch (Exception e) {
                // The job processor makes the same check next and reports what is wrong.
                Logger.warn("Feeder {}: part-on check after pick failed: {}", getName(), e.getMessage());
            }
        }
    }

    /**
     * @param feeds number of feeds since the last measurement, counting the one in hand.
     */
    private boolean isVisionCalibrationDue(int feeds) {
        if (visionCalibrationPending) {
            return true;
        }
        switch (visionCalibration) {
        case EveryFeed:
            return true;
        case EveryNFeeds:
            return getVisionOffset() == null || feeds >= visionCalibrationInterval;
        case AfterFailedPick:
            return pickFailedSinceVisionCalibration;
        default:
            return false;
        }
    }

    /**
     * Look at the tape with the camera and set the correction that getPickLocation() applies from
     * now on. Moves the camera over the configured pick location if it is not already there.
     * Does not feed, and does not count as the measurement a feed in progress is waiting for.
     *
     * @return what was measured, or null if no sprocket holes were found and the feeder is set
     *         to carry on without them.
     * @throws TapeHolesNotFoundException if no sprocket holes were found and the feeder is set to
     *         stop.
     * @throws VisionCorrectionTooLargeException if the pocket is further from the configured pick
     *         location than the maximum correction. No correction is applied in that case.
     */
    public TapeHoleLocator.Measurement calibrateVision(Camera camera) throws Exception {
        Location base = getUncorrectedPickLocation();
        moveCameraTo(camera, base);

        TapeHoleLocator.Measurement measurement;
        try {
            measurement = measureTape(camera);
        }
        catch (TapeHolesNotFoundException e) {
            if (visionStopWhenHolesNotFound) {
                throw new TapeHolesNotFoundException(String.format(
                        "Feeder %s: %s Is the tape loaded, and is the pick location within reach of the sprocket holes?",
                        getName(), e.getMessage()));
            }
            Logger.warn("Feeder {}: {} Picking with {}.", getName(), e.getMessage(),
                    getVisionOffset() == null ? "no correction" : "the previous correction");
            return null;
        }

        double limit = visionMaxCorrection.convertToUnits(LengthUnit.Millimeters).getValue();
        if (measurement.getDistance() > limit) {
            setVisionOffset(null, null);
            throw new VisionCorrectionTooLargeException(String.format(
                    "Feeder %s: the pocket is %.2f mm from the configured pick location (X %+.2f, Y %+.2f), "
                    + "more than the maximum correction of %.2f mm. "
                    + "The tape may have slipped, or the feeder's part offset needs setting again.",
                    getName(), measurement.getDistance(), measurement.getOffsetX(),
                    measurement.getOffsetY(), limit));
        }

        setVisionOffset(new Location(LengthUnit.Millimeters, measurement.getOffsetX(),
                measurement.getOffsetY(), 0, 0), base);
        feedsSinceVisionCalibration = 0;
        pickFailedSinceVisionCalibration = false;
        return measurement;
    }

    protected void moveCameraTo(Camera camera, Location location) throws Exception {
        MovableUtils.moveToLocationAtSafeZ(camera, location);
    }

    private TapeHoleLocator.Measurement measureTape(Camera camera) throws Exception {
        BiConsumer<BufferedImage, String> display = (image, text) -> showVisionResult(camera, image, text);
        try (CvPipeline pipeline = getVisionPipeline()) {
            try {
                return TapeHoleVision.measure(camera, pipeline, partPitch, display);
            }
            catch (TapeHolesNotFoundException e) {
                // One more look: a frame caught before the tape or the head had settled.
                return TapeHoleVision.measure(camera, pipeline, partPitch, display);
            }
        }
    }

    /**
     * Show what vision found in the camera view for a moment. Does nothing without a GUI.
     */
    protected void showVisionResult(Camera camera, BufferedImage image, String text) {
        try {
            if (MainFrame.get() != null) {
                MainFrame.get().getCameraViews().getCameraView(camera)
                        .showFilteredImage(image, text, VISION_RESULT_DISPLAY_MILLISECONDS);
            }
        }
        catch (Exception e) {
            // Showing the result is a convenience: it must never fail a feed.
            Logger.debug("Feeder {}: could not show the vision result: {}", getName(), e.getMessage());
        }
    }

    /**
     * The correction vision last measured, or null if there is none in force.
     */
    public Location getVisionOffset() {
        if (visionCalibration == VisionCalibration.Off || visionOffset == null || offset == null
                || slotAddress == null) {
            return null;
        }
        Location slotLocation = getSlot().getLocation();
        if (slotLocation == null
                || !offset.offsetWithRotationFrom(slotLocation).equals(visionOffsetBase)) {
            // The slot or the offset was changed after the measurement.
            return null;
        }
        return visionOffset;
    }

    private void setVisionOffset(Location visionOffset, Location base) {
        Object oldValue = this.visionOffset;
        this.visionOffset = visionOffset;
        this.visionOffsetBase = base;
        firePropertyChange("visionOffset", oldValue, visionOffset);
    }

    public void resetVisionCalibration() {
        setVisionOffset(null, null);
        feedsSinceVisionCalibration = 0;
        picksSinceFeed = 0;
        pickFailedSinceVisionCalibration = false;
        visionCalibrationPending = false;
    }

    public VisionCalibration getVisionCalibration() {
        return visionCalibration;
    }

    public void setVisionCalibration(VisionCalibration visionCalibration) {
        Object oldValue = this.visionCalibration;
        this.visionCalibration = visionCalibration == null ? VisionCalibration.Off : visionCalibration;
        if (oldValue != this.visionCalibration) {
            resetVisionCalibration();
        }
        firePropertyChange("visionCalibration", oldValue, this.visionCalibration);
    }

    public int getVisionCalibrationInterval() {
        return visionCalibrationInterval;
    }

    public void setVisionCalibrationInterval(int visionCalibrationInterval) {
        int oldValue = this.visionCalibrationInterval;
        this.visionCalibrationInterval = Math.max(1, visionCalibrationInterval);
        firePropertyChange("visionCalibrationInterval", oldValue, this.visionCalibrationInterval);
    }

    public Length getVisionMaxCorrection() {
        return visionMaxCorrection;
    }

    public void setVisionMaxCorrection(Length visionMaxCorrection) {
        if (visionMaxCorrection == null || visionMaxCorrection.getValue() <= 0) {
            throw new IllegalArgumentException("The maximum vision correction must be greater than zero.");
        }
        Object oldValue = this.visionMaxCorrection;
        this.visionMaxCorrection = visionMaxCorrection;
        firePropertyChange("visionMaxCorrection", oldValue, visionMaxCorrection);
    }

    public boolean isVisionStopWhenHolesNotFound() {
        return visionStopWhenHolesNotFound;
    }

    public void setVisionStopWhenHolesNotFound(boolean visionStopWhenHolesNotFound) {
        boolean oldValue = this.visionStopWhenHolesNotFound;
        this.visionStopWhenHolesNotFound = visionStopWhenHolesNotFound;
        firePropertyChange("visionStopWhenHolesNotFound", oldValue, visionStopWhenHolesNotFound);
    }

    public CvPipeline getVisionPipeline() {
        if (visionPipeline == null) {
            visionPipeline = TapeHoleVision.createDefaultPipeline();
        }
        return visionPipeline;
    }

    public void resetVisionPipeline() {
        visionPipeline = TapeHoleVision.createDefaultPipeline();
    }

    public void feedOneMm() throws Exception {
        feed(null, 1);
        resetVisionCalibration();
    }

    @Override
    public String getPropertySheetHolderTitle() {
        String classSimpleName = getClass().getSimpleName();
        if (hardwareId == null) {
            return String.format("Unconfigured %s", classSimpleName);
        } else {
            return String.format("%s %s", classSimpleName, getName());
        }
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        List<PropertySheet> sheets = new ArrayList<>();

        if (hardwareId != null) {
            sheets.add(new FeederPropertySheet(this));
        }

        sheets.add(new GlobalConfigPropertySheet());

        return sheets.toArray(new PropertySheet[0]);
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return new PropertySheetHolder[0];
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return new Action[0];
    }

    @Override
    public Wizard getConfigurationWizard() {
        return null;
    }

    @Override
    public String getName() {
        if (hardwareId == null) {
            return String.format("Unconfigured %s", getClass().getSimpleName());
        }

        StringBuilder result = new StringBuilder();
        result.append(name);
        result.append(" (Slot: ");

        if (slotAddress == null) {
            result.append("None");
        } else {
            result.append(slotAddress);
        }

        result.append(")");

        return result.toString();
    }

    @Override
    public void setName(String name) {
        Matcher matcher = Pattern.compile("(\\(Slot: [\\w+]+\\))").matcher(name);
        while (matcher.find()) {
            name = name.replace(matcher.group(), "");
        }

        name = name.trim();

        super.setName(name);
    }

    /**
     * The PhotonFeeder assumes you have a physical slot that is numbered 1 - 254. That
     * value is also used in the protocol as the address of the feeder once the feeder
     * is initialized.
     *
     * @return The slot address of this feeder or null if it doesn't have one.
     */
    public Integer getSlotAddress() {
        return slotAddress;
    }

    public PhotonFeederSlots.Slot getSlot() {
        if (slotAddress == null) {
            return null;
        }

        return photonProperties.getFeederSlots().getSlot(slotAddress);
    }

    public void setSlotAddress(Integer slotAddress) {
        PhotonFeederSlots.Slot oldSlot = getSlot();
        Integer oldValue = this.slotAddress;
        String oldName = this.getName();

        if (slotAddress != null) {
            // Find any other photon feeders and if they have this slot address, set their address to null
            PhotonFeeder otherFeeder = findBySlotAddress(slotAddress);
            if (otherFeeder != null) {
                otherFeeder.slotAddress = null;
                otherFeeder.initialized = false;
            }
        }

        this.slotAddress = slotAddress;

        firePropertyChange("slotAddress", oldValue, slotAddress);
        firePropertyChange("slot", oldSlot, getSlot());
        firePropertyChange("name", oldName, getName());
    }

    public String getHardwareId() {
        return hardwareId;
    }

    public void setHardwareId(String hardwareId) {
        String oldValue = this.hardwareId;
        this.hardwareId = hardwareId;

        if (getClass().getSimpleName().equals(name)) {
            name = hardwareId;
        }

        firePropertyChange("hardwareId", oldValue, hardwareId);
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() &&
                hardwareId != null &&
                partId != null &&
                slotAddress != null &&
                offset != null &&
                getSlot().getLocation() != null;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setPartPitch(int partPitch) {
        if (partPitch != this.partPitch) {
            // The pockets are somewhere else relative to the sprocket holes.
            resetVisionCalibration();
        }
        this.partPitch = partPitch;
    }

    public int getPartPitch() {
        return partPitch;
    }

    public static PhotonFeeder findByHardwareId(String hardwareId) {
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (!(feeder instanceof PhotonFeeder)) {
                continue;
            }

            PhotonFeeder photonFeeder = (PhotonFeeder) feeder;

            // Are we explicitly asking for feeders with null hardware ID?
            if (photonFeeder.hardwareId == null && hardwareId == null) {
                return photonFeeder;
            }

            if (photonFeeder.hardwareId != null && photonFeeder.hardwareId.equals(hardwareId)) {
                return photonFeeder;
            }
        }

        return null;
    }

    public static PhotonFeeder findBySlotAddress(int slotAddress) {
        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (!(feeder instanceof PhotonFeeder)) {
                continue;
            }

            PhotonFeeder photonFeeder = (PhotonFeeder) feeder;

            if (photonFeeder.slotAddress != null && photonFeeder.slotAddress.equals(slotAddress)) {
                return photonFeeder;
            }
        }

        return null;
    }

    public enum FeederSearchState {
        UNKNOWN,
        SEARCHING,
        FOUND,
        MISSING
    }

    public interface FeederSearchProgressConsumer {
        void accept(int feederAddress, FeederSearchState feederSearchState);
    }

    public static void findAllFeeders(FeederSearchProgressConsumer progressUpdate) throws Exception {
        Logger.info("Searching for Photon Feeders");
        Machine machine = Configuration.get().getMachine();
        PhotonProperties photonProperties = new PhotonProperties(machine);
        int maxFeederAddress = photonProperties.getMaxFeederAddress();
        Logger.debug("Max Photon feeder address: " + maxFeederAddress);

        List<PhotonFeeder> feedersToAdd = new ArrayList<>();

        for (int address = 1; address <= maxFeederAddress; address++) {
            Logger.debug("Querying Photon feeder address: " + address);

            if (progressUpdate != null) {
                progressUpdate.accept(address, FeederSearchState.SEARCHING);
            }

            GetFeederId getFeederId = new GetFeederId(address);
            GetFeederId.Response response = getFeederId.send(photonBus);

            if (progressUpdate != null) {
                progressUpdate.accept(address, response == null ? FeederSearchState.MISSING : FeederSearchState.FOUND);
            }

            if (response == null) {
                PhotonFeeder otherFeeder = findBySlotAddress(address);
                if (otherFeeder != null) {
                    otherFeeder.slotAddress = null;
                    otherFeeder.initialized = false;
                }
            } else {
                PhotonFeeder otherFeeder = findByHardwareId(response.uuid);
                if (otherFeeder == null) {
                    // Try to find an existing feeder without a hardware id before making a new one
                    otherFeeder = findByHardwareId(null);
                    if (otherFeeder == null) {
                        otherFeeder = new PhotonFeeder();
                        feedersToAdd.add(otherFeeder);
                    }
                }

                otherFeeder.setHardwareId(response.uuid);
                otherFeeder.setSlotAddress(address);

                Logger.trace("Found feeder with hardware uuid " + otherFeeder.getHardwareId() + " at address " + otherFeeder.getSlotAddress());
            }
        }

        for (PhotonFeeder feeder : feedersToAdd) {
            Configuration.get().getMachine().addFeeder(feeder);
        }
    }

    @Override
    public boolean canTakeBackPart() {
        return getFeedOptions() == FeedOptions.Normal;
    }

    @Override
    public void takeBackPart(Nozzle nozzle) throws Exception {
        super.takeBackPart(nozzle);
        putPartBack(nozzle);
        setFeedOptions(FeedOptions.SkipNext);
    }

    @Override
    public boolean supportsFeedOptions() {
        return true;
    }
}
