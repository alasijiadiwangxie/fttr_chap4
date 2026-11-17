package edu.suda.fttr.traffic;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.Event;
import edu.suda.fttr.simulator.EventType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrafficGeneratorTest {
    @Test
    void emitsDirectionAndEndpointMetadataWithoutNetworkTopology() {
        long durationUs = 8_000_000;
        // 源和目的 ID 由调用方传入，与仿真的设备编号无关。两台 STA 分别配置自己的上行到达率。
        List<TrafficGenerator.Source> sources = List.of(
                new TrafficGenerator.Source(Packet.Direction.UL, 11, List.of(700),
                        180, 500, 427, durationUs, new double[]{1, 0, 0, 0}),
                new TrafficGenerator.Source(Packet.Direction.UL, 42, List.of(700),
                        520, 900, 427, durationUs, new double[]{0, 0, 1, 0}),
                new TrafficGenerator.Source(Packet.Direction.DL, 700, List.of(11, 42),
                        300, 1200, 317, durationUs, new double[]{0, 1, 0, 0}));
        List<Event> events = TrafficGenerator.generate(sources);

        Map<Integer, Integer> counts = new HashMap<>();
        long previousUs = 0;
        int packetId = 1;
        for (Event event : events) {
            Packet packet = event.packet();
            assertEquals(EventType.PACKET_GENERATED, event.type());
            assertEquals(0, event.sfuId());
            assertEquals(packetId++, packet.id());
            assertEquals(event.timeUs(), packet.generationTimeUs());
            assertTrue(event.timeUs() >= previousUs && event.timeUs() < durationUs);
            previousUs = event.timeUs();

            switch (packet.sourceNodeId()) {
                case 11:
                    assertUplinkPacket(packet, 500, AccessCategory.VO);
                    break;
                case 42:
                    assertUplinkPacket(packet, 900, AccessCategory.BE);
                    break;
                case 700:
                    assertEquals(Packet.Direction.DL, packet.direction());
                    assertTrue(List.of(11, 42).contains(packet.destinationNodeId()));
                    assertEquals(1200, packet.bytes());
                    assertEquals(AccessCategory.VI, packet.type());
                    break;
                default:
                    throw new AssertionError("Unexpected source ID: " + packet.sourceNodeId());
            }
            counts.merge(packet.sourceNodeId(), 1, Integer::sum);
        }

        assertEquals(180, counts.getOrDefault(11, 0) / 8.0, 20);
        assertEquals(520, counts.getOrDefault(42, 0) / 8.0, 40);
        assertEquals(300, counts.getOrDefault(700, 0) / 8.0, 30);
    }

    private static void assertUplinkPacket(Packet packet, int bytes, AccessCategory category) {
        assertEquals(Packet.Direction.UL, packet.direction());
        assertEquals(700, packet.destinationNodeId());
        assertEquals(bytes, packet.bytes());
        assertEquals(category, packet.type());
    }
}
