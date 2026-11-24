package edu.suda.fttr.channel;

import edu.suda.fttr.link.WifiChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalInterferenceTest {
    @Test
    void sumsConcurrentSourcesAndIntegratesHalfOpenCcaIntervals() {
        ExternalInterference model = new ExternalInterference(-82, List.of(
                new ExternalInterference.Activity(1, 2, 10, 30, -84, -110),
                new ExternalInterference.Activity(1, 2, 20, 40, -84, -110),
                new ExternalInterference.Activity(2, 0, 0, 50, -75, -110)));

        assertEquals(0, model.apBusyMask(1, 19));
        assertEquals(4, model.apBusyMask(1, 20));
        assertEquals(4, model.apBusyMask(1, 29));
        assertEquals(0, model.apBusyMask(1, 30));
        assertEquals(1, model.apBusyMask(2, 20));
        assertEquals(10, model.apBusyDurationNs(1, 2, 0, 40));
        assertEquals(5, model.apBusyDurationNs(1, 2, 15, 25));
        assertEquals(0, model.apBusyDurationNs(1, 3, 15, 25));
        assertEquals(0, model.apBusyDurationNs(1, 2, 30, 30));
        assertThrows(IllegalArgumentException.class, () -> model.apBusyDurationNs(1, 2, 30, 29));
    }

    @Test
    void receiverSinrUsesItsOwnBackgroundAndExternalPowerWithoutCountingCcaAgain() {
        ExternalInterference model = new ExternalInterference(-82, List.of(
                new ExternalInterference.Activity(1, 0, 100, 200, -100, -90),
                new ExternalInterference.Activity(1, 0, 100, 200, -100, -90),
                new ExternalInterference.Activity(1, 1, 100, 200, -70, -120)));
        ChannelTruth.Change baseline = new ChannelTruth.Change(1, 1, 0, 0, 20, -1, true, -90);
        assertEquals(20, model.downlinkSinrDb(baseline, 99));
        assertEquals(20 - 10 * Math.log10(3), model.downlinkSinrDb(baseline, 100), 1e-12);
        assertEquals(20, model.downlinkSinrDb(baseline, 200));
        assertEquals(2, model.apBusyMask(1, 150));
        assertEquals(List.of(100L, 200L), List.of(model.edges().get(0).timeNs(), model.edges().get(1).timeNs()));
    }

    @Test
    void externalAndOwnedBusyIntervalsFormAUnionWithoutSpuriousIdleTransitions() {
        WifiChannel channel = new WifiChannel();
        List<WifiChannel.State> states = new ArrayList<>();
        channel.onStateChange(states::add);
        channel.occupy();
        channel.setExternalBusy(true);
        channel.release();
        assertEquals(WifiChannel.State.BUSY, channel.state());
        assertThrows(IllegalStateException.class, channel::occupy);
        channel.setExternalBusy(false);
        assertEquals(List.of(WifiChannel.State.BUSY, WifiChannel.State.IDLE), states);
    }
}
