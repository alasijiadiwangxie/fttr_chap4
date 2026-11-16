package edu.suda.fttr.simulator;

import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.link.FiberLink;
import edu.suda.fttr.link.FixedDelayModel;
import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.trace.TraceLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 建立下行拓扑，并将各次事件交给它所属的节点。 */
public final class Simulator {
    private Simulator() {
    }

    public static void run(SimulationConfig config, TraceLogger trace) {
        boolean poisson = !config.poissonSources().isEmpty();
        SimulationContext context = new SimulationContext(trace, poisson || config.packets().size() > 1);
        List<Map<Integer, Sta>> stationsBySfu = new ArrayList<>(config.sfuCount());
        for (int i = 0; i < config.sfuCount(); i++) {
            stationsBySfu.add(new HashMap<>());
        }
        List<Sta> stations = new ArrayList<>(config.staCount());
        for (int i = 0; i < config.staCount(); i++) {
            Sta sta = new Sta(i + 1, config.staSfuIds().get(i));
            stations.add(sta);
            stationsBySfu.get(sta.sfuId() - 1).put(sta.id(), sta);
        }

        // 每台 SFU 都有各自的收包队列和发送状态，光链路也按目的 SFU 分开实例化。
        FixedDelayModel fiberDelay = new FixedDelayModel(config.fiberDelayUs());
        FixedDelayModel wifiDelay = new FixedDelayModel(config.wifiDelayUs());
        List<FiberLink> fibers = new ArrayList<>(config.sfuCount());
        List<Sfu> sfus = new ArrayList<>(config.sfuCount());
        for (int i = 0; i < config.sfuCount(); i++) {
            fibers.add(new FiberLink(fiberDelay));
            sfus.add(new Sfu(i + 1, new WifiLink(wifiDelay), stationsBySfu.get(i)));
        }
        Mfu mfu = new Mfu(config.staSfuIds(), fibers);

        // 外部业务只安排 MFU 到达。后续的事件由节点和链路逐跳生成。
        for (Packet packet : config.packets()) {
            context.scheduleAfter(packet.mfuArrivalUs(), EventType.PACKET_ARRIVAL_AT_MFU, packet);
        }

        Event event;
        while ((event = context.nextEvent()) != null) {
            switch (event.type()) {
                case PACKET_ARRIVAL_AT_MFU:
                    mfu.receive(event.packet(), context);
                    break;
                case FIBER_TX_START:
                    mfu.transmit(event.packet(), event.sfuId(), context);
                    break;
                case PACKET_ARRIVAL_AT_SFU:
                    sfus.get(event.sfuId() - 1).receive(event.packet(), context);
                    break;
                case WIFI_TX_START:
                    sfus.get(event.sfuId() - 1).transmit(event.packet(), context);
                    break;
                case PACKET_ARRIVAL_AT_STA:
                    // 完成事件只释放本次发送所占用的 SFU 无线资源。
                    sfus.get(event.sfuId() - 1).completeTransmission(event.packet(), context);
                    break;
                case WIFI_SELECT_NEXT:
                    sfus.get(event.sfuId() - 1).selectNext(context);
                    break;
                default:
                    throw new IllegalStateException("Unhandled event: " + event.type());
            }
        }
        if (poisson) {
            recordGeneratedTraffic(config, trace);
        }
        context.recordSummary(sfus, stations);
    }

    private static void recordGeneratedTraffic(SimulationConfig config, TraceLogger trace) {
        AccessCategory[] categories = AccessCategory.values();
        int[][] packetCounts = new int[config.sfuCount()][categories.length];
        long[][] byteCounts = new long[config.sfuCount()][categories.length];
        // 按实际进入 MFU 的报文统计供给负载。分母是生成窗口，与链路排队或清空时刻无关。
        for (Packet packet : config.packets()) {
            int sfu = config.staSfuIds().get(packet.destinationStaId() - 1) - 1;
            int type = packet.type().ordinal();
            packetCounts[sfu][type]++;
            byteCounts[sfu][type] += packet.bytes();
        }
        for (int sfu = 0; sfu < config.sfuCount(); sfu++) {
            long windowUs = config.poissonSources().get(sfu).durationUs();
            int totalPackets = 0;
            long totalBytes = 0;
            for (AccessCategory type : categories) {
                int packets = packetCounts[sfu][type.ordinal()];
                long bytes = byteCounts[sfu][type.ordinal()];
                trace.recordTrafficSummary(sfu + 1, type, packets, bytes, windowUs);
                totalPackets += packets;
                totalBytes += bytes;
            }
            trace.recordTrafficSummary(sfu + 1, null, totalPackets, totalBytes, windowUs);
        }
    }
}
