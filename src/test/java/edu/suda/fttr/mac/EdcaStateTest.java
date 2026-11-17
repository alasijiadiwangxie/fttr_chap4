package edu.suda.fttr.mac;

import edu.suda.fttr.config.ConfigLoader;
import edu.suda.fttr.entity.AccessCategory;
import edu.suda.fttr.entity.Sfu;
import edu.suda.fttr.entity.Sta;
import edu.suda.fttr.link.FixedDelayModel;
import edu.suda.fttr.link.WifiLink;
import edu.suda.fttr.mac.EdcaState.AcState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdcaStateTest {
    @Test
    void startsEachCategoryAtItsConfiguredMinimum(@TempDir Path directory) throws IOException {
        EdcaParameters defaults = parameters(directory);
        assertEquals(0, defaults.seed());
        Sta station = new Sta(1, 1, defaults);
        Sfu accessPoint = sfu(1, defaults);
        Map<AccessCategory, Integer> expected = Map.of(
                AccessCategory.VO, 3, AccessCategory.VI, 7, AccessCategory.BE, 15, AccessCategory.BK, 15);
        expected.forEach((category, minimum) -> {
            assertInitialState(station.edcaState().forCategory(category), minimum);
            assertInitialState(accessPoint.edcaState().forCategory(category), minimum);
        });

        EdcaParameters override = parameters(directory, "wifi.mac.edca.VI.cwmin=1");
        assertInitialState(new Sta(1, 1, override).edcaState().forCategory(AccessCategory.VI), 1);
    }

    @Test
    void drawsAndStoresCountersInTheInclusiveWindow(@TempDir Path directory) throws IOException {
        Sta station = new Sta(1, 1, parameters(directory, "wifi.mac.edca.seed=13"));
        for (AccessCategory category : AccessCategory.values()) {
            AcState state = station.edcaState().forCategory(category);
            int cw = state.currentCw();
            boolean sawZero = false;
            boolean sawCw = false;
            for (int i = 0; i < 512; i++) {
                int counter = state.drawBackoff();
                assertTrue(counter >= 0 && counter <= cw, category.name());
                assertEquals(OptionalInt.of(counter), state.backoffCounter());
                sawZero |= counter == 0;
                sawCw |= counter == cw;
            }
            assertTrue(sawZero && sawCw, category.name());
        }

        EdcaParameters zeroWindow = parameters(directory, "wifi.mac.edca.VO.cwmin=0", "wifi.mac.edca.VO.cwmax=0");
        AcState voice = sfu(1, zeroWindow).edcaState().forCategory(AccessCategory.VO);
        assertEquals(0, voice.drawBackoff());
        voice.onContentionFailure();
        assertEquals(0, voice.currentCw());
        assertEquals(0, voice.drawBackoff());
    }

    @Test
    void growsTheWindowThroughItsCapAndResetsAfterSuccess(@TempDir Path directory) throws IOException {
        AcState bestEffort = sfu(1, parameters(directory)).edcaState().forCategory(AccessCategory.BE);
        for (int expected : new int[]{31, 63, 127, 255, 511, 1023, 1023}) {
            bestEffort.drawBackoff();
            bestEffort.onContentionFailure();
            assertEquals(expected, bestEffort.currentCw());
            assertEquals(OptionalInt.empty(), bestEffort.backoffCounter());
        }
        bestEffort.drawBackoff();
        bestEffort.onTransmissionSuccess();
        assertInitialState(bestEffort, 15);
    }

    @Test
    void reproducesSequencesFromTheSignedSeed(@TempDir Path directory) throws IOException {
        EdcaParameters seed = parameters(directory, "wifi.mac.edca.seed=-9223372036854775808");
        EdcaParameters otherSeed = parameters(directory, "wifi.mac.edca.seed=31");
        assertEquals(Long.MIN_VALUE, seed.seed());

        List<Integer> first = draws(sfu(1, seed).edcaState().forCategory(AccessCategory.BE));
        List<Integer> second = draws(sfu(1, seed).edcaState().forCategory(AccessCategory.BE));
        List<Integer> different = draws(sfu(1, otherSeed).edcaState().forCategory(AccessCategory.BE));
        assertEquals(first, second);
        assertNotEquals(first, different);
    }

    @Test
    void defaultSeedDoesNotProduceAdjacentCategoryStreamsOneDrawApart(@TempDir Path directory) throws IOException {
        EdcaState edca = new Sta(1, 1, parameters(directory)).edcaState();
        AcState be = edca.forCategory(AccessCategory.BE);
        // 若种子差恰为生成器步长，跳过 BE 的首个值就会得到与 BK 相同的序列。
        be.drawBackoff();
        assertNotEquals(draws(be), draws(edca.forCategory(AccessCategory.BK)));
    }

    @Test
    void keepsDeviceAndCategoryStateAndRandomStreamsIndependent(@TempDir Path directory) throws IOException {
        EdcaParameters parameters = parameters(directory, "wifi.mac.edca.seed=37");
        Sta firstStation = new Sta(1, 1, parameters);
        Sta secondStation = new Sta(2, 1, parameters);
        Sfu accessPoint = sfu(1, parameters);
        AcState be = firstStation.edcaState().forCategory(AccessCategory.BE);
        AcState bk = firstStation.edcaState().forCategory(AccessCategory.BK);
        AcState otherStaBe = secondStation.edcaState().forCategory(AccessCategory.BE);
        AcState sameIdSfuBe = accessPoint.edcaState().forCategory(AccessCategory.BE);

        // BE 和 BK 的初始 CW 相同，可直接验证 AC、设备 ID 和设备类型都区分随机序列。
        List<Integer> expected = draws(new Sta(1, 1, parameters).edcaState().forCategory(AccessCategory.BE));
        assertNotEquals(expected, draws(bk));
        assertNotEquals(expected, draws(otherStaBe));
        assertNotEquals(expected, draws(sameIdSfuBe));
        assertInitialState(be, 15);
        assertEquals(expected, draws(be));

        OptionalInt bkCounter = bk.backoffCounter();
        OptionalInt otherStaCounter = otherStaBe.backoffCounter();
        OptionalInt sameIdSfuCounter = sameIdSfuBe.backoffCounter();
        be.onContentionFailure();
        assertEquals(31, be.currentCw());
        assertEquals(15, bk.currentCw());
        assertEquals(15, otherStaBe.currentCw());
        assertEquals(15, sameIdSfuBe.currentCw());
        assertEquals(bkCounter, bk.backoffCounter());
        assertEquals(otherStaCounter, otherStaBe.backoffCounter());
        assertEquals(sameIdSfuCounter, sameIdSfuBe.backoffCounter());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "9223372036854775808"})
    void rejectsInvalidSeed(String seed, @TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> parameters(directory, "wifi.mac.edca.seed=" + seed));
    }

    private static void assertInitialState(AcState state, int cw) {
        assertEquals(cw, state.currentCw());
        assertEquals(OptionalInt.empty(), state.backoffCounter());
    }

    private static List<Integer> draws(AcState state) {
        List<Integer> sequence = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            sequence.add(state.drawBackoff());
        }
        return sequence;
    }

    private static Sfu sfu(int id, EdcaParameters parameters) {
        return new Sfu(id, new WifiLink(new FixedDelayModel(1)), Map.of(), parameters);
    }

    private static EdcaParameters parameters(Path directory, String... settings) throws IOException {
        List<String> properties = new ArrayList<>(List.of("fiber.delay.us=0", "packet.arrival.us=0", "packet.bytes=100"));
        properties.addAll(List.of(settings));
        Path file = Files.write(directory.resolve("edca-state.properties"), properties, StandardCharsets.UTF_8);
        return ConfigLoader.load(file).edcaParameters();
    }
}
