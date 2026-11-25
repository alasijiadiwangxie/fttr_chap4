package edu.suda.fttr.config;

import edu.suda.fttr.channel.ChannelReport;
import edu.suda.fttr.channel.ChannelTruth;
import edu.suda.fttr.channel.ExternalInterference;
import edu.suda.fttr.dba.GiantSlaAdmission;
import edu.suda.fttr.dba.TcontProfile;
import edu.suda.fttr.dba.TcontTopology;
import edu.suda.fttr.energy.ApAnalysisSettings;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.ApEnergyModel;
import edu.suda.fttr.entity.ApModeController;
import edu.suda.fttr.entity.ApModeSwitch;
import edu.suda.fttr.entity.ApTrafficObserver;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.AmpduConfig;
import edu.suda.fttr.link.PonLinkTiming;
import edu.suda.fttr.link.WifiFrameExchange;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.EdcaParameters.AcParameters;
import edu.suda.fttr.mac.WifiAccessMode;
import edu.suda.fttr.mac.WifiRetryPolicy;
import edu.suda.fttr.phy.ApCapabilityMode;
import edu.suda.fttr.phy.DownlinkDecoder;
import edu.suda.fttr.phy.SinrPerModel;
import edu.suda.fttr.phy.WirelessPhy;
import edu.suda.fttr.simulator.Event;
import edu.suda.fttr.traffic.TrafficGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取并校验完整的场景配置，再开始处理仿真事件。 */
public final class ConfigLoader {
    private static final String DEFAULT_SCENARIO = "/phase1-minimal-downlink.properties";
    private static final String FIBER_DELAY_US = "fiber.delay.us";
    private static final String FIBER_DOWNLINK = "fiber.downlink.";
    private static final String FIBER_UPLINK = "fiber.uplink.";
    private static final String DOWNLINK_PROPAGATION_US = FIBER_DOWNLINK + "propagation.us";
    private static final String UPLINK_FRAME_PERIOD_US = FIBER_UPLINK + "frame.period.us";
    private static final String UPLINK_REPORT_ENABLED = FIBER_UPLINK + "report.enabled";
    private static final String UPLINK_BWMAP_ENABLED = FIBER_UPLINK + "bwmap.enabled";
    private static final String UPLINK_DEMAND_LEDGER_ENABLED = FIBER_UPLINK + "demand.ledger.enabled";
    private static final String UPLINK_POLL_ENABLED = FIBER_UPLINK + "poll.enabled";
    private static final String UPLINK_POLL_BYTES = FIBER_UPLINK + "poll.bytes";
    private static final String UPLINK_POLL_INTERVAL_FRAMES = FIBER_UPLINK + "poll.interval.frames";
    private static final String UPLINK_POLL_END_US = FIBER_UPLINK + "poll.end.us";
    private static final String UPLINK_DBA_MODE = FIBER_UPLINK + "dba.mode";
    private static final String TCONT_PREFIX = FIBER_UPLINK + "tcont.";
    private static final String TCONT_ENABLED = TCONT_PREFIX + "enabled";
    private static final String TCONT_ALLOC_IDS = TCONT_PREFIX + "alloc.ids";
    private static final String MAC_EXCHANGE = "wifi.mac.exchange";
    private static final String MAC_ACCESS = "wifi.mac.access";
    private static final String MAC_SIFS_US = "wifi.mac.sifs.us";
    private static final String MAC_SLOT_US = "wifi.mac.slot.us";
    private static final String MAC_ACK_RATE_MBPS = "wifi.mac.ack.rate.mbps";
    private static final String MAC_ACK_TIMEOUT_US = "wifi.mac.ack.timeout.us";
    private static final String MAC_MAX_RETRIES = "wifi.mac.retry.max";
    private static final String AMPDU_ENABLED = "wifi.mac.ampdu.enabled";
    private static final String AMPDU_MAX_MPDUS = "wifi.mac.ampdu.max-mpdus";
    private static final String AMPDU_MAX_BYTES = "wifi.mac.ampdu.max-bytes";
    private static final String AMPDU_FIRST_ATTEMPT_ERRORS = "wifi.mac.ampdu.first-attempt-error.packet.ids";
    private static final String EDCA_SEED = "wifi.mac.edca.seed";
    private static final String AP_ACTIVITY_ENABLED = "wifi.ap.activity.enabled";
    private static final String AP_ACTIVITY_WINDOW_US = "wifi.ap.activity.window.us";
    private static final String AP_OBSERVATION_ENABLED = "wifi.ap.observation.enabled";
    private static final String AP_OBSERVATION_PERIOD_US = "wifi.ap.observation.period.us";
    private static final String AP_OBSERVATION_EWMA_ALPHA = "wifi.ap.observation.ewma.alpha";
    private static final String AP_PREDICTION_ENABLED = "wifi.ap.prediction.enabled";
    private static final String AP_PREDICTION_MODEL = "wifi.ap.prediction.model";
    private static final String AP_PREDICTION_WINDOW_US = "wifi.ap.prediction.window.us";
    private static final String AP_PREDICTION_ALPHA = "wifi.ap.prediction.alpha";
    private static final String AP_QOS_ENABLED = "wifi.ap.qos.enabled";
    private static final Pattern AP_QOS_AC_KEY = Pattern.compile("wifi\\.ap\\.qos\\.(VO|VI|BE|BK)\\.(deadline\\.us|min-on-time)");
    private static final String CHANNEL_OBSERVATION_ENABLED = "wifi.channel.observation.enabled";
    private static final String CHANNEL_TRUTH = "wifi.channel.truth";
    private static final String CHANNEL_FEEDBACK = "wifi.channel.feedback";
    private static final String CHANNEL_READ_TIMES = "wifi.channel.observe.us";
    private static final String CHANNEL_PASSIVE = "wifi.channel.passive.enabled";
    private static final String CHANNEL_FEEDBACK_AIRTIME = "wifi.channel.feedback.airtime.us";
    private static final String CHANNEL_SCAN_SCHEDULE = "wifi.channel.scan.schedule";
    private static final String CHANNEL_SCAN_POWER = "wifi.channel.scan.power.w";
    private static final String PHY_PER_ENABLED = "wifi.phy.per.enabled";
    private static final String PHY_PER_SEED = "wifi.phy.per.seed";
    private static final String PHY_PER_REFERENCE_BYTES = "wifi.phy.per.reference.mpdu.bytes";
    private static final String PHY_PER_CURVES = "wifi.phy.per.curves";
    private static final String PHY_PER_SOURCE = "wifi.phy.per.source";
    private static final String PHY_PER_FREQUENCY_MODEL = "wifi.phy.per.frequency.model";
    private static final String PHY_PER_SUBCHANNEL_WEIGHTS = "wifi.phy.per.subchannel.weights";
    private static final String INTERFERENCE_ENABLED = "wifi.channel.interference.enabled";
    private static final String INTERFERENCE_TIMELINE = "wifi.channel.interference.timeline";
    private static final String EXTERNAL_CCA_THRESHOLD = "wifi.channel.interference.cca.threshold.dbm";
    private static final String AP_ENERGY = "wifi.ap.energy.";
    private static final String AP_ENERGY_ENABLED = AP_ENERGY + "enabled";
    private static final String LCM_POWER = AP_ENERGY + "lcm.";
    private static final String HCM_POWER = AP_ENERGY + "hcm.";
    private static final String AP_SWITCH = "wifi.ap.switch.";
    private static final String AP_SWITCH_ENABLED = AP_SWITCH + "enabled";
    private static final String LCM_TO_HCM = AP_SWITCH + "lcm-to-hcm.";
    private static final String HCM_TO_LCM = AP_SWITCH + "hcm-to-lcm.";
    private static final String AP_CONTROL_POLICY = "wifi.ap.control.policy";
    private static final String AP_CONTROL_PERIOD_US = "wifi.ap.control.period.us";
    private static final String AP_CONTROL_ALPHA = "wifi.ap.control.ewma.alpha";
    private static final String AP_CONTROL_THRESHOLD = "wifi.ap.control.threshold.mbps";
    private static final String AP_CONTROL_MARGIN = "wifi.ap.control.energy.margin.uj";
    private static final String AP_CONTROL_SERVICE_MODEL = "wifi.ap.control.service.model";
    private static final String AP_CONTROL_QOS_MARGIN_US = "wifi.ap.control.qos.margin.us";
    private static final String AP_CONTROL_DEFER_LIMIT_US = "wifi.ap.control.defer.max.us";
    private static final String AP_CONTROL_MIN_RESIDENCY_US = "wifi.ap.control.residency.minimum.us";
    private static final String AP_CONTROL_REVERSE_MARGIN = "wifi.ap.control.residency.reverse.margin.uj";
    private static final Set<String> KEYS = Set.of(
            FIBER_DELAY_US, FIBER_DOWNLINK + "mode", FIBER_DOWNLINK + "rate.mbps", DOWNLINK_PROPAGATION_US,
            FIBER_UPLINK + "mode", FIBER_UPLINK + "rate.mbps", FIBER_UPLINK + "propagation.us", UPLINK_FRAME_PERIOD_US,
            UPLINK_REPORT_ENABLED, UPLINK_BWMAP_ENABLED, UPLINK_DEMAND_LEDGER_ENABLED,
            UPLINK_POLL_ENABLED, UPLINK_POLL_BYTES, UPLINK_POLL_INTERVAL_FRAMES, UPLINK_POLL_END_US,
            UPLINK_DBA_MODE, TCONT_ENABLED, TCONT_ALLOC_IDS,
            "wifi.delay.us", "wifi.model", "sta.count", "sfu.count", "traffic.mode", "traffic.direction",
            MAC_EXCHANGE, MAC_ACCESS, MAC_SIFS_US, MAC_SLOT_US, MAC_ACK_RATE_MBPS, MAC_ACK_TIMEOUT_US,
            MAC_MAX_RETRIES, AMPDU_ENABLED, AMPDU_MAX_MPDUS, AMPDU_MAX_BYTES, AMPDU_FIRST_ATTEMPT_ERRORS,
            EDCA_SEED, AP_ACTIVITY_ENABLED, AP_ACTIVITY_WINDOW_US, AP_ENERGY_ENABLED,
            AP_OBSERVATION_ENABLED, AP_OBSERVATION_PERIOD_US, AP_OBSERVATION_EWMA_ALPHA,
            AP_PREDICTION_ENABLED, AP_PREDICTION_MODEL, AP_PREDICTION_WINDOW_US, AP_PREDICTION_ALPHA, AP_QOS_ENABLED,
            CHANNEL_OBSERVATION_ENABLED, CHANNEL_TRUTH, CHANNEL_FEEDBACK, CHANNEL_READ_TIMES,
            CHANNEL_PASSIVE, CHANNEL_FEEDBACK_AIRTIME, CHANNEL_SCAN_SCHEDULE, CHANNEL_SCAN_POWER,
            PHY_PER_ENABLED, PHY_PER_SEED, PHY_PER_REFERENCE_BYTES, PHY_PER_CURVES, PHY_PER_SOURCE,
            PHY_PER_FREQUENCY_MODEL, PHY_PER_SUBCHANNEL_WEIGHTS,
            INTERFERENCE_ENABLED, INTERFERENCE_TIMELINE, EXTERNAL_CCA_THRESHOLD,
            LCM_POWER + "idle.w", LCM_POWER + "tx.w", LCM_POWER + "rx.w",
            HCM_POWER + "idle.w", HCM_POWER + "tx.w", HCM_POWER + "rx.w",
            AP_CONTROL_POLICY, AP_CONTROL_PERIOD_US, AP_CONTROL_ALPHA, AP_CONTROL_THRESHOLD, AP_CONTROL_MARGIN,
            AP_CONTROL_SERVICE_MODEL, AP_CONTROL_QOS_MARGIN_US, AP_CONTROL_DEFER_LIMIT_US,
            AP_CONTROL_MIN_RESIDENCY_US, AP_CONTROL_REVERSE_MARGIN,
            AP_SWITCH_ENABLED, LCM_TO_HCM + "delay.us", LCM_TO_HCM + "power.w",
            HCM_TO_LCM + "delay.us", HCM_TO_LCM + "power.w");
    private static final Set<String> PHY_KEYS = Set.of(
            "wifi.phy.bandwidth.mhz", "wifi.phy.mcs", "wifi.phy.nss", "wifi.phy.gi.us",
            "wifi.phy.mac.overhead.bytes", "wifi.phy.he-ltf.type", "wifi.phy.nominal.packet.padding.us");
    private static final Set<String> MANUAL_KEYS = Set.of("packet.bytes", "packet.arrival.us");
    private static final Pattern PACKET_OVERRIDE = Pattern.compile("packet\\.([1-9][0-9]*)\\.(bytes|type|sta\\.id|direction)");
    private static final Pattern STA_KEY = Pattern.compile(
            "sta\\.([1-9][0-9]*)\\.(?:sfu\\.id|wifi\\.max\\.(?:bandwidth\\.mhz|nss))");
    private static final Pattern SFU_AP_MODE_KEY =
            Pattern.compile("sfu\\.([1-9][0-9]*)\\.wifi\\.ap\\.(?:mode|control\\.policy)");
    private static final Pattern SFU_AP_SWITCH_KEY =
            Pattern.compile("sfu\\.([1-9][0-9]*)\\.wifi\\.ap\\.switch\\.requests");
    private static final Pattern EDCA_AC_KEY = Pattern.compile(
            "wifi\\.mac\\.edca\\.(VO|VI|BE|BK)\\.(aifsn|cwmin|cwmax|txop\\.limit\\.us)");
    private static final Pattern POISSON_SOURCE = Pattern.compile(
            "traffic\\.(sfu|sta)\\.([1-9][0-9]*)\\.(rate\\.pps|packet\\.bytes|seed|class\\.weights|duration\\.us|"
                    + "class\\.(VO|VI|BE|BK)\\.(rate\\.pps|rate\\.mbps|packet\\.bytes))");
    private static final Pattern TCONT_ALLOCATION_KEY = Pattern.compile(
            "fiber\\.uplink\\.tcont\\.alloc\\.([1-9][0-9]*)\\.(sfu\\.id|type|classes|si\\.max\\.frames|"
                    + "si\\.min\\.frames|ab\\.min\\.bytes|ab\\.sur\\.bytes|"
                    + "si\\.fixed\\.frames|ab\\.fixed\\.bytes|si\\.be\\.frames|ab\\.be\\.bytes)");
    private static final AccessCategory[] CATEGORIES = AccessCategory.values();

    private static final class PoissonTraffic {
        private final List<TrafficGenerator.Source> streams = new ArrayList<>();
        // 统计按设备分组。每台设备即便有多条流，也只有一个生成窗口。
        private final List<Long> windowsUs = new ArrayList<>();
    }

    private enum DirectionMode {
        DL, UL, BOTH;

        boolean allows(Packet.Direction direction) {
            return this == BOTH || (direction == Packet.Direction.DL ? this == DL : this == UL);
        }
    }

    private ConfigLoader() {
    }

    public static SimulationConfig loadDefault() throws IOException {
        InputStream input = ConfigLoader.class.getResourceAsStream(DEFAULT_SCENARIO);
        if (input == null) {
            throw new IOException("Missing bundled scenario " + DEFAULT_SCENARIO);
        }
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return read(reader);
        }
    }

    public static SimulationConfig load(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return read(reader);
        }
    }

    private static SimulationConfig read(Reader reader) throws IOException {
        Properties properties = new Properties();
        properties.load(reader);
        DirectionMode directionMode = readDirectionMode(properties);

        String mode = properties.getProperty("traffic.mode", "manual").strip();
        if (!mode.equals("manual") && !mode.equals("poisson")) {
            throw new IllegalArgumentException("traffic.mode must be manual or poisson");
        }
        boolean poisson = mode.equals("poisson");
        List<Long> arrivalsUs = poisson ? List.of() : readArrivals(properties);
        int staCount = properties.containsKey("sta.count") ? readPositiveInt(properties, "sta.count") : 1;
        int sfuCount = properties.containsKey("sfu.count") ? readPositiveInt(properties, "sfu.count") : 1;
        validateKeys(properties, arrivalsUs.size(), staCount, sfuCount, poisson, directionMode);
        List<Integer> staSfuIds = readStaAssociations(properties, staCount, sfuCount);
        long bwmapPropagationNs = readBwmapPropagationNs(properties);
        SimulationConfig.PonLink ponDownlink = readPonLink(properties, FIBER_DOWNLINK, "10000", bwmapPropagationNs);
        SimulationConfig.PonLink ponUplink = readPonLink(properties, FIBER_UPLINK, "2500", bwmapPropagationNs);
        boolean demandLedgerEnabled = readDemandLedgerEnabled(properties, ponUplink);
        String dbaMode = readDbaMode(properties, ponUplink, demandLedgerEnabled);
        boolean dbaShadowEnabled = dbaMode.equals("shadow-rr");
        boolean dbaGiantCompleteEnabled = dbaMode.equals("giant-complete");
        boolean dbaGiantEnabled = dbaMode.equals("giant") || dbaGiantCompleteEnabled;
        boolean dbaMultiGrantEnabled = dbaMode.equals("rr-multi") || dbaGiantEnabled;
        boolean dbaExecutionEnabled = dbaMode.equals("rr") || dbaMultiGrantEnabled;
        TcontTopology tcontTopology = readTcontTopology(properties, sfuCount, dbaExecutionEnabled);
        if (dbaGiantEnabled && !tcontTopology.enabled()) {
            throw new IllegalArgumentException(dbaMode + " requires " + TCONT_ENABLED + "=true");
        }
        if (dbaGiantEnabled && !dbaGiantCompleteEnabled
                && tcontTopology.profiles().stream().anyMatch(profile -> profile.type() == 1 || profile.type() == 5)) {
            throw new IllegalArgumentException("giant is the Type 2/3/4 baseline; use giant-complete for Type 1 or 5");
        }
        if (dbaGiantCompleteEnabled) {
            GiantSlaAdmission.validate(tcontTopology.profiles(), ponUplink.framePeriodNs(), ponUplink.rateMbps(),
                    ponUplink.polling().bytes());
        }
        if (dbaExecutionEnabled && sfuCount > 2 && !tcontTopology.enabled()) {
            throw new IllegalArgumentException("Real DBA execution currently supports one or two SFUs");
        }
        boolean hasFixedDelay = properties.containsKey(FIBER_DELAY_US);
        long fiberUs = hasFixedDelay || (ponDownlink == null && ponUplink == null)
                ? readNonNegativeLong(properties, FIBER_DELAY_US) : 0;
        WirelessPhy wirelessPhy = readWirelessPhy(properties);
        ApModeController.Settings apControl = readApControl(properties, sfuCount);
        Map<Integer, ApCapabilityMode> apCapabilityModes = readApCapabilityModes(properties, sfuCount, wirelessPhy,
                apControl);
        boolean apActivityEnabled = readApActivityEnabled(properties);
        long apActivityWindowNs = properties.containsKey(AP_ACTIVITY_WINDOW_US)
                ? readDurationNs(properties, AP_ACTIVITY_WINDOW_US, "0", true) : -1;
        // 控制器复用 C1 的下行入口账本，决策周期独立于 C1 的统计采样周期。
        long apObservationPeriodNs = readApObservationPeriod(properties, apControl);
        double apObservationAlpha = readApObservationAlpha(properties);
        ChannelTruth channelTruth = readChannelTruth(properties, staSfuIds);
        ChannelObservationInput channelObservation = readChannelObservation(properties, sfuCount, staSfuIds, channelTruth);
        ApEnergyModel apEnergyModel = readApEnergyModel(properties, apActivityEnabled);
        ApModeSwitch.Settings apSwitching = readApSwitching(properties, sfuCount, apCapabilityModes, apEnergyModel);
        validateApControl(apControl, apSwitching);
        ApAnalysisSettings apAnalysis = readApAnalysisSettings(properties, apObservationPeriodNs,
                apEnergyModel, apCapabilityModes.size() == sfuCount, apControl.needsPrediction());
        Map<Integer, WirelessPhy> alternateStationPhys = new HashMap<>();
        Map<Integer, WirelessPhy> stationPhys = readStationPhys(properties, staSfuIds, apCapabilityModes,
                apSwitching, apControl, apAnalysis != null && apAnalysis.predictionEnabled(), alternateStationPhys);
        EdcaParameters edcaParameters = readEdcaParameters(properties);
        WifiAccessMode accessMode = readWifiAccessMode(properties);
        WifiFrameExchange frameExchange = readWifiFrameExchange(properties, wirelessPhy, edcaParameters.sifsUs());
        if (apControl.needsPrediction() && (frameExchange == null || accessMode != WifiAccessMode.EDCA_MULTI_AC)) {
            throw new IllegalArgumentException("Predictive AP control requires edca-multi-ac and data-sifs-ack");
        }
        if (apAnalysis != null && apAnalysis.predictionEnabled() && frameExchange == null) {
            throw new IllegalArgumentException(AP_PREDICTION_ENABLED + " requires data-sifs-ack");
        }
        WifiRetryPolicy retryPolicy = readWifiRetryPolicy(properties, accessMode, frameExchange, edcaParameters);
        AmpduConfig ampduConfig = readAmpduConfig(properties, accessMode, frameExchange, edcaParameters);
        ExternalInterference externalInterference = readExternalInterference(properties, sfuCount);
        DownlinkDecoder downlinkDecoder = readDownlinkDecoder(properties, retryPolicy, channelTruth, externalInterference);
        long wifiUs = wirelessPhy == null ? readNonNegativeLong(properties, "wifi.delay.us") : 0;
        long durationUs = poisson ? readPositiveDuration(properties, "traffic.duration.us") : 0;
        PoissonTraffic downlink = poisson && directionMode.allows(Packet.Direction.DL)
                ? readDownlinkSources(properties, sfuCount, staSfuIds, durationUs) : new PoissonTraffic();
        PoissonTraffic uplink = poisson && directionMode.allows(Packet.Direction.UL)
                ? readUplinkSources(properties, staCount, durationUs, downlink.windowsUs.size()) : new PoissonTraffic();
        List<Event> manualEvents = poisson ? List.of() : readManualEvents(properties, arrivalsUs, staCount, directionMode);
        // 保留固定方向的时延参数。未生成报文的正速率泊松源也属于已配置业务。
        if (!hasFixedDelay) {
            if (ponDownlink == null && hasTraffic(downlink, manualEvents, Packet.Direction.DL)) {
                throw new IllegalArgumentException("Missing property: " + FIBER_DELAY_US + " is required by fixed downlink fiber");
            }
            if (ponUplink == null && hasTraffic(uplink, manualEvents, Packet.Direction.UL)) {
                throw new IllegalArgumentException("Missing property: " + FIBER_DELAY_US + " is required by fixed uplink fiber");
            }
        }
        List<TrafficGenerator.Source> sources = new ArrayList<>(downlink.streams);
        sources.addAll(uplink.streams);
        List<Event> inputEvents = poisson ? TrafficGenerator.generate(sources) : manualEvents;

        validateCompletionClock(inputEvents, sfuCount, staSfuIds, fiberUs, ponDownlink, ponUplink, wifiUs, wirelessPhy,
                stationPhys, alternateStationPhys, frameExchange,
                apActivityEnabled || apObservationPeriodNs > 0 || channelObservation != null);
        return new SimulationConfig(inputEvents, sfuCount, staSfuIds, fiberUs, ponDownlink, ponUplink, wifiUs, wirelessPhy,
                apCapabilityModes, stationPhys, alternateStationPhys, apActivityEnabled, apActivityWindowNs,
                apEnergyModel, apSwitching, apControl, apObservationPeriodNs, apObservationAlpha, apAnalysis,
                channelObservation,
                frameExchange, edcaParameters, accessMode, retryPolicy, ampduConfig, downlinkDecoder, externalInterference,
                downlink.windowsUs, uplink.windowsUs,
                directionMode.allows(Packet.Direction.UL), demandLedgerEnabled, dbaShadowEnabled, dbaExecutionEnabled,
                dbaMultiGrantEnabled, dbaGiantEnabled, dbaGiantCompleteEnabled, tcontTopology);
    }

    private static TcontTopology readTcontTopology(Properties properties, int sfuCount, boolean dbaExecutionEnabled) {
        String enabledValue = properties.getProperty(TCONT_ENABLED, "false").strip();
        if (!enabledValue.equals("true") && !enabledValue.equals("false")) {
            throw new IllegalArgumentException(TCONT_ENABLED + " must be true or false");
        }
        if (!enabledValue.equals("true")) {
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith(TCONT_PREFIX) && !key.equals(TCONT_ENABLED)) {
                    throw new IllegalArgumentException(key + " requires " + TCONT_ENABLED + "=true");
                }
            }
            return TcontTopology.disabled(sfuCount);
        }
        // 真实 DBA 路径依赖 pon-framed、BWmap、REPORT、账本和有限轮询。
        if (!dbaExecutionEnabled) {
            throw new IllegalArgumentException(TCONT_ENABLED + " requires fiber.uplink.dba.mode=rr, rr-multi, giant or giant-complete");
        }
        Set<Long> allocationIds = new HashSet<>();
        List<TcontProfile> profiles = new ArrayList<>();
        for (String rawId : requireProperty(properties, TCONT_ALLOC_IDS).split(",", -1)) {
            long allocationId = parseNonNegativeLong(rawId, TCONT_ALLOC_IDS);
            if (allocationId == 0 || !allocationIds.add(allocationId)) {
                throw new IllegalArgumentException(TCONT_ALLOC_IDS + " requires unique positive allocation IDs");
            }
            String prefix = TCONT_PREFIX + "alloc." + allocationId + ".";
            int sfuId = readPositiveInt(properties, prefix + "sfu.id");
            int type = readPositiveInt(properties, prefix + "type");
            Set<AccessCategory> classes = EnumSet.noneOf(AccessCategory.class);
            for (String rawClass : requireProperty(properties, prefix + "classes").split(",", -1)) {
                AccessCategory category;
                try {
                    category = AccessCategory.valueOf(rawClass.strip());
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(prefix + "classes must contain VO, VI, BE or BK", exception);
                }
                if (!classes.add(category)) {
                    throw new IllegalArgumentException(prefix + "classes contains a duplicate business class");
                }
            }
            profiles.add(readTcontProfile(properties, prefix, allocationId, sfuId, type, classes));
        }
        // 已声明但未列入 alloc.ids 的对象也必须报错，防止部分参数被静默忽略。
        for (String key : properties.stringPropertyNames()) {
            Matcher matcher = TCONT_ALLOCATION_KEY.matcher(key);
            if (matcher.matches() && !allocationIds.contains(parseNonNegativeLong(matcher.group(1), key))) {
                throw new IllegalArgumentException("T-CONT allocation is not listed in " + TCONT_ALLOC_IDS + ": " + key);
            }
        }
        return new TcontTopology(sfuCount, profiles);
    }

    private static TcontProfile readTcontProfile(Properties properties, String prefix, long allocationId,
                                                 int sfuId, int type, Set<AccessCategory> classes) {
        // Type 1 只有固定带宽；Type 1/2 可省略未启用的 SImin / ABsur。
        int siMax = type == 1 ? readTcontInterval(properties, prefix + "si.max.frames", true)
                : readPositiveInt(properties, prefix + "si.max.frames");
        int siMin = readTcontInterval(properties, prefix + "si.min.frames", type == 1 || type == 2);
        long abMin = readTcontQuota(properties, prefix + "ab.min.bytes", type == 1);
        long abSur = readTcontQuota(properties, prefix + "ab.sur.bytes", type == 1 || type == 2);

        String siFixedKey = prefix + "si.fixed.frames";
        String abFixedKey = prefix + "ab.fixed.bytes";
        String siBeKey = prefix + "si.be.frames";
        String abBeKey = prefix + "ab.be.bytes";
        if (type >= 2 && type <= 4) {
            for (String key : List.of(siFixedKey, abFixedKey, siBeKey, abBeKey)) {
                if (properties.containsKey(key)) {
                    throw new IllegalArgumentException(key + " is not used by Type " + type);
                }
            }
            return new TcontProfile(allocationId, sfuId, type, classes, siMax, siMin, abMin, abSur);
        }
        return new TcontProfile(allocationId, sfuId, type, classes, siMax, siMin, abMin, abSur,
                readTcontInterval(properties, siFixedKey, false), readTcontQuota(properties, abFixedKey, false),
                readTcontInterval(properties, siBeKey, type == 1), readTcontQuota(properties, abBeKey, type == 1));
    }

    private static int readTcontInterval(Properties properties, String key, boolean optional) {
        long value = readTcontQuota(properties, key, optional);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must fit a nonnegative integer");
        }
        return (int) value;
    }

    private static long readTcontQuota(Properties properties, String key, boolean optional) {
        return optional && !properties.containsKey(key) ? 0 : readNonNegativeLong(properties, key);
    }

    private static String readDbaMode(Properties properties, SimulationConfig.PonLink uplink,
                                      boolean demandLedgerEnabled) {
        String mode = properties.getProperty(UPLINK_DBA_MODE, "fcfs").strip();
        if (mode.equals("fcfs")) {
            return mode;
        }
        if (!mode.equals("shadow-rr") && !mode.equals("rr") && !mode.equals("rr-multi")
                && !mode.equals("giant") && !mode.equals("giant-complete")) {
            throw new IllegalArgumentException(UPLINK_DBA_MODE + " must be fcfs, shadow-rr, rr, rr-multi, giant or giant-complete");
        }
        if (uplink == null || uplink.framePeriodNs() == 0 || uplink.bwmapPropagationNs() < 0
                || !uplink.reportEnabled() || !demandLedgerEnabled || uplink.polling() == null) {
            throw new IllegalArgumentException(mode + " requires pon-framed, BWmap, REPORT, demand ledger and polling");
        }
        return mode;
    }

    private static boolean readDemandLedgerEnabled(Properties properties, SimulationConfig.PonLink ponUplink) {
        if (!properties.containsKey(UPLINK_DEMAND_LEDGER_ENABLED)) {
            return false;
        }
        if (ponUplink == null || ponUplink.framePeriodNs() == 0) {
            throw new IllegalArgumentException(UPLINK_DEMAND_LEDGER_ENABLED + " requires fiber.uplink.mode=pon-framed");
        }
        String enabled = properties.getProperty(UPLINK_DEMAND_LEDGER_ENABLED).strip();
        if (!enabled.equals("true") && !enabled.equals("false")) {
            throw new IllegalArgumentException(UPLINK_DEMAND_LEDGER_ENABLED + " must be true or false");
        }
        return enabled.equals("true");
    }

    private static boolean hasTraffic(PoissonTraffic poisson, List<Event> manualEvents, Packet.Direction direction) {
        return !poisson.streams.isEmpty() || manualEvents.stream().anyMatch(event -> event.packet().direction() == direction);
    }

    private static long readBwmapPropagationNs(Properties properties) {
        if (!properties.containsKey(UPLINK_BWMAP_ENABLED)) {
            return -1;
        }
        String uplinkModeKey = FIBER_UPLINK + "mode";
        if (!properties.getProperty(uplinkModeKey, "fixed").strip().equals("pon-framed")) {
            throw new IllegalArgumentException(UPLINK_BWMAP_ENABLED + " requires " + uplinkModeKey + "=pon-framed");
        }
        String enabled = properties.getProperty(UPLINK_BWMAP_ENABLED).strip();
        if (!enabled.equals("true") && !enabled.equals("false")) {
            throw new IllegalArgumentException(UPLINK_BWMAP_ENABLED + " must be true or false");
        }
        return enabled.equals("true") ? readDurationNs(properties, DOWNLINK_PROPAGATION_US, "0", false) : -1;
    }

    private static SimulationConfig.PonLink readPonLink(Properties properties, String prefix, String defaultRateMbps,
                                                       long bwmapPropagationNs) {
        String modeKey = prefix + "mode";
        String rateKey = prefix + "rate.mbps";
        String propagationKey = prefix + "propagation.us";
        String mode = properties.getProperty(modeKey, "fixed").strip();
        boolean uplink = prefix.equals(FIBER_UPLINK);
        boolean framed = uplink && mode.equals("pon-framed");
        boolean bwmapDownlink = !uplink && bwmapPropagationNs >= 0;
        if (uplink && !framed && properties.containsKey(UPLINK_POLL_ENABLED)) {
            throw new IllegalArgumentException(UPLINK_POLL_ENABLED + " requires " + modeKey + "=pon-framed");
        }
        if (uplink && !framed && hasPollingParameters(properties)) {
            throw new IllegalArgumentException("Polling parameters require " + modeKey + "=pon-framed");
        }
        if (uplink && !framed && properties.containsKey(UPLINK_FRAME_PERIOD_US)) {
            throw new IllegalArgumentException(UPLINK_FRAME_PERIOD_US + " requires " + modeKey + "=pon-framed");
        }
        if (uplink && !framed && properties.containsKey(UPLINK_REPORT_ENABLED)) {
            throw new IllegalArgumentException(UPLINK_REPORT_ENABLED + " requires " + modeKey + "=pon-framed");
        }
        if (mode.equals("fixed")) {
            // BWmap 使用下行传播时延，固定下行 DATA 仍使用 fiber.delay.us。
            if (bwmapDownlink) {
                if (properties.containsKey(rateKey)) {
                    throw new IllegalArgumentException(rateKey + " requires " + modeKey + "=pon-shared");
                }
                return null;
            }
            if (properties.containsKey(rateKey) || properties.containsKey(propagationKey)) {
                throw new IllegalArgumentException("PON rate and propagation require " + modeKey + "=pon-shared"
                        + (uplink ? " or pon-framed" : ""));
            }
            return null;
        }
        if (!mode.equals("pon-shared") && !framed) {
            throw new IllegalArgumentException(modeKey + " must be fixed or pon-shared" + (uplink ? " or pon-framed" : ""));
        }
        BigDecimal rateMbps;
        try {
            rateMbps = new BigDecimal(properties.getProperty(rateKey, defaultRateMbps).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(rateKey + " must be a positive decimal number", e);
        }
        if (rateMbps.signum() <= 0) {
            throw new IllegalArgumentException(rateKey + " must be a positive decimal number");
        }
        long propagationNs = bwmapDownlink ? bwmapPropagationNs : readDurationNs(properties, propagationKey, "0", false);
        long framePeriodNs = framed ? readDurationNs(properties, UPLINK_FRAME_PERIOD_US, "125", true) : 0;
        long linkBwmapPropagationNs = framed ? bwmapPropagationNs : -1;
        if (linkBwmapPropagationNs >= framePeriodNs) {
            throw new IllegalArgumentException(DOWNLINK_PROPAGATION_US + " must be less than "
                    + UPLINK_FRAME_PERIOD_US + " when BWmap is enabled");
        }
        String reportEnabled = framed ? properties.getProperty(UPLINK_REPORT_ENABLED, "false").strip() : "false";
        if (!reportEnabled.equals("true") && !reportEnabled.equals("false")) {
            throw new IllegalArgumentException(UPLINK_REPORT_ENABLED + " must be true or false");
        }
        SimulationConfig.PonPolling polling = uplink
                ? readPolling(properties, rateMbps, propagationNs, framePeriodNs, reportEnabled.equals("true"),
                        linkBwmapPropagationNs) : null;
        return new SimulationConfig.PonLink(rateMbps, propagationNs, framePeriodNs, reportEnabled.equals("true"),
                linkBwmapPropagationNs, polling);
    }

    private static boolean hasPollingParameters(Properties properties) {
        return properties.containsKey(UPLINK_POLL_BYTES) || properties.containsKey(UPLINK_POLL_INTERVAL_FRAMES)
                || properties.containsKey(UPLINK_POLL_END_US);
    }

    private static SimulationConfig.PonPolling readPolling(Properties properties, BigDecimal rateMbps,
                                                          long propagationNs, long framePeriodNs,
                                                          boolean reportEnabled, long bwmapPropagationNs) {
        String enabled = properties.getProperty(UPLINK_POLL_ENABLED, "false").strip();
        if (!enabled.equals("true") && !enabled.equals("false")) {
            throw new IllegalArgumentException(UPLINK_POLL_ENABLED + " must be true or false");
        }
        if (enabled.equals("false")) {
            if (hasPollingParameters(properties)) {
                throw new IllegalArgumentException("Polling parameters require " + UPLINK_POLL_ENABLED + "=true");
            }
            return null;
        }
        if (framePeriodNs == 0 || !reportEnabled || bwmapPropagationNs < 0) {
            throw new IllegalArgumentException("REPORT-only polling requires pon-framed, REPORT and BWmap");
        }
        int bytes = properties.containsKey(UPLINK_POLL_BYTES) ? readPositiveInt(properties, UPLINK_POLL_BYTES) : 64;
        int intervalFrames = properties.containsKey(UPLINK_POLL_INTERVAL_FRAMES)
                ? readPositiveInt(properties, UPLINK_POLL_INTERVAL_FRAMES) : 1;
        if (!properties.containsKey(UPLINK_POLL_END_US)) {
            throw new IllegalArgumentException("Missing property: " + UPLINK_POLL_END_US);
        }
        long endNs = readDurationNs(properties, UPLINK_POLL_END_US, "0", true);
        if (endNs <= framePeriodNs) {
            throw new IllegalArgumentException(UPLINK_POLL_END_US + " must be greater than the frame period");
        }
        try {
            long durationNs = PonLinkTiming.serializationNs(bytes, rateMbps);
            if (durationNs > framePeriodNs) {
                throw new IllegalArgumentException("REPORT-only slot exceeds the uplink frame capacity");
            }
            // 首次在帧 1 调度，此后每隔 intervalFrames 帧，调度边界严格早于 endNs。
            long finalEligibleFrame = (endNs - 1) / framePeriodNs;
            long lastPollFrame = 1 + ((finalEligibleFrame - 1) / intervalFrames) * intervalFrames;
            long lastPollNs = Math.multiplyExact(lastPollFrame, framePeriodNs);
            long targetStartNs = Math.addExact(lastPollNs, framePeriodNs);
            Math.addExact(targetStartNs, framePeriodNs);
            Math.addExact(Math.addExact(targetStartNs, durationNs), propagationNs);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Polling window exceeds the nanosecond clock range", e);
        }
        return new SimulationConfig.PonPolling(bytes, intervalFrames, endNs);
    }

    private static long readDurationNs(Properties properties, String key, String defaultUs, boolean positive) {
        return parseDurationNs(properties.getProperty(key, defaultUs), key, positive);
    }

    private static long parseDurationNs(String value, String key, boolean positive) {
        String constraint = positive ? "positive" : "non-negative";
        long durationNs;
        try {
            durationNs = new BigDecimal(value.strip()).scaleByPowerOfTen(3).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException(key + " must be a " + constraint
                    + " duration in microseconds representable in whole nanoseconds", e);
        }
        if (durationNs < (positive ? 1 : 0)) {
            throw new IllegalArgumentException(key + " must be " + constraint);
        }
        return durationNs;
    }

    private static WifiRetryPolicy readWifiRetryPolicy(Properties properties, WifiAccessMode accessMode,
                                                       WifiFrameExchange frameExchange, EdcaParameters edcaParameters) {
        boolean timeoutConfigured = properties.containsKey(MAC_ACK_TIMEOUT_US);
        if (accessMode != WifiAccessMode.EDCA_MULTI_AC || frameExchange == null) {
            if (timeoutConfigured || properties.containsKey(MAC_MAX_RETRIES)) {
                throw new IllegalArgumentException("ACK retry parameters require edca-multi-ac and data-sifs-ack");
            }
            return null;
        }
        // 近似的缺失 ACK 等待窗口。默认覆盖一次正常 SIFS、ACK 和一个空闲时隙。
        long timeoutUs = timeoutConfigured ? readNonNegativeLong(properties, MAC_ACK_TIMEOUT_US)
                : frameExchange.responseDurationNs() / 1_000L + edcaParameters.slotTimeUs();
        return new WifiRetryPolicy(timeoutUs, readOptionalNonNegativeInt(properties, MAC_MAX_RETRIES, 3));
    }

    private static WifiAccessMode readWifiAccessMode(Properties properties) {
        String access = properties.getProperty(MAC_ACCESS, "fixed-priority").strip();
        switch (access) {
            case "fixed-priority": return WifiAccessMode.FIXED_PRIORITY;
            case "edca-countdown": return WifiAccessMode.EDCA_COUNTDOWN;
            case "edca-multi-ac": return WifiAccessMode.EDCA_MULTI_AC;
            default: throw new IllegalArgumentException(MAC_ACCESS + " must be fixed-priority, edca-countdown or edca-multi-ac");
        }
    }

    private static AmpduConfig readAmpduConfig(Properties properties, WifiAccessMode accessMode,
                                                WifiFrameExchange frameExchange, EdcaParameters edcaParameters) {
        String enabled = properties.getProperty(AMPDU_ENABLED, "false").strip();
        if (enabled.equals("false")) {
            return null;
        }
        if (!enabled.equals("true")) {
            throw new IllegalArgumentException(AMPDU_ENABLED + " must be true or false");
        }
        if (accessMode != WifiAccessMode.EDCA_MULTI_AC || frameExchange == null) {
            throw new IllegalArgumentException(AMPDU_ENABLED + " requires HE SU, edca-multi-ac and data-sifs-ack");
        }
        for (AccessCategory category : CATEGORIES) {
            if (edcaParameters.forCategory(category).txopLimitUs() != 0) {
                throw new IllegalArgumentException(AMPDU_ENABLED + " requires zero TXOP limits in this model");
            }
        }
        if (readOptionalNonNegativeInt(properties, "wifi.phy.mac.overhead.bytes", 34) < 4) {
            throw new IllegalArgumentException(AMPDU_ENABLED + " requires MAC overhead including the 4-byte delimiter");
        }
        frameExchange.blockAckResponseDurationNs();
        Set<Integer> firstAttemptErrors = new HashSet<>();
        String errorList = properties.getProperty(AMPDU_FIRST_ATTEMPT_ERRORS, "").strip();
        if (!errorList.isEmpty()) {
            for (String entry : errorList.split(",", -1)) {
                long id = parseNonNegativeLong(entry, AMPDU_FIRST_ATTEMPT_ERRORS);
                if (id == 0 || id > Integer.MAX_VALUE || !firstAttemptErrors.add((int) id)) {
                    throw new IllegalArgumentException(AMPDU_FIRST_ATTEMPT_ERRORS
                            + " must list distinct positive 32-bit packet IDs");
                }
            }
        }
        return new AmpduConfig(readOptionalNonNegativeInt(properties, AMPDU_MAX_MPDUS, 8),
                readOptionalNonNegativeInt(properties, AMPDU_MAX_BYTES, 65_535), firstAttemptErrors);
    }

    private static EdcaParameters readEdcaParameters(Properties properties) {
        EdcaParameters defaults = EdcaParameters.defaults();
        long sifsUs = properties.containsKey(MAC_SIFS_US) ? readNonNegativeLong(properties, MAC_SIFS_US) : defaults.sifsUs();
        long slotTimeUs = properties.containsKey(MAC_SLOT_US) ? readNonNegativeLong(properties, MAC_SLOT_US)
                : defaults.slotTimeUs();
        long seed = properties.containsKey(EDCA_SEED) ? readSeed(properties, EDCA_SEED) : defaults.seed();
        Map<AccessCategory, AcParameters> byCategory = new EnumMap<>(AccessCategory.class);
        for (AccessCategory category : CATEGORIES) {
            AcParameters acDefaults = defaults.forCategory(category);
            String prefix = "wifi.mac.edca." + category + ".";
            int aifsn = readOptionalNonNegativeInt(properties, prefix + "aifsn", acDefaults.aifsn());
            int cwMin = readOptionalNonNegativeInt(properties, prefix + "cwmin", acDefaults.cwMin());
            int cwMax = readOptionalNonNegativeInt(properties, prefix + "cwmax", acDefaults.cwMax());
            String txopKey = prefix + "txop.limit.us";
            long txopLimitUs = properties.containsKey(txopKey) ? readNonNegativeLong(properties, txopKey)
                    : acDefaults.txopLimitUs();
            try {
                byCategory.put(category, new AcParameters(aifsn, cwMin, cwMax, txopLimitUs));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid EDCA parameters for " + category + ": " + e.getMessage(), e);
            }
        }
        return new EdcaParameters(sifsUs, slotTimeUs, byCategory, seed);
    }

    private static WifiFrameExchange readWifiFrameExchange(Properties properties, WirelessPhy wirelessPhy, long sifsUs) {
        String mode = properties.getProperty(MAC_EXCHANGE, "data-only").strip();
        if (mode.equals("data-only")) {
            if (properties.containsKey(MAC_ACK_RATE_MBPS)) {
                throw new IllegalArgumentException(MAC_ACK_RATE_MBPS + " requires wifi.mac.exchange=data-sifs-ack");
            }
            return null;
        }
        if (!mode.equals("data-sifs-ack")) {
            throw new IllegalArgumentException("wifi.mac.exchange must be data-only or data-sifs-ack");
        }
        if (wirelessPhy == null) {
            throw new IllegalArgumentException("wifi.mac.exchange=data-sifs-ack requires wifi.model=he-su");
        }
        int ackRateMbps = properties.containsKey(MAC_ACK_RATE_MBPS) ? readPositiveInt(properties, MAC_ACK_RATE_MBPS) : 24;
        return new WifiFrameExchange(sifsUs, ackRateMbps);
    }

    private static WirelessPhy readWirelessPhy(Properties properties) {
        // 旧配置只要包含 wifi.delay.us 就继续使用固定时延。新场景默认使用 HE SU。
        String defaultMode = properties.containsKey("wifi.delay.us") ? "fixed" : "he-su";
        String mode = properties.getProperty("wifi.model", defaultMode).strip();
        if (mode.equals("fixed")) {
            if (PHY_KEYS.stream().anyMatch(properties::containsKey)) {
                throw new IllegalArgumentException("wifi.phy.* requires wifi.model=he-su");
            }
            return null;
        }
        if (!mode.equals("he-su")) {
            throw new IllegalArgumentException("wifi.model must be fixed or he-su");
        }
        if (properties.containsKey("wifi.delay.us")) {
            throw new IllegalArgumentException("wifi.delay.us requires wifi.model=fixed");
        }
        int giNs;
        try {
            giNs = new BigDecimal(properties.getProperty("wifi.phy.gi.us", "0.8").strip())
                    .movePointRight(3).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("wifi.phy.gi.us must be 0.8, 1.6 or 3.2", e);
        }
        return buildWirelessPhy(properties, readOptionalNonNegativeInt(properties, "wifi.phy.bandwidth.mhz", 160),
                readOptionalNonNegativeInt(properties, "wifi.phy.mcs", 11),
                readOptionalNonNegativeInt(properties, "wifi.phy.nss", 2), giNs);
    }

    private static WirelessPhy buildWirelessPhy(Properties properties, int bandwidthMhz, int mcs, int nss, int giNs) {
        return new WirelessPhy(bandwidthMhz, mcs, nss, giNs,
                readOptionalNonNegativeInt(properties, "wifi.phy.mac.overhead.bytes", 34),
                readOptionalNonNegativeInt(properties, "wifi.phy.he-ltf.type", WirelessPhy.defaultHeLtfType(giNs)),
                readOptionalNonNegativeInt(properties, "wifi.phy.nominal.packet.padding.us", 0));
    }

    private static ApModeController.Settings readApControl(Properties properties, int sfuCount) {
        ApModeController.Policy fallback = readApPolicy(properties, AP_CONTROL_POLICY, "off");
        Map<Integer, ApModeController.Policy> policies = new HashMap<>();
        for (int sfuId = 1; sfuId <= sfuCount; sfuId++) {
            ApModeController.Policy policy = readApPolicy(properties, "sfu." + sfuId + "." + AP_CONTROL_POLICY,
                    fallback.key());
            if (policy != ApModeController.Policy.OFF) {
                policies.put(sfuId, policy);
            }
        }
        long periodNs = readDurationNs(properties, AP_CONTROL_PERIOD_US,
                Long.toString(ApModeController.DEFAULT_PERIOD_NS / 1_000), true);
        double alpha = parseNonNegativeDouble(properties.getProperty(AP_CONTROL_ALPHA,
                Double.toString(ApModeController.DEFAULT_ALPHA)), AP_CONTROL_ALPHA);
        if (!(alpha > 0 && alpha <= 1)) {
            throw new IllegalArgumentException(AP_CONTROL_ALPHA + " must be in (0, 1]");
        }
        double threshold = parseNonNegativeDouble(properties.getProperty(AP_CONTROL_THRESHOLD,
                Double.toString(ApModeController.DEFAULT_THRESHOLD_MBPS)), AP_CONTROL_THRESHOLD);
        double margin = parseNonNegativeDouble(properties.getProperty(AP_CONTROL_MARGIN,
                Double.toString(ApModeController.DEFAULT_ENERGY_MARGIN_UJ)), AP_CONTROL_MARGIN);
        String model = properties.getProperty(AP_CONTROL_SERVICE_MODEL, "auto").strip();
        ApModeController.ServiceModel serviceModel;
        switch (model) {
            case "auto": serviceModel = ApModeController.ServiceModel.AUTO; break;
            case "d2-original": serviceModel = ApModeController.ServiceModel.D2_ORIGINAL; break;
            case "load-aware": serviceModel = ApModeController.ServiceModel.LOAD_AWARE; break;
            default: throw new IllegalArgumentException(AP_CONTROL_SERVICE_MODEL + " must be auto, d2-original or load-aware");
        }
        long qosMargin = readDurationNs(properties, AP_CONTROL_QOS_MARGIN_US,
                Long.toString(ApModeController.DEFAULT_QOS_MARGIN_NS / 1000), false);
        long deferLimit = readDurationNs(properties, AP_CONTROL_DEFER_LIMIT_US,
                Long.toString(ApModeController.DEFAULT_DEFERRAL_LIMIT_NS / 1000), false);
        long minimumResidency = readDurationNs(properties, AP_CONTROL_MIN_RESIDENCY_US,
                Long.toString(ApModeController.DEFAULT_MINIMUM_RESIDENCY_NS / 1000), false);
        double reverseMargin = parseNonNegativeDouble(properties.getProperty(AP_CONTROL_REVERSE_MARGIN,
                Double.toString(ApModeController.DEFAULT_REVERSE_MARGIN_UJ)), AP_CONTROL_REVERSE_MARGIN);
        return new ApModeController.Settings(policies, periodNs, alpha, threshold, margin, serviceModel, qosMargin, deferLimit,
                minimumResidency, reverseMargin);
    }

    private static ApModeController.Policy readApPolicy(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key, fallback).strip();
        for (ApModeController.Policy policy : ApModeController.Policy.values()) {
            if (policy.key().equals(value)) {
                return policy;
            }
        }
        throw new IllegalArgumentException(key + " must be off, always-lcm, always-hcm, fixed-threshold, e-aware, ec-aware, ecq-aware, ecq-sdps or ecq-sdps-d4");
    }

    private static void validateApControl(ApModeController.Settings control, ApModeSwitch.Settings switching) {
        if (control.hasDynamicPolicy() && (switching == null || switching.profileFrom(ApCapabilityMode.LCM) == null
                || switching.profileFrom(ApCapabilityMode.HCM) == null)) {
            throw new IllegalArgumentException("Dynamic AP control requires enabled B4 switching and both direction profiles");
        }
        if (switching != null) {
            for (int sfuId : control.policies().keySet()) {
                if (!switching.requestsFor(sfuId).isEmpty()) {
                    throw new IllegalArgumentException("SFU " + sfuId + " cannot combine mode control and manual switches");
                }
            }
        }
    }

    private static Map<Integer, ApCapabilityMode> readApCapabilityModes(Properties properties, int sfuCount,
                                                                       WirelessPhy wirelessPhy,
                                                                       ApModeController.Settings control) {
        Map<Integer, ApCapabilityMode> modes = new HashMap<>();
        for (int sfuId = 1; sfuId <= sfuCount; sfuId++) {
            String key = "sfu." + sfuId + ".wifi.ap.mode";
            ApModeController.Policy policy = control.policyFor(sfuId);
            if (!properties.containsKey(key) && policy == ApModeController.Policy.OFF) {
                continue;
            }
            if (wirelessPhy == null) {
                throw new IllegalArgumentException(key + " requires wifi.model=he-su");
            }
            ApCapabilityMode pinned = policy.staticMode();
            String fallback = pinned == null ? "LCM" : pinned.name();
            ApCapabilityMode configured;
            try {
                configured = ApCapabilityMode.valueOf(properties.getProperty(key, fallback).strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(key + " must be LCM or HCM", e);
            }
            // Always 策略直接选定启动能力，启动不经过切换，因此没有虚假的切换开销。
            modes.put(sfuId, pinned == null ? configured : pinned);
        }
        return modes;
    }

    private static boolean readApActivityEnabled(Properties properties) {
        String value = properties.getProperty(AP_ACTIVITY_ENABLED, "false").strip();
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(AP_ACTIVITY_ENABLED + " must be true or false");
        }
        if (value.equals("false") && properties.containsKey(AP_ACTIVITY_WINDOW_US)) {
            throw new IllegalArgumentException(AP_ACTIVITY_WINDOW_US + " requires " + AP_ACTIVITY_ENABLED + "=true");
        }
        return value.equals("true");
    }

    private static long readApObservationPeriod(Properties properties, ApModeController.Settings control) {
        String enabled = properties.getProperty(AP_OBSERVATION_ENABLED, "false").strip();
        if (!enabled.equals("true") && !enabled.equals("false")) {
            throw new IllegalArgumentException(AP_OBSERVATION_ENABLED + " must be true or false");
        }
        long defaultNs = control.needsPrediction() ? ApModeController.DEFAULT_PERIOD_NS : ApTrafficObserver.DEFAULT_PERIOD_NS;
        String defaultUs = Long.toString(defaultNs / 1_000);
        long periodNs = readDurationNs(properties, AP_OBSERVATION_PERIOD_US, defaultUs, true);
        return enabled.equals("true") || control.hasDynamicPolicy() ? periodNs : 0;
    }

    private static double readApObservationAlpha(Properties properties) {
        String error = AP_OBSERVATION_EWMA_ALPHA + " must be in (0, 1]";
        double alpha;
        try {
            String defaultAlpha = Double.toString(ApTrafficObserver.DEFAULT_EWMA_ALPHA);
            alpha = Double.parseDouble(properties.getProperty(AP_OBSERVATION_EWMA_ALPHA, defaultAlpha).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error, e);
        }
        if (!(alpha > 0 && alpha <= 1)) {
            throw new IllegalArgumentException(error);
        }
        return alpha;
    }

    private static ApAnalysisSettings readApAnalysisSettings(Properties properties, long c1PeriodNs,
                                                              ApEnergyModel energyModel, boolean allApModes,
                                                              boolean controlPrediction) {
        boolean predict = channelBoolean(properties.getProperty(AP_PREDICTION_ENABLED, "false").strip(),
                AP_PREDICTION_ENABLED);
        boolean qos = channelBoolean(properties.getProperty(AP_QOS_ENABLED, "false").strip(), AP_QOS_ENABLED);
        if (!predict && !qos && !controlPrediction) {
            return null;
        }
        if (c1PeriodNs == 0 || (predict && (energyModel == null || !allApModes))) {
            throw new IllegalArgumentException("AP analysis requires C1; C3 also requires B3 and explicit AP modes");
        }
        EnumMap<AccessCategory, ApAnalysisSettings.Requirement> requirements = new EnumMap<>(AccessCategory.class);
        long[] defaultDeadlineUs = {10_000, 30_000, 100_000, 200_000};
        double[] defaultMinOnTime = {0.99, 0.98, 0.95, 0.90};
        for (AccessCategory ac : AccessCategory.values()) {
            String prefix = "wifi.ap.qos." + ac + ".";
            long deadline = readDurationNs(properties, prefix + "deadline.us",
                    Long.toString(defaultDeadlineUs[ac.ordinal()]), true);
            double minOnTime = readAnalysisFraction(properties, prefix + "min-on-time", defaultMinOnTime[ac.ordinal()]);
            requirements.put(ac, new ApAnalysisSettings.Requirement(deadline, minOnTime));
        }
        String model = properties.getProperty(AP_PREDICTION_MODEL, "legacy").strip();
        if (!model.equals("legacy") && !model.equals("revised")) {
            throw new IllegalArgumentException(AP_PREDICTION_MODEL + " must be legacy or revised");
        }
        return new ApAnalysisSettings(predict, model.equals("revised"),
                readDurationNs(properties, AP_PREDICTION_WINDOW_US, controlPrediction ? "5000" : "1000", true),
                readAnalysisFraction(properties, AP_PREDICTION_ALPHA, 0.25), requirements);
    }

    private static double readAnalysisFraction(Properties properties, String key, double fallback) {
        double value;
        try {
            value = Double.parseDouble(properties.getProperty(key, Double.toString(fallback)).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " requires a fraction in [0,1]", e);
        }
        if (!(value >= 0 && value <= 1)) {
            throw new IllegalArgumentException(key + " requires a fraction in [0,1]");
        }
        return value;
    }

    private static ChannelTruth readChannelTruth(Properties properties, List<Integer> staSfuIds) {
        List<ChannelTruth.Change> changes = new ArrayList<>();
        for (String row : channelRows(properties, CHANNEL_TRUTH, ";")) {
            String[] fields = channelFields(row, CHANNEL_TRUTH, 7, 8, staSfuIds);
            double noiseDbm = fields.length == 8 ? finiteDb(fields[7], CHANNEL_TRUTH + " noiseDbm")
                    : ChannelTruth.DEFAULT_NOISE_DBM;
            changes.add(new ChannelTruth.Change(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]), parseDurationNs(fields[3], CHANNEL_TRUTH, false),
                    channelSinr(fields[4]), channelInt(fields[5], CHANNEL_TRUTH),
                    channelBoolean(fields[6], CHANNEL_TRUTH + " interfered"), noiseDbm));
        }
        return new ChannelTruth(changes);
    }

    private static ChannelObservationInput readChannelObservation(Properties properties, int sfuCount,
                                                                   List<Integer> staSfuIds,
                                                                   ChannelTruth truth) {
        boolean enabled = channelBoolean(properties.getProperty(CHANNEL_OBSERVATION_ENABLED, "false").strip(),
                CHANNEL_OBSERVATION_ENABLED);
        List<ChannelObservationInput.Feedback> feedback = new ArrayList<>();
        long airtimeNs = readDurationNs(properties, CHANNEL_FEEDBACK_AIRTIME, "0", false);
        for (String row : channelRows(properties, CHANNEL_FEEDBACK, ";")) {
            String[] parts = row.split(",", -1);
            boolean lost = parts.length == 7 && parts[6].strip().equals("drop");
            String fieldsWithoutStatus = lost ? row.substring(0, row.lastIndexOf(',')) : row;
            String[] fields = channelFields(fieldsWithoutStatus, CHANNEL_FEEDBACK, 6, staSfuIds);
            feedback.add(new ChannelObservationInput.Feedback(feedback.size() + 1, Integer.parseInt(fields[0]),
                    Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                    parseDurationNs(fields[3], CHANNEL_FEEDBACK, false),
                    parseDurationNs(fields[4], CHANNEL_FEEDBACK, false),
                    parseDurationNs(fields[5], CHANNEL_FEEDBACK, true), airtimeNs, lost));
        }
        List<Long> reads = new ArrayList<>();
        Set<Long> seenReads = new HashSet<>();
        for (String time : channelRows(properties, CHANNEL_READ_TIMES, ",")) {
            long timeNs = parseDurationNs(time, CHANNEL_READ_TIMES, false);
            if (!seenReads.add(timeNs)) {
                throw new IllegalArgumentException(CHANNEL_READ_TIMES + " repeats a timestamp: " + time);
            }
            reads.add(timeNs);
        }
        boolean passive = channelBoolean(properties.getProperty(CHANNEL_PASSIVE, "false").strip(), CHANNEL_PASSIVE);
        BigDecimal scanPower = readPowerWatts(properties, CHANNEL_SCAN_POWER, new BigDecimal("0.5"));
        List<ChannelObservationInput.Scan> scans = readAuxiliaryScans(properties, sfuCount);
        return enabled ? new ChannelObservationInput(truth, feedback, reads, scans, passive, scanPower) : null;
    }

    private static List<ChannelObservationInput.Scan> readAuxiliaryScans(Properties properties, int sfuCount) {
        List<ChannelObservationInput.Scan> scans = new ArrayList<>();
        for (String row : channelRows(properties, CHANNEL_SCAN_SCHEDULE, ";")) {
            String[] fields = row.split(",", -1);
            if (fields.length != 5) {
                throw new IllegalArgumentException(CHANNEL_SCAN_SCHEDULE + " requires sfu,subchannel,startUs,durationUs,validUs");
            }
            int sfu = channelInt(fields[0].strip(), CHANNEL_SCAN_SCHEDULE);
            int subchannel = channelInt(fields[1].strip(), CHANNEL_SCAN_SCHEDULE);
            if (sfu < 1 || sfu > sfuCount || subchannel < 1 || subchannel >= ChannelReport.SUBCHANNEL_COUNT) {
                throw new IllegalArgumentException(CHANNEL_SCAN_SCHEDULE + " requires an existing SFU and secondary subchannel");
            }
            scans.add(new ChannelObservationInput.Scan(sfu, subchannel,
                    parseDurationNs(fields[2], CHANNEL_SCAN_SCHEDULE, false),
                    parseDurationNs(fields[3], CHANNEL_SCAN_SCHEDULE, true),
                    parseDurationNs(fields[4], CHANNEL_SCAN_SCHEDULE, true)));
        }
        scans.sort(Comparator.comparingInt(ChannelObservationInput.Scan::sfuId)
                .thenComparingLong(ChannelObservationInput.Scan::startNs));
        for (int i = 1; i < scans.size(); i++) {
            ChannelObservationInput.Scan prior = scans.get(i - 1);
            ChannelObservationInput.Scan next = scans.get(i);
            if (next.sfuId() == prior.sfuId() && next.startNs() < prior.endNs()) {
                throw new IllegalArgumentException(CHANNEL_SCAN_SCHEDULE + " overlaps a scan on the same auxiliary radio");
            }
        }
        return scans;
    }

    private static DownlinkDecoder readDownlinkDecoder(Properties properties, WifiRetryPolicy retryPolicy,
                                                        ChannelTruth truth, ExternalInterference interference) {
        if (!channelBoolean(properties.getProperty(PHY_PER_ENABLED, "false").strip(), PHY_PER_ENABLED)) {
            if (interference != null) {
                throw new IllegalArgumentException(INTERFERENCE_ENABLED + " requires " + PHY_PER_ENABLED);
            }
            return null;
        }
        if (retryPolicy == null) {
            throw new IllegalArgumentException(PHY_PER_ENABLED + " requires HE SU, edca-multi-ac and data-sifs-ack");
        }
        if (!properties.getProperty(AMPDU_FIRST_ATTEMPT_ERRORS, "").isBlank()) {
            throw new IllegalArgumentException(PHY_PER_ENABLED + " cannot be combined with " + AMPDU_FIRST_ATTEMPT_ERRORS);
        }
        if (readOptionalNonNegativeInt(properties, "wifi.phy.mac.overhead.bytes", 34) < 4) {
            throw new IllegalArgumentException(PHY_PER_ENABLED + " requires MAC overhead including the 4-byte delimiter");
        }
        if (properties.getProperty(PHY_PER_SOURCE, "").isBlank()) {
            throw new IllegalArgumentException(PHY_PER_SOURCE + " must identify the source of the configured curves");
        }
        Map<Integer, List<SinrPerModel.Knot>> curves = new HashMap<>();
        for (String row : channelRows(properties, PHY_PER_CURVES, ";")) {
            String[] fields = row.split(",", -1);
            if (fields.length != 3) {
                throw new IllegalArgumentException(PHY_PER_CURVES + " requires rows of mcs,sinrDb,per");
            }
            try {
                int mcs = Integer.parseInt(fields[0].strip());
                SinrPerModel.Knot knot = new SinrPerModel.Knot(Double.parseDouble(fields[1].strip()),
                        Double.parseDouble(fields[2].strip()));
                curves.computeIfAbsent(mcs, key -> new ArrayList<>()).add(knot);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(PHY_PER_CURVES + " has an invalid number in " + row, e);
            }
        }
        long seed = properties.containsKey(PHY_PER_SEED) ? readSeed(properties, PHY_PER_SEED) : 1L;
        int referenceBytes = readOptionalNonNegativeInt(properties, PHY_PER_REFERENCE_BYTES, 1530);
        SinrPerModel model = new SinrPerModel(seed, referenceBytes, curves);
        String frequencyModel = properties.getProperty(PHY_PER_FREQUENCY_MODEL, "flat").strip();
        switch (frequencyModel) {
            case "flat":
                if (interference != null) {
                    throw new IllegalArgumentException(INTERFERENCE_ENABLED + " requires subchannel-blocks PER");
                }
                return new DownlinkDecoder(truth, model);
            case "subchannel-blocks":
                return new DownlinkDecoder(truth, model, readPerSubchannelWeights(properties), interference);
            default:
                throw new IllegalArgumentException(PHY_PER_FREQUENCY_MODEL + " must be flat or subchannel-blocks");
        }
    }

    private static ExternalInterference readExternalInterference(Properties properties, int sfuCount) {
        if (!channelBoolean(properties.getProperty(INTERFERENCE_ENABLED, "false").strip(), INTERFERENCE_ENABLED)) {
            return null;
        }
        double thresholdDbm = finiteDb(properties.getProperty(EXTERNAL_CCA_THRESHOLD, "-82"), EXTERNAL_CCA_THRESHOLD);
        List<ExternalInterference.Activity> activities = new ArrayList<>();
        for (String row : channelRows(properties, INTERFERENCE_TIMELINE, ";")) {
            String[] values = row.split(",", -1);
            if (values.length != 6) {
                throw new IllegalArgumentException(INTERFERENCE_TIMELINE
                        + " requires sfu,subchannel,startUs,endUs,apPowerDbm,staPowerDbm");
            }
            int sfuId = channelInt(values[0].strip(), INTERFERENCE_TIMELINE);
            if (sfuId < 1 || sfuId > sfuCount) {
                throw new IllegalArgumentException(INTERFERENCE_TIMELINE + " contains an unknown SFU " + sfuId);
            }
            activities.add(new ExternalInterference.Activity(sfuId, channelInt(values[1].strip(), INTERFERENCE_TIMELINE),
                    parseDurationNs(values[2], INTERFERENCE_TIMELINE, false),
                    parseDurationNs(values[3], INTERFERENCE_TIMELINE, true),
                    finiteDb(values[4], INTERFERENCE_TIMELINE + " apPowerDbm"),
                    finiteDb(values[5], INTERFERENCE_TIMELINE + " staPowerDbm")));
        }
        return new ExternalInterference(thresholdDbm, activities);
    }

    private static double[] readPerSubchannelWeights(Properties properties) {
        String[] fields = properties.getProperty(PHY_PER_SUBCHANNEL_WEIGHTS, "1,1,1,1").split(",", -1);
        String error = PHY_PER_SUBCHANNEL_WEIGHTS + " requires four positive finite weights (subchannels 0..3)";
        if (fields.length != ChannelReport.SUBCHANNEL_COUNT) {
            throw new IllegalArgumentException(error);
        }
        double[] weights = new double[fields.length];
        try {
            for (int index = 0; index < weights.length; index++) {
                weights[index] = Double.parseDouble(fields[index].strip());
                if (!Double.isFinite(weights[index]) || weights[index] <= 0) {
                    throw new IllegalArgumentException(error);
                }
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error, e);
        }
        return weights;
    }

    private static String[] channelRows(Properties properties, String key, String separator) {
        String value = properties.getProperty(key, "").strip();
        return value.isEmpty() ? new String[0] : value.split(separator, -1);
    }

    private static String[] channelFields(String row, String key, int length, List<Integer> staSfuIds) {
        return channelFields(row, key, length, length, staSfuIds);
    }

    private static String[] channelFields(String row, String key, int minimum, int maximum, List<Integer> staSfuIds) {
        String[] fields = row.split(",", -1);
        if (fields.length < minimum || fields.length > maximum) {
            String range = minimum == maximum ? Integer.toString(minimum) : minimum + " to " + maximum;
            throw new IllegalArgumentException(key + " requires " + range + " fields per row: " + row);
        }
        for (int i = 0; i < fields.length; i++) {
            fields[i] = fields[i].strip();
        }
        int sfuId = channelInt(fields[0], key);
        int staId = channelInt(fields[1], key);
        int subchannel = channelInt(fields[2], key);
        if (staId < 1 || staId > staSfuIds.size() || sfuId != staSfuIds.get(staId - 1)) {
            throw new IllegalArgumentException(key + " requires an associated SFU and STA: " + row);
        }
        if (subchannel < 0 || subchannel >= ChannelReport.SUBCHANNEL_COUNT) {
            throw new IllegalArgumentException(key + " subchannel must be 0..3: " + row);
        }
        return fields;
    }

    private static int channelInt(String value, String key) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " requires an integer: " + value, e);
        }
    }

    private static double channelSinr(String value) {
        return finiteDb(value, CHANNEL_TRUTH + " SINR");
    }

    private static double finiteDb(String value, String label) {
        try {
            double sinr = Double.parseDouble(value);
            if (Double.isFinite(sinr)) {
                return sinr;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " requires a finite dB value: " + value, e);
        }
        throw new IllegalArgumentException(label + " requires a finite dB value: " + value);
    }

    private static boolean channelBoolean(String value, String key) {
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return value.equals("true");
    }

    private static ApEnergyModel readApEnergyModel(Properties properties, boolean activityEnabled) {
        String enabled = properties.getProperty(AP_ENERGY_ENABLED, "false").strip();
        if (!enabled.equals("true") && !enabled.equals("false")) {
            throw new IllegalArgumentException(AP_ENERGY_ENABLED + " must be true or false");
        }
        if (enabled.equals("true") && !activityEnabled) {
            throw new IllegalArgumentException(AP_ENERGY_ENABLED + " requires " + AP_ACTIVITY_ENABLED + "=true");
        }
        ApEnergyModel.Power lcm = readApPower(properties, LCM_POWER, ApEnergyModel.LCM_DEFAULT);
        ApEnergyModel.Power hcm = readApPower(properties, HCM_POWER, ApEnergyModel.HCM_DEFAULT);
        return enabled.equals("true") ? new ApEnergyModel(lcm, hcm) : null;
    }

    private static ApEnergyModel.Power readApPower(Properties properties, String prefix, ApEnergyModel.Power defaults) {
        return new ApEnergyModel.Power(readPowerWatts(properties, prefix + "idle.w", defaults.idleW()),
                readPowerWatts(properties, prefix + "tx.w", defaults.txW()),
                readPowerWatts(properties, prefix + "rx.w", defaults.rxW()));
    }

    private static BigDecimal readPowerWatts(Properties properties, String key, BigDecimal defaultWatts) {
        String configured = properties.getProperty(key);
        if (configured == null) {
            return defaultWatts;
        }
        String message = key + " must be a positive finite decimal power in W";
        BigDecimal watts;
        try {
            watts = new BigDecimal(configured.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message, e);
        }
        if (!ApEnergyModel.Power.isValidWatts(watts)) {
            throw new IllegalArgumentException(message);
        }
        return watts;
    }

    private static ApModeSwitch.Settings readApSwitching(Properties properties, int sfuCount,
                                                        Map<Integer, ApCapabilityMode> modes, ApEnergyModel energy) {
        String enabled = properties.getProperty(AP_SWITCH_ENABLED, "false").strip();
        if (enabled.equals("false")) {
            return null;
        }
        if (!enabled.equals("true")) {
            throw new IllegalArgumentException(AP_SWITCH_ENABLED + " must be true or false");
        }
        if (energy == null) {
            throw new IllegalArgumentException(AP_SWITCH_ENABLED + " requires " + AP_ENERGY_ENABLED + "=true");
        }
        Map<Integer, List<ApModeSwitch.Request>> schedules = new HashMap<>();
        for (int sfuId = 1; sfuId <= sfuCount; sfuId++) {
            String key = "sfu." + sfuId + ".wifi.ap.switch.requests";
            String value = properties.getProperty(key, "").strip();
            if (value.isEmpty()) {
                continue;
            }
            if (!modes.containsKey(sfuId)) {
                throw new IllegalArgumentException(key + " requires a configured initial AP mode");
            }
            schedules.put(sfuId, readApSwitchRequests(value, key));
        }
        boolean hasProfile = properties.containsKey(LCM_TO_HCM + "delay.us") || properties.containsKey(LCM_TO_HCM + "power.w")
                || properties.containsKey(HCM_TO_LCM + "delay.us") || properties.containsKey(HCM_TO_LCM + "power.w");
        if (schedules.isEmpty() && !hasProfile) {
            return new ApModeSwitch.Settings(schedules, null, null);
        }
        return new ApModeSwitch.Settings(schedules, readApSwitchProfile(properties, LCM_TO_HCM),
                readApSwitchProfile(properties, HCM_TO_LCM));
    }

    private static List<ApModeSwitch.Request> readApSwitchRequests(String value, String key) {
        List<ApModeSwitch.Request> requests = new ArrayList<>();
        long previousNs = -1;
        for (String entry : value.split(",", -1)) {
            String[] fields = entry.split(":", -1);
            if (fields.length != 2) {
                throw new IllegalArgumentException(key + " must list timeUs:LCM or timeUs:HCM");
            }
            long timeNs = parseDurationNs(fields[0], key, false);
            if (timeNs < previousNs) {
                throw new IllegalArgumentException(key + " must list requests in chronological order");
            }
            ApCapabilityMode target;
            try {
                target = ApCapabilityMode.valueOf(fields[1].strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(key + " targets must be LCM or HCM", e);
            }
            requests.add(new ApModeSwitch.Request(requests.size() + 1, timeNs, target));
            previousNs = timeNs;
        }
        return List.copyOf(requests);
    }

    private static ApModeSwitch.Profile readApSwitchProfile(Properties properties, String prefix) {
        String delayKey = prefix + "delay.us";
        String powerKey = prefix + "power.w";
        long delayNs = parseDurationNs(requireProperty(properties, delayKey), delayKey, true);
        requireProperty(properties, powerKey);
        return new ApModeSwitch.Profile(delayNs, readPowerWatts(properties, powerKey, null));
    }

    private static Map<Integer, WirelessPhy> readStationPhys(Properties properties, List<Integer> staSfuIds,
                                                            Map<Integer, ApCapabilityMode> apModes,
                                                            ApModeSwitch.Settings switching, ApModeController.Settings control,
                                                            boolean predictBoth,
                                                            Map<Integer, WirelessPhy> alternateStationPhys) {
        Map<Integer, WirelessPhy> stationPhys = new HashMap<>();
        for (int staIndex = 0; staIndex < staSfuIds.size(); staIndex++) {
            int staId = staIndex + 1;
            int sfuId = staSfuIds.get(staIndex);
            ApCapabilityMode mode = apModes.get(sfuId);
            String prefix = "sta." + staId + ".wifi.max.";
            String bandwidthKey = prefix + "bandwidth.mhz";
            String nssKey = prefix + "nss";
            if (mode == null) {
                if (properties.containsKey(bandwidthKey) || properties.containsKey(nssKey)) {
                    throw new IllegalArgumentException(prefix + "* requires sfu." + sfuId + ".wifi.ap.mode");
                }
                continue;
            }
            int maxBandwidthMhz = readOptionalNonNegativeInt(properties, bandwidthKey, 80);
            if (maxBandwidthMhz != 20 && maxBandwidthMhz != 40 && maxBandwidthMhz != 80 && maxBandwidthMhz != 160) {
                throw new IllegalArgumentException(bandwidthKey + " must be 20, 40, 80 or 160");
            }
            int maxNss = readOptionalNonNegativeInt(properties, nssKey, 2);
            if (maxNss < 1 || maxNss > 8) {
                throw new IllegalArgumentException(nssKey + " must be in 1..8");
            }
            // 带宽和空间流数分别取两端的能力交集；同一模型用于 STA 的两个 DATA 方向。
            stationPhys.put(staId, buildStationPhy(properties, mode, maxBandwidthMhz, maxNss));
            if (predictBoth || control.policyFor(sfuId).dynamic()
                    || (switching != null && !switching.requestsFor(sfuId).isEmpty())) {
                alternateStationPhys.put(staId, buildStationPhy(properties, ApModeSwitch.otherMode(mode),
                        maxBandwidthMhz, maxNss));
            }
        }
        return stationPhys;
    }

    private static WirelessPhy buildStationPhy(Properties properties, ApCapabilityMode mode, int bandwidthMhz, int nss) {
        return buildWirelessPhy(properties, Math.min(mode.bandwidthMhz(), bandwidthMhz),
                ApCapabilityMode.MCS, Math.min(mode.nss(), nss), ApCapabilityMode.GI_NS);
    }

    private static int readOptionalNonNegativeInt(Properties properties, String key, int defaultValue) {
        if (!properties.containsKey(key)) {
            return defaultValue;
        }
        long value = readNonNegativeLong(properties, key);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must fit in a 32-bit integer");
        }
        return (int) value;
    }

    private static DirectionMode readDirectionMode(Properties properties) {
        String direction = properties.getProperty("traffic.direction", "DL").strip();
        try {
            return DirectionMode.valueOf(direction);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("traffic.direction must be DL, UL or BOTH", e);
        }
    }

    private static void validateCompletionClock(List<Event> inputEvents, int sfuCount, List<Integer> staSfuIds,
                                                long fiberUs, SimulationConfig.PonLink ponDownlink,
                                                SimulationConfig.PonLink ponUplink, long wifiUs, WirelessPhy wirelessPhy,
                                                Map<Integer, WirelessPhy> stationPhys,
                                                Map<Integer, WirelessPhy> alternatePhys, WifiFrameExchange frameExchange,
                                                boolean needsApNanoseconds) {
        // 同一 SFU 按到达时刻累计无线工作量即可计算忙闲边界，优先级只改变其中的发送顺序。
        boolean hasUplink = inputEvents.stream().anyMatch(event -> event.packet().isUplink());
        boolean hasDownlink = inputEvents.stream().anyMatch(event -> !event.packet().isUplink());
        boolean mixed = hasUplink && hasDownlink;
        long[] completion = new long[sfuCount];
        boolean bothFixed = ponDownlink == null && ponUplink == null;
        long ticksPerUs = wirelessPhy == null && bothFixed && !needsApNanoseconds ? 1 : 1_000;
        long responseTicks = frameExchange == null ? 0 : frameExchange.responseDurationNs();
        try {
            boolean usesFixedFiber = bothFixed || (ponDownlink == null && hasDownlink) || (ponUplink == null && hasUplink);
            long fiberTicks = usesFixedFiber ? Math.multiplyExact(fiberUs, ticksPerUs) : 0;
            long fixedWifiTicks = Math.multiplyExact(wifiUs, ticksPerUs);
            ToLongFunction<Event> arrivalTicks = ponDownlink == null
                    ? event -> wifiArrivalTicks(event, ticksPerUs, fiberTicks)
                    : wifiArrivalTicksWithPon(inputEvents, ponDownlink)::get;
            List<Event> wifiArrivals = inputEvents;
            if (mixed) {
                // 上行直接进入无线队列，下行先走光路，双向模式的入队顺序可能与生成顺序不同。
                wifiArrivals = new ArrayList<>(inputEvents);
                wifiArrivals.sort(Comparator.comparingLong(arrivalTicks));
            }
            for (Event event : wifiArrivals) {
                Packet packet = event.packet();
                long uplinkSerializationNs = packet.isUplink() && ponUplink != null
                        ? validateUplinkSerialization(packet, ponUplink) : 0;
                long arrival = arrivalTicks.applyAsLong(event);
                int sfuIndex = staSfuIds.get(packet.staId() - 1) - 1;
                WirelessPhy packetPhy = stationPhys.getOrDefault(packet.staId(), wirelessPhy);
                long dataTicks = packetPhy == null ? fixedWifiTicks : packetPhy.ppduDurationNs(packet.bytes());
                WirelessPhy alternate = alternatePhys.get(packet.staId());
                if (alternate != null) {
                    dataTicks = Math.max(dataTicks, alternate.ppduDurationNs(packet.bytes()));
                }
                long exchangeTicks = Math.addExact(dataTicks, responseTicks);
                completion[sfuIndex] = Math.addExact(Math.max(arrival, completion[sfuIndex]), exchangeTicks);
                if (packet.isUplink()) {
                    if (ponUplink == null) {
                        // 固定上行保留原校验。双向时由实际选队决定光路出发顺序。
                        long dataEnd = mixed ? Math.addExact(arrival, dataTicks) : completion[sfuIndex] - responseTicks;
                        Math.addExact(dataEnd, fiberTicks);
                    } else {
                        // 实际 EDCA 与光侧排队顺序由仿真决定。先核对逐包最早可进入的帧和到达时刻。
                        long earliestDataEnd = Math.addExact(arrival, dataTicks);
                        long earliestTxStart = earliestDataEnd;
                        long framePeriodNs = ponUplink.framePeriodNs();
                        if (framePeriodNs > 0) {
                            // 即使恰好在帧边界入队，最早也要到严格晚于入队时刻的下一帧。
                            earliestTxStart = Math.addExact(earliestDataEnd, framePeriodNs - earliestDataEnd % framePeriodNs);
                            if (ponUplink.bwmapPropagationNs() >= 0) {
                                // BWmap 在调度边界下发，上行 DATA 最早从再下一帧开始发送。
                                earliestTxStart = Math.addExact(earliestTxStart, framePeriodNs);
                            }
                            Math.addExact(earliestTxStart, framePeriodNs);
                        }
                        long earliestTxEnd = Math.addExact(earliestTxStart, uplinkSerializationNs);
                        Math.addExact(earliestTxEnd, ponUplink.propagationNs());
                    }
                }
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Packet arrival time plus link delays and queueing exceeds the clock range", e);
        }
    }

    private static long validateUplinkSerialization(Packet packet, SimulationConfig.PonLink ponUplink) {
        long framePeriodNs = ponUplink.framePeriodNs();
        long serializationNs = PonLinkTiming.serializationNs(packet.bytes(), ponUplink.rateMbps());
        if (framePeriodNs > 0 && serializationNs > framePeriodNs) {
            throw new IllegalArgumentException("PON uplink packet " + packet.id() + " requires " + serializationNs
                    + " ns, exceeding frame capacity " + framePeriodNs + " ns");
        }
        return serializationNs;
    }

    private static long wifiArrivalTicks(Event event, long ticksPerUs, long fiberTicks) {
        long input = Math.multiplyExact(event.timeUs(), ticksPerUs);
        return event.packet().isUplink() ? input : Math.addExact(input, fiberTicks);
    }

    private static Map<Event, Long> wifiArrivalTicksWithPon(List<Event> inputEvents,
                                                           SimulationConfig.PonLink ponDownlink) {
        // 输入已按时刻排序，同刻保留原序。预检沿共享 FIFO 顺序推进发送完成边界。
        Map<Event, Long> arrivals = new HashMap<>();
        long availableNs = 0;
        for (Event event : inputEvents) {
            long generatedNs = Math.multiplyExact(event.timeUs(), 1_000L);
            if (event.packet().isUplink()) {
                arrivals.put(event, generatedNs);
            } else {
                long serializationNs = PonLinkTiming.serializationNs(event.packet().bytes(), ponDownlink.rateMbps());
                availableNs = Math.addExact(Math.max(generatedNs, availableNs), serializationNs);
                arrivals.put(event, Math.addExact(availableNs, ponDownlink.propagationNs()));
            }
        }
        return arrivals;
    }

    private static void validateKeys(Properties properties, int packetCount, int staCount, int sfuCount,
                                     boolean poisson, DirectionMode directionMode) {
        // 模式间的字段不能混用，防止手填报文或流量源的设置被忽略。
        for (String key : properties.stringPropertyNames()) {
            if (KEYS.contains(key) || PHY_KEYS.contains(key) || AP_QOS_AC_KEY.matcher(key).matches()
                    || EDCA_AC_KEY.matcher(key).matches()
                    || TCONT_ALLOCATION_KEY.matcher(key).matches()) {
                continue;
            }
            Matcher packet = PACKET_OVERRIDE.matcher(key);
            if (MANUAL_KEYS.contains(key) || packet.matches()) {
                if (poisson) {
                    throw new IllegalArgumentException(key + " requires traffic.mode=manual");
                }
                if (MANUAL_KEYS.contains(key)) {
                    continue;
                }
                if (parseNonNegativeLong(packet.group(1), key) > packetCount) {
                    throw new IllegalArgumentException("Packet index out of range: " + key);
                }
                continue;
            }
            Matcher station = STA_KEY.matcher(key);
            if (station.matches()) {
                if (parseNonNegativeLong(station.group(1), key) > staCount) {
                    throw new IllegalArgumentException("STA index out of range: " + key);
                }
                continue;
            }
            Matcher apMode = SFU_AP_MODE_KEY.matcher(key);
            if (!apMode.matches()) {
                apMode = SFU_AP_SWITCH_KEY.matcher(key);
            }
            if (apMode.matches()) {
                if (parseNonNegativeLong(apMode.group(1), key) > sfuCount) {
                    throw new IllegalArgumentException("SFU index out of range: " + key);
                }
                continue;
            }
            Matcher source = POISSON_SOURCE.matcher(key);
            boolean indexedSource = source.matches();
            if (key.equals("traffic.duration.us") || indexedSource) {
                if (!poisson) {
                    throw new IllegalArgumentException(key + " requires traffic.mode=poisson");
                }
                if (indexedSource) {
                    boolean uplink = source.group(1).equals("sta");
                    Packet.Direction direction = uplink ? Packet.Direction.UL : Packet.Direction.DL;
                    if (!directionMode.allows(direction)) {
                        throw new IllegalArgumentException(key + " requires traffic.direction=" + direction + " or BOTH");
                    }
                    if (parseNonNegativeLong(source.group(2), key) > (uplink ? staCount : sfuCount)) {
                        throw new IllegalArgumentException((uplink ? "STA" : "SFU") + " index out of range: " + key);
                    }
                }
                continue;
            }
            throw new IllegalArgumentException("Unknown property: " + key);
        }
    }

    private static PoissonTraffic readDownlinkSources(Properties properties, int sfuCount,
                                                      List<Integer> staSfuIds, long durationUs) {
        // 配置层把 STA–SFU 关联转换为每条下行流的候选终端，生成器只接收端点列表。
        List<List<Integer>> stationsBySfu = new ArrayList<>(sfuCount);
        for (int i = 0; i < sfuCount; i++) {
            stationsBySfu.add(new ArrayList<>());
        }
        for (int i = 0; i < staSfuIds.size(); i++) {
            stationsBySfu.get(staSfuIds.get(i) - 1).add(i + 1);
        }
        PoissonTraffic traffic = new PoissonTraffic();
        for (int sfuId = 1; sfuId <= sfuCount; sfuId++) {
            readSource(properties, traffic, Packet.Direction.DL, sfuId, durationUs, stationsBySfu.get(sfuId - 1), sfuId);
        }
        return traffic;
    }

    private static PoissonTraffic readUplinkSources(Properties properties, int staCount, long durationUs,
                                                    int downlinkOwnerCount) {
        PoissonTraffic traffic = new PoissonTraffic();
        for (int staId = 1; staId <= staCount; staId++) {
            readSource(properties, traffic, Packet.Direction.UL, staId, durationUs, List.of(Packet.DEFAULT_MFU_ID),
                    (long) downlinkOwnerCount + staId);
        }
        return traffic;
    }

    private static void readSource(Properties properties, PoissonTraffic traffic, Packet.Direction direction,
                                   int ownerId, long durationUs, List<Integer> destinations, long legacyStreamKey) {
        boolean uplink = direction == Packet.Direction.UL;
        String prefix = (uplink ? "traffic.sta." : "traffic.sfu.") + ownerId;
        String durationKey = prefix + ".duration.us";
        long sourceDurationUs = properties.containsKey(durationKey)
                ? readPositiveDuration(properties, durationKey) : durationUs;
        if (sourceDurationUs > durationUs) {
            throw new IllegalArgumentException(durationKey + " must not exceed traffic.duration.us");
        }
        traffic.windowsUs.add(sourceDurationUs);
        int sourceId = uplink ? ownerId : Packet.DEFAULT_MFU_ID;
        if (hasClassSources(properties, prefix)) {
            for (String suffix : List.of(".rate.pps", ".packet.bytes", ".class.weights")) {
                if (properties.containsKey(prefix + suffix)) {
                    throw new IllegalArgumentException(prefix + suffix + " cannot be combined with per-class traffic");
                }
            }
            for (AccessCategory category : CATEGORIES) {
                double[] weights = new double[CATEGORIES.length];
                weights[category.ordinal()] = 1.0;
                // 类别的随机流标识由方向、设备 ID、类别确定，避免其他流的增减改写随机序列。
                long streamKey = (1L << 40) | ((long) ownerId << 3) | ((uplink ? 1L : 0L) << 2) | category.ordinal();
                addStream(properties, traffic, prefix, prefix + ".class." + category, sourceDurationUs,
                        direction, sourceId, destinations, weights, streamKey);
            }
        } else {
            addStream(properties, traffic, prefix, prefix, sourceDurationUs, direction, sourceId, destinations,
                    readClassWeights(properties, prefix + ".class.weights"), legacyStreamKey);
        }
    }

    private static boolean hasClassSources(Properties properties, String prefix) {
        for (AccessCategory category : CATEGORIES) {
            String key = prefix + ".class." + category;
            if (properties.containsKey(key + ".rate.pps") || properties.containsKey(key + ".rate.mbps")
                    || properties.containsKey(key + ".packet.bytes")) {
                return true;
            }
        }
        return false;
    }

    private static void addStream(Properties properties, PoissonTraffic traffic, String ownerPrefix, String streamPrefix,
                                  long durationUs, Packet.Direction direction, int sourceId, List<Integer> destinations,
                                  double[] weights, long streamKey) {
        String ppsKey = streamPrefix + ".rate.pps";
        String mbpsKey = streamPrefix + ".rate.mbps";
        boolean rateInMbps = properties.containsKey(mbpsKey);
        if (rateInMbps && properties.containsKey(ppsKey)) {
            throw new IllegalArgumentException("Configure only one of " + ppsKey + " and " + mbpsKey);
        }
        String rateKey = rateInMbps ? mbpsKey : ppsKey;
        double rate = parseNonNegativeDouble(properties.getProperty(rateKey, "0"), rateKey);
        String bytesKey = streamPrefix + ".packet.bytes";
        int bytes = rate > 0.0 || properties.containsKey(bytesKey) ? readPositiveInt(properties, bytesKey) : 0;
        String seedKey = ownerPrefix + ".seed";
        long seed = rate > 0.0 || properties.containsKey(seedKey) ? readSeed(properties, seedKey) : 0;
        if (rate == 0.0) {
            return;
        }
        double ratePps = rateInMbps ? rate * (1_000_000.0 / (8.0 * bytes)) : rate;
        if (!Double.isFinite(ratePps) || ratePps == 0.0) {
            throw new IllegalArgumentException(rateKey + " cannot be represented as packets per second");
        }
        if (destinations.isEmpty()) {
            throw new IllegalArgumentException(rateKey + " is positive but the SFU has no associated STA");
        }
        traffic.streams.add(new TrafficGenerator.Source(direction, sourceId, destinations, ratePps, bytes, seed,
                durationUs, weights, streamKey));
    }

    private static double[] readClassWeights(Properties properties, String key) {
        String[] values = properties.getProperty(key, "0,0,1,0").split(",", -1);
        if (values.length != AccessCategory.values().length) {
            throw new IllegalArgumentException(key + " must list four weights in VO, VI, BE, BK order");
        }
        double[] weights = new double[values.length];
        boolean hasPositiveWeight = false;
        for (int i = 0; i < values.length; i++) {
            weights[i] = parseNonNegativeDouble(values[i], key);
            hasPositiveWeight |= weights[i] > 0.0;
        }
        if (!hasPositiveWeight) {
            throw new IllegalArgumentException(key + " must have at least one positive weight");
        }
        return weights;
    }

    private static double parseNonNegativeDouble(String value, String key) {
        try {
            double number = Double.parseDouble(value.strip());
            if (Double.isFinite(number) && number >= 0.0) {
                return number;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a finite non-negative number", e);
        }
        throw new IllegalArgumentException(key + " must be a finite non-negative number");
    }

    private static long readPositiveDuration(Properties properties, String key) {
        long duration = readNonNegativeLong(properties, key);
        if (duration == 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return duration;
    }

    private static long readSeed(Properties properties, String key) {
        String value = requireProperty(properties, key);
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a signed 64-bit integer", e);
        }
    }

    private static List<Integer> readStaAssociations(Properties properties, int staCount, int sfuCount) {
        List<Integer> staSfuIds = new ArrayList<>(staCount);
        for (int i = 0; i < staCount; i++) {
            String key = "sta." + (i + 1) + ".sfu.id";
            int sfuId = properties.containsKey(key) ? readPositiveInt(properties, key) : 1;
            if (sfuId > sfuCount) {
                throw new IllegalArgumentException(key + " must refer to a configured SFU (1 to " + sfuCount + ")");
            }
            staSfuIds.add(sfuId);
        }
        return staSfuIds;
    }

    private static List<Event> readManualEvents(Properties properties, List<Long> arrivalsUs, int staCount,
                                                DirectionMode directionMode) {
        // 覆盖项对应原始列表位置。报文 ID 仍按时刻编号，稳定排序保留同刻配置顺序。
        List<Integer> positions = new ArrayList<>(arrivalsUs.size());
        for (int i = 0; i < arrivalsUs.size(); i++) {
            positions.add(i);
        }
        positions.sort(Comparator.comparingLong(arrivalsUs::get));

        int defaultBytes = properties.containsKey("packet.bytes") ? readPositiveInt(properties, "packet.bytes") : 0;
        List<Event> events = new ArrayList<>(arrivalsUs.size());
        for (int position : positions) {
            String prefix = "packet." + (position + 1);
            String bytesKey = prefix + ".bytes";
            int bytes = properties.containsKey(bytesKey) || defaultBytes == 0
                    ? readPositiveInt(properties, bytesKey) : defaultBytes;
            String typeKey = prefix + ".type";
            AccessCategory type = AccessCategory.BE;
            if (properties.containsKey(typeKey)) {
                try {
                    type = AccessCategory.valueOf(properties.getProperty(typeKey).strip());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(typeKey + " must be VO, VI, BE or BK", e);
                }
            }
            String staKey = prefix + ".sta.id";
            int staId = properties.containsKey(staKey) ? readPositiveInt(properties, staKey) : 1;
            if (staId > staCount) {
                throw new IllegalArgumentException(staKey + " must refer to a configured STA (1 to " + staCount + ")");
            }
            Packet.Direction direction = readPacketDirection(properties, prefix + ".direction", directionMode);
            boolean uplink = direction == Packet.Direction.UL;
            // 手动场景的 STA ID 在下行中指目的终端，在上行中指源终端。
            Packet packet = new Packet(events.size() + 1, bytes, arrivalsUs.get(position), type, direction,
                    uplink ? staId : Packet.DEFAULT_MFU_ID, uplink ? Packet.DEFAULT_MFU_ID : staId);
            events.add(Event.packetGenerated(packet));
        }
        return events;
    }

    private static Packet.Direction readPacketDirection(Properties properties, String key, DirectionMode directionMode) {
        if (directionMode != DirectionMode.BOTH && !properties.containsKey(key)) {
            return directionMode == DirectionMode.UL ? Packet.Direction.UL : Packet.Direction.DL;
        }
        String value = requireProperty(properties, key).strip();
        Packet.Direction direction;
        try {
            direction = Packet.Direction.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + " must be DL or UL", e);
        }
        if (!directionMode.allows(direction)) {
            throw new IllegalArgumentException(key + " conflicts with traffic.direction=" + directionMode);
        }
        return direction;
    }

    private static int readPositiveInt(Properties properties, String key) {
        long number = readNonNegativeLong(properties, key);
        if (number == 0 || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must be between 1 and " + Integer.MAX_VALUE);
        }
        return (int) number;
    }

    private static List<Long> readArrivals(Properties properties) {
        String key = "packet.arrival.us";
        String[] values = requireProperty(properties, key).split(",", -1);
        List<Long> arrivalsUs = new ArrayList<>(values.length);
        for (String value : values) {
            arrivalsUs.add(parseNonNegativeLong(value, key));
        }
        return arrivalsUs;
    }

    private static long readNonNegativeLong(Properties properties, String key) {
        return parseNonNegativeLong(requireProperty(properties, key), key);
    }

    private static String requireProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing property: " + key);
        }
        return value;
    }

    private static long parseNonNegativeLong(String value, String key) {
        try {
            long number = Long.parseLong(value.strip());
            if (number >= 0) {
                return number;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a non-negative integer", e);
        }
        throw new IllegalArgumentException(key + " must be a non-negative integer");
    }
}
