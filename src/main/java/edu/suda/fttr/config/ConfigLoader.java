package edu.suda.fttr.config;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.link.WifiFrameExchange;
import edu.suda.fttr.mac.EdcaParameters;
import edu.suda.fttr.mac.EdcaParameters.AcParameters;
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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取并校验完整的场景配置，再开始处理仿真事件。 */
public final class ConfigLoader {
    private static final String DEFAULT_SCENARIO = "/phase1-minimal-downlink.properties";
    private static final String MAC_EXCHANGE = "wifi.mac.exchange";
    private static final String MAC_ACCESS = "wifi.mac.access";
    private static final String MAC_SIFS_US = "wifi.mac.sifs.us";
    private static final String MAC_SLOT_US = "wifi.mac.slot.us";
    private static final String MAC_ACK_RATE_MBPS = "wifi.mac.ack.rate.mbps";
    private static final String EDCA_SEED = "wifi.mac.edca.seed";
    private static final Set<String> KEYS = Set.of(
            "fiber.delay.us", "wifi.delay.us", "wifi.model", "sta.count", "sfu.count", "traffic.mode", "traffic.direction",
            MAC_EXCHANGE, MAC_ACCESS, MAC_SIFS_US, MAC_SLOT_US, MAC_ACK_RATE_MBPS, EDCA_SEED);
    private static final Set<String> PHY_KEYS = Set.of(
            "wifi.phy.bandwidth.mhz", "wifi.phy.mcs", "wifi.phy.nss", "wifi.phy.gi.us",
            "wifi.phy.mac.overhead.bytes", "wifi.phy.he-ltf.type", "wifi.phy.nominal.packet.padding.us");
    private static final Set<String> MANUAL_KEYS = Set.of("packet.bytes", "packet.arrival.us");
    private static final Pattern PACKET_OVERRIDE = Pattern.compile("packet\\.([1-9][0-9]*)\\.(bytes|type|sta\\.id|direction)");
    private static final Pattern STA_ASSOCIATION = Pattern.compile("sta\\.([1-9][0-9]*)\\.sfu\\.id");
    private static final Pattern EDCA_AC_KEY = Pattern.compile("wifi\\.mac\\.edca\\.(VO|VI|BE|BK)\\.(aifsn|cwmin|cwmax)");
    private static final Pattern POISSON_SOURCE = Pattern.compile(
            "traffic\\.(sfu|sta)\\.([1-9][0-9]*)\\.(rate\\.pps|packet\\.bytes|seed|class\\.weights|duration\\.us|"
                    + "class\\.(VO|VI|BE|BK)\\.(rate\\.pps|rate\\.mbps|packet\\.bytes))");
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
        long fiberUs = readNonNegativeLong(properties, "fiber.delay.us");
        WirelessPhy wirelessPhy = readWirelessPhy(properties);
        EdcaParameters edcaParameters = readEdcaParameters(properties);
        boolean edcaCountdown = readEdcaCountdown(properties);
        WifiFrameExchange frameExchange = readWifiFrameExchange(properties, wirelessPhy, edcaParameters.sifsUs());
        long wifiUs = wirelessPhy == null ? readNonNegativeLong(properties, "wifi.delay.us") : 0;
        long durationUs = poisson ? readPositiveDuration(properties, "traffic.duration.us") : 0;
        PoissonTraffic downlink = poisson && directionMode.allows(Packet.Direction.DL)
                ? readDownlinkSources(properties, sfuCount, staSfuIds, durationUs) : new PoissonTraffic();
        PoissonTraffic uplink = poisson && directionMode.allows(Packet.Direction.UL)
                ? readUplinkSources(properties, staCount, durationUs, downlink.windowsUs.size()) : new PoissonTraffic();
        List<TrafficGenerator.Source> sources = new ArrayList<>(downlink.streams);
        sources.addAll(uplink.streams);
        List<Event> inputEvents = poisson
                ? TrafficGenerator.generate(sources)
                : readManualEvents(properties, arrivalsUs, staCount, directionMode);

        validateCompletionClock(inputEvents, sfuCount, staSfuIds, fiberUs, wifiUs, wirelessPhy, frameExchange);
        return new SimulationConfig(inputEvents, sfuCount, staSfuIds, fiberUs, wifiUs, wirelessPhy, frameExchange,
                edcaParameters, edcaCountdown, downlink.windowsUs, uplink.windowsUs, directionMode.allows(Packet.Direction.UL));
    }

    private static boolean readEdcaCountdown(Properties properties) {
        String access = properties.getProperty(MAC_ACCESS, "fixed-priority").strip();
        switch (access) {
            case "fixed-priority": return false;
            case "edca-countdown": return true;
            default: throw new IllegalArgumentException(MAC_ACCESS + " must be fixed-priority or edca-countdown");
        }
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
            try {
                byCategory.put(category, new AcParameters(aifsn, cwMin, cwMax));
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
        return new WirelessPhy(readOptionalNonNegativeInt(properties, "wifi.phy.bandwidth.mhz", 160),
                readOptionalNonNegativeInt(properties, "wifi.phy.mcs", 11),
                readOptionalNonNegativeInt(properties, "wifi.phy.nss", 2), giNs,
                readOptionalNonNegativeInt(properties, "wifi.phy.mac.overhead.bytes", 34),
                readOptionalNonNegativeInt(properties, "wifi.phy.he-ltf.type", WirelessPhy.defaultHeLtfType(giNs)),
                readOptionalNonNegativeInt(properties, "wifi.phy.nominal.packet.padding.us", 0));
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
                                                long fiberUs, long wifiUs, WirelessPhy wirelessPhy,
                                                WifiFrameExchange frameExchange) {
        // 同一 SFU 按到达时刻累计无线工作量即可计算忙闲边界，优先级只改变其中的发送顺序。
        boolean mixed = inputEvents.stream().anyMatch(event -> event.packet().direction() == Packet.Direction.UL)
                && inputEvents.stream().anyMatch(event -> event.packet().direction() == Packet.Direction.DL);
        long[] completion = new long[sfuCount];
        long ticksPerUs = wirelessPhy == null ? 1 : 1000;
        long responseTicks = frameExchange == null ? 0 : frameExchange.responseDurationNs();
        try {
            long fiberTicks = Math.multiplyExact(fiberUs, ticksPerUs);
            List<Event> wifiArrivals = inputEvents;
            if (mixed) {
                // 上行直接进入无线队列，下行先走光路，双向模式的入队顺序可能与生成顺序不同。
                wifiArrivals = new ArrayList<>(inputEvents);
                wifiArrivals.sort(Comparator.comparingLong(event -> wifiArrivalTicks(event, ticksPerUs, fiberTicks)));
            }
            for (Event event : wifiArrivals) {
                Packet packet = event.packet();
                long arrival = wifiArrivalTicks(event, ticksPerUs, fiberTicks);
                int sfuIndex = staSfuIds.get(packet.staId() - 1) - 1;
                long dataTicks = wirelessPhy == null ? wifiUs : wirelessPhy.ppduDurationNs(packet.bytes());
                long exchangeTicks = Math.addExact(dataTicks, responseTicks);
                completion[sfuIndex] = Math.addExact(Math.max(arrival, completion[sfuIndex]), exchangeTicks);
                if (packet.isUplink()) {
                    // 光路从 DATA 结束起算。纯上行可由资源释放时刻减去 SIFS 和 ACK 得到上界。
                    // 双向时上行顺序由实际选队决定，此处只检查最早出发的光路时刻，运行时再校验实际时刻。
                    long dataEnd = mixed ? Math.addExact(arrival, dataTicks) : completion[sfuIndex] - responseTicks;
                    Math.addExact(dataEnd, fiberTicks);
                }
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Packet arrival time plus link delays and queueing exceeds the clock range", e);
        }
    }

    private static long wifiArrivalTicks(Event event, long ticksPerUs, long fiberTicks) {
        long input = Math.multiplyExact(event.timeUs(), ticksPerUs);
        return event.packet().isUplink() ? input : Math.addExact(input, fiberTicks);
    }

    private static void validateKeys(Properties properties, int packetCount, int staCount, int sfuCount,
                                     boolean poisson, DirectionMode directionMode) {
        // 模式间的字段不能混用，防止手填报文或流量源的设置被忽略。
        for (String key : properties.stringPropertyNames()) {
            if (KEYS.contains(key) || PHY_KEYS.contains(key) || EDCA_AC_KEY.matcher(key).matches()) {
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
            Matcher station = STA_ASSOCIATION.matcher(key);
            if (station.matches()) {
                if (parseNonNegativeLong(station.group(1), key) > staCount) {
                    throw new IllegalArgumentException("STA index out of range: " + key);
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
