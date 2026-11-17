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
import edu.suda.fttr.traffic.TrafficGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 建立拓扑，并将上下行事件交给对应的设备或链路。 */
public final class Simulator {
    private Simulator() {
    }

    public static void run(SimulationConfig config, TraceLogger trace) {
        List<TrafficGenerator.Source> downlinkSources = config.downlinkPoissonSources();
        List<TrafficGenerator.Source> uplinkSources = config.uplinkPoissonSources();
        boolean runSummary = config.uplinkEnabled() || !downlinkSources.isEmpty() || config.inputEvents().size() > 1;
        SimulationContext context = new SimulationContext(trace, runSummary);
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
        Mfu mfu = new Mfu(config.staSfuIds());

        // 输入事件只提供报文和生成时间，实际路径由仿真器和设备根据关联表决定。
        for (Event input : config.inputEvents()) {
            context.scheduleInputEvent(input);
        }

        Event event;
        while ((event = context.nextEvent()) != null) {
            switch (event.type()) {
                case PACKET_GENERATED:
                    Packet packet = event.packet();
                    if (packet.isUplink()) {
                        Sta source = stations.get(packet.staId() - 1);
                        sfus.get(source.sfuId() - 1).enqueueForWifi(packet, context);
                    } else {
                        mfu.receive(packet, context);
                    }
                    break;
                case FIBER_TX_START:
                    fibers.get(event.sfuId() - 1).transmit(event.packet(), event.sfuId(), context);
                    break;
                case PACKET_ARRIVAL_AT_SFU:
                    sfus.get(event.sfuId() - 1).enqueueForWifi(event.packet(), context);
                    break;
                case WIFI_TX_START:
                    sfus.get(event.sfuId() - 1).transmit(event.packet(), context);
                    break;
                case PACKET_ARRIVAL_AT_STA:
                case WIFI_ARRIVAL_AT_SFU:
                    // 上下行完成事件都释放该 SFU 的无线资源。上行还需要转发光侧一跳。
                    sfus.get(event.sfuId() - 1).completeTransmission(event.packet(), context);
                    break;
                case PACKET_ARRIVAL_AT_MFU:
                    mfu.receiveUplink(event.packet(), event.sfuId(), context);
                    break;
                case WIFI_SELECT_NEXT:
                    sfus.get(event.sfuId() - 1).selectNext(context);
                    break;
                default:
                    throw new IllegalStateException("Unhandled event: " + event.type());
            }
        }
        if (!downlinkSources.isEmpty()) {
            recordGeneratedTraffic(config, trace, downlinkSources, false);
        }
        if (!uplinkSources.isEmpty()) {
            recordGeneratedTraffic(config, trace, uplinkSources, true);
        }
        context.recordSummary(sfus, stations, config.uplinkEnabled());
    }

    private static void recordGeneratedTraffic(SimulationConfig config, TraceLogger trace,
                                               List<TrafficGenerator.Source> sources, boolean uplink) {
        AccessCategory[] categories = AccessCategory.values();
        int[][] packetCounts = new int[sources.size()][categories.length];
        long[][] byteCounts = new long[sources.size()][categories.length];
        // 下行以目的 SFU 的业务源分组，上行以源 STA 分组。负载分母沿用各自的生成窗口。
        for (Event input : config.inputEvents()) {
            Packet packet = input.packet();
            if (packet.isUplink() != uplink) {
                continue;
            }
            int staIndex = packet.staId() - 1;
            int source = uplink ? staIndex : config.staSfuIds().get(staIndex) - 1;
            int type = packet.type().ordinal();
            packetCounts[source][type]++;
            byteCounts[source][type] += packet.bytes();
        }
        for (int source = 0; source < sources.size(); source++) {
            long windowUs = sources.get(source).durationUs();
            int sfuId = uplink ? config.staSfuIds().get(source) : source + 1;
            int totalPackets = 0;
            long totalBytes = 0;
            for (AccessCategory type : categories) {
                int packets = packetCounts[source][type.ordinal()];
                long bytes = byteCounts[source][type.ordinal()];
                recordTrafficSummary(trace, uplink, source + 1, sfuId, type, packets, bytes, windowUs);
                totalPackets += packets;
                totalBytes += bytes;
            }
            recordTrafficSummary(trace, uplink, source + 1, sfuId, null, totalPackets, totalBytes, windowUs);
        }
    }

    private static void recordTrafficSummary(TraceLogger trace, boolean uplink, int sourceId, int sfuId,
                                             AccessCategory type, int packets, long bytes, long windowUs) {
        if (uplink) {
            trace.recordUplinkTrafficSummary(sourceId, sfuId, type, packets, bytes, windowUs);
        } else {
            trace.recordTrafficSummary(sfuId, type, packets, bytes, windowUs);
        }
    }
}
