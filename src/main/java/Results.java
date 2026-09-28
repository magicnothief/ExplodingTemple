import com.seedfinding.mccore.util.pos.BPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/*
The world seeds found, closest spawn first. They are kept in results.txt in the folder the finder runs in, which the
next run reads back, so the list stays sorted over stopped and resumed searches.
 */
public final class Results {
    private static final Path FILE = Path.of("results.txt");
    private static final String SEPARATOR = " | ";

    private record Result(double distance, long worldSeed, String line) {
    }

    private static final List<Result> results = new ArrayList<>();

    private Results() {
    }

    // the results of earlier runs, lines like "52 blocks | -5722945821321355046 | ..."
    public static synchronized int load() throws IOException {
        if (!Files.exists(FILE)) return 0;
        for (String line : Files.readAllLines(FILE)) {
            String[] fields = line.split(" \\| ");
            if (fields.length < 2 || !fields[0].endsWith(" blocks")) continue;
            try {
                double distance = Double.parseDouble(fields[0].substring(0, fields[0].length() - " blocks".length()));
                insert(new Result(distance, Long.parseLong(fields[1]), line));
            } catch (NumberFormatException ignored) {
            }
        }
        return results.size();
    }

    public static synchronized void add(long worldSeed, BPos shaft, BPos spawn, double distance, int cageY, int landings) {
        String line = String.format("%.0f blocks%s%d%s/tp %d 100 %d%sspawn %d %d %d%sgolem cage Y=%d%sdripstone under %d"
                        + " of the shaft's 9 columns", distance, SEPARATOR, worldSeed, SEPARATOR, shaft.getX(), shaft.getZ(),
                SEPARATOR, spawn.getX(), spawn.getY(), spawn.getZ(), SEPARATOR, cageY, SEPARATOR, landings);
        for (Result r : results) {
            if (r.worldSeed == worldSeed) {
                System.out.println("Got full world seed again (already in " + FILE + "): " + line);
                return;
            }
        }
        int rank = insert(new Result(distance, worldSeed, line));
        System.out.printf("Got full world seed: %s (%s)%n", line,
                rank == 1 ? "the closest so far" : "number " + rank + " of " + results.size() + " by distance");
        try {
            Files.write(FILE, results.stream().map(Result::line).toList());
        } catch (IOException e) {
            System.out.println("couldn't write " + FILE + ": " + e);
        }
    }

    // keeps the list sorted by distance and returns the 1-based rank
    private static int insert(Result result) {
        int i = 0;
        while (i < results.size() && results.get(i).distance <= result.distance) i++;
        results.add(i, result);
        return i + 1;
    }
}
