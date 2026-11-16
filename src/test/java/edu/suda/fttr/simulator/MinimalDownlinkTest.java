package edu.suda.fttr.simulator;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.trace.TraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinimalDownlinkTest {
    @Test
    void deliversDefaultPacketWithExpectedTimeline() throws IOException {
        SimulationConfig config = ConfigLoader.loadDefault();
        assertEquals(1200, config.packetBytes());
        assertEquals(0, config.packetArrivalUs());
        assertEquals(100, config.fiberDelayUs());
        assertEquals(250, config.wifiDelayUs());

        // 整条轨迹同时验证发送先于下一跳到达、报文身份一致和交付仅统计一次。
        assertEquals(List.of(
                "t_us=0 node=MFU event=ARRIVAL packet_id=1 bytes=1200",
                "t_us=0 node=MFU event=FIBER_TX_START packet_id=1 bytes=1200",
                "t_us=100 node=SFU event=ARRIVAL packet_id=1 bytes=1200",
                "t_us=100 node=SFU event=WIFI_TX_START packet_id=1 bytes=1200",
                "t_us=350 node=STA event=DELIVERED packet_id=1 bytes=1200",
                "delivered_packets=1 delivered_bytes=1200 e2e_delay_us=350"
        ), run(config));
    }

    @Test
    void readsCustomDelaysAndMaintainsCausalityAtEqualTimes(@TempDir Path directory) throws IOException {
        Path scenario = directory.resolve("zero-wifi-delay.properties");
        Files.write(scenario, List.of(
                "# 同刻发送与交付仍需按因果顺序执行",
                "packet.bytes=432",
                "packet.arrival.us=500",
                "fiber.delay.us=17",
                "wifi.delay.us=0"
        ), StandardCharsets.UTF_8);

        assertEquals(List.of(
                "t_us=500 node=MFU event=ARRIVAL packet_id=1 bytes=432",
                "t_us=500 node=MFU event=FIBER_TX_START packet_id=1 bytes=432",
                "t_us=517 node=SFU event=ARRIVAL packet_id=1 bytes=432",
                "t_us=517 node=SFU event=WIFI_TX_START packet_id=1 bytes=432",
                "t_us=517 node=STA event=DELIVERED packet_id=1 bytes=432",
                "delivered_packets=1 delivered_bytes=432 e2e_delay_us=17"
        ), run(ConfigLoader.load(scenario)));
    }

    private List<String> run(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(output));
        }
        return buffer.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());
    }
}
