package interner.mechanism;

import interner.Driver;
import interner.gc.GcLog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mechanism in G1's own accounting: each table runs a few compiles in a child JVM with a fixed young generation
 * (so each compile sees young GCs), and the test reads the G1 log. A chained table must show humongous regions and
 * card scanning that the chunked tables do not.
 */
class GcLogMechanismTest {
    static final Map<String, GcLog.Summary> results = new HashMap<>();

    @BeforeAll
    static void runChildren() throws Exception {
        Path dir = Files.createDirectories(Path.of("target", "mechanism"));
        try (var old = Files.list(dir)) {
            for (Path f : old.toList()) Files.delete(f);
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String table : List.of("chained-weak", "chained-strong", "chunked-weak", "chunked-strong")) {
            Path log = dir.resolve(table + ".gc.log");
            List<String> cmd = new ArrayList<>(List.of(java, "-XX:+UseG1GC", "-Xms2g", "-Xmx2g", "-Xmn512m",
                    "-XX:G1HeapRegionSize=4m", "-Xlog:gc*=debug:file=" + log + "::filecount=0", "-cp", cp,
                    Driver.class.getName(), table, "4", "scale=0.25"));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(dir.resolve(table + ".out").toFile()).start();
            assertEquals(0, p.waitFor(), table + " child failed: " + Files.readString(dir.resolve(table + ".out")));
            results.put(table, GcLog.parse(log));
        }
    }

    @Test
    void everyTableSeesYoungGcs() {
        results.forEach((t, s) -> assertTrue(s.pauses >= 4, t + ": " + s.pauses + " young pauses"));
    }

    @Test
    void chainedTablesHoldHumongousRegionsChunkedOnesNone() {
        assertTrue(results.get("chained-weak").humongousMax >= 1);
        assertTrue(results.get("chained-strong").humongousMax >= 1);
        assertEquals(0, results.get("chunked-weak").humongousMax);
        assertEquals(0, results.get("chunked-strong").humongousMax);
    }

    @Test
    void chainedTablesMakeYoungGcsScanCards() {
        for (String chained : List.of("chained-weak", "chained-strong")) {
            for (String chunked : List.of("chunked-weak", "chunked-strong")) {
                long a = results.get(chained).scannedCards, b = results.get(chunked).scannedCards;
                assertTrue(a > 10_000 && a > 20 * Math.max(b, 1), chained + " scanned " + a + " cards, " + chunked + " " + b);
            }
        }
    }

    @Test
    void onlyWeakTablesPayForReferenceDiscovery() {
        assertTrue(results.get("chained-weak").weakRefsDiscovered > 100_000);
        assertTrue(results.get("chunked-weak").weakRefsDiscovered > 100_000);
        assertTrue(results.get("chunked-strong").weakRefsDiscovered < 10_000);
        assertTrue(results.get("chained-strong").weakRefsDiscovered < 10_000);
    }
}
