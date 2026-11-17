package edu.suda.fttr.config;

import edu.suda.fttr.entity.Packet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrafficDirectionConfigTest {
    @Test
    void rejectsUplinkSimulationConfigurationAtLoadTime(@TempDir Path directory) throws IOException {
        for (String uplinkSetting : List.of("traffic.direction=UL", "traffic.sta.1.rate.pps=1")) {
            Path scenario = writeScenario(directory, uplinkSetting);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(scenario));
            assertTrue(error.getMessage().contains("Uplink traffic (UL) is not supported"), error.getMessage());
        }
    }

    @Test
    void acceptsExplicitDownlinkAndKeepsLegacyPacketAccessors(@TempDir Path directory) throws IOException {
        SimulationConfig config = ConfigLoader.load(writeScenario(directory, "traffic.direction=DL"));
        assertTrue(config.packets().size() > 10);
        for (Packet packet : config.packets()) {
            assertEquals(Packet.Direction.DL, packet.direction());
            assertEquals(packet.generationTimeUs(), packet.mfuArrivalUs());
            assertEquals(packet.destinationNodeId(), packet.destinationStaId());
            assertEquals(1, packet.destinationStaId());
        }
    }

    private static Path writeScenario(Path directory, String setting) throws IOException {
        List<String> properties = new ArrayList<>(List.of(
                "traffic.mode=poisson", "traffic.duration.us=100000", "fiber.delay.us=100", "wifi.delay.us=40",
                "traffic.sfu.1.rate.pps=600", "traffic.sfu.1.packet.bytes=240", "traffic.sfu.1.seed=759"));
        properties.add(setting);
        return Files.write(directory.resolve("direction.properties"), properties, StandardCharsets.UTF_8);
    }
}
