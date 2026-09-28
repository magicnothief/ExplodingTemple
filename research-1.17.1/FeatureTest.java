import com.seedfinding.mccore.util.pos.CPos;
import features.BlockModel;
import features.Decoration;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.util.zip.GZIPInputStream;

/*
Compares the feature models with the game on HarnessDrip's snapshots (the 3x3 chunks around a chunk whose rare
dripstone cluster rolled: before the chunk's features, right before the cluster, right after it, and the ocean floor
heightmap).

  exact:    the cluster port on the game's blocks right before the cluster
  features: lakes, dungeons and ores on the blocks before any feature, then the clusters
  carved:   the clusters on the blocks before any feature
 */
public class FeatureTest {
    static final int HEIGHT = 128;

    static class Snapshot implements BlockModel.Terrain {
        final int minX, minZ;
        final byte[] data;
        final short[] oceanFloor;

        Snapshot(int cx, int cz, byte[] data, short[] oceanFloor) {
            this.minX = (cx - 1) << 4;
            this.minZ = (cz - 1) << 4;
            this.data = data;
            this.oceanFloor = oceanFloor;
        }

        public byte get(int x, int y, int z) {
            if (y < 0 || y >= HEIGHT) return BlockModel.AIR;
            int i = x - minX, j = z - minZ;
            if (i < 0 || j < 0 || i >= 48 || j >= 48) throw new IllegalStateException("outside " + x + " " + z);
            return data[(i * 48 + j) * HEIGHT + y];
        }

        boolean contains(int x, int z) {
            return x >= minX && z >= minZ && x < minX + 48 && z < minZ + 48;
        }

        public int oceanFloor(int x, int z) {
            return oceanFloor[(x - minX) * 48 + z - minZ];
        }
    }

    static Decoration.Biome kind(String biome) {
        return switch (biome) {
            case "minecraft:desert" -> Decoration.Biome.DESERT;
            case "minecraft:desert_hills", "minecraft:desert_lakes" -> Decoration.Biome.OTHER_DESERT;
            case "minecraft:swamp" -> Decoration.Biome.SWAMP;
            default -> Decoration.Biome.OTHER;
        };
    }

    public static void main(String[] args) throws Exception {
        int skipped = 0;
        int cases = 0, withBlocks = 0, exactSame = 0, featureSame = 0, carvedSame = 0, preSame = 0;
        long featureWrong = 0, carvedWrong = 0, preWrong = 0;
        for (String file : args) {
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
                while (true) {
                    long seed;
                    int cx, cz;
                    String biome;
                    byte[][] snaps = new byte[3][48 * 48 * HEIGHT];
                    short[] heights = new short[48 * 48];
                    try {
                        seed = in.readLong();
                        cx = in.readInt();
                        cz = in.readInt();
                        in.readInt();
                        biome = in.readUTF();
                        for (byte[] s : snaps) in.readFully(s);
                        for (int i = 0; i < heights.length; i++) heights[i] = in.readShort();
                    } catch (EOFException e) {
                        break;
                    }
                    String skip = unmodelled(seed, cx, cz, biome);
                    if (skip != null) {
                        System.out.printf("chunk %d %d %s: skipped, %s%n", cx, cz, biome, skip);
                        skipped++;
                        continue;
                    }
                    cases++;
                    Snapshot before = new Snapshot(cx, cz, snaps[0], heights);
                    Snapshot beforeCluster = new Snapshot(cx, cz, snaps[1], heights);
                    Snapshot after = new Snapshot(cx, cz, snaps[2], heights);
                    int changed = diff(beforeCluster::get, after, false);
                    if (changed > 0) withBlocks++;

                    BlockModel exact = new BlockModel(beforeCluster);
                    Decoration.placeClusters(exact, Decoration.decorationSeed(seed, cx << 4, cz << 4), cx, cz);
                    if (diff(exact::get, after, false) == 0) exactSame++;

                    BlockModel features = new BlockModel(before);
                    long d = Decoration.decorateBeforeClusters(features, seed, cx, cz, kind(biome), null);
                    int pre = diff(features::get, beforeCluster, false);
                    preWrong += pre;
                    if (pre == 0) preSame++;
                    Decoration.placeClusters(features, d, cx, cz);
                    int featureDiff = diff(features::get, after, true);
                    featureWrong += featureDiff;
                    if (featureDiff == 0) featureSame++;

                    BlockModel carved = new BlockModel(before);
                    Decoration.placeClusters(carved, d, cx, cz);
                    int carvedDiff = diff(carved::get, after, true);
                    carvedWrong += carvedDiff;
                    if (carvedDiff == 0) carvedSame++;

                    if (pre > 0 && System.getenv("DETAIL") != null) detail(features, beforeCluster, cx, cz, biome);
                    if (changed > 0 || featureDiff > 0 || pre > 0) {
                        System.out.printf("chunk %d %d %s: cluster placed %d blocks; blocks before the cluster wrong %d;"
                                        + " dripstone wrong with the features %d, on the carved terrain %d%n",
                                cx, cz, biome, changed, pre, featureDiff, carvedDiff);
                    }
                }
            }
        }
        System.out.printf("%d skipped for a mineshaft, geode or fossil. %d chunks, %d with dripstone. exact: %d identical. features before the cluster: %d chunks"
                        + " identical (%d wrong blocks). dripstone with the features: %d chunks right (%d blocks wrong),"
                        + " on the carved terrain: %d right (%d wrong)%n", skipped, cases, withBlocks, exactSame, preSame, preWrong,
                featureSame, featureWrong, carvedSame, carvedWrong);
    }

    // what the finder doesn't model and avoids instead: a mineshaft in the chunk, or its geode or fossil
    static String unmodelled(long seed, int cx, int cz, String biome) {
        Mineshafts.Box chunk = new Mineshafts.Box(cx << 4, 0, cz << 4, (cx << 4) + 15, 127, (cz << 4) + 15);
        for (Mineshafts.Box box : Mineshafts.near(seed & ((1L << 48) - 1), new CPos(cx, cz), 0)) {
            if (box.intersects(chunk)) return "mineshaft";
        }
        long d = Decoration.decorationSeed(seed, cx << 4, cz << 4);
        int geodeIndex = biome.contains("frozen_ocean") ? 2 : 0;
        if (Decoration.featureRandom(d, geodeIndex, 2).nextFloat() < 1.0f / 53) return "geode";
        int fossilIndex = biome.equals("minecraft:desert") || biome.equals("minecraft:swamp") ? 2
                : biome.equals("minecraft:swamp_hills") ? 3 : -1;
        if (fossilIndex >= 0 && Decoration.featureRandom(d, fossilIndex, 3).nextFloat() < 1.0f / 64) return "fossil";
        return null;
    }

    // which kinds of blocks the model gets wrong before the cluster, with the Y range and a sample position
    static void detail(BlockModel model, Snapshot game, int cx, int cz, String biome) {
        String[] names = {"stone", "air", "water", "lava", "other", "dripstone", "pointed", "dirt", "pointedW", "waterlogged"};
        java.util.Map<String, int[]> kinds = new java.util.TreeMap<>();
        for (int x = game.minX; x < game.minX + 48; x++) {
            for (int z = game.minZ; z < game.minZ + 48; z++) {
                for (int y = 0; y < HEIGHT; y++) {
                    byte p = model.get(x, y, z), q = game.get(x, y, z);
                    if (p == q) continue;
                    int[] k = kinds.computeIfAbsent(names[p] + "->" + names[q], key -> new int[]{0, 999, -1, 0, 0, 0});
                    k[0]++;
                    k[1] = Math.min(k[1], y);
                    k[2] = Math.max(k[2], y);
                    k[3] = x;
                    k[4] = y;
                    k[5] = z;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        kinds.forEach((key, k) -> sb.append(String.format(" %s %d (y %d-%d, e.g. %d %d %d)", key, k[0], k[1], k[2], k[3], k[4], k[5])));
        System.out.printf("DETAIL %d %d %s (model->game):%s%n", cx, cz, biome, sb);
    }

    interface Getter {
        byte get(int x, int y, int z);
    }

    // blocks in the 3x3 chunks that differ, or only where one has dripstone and the other doesn't
    static int diff(Getter a, Snapshot b, boolean dripstoneOnly) {
        int n = 0;
        for (int x = b.minX; x < b.minX + 48; x++) {
            for (int z = b.minZ; z < b.minZ + 48; z++) {
                for (int y = 0; y < HEIGHT; y++) {
                    byte p = a.get(x, y, z), q = b.get(x, y, z);
                    if (dripstoneOnly ? BlockModel.isDripstone(p) != BlockModel.isDripstone(q) : p != q) n++;
                }
            }
        }
        return n;
    }
}
