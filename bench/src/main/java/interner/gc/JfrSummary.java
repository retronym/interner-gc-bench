package interner.gc;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * GC pause phases and promotions from a JFR recording, as a second source beside the G1 log. Record with
 * `-XX:StartFlightRecording:filename=x.jfr,jdk.PromoteObjectInNewPLAB#enabled=true,jdk.PromoteObjectOutsidePLAB#enabled=true`.
 *
 * <p>Prints the total time of each `jdk.GCPhasePause` level 1 and 2 phase over young pauses, and the objects and bytes
 * copied by class, split into survivor copies and promotions to the old generation (`tenured`).
 */
public final class JfrSummary {
    public static void main(String[] args) throws IOException {
        for (String a : args) {
            Map<String, Duration> phases = new TreeMap<>();
            Map<String, long[]> copied = new TreeMap<>();
            Duration pauses = Duration.ZERO;
            int pauseCount = 0;
            for (RecordedEvent e : RecordingFile.readAllEvents(Path.of(a))) {
                String type = e.getEventType().getName();
                switch (type) {
                    case "jdk.GCPhasePause" -> {
                        pauses = pauses.plus(e.getDuration());
                        pauseCount++;
                    }
                    case "jdk.GCPhasePauseLevel1", "jdk.GCPhasePauseLevel2" -> {
                        String name = (type.endsWith("1") ? "" : "  ") + e.getString("name");
                        phases.merge(name, e.getDuration(), Duration::plus);
                    }
                    case "jdk.PromoteObjectInNewPLAB", "jdk.PromoteObjectOutsidePLAB" -> {
                        String cls = e.getClass("objectClass").getName();
                        boolean tenured = e.getBoolean("tenured");
                        long[] c = copied.computeIfAbsent(cls, k -> new long[4]);
                        int i = tenured ? 2 : 0;
                        c[i]++;
                        c[i + 1] += e.getLong("objectSize");
                    }
                    default -> {}
                }
            }
            System.out.println(a);
            System.out.printf(Locale.ROOT, "  %d pauses, %.1f ms%n", pauseCount, pauses.toNanos() / 1e6);
            phases.forEach((k, v) -> System.out.printf(Locale.ROOT, "  %-40s %10.1f ms%n", k, v.toNanos() / 1e6));
            if (!copied.isEmpty()) {
                System.out.printf(Locale.ROOT, "  %-50s %12s %12s %12s %12s%n", "copied class", "to-surv", "surv-MB", "to-old", "old-MB");
                copied.entrySet().stream()
                        .sorted((x, y) -> Long.compare(y.getValue()[1] + y.getValue()[3], x.getValue()[1] + x.getValue()[3]))
                        .limit(12)
                        .forEach(en -> {
                            long[] c = en.getValue();
                            System.out.printf(Locale.ROOT, "  %-50s %12d %12.1f %12d %12.1f%n", en.getKey(), c[0], c[1] / 1e6, c[2], c[3] / 1e6);
                        });
            }
        }
    }
}
