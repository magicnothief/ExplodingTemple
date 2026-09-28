package features;

import java.util.OptionalInt;
import java.util.Random;

import static features.BlockModel.*;

/*
The small dripstone clusters 1.17.1 puts in ordinary caves (rare_dripstone_cluster: in 1 chunk in 25, ten tries at a
random spot below Y 60, each building a patch of dripstone blocks and pointed dripstone of radius 2 to 6 on the
floor and ceiling around it if the spot is in a cave). Normal 1.17 worlds have no dripstone cave biome, so these are
the only dripstone near a desert pyramid.

This replays the feature with the game's own random calls (DripstoneClusterFeature, DripstoneUtils, Column). Given
the game's blocks right before the feature it places exactly the same blocks (checked on 300 chunks).
 */
public final class DripstoneClusters {
    // every overworld biome has the rare cluster right after the nether fortress and nether fossil structures
    public static final int INDEX = 2;
    private static final int CHANCE = 25, TRIES = 10, MAX_Y = 59;

    // the rare_dripstone_cluster configuration
    private static final int SEARCH_RANGE = 12;
    private static final int HEIGHT = 3;
    private static final int MIN_RADIUS = 2, MAX_RADIUS = 6;
    private static final int MAX_HEIGHT_DIFF = 1;
    private static final int HEIGHT_DEVIATION = 3;
    private static final int LAYER_THICKNESS = 2;
    private static final float MIN_DENSITY = 0.3f, MAX_DENSITY = 0.4f;
    private static final float WETNESS_MEAN = 0.1f, WETNESS_DEVIATION = 0.3f, MIN_WETNESS = 0.1f, MAX_WETNESS = 0.9f;
    private static final float EDGE_CHANCE = 0.1f;
    private static final int EDGE_DISTANCE = 3;
    private static final int CENTER_DISTANCE = 8;

    private DripstoneClusters() {
    }

    // whether the chunk gets clusters at all: the first draw of the feature's random
    public static boolean rolls(Random random) {
        return random.nextFloat() < 1.0f / CHANCE;
    }

    /**
     * Places a chunk's rare dripstone clusters like 1.17.1 does, with the feature's random, and returns how many were
     * built.
     */
    public static int place(BlockModel world, Random random, int chunkX, int chunkZ) {
        // .rangeUniform(bottom, 59).squared().count(10).rarity(25): the chance, the count, then each try's x, z, y
        if (!rolls(random)) return 0;
        int tries = random.nextInt(1) + TRIES;
        int built = 0;
        for (int i = 0; i < tries; i++) {
            int x = random.nextInt(16) + (chunkX << 4);
            int z = random.nextInt(16) + (chunkZ << 4);
            int y = random.nextInt(MAX_Y + 1);
            if (placeCluster(world, random, x, y, z)) built++;
        }
        return built;
    }

    // DripstoneClusterFeature#place
    private static boolean placeCluster(BlockModel world, Random random, int x, int y, int z) {
        if (!isAirOrWater(world.get(x, y, z))) return false;
        int height = random.nextInt(1) + HEIGHT;
        float wetness = clampedNormal(random, WETNESS_MEAN, WETNESS_DEVIATION, MIN_WETNESS, MAX_WETNESS);
        float density = random.nextFloat() * (MAX_DENSITY - MIN_DENSITY) + MIN_DENSITY;
        int radiusX = random.nextInt(MAX_RADIUS - MIN_RADIUS + 1) + MIN_RADIUS;
        int radiusZ = random.nextInt(MAX_RADIUS - MIN_RADIUS + 1) + MIN_RADIUS;
        for (int dx = -radiusX; dx <= radiusX; dx++) {
            for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                double chance = chanceOfColumn(radiusX, radiusZ, dx, dz);
                placeColumn(world, random, x + dx, y, z + dz, dx, dz, wetness, chance, height, density);
            }
        }
        return true;
    }

    private static void placeColumn(BlockModel world, Random random, int x, int y, int z, int dx, int dz, float wetness,
                                    double chance, int height, float density) {
        // Column#scan
        if (!isAirOrWater(world.get(x, y, z))) return;
        OptionalInt ceiling = scan(world, x, y, z, 1);
        OptionalInt floor = scan(world, x, y, z, -1);
        if (ceiling.isEmpty() && floor.isEmpty()) return;

        boolean wet = random.nextFloat() < wetness;
        OptionalInt columnFloor = floor;
        if (wet && floor.isPresent() && canPlacePool(world, x, floor.getAsInt(), z)) {
            columnFloor = OptionalInt.of(floor.getAsInt() - 1);
            world.set(x, floor.getAsInt(), z, WATER);
        }

        int stalactite = 0;
        boolean ceilingColumn = random.nextDouble() < chance;
        if (ceiling.isPresent() && ceilingColumn && world.get(x, ceiling.getAsInt(), z) != LAVA) {
            int thickness = random.nextInt(1) + LAYER_THICKNESS;
            replaceWithDripstoneBlocks(world, x, ceiling.getAsInt(), z, thickness, 1);
            int maxHeight = columnFloor.isPresent() ? Math.min(height, ceiling.getAsInt() - columnFloor.getAsInt()) : height;
            stalactite = dripstoneHeight(random, dx, dz, density, maxHeight);
        }
        int stalagmite = 0;
        boolean floorColumn = random.nextDouble() < chance;
        if (columnFloor.isPresent() && floorColumn && world.get(x, columnFloor.getAsInt(), z) != LAVA) {
            int thickness = random.nextInt(1) + LAYER_THICKNESS;
            replaceWithDripstoneBlocks(world, x, columnFloor.getAsInt(), z, thickness, -1);
            stalagmite = Math.max(0, stalactite + random.nextInt(2 * MAX_HEIGHT_DIFF + 1) - MAX_HEIGHT_DIFF);
        }

        if (ceiling.isPresent() && columnFloor.isPresent() && ceiling.getAsInt() - stalactite <= columnFloor.getAsInt() + stalagmite) {
            int floorY = columnFloor.getAsInt(), ceilingY = ceiling.getAsInt();
            int low = Math.max(ceilingY - stalactite, floorY + 1);
            int high = Math.min(floorY + stalagmite, ceilingY - 1);
            int meet = random.nextInt(high + 1 - low + 1) + low;
            stalactite = ceilingY - meet;
            stalagmite = meet - 1 - floorY;
        }
        // the column's height, only a column with both a floor and a ceiling has one
        boolean merge = random.nextBoolean() && stalactite > 0 && stalagmite > 0 && ceiling.isPresent()
                && columnFloor.isPresent() && stalactite + stalagmite == ceiling.getAsInt() - columnFloor.getAsInt() - 1;
        if (ceiling.isPresent()) grow(world, x, ceiling.getAsInt() - 1, z, -1, stalactite);
        if (columnFloor.isPresent()) grow(world, x, columnFloor.getAsInt() + 1, z, 1, stalagmite);
    }

    // Column#scanDirection: the edge block the column of air or water ends at within the search range
    private static OptionalInt scan(BlockModel world, int x, int y, int z, int step) {
        int yy = y;
        for (int i = 1; i < SEARCH_RANGE && isAirOrWater(world.get(x, yy, z)); i++) {
            yy += step;
        }
        return isDripstoneBaseOrLava(world.get(x, yy, z)) ? OptionalInt.of(yy) : OptionalInt.empty();
    }

    private static boolean canPlacePool(BlockModel world, int x, int y, int z) {
        byte b = world.get(x, y, z);
        if (b == WATER || b == DRIPSTONE_BLOCK || isPointedDripstone(b)) return false;
        return canBeNextToWater(world.get(x - 1, y, z)) && canBeNextToWater(world.get(x + 1, y, z))
                && canBeNextToWater(world.get(x, y, z - 1)) && canBeNextToWater(world.get(x, y, z + 1))
                && canBeNextToWater(world.get(x, y - 1, z));
    }

    // base_stone_overworld or water (dripstone blocks aren't base stone)
    private static boolean canBeNextToWater(byte b) {
        return b == STONE || hasWater(b);
    }

    private static void replaceWithDripstoneBlocks(BlockModel world, int x, int y, int z, int count, int step) {
        for (int i = 0; i < count; i++) {
            byte b = world.get(x, y, z);
            if (b != STONE && b != DIRT) return;
            world.set(x, y, z, DRIPSTONE_BLOCK);
            y += step;
        }
    }

    // DripstoneUtils#growPointedDripstone places the whole column without checking what's there
    private static void grow(BlockModel world, int x, int y, int z, int step, int length) {
        for (int i = 0; i < length; i++) {
            world.set(x, y, z, hasWater(world.get(x, y, z)) ? POINTED_DRIPSTONE_WATERLOGGED : POINTED_DRIPSTONE);
            y += step;
        }
    }

    private static int dripstoneHeight(Random random, int dx, int dz, float density, int maxHeight) {
        if (random.nextFloat() > density) return 0;
        int distance = Math.abs(dx) + Math.abs(dz);
        float mean = (float) clampedMap(distance, 0.0, CENTER_DISTANCE, maxHeight / 2.0, 0.0);
        return (int) clampedNormal(random, mean, HEIGHT_DEVIATION, 0.0f, maxHeight);
    }

    private static double chanceOfColumn(int radiusX, int radiusZ, int dx, int dz) {
        int fromEdge = Math.min(radiusX - Math.abs(dx), radiusZ - Math.abs(dz));
        return clampedMap(fromEdge, 0.0, EDGE_DISTANCE, EDGE_CHANCE, 1.0);
    }

    // ClampedNormalFloat#sample
    private static float clampedNormal(Random random, float mean, float deviation, float min, float max) {
        float value = mean + (float) random.nextGaussian() * deviation;
        return value < min ? min : Math.min(value, max);
    }

    // Mth#clampedMap
    private static double clampedMap(double value, double fromMin, double fromMax, double toMin, double toMax) {
        double delta = (value - fromMin) / (fromMax - fromMin);
        if (delta < 0.0) return toMin;
        if (delta > 1.0) return toMax;
        return toMin + delta * (toMax - toMin);
    }

    // dripstone blocks, base stone and dirt, or lava
    private static boolean isDripstoneBaseOrLava(byte b) {
        return b == STONE || b == DIRT || b == DRIPSTONE_BLOCK || b == LAVA;
    }
}
