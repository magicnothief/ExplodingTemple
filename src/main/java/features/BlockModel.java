package features;

import java.util.HashMap;
import java.util.Map;

/*
Blocks reduced to what 1.17.1's underground features tell apart, starting from the terrain as the carvers leave it,
with whatever the modelled features place over it.
 */
public final class BlockModel {
    // STONE is base stone (stone, granite, diorite, andesite, tuff, deepslate), which ores and dripstone replace. Dirt
    // can be replaced by dripstone but isn't base stone. OTHER is any other solid block (sand, sandstone, gravel, ores,
    // bedrock). Pointed dripstone placed in water, and any other waterlogged block, count as water next to a pool.
    public static final byte STONE = 0, AIR = 1, WATER = 2, LAVA = 3, OTHER = 4, DRIPSTONE_BLOCK = 5, POINTED_DRIPSTONE = 6,
            DIRT = 7, POINTED_DRIPSTONE_WATERLOGGED = 8, WATERLOGGED = 9;

    public interface Terrain {
        byte get(int x, int y, int z);

        // the OCEAN_FLOOR_WG heightmap after carving: the first Y above the column's top block that isn't air or
        // liquid, which the features don't change
        int oceanFloor(int x, int z);
    }

    private final Terrain terrain;
    private final Map<Long, Byte> placed = new HashMap<>();

    public BlockModel(Terrain terrain) {
        this.terrain = terrain;
    }

    public byte get(int x, int y, int z) {
        Byte b = placed.get(key(x, y, z));
        return b != null ? b : terrain.get(x, y, z);
    }

    public void set(int x, int y, int z, byte b) {
        placed.put(key(x, y, z), b);
    }

    public int oceanFloor(int x, int z) {
        return terrain.oceanFloor(x, z);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    public static boolean isAirOrWater(byte b) {
        return b == AIR || b == WATER;
    }

    public static boolean isLiquid(byte b) {
        return b == WATER || b == LAVA;
    }

    // Material#isSolid: everything but air, liquids and waterlogged plants
    public static boolean isSolid(byte b) {
        return b != AIR && b != WATER && b != LAVA && b != WATERLOGGED;
    }

    // a block whose fluid is water
    public static boolean hasWater(byte b) {
        return b == WATER || b == WATERLOGGED || b == POINTED_DRIPSTONE_WATERLOGGED;
    }

    public static boolean isPointedDripstone(byte b) {
        return b == POINTED_DRIPSTONE || b == POINTED_DRIPSTONE_WATERLOGGED;
    }

    public static boolean isDripstone(byte b) {
        return b == DRIPSTONE_BLOCK || isPointedDripstone(b);
    }

    /** What a player falling straight down this column from fromY lands on: the first block that isn't air. */
    public byte landing(int x, int fromY, int z) {
        for (int y = fromY; y > 0; y--) {
            byte b = get(x, y, z);
            if (b != AIR) return b;
        }
        return OTHER;
    }
}
