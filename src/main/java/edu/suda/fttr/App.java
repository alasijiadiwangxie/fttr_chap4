package edu.suda.fttr;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.config.SimulationConfig;
import edu.suda.fttr.simulator.Simulator;
import edu.suda.fttr.trace.TraceLogger;

import java.io.IOException;
import java.nio.file.Path;

/** 加载一份场景配置，运行一次下行仿真。 */
public final class App {
    private static final String USAGE = "Usage: java -jar fttr-chap4-0.1.0-SNAPSHOT.jar [scenario.properties]";

    private App() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println(USAGE);
            return;
        }
        if (args.length > 1) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        try {
            // 默认场景随 JAR 打包。传入路径时，读取该文件作为完整场景。
            SimulationConfig config = args.length == 0
                    ? ConfigLoader.loadDefault()
                    : ConfigLoader.load(Path.of(args[0]));
            Simulator.run(config, new TraceLogger(System.out));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(2);
        }
    }
}
