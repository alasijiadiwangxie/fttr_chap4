package edu.suda.fttr.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** 读取并校验完整的场景配置，再开始处理仿真事件。 */
public final class ConfigLoader {
    private static final String DEFAULT_SCENARIO = "/phase1-minimal-downlink.properties";
    private static final Set<String> KEYS = Set.of(
            "packet.bytes", "packet.arrival.us", "fiber.delay.us", "wifi.delay.us");

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

        // 拼错的配置项应立即报错，以免仿真在错误参数下继续运行。
        for (String key : properties.stringPropertyNames()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown property: " + key);
            }
        }

        long bytes = readNonNegativeLong(properties, "packet.bytes");
        if (bytes == 0 || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("packet.bytes must be between 1 and " + Integer.MAX_VALUE);
        }
        List<Long> arrivalsUs = readArrivals(properties);
        long fiberUs = readNonNegativeLong(properties, "fiber.delay.us");
        long wifiUs = readNonNegativeLong(properties, "wifi.delay.us");

        // 以 FIFO 的最早完成时刻预检时钟范围。即使每条报文单独能发完，积压也可能越界。
        try {
            long previousCompletionUs = 0;
            for (long arrivalUs : arrivalsUs) {
                long sfuArrivalUs = Math.addExact(arrivalUs, fiberUs);
                previousCompletionUs = Math.addExact(Math.max(sfuArrivalUs, previousCompletionUs), wifiUs);
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Packet arrival time plus link delays and queueing exceeds the clock range", e);
        }
        return new SimulationConfig((int) bytes, arrivalsUs, fiberUs, wifiUs);
    }

    private static List<Long> readArrivals(Properties properties) {
        String key = "packet.arrival.us";
        String[] values = requireProperty(properties, key).split(",", -1);
        List<Long> arrivalsUs = new ArrayList<>(values.length);
        for (String value : values) {
            arrivalsUs.add(parseNonNegativeLong(value, key));
        }
        // 按时刻分配报文 ID，并保留同刻报文在配置中的先后顺序。
        arrivalsUs.sort(null);
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
