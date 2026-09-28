import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/*
Which chunk offsets from a 1.17.1 outpost's chunk can have a golem standing in a desert pyramid's shaft (hitbox blocks
within 9..11 of the chunk on both axes), and with which base plate rotations, from superflat layouts of random seeds.
The finder only lays out outposts whose pyramid is at one of these offsets with a matching rotation.

  LayoutTest [layouts] [seed]
 */
public class LayoutTest {
    public static void main(String[] args) {
        long count = args.length > 0 ? Long.parseLong(args[0]) : 10_000_000L;
        Random random = new Random(args.length > 1 ? Long.parseLong(args[1]) : 1);
        OutpostGolems golems = new OutpostGolems();
        Map<String, long[]> byOffset = new TreeMap<>();
        long withGolem = 0;
        for (long i = 0; i < count; i++) {
            long seed = random.nextLong() & ((1L << 48) - 1);
            int chunkX = random.nextInt(24), chunkZ = random.nextInt(24);
            int n = golems.layOut(seed, chunkX, chunkZ);
            boolean found = false;
            for (int g = 0; g < n; g++) {
                int minX = golems.golem(g, 0), minZ = golems.golem(g, 1), maxX = golems.golem(g, 2), maxZ = golems.golem(g, 3);
                if (Math.floorDiv(minX, 16) != Math.floorDiv(maxX, 16) || Math.floorDiv(minZ, 16) != Math.floorDiv(maxZ, 16)) continue;
                int x0 = Math.floorMod(minX, 16), x1 = Math.floorMod(maxX, 16), z0 = Math.floorMod(minZ, 16), z1 = Math.floorMod(maxZ, 16);
                if (x0 < 9 || x1 > 11 || z0 < 9 || z1 > 11) continue;
                int dx = Math.floorDiv(minX, 16) - chunkX, dz = Math.floorDiv(minZ, 16) - chunkZ;
                byOffset.computeIfAbsent(dx + " " + dz, k -> new long[4])[golems.baseRotation()]++;
                found = true;
            }
            if (found) withGolem++;
        }
        System.out.printf("%d layouts, %d with a golem in a shaft position%n", count, withGolem);
        System.out.println("offset x z: golems by base plate rotation 0 1 2 3");
        for (Map.Entry<String, long[]> e : byOffset.entrySet()) {
            long[] c = e.getValue();
            System.out.printf("%s: %d %d %d %d%n", e.getKey(), c[0], c[1], c[2], c[3]);
        }
    }
}
