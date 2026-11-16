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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void serializesBurstsAndReportsBusyAndIdleTime() throws IOException {
        List<String> trace = run(ConfigLoader.load(Path.of("configs", "phase1-serial-downlink.properties")));

        // 四段占用包含同刻接力与一段空闲。逐包检查可发现重复启动和错误提前释放。
        assertEquals(List.of(
                "t_us=100 node=SFU event=WIFI_TX_START packet_id=1 bytes=1200",
                "t_us=350 node=SFU event=WIFI_TX_END packet_id=1 wifi_tx_start_us=100 wifi_queue_wait_us=0 wifi_service_us=250 e2e_delay_us=350",
                "t_us=350 node=SFU event=WIFI_TX_START packet_id=2 bytes=1200",
                "t_us=600 node=SFU event=WIFI_TX_END packet_id=2 wifi_tx_start_us=350 wifi_queue_wait_us=250 wifi_service_us=250 e2e_delay_us=600",
                "t_us=600 node=SFU event=WIFI_TX_START packet_id=3 bytes=1200",
                "t_us=850 node=SFU event=WIFI_TX_END packet_id=3 wifi_tx_start_us=600 wifi_queue_wait_us=250 wifi_service_us=250 e2e_delay_us=600",
                "t_us=1100 node=SFU event=WIFI_TX_START packet_id=4 bytes=1200",
                "t_us=1350 node=SFU event=WIFI_TX_END packet_id=4 wifi_tx_start_us=1100 wifi_queue_wait_us=0 wifi_service_us=250 e2e_delay_us=350"
        ), trace.stream().filter(line -> line.contains("event=WIFI_TX_")).collect(Collectors.toList()));
        assertEquals(List.of(
                "t_us=350 node=STA event=DELIVERED packet_id=1 bytes=1200",
                "t_us=600 node=STA event=DELIVERED packet_id=2 bytes=1200",
                "t_us=850 node=STA event=DELIVERED packet_id=3 bytes=1200",
                "t_us=1350 node=STA event=DELIVERED packet_id=4 bytes=1200"
        ), trace.stream().filter(line -> line.contains("event=DELIVERED")).collect(Collectors.toList()));
        assertEquals("delivered_packets=4 delivered_bytes=4800 wifi_busy_us=1000 wifi_idle_us=350 simulation_end_us=1350",
                trace.get(trace.size() - 1));

        long previousUs = 0;
        for (String line : trace) {
            if (line.startsWith("t_us=")) {
                long currentUs = Long.parseLong(line.substring(5, line.indexOf(' ')));
                assertTrue(currentUs >= previousUs, line);
                previousUs = currentUs;
            }
        }
    }

    @Test
    void waitsForCompletionWhenSameTimeArrivalIsDispatchedFirst(@TempDir Path directory) throws IOException {
        List<String> trace = run(ConfigLoader.load(writeScenario(directory, "0,150", 200, 150)));

        assertEquals(List.of(
                "t_us=350 node=SFU event=ARRIVAL packet_id=2 bytes=1200",
                "t_us=350 node=STA event=DELIVERED packet_id=1 bytes=1200",
                "t_us=350 node=SFU event=WIFI_TX_END packet_id=1 wifi_tx_start_us=200 wifi_queue_wait_us=0 wifi_service_us=150 e2e_delay_us=350",
                "t_us=350 node=SFU event=WIFI_TX_START packet_id=2 bytes=1200"
        ), trace.stream().filter(line -> line.startsWith("t_us=350 ")).collect(Collectors.toList()));
        assertEquals("delivered_packets=2 delivered_bytes=2400 wifi_busy_us=300 wifi_idle_us=200 simulation_end_us=500",
                trace.get(trace.size() - 1));
    }

    @Test
    void zeroTimeTransmissionsStillFinishBeforeStartingNextPacket(@TempDir Path directory) throws IOException {
        List<String> trace = run(ConfigLoader.load(writeScenario(directory, "0,0", 0, 0)));
        assertEquals(List.of(
                "t_us=0 node=SFU event=WIFI_TX_START packet_id=1 bytes=1200",
                "t_us=0 node=SFU event=WIFI_TX_END packet_id=1 wifi_tx_start_us=0 wifi_queue_wait_us=0 wifi_service_us=0 e2e_delay_us=0",
                "t_us=0 node=SFU event=WIFI_TX_START packet_id=2 bytes=1200",
                "t_us=0 node=SFU event=WIFI_TX_END packet_id=2 wifi_tx_start_us=0 wifi_queue_wait_us=0 wifi_service_us=0 e2e_delay_us=0"
        ), trace.stream().filter(line -> line.contains("event=WIFI_TX_")).collect(Collectors.toList()));
        assertEquals("delivered_packets=2 delivered_bytes=2400 wifi_busy_us=0 wifi_idle_us=0 simulation_end_us=0",
                trace.get(trace.size() - 1));
    }

    @Test
    void rejectsClockOverflowCausedOnlyByQueueing(@TempDir Path directory) throws IOException {
        long arrival = Long.MAX_VALUE - 250;
        Path scenario = writeScenario(directory, arrival + "," + arrival, 0, 250);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(scenario));
        assertTrue(exception.getMessage().contains("exceeds the clock range"));
    }

    private Path writeScenario(Path directory, String arrivals, long fiberDelay, long wifiDelay) throws IOException {
        return Files.write(directory.resolve("serial.properties"), List.of(
                "packet.bytes=1200",
                "packet.arrival.us=" + arrivals,
                "fiber.delay.us=" + fiberDelay,
                "wifi.delay.us=" + wifiDelay
        ), StandardCharsets.UTF_8);
    }

    private List<String> run(SimulationConfig config) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            Simulator.run(config, new TraceLogger(output));
        }
        // 原单 FIFO 用例继续断言时序与交付量，新增字段和日志由优先级用例单独核对。
        return buffer.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("summary="))
                .filter(line -> !line.contains("event=SFU_ENQUEUE") && !line.contains("event=QUEUE_SELECT"))
                .filter(line -> config.packets().size() > 1 || !line.contains("event=WIFI_TX_END"))
                .map(line -> line.split(" type=", 2)[0])
                .collect(Collectors.toList());
    }
}
