package edu.suda.fttr.config;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.link.AmpduConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmpduConfigTest {
    @Test
    void preservesTheOriginalDefaultAndEnablesOnlyConfiguredAggregation(@TempDir Path temp) throws IOException {
        SimulationConfig original = load(temp, "");
        assertNull(original.ampduConfig());
        assertEquals(70, original.edcaParameters().aifsUs(AccessCategory.BE));

        SimulationConfig enabled = load(temp, "wifi.mac.ampdu.enabled=true\n");
        assertEquals(8, enabled.ampduConfig().maxMpdus());
        assertEquals(65_535, enabled.ampduConfig().maxBytes());
        assertFalse(enabled.ampduConfig().failsFirstAttempt(1));
        assertEquals(70, enabled.edcaParameters().aifsUs(AccessCategory.BE));
    }

    @Test
    void readsBothCapsAndExplicitErrorFixtureWithBeOverride(@TempDir Path temp) throws IOException {
        SimulationConfig enabled = load(temp, "wifi.mac.ampdu.enabled=true\n"
                + "wifi.mac.ampdu.max-mpdus=4\n"
                + "wifi.mac.ampdu.max-bytes=7000\n"
                + "wifi.mac.ampdu.first-attempt-error.packet.ids=2, 3\n"
                + "wifi.mac.edca.BE.aifsn=3\n");
        AmpduConfig aggregation = enabled.ampduConfig();
        assertEquals(4, aggregation.maxMpdus());
        assertEquals(7_000, aggregation.maxBytes());
        assertTrue(aggregation.failsFirstAttempt(2));
        assertTrue(aggregation.failsFirstAttempt(3));
        assertFalse(aggregation.failsFirstAttempt(1));
        assertEquals(43, enabled.edcaParameters().aifsUs(AccessCategory.BE));
        assertEquals(34, enabled.edcaParameters().aifsUs(AccessCategory.VO));
        assertEquals(43, enabled.edcaParameters().aifsUs(AccessCategory.VI));
        assertEquals(97, enabled.edcaParameters().aifsUs(AccessCategory.BK));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "wifi.mac.ampdu.max-mpdus=0", "wifi.mac.ampdu.max-mpdus=65",
            "wifi.mac.ampdu.max-bytes=0", "wifi.mac.ampdu.max-bytes=6500632",
            "wifi.mac.ampdu.first-attempt-error.packet.ids=2,2",
            "wifi.mac.ampdu.first-attempt-error.packet.ids=0",
            "wifi.mac.ampdu.first-attempt-error.packet.ids=2147483648",
            "wifi.mac.ampdu.first-attempt-error.packet.ids=2,",
            "wifi.mac.exchange=data-only", "wifi.mac.access=fixed-priority",
            "wifi.mac.edca.BE.txop.limit.us=500", "wifi.phy.mac.overhead.bytes=3"
    })
    void rejectsInvalidEnabledSettings(String property, @TempDir Path temp) {
        assertThrows(IllegalArgumentException.class, () -> load(temp, "wifi.mac.ampdu.enabled=true\n" + property));
    }

    @Test
    void rejectsAFlagTypo(@TempDir Path temp) {
        assertThrows(IllegalArgumentException.class, () -> load(temp, "wifi.mac.ampdu.enabled=yes\n"));
    }

    private static SimulationConfig load(Path temp, String overrides) throws IOException {
        Path file = Files.writeString(temp.resolve("ampdu.properties"),
                "fiber.delay.us=0\npacket.arrival.us=0,0,0\npacket.bytes=1500\n"
                        + "wifi.mac.exchange=data-sifs-ack\nwifi.mac.access=edca-multi-ac\n" + overrides);
        return ConfigLoader.load(file);
    }
}
