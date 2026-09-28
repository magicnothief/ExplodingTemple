package features;

import java.util.Random;

import static features.BlockModel.*;

/*
1.17.1's MonsterRoomFeature (dungeons): 8 tries per chunk, each building a room of cobblestone where the spot has a
solid floor and ceiling and 1 to 5 openings to air at floor level, which puts them right next to caves. Chests and
spawners count as OTHER, which isn't quite right for later rooms that would skip them.
 */
final class MonsterRooms {
    static final int STEP = 3;

    private MonsterRooms() {
    }

    // .range(FULL_RANGE).squared().count(8)
    static void place(BlockModel world, Random random, int chunkX, int chunkZ) {
        for (int i = 0; i < 8; i++) {
            int x = random.nextInt(16) + (chunkX << 4);
            int z = random.nextInt(16) + (chunkZ << 4);
            int y = random.nextInt(256);
            placeRoom(world, random, x, y, z);
        }
    }

    private static boolean placeRoom(BlockModel world, Random random, int ox, int oy, int oz) {
        int radiusX = random.nextInt(2) + 2;
        int minX = -radiusX - 1, maxX = radiusX + 1;
        int radiusZ = random.nextInt(2) + 2;
        int minZ = -radiusZ - 1, maxZ = radiusZ + 1;
        int openings = 0;
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = -1; dy <= 4; dy++) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    int x = ox + dx, y = oy + dy, z = oz + dz;
                    boolean solid = isSolid(world.get(x, y, z));
                    if (dy == -1 && !solid) return false;
                    if (dy == 4 && !solid) return false;
                    if ((dx == minX || dx == maxX || dz == minZ || dz == maxZ) && dy == 0
                            && world.get(x, y, z) == AIR && world.get(x, y + 1, z) == AIR) {
                        openings++;
                    }
                }
            }
        }
        if (openings < 1 || openings > 5) return false;

        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = 3; dy >= -1; dy--) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    int x = ox + dx, y = oy + dy, z = oz + dz;
                    byte b = world.get(x, y, z);
                    if (dx == minX || dy == -1 || dz == minZ || dx == maxX || dz == maxZ) {
                        // walls and floor: air where there's no solid block under them, else (mossy) cobblestone
                        if (y >= 0 && !isSolid(world.get(x, y - 1, z))) {
                            world.set(x, y, z, AIR);
                            continue;
                        }
                        if (!isSolid(b)) continue;
                        if (dy == -1 && random.nextInt(4) != 0) {
                            world.set(x, y, z, OTHER);
                            continue;
                        }
                        world.set(x, y, z, OTHER);
                        continue;
                    }
                    world.set(x, y, z, AIR);
                }
            }
        }

        // two chests, each at the first of 3 random spots on the floor with exactly one wall next to it
        for (int n = 0; n < 2; n++) {
            for (int t = 0; t < 3; t++) {
                int x = ox + random.nextInt(radiusX * 2 + 1) - radiusX;
                int z = oz + random.nextInt(radiusZ * 2 + 1) - radiusZ;
                if (world.get(x, oy, z) != AIR) continue;
                int walls = 0;
                if (isSolid(world.get(x, oy, z - 1))) walls++;
                if (isSolid(world.get(x + 1, oy, z))) walls++;
                if (isSolid(world.get(x, oy, z + 1))) walls++;
                if (isSolid(world.get(x - 1, oy, z))) walls++;
                if (walls != 1) continue;
                world.set(x, oy, z, OTHER);
                // the chest's loot table seed
                random.nextLong();
                break;
            }
        }
        world.set(ox, oy, oz, OTHER);
        // the spawner's mob
        random.nextInt(4);
        return true;
    }
}
