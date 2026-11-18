package edu.suda.fttr.config;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.simulator.Simulator;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EdcaConfigTest {
    @Test
    void calculatesDefaultAifsForEachCategory(@TempDir Path directory) throws IOException {
        EdcaParameters edca = load(directory).edcaParameters();

        assertEquals(16, edca.sifsUs());
        assertEquals(9, edca.slotTimeUs());
        assertCategory(edca, AccessCategory.VO, 2, 3, 7, 34);
        assertCategory(edca, AccessCategory.VI, 3, 7, 15, 43);
        assertCategory(edca, AccessCategory.BE, 6, 15, 1023, 70);
        assertCategory(edca, AccessCategory.BK, 9, 15, 1023, 97);
    }

    @Test
    void appliesPartialOverridesAndSharesSifsWithAck(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "wifi.mac.exchange=data-sifs-ack", "wifi.mac.sifs.us=20",
                "wifi.mac.slot.us=5", "wifi.mac.edca.VO.aifsn=4", "wifi.mac.edca.VO.cwmin=0",
                "wifi.mac.edca.BE.cwmin=1023", "wifi.mac.edca.BK.aifsn=15", "wifi.mac.edca.BK.cwmax=32767");
        EdcaParameters edca = config.edcaParameters();

        assertEquals(20, edca.sifsUs());
        assertEquals(5, edca.slotTimeUs());
        assertEquals(20_000, config.wifiFrameExchange().sifsNs());
        assertCategory(edca, AccessCategory.VO, 4, 0, 7, 40);
        assertCategory(edca, AccessCategory.VI, 3, 7, 15, 35);
        assertCategory(edca, AccessCategory.BE, 6, 1023, 1023, 50);
        assertCategory(edca, AccessCategory.BK, 15, 15, 32767, 95);
    }

    @Test
    void readsSifsInDataOnlyMode(@TempDir Path directory) throws IOException {
        SimulationConfig config = load(directory, "wifi.mac.sifs.us=20");

        assertNull(config.wifiFrameExchange());
        assertEquals(38, config.edcaParameters().aifsUs(AccessCategory.VO));
    }

    @Test
    void edcaOverridesDoNotAffectTheFixedPriorityTrace(@TempDir Path directory) throws IOException {
        SimulationConfig defaults = load(directory, "wifi.mac.exchange=data-sifs-ack");
        SimulationConfig overrides = load(directory, "wifi.mac.exchange=data-sifs-ack", "wifi.mac.slot.us=10",
                "wifi.mac.edca.VO.aifsn=15", "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.BK.aifsn=2");

        // 即使把 BK 的 AIFS 调到 VO 之前，仿真发送顺序和时间仍由现有调度决定。
        assertEquals(166, overrides.edcaParameters().aifsUs(AccessCategory.VO));
        assertEquals(36, overrides.edcaParameters().aifsUs(AccessCategory.BK));
        assertEquals(trace(defaults), trace(overrides));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wifi.mac.sifs.us=0", "wifi.mac.sifs.us=1.5", "wifi.mac.slot.us=0",
            "wifi.mac.slot.us=-9", "wifi.mac.slot.us=0.8", "wifi.mac.slot.us=9223372036854775",
            "wifi.mac.slot.us=9223372036854775807", "wifi.mac.edca.VO.aifsn=1", "wifi.mac.edca.BK.aifsn=16",
            "wifi.mac.edca.VO.cwmin=-1", "wifi.mac.edca.VO.cwmin=4", "wifi.mac.edca.VO.cwmin=15",
            "wifi.mac.edca.BE.cwmax=65535", "wifi.mac.edca.VX.aifsn=2", "wifi.mac.edca.BE.cw.mx=15"})
    void rejectsInvalidSettings(String property, @TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> load(directory, property), property);
    }

    private static void assertCategory(EdcaParameters edca, AccessCategory category, int aifsn,
                                       int cwMin, int cwMax, long aifsUs) {
        EdcaParameters.AcParameters parameters = edca.forCategory(category);
        assertEquals(aifsn, parameters.aifsn(), category.name());
        assertEquals(cwMin, parameters.cwMin(), category.name());
        assertEquals(cwMax, parameters.cwMax(), category.name());
        assertEquals(aifsUs, edca.aifsUs(category), category.name());
    }

    private static SimulationConfig load(Path directory, String... settings) throws IOException {
        List<String> properties = new ArrayList<>(List.of("fiber.delay.us=0", "packet.arrival.us=0,0,0,0",
                "packet.bytes=1500", "packet.1.type=BK", "packet.2.type=BE", "packet.3.type=VI", "packet.4.type=VO"));
        properties.addAll(List.of(settings));
        Path file = Files.write(directory.resolve("edca.properties"), properties, StandardCharsets.UTF_8);
        return ConfigLoader.load(file);
    }

    private static String trace(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
