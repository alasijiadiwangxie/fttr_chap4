package edu.suda.fttr.simulator;

import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.entity.Mfu;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.entity.PonDbaShadowPlanner;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.link.FiberLink;
import edu.suda.fttr.link.FixedDelayModel;
import edu.suda.fttr.link.PonDownlink;
import edu.suda.fttr.link.PonUplink;
import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.mac.WifiRetryPolicy;
import edu.suda.fttr.phy.ApCapabilityMode;
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
                        uplinkConfig.reportEnabled(), uplinkConfig.bwmapPropagationNs(), uplinkConfig.polling());
        boolean demandLedgerEnabled = config.demandLedgerEnabled();
        PonDbaShadowPlanner shadowPlanner = config.dbaShadowEnabled()
                ? new PonDbaShadowPlanner(config.staSfuIds(), uplinkConfig.rateMbps(), uplinkConfig.framePeriodNs()) : null;
        Mfu mfu = new Mfu(config.staSfuIds(), ponDownlink, demandLedgerEnabled, shadowPlanner);
        if (config.tcontTopology().enabled()) {
            mfu.configureTcont(config.tcontTopology());
            ponUplink.configureTcont(config.tcontTopology());
        }
        if (config.dbaGiantEnabled()) {
            mfu.enableGiantDba(config.dbaGiantCompleteEnabled());
        }
        if (config.dbaExecutionEnabled()) {
            ponUplink.enableDbaExecution(mfu, config.dbaMultiGrantEnabled());
        }
        SimulationContext context = new SimulationContext(trace, runSummary, config.usesNanosecondTicks(),
                retryPolicy != null, demandLedgerEnabled ? mfu : null);
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
        // 旧场景共用全局链路时长。启用静态能力的 SFU 各自持有关联 STA 的实际 PHY。
        WifiLink legacyWifi = wirelessPhy == null ? new WifiLink(new FixedDelayModel(config.wifiDelayUs()))
                : new WifiLink(wirelessPhy, config.wifiFrameExchange());
        List<FiberLink> fibers = new ArrayList<>(config.sfuCount());
        List<Sfu> sfus = new ArrayList<>(config.sfuCount());
        for (int i = 0; i < config.sfuCount(); i++) {
            fibers.add(new FiberLink(fiberDelay));
            int sfuId = i + 1;
            ApCapabilityMode mode = config.apCapabilityMode(sfuId);
            Map<Integer, Sta> associated = stationsBySfu.get(i);
            WifiLink wifi = legacyWifi;
            if (mode != null) {
                Map<Integer, WirelessPhy> stationPhys = new HashMap<>();
                for (int staId : associated.keySet()) {
                    stationPhys.put(staId, config.wirelessPhyForSta(staId));
                }
                wifi = new WifiLink(wirelessPhy, config.wifiFrameExchange(), stationPhys);
            }
            PonUplink.Port uplinkPort = ponUplink == null ? null : ponUplink.attachSfu(sfuId);
            Sfu sfu = new Sfu(sfuId, wifi, associated, config.edcaParameters(), config.wifiAccessMode(),
                    retryPolicy, uplinkPort);
            sfus.add(sfu);
            if (config.apActivityEnabled()) {
                context.trackApActivity(sfuId, mode, config.apActivityWindowNs());
            }
            ApModeSwitch.Settings switching = config.apSwitching();
            if (switching != null && !switching.requestsFor(sfuId).isEmpty()) {
                Map<Integer, WirelessPhy> alternatePhys = new HashMap<>();
                for (int staId : associated.keySet()) {
                    alternatePhys.put(staId, config.alternateWirelessPhyForSta(staId));
                }
                WifiLink alternate = new WifiLink(wirelessPhy, config.wifiFrameExchange(), alternatePhys);
                sfu.configureModeSwitch(new ApModeSwitch(sfuId, switching, mode, wifi, alternate));
                for (ApModeSwitch.Request request : switching.requestsFor(sfuId)) {
                    context.scheduleAfterNanoseconds(request.timeNs(), EventType.WIFI_AP_SWITCH_REQUEST, null, sfuId);
                }
            }
        }
        // 输入事件只提供报文和生成时间，实际路径由仿真器和设备根据关联表决定。
        for (Event input : config.inputEvents()) {
            context.scheduleInputEvent(input);
        }
        if (ponUplink != null) {
            ponUplink.startPolling(config.staSfuIds(), context);
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
                    if (config.tcontTopology().enabled()) {
                        for (edu.suda.fttr.dba.TcontProfile profile : config.tcontTopology().profiles()) {
                            mfu.recordAllocationDemand(profile.allocationId(), "FRAME_BOUNDARY", context);
                        }
                    } else if (uplinkConfig.reportEnabled()) {
                        long frameId = context.nowTicks() / uplinkConfig.framePeriodNs();
                        for (Sfu sfu : sfus) {
                            context.recordMfuReportState(frameId, sfu.id(), mfu.latestUplinkReport(sfu.id()));
                        }
                    }
                    if (demandLedgerEnabled && !config.tcontTopology().enabled()) {
                        for (Sfu sfu : sfus) {
                            mfu.recordUplinkDemand(sfu.id(), "FRAME_BOUNDARY", context);
                        }
                    }
                    ponUplink.scheduleFrame(context);
                    break;
                case PON_BWMAP_RX:
                    sfus.get(event.sfuId() - 1).receivePonBwmap(event.packet(), context);
                    break;
                case PON_POLL_BWMAP_RX:
                    sfus.get(event.sfuId() - 1).receivePollBwmap(event.reportPoll(), context);
                    break;
                case PON_POLL_START:
                    ponUplink.startPoll(event.reportPoll(), context);
                    break;
                case PON_POLL_END:
                    ponUplink.finishPoll(event.reportPoll(), context);
                    break;
                case PON_POLL_REPORT_RX:
                    mfu.receivePolledReport(event.reportPoll(), context);
                    break;
                case PON_DBA_BWMAP_RX:
                    ponUplink.receiveDbaBwmap(event.dbaGrant(), context);
                    break;
                case PON_DBA_SLOT_START:
                    ponUplink.startDbaGrant(event.dbaGrant(), context);
                    break;
                case PON_DBA_SLOT_END:
                    ponUplink.finishDbaGrant(event.dbaGrant(), context);
                    break;
                case PON_DBA_SETTLEMENT_RX:
                    mfu.receiveDbaSettlement(event.dbaGrant(), context);
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
                case PON_UPLINK_REPORT_RX:
                    mfu.receiveUplinkReport(event.uplinkReport(), event.packet(), context);
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
                case WIFI_AP_SWITCH_REQUEST:
                    sfus.get(event.sfuId() - 1).receiveModeSwitchRequest(context);
                    break;
                case WIFI_AP_SWITCH_END:
                    sfus.get(event.sfuId() - 1).finishModeSwitch(context);
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
            recordWirelessProfiles(config, trace, stations);
        }
        if (ponDownlink != null) {
            ponDownlink.recordSummary(context);
        }
        if (ponUplink != null) {
            ponUplink.recordSummary(context);
            ponUplink.recordReportSummary(mfu.receivedUplinkReports(), context);
        }
        context.recordSummary(sfus, stations, config.uplinkEnabled(), config.apEnergyModel(), config.apSwitching());
    }

    private static void recordWirelessProfiles(SimulationConfig config, TraceLogger trace, List<Sta> stations) {
        boolean usesLegacyPhy = false;
        for (int sfuId = 1; sfuId <= config.sfuCount(); sfuId++) {
            ApCapabilityMode mode = config.apCapabilityMode(sfuId);
            if (mode == null) {
                usesLegacyPhy = true;
            } else {
                trace.recordApCapability(sfuId, mode);
            }
        }
        for (Sta sta : stations) {
            ApCapabilityMode mode = config.apCapabilityMode(sta.sfuId());
            if (mode != null) {
                trace.recordStationPhy(sta, mode, config.wirelessPhyForSta(sta.id()));
            }
        }
        if (usesLegacyPhy) {
            trace.recordWirelessPhyRate(config.wirelessPhy().phyRateMbps());
        }
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
