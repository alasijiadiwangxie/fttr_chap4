package edu.suda.fttr.simulator;

import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.link.FiberLink;
import edu.suda.fttr.link.FixedDelayModel;
import edu.suda.fttr.link.PonDownlink;
import edu.suda.fttr.link.PonUplink;
import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.mac.WifiRetryPolicy;
import edu.suda.fttr.phy.WirelessPhy;
import edu.suda.fttr.trace.TraceLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 建立拓扑，并将上下行事件交给对应的设备或链路。 */
public final class Simulator {
    private Simulator() {
    }

    public static void run(SimulationConfig config, TraceLogger trace) {
        List<Long> downlinkWindowsUs = config.downlinkTrafficWindowsUs();
        List<Long> uplinkWindowsUs = config.uplinkTrafficWindowsUs();
        boolean runSummary = config.uplinkEnabled() || !downlinkWindowsUs.isEmpty() || config.inputEvents().size() > 1;
        WirelessPhy wirelessPhy = config.wirelessPhy();
        WifiRetryPolicy retryPolicy = config.wifiRetryPolicy();
        SimulationConfig.PonLink downlinkConfig = config.ponDownlink();
        SimulationConfig.PonLink uplinkConfig = config.ponUplink();
        PonDownlink ponDownlink = downlinkConfig == null ? null
                : new PonDownlink(downlinkConfig.rateMbps(), downlinkConfig.propagationNs());
        PonUplink ponUplink = uplinkConfig == null ? null
                : new PonUplink(uplinkConfig.rateMbps(), uplinkConfig.propagationNs(), uplinkConfig.framePeriodNs(),
                        uplinkConfig.reportEnabled());
        SimulationContext context = new SimulationContext(trace, runSummary, config.usesNanosecondTicks(),
                retryPolicy != null);
        List<Map<Integer, Sta>> stationsBySfu = new ArrayList<>(config.sfuCount());
        for (int i = 0; i < config.sfuCount(); i++) {
            stationsBySfu.add(new HashMap<>());
        }
        List<Sta> stations = new ArrayList<>(config.staCount());
        for (int i = 0; i < config.staCount(); i++) {
            Sta sta = new Sta(i + 1, config.staSfuIds().get(i), config.edcaParameters());
            stations.add(sta);
            stationsBySfu.get(sta.sfuId() - 1).put(sta.id(), sta);
        }

        // 原有逻辑光链路继续服务选用固定时延模式的方向。
        FixedDelayModel fiberDelay = new FixedDelayModel(config.fiberDelayUs());
        // 链路时长模型可共用。每台 SFU 各自维护一个与其 STA 共享的信道。
        WifiLink wifi = wirelessPhy == null ? new WifiLink(new FixedDelayModel(config.wifiDelayUs()))
                : new WifiLink(wirelessPhy, config.wifiFrameExchange());
        List<FiberLink> fibers = new ArrayList<>(config.sfuCount());
        List<Sfu> sfus = new ArrayList<>(config.sfuCount());
        for (int i = 0; i < config.sfuCount(); i++) {
            fibers.add(new FiberLink(fiberDelay));
            int sfuId = i + 1;
            PonUplink.Port uplinkPort = ponUplink == null ? null : ponUplink.attachSfu(sfuId);
            sfus.add(new Sfu(sfuId, wifi, stationsBySfu.get(i), config.edcaParameters(), config.wifiAccessMode(),
                    retryPolicy, uplinkPort));
        }
        Mfu mfu = new Mfu(config.staSfuIds(), ponDownlink);

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
                    if (ponUplink != null && event.packet().isUplink()) {
                        ponUplink.start(event.packet(), event.sfuId(), context);
                    } else if (ponDownlink != null && !event.packet().isUplink()) {
                        ponDownlink.start(event.packet(), event.sfuId(), context);
                    } else {
                        fibers.get(event.sfuId() - 1).transmit(event.packet(), event.sfuId(), context);
                    }
                    break;
                case FIBER_TX_END:
                    if (event.packet().isUplink()) {
                        ponUplink.finish(event.packet(), event.sfuId(), context);
                    } else {
                        ponDownlink.finish(event.packet(), event.sfuId(), context);
                    }
                    break;
                case PON_UPLINK_FRAME:
                    ponUplink.scheduleFrame(context);
                    break;
                case PACKET_ARRIVAL_AT_SFU:
                    sfus.get(event.sfuId() - 1).enqueueForWifi(event.packet(), context);
                    break;
                case WIFI_TX_START:
                    sfus.get(event.sfuId() - 1).transmit(event.packet(), context);
                    break;
                case WIFI_TXOP_DATA_START:
                    sfus.get(event.sfuId() - 1).transmitWithinTxop(event.packet(), context);
                    break;
                case PACKET_ARRIVAL_AT_STA:
                case WIFI_ARRIVAL_AT_SFU:
                    sfus.get(event.sfuId() - 1).receiveData(event.packet(), context);
                    break;
                case WIFI_ACK_START:
                    sfus.get(event.sfuId() - 1).transmitAck(event.packet(), context);
                    break;
                case WIFI_ACK_END:
                    sfus.get(event.sfuId() - 1).completeTransmission(event.packet(), context);
                    break;
                case PACKET_ARRIVAL_AT_MFU:
                    mfu.receiveUplink(event.packet(), event.sfuId(), context);
                    break;
                case WIFI_SELECT_NEXT:
                    sfus.get(event.sfuId() - 1).selectNext(context);
                    break;
                case WIFI_ACCESS_TIMER:
                    sfus.get(event.sfuId() - 1).advanceAccess(event);
                    break;
                case WIFI_ACCESS_RESOLVE:
                    sfus.get(event.sfuId() - 1).resolveAccess(event, context);
                    break;
                case WIFI_COLLISION_DATA_END:
                    sfus.get(event.sfuId() - 1).finishCollidedData(event.packet(), context);
                    break;
                case WIFI_ACK_TIMEOUT:
                    sfus.get(event.sfuId() - 1).onAckTimeout(event.packet(), context);
                    break;
                default:
                    throw new IllegalStateException("Unhandled event: " + event.type());
            }
        }
        if (!downlinkWindowsUs.isEmpty()) {
            recordGeneratedTraffic(config, trace, downlinkWindowsUs, false);
        }
        if (!uplinkWindowsUs.isEmpty()) {
            recordGeneratedTraffic(config, trace, uplinkWindowsUs, true);
        }
        if (wirelessPhy != null) {
            trace.recordWirelessPhyRate(wirelessPhy.phyRateMbps());
        }
        if (ponDownlink != null) {
            ponDownlink.recordSummary(context);
        }
        if (ponUplink != null) {
            ponUplink.recordSummary(context);
        }
        context.recordSummary(sfus, stations, config.uplinkEnabled());
    }

    private static void recordGeneratedTraffic(SimulationConfig config, TraceLogger trace,
                                               List<Long> windowsUs, boolean uplink) {
        AccessCategory[] categories = AccessCategory.values();
        int[][] packetCounts = new int[windowsUs.size()][categories.length];
        long[][] byteCounts = new long[windowsUs.size()][categories.length];
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
        for (int source = 0; source < windowsUs.size(); source++) {
            long windowUs = windowsUs.get(source);
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
