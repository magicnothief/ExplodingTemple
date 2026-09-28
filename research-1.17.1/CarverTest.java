import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcterrain.TerrainGenerator;
import features.BlockModel;
import features.Decoration;
import generator.CarveRegion;
import generator.CubiomesBiomeChecker;
import generator.VanillaCarver;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.util.zip.GZIPInputStream;

/*
The finder's own terrain (VanillaCarver on the mc_terrain noise, or cubiomes' carvers) against the game's blocks
before any feature, and the whole finder model (that terrain, then the chunk's features and clusters) against the
game's dripstone. Only chunks without a mineshaft, geode or fossil.
 */
public class CarverTest {
    static final int HEIGHT = 128;

    public static void main(String[] args) throws Exception {
        int cases = 0, dripstoneCases = 0, finderRight = 0, cubiomesRight = 0, bedrockRight = 0;
        long cells = 0, vanillaWrong = 0, cubiomesWrong = 0, surfaceWrong = 0;
        try (CubiomesBiomeChecker biomes = new CubiomesBiomeChecker()) {
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
                        if (FeatureTest.unmodelled(seed, cx, cz, biome) != null) continue;
                        cases++;
                        FeatureTest.Snapshot before = new FeatureTest.Snapshot(cx, cz, snaps[0], heights);
                        FeatureTest.Snapshot after = new FeatureTest.Snapshot(cx, cz, snaps[2], heights);

                        biomes.applySeed(seed);
                        BiomeSource obs = BiomeSource.of(Dimension.OVERWORLD, MCVersion.v1_17_1, seed);
                        TerrainGenerator otg = TerrainGenerator.of(obs);
                        CPos center = new CPos(cx, cz);
                        CarveRegion vanilla = VanillaCarver.carve(seed, center, 2, otg, biomes::getCarverBiome);
                        CarveRegion cubiomes = CarveRegion.cubiomes(seed & ((1L << 48) - 1), center, 2);
                        BlockModel.Terrain vanillaTerrain = ExplodingTempleFinder.carvedTerrain(vanilla);
                        BlockModel.Terrain cubiomesTerrain = ExplodingTempleFinder.carvedTerrain(cubiomes);

                        int caseWrong = 0;
                        java.util.Map<String, int[]> detail = new java.util.TreeMap<>();
                        for (int x = before.minX; x < before.minX + 48; x++) {
                            for (int z = before.minZ; z < before.minZ + 48; z++) {
                                int ground = vanilla.ground(x, z);
                                for (int y = 1; y < 60; y++) {
                                    byte game = kind(before.get(x, y, z));
                                    cells++;
                                    if (kind(vanillaTerrain.get(x, y, z)) != game) {
                                        vanillaWrong++;
                                        caseWrong++;
                                        if (y >= ground - 8) surfaceWrong++;
                                        if (System.getenv("DETAIL") != null) {
                                            String key = kind(vanillaTerrain.get(x, y, z)) + "->" + game;
                                            int[] k = detail.computeIfAbsent(key, q -> new int[]{0, 999, -1, 0, 0, 0});
                                            k[0]++; k[1] = Math.min(k[1], y); k[2] = Math.max(k[2], y); k[3] = x; k[4] = y; k[5] = z;
                                        }
                                    }
                                    if (kind(cubiomesTerrain.get(x, y, z)) != game) cubiomesWrong++;
                                }
                            }
                        }

                        if (!detail.isEmpty() && caseWrong > 100) {
                            StringBuilder sb = new StringBuilder();
                            detail.forEach((key, k) -> sb.append(String.format(" %s %d (y %d-%d, e.g. %d %d %d)", key, k[0], k[1], k[2], k[3], k[4], k[5])));
                            System.out.printf("CARVE %d %d %d (model->game, 0 solid 1 air 2 water 3 lava):%s%n", seed, cx, cz, sb);
                        }
                        boolean dripstone = hasDripstone(after, before);
                        if (dripstone) dripstoneCases++;
                        Decoration.Biome kind = CubiomesBiomeChecker.decorationBiome(biomes.getStructureBiome(center));
                        int finder = dripstoneDiff(model(vanillaTerrain, seed, cx, cz, kind), after);
                        // the same with the game's own blocks up to Y=4 (its bedrock)
                        BlockModel.Terrain withBedrock = new BlockModel.Terrain() {
                            public byte get(int x, int y, int z) {
                                return y <= 4 && before.contains(x, z) ? before.get(x, y, z) : vanillaTerrain.get(x, y, z);
                            }

                            public int oceanFloor(int x, int z) {
                                return vanillaTerrain.oceanFloor(x, z);
                            }
                        };
                        int bedrock = dripstoneDiff(model(withBedrock, seed, cx, cz, kind), after);
                        if (bedrock == 0) bedrockRight++;
                        if (finder != bedrock) System.out.printf("BEDROCK chunk %d %d: %d wrong, %d with the game's bedrock%n", cx, cz, finder, bedrock);
                        if (finder > 0 && System.getenv("PRE") != null) {
                            BlockModel pre = new BlockModel(vanillaTerrain);
                            Decoration.decorateBeforeClusters(pre, seed, cx, cz, kind, null);
                            FeatureTest.detail(pre, new FeatureTest.Snapshot(cx, cz, snaps[1], heights), cx, cz, biome);
                        }
                        int cub = dripstoneDiff(model(cubiomesTerrain, seed, cx, cz, kind), after);
                        if (finder == 0) finderRight++;
                        if (cub == 0) cubiomesRight++;
                        if (dripstone || finder > 0) {
                            System.out.printf("chunk %d %d %s: %d carved blocks below Y=60 wrong; dripstone blocks wrong: %d"
                                    + " (cubiomes carvers %d)%n", cx, cz, biome, caseWrong, finder, cub);
                        }
                    }
                }
            }
        }
        System.out.printf("%d chunks (%d with dripstone): below Y=60 VanillaCarver gets %.3f%% of air/water/lava/solid"
                        + " wrong (%.3f%% within 8 of the ground), cubiomes %.3f%%. dripstone right: %d with VanillaCarver,"
                        + " %d with cubiomes, %d with the game's bedrock%n", cases, dripstoneCases, 100.0 * vanillaWrong / cells,
                100.0 * surfaceWrong / cells, 100.0 * cubiomesWrong / cells, finderRight, cubiomesRight, bedrockRight);
    }

    static BlockModel model(BlockModel.Terrain terrain, long seed, int cx, int cz, Decoration.Biome kind) {
        BlockModel world = new BlockModel(terrain);
        Decoration.decorate(world, seed, cx, cz, kind, null);
        return world;
    }

    // solid (0), air (1), water (2), lava (3)
    static byte kind(byte b) {
        return b == BlockModel.AIR || b == BlockModel.WATER || b == BlockModel.LAVA ? b : 0;
    }

    static boolean hasDripstone(FeatureTest.Snapshot after, FeatureTest.Snapshot before) {
        for (int i = 0; i < after.data.length; i++) {
            if (BlockModel.isDripstone(after.data[i]) && !BlockModel.isDripstone(before.data[i])) return true;
        }
        return false;
    }

    static int dripstoneDiff(BlockModel model, FeatureTest.Snapshot game) {
        int n = 0;
        for (int x = game.minX; x < game.minX + 48; x++) {
            for (int z = game.minZ; z < game.minZ + 48; z++) {
                for (int y = 0; y < HEIGHT; y++) {
                    if (BlockModel.isDripstone(model.get(x, y, z)) != BlockModel.isDripstone(game.get(x, y, z))) n++;
                }
            }
        }
        return n;
    }
}
