package edu.suda.fttr.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        long arrivalUs = readNonNegativeLong(properties, "packet.arrival.us");
        long fiberUs = readNonNegativeLong(properties, "fiber.delay.us");
        long wifiUs = readNonNegativeLong(properties, "wifi.delay.us");

        // 提前检查最终到达时刻，确保输入越界时不会留下部分仿真记录。
        try {
            Math.addExact(Math.addExact(arrivalUs, fiberUs), wifiUs);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Packet arrival time plus link delays exceeds the clock range", e);
        }
        return new SimulationConfig((int) bytes, arrivalUs, fiberUs, wifiUs);
    }

    private static long readNonNegativeLong(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing property: " + key);
        }
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
