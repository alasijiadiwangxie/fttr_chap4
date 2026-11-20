package edu.suda.fttr.config;

import edu.suda.fttr.dba.TcontTopology;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.WifiFrameExchange;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.WifiAccessMode;
import edu.suda.fttr.mac.WifiRetryPolicy;
import edu.suda.fttr.phy.WirelessPhy;
import edu.suda.fttr.simulator.Event;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/** 场景的已校验输入。入口时刻和原有固定链路时延使用整数微秒。 */
public final class SimulationConfig {
    private final List<Event> inputEvents;
    private final int sfuCount;
    private final List<Integer> staSfuIds;
    private final long fiberDelayUs;
    private final PonLink ponDownlink;
    private final PonLink ponUplink;
    private final long wifiDelayUs;
    private final WirelessPhy wirelessPhy;
    private final WifiFrameExchange wifiFrameExchange;
    private final EdcaParameters edcaParameters;
    private final WifiAccessMode wifiAccessMode;
    private final WifiRetryPolicy wifiRetryPolicy;
    private final List<Long> downlinkTrafficWindowsUs;
    private final List<Long> uplinkTrafficWindowsUs;
    private final boolean uplinkEnabled;
    private final boolean demandLedgerEnabled;
    private final boolean dbaShadowEnabled;
    private final boolean dbaExecutionEnabled;
    private final boolean dbaMultiGrantEnabled;
    private final boolean dbaGiantEnabled;
    private final TcontTopology tcontTopology;

    SimulationConfig(List<Event> inputEvents, int sfuCount, List<Integer> staSfuIds, long fiberDelayUs,
                     PonLink ponDownlink, PonLink ponUplink, long wifiDelayUs, WirelessPhy wirelessPhy,
                     WifiFrameExchange wifiFrameExchange, EdcaParameters edcaParameters, WifiAccessMode wifiAccessMode,
                     WifiRetryPolicy wifiRetryPolicy,
                     List<Long> downlinkTrafficWindowsUs, List<Long> uplinkTrafficWindowsUs, boolean uplinkEnabled,
                     boolean demandLedgerEnabled, boolean dbaShadowEnabled, boolean dbaExecutionEnabled,
                     boolean dbaMultiGrantEnabled, boolean dbaGiantEnabled, TcontTopology tcontTopology) {
        this.inputEvents = List.copyOf(inputEvents);
        this.sfuCount = sfuCount;
        this.staSfuIds = List.copyOf(staSfuIds);
        this.fiberDelayUs = fiberDelayUs;
        this.ponDownlink = ponDownlink;
        this.ponUplink = ponUplink;
        this.wifiDelayUs = wifiDelayUs;
        this.wirelessPhy = wirelessPhy;
        this.wifiFrameExchange = wifiFrameExchange;
        this.edcaParameters = edcaParameters;
        this.wifiAccessMode = wifiAccessMode;
        this.wifiRetryPolicy = wifiRetryPolicy;
        this.downlinkTrafficWindowsUs = List.copyOf(downlinkTrafficWindowsUs);
        this.uplinkTrafficWindowsUs = List.copyOf(uplinkTrafficWindowsUs);
        this.uplinkEnabled = uplinkEnabled;
        this.demandLedgerEnabled = demandLedgerEnabled;
        this.dbaShadowEnabled = dbaShadowEnabled;
        this.dbaExecutionEnabled = dbaExecutionEnabled;
        this.dbaMultiGrantEnabled = dbaMultiGrantEnabled;
        this.dbaGiantEnabled = dbaGiantEnabled;
        this.tcontTopology = tcontTopology;
    }

    /** 首个报文的长度，兼容原有单包调用。多包场景从各报文读取长度。 */
    public int packetBytes() {
        return inputEvents.get(0).packet().bytes();
    }

    /** 兼容单包调用，多包时返回最早的生成时刻。 */
    public long packetArrivalUs() {
        return inputEvents.get(0).timeUs();
    }

    public List<Long> packetArrivalsUs() {
        return inputEvents.stream().map(Event::timeUs).collect(Collectors.toUnmodifiableList());
    }

    public List<Packet> packets() {
        return inputEvents.stream().map(Event::packet).collect(Collectors.toUnmodifiableList());
    }

    /** 手动和泊松输入均按生成时刻排列，由仿真器注入对应源节点。 */
    public List<Event> inputEvents() {
        return inputEvents;
    }

    public int staCount() {
        return staSfuIds.size();
    }

    public int sfuCount() {
        return sfuCount;
    }

    /** 列表按 STA ID 排列，STA 1 的关联位于下标 0。 */
    public List<Integer> staSfuIds() {
        return staSfuIds;
    }

    /** 原有固定时延光链路使用的时延。 */
    public long fiberDelayUs() {
        return fiberDelayUs;
    }

    /** 启用共享下行时提供速率和传播时延，固定下行模式返回 null。 */
    public PonLink ponDownlink() {
        return ponDownlink;
    }

    /** 启用共享上行时提供速率和传播时延，固定上行模式返回 null。 */
    public PonLink ponUplink() {
        return ponUplink;
    }

    /** 共享光发送与 HE SU 都可能引入小数微秒，使用整数纳秒时钟。 */
    public boolean usesNanosecondTicks() {
        return wirelessPhy != null || ponDownlink != null || ponUplink != null;
    }

    public long wifiDelayUs() {
        if (wirelessPhy != null) {
            throw new IllegalStateException("Wi-Fi delay is computed per packet in he-su mode");
        }
        return wifiDelayUs;
    }

    /** HE SU 模式的静态速率模型。固定时延模式返回 null。 */
    public WirelessPhy wirelessPhy() {
        return wirelessPhy;
    }

    /** 启用立即 ACK 时的响应时序。旧场景只计算 DATA，返回 null。 */
    public WifiFrameExchange wifiFrameExchange() {
        return wifiFrameExchange;
    }

    public EdcaParameters edcaParameters() {
        return edcaParameters;
    }

    public boolean edcaCountdownEnabled() {
        return wifiAccessMode != WifiAccessMode.FIXED_PRIORITY;
    }

    public WifiAccessMode wifiAccessMode() {
        return wifiAccessMode;
    }

    public WifiRetryPolicy wifiRetryPolicy() {
        return wifiRetryPolicy;
    }

    /** 各 SFU 的下行泊松生成窗口，按 ID 排列。手动模式或未开启下行时为空。 */
    public List<Long> downlinkTrafficWindowsUs() {
        return downlinkTrafficWindowsUs;
    }

    /** 各 STA 的上行泊松生成窗口，按 ID 排列。手动模式或未开启上行时为空。 */
    public List<Long> uplinkTrafficWindowsUs() {
        return uplinkTrafficWindowsUs;
    }

    public boolean uplinkEnabled() {
        return uplinkEnabled;
    }

    public boolean demandLedgerEnabled() {
        return demandLedgerEnabled;
    }

    public boolean dbaShadowEnabled() {
        return dbaShadowEnabled;
    }

    public boolean dbaExecutionEnabled() {
        return dbaExecutionEnabled;
    }

    public boolean dbaMultiGrantEnabled() {
        return dbaMultiGrantEnabled;
    }

    public boolean dbaGiantEnabled() {
        return dbaGiantEnabled;
    }

    /** 显式的 Alloc-ID / SFU / 业务类型映射；默认关闭时仍保留旧单对象编号。 */
    public TcontTopology tcontTopology() {
        return tcontTopology;
    }

    /** 单方向的共享光发送器配置，传播时延与原有固定光链路时延分别设置。 */
    public static final class PonLink {
        private final BigDecimal rateMbps;
        private final long propagationNs;
        private final long framePeriodNs;
        private final boolean reportEnabled;
        private final long bwmapPropagationNs;
        private final PonPolling polling;

        PonLink(BigDecimal rateMbps, long propagationNs, long framePeriodNs, boolean reportEnabled,
                long bwmapPropagationNs, PonPolling polling) {
            this.rateMbps = rateMbps;
            this.propagationNs = propagationNs;
            this.framePeriodNs = framePeriodNs;
            this.reportEnabled = reportEnabled;
            this.bwmapPropagationNs = bwmapPropagationNs;
            this.polling = polling;
        }

        public BigDecimal rateMbps() {
            return rateMbps;
        }

        public long propagationNs() {
            return propagationNs;
        }

        /** 分帧上行使用正纳秒周期，即时共享链路取 0。 */
        public long framePeriodNs() {
            return framePeriodNs;
        }

        /** 只在分帧上行中启用逻辑队列快照。 */
        public boolean reportEnabled() {
            return reportEnabled;
        }

        /** 启用 BWmap 时使用统一下行传播时延。关闭时为 -1。 */
        public long bwmapPropagationNs() {
            return bwmapPropagationNs;
        }

        public PonPolling polling() {
            return polling;
        }
    }

    /** 有限的 REPORT-only 轮询。结束时刻约束授权创建，已创建控制时隙继续完整执行。 */
    public static final class PonPolling {
        private final int bytes;
        private final int intervalFrames;
        private final long endNs;

        PonPolling(int bytes, int intervalFrames, long endNs) {
            this.bytes = bytes;
            this.intervalFrames = intervalFrames;
            this.endNs = endNs;
        }

        public int bytes() {
            return bytes;
        }

        public int intervalFrames() {
            return intervalFrames;
        }

        public long endNs() {
            return endNs;
        }
    }
}
