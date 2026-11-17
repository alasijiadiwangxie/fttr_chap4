package edu.suda.fttr.traffic;

import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Packet;
import edu.suda.fttr.simulator.Event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.SplittableRandom;

/** 独立采样各泊松流并生成入口事件。源与候选目的节点由场景提供。 */
public final class TrafficGenerator {
    private static final double MICROS_PER_SECOND = 1_000_000.0;
    private static final AccessCategory[] CATEGORIES = AccessCategory.values();

    private TrafficGenerator() {
    }

    public static List<Event> generate(List<Source> sources) {
        // 归并时每个流只保留一个待输出时刻，同一微秒按流在输入列表中的顺序排序。
        PriorityQueue<ArrivalStream> nextArrivals = new PriorityQueue<>(
                Comparator.comparingLong(ArrivalStream::timeUs).thenComparingInt(stream -> stream.streamId));
        double expectedPackets = 0.0;
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            if (source.ratePps == 0.0) {
                continue;
            }
            expectedPackets += source.ratePps * (source.durationUs / MICROS_PER_SECOND);
            if (expectedPackets > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Expected Poisson packet count exceeds the packet ID range");
            }
            ArrivalStream stream = new ArrivalStream(i + 1, source);
            if (stream.advance()) {
                nextArrivals.add(stream);
            }
        }

        List<Event> events = new ArrayList<>();
        while (!nextArrivals.isEmpty()) {
            ArrivalStream stream = nextArrivals.remove();
            if (events.size() == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Poisson packet count exceeds the packet ID range");
            }
            events.add(Event.packetGenerated(stream.packet(events.size() + 1)));
            if (stream.advance()) {
                nextArrivals.add(stream);
            }
        }
        return events;
    }

    /** 一条泊松流的端点与参数。业务权重按 VO、VI、BE、BK 排列。 */
    public static final class Source {
        private final Packet.Direction direction;
        private final int sourceNodeId;
        private final List<Integer> destinationNodeIds;
        private final double ratePps;
        private final int packetBytes;
        private final long seed;
        private final long durationUs;
        private final double[] cumulativeWeights;
        private final long streamKey;

        public Source(Packet.Direction direction, int sourceNodeId, List<Integer> destinationNodeIds,
                      double ratePps, int packetBytes, long seed, long durationUs, double[] classWeights) {
            this(direction, sourceNodeId, destinationNodeIds, ratePps, packetBytes, seed, durationUs, classWeights, 0);
        }

        /** 显式流标识用于稳定地派生随机序列。0 表示按列表顺序分配标识。 */
        public Source(Packet.Direction direction, int sourceNodeId, List<Integer> destinationNodeIds,
                      double ratePps, int packetBytes, long seed, long durationUs, double[] classWeights, long streamKey) {
            if (!Double.isFinite(ratePps) || ratePps < 0.0 || durationUs <= 0 || packetBytes < 0) {
                throw new IllegalArgumentException("Invalid Poisson rate, duration or packet size");
            }
            if (ratePps > 0.0 && (packetBytes == 0 || destinationNodeIds.isEmpty())) {
                throw new IllegalArgumentException("Active traffic source requires a positive packet size and a destination");
            }
            if (sourceNodeId < 1 || destinationNodeIds.stream().anyMatch(id -> id < 1)) {
                throw new IllegalArgumentException("Source and destination node IDs must be positive");
            }
            if (classWeights.length != CATEGORIES.length) {
                throw new IllegalArgumentException("Expected one weight per access category");
            }
            this.direction = Objects.requireNonNull(direction);
            this.sourceNodeId = sourceNodeId;
            this.destinationNodeIds = List.copyOf(destinationNodeIds);
            this.ratePps = ratePps;
            this.packetBytes = packetBytes;
            this.seed = seed;
            this.durationUs = durationUs;
            this.streamKey = streamKey;

            // 先按最大权重缩放，避免合法权重相加超出浮点数范围。
            double largestWeight = 0.0;
            for (double weight : classWeights) {
                if (!Double.isFinite(weight) || weight < 0.0) {
                    throw new IllegalArgumentException("Class weights must be finite non-negative numbers");
                }
                largestWeight = Math.max(largestWeight, weight);
            }
            if (largestWeight == 0.0) {
                throw new IllegalArgumentException("Expected at least one positive class weight");
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
        private final int streamId;
        private final Source source;
        private final SplittableRandom arrivalRandom;
        private final SplittableRandom packetRandom;
        private double arrivalUs;

        private ArrivalStream(int streamId, Source source) {
            this.streamId = streamId;
            this.source = source;
            // 随机流标识可以独立于归并顺序，添加其他业务时无需改变已有业务的随机序列。
            long randomKey = source.streamKey == 0 ? streamId : source.streamKey;
            SplittableRandom random = new SplittableRandom(source.seed ^ (0x9E3779B97F4A7C15L * randomKey));
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
            int destinationId = source.destinationNodeIds.get(packetRandom.nextInt(source.destinationNodeIds.size()));
            double[] weights = source.cumulativeWeights;
            double draw = packetRandom.nextDouble(weights[weights.length - 1]);
            int index = 0;
            while (draw >= weights[index]) {
                index++;
            }
            return new Packet(id, source.packetBytes, timeUs(), CATEGORIES[index], source.direction,
                    source.sourceNodeId, destinationId);
        }
    }
}
