package org.openpnp.machine.photon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTextField;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.Translations;
import org.openpnp.gui.support.WizardContainer;
import org.openpnp.machine.photon.PhotonFeeder.VisionCalibration;
import org.openpnp.machine.photon.exceptions.TapeHolesNotFoundException;
import org.openpnp.machine.photon.exceptions.VisionCorrectionTooLargeException;
import org.openpnp.machine.photon.protocol.commands.InitializeFeeder;
import org.openpnp.machine.photon.protocol.commands.MoveFeedForward;
import org.openpnp.machine.photon.protocol.commands.MoveFeedStatus;
import org.openpnp.machine.photon.protocol.helpers.ResponsesHelper;
import org.openpnp.machine.photon.protocol.helpers.TestBus;
import org.openpnp.machine.photon.sheets.gui.FeederConfigurationWizard;
import org.openpnp.machine.photon.vision.TapeHoleLocator;
import org.openpnp.machine.photon.vision.TapeImages;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.simpleframework.xml.Serializer;

import com.google.common.io.Files;

public class PhotonFeederVisionCalibrationTest {
    private static final double TOLERANCE = 0.03;

    private final String hardwareId = "00112233445566778899AABB";
    private final int feederAddress = 5;
    private final Location slotLocation = new Location(LengthUnit.Millimeters, 100, 50, 4, 0);
    private final Location feederOffset = new Location(LengthUnit.Millimeters, 1, 2, 0, 90);

    /** A PhotonFeeder that records where the camera is sent instead of moving a machine. */
    private static class TestFeeder extends PhotonFeeder {
        final List<Location> cameraMoves = new ArrayList<>();

        @Override
        protected void moveCameraTo(Camera camera, Location location) {
            cameraMoves.add(location);
        }

        final List<String> shown = new ArrayList<>();

        @Override
        protected void showVisionResult(Camera camera, BufferedImage image, String text) {
            assertNotNull(image);
            shown.add(text);
        }
    }

    private TestFeeder feeder;
    private Machine machine;
    private Nozzle nozzle;
    private Camera camera;
    private TestBus bus;
    private ResponsesHelper responses;

    @BeforeEach
    public void setUp() throws Exception {
        File workingDirectory = new File(Files.createTempDir(), ".openpnp");
        Configuration.initialize(workingDirectory);
        Configuration.get().load();

        machine = Configuration.get().getMachine();
        feeder = new TestFeeder();
        machine.addFeeder(feeder);

        machine.removeActuator(machine.getActuatorByName(PhotonFeeder.ACTUATOR_DATA_NAME));
        Actuator mockedActuator = mock(Actuator.class);
        when(mockedActuator.getName()).thenReturn(PhotonFeeder.ACTUATOR_DATA_NAME);
        machine.addActuator(mockedActuator);

        bus = new TestBus();
        PhotonFeeder.setBus(bus);
        responses = new ResponsesHelper(0);

        feeder.setHardwareId(hardwareId);
        feeder.setPartPitch(2);
        feeder.setOffset(feederOffset);
        feeder.setSlotAddress(feederAddress);
        feeder.setMoveWhileFeeding(false);
        new PhotonProperties(machine).getFeederSlots().getSlot(feederAddress).setLocation(slotLocation);

        bus.when(new InitializeFeeder(feederAddress, hardwareId))
                .reply(responses.initializeFeeder.ok(feederAddress, hardwareId));
        bus.when(new MoveFeedForward(feederAddress, 20))
                .reply(responses.moveFeedForward.ok(feederAddress, 0));
        bus.when(new MoveFeedStatus(feederAddress))
                .reply(responses.moveFeedStatus.ok(feederAddress));

        show(TapeImages.tapeWithPocketAt(0.30, 0.40));
    }

    /** Put a nozzle on a head whose camera sees the given image. */
    private void show(BufferedImage image) throws Exception {
        camera = TapeImages.cameraShowing(image);
        Head head = mock(Head.class);
        when(head.getDefaultCamera()).thenReturn(camera);
        nozzle = mock(Nozzle.class);
        when(nozzle.getName()).thenReturn("Test Nozzle");
        when(nozzle.getHead()).thenReturn(head);
    }

    private Location uncorrected() throws Exception {
        return feederOffset.offsetWithRotationFrom(slotLocation);
    }

    private void assertPickLocationOffsetBy(double x, double y) throws Exception {
        Location expected = uncorrected();
        Location actual = feeder.getPickLocation();
        assertEquals(expected.getX() + x, actual.getX(), TOLERANCE);
        assertEquals(expected.getY() + y, actual.getY(), TOLERANCE);
        assertEquals(expected.getZ(), actual.getZ(), 1e-9);
        assertEquals(expected.getRotation(), actual.getRotation(), 1e-9);
    }

    private int captures() throws Exception {
        return org.mockito.Mockito.mockingDetails(camera).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("settleAndCapture"))
                .mapToInt(i -> 1).sum();
    }

    /** Checks that the tape was advanced exactly this many times. */
    private void verifyFeeds(int count) {
        TestBus.ContinuedVerification verification =
                bus.verify(new InitializeFeeder(feederAddress, hardwareId));
        for (int i = 0; i < count; i++) {
            verification = verification.then(new MoveFeedForward(feederAddress, 20))
                    .then(new MoveFeedStatus(feederAddress));
        }
        verification.nothingElseSent();
    }

    @Test
    public void visionCalibrationIsOffByDefault() throws Exception {
        assertEquals(VisionCalibration.Off, new PhotonFeeder().getVisionCalibration());

        feeder.feed(nozzle);

        assertEquals(0, captures());
        assertTrue(feeder.cameraMoves.isEmpty());
        assertNull(feeder.getVisionOffset());
        assertEquals(uncorrected(), feeder.getPickLocation());
        verifyFeeds(1);
    }

    @Test
    public void everyFeedLooksAtTheTapeOverTheConfiguredPickLocation() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);

        feeder.feed(nozzle);

        assertEquals(1, feeder.cameraMoves.size());
        assertEquals(uncorrected(), feeder.cameraMoves.get(0));
        assertEquals(1, captures());
        assertPickLocationOffsetBy(0.30, 0.40);
        verifyFeeds(1);
    }

    @Test
    public void correctionIsNotSavedIntoTheOffset() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);

        feeder.feed(nozzle);

        assertEquals(feederOffset, feeder.getOffset());
        assertEquals(uncorrected(), feeder.getUncorrectedPickLocation());
    }

    @Test
    public void correctionDoesNotAccumulateOverFeeds() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);

        for (int i = 0; i < 5; i++) {
            feeder.feed(nozzle);
        }

        // Every measurement is made at the configured location, not at the last corrected one.
        for (Location move : feeder.cameraMoves) {
            assertEquals(uncorrected(), move);
        }
        assertPickLocationOffsetBy(0.30, 0.40);
    }

    @Test
    public void eachMeasurementReplacesTheLast() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        feeder.feed(nozzle);

        show(TapeImages.tapeWithPocketAt(-0.20, 0.10));
        feeder.feed(nozzle);

        assertPickLocationOffsetBy(-0.20, 0.10);
    }

    @Test
    public void everyNFeedsMeasuresOnTheFirstFeedAndThenEveryNth() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryNFeeds);
        feeder.setVisionCalibrationInterval(3);

        int[] expectedCaptures = {1, 1, 1, 2, 2, 2, 3};
        for (int feed = 0; feed < expectedCaptures.length; feed++) {
            feeder.feed(nozzle);
            assertEquals(expectedCaptures[feed], captures(), "after feed " + (feed + 1));
            // The last correction stays in force between measurements.
            assertPickLocationOffsetBy(0.30, 0.40);
        }
        verifyFeeds(expectedCaptures.length);
    }

    @Test
    public void intervalBelowOneIsTreatedAsOne() {
        feeder.setVisionCalibrationInterval(0);

        assertEquals(1, feeder.getVisionCalibrationInterval());
    }

    @Test
    public void afterFailedPickDoesNotMeasureWhilePicksSucceed() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.AfterFailedPick);
        when(nozzle.isPartOnEnabled(Nozzle.PartOnStep.AfterPick)).thenReturn(true);
        when(nozzle.isPartOn()).thenReturn(true);

        for (int i = 0; i < 3; i++) {
            feeder.feed(nozzle);
            feeder.postPick(nozzle);
        }

        assertEquals(0, captures());
        assertEquals(uncorrected(), feeder.getPickLocation());
    }

    @Test
    public void afterFailedPickMeasuresOnTheFeedFollowingAPickWithNoPartOnTheNozzle() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.AfterFailedPick);
        when(nozzle.isPartOnEnabled(Nozzle.PartOnStep.AfterPick)).thenReturn(true);
        when(nozzle.isPartOn()).thenReturn(false);

        feeder.feed(nozzle);
        assertEquals(0, captures());
        feeder.postPick(nozzle);
        feeder.feed(nozzle);

        assertEquals(1, captures());
        assertPickLocationOffsetBy(0.30, 0.40);

        // The correction stays, and nothing more is measured while picks succeed.
        when(nozzle.isPartOn()).thenReturn(true);
        feeder.postPick(nozzle);
        feeder.feed(nozzle);
        assertEquals(1, captures());
        assertPickLocationOffsetBy(0.30, 0.40);
    }

    @Test
    public void afterFailedPickTakesARetriedPickAsAFailedOne() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.AfterFailedPick);
        // No vacuum sensing on this nozzle.
        when(nozzle.isPartOnEnabled(Nozzle.PartOnStep.AfterPick)).thenReturn(false);

        feeder.feed(nozzle);
        feeder.postPick(nozzle);
        // The job processor picks again from the same pocket without feeding.
        feeder.postPick(nozzle);
        feeder.feed(nozzle);

        assertEquals(1, captures());
        verify(nozzle, times(0)).isPartOn();
    }

    @Test
    public void otherModesDoNotCheckForAPartAfterThePick() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);

        feeder.feed(nozzle);
        feeder.postPick(nozzle);

        verify(nozzle, times(0)).isPartOn();
    }

    @Test
    public void correctionBeyondTheLimitFailsTheFeedAndIsNotApplied() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        feeder.setVisionMaxCorrection(new Length(0.4, LengthUnit.Millimeters));

        VisionCorrectionTooLargeException e =
                assertThrows(VisionCorrectionTooLargeException.class, () -> feeder.feed(nozzle));

        assertTrue(e.getMessage().contains("0.50 mm"), e.getMessage());
        assertTrue(e.getMessage().contains("0.40 mm"), e.getMessage());
        assertNull(feeder.getVisionOffset());
        assertEquals(uncorrected(), feeder.getPickLocation());
    }

    @Test
    public void correctionBeyondTheLimitDropsAnEarlierCorrection() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        feeder.feed(nozzle);
        assertNotNull(feeder.getVisionOffset());

        show(TapeImages.tapeWithPocketAt(0.1, 0.95));
        assertThrows(VisionCorrectionTooLargeException.class, () -> feeder.feed(nozzle));

        assertNull(feeder.getVisionOffset());
    }

    @Test
    public void defaultLimitIsWithinWhatTwoMillimetrePitchTapeCanResolve() {
        double limit = new PhotonFeeder().getVisionMaxCorrection()
                .convertToUnits(LengthUnit.Millimeters).getValue();

        assertEquals(0.75, limit, 1e-9);
        assertTrue(limit < TapeHoleLocator.getMaxUnambiguousCorrection(2));
    }

    @Test
    public void maximumCorrectionMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> feeder.setVisionMaxCorrection(new Length(0, LengthUnit.Millimeters)));
        assertThrows(IllegalArgumentException.class, () -> feeder.setVisionMaxCorrection(null));
    }

    @Test
    public void retryAfterAFailedMeasurementDoesNotAdvanceTheTapeAgain() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        show(TapeImages.blank());

        // What the job processor does with a feed retry count of 2.
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(TapeHolesNotFoundException.class, () -> feeder.feed(nozzle));
        }
        verifyFeeds(1);

        // Once the tape can be seen the same feed completes, still without advancing again.
        show(TapeImages.tapeWithPocketAt(0.30, 0.40));
        feeder.feed(nozzle);
        assertPickLocationOffsetBy(0.30, 0.40);
        bus.verifyNothingSent();
    }

    @Test
    public void feedAfterASuccessfulRetryAdvancesTheTapeAgain() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        show(TapeImages.blank());
        assertThrows(TapeHolesNotFoundException.class, () -> feeder.feed(nozzle));
        show(TapeImages.tapeWithPocketAt(0.30, 0.40));
        feeder.feed(nozzle);

        feeder.feed(nozzle);

        verifyFeeds(2);
    }

    @Test
    public void noHolesFoundStopsTheFeedByDefaultAndNamesTheFeeder() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        show(TapeImages.blank());

        TapeHolesNotFoundException e =
                assertThrows(TapeHolesNotFoundException.class, () -> feeder.feed(nozzle));

        assertTrue(e.getMessage().contains(feeder.getName()), e.getMessage());
        // A second look is taken before giving up.
        assertEquals(2, captures());
    }

    @Test
    public void noHolesFoundCanBeSetToCarryOnWithTheLastCorrection() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        feeder.setVisionStopWhenHolesNotFound(false);
        feeder.feed(nozzle);

        show(TapeImages.blank());
        feeder.feed(nozzle);

        assertPickLocationOffsetBy(0.30, 0.40);
        verifyFeeds(2);
    }

    @Test
    public void noHolesFoundCanBeSetToCarryOnUncorrected() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        feeder.setVisionStopWhenHolesNotFound(false);
        show(TapeImages.blank());

        feeder.feed(nozzle);

        assertEquals(uncorrected(), feeder.getPickLocation());
    }

    @Test
    public void changingTheOffsetVoidsTheCorrection() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryNFeeds);
        feeder.feed(nozzle);
        assertNotNull(feeder.getVisionOffset());

        Location newOffset = new Location(LengthUnit.Millimeters, 1.5, 2, 0, 90);
        feeder.setOffset(newOffset);

        assertNull(feeder.getVisionOffset());
        assertEquals(newOffset.offsetWithRotationFrom(slotLocation), feeder.getPickLocation());
        // And the next feed measures again rather than waiting for the interval.
        feeder.feed(nozzle);
        assertEquals(2, captures());
    }

    @Test
    public void changingTheSlotLocationVoidsTheCorrection() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryNFeeds);
        feeder.feed(nozzle);

        Location moved = new Location(LengthUnit.Millimeters, 101, 50, 4, 0);
        new PhotonProperties(machine).getFeederSlots().getSlot(feederAddress).setLocation(moved);

        assertNull(feeder.getVisionOffset());
        assertEquals(feederOffset.offsetWithRotationFrom(moved), feeder.getPickLocation());
    }

    @Test
    public void changingThePartPitchVoidsTheCorrection() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryNFeeds);
        feeder.feed(nozzle);

        feeder.setPartPitch(4);

        assertNull(feeder.getVisionOffset());
    }

    @Test
    public void switchingOffRemovesTheCorrection() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        feeder.feed(nozzle);

        feeder.setVisionCalibration(VisionCalibration.Off);

        assertEquals(uncorrected(), feeder.getPickLocation());
        feeder.setVisionCalibration(VisionCalibration.EveryNFeeds);
        assertNull(feeder.getVisionOffset());
    }

    @Test
    public void manualFeedWithoutANozzleDoesNotMeasure() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        bus.when(new MoveFeedForward(feederAddress, 10))
                .reply(responses.moveFeedForward.ok(feederAddress, 0));

        feeder.feed(null);
        feeder.feedOneMm();

        assertEquals(0, captures());
    }

    @Test
    public void manualFeedVoidsTheCorrection() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryNFeeds);
        bus.when(new MoveFeedForward(feederAddress, 10))
                .reply(responses.moveFeedForward.ok(feederAddress, 0));
        feeder.feed(nozzle);
        assertNotNull(feeder.getVisionOffset());

        feeder.feedOneMm();
        assertNull(feeder.getVisionOffset());

        feeder.feed(nozzle);
        assertNotNull(feeder.getVisionOffset());
        feeder.feed(null);
        assertNull(feeder.getVisionOffset());
    }

    @Test
    public void manualFeedAdvancesTheTapeEvenAfterAFailedMeasurement() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        show(TapeImages.blank());
        assertThrows(TapeHolesNotFoundException.class, () -> feeder.feed(nozzle));

        feeder.feed(null);
        verifyFeeds(2);

        // And the job's next feed is an ordinary one again.
        show(TapeImages.tapeWithPocketAt(0.30, 0.40));
        feeder.feed(nozzle);
        bus.verify(new MoveFeedForward(feederAddress, 20)).then(new MoveFeedStatus(feederAddress))
                .nothingElseSent();
    }

    @Test
    public void measuringByHandAfterAFailedMeasurementDoesNotMakeTheNextFeedSkipTheWaitingPocket() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        show(TapeImages.blank());
        assertThrows(TapeHolesNotFoundException.class, () -> feeder.feed(nozzle));
        verifyFeeds(1);

        show(TapeImages.tapeWithPocketAt(0.30, 0.40));
        feeder.calibrateVision(camera);
        // The pocket that was fed has still not been picked: the retried feed must not advance.
        feeder.feed(nozzle);

        bus.verifyNothingSent();
        assertPickLocationOffsetBy(0.30, 0.40);
    }

    @Test
    public void partPutBackAndPickedAgainIsNotTakenForAFailedPick() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.AfterFailedPick);
        when(nozzle.isPartOnEnabled(Nozzle.PartOnStep.AfterPick)).thenReturn(false);

        feeder.feed(nozzle);
        feeder.postPick(nozzle);
        feeder.setFeedOptions(org.openpnp.machine.reference.ReferenceFeeder.FeedOptions.SkipNext);
        feeder.feed(nozzle);
        feeder.postPick(nozzle);
        feeder.feed(nozzle);

        assertEquals(0, captures());
    }

    private long issuesForFeeder() {
        org.openpnp.model.Solutions solutions = new org.openpnp.model.Solutions();
        solutions.findIssues();
        solutions.publishIssues();
        return solutions.getIssues().stream().filter(i -> i.getSubject() == feeder).count();
    }

    @Test
    public void tooLargeAMaximumCorrectionIsReportedAsAnIssue() {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        long before = issuesForFeeder();

        feeder.setVisionMaxCorrection(new Length(1.5, LengthUnit.Millimeters));

        assertEquals(before + 1, issuesForFeeder());
    }

    @Test
    public void eachMeasurementIsShownInTheCameraView() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);

        feeder.feed(nozzle);
        assertEquals(1, feeder.shown.size());
        assertTrue(feeder.shown.get(0).startsWith("Pocket at X +0.3"), feeder.shown.get(0));

        show(TapeImages.blank());
        assertThrows(TapeHolesNotFoundException.class, () -> feeder.feed(nozzle));
        // Both looks at the empty image are shown.
        assertEquals(Arrays.asList("No sprocket holes found", "No sprocket holes found"),
                feeder.shown.subList(1, feeder.shown.size()));
    }

    @Test
    public void showingTheResultWithoutAGuiDoesNothing() throws Exception {
        PhotonFeeder plain = new PhotonFeeder();

        plain.showVisionResult(camera, TapeImages.blank(), "text");
    }

    @Test
    public void calibrateVisionCanBeCalledOnItsOwn() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.AfterFailedPick);

        TapeHoleLocator.Measurement measurement = feeder.calibrateVision(camera);

        assertEquals(0.30, measurement.getOffsetX(), TOLERANCE);
        assertEquals(0.40, measurement.getOffsetY(), TOLERANCE);
        assertPickLocationOffsetBy(0.30, 0.40);
        bus.verifyNothingSent();
    }

    @Test
    public void cameraGoesOverThePickLocationWhileTheTapeMoves() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryFeed);
        feeder.setMoveWhileFeeding(true);
        machine.setHomed(true);

        feeder.feed(nozzle);

        // Once during the feed and once, as a move of no distance, before the measurement.
        assertEquals(2, feeder.cameraMoves.size());
        assertEquals(uncorrected(), feeder.cameraMoves.get(0));
        // The nozzle is not sent anywhere: it follows for the pick.
        verify(nozzle, times(0)).moveTo(any());
        verify(nozzle, times(0)).moveTo(any(), any());
    }

    @Test
    public void configurationWizardShowsAndAppliesTheVisionSettings() throws Exception {
        feeder.setVisionCalibration(VisionCalibration.EveryNFeeds);
        feeder.setVisionCalibrationInterval(7);
        FeederConfigurationWizard wizard = new FeederConfigurationWizard(feeder);
        // Creates the bindings and loads the feeder's values, as showing the wizard does.
        wizard.setWizardContainer(mock(WizardContainer.class));

        JComboBox<?> calibration = find(wizard, JComboBox.class,
                c -> c.getItemCount() > 0 && c.getItemAt(0) instanceof VisionCalibration);
        assertEquals(VisionCalibration.EveryNFeeds, calibration.getSelectedItem());
        JTextField interval = find(wizard, JTextField.class, c -> "7".equals(c.getText()));
        assertNotNull(interval);

        calibration.setSelectedItem(VisionCalibration.AfterFailedPick);
        interval.setText("12");
        find(wizard, JButton.class, b -> Translations.getString(
                "AbstractConfigurationWizard.Action.Apply").equals(b.getText())).doClick();

        assertEquals(VisionCalibration.AfterFailedPick, feeder.getVisionCalibration());
        assertEquals(12, feeder.getVisionCalibrationInterval());
    }

    private static <T extends Component> T find(Container container, Class<T> type, Predicate<T> test) {
        for (Component component : container.getComponents()) {
            if (type.isInstance(component) && test.test(type.cast(component))) {
                return type.cast(component);
            }
            if (component instanceof Container) {
                T found = find((Container) component, type, test);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void settingsThatAreOffAreNotWrittenAsAPipeline() throws Exception {
        Serializer serializer = Configuration.createSerializer();
        feeder.setPart(Configuration.get().getParts().get(0));
        StringWriter off = new StringWriter();
        serializer.write(feeder, off);
        assertFalse(off.toString().contains("vision-pipeline"), off.toString());
        assertTrue(off.toString().contains("vision-calibration=\"Off\""), off.toString());

        feeder.setVisionCalibration(VisionCalibration.EveryNFeeds);
        feeder.setVisionCalibrationInterval(7);
        feeder.feed(nozzle);
        StringWriter on = new StringWriter();
        serializer.write(feeder, on);
        String xml = on.toString();
        assertTrue(xml.contains("vision-calibration=\"EveryNFeeds\""), xml);
        assertTrue(xml.contains("vision-calibration-interval=\"7\""), xml);
        // The measured correction is never part of the saved configuration.
        assertFalse(xml.contains("vision-offset"), xml);
    }
}
