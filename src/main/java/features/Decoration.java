package features;

import java.util.Random;

/*
One chunk's feature decoration in 1.17.1 (ChunkGenerator#applyBiomeDecoration and Biome#generate) as far as the rare
dripstone clusters care: lakes, dungeons, the structures, the ores and then the clusters, each feature with its own
random from the chunk's decoration seed, the feature's index in its step and the step. Amethyst geodes, fossils,
mineshafts and structures other than the ones passed in aren't modelled.
 */
public final class Decoration {
    // what the modelled steps of the overworld biomes differ in
    public enum Biome {
        // desert: only lava lakes, and fossils before the dungeons
        DESERT(true, 3),
        // desert hills and desert lakes
        OTHER_DESERT(true, 2),
        SWAMP(false, 3),
        OTHER(false, 2);

        final boolean onlyLavaLakes;
        final int monsterRoomIndex;

        Biome(boolean onlyLavaLakes, int monsterRoomIndex) {
            this.onlyLavaLakes = onlyLavaLakes;
            this.monsterRoomIndex = monsterRoomIndex;
        }
    }

    public interface Structures {
        void place(BlockModel world, int chunkX, int chunkZ);
    }

    public static final int UNDERGROUND_DECORATION = 7;

    private Decoration() {
    }

    // WorldgenRandom#setDecorationSeed, from the chunk's corner; only the low 48 bits of the seed matter
    public static long decorationSeed(long seed, int blockX, int blockZ) {
        Random r = new Random(seed);
        long a = r.nextLong() | 1L;
        long b = r.nextLong() | 1L;
        return (long) blockX * a + (long) blockZ * b ^ seed;
    }

    // WorldgenRandom#setFeatureSeed
    public static Random featureRandom(long decorationSeed, int index, int step) {
        return new Random(decorationSeed + index + 10000L * step);
    }

    // whether the chunk's rare dripstone cluster feature gets past its 1 in 25 at all
    public static boolean rollsClusters(long seed, int chunkX, int chunkZ) {
        long decorationSeed = decorationSeed(seed, chunkX << 4, chunkZ << 4);
        return DripstoneClusters.rolls(featureRandom(decorationSeed, DripstoneClusters.INDEX, UNDERGROUND_DECORATION));
    }

    /** Decorates the chunk and returns how many dripstone clusters it built. */
    public static int decorate(BlockModel world, long seed, int chunkX, int chunkZ, Biome biome, Structures structures) {
        long decorationSeed = decorateBeforeClusters(world, seed, chunkX, chunkZ, biome, structures);
        return placeClusters(world, decorationSeed, chunkX, chunkZ);
    }

    // the steps before UNDERGROUND_DECORATION, returns the chunk's decoration seed
    public static long decorateBeforeClusters(BlockModel world, long seed, int chunkX, int chunkZ, Biome biome,
                                              Structures structures) {
        long d = decorationSeed(seed, chunkX << 4, chunkZ << 4);
        if (biome.onlyLavaLakes) {
            Lakes.placeLava(world, featureRandom(d, 0, Lakes.STEP), chunkX, chunkZ);
        } else {
            Lakes.placeWater(world, featureRandom(d, 0, Lakes.STEP), chunkX, chunkZ);
            Lakes.placeLava(world, featureRandom(d, 1, Lakes.STEP), chunkX, chunkZ);
        }
        MonsterRooms.place(world, featureRandom(d, biome.monsterRoomIndex, MonsterRooms.STEP), chunkX, chunkZ);
        if (structures != null) structures.place(world, chunkX, chunkZ);
        Ores.place(world, d, chunkX, chunkZ);
        return d;
    }

    public static int placeClusters(BlockModel world, long decorationSeed, int chunkX, int chunkZ) {
        return DripstoneClusters.place(world, featureRandom(decorationSeed, DripstoneClusters.INDEX, UNDERGROUND_DECORATION),
                chunkX, chunkZ);
    }
}
