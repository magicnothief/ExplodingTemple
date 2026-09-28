package features;

import java.util.Random;

import static features.BlockModel.*;

/*
1.17.1's LakeFeature with the lake_lava and lake_water decorators. Lava lakes are mostly underground: a blob of lava
with air above it, which needs solid blocks around its lower half but may open into a cave above.
 */
final class Lakes {
    static final int STEP = 1;

    private Lakes() {
    }

    // lake_lava: .decorated(LAVA_LAKE(80)).range(BiasedToBottomHeight(bottom, top, 8)).squared().rarity(8)
    static void placeLava(BlockModel world, Random random, int chunkX, int chunkZ) {
        if (!(random.nextFloat() < 1.0f / 8)) return;
        int x = random.nextInt(16) + (chunkX << 4);
        int z = random.nextInt(16) + (chunkZ << 4);
        int y = random.nextInt(random.nextInt(248) + 8);
        if (y < 63 || random.nextInt(10) == 0) placeLake(world, random, x, y, z, LAVA);
    }

    // lake_water: .range(FULL_RANGE).squared().rarity(4)
    static void placeWater(BlockModel world, Random random, int chunkX, int chunkZ) {
        if (!(random.nextFloat() < 1.0f / 4)) return;
        int x = random.nextInt(16) + (chunkX << 4);
        int z = random.nextInt(16) + (chunkZ << 4);
        int y = random.nextInt(256);
        placeLake(world, random, x, y, z, WATER);
    }

    private static boolean placeLake(BlockModel world, Random random, int ox, int oy, int oz, byte fluid) {
        int y = oy;
        while (y > 5 && world.get(ox, y, oz) == AIR) y--;
        if (y <= 4) return false;
        // 4 lower (a village nearby would stop it, but there is none by an outpost)
        y -= 4;
        boolean[] lake = new boolean[2048];
        int blobs = random.nextInt(4) + 4;
        for (int n = 0; n < blobs; n++) {
            double sizeX = random.nextDouble() * 6.0 + 3.0;
            double sizeY = random.nextDouble() * 4.0 + 2.0;
            double sizeZ = random.nextDouble() * 6.0 + 3.0;
            double centerX = random.nextDouble() * (16.0 - sizeX - 2.0) + 1.0 + sizeX / 2.0;
            double centerY = random.nextDouble() * (8.0 - sizeY - 4.0) + 2.0 + sizeY / 2.0;
            double centerZ = random.nextDouble() * (16.0 - sizeZ - 2.0) + 1.0 + sizeZ / 2.0;
            for (int i = 1; i < 15; i++) {
                for (int j = 1; j < 15; j++) {
                    for (int k = 1; k < 7; k++) {
                        double dx = ((double) i - centerX) / (sizeX / 2.0);
                        double dy = ((double) k - centerY) / (sizeY / 2.0);
                        double dz = ((double) j - centerZ) / (sizeZ / 2.0);
                        if (dx * dx + dy * dy + dz * dz < 1.0) lake[(i * 16 + j) * 8 + k] = true;
                    }
                }
            }
        }
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                for (int k = 0; k < 8; k++) {
                    if (!isEdge(lake, i, j, k)) continue;
                    byte b = world.get(ox + i, y + k, oz + j);
                    if (k >= 4 && isLiquid(b)) return false;
                    if (k < 4 && !isSolid(b) && b != fluid) return false;
                }
            }
        }
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                for (int k = 0; k < 8; k++) {
                    if (lake[(i * 16 + j) * 8 + k]) world.set(ox + i, y + k, oz + j, k >= 4 ? AIR : fluid);
                }
            }
        }
        if (fluid == LAVA) {
            // stone around the lava, and around the air above it at random
            for (int i = 0; i < 16; i++) {
                for (int j = 0; j < 16; j++) {
                    for (int k = 0; k < 8; k++) {
                        if (!isEdge(lake, i, j, k) || k >= 4 && random.nextInt(2) == 0) continue;
                        if (!isSolid(world.get(ox + i, y + k, oz + j))) continue;
                        world.set(ox + i, y + k, oz + j, STONE);
                    }
                }
            }
        }
        return true;
    }

    // outside the lake, next to it
    private static boolean isEdge(boolean[] lake, int i, int j, int k) {
        return !lake[(i * 16 + j) * 8 + k] && (i < 15 && lake[((i + 1) * 16 + j) * 8 + k]
                || i > 0 && lake[((i - 1) * 16 + j) * 8 + k] || j < 15 && lake[(i * 16 + j + 1) * 8 + k]
                || j > 0 && lake[(i * 16 + (j - 1)) * 8 + k] || k < 7 && lake[(i * 16 + j) * 8 + k + 1]
                || k > 0 && lake[(i * 16 + j) * 8 + (k - 1)]);
    }
}
