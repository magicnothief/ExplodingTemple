package features;

import static features.BlockModel.*;

/*
The blocks of a 1.17.1 desert pyramid (DesertPyramidPiece#postProcess) that underground features can meet: the solid
base from Y=60 up to the floor at 64, the sandstone it fills each column of its footprint with from Y=59 down through
air and liquid, and the hidden room with the TNT and the shaft above it. The pyramid is always at Y=64 and its hidden
room is the same in all four directions, so its rotation doesn't matter.
 */
public final class PyramidBlocks {
    public static final int SIZE = 21;
    public static final int FLOOR_Y = 64;

    private PyramidBlocks() {
    }

    /** The part of the pyramid with its corner at px pz that is inside this chunk, like StructureStart#placeInChunk. */
    public static void place(BlockModel world, int px, int pz, int chunkX, int chunkZ) {
        int x0 = Math.max(px, chunkX << 4), x1 = Math.min(px + SIZE - 1, (chunkX << 4) + 15);
        int z0 = Math.max(pz, chunkZ << 4), z1 = Math.min(pz + SIZE - 1, (chunkZ << 4) + 15);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = FLOOR_Y - 4; y <= FLOOR_Y; y++) world.set(x, y, z, OTHER);
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = FLOOR_Y - 5; y > 1 && isAirOrLiquid(world.get(x, y, z)); y--) world.set(x, y, z, OTHER);
            }
        }
        for (int x = Math.max(x0, px + 7); x <= Math.min(x1, px + 13); x++) {
            for (int z = Math.max(z0, pz + 7); z <= Math.min(z1, pz + 13); z++) {
                for (int dy = -14; dy <= -1; dy++) {
                    byte b = hiddenRoom(x - px, dy, z - pz);
                    if (b >= 0) world.set(x, FLOOR_Y + dy, z, b);
                }
            }
        }
    }

    private static boolean isAirOrLiquid(byte b) {
        return b == AIR || isLiquid(b);
    }

    // the hidden room at x/z 8..12 under the middle of the floor, with the alcoves of its 4 chests reaching to 7 and 13
    private static byte hiddenRoom(int x, int dy, int z) {
        int dx = Math.abs(x - 10), dz = Math.abs(z - 10);
        boolean alcove = dx == 2 && dz == 0 || dx == 0 && dz == 2;
        if (dx <= 2 && dz <= 2) {
            // the shaft down to the pressure plate at Y=53
            if (dx <= 1 && dz <= 1 && dy >= -11) return dx == 0 && dz == 0 && dy == -11 ? OTHER : AIR;
            // a chest, with air above it
            if (alcove && dy == -10) return AIR;
            // sandstone, cut and chiseled sandstone, the TNT at Y=51 and the chests
            return OTHER;
        }
        if ((dx == 3 && dz == 0 || dx == 0 && dz == 3) && (dy == -11 || dy == -10)) return OTHER;
        return -1;
    }
}
