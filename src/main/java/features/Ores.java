package features;

import java.util.BitSet;
import java.util.Random;

import static features.BlockModel.*;

/*
1.17.1's OreFeature and the ores of the UNDERGROUND_ORES step that turn base stone into something else: dirt, gravel
and the ores, then the sand, clay and gravel disks that replace dirt under water. Every overworld biome has these, at
the same indexes, except that badlands have extra gold before the disks and mountains emeralds after them. Granite,
diorite, andesite, tuff and deepslate are base stone like the stone they replace, so they change nothing the models
look at.
 */
final class Ores {
    static final int STEP = 6;

    private interface Height {
        int sample(Random random);
    }

    // .range...(y).squared().count(count): the count is a constant, then each ore's x, z and y
    private record Ore(int index, int count, int size, byte block, Height height) {
    }

    private static final Ore[] ORES = {
            new Ore(0, 10, 33, DIRT, r -> r.nextInt(256)),
            new Ore(1, 8, 33, OTHER, r -> r.nextInt(256)),
            new Ore(7, 20, 17, OTHER, r -> r.nextInt(128)),
            new Ore(8, 20, 9, OTHER, r -> r.nextInt(64)),
            new Ore(9, 2, 9, OTHER, r -> r.nextInt(32)),
            new Ore(10, 8, 8, OTHER, r -> r.nextInt(16)),
            new Ore(11, 1, 8, OTHER, r -> r.nextInt(16)),
            // lapis and copper: TrapezoidHeight, a triangle from 0 to 30 and 0 to 96
            new Ore(12, 1, 7, OTHER, r -> r.nextInt(16) + r.nextInt(16)),
            new Ore(13, 6, 10, OTHER, r -> r.nextInt(49) + r.nextInt(49)),
    };

    // disk_sand, disk_clay and disk_gravel: .decorated(TOP_SOLID_HEIGHTMAP_SQUARE), sand 3 times
    private record Disk(int index, int count, int minRadius, int maxRadius, int halfHeight) {
    }

    private static final Disk[] DISKS = {new Disk(14, 3, 2, 6, 2), new Disk(15, 1, 2, 3, 1), new Disk(16, 1, 2, 5, 2)};

    private Ores() {
    }

    static void place(BlockModel world, long decorationSeed, int chunkX, int chunkZ) {
        for (Ore ore : ORES) {
            Random random = Decoration.featureRandom(decorationSeed, ore.index, STEP);
            for (int i = 0; i < ore.count; i++) {
                int x = random.nextInt(16) + (chunkX << 4);
                int z = random.nextInt(16) + (chunkZ << 4);
                int y = ore.height.sample(random);
                placeOre(world, random, chunkX, chunkZ, x, y, z, ore.size, ore.block);
            }
        }
        for (Disk disk : DISKS) {
            Random random = Decoration.featureRandom(decorationSeed, disk.index, STEP);
            for (int i = 0; i < disk.count; i++) {
                int x = random.nextInt(16) + (chunkX << 4);
                int z = random.nextInt(16) + (chunkZ << 4);
                placeDisk(world, random, x, world.oceanFloor(x, z), z, disk);
            }
        }
    }

    // DiskReplaceFeature: on the ground under water, dirt within the radius and halfHeight of it becomes sand, clay or
    // gravel
    private static void placeDisk(BlockModel world, Random random, int ox, int oy, int oz, Disk disk) {
        if (oy <= 0 || !hasWater(world.get(ox, oy, oz))) return;
        int radius = random.nextInt(disk.maxRadius - disk.minRadius + 1) + disk.minRadius;
        for (int x = ox - radius; x <= ox + radius; x++) {
            for (int z = oz - radius; z <= oz + radius; z++) {
                if ((x - ox) * (x - ox) + (z - oz) * (z - oz) > radius * radius) continue;
                for (int y = oy + disk.halfHeight; y >= oy - disk.halfHeight; y--) {
                    if (world.get(x, y, z) == DIRT) world.set(x, y, z, OTHER);
                }
            }
        }
    }

    // OreFeature#place
    private static void placeOre(BlockModel world, Random random, int chunkX, int chunkZ, int ox, int oy, int oz,
                                 int size, byte block) {
        float angle = random.nextFloat() * (float) Math.PI;
        float spread = (float) size / 8.0f;
        int margin = Mth.ceil(((float) size / 16.0f * 2.0f + 1.0f) / 2.0f);
        double x0 = ox + Math.sin(angle) * spread;
        double x1 = ox - Math.sin(angle) * spread;
        double z0 = oz + Math.cos(angle) * spread;
        double z1 = oz - Math.cos(angle) * spread;
        double y0 = oy + random.nextInt(3) - 2;
        double y1 = oy + random.nextInt(3) - 2;
        int minX = ox - Mth.ceil(spread) - margin;
        int minY = oy - 2 - margin;
        int minZ = oz - Mth.ceil(spread) - margin;
        int width = 2 * (Mth.ceil(spread) + margin);
        int height = 2 * (2 + margin);
        // only where some column of the box reaches down to the bottom of the box
        for (int x = minX; x <= minX + width; x++) {
            for (int z = minZ; z <= minZ + width; z++) {
                if (minY > world.oceanFloor(x, z)) continue;
                doPlace(world, random, chunkX, chunkZ, size, block, x0, x1, z0, z1, y0, y1, minX, minY, minZ, width, height);
                return;
            }
        }
    }

    // OreFeature#doPlace: a line of spheres of random sizes, dropping those inside a bigger one
    private static void doPlace(BlockModel world, Random random, int chunkX, int chunkZ, int size, byte block,
                                double x0, double x1, double z0, double z1, double y0, double y1,
                                int minX, int minY, int minZ, int width, int height) {
        BitSet done = new BitSet(width * height * width);
        double[] spheres = new double[size * 4];
        for (int i = 0; i < size; i++) {
            float f = (float) i / (float) size;
            spheres[i * 4] = Mth.lerp(f, x0, x1);
            spheres[i * 4 + 1] = Mth.lerp(f, y0, y1);
            spheres[i * 4 + 2] = Mth.lerp(f, z0, z1);
            double s = random.nextDouble() * (double) size / 16.0;
            spheres[i * 4 + 3] = ((double) (Mth.sin((float) Math.PI * f) + 1.0f) * s + 1.0) / 2.0;
        }
        for (int i = 0; i < size - 1; i++) {
            if (spheres[i * 4 + 3] <= 0.0) continue;
            for (int j = i + 1; j < size; j++) {
                if (spheres[j * 4 + 3] <= 0.0) continue;
                double dr = spheres[i * 4 + 3] - spheres[j * 4 + 3];
                double dx = spheres[i * 4] - spheres[j * 4];
                double dy = spheres[i * 4 + 1] - spheres[j * 4 + 1];
                double dz = spheres[i * 4 + 2] - spheres[j * 4 + 2];
                if (!(dr * dr > dx * dx + dy * dy + dz * dz)) continue;
                if (dr > 0.0) {
                    spheres[j * 4 + 3] = -1.0;
                } else {
                    spheres[i * 4 + 3] = -1.0;
                }
            }
        }
        for (int i = 0; i < size; i++) {
            double r = spheres[i * 4 + 3];
            if (r < 0.0) continue;
            double sx = spheres[i * 4], sy = spheres[i * 4 + 1], sz = spheres[i * 4 + 2];
            int bx0 = Math.max(Mth.floor(sx - r), minX);
            int by0 = Math.max(Mth.floor(sy - r), minY);
            int bz0 = Math.max(Mth.floor(sz - r), minZ);
            int bx1 = Math.max(Mth.floor(sx + r), bx0);
            int by1 = Math.max(Mth.floor(sy + r), by0);
            int bz1 = Math.max(Mth.floor(sz + r), bz0);
            for (int x = bx0; x <= bx1; x++) {
                double dx = ((double) x + 0.5 - sx) / r;
                if (!(dx * dx < 1.0)) continue;
                for (int y = by0; y <= by1; y++) {
                    double dy = ((double) y + 0.5 - sy) / r;
                    if (!(dx * dx + dy * dy < 1.0)) continue;
                    for (int z = bz0; z <= bz1; z++) {
                        double dz = ((double) z + 0.5 - sz) / r;
                        if (!(dx * dx + dy * dy + dz * dz < 1.0) || y < 0 || y >= 256) continue;
                        int bit = x - minX + (y - minY) * width + (z - minZ) * width * height;
                        if (done.get(bit)) continue;
                        done.set(bit);
                        // WorldGenRegion#ensureCanWrite: only the chunk being decorated and its 8 neighbours
                        if (Math.abs((x >> 4) - chunkX) > 1 || Math.abs((z >> 4) - chunkZ) > 1) continue;
                        if (world.get(x, y, z) == STONE) world.set(x, y, z, block);
                    }
                }
            }
        }
    }
}
