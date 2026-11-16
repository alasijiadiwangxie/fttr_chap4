package edu.suda.fttr.traffic;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.SplittableRandom;

/** 为每台 SFU 独立生成泊松下行业务，输出按生成时刻排列的 MFU 输入报文。 */
public final class TrafficGenerator {
    private static final double MICROS_PER_SECOND = 1_000_000.0;
    private static final AccessCategory[] CATEGORIES = AccessCategory.values();

    private TrafficGenerator() {
    }

    public static List<Packet> generate(List<Source> sources, List<Integer> staSfuIds) {
        List<List<Integer>> stationsBySfu = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            stationsBySfu.add(new ArrayList<>());
        }
        for (int i = 0; i < staSfuIds.size(); i++) {
            stationsBySfu.get(staSfuIds.get(i) - 1).add(i + 1);
        }

        // 各源已经按时间生成。归并时每个源只保留一个待输出时刻，同一微秒按 SFU ID 排序。
        PriorityQueue<ArrivalStream> nextArrivals = new PriorityQueue<>(
                Comparator.comparingLong(ArrivalStream::timeUs).thenComparingInt(stream -> stream.sfuId));
        double expectedPackets = 0.0;
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            if (source.ratePps == 0.0) {
                continue;
            }
            List<Integer> stations = stationsBySfu.get(i);
            int sfuId = i + 1;
            if (stations.isEmpty()) {
                throw new IllegalArgumentException("SFU " + sfuId + " has positive traffic rate but no associated STA");
            }
            expectedPackets += source.ratePps * (source.durationUs / MICROS_PER_SECOND);
            if (expectedPackets > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Expected Poisson packet count exceeds the packet ID range");
            }
            ArrivalStream stream = new ArrivalStream(sfuId, source, stations);
            if (stream.advance()) {
                nextArrivals.add(stream);
            }
        }

        List<Packet> packets = new ArrayList<>();
        while (!nextArrivals.isEmpty()) {
            ArrivalStream stream = nextArrivals.remove();
            if (packets.size() == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Poisson packet count exceeds the packet ID range");
            }
            packets.add(stream.packet(packets.size() + 1));
            if (stream.advance()) {
                nextArrivals.add(stream);
            }
        }
        return packets;
    }

    /** 单台 SFU 的已校验输入。业务权重按 VO、VI、BE、BK 排列。 */
    public static final class Source {
        private final double ratePps;
        private final int packetBytes;
        private final long seed;
        private final long durationUs;
        private final double[] cumulativeWeights;

        public Source(double ratePps, int packetBytes, long seed, long durationUs, double[] classWeights) {
            this.ratePps = ratePps;
            this.packetBytes = packetBytes;
            this.seed = seed;
            this.durationUs = durationUs;

            // 先按最大权重缩放，避免合法权重相加超出浮点数范围。
            double largestWeight = 0.0;
            for (double weight : classWeights) {
                largestWeight = Math.max(largestWeight, weight);
            }
            cumulativeWeights = new double[classWeights.length];
            double sum = 0.0;
            for (int i = 0; i < classWeights.length; i++) {
                sum += classWeights[i] / largestWeight;
                cumulativeWeights[i] = sum;
            }
        }

        public long durationUs() {
            return durationUs;
        }
    }

    private static final class ArrivalStream {
        private final int sfuId;
        private final Source source;
        private final List<Integer> stations;
        private final SplittableRandom arrivalRandom;
        private final SplittableRandom packetRandom;
        private double arrivalUs;

        private ArrivalStream(int sfuId, Source source, List<Integer> stations) {
            this.sfuId = sfuId;
            this.source = source;
            this.stations = stations;
            // 混入 SFU ID，确保同一配置种子可用于多台 SFU。到达时刻与报文属性使用各自的随机流。
            SplittableRandom random = new SplittableRandom(source.seed ^ (0x9E3779B97F4A7C15L * sfuId));
            arrivalRandom = random.split();
            packetRandom = random.split();
        }

        private boolean advance() {
            // 逆变换采样指数间隔。累计连续时刻后才取整，避免逐个取整使负载产生系统偏差。
            double u;
            do {
                u = arrivalRandom.nextDouble();
            } while (u == 0.0);
            arrivalUs += -StrictMath.log1p(-u) * MICROS_PER_SECOND / source.ratePps;
            return timeUs() < source.durationUs;
        }

        private long timeUs() {
            return (long) arrivalUs;
        }

        private Packet packet(int id) {
            int staId = stations.get(packetRandom.nextInt(stations.size()));
            double[] weights = source.cumulativeWeights;
            double draw = packetRandom.nextDouble(weights[weights.length - 1]);
            int index = 0;
            while (draw >= weights[index]) {
                index++;
            }
            // 生成时刻就是进入 MFU 的时刻。目标 STA 的关联关系由 MFU 的原有转发机制查询。
            return new Packet(id, source.packetBytes, timeUs(), CATEGORIES[index], staId);
        }
    }
}
