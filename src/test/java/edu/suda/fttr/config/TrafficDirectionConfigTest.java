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
    void rejectsSourcesThatDoNotMatchTheScenarioDirection(@TempDir Path directory) throws IOException {
        Path scenario = writeScenario(directory, "traffic.direction=UL");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(scenario), "UL mode must reject DL sources");

        writeScenario(directory, "traffic.sta.1.rate.pps=1", "traffic.sta.1.packet.bytes=240", "traffic.sta.1.seed=759");
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(scenario), "Default DL mode must reject UL sources");
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

    @Test
    void catchesClockOverflowFromQueuedUplinkPackets(@TempDir Path directory) throws IOException {
        Path scenario = directory.resolve("manual-uplink.properties");
        List<String> properties = new ArrayList<>(List.of("traffic.direction=UL", "fiber.delay.us=10",
                "wifi.delay.us=20", "packet.bytes=240"));
        long arrivalUs = Long.MAX_VALUE - 30;
        properties.add("packet.arrival.us=" + arrivalUs);
        Files.write(scenario, properties, StandardCharsets.UTF_8);
        assertEquals(1, ConfigLoader.load(scenario).packets().size());

        // 单包可恰好到达时钟上界。同一 SFU 的第二包还需等待 20 μs。
        properties.set(properties.size() - 1, "packet.arrival.us=" + arrivalUs + "," + arrivalUs);
        Files.write(scenario, properties, StandardCharsets.UTF_8);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(scenario));
        assertTrue(error.getMessage().contains("clock range"));
    }

    private static Path writeScenario(Path directory, String... settings) throws IOException {
        List<String> properties = new ArrayList<>(List.of(
                "traffic.mode=poisson", "traffic.duration.us=100000", "fiber.delay.us=100", "wifi.delay.us=40",
                "traffic.sfu.1.rate.pps=600", "traffic.sfu.1.packet.bytes=240", "traffic.sfu.1.seed=759"));
        properties.addAll(List.of(settings));
        return Files.write(directory.resolve("direction.properties"), properties, StandardCharsets.UTF_8);
    }
}
