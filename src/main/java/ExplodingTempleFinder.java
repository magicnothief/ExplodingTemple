import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.rand.seed.RegionSeed;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.misc.SpawnPoint;
import com.seedfinding.mcfeature.structure.DesertPyramid;
import com.seedfinding.mcfeature.structure.PillagerOutpost;
import com.seedfinding.mcmath.util.Mth;
import com.seedfinding.mcterrain.TerrainGenerator;
import com.seedfinding.mcterrain.terrain.OverworldTerrainGenerator;
import features.BlockModel;
import features.Decoration;
import features.PyramidBlocks;
import generator.CarveRegion;
import generator.CubiomesBiomeChecker;
import generator.CubiomesRavineGenerator;
import generator.VanillaCarver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntBinaryOperator;
import java.util.stream.IntStream;

/*
Finds 1.17.1 world seeds where a pillager outpost's iron golem drops into a desert pyramid's hidden shaft and sets
off its TNT, and the crater opens into a ravine with dripstone on its floor right under the shaft, so a player
jumping in lands on dripstone.
 */
public class ExplodingTempleFinder {
    public static final AtomicInteger resultCount = new AtomicInteger(0);
    public static final AtomicLong ravineCount = new AtomicLong(0);
    public static final AtomicLong structureSeedCount = new AtomicLong(0);

    // Offsets from the outpost chunk to the golem's chunk that LayoutTest found, with the base plate rotation (the
    // first nextInt(4) after the carver seed) each one needs: out of 5M superflat 1.17.1 layouts every offset only
    // ever came up with that one rotation, so the other 3/4 of layouts can be skipped.
    private static final int[][] GOLEM_OFFSETS = {
            {-1, -2, 3}, {-1, 1, 0}, {-2, -1, 1}, {-2, -2, 2}, {-2, 0, 2}, {-2, 1, 1},
            {0, -2, 2}, {0, 1, 1}, {1, -1, 0}, {1, -2, 3}, {1, 0, 3}, {1, 1, 0}
    };
    private static final int[][] GOLEM_ROTATION = new int[5][5];

    static {
        for (int[] row : GOLEM_ROTATION) Arrays.fill(row, -1);
        for (int[] offset : GOLEM_OFFSETS) {
            GOLEM_ROTATION[offset[0] + 2][offset[1] + 2] = offset[2];
        }
    }

    // The last 3 bits of a nextInt(24) are bits 17-19 of the LCG state, which only depend on the low 20 bits of the
    // seed as the LCG never carries downwards. So the low 20 bits of a base seed already rule out the 7/8 whose
    // outpost to temple offset can't be any of the golem offsets, even mod 8. These are the rest, in order.
    private static final int LOW_BITS = 20;
    private static final int[] PROMISING_LOW_BITS = promisingLowBits(
            new PillagerOutpost(MCVersion.v1_17_1).getSalt(), new DesertPyramid(MCVersion.v1_17_1).getSalt());

    // 1.17 puts the world spawn in a random spawn biome cell within 256 blocks of (0,0), then spirals
    // out chunk by chunk from there for a grass block (almost always found in the first chunk)
    private static final int SPAWN_SEARCH_RADIUS = 256 + 16;
    // the cubiomes estimate is only the spawn biome cell, the real spawn can be a few chunks away
    private static final int SPAWN_ESTIMATE_SLACK = 64;

    // The temple floor at Y=64 caps the shaft. The golem's feet start 1 block above the cage's bottom, which is the
    // first free block above the surface (water counts), so it only drops into the shaft when the cage's bottom is
    // at Y<=63: its feet start inside the floor block, which doesn't stop it falling.
    private static final int MAX_GOLEM_CAGE_Y = 63;
    // the pyramid fills each column of its footprint from Y=59 down through air and liquid, which would plug a
    // ravine that reaches up to there
    private static final int PYRAMID_FILL_TOP = 59;
    // the crater the TNT leaves reaches down to about Y=49, the fall starts below it
    private static final int FALL_FROM_Y = 48;
    // the ravine has to be open under the whole shaft from here up to the crater, a drop of 19 blocks at least
    private static final int MIN_RAVINE_Y = 30;
    // caves and ravines are modelled this many chunks around the temple: the features of the temple's chunk and its
    // 8 neighbours are, and they reach one chunk further
    private static final int CARVE_CHUNK_RADIUS = 2;
    private static final int SEA_LEVEL = 63;
    // the ground's top blocks, sand and sandstone in a desert, which ores and dripstone don't replace
    private static final int SURFACE_DEPTH = 4;
    // the temple chunk's 8 neighbours, the three whose dripstone clusters can reach the shaft first
    private static final int[][] NEIGHBOURS = {{1, 0}, {0, 1}, {1, 1}, {-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {-1, 1}};

    // outposts and desert pyramids both have one attempt per 32x32 chunk region
    private static final int REGION_CHUNKS = 32;

    private static final long LCG_MULTIPLIER = 0x5DEECE66DL;
    private static final long LCG_ADDEND = 0xBL;
    // nextInt(24) re-rolls a next(31) value this large
    private static final int NEXT_INT_24_REROLL = Integer.MAX_VALUE - Integer.MAX_VALUE % 24;

    private final ChunkRand rand = new ChunkRand();
    private final MCVersion version = MCVersion.v1_17_1;
    private final PillagerOutpost outpost = new PillagerOutpost(version);
    private final DesertPyramid temple = new DesertPyramid(version);
    private final OutpostGolems golems = new OutpostGolems();

    private final long seedMin;
    private final long seedMax;
    private final int maxSpawnDistance;
    private final int maxTempleCoord;

    public ExplodingTempleFinder(long seedMin, long seedMax, int maxSpawnDistance) {
        this.seedMin = seedMin;
        this.seedMax = seedMax;
        this.maxSpawnDistance = maxSpawnDistance;
        // no temple further out than this can be within maxSpawnDistance of the spawn
        this.maxTempleCoord = SPAWN_SEARCH_RADIUS + maxSpawnDistance;
    }

    public void run() {
        int outpostSalt = outpost.getSalt();
        int templeSalt = temple.getSalt();

        try (CubiomesBiomeChecker biomes = new CubiomesBiomeChecker()) {
            for (long high = seedMin >>> LOW_BITS; high <= (seedMax - 1) >>> LOW_BITS; high++) {
                for (int low : PROMISING_LOW_BITS) {
                    long baseSeed = high << LOW_BITS | low;
                    if (baseSeed < seedMin || baseSeed >= seedMax) continue;

                    // Positions in region (0,0) without the outpost start checks - those depend on the region shift,
                    // so they are only checked for the shifted seeds. Same as rand.setRegionSeed(baseSeed, 0, 0,
                    // salt, version) followed by nextInt(24) for x and z, inlined as this runs for every base seed,
                    // with x compared first as most seeds already fail there.
                    long outpostState = nextState((baseSeed + outpostSalt ^ LCG_MULTIPLIER) & Mth.MASK_48);
                    long templeState = nextState((baseSeed + templeSalt ^ LCG_MULTIPLIER) & Mth.MASK_48);
                    int outpostX = nextInt24(outpostState);
                    int templeX = nextInt24(templeState);
                    int offsetX = templeX - outpostX;
                    if (outpostX < 0 || templeX < 0 || offsetX < -2 || offsetX > 2) continue;

                    outpostState = nextState(outpostState);
                    templeState = nextState(templeState);
                    int outpostZ = nextInt24(outpostState);
                    int templeZ = nextInt24(templeState);
                    int offsetZ = templeZ - outpostZ;
                    if (outpostZ < 0 || templeZ < 0 || offsetZ < -2 || offsetZ > 2
                            || GOLEM_ROTATION[offsetX + 2][offsetZ + 2] < 0) { continue; }

                    CPos outpostChunk = new CPos(outpostX, outpostZ);
                    checkRegionShifts(baseSeed, outpostChunk, outpostChunk.add(offsetX, offsetZ),
                            GOLEM_ROTATION[offsetX + 2][offsetZ + 2], biomes);
                }
            }
        }
    }

    private static int[] promisingLowBits(int outpostSalt, int templeSalt) {
        boolean[][] golemOffsetMod8 = new boolean[8][8];
        for (int[] offset : GOLEM_OFFSETS) {
            golemOffsetMod8[offset[0] & 7][offset[1] & 7] = true;
        }
        int mask = (1 << LOW_BITS) - 1;
        return IntStream.rangeClosed(0, mask).filter(low -> {
            long outpostState = nextState((low + outpostSalt ^ LCG_MULTIPLIER) & mask) & mask;
            long templeState = nextState((low + templeSalt ^ LCG_MULTIPLIER) & mask) & mask;
            int offsetX = (int) ((templeState >>> 17) - (outpostState >>> 17)) & 7;
            outpostState = nextState(outpostState) & mask;
            templeState = nextState(templeState) & mask;
            int offsetZ = (int) ((templeState >>> 17) - (outpostState >>> 17)) & 7;
            return golemOffsetMod8[offsetX][offsetZ];
        }).toArray();
    }

    // one step of the java.util.Random LCG
    private static long nextState(long state) {
        return state * LCG_MULTIPLIER + LCG_ADDEND & Mth.MASK_48;
    }

    // nextInt(24) for the state, or -1 in the ~1e-8 case where it would re-roll
    private static int nextInt24(long state) {
        int bits = (int) (state >>> 17);
        return bits >= NEXT_INT_24_REROLL ? -1 : bits % 24;
    }

    // shifting the structure seed by whole regions moves the structures by whole regions, so only the
    // few regions around the origin are checked - anything further away can't be near the world spawn
    private void checkRegionShifts(long baseSeed, CPos outpostPos, CPos templePos, int rotation, CubiomesBiomeChecker biomes) {
        BPos shaft = templeShaftCenter(templePos);
        for (int regX = minRegionShift(shaft.getX()); regX <= maxRegionShift(shaft.getX()); regX++) {
            for (int regZ = minRegionShift(shaft.getZ()); regZ <= maxRegionShift(shaft.getZ()); regZ++) {
                long structureSeed = (baseSeed - regX * RegionSeed.A - regZ * RegionSeed.B) & Mth.MASK_48;
                int outpostX = outpostPos.getX() + regX * REGION_CHUNKS;
                int outpostZ = outpostPos.getZ() + regZ * REGION_CHUNKS;
                // cheapest check first: the weak seed passes 1 in 5, the base plate rotation 1 in 4
                if (!passesWeakSeedCheck(structureSeed, outpostX, outpostZ)) {
                    continue;
                }
                rand.setCarverSeed(structureSeed, outpostX, outpostZ, version);
                if (rand.nextInt(4) != rotation) {
                    continue;
                }
                CPos shiftedOutpost = new CPos(outpostX, outpostZ);
                if (outpost.hasNearbyVillage(structureSeed, outpostX, outpostZ, rand)) {
                    continue;
                }

                CPos shiftedTemple = templePos.add(regX * REGION_CHUNKS, regZ * REGION_CHUNKS);

                if (!golemInShaft(structureSeed, shiftedOutpost, shiftedTemple)
                        || !ravineCarvesBelowShaft(structureSeed, shiftedTemple)) {
                    continue;
                }
                ravineCount.incrementAndGet();

                // The features only depend on the structure seed and the biomes, taken to be desert here. Most
                // chunks have no dripstone at all, and some features aren't modelled
                if (!clustersCanReachShaft(structureSeed, shiftedTemple)
                        || unmodelledFeatureNear(structureSeed, shiftedTemple, ALL_DESERT)) {
                    continue;
                }
                CarveRegion carve = CarveRegion.cubiomes(structureSeed, shiftedTemple, CARVE_CHUNK_RADIUS);
                BlockModel.Terrain terrain = carvedTerrain(carve);
                int templeFirst = dripstoneLandings(structureSeed, shiftedTemple, terrain, ALL_DESERT, true);
                int templeLast = dripstoneLandings(structureSeed, shiftedTemple, terrain, ALL_DESERT, false);
                if (templeFirst > 0 || templeLast > 0) {
                    finalCheck(structureSeed, shiftedOutpost, shiftedTemple, biomes, templeFirst, templeLast);
                }
            }
        }
    }

    // the first and last region shift that keep the temple shaft within maxTempleCoord of the origin on one axis
    private int minRegionShift(int shaftCoord) {
        return Math.ceilDiv(-maxTempleCoord - shaftCoord, REGION_CHUNKS * 16);
    }

    private int maxRegionShift(int shaftCoord) {
        return Math.floorDiv(maxTempleCoord - shaftCoord, REGION_CHUNKS * 16);
    }

    // Besides its region position, an outpost needs this and no village nearby to start
    private boolean passesWeakSeedCheck(long structureSeed, int outpostX, int outpostZ) {
        rand.setWeakSeed(structureSeed, outpostX, outpostZ, version);
        rand.nextInt();
        return rand.nextInt(5) == 0;
    }

    // A ravine under the whole shaft from the crater down to MIN_RAVINE_Y at least, and solid at Y=59: the pyramid
    // fills its footprint with sandstone from Y=59 down through any air, which would plug the ravine under the shaft
    private static boolean ravineCarvesBelowShaft(long structureSeed, CPos templePos) {
        var targetAirList = new ArrayList<BPos>();
        var targetSolidList = new ArrayList<BPos>();
        var basePos = templePos.toBlockPos(0);

        for (int dx = 9; dx <= 11; dx++) {
            for (int dz = 9; dz <= 11; dz++) {
                targetSolidList.add(basePos.add(dx, PYRAMID_FILL_TOP, dz));
                for (int y = MIN_RAVINE_Y; y <= FALL_FROM_Y; y++)
                    targetAirList.add(basePos.add(dx, y, dz));
            }
        }

        return CubiomesRavineGenerator.canyonGivesRequiredAir(structureSeed, targetAirList, targetSolidList);
    }

    // what the modelled features of a chunk depend on in its biome
    interface ChunkBiomes {
        Decoration.Biome at(int chunkX, int chunkZ);
    }

    private static final ChunkBiomes ALL_DESERT = (x, z) -> Decoration.Biome.DESERT;

    // the terrain as the carvers leave it: caves and ravines (lava at Y<=10), water, the ground's top blocks of sand
    // and sandstone and the stone under them
    static BlockModel.Terrain carvedTerrain(CarveRegion carve) {
        return new BlockModel.Terrain() {
            @Override
            public byte get(int x, int y, int z) {
                if (y <= 0) return BlockModel.OTHER;
                if (carve.isWater(x, y, z)) return BlockModel.WATER;
                if (carve.isCarved(x, y, z)) return y <= 10 ? BlockModel.LAVA : BlockModel.AIR;
                int ground = carve.ground(x, z);
                if (y >= ground) return y < SEA_LEVEL ? BlockModel.WATER : BlockModel.AIR;
                return y >= ground - SURFACE_DEPTH ? BlockModel.OTHER : BlockModel.STONE;
            }

            @Override
            public int oceanFloor(int x, int z) {
                int y = carve.ground(x, z) - 1;
                while (y > 0 && (carve.isCarved(x, y, z) || carve.isWater(x, y, z))) y--;
                return y + 1;
            }
        };
    }

    // Only the rare dripstone clusters of the temple's chunk and the three chunks after it can reach the shaft, and
    // each chunk only has them 1 time in 25
    private static boolean clustersCanReachShaft(long seed, CPos templePos) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                if (Decoration.rollsClusters(seed, templePos.getX() + dx, templePos.getZ() + dz)) return true;
            }
        }
        return false;
    }

    /*
    Amethyst geodes, desert fossils and mineshafts aren't modelled, and anything they build near the blocks the
    clusters reaching the shaft stand on or scan changes the clusters. Those clusters reach 6 blocks from a spot at
    most 7 blocks from the shaft.
     */
    private static boolean unmodelledFeatureNear(long seed, CPos templePos, ChunkBiomes biomes) {
        int x0 = (templePos.getX() << 4) + 9 - 13, x1 = (templePos.getX() << 4) + 11 + 13;
        int z0 = (templePos.getZ() << 4) + 9 - 13, z1 = (templePos.getZ() << 4) + 11 + 13;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int chunkX = templePos.getX() + dx, chunkZ = templePos.getZ() + dz;
                long decorationSeed = Decoration.decorationSeed(seed, chunkX << 4, chunkZ << 4);
                // amethyst_geode: .rangeUniform(6, 46).squared().rarity(53); a geode reaches about 12 blocks
                Random geode = Decoration.featureRandom(decorationSeed, 0, 2);
                if (geode.nextFloat() < 1.0f / 53) {
                    int x = geode.nextInt(16) + (chunkX << 4), z = geode.nextInt(16) + (chunkZ << 4);
                    if (x >= x0 - 12 && x <= x1 + 12 && z >= z0 - 12 && z <= z1 + 12) return true;
                }
                // fossil: .rarity(64), somewhere in its chunk 15 to 25 blocks under the ground
                Decoration.Biome biome = biomes.at(chunkX, chunkZ);
                if ((biome == Decoration.Biome.DESERT || biome == Decoration.Biome.SWAMP)
                        && Decoration.featureRandom(decorationSeed, 2, 3).nextFloat() < 1.0f / 64) {
                    boolean overlaps = (chunkX << 4) + 15 >= x0 && (chunkX << 4) <= x1 && (chunkZ << 4) + 15 >= z0
                            && (chunkZ << 4) <= z1;
                    if (overlaps) return true;
                }
            }
        }
        Mineshafts.Box zone = new Mineshafts.Box(x0 - 1, 0, z0 - 1, x1 + 1, PyramidBlocks.FLOOR_Y, z1 + 1);
        for (Mineshafts.Box box : Mineshafts.near(seed & Mth.MASK_48, templePos, 1)) {
            if (box.intersects(zone)) return true;
        }
        return false;
    }

    /*
    How many of the shaft's 9 columns end on dripstone for a player falling from the crater, once the temple's chunk
    and its 8 neighbours are decorated: lakes, dungeons, the pyramid, ores and dripstone clusters. The game decorates
    chunks in whatever order it generates them, and the temple's chunk decorated first or last covers most of the
    difference the order makes.
     */
    static int dripstoneLandings(long seed, CPos templePos, BlockModel.Terrain terrain, ChunkBiomes biomes,
                                 boolean templeFirst) {
        BlockModel world = new BlockModel(terrain);
        int cx = templePos.getX(), cz = templePos.getZ();
        int px = cx << 4, pz = cz << 4;
        Decoration.Structures pyramid = (w, chunkX, chunkZ) -> PyramidBlocks.place(w, px, pz, chunkX, chunkZ);
        if (templeFirst) Decoration.decorate(world, seed, cx, cz, biomes.at(cx, cz), pyramid);
        for (int[] n : NEIGHBOURS) {
            Decoration.decorate(world, seed, cx + n[0], cz + n[1], biomes.at(cx + n[0], cz + n[1]), pyramid);
        }
        if (!templeFirst) Decoration.decorate(world, seed, cx, cz, biomes.at(cx, cz), pyramid);
        int landings = 0;
        for (int x = px + 9; x <= px + 11; x++) {
            for (int z = pz + 9; z <= pz + 11; z++) {
                if (BlockModel.isDripstone(world.landing(x, FALL_FROM_Y, z))) landings++;
            }
        }
        return landings;
    }

    private void finalCheck(long structureSeed, CPos outpostPos, CPos templePos, CubiomesBiomeChecker biomes,
                            int templeFirst, int templeLast) {
        structureSeedCount.incrementAndGet();
        System.out.printf("got a candidate structure seed: %d (temple chunk %d %d, dripstone under %d of the shaft's 9"
                        + " columns with the temple's chunk decorated first, %d with it last), checking its 65536 world"
                        + " seeds%n", structureSeed, templePos.getX(), templePos.getZ(), templeFirst, templeLast);

        // how many sister seeds get through each check, to see what rules a structure seed out
        int[] passed = new int[6];
        BPos shaft = templeShaftCenter(templePos);
        for (long upperBits = 0; upperBits < 1L << 16; upperBits++) {
            long worldSeed = upperBits << 48 | structureSeed;

            // cheap native biome and spawn biome checks first
            if (!hasStructureBiomes(worldSeed, outpostPos, templePos, biomes)) {
                continue;
            }
            passed[0]++;
            if (!spawnCanBeClose(biomes.estimateSpawn(), shaft)) {
                continue;
            }
            passed[1]++;

            BiomeSource obs = BiomeSource.of(Dimension.OVERWORLD, version, worldSeed);
            if (!temple.canSpawn(templePos, obs) || !outpost.canSpawn(outpostPos, obs)) {
                continue;
            }
            passed[2]++;

            TerrainGenerator otg = TerrainGenerator.of(obs);
            IntBinaryOperator firstFree = (x, z) -> otg.getFirstHeightInColumn(x, z, TerrainGenerator.WORLD_SURFACE_WG);
            int cage = droppingGolemCage(worldSeed, outpostPos, templePos, firstFree);
            if (cage == Integer.MIN_VALUE) {
                continue;
            }
            passed[3]++;

            BPos spawn = SpawnPoint.getSpawn((OverworldTerrainGenerator) otg);
            double distance = horizontalDistance(spawn, shaft);
            if (distance > maxSpawnDistance) {
                continue;
            }
            passed[4]++;

            // the game's own carvers skip caves next to water, the ground decides what the features build on, and
            // the biomes which features there are
            CarveRegion carve = VanillaCarver.carve(worldSeed, templePos, CARVE_CHUNK_RADIUS, otg, biomes::getCarverBiome);
            if (!ravineUnderShaft(carve, templePos)) {
                System.out.println("  " + worldSeed + " only has the ravine with the cubiomes carvers");
                continue;
            }
            ChunkBiomes chunkBiomes = (x, z) -> CubiomesBiomeChecker.decorationBiome(biomes.getStructureBiome(new CPos(x, z)));
            if (unmodelledFeatureNear(worldSeed, templePos, chunkBiomes)) continue;
            BlockModel.Terrain terrain = carvedTerrain(carve);
            int first = dripstoneLandings(worldSeed, templePos, terrain, chunkBiomes, true);
            int last = dripstoneLandings(worldSeed, templePos, terrain, chunkBiomes, false);
            if (first == 0 || last == 0) {
                System.out.printf("  %d has dripstone under %d of the shaft's columns with the temple's chunk decorated"
                        + " first and %d with it last%n", worldSeed, first, last);
                continue;
            }

            passed[5]++;
            resultCount.incrementAndGet();
            Results.add(worldSeed, shaft, spawn, distance, cage, Math.min(first, last));
        }
        System.out.printf("  structure seed %d, sister seeds left after each check: biomes %d, spawn estimate close %d,"
                        + " structures spawn %d, golem drops %d, spawn close %d, ravine and dripstone with vanilla"
                        + " carvers %d%n", structureSeed, passed[0], passed[1], passed[2], passed[3], passed[4], passed[5]);
    }

    // With no spawn biome within 256 blocks the game spirals out from 0 0 for grass instead, and cubiomes estimates
    // 8 8, but in a desert the nearest grass can be 90 blocks from there, so then only the real spawn tells
    private boolean spawnCanBeClose(BPos estimate, BPos shaft) {
        boolean noSpawnBiome = estimate.getX() == 8 && estimate.getZ() == 8;
        return noSpawnBiome || horizontalDistance(estimate, shaft) <= maxSpawnDistance + SPAWN_ESTIMATE_SLACK;
    }

    private static boolean ravineUnderShaft(CarveRegion carve, CPos templePos) {
        int px = templePos.getX() << 4, pz = templePos.getZ() << 4;
        for (int x = px + 9; x <= px + 11; x++) {
            for (int z = pz + 9; z <= pz + 11; z++) {
                if (carve.isCarved(x, PYRAMID_FILL_TOP, z)) return false;
                for (int y = MIN_RAVINE_Y; y <= FALL_FROM_Y; y++) {
                    if (!carve.isCarved(x, y, z)) return false;
                }
            }
        }
        return true;
    }

    private static boolean hasStructureBiomes(long worldSeed, CPos outpostPos, CPos templePos, CubiomesBiomeChecker biomes) {
        biomes.applySeed(worldSeed);
        return CubiomesBiomeChecker.isDesert(biomes.getStructureBiome(templePos))
                && CubiomesBiomeChecker.isOutpostBiome(biomes.getStructureBiome(outpostPos));
    }

    // whether the outpost, laid out on flat ground, puts a golem in the temple shaft
    private boolean golemInShaft(long structureSeed, CPos outpostPos, CPos templePos) {
        int shaftX = (templePos.getX() << 4) + 9, shaftZ = (templePos.getZ() << 4) + 9;
        return golems.hasGolemIn(structureSeed, outpostPos.getX(), outpostPos.getZ(), shaftX, shaftZ, shaftX + 2, shaftZ + 2);
    }

    // the outpost laid out on the real terrain: the bottom Y of the cage whose golem stands in the shaft low enough
    // to drop, or MIN_VALUE if there is none
    private int droppingGolemCage(long worldSeed, CPos outpostPos, CPos templePos, IntBinaryOperator firstFree) {
        int shaftX = (templePos.getX() << 4) + 9, shaftZ = (templePos.getZ() << 4) + 9;
        int count = golems.layOut(worldSeed & Mth.MASK_48, outpostPos.getX(), outpostPos.getZ(), firstFree);
        for (int g = 0; g < count; g++) {
            if (golems.golem(g, 0) >= shaftX && golems.golem(g, 2) <= shaftX + 2 && golems.golem(g, 1) >= shaftZ
                    && golems.golem(g, 3) <= shaftZ + 2 && golems.golem(g, 4) <= MAX_GOLEM_CAGE_Y) {
                return golems.golem(g, 4);
            }
        }
        return Integer.MIN_VALUE;
    }

    private static BPos templeShaftCenter(CPos templeChunk) {
        return templeChunk.toBlockPos(0).add(10, 0, 10);
    }

    private static double horizontalDistance(BPos a, BPos b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }
}
