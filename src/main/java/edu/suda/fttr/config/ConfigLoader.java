package edu.suda.fttr.config;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.Event;
import edu.suda.fttr.traffic.TrafficGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取并校验完整的场景配置，再开始处理仿真事件。 */
public final class ConfigLoader {
    private static final String DEFAULT_SCENARIO = "/phase1-minimal-downlink.properties";
    private static final Set<String> KEYS = Set.of(
            "fiber.delay.us", "wifi.delay.us", "sta.count", "sfu.count", "traffic.mode", "traffic.direction");
    private static final Set<String> MANUAL_KEYS = Set.of("packet.bytes", "packet.arrival.us");
    private static final Pattern PACKET_OVERRIDE = Pattern.compile("packet\\.([1-9][0-9]*)\\.(bytes|type|sta\\.id)");
    private static final Pattern STA_ASSOCIATION = Pattern.compile("sta\\.([1-9][0-9]*)\\.sfu\\.id");
    private static final Pattern POISSON_SOURCE = Pattern.compile(
            "traffic\\.sfu\\.([1-9][0-9]*)\\.(rate\\.pps|packet\\.bytes|seed|class\\.weights|duration\\.us)");

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
        validateTrafficDirection(properties);

        String mode = properties.getProperty("traffic.mode", "manual").strip();
        if (!mode.equals("manual") && !mode.equals("poisson")) {
            throw new IllegalArgumentException("traffic.mode must be manual or poisson");
        }
        boolean poisson = mode.equals("poisson");
        List<Long> arrivalsUs = poisson ? List.of() : readArrivals(properties);
        int staCount = properties.containsKey("sta.count") ? readPositiveInt(properties, "sta.count") : 1;
        int sfuCount = properties.containsKey("sfu.count") ? readPositiveInt(properties, "sfu.count") : 1;
        validateKeys(properties, arrivalsUs.size(), staCount, sfuCount, poisson);
        List<Integer> staSfuIds = readStaAssociations(properties, staCount, sfuCount);
        long fiberUs = readNonNegativeLong(properties, "fiber.delay.us");
        long wifiUs = readNonNegativeLong(properties, "wifi.delay.us");
        List<TrafficGenerator.Source> sources = poisson ? readDownlinkSources(properties, sfuCount, staSfuIds) : List.of();
        List<Event> inputEvents = poisson
                ? TrafficGenerator.generate(sources)
                : readManualEvents(properties, arrivalsUs, staCount);

        validateCompletionClock(inputEvents, sfuCount, staSfuIds, fiberUs, wifiUs);
        return new SimulationConfig(inputEvents, sfuCount, staSfuIds, fiberUs, wifiUs, sources);
    }

    private static void validateTrafficDirection(Properties properties) {
        String direction = properties.getProperty("traffic.direction", "DL").strip();
        if (direction.equals("UL")) {
            throw new IllegalArgumentException("Uplink traffic (UL) is not supported: traffic.direction");
        }
        if (!direction.equals("DL")) {
            throw new IllegalArgumentException("traffic.direction must be DL or UL");
        }
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("traffic.sta.")) {
                throw new IllegalArgumentException("Uplink traffic (UL) is not supported: " + key);
            }
        }
    }

    private static void validateCompletionClock(List<Event> inputEvents, int sfuCount, List<Integer> staSfuIds,
                                                long fiberUs, long wifiUs) {
        // 每台 SFU 独立预检最后完成时刻。固定服务时长下，优先级不改变同一台 SFU 的忙闲边界。
        long[] completionUs = new long[sfuCount];
        try {
            for (Event event : inputEvents) {
                long sfuArrivalUs = Math.addExact(event.timeUs(), fiberUs);
                int sfuIndex = staSfuIds.get(event.packet().destinationStaId() - 1) - 1;
                completionUs[sfuIndex] = Math.addExact(Math.max(sfuArrivalUs, completionUs[sfuIndex]), wifiUs);
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Packet arrival time plus link delays and queueing exceeds the clock range", e);
        }
    }

    private static void validateKeys(Properties properties, int packetCount, int staCount, int sfuCount,
                                     boolean poisson) {
        // 模式间的字段不能混用，防止手填报文或流量源的设置被忽略。
        for (String key : properties.stringPropertyNames()) {
            if (KEYS.contains(key)) {
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
            if (key.equals("traffic.duration.us") || source.matches()) {
                if (!poisson) {
                    throw new IllegalArgumentException(key + " requires traffic.mode=poisson");
                }
                if (!key.equals("traffic.duration.us") && parseNonNegativeLong(source.group(1), key) > sfuCount) {
                    throw new IllegalArgumentException("SFU index out of range: " + key);
                }
                continue;
            }
            throw new IllegalArgumentException("Unknown property: " + key);
        }
    }

    private static List<TrafficGenerator.Source> readDownlinkSources(Properties properties, int sfuCount,
                                                                    List<Integer> staSfuIds) {
        long durationUs = readPositiveDuration(properties, "traffic.duration.us");
        // 配置层把 STA–SFU 关联转换为每条下行流的候选终端，生成器只接收端点列表。
        List<List<Integer>> stationsBySfu = new ArrayList<>(sfuCount);
        for (int i = 0; i < sfuCount; i++) {
            stationsBySfu.add(new ArrayList<>());
        }
        for (int i = 0; i < staSfuIds.size(); i++) {
            stationsBySfu.get(staSfuIds.get(i) - 1).add(i + 1);
        }
        List<TrafficGenerator.Source> sources = new ArrayList<>(sfuCount);
        for (int i = 0; i < sfuCount; i++) {
            int sfuId = i + 1;
            String prefix = "traffic.sfu." + sfuId;
            String rateKey = prefix + ".rate.pps";
            double ratePps = parseNonNegativeDouble(properties.getProperty(rateKey, "0"), rateKey);
            String bytesKey = prefix + ".packet.bytes";
            int bytes = ratePps > 0.0 || properties.containsKey(bytesKey) ? readPositiveInt(properties, bytesKey) : 0;
            String seedKey = prefix + ".seed";
            long seed = ratePps > 0.0 || properties.containsKey(seedKey) ? readSeed(properties, seedKey) : 0;
            String durationKey = prefix + ".duration.us";
            long sourceDurationUs = properties.containsKey(durationKey)
                    ? readPositiveDuration(properties, durationKey) : durationUs;
            if (sourceDurationUs > durationUs) {
                throw new IllegalArgumentException(durationKey + " must not exceed traffic.duration.us");
            }
            double[] weights = readClassWeights(properties, prefix + ".class.weights");
            List<Integer> destinations = stationsBySfu.get(i);
            if (ratePps > 0.0 && destinations.isEmpty()) {
                throw new IllegalArgumentException("SFU " + sfuId + " has positive traffic rate but no associated STA");
            }
            sources.add(new TrafficGenerator.Source(Packet.Direction.DL, Packet.DEFAULT_MFU_ID, destinations,
                    ratePps, bytes, seed, sourceDurationUs, weights));
        }
        return sources;
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

    private static List<Event> readManualEvents(Properties properties, List<Long> arrivalsUs, int staCount) {
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
            Packet packet = new Packet(events.size() + 1, bytes, arrivalsUs.get(position), type, staId);
            events.add(Event.packetGenerated(packet));
        }
        return events;
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
