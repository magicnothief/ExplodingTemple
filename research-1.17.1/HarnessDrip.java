import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.StructureFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/*
Replays one chunk's feature decoration in the vanilla 1.17.1 server (the Mojang-mapped jar, in this JVM) the way
ChunkStatus.FEATURES and Biome#generate do it, and snapshots the 3x3 chunks around it three times: before any of
the chunk's features, right before its rare_dripstone_cluster feature and right after it. Runs in a folder with
server.properties and eula.txt.

  biomes                   every biome's UNDERGROUND_DECORATION step with the feature indexes
  clusters N PICK OUT      N chunks of the world whose rare dripstone cluster rolls its 1 in 25, snapshots to OUT
 */
public class HarnessDrip {
    static MinecraftServer server;
    static ServerLevel level;
    static RegistryAccess registries;

    static final int UNDERGROUND_DECORATION = 7;
    static final int HEIGHT = 128;
    static final ResourceLocation RARE_CLUSTER = new ResourceLocation("rare_dripstone_cluster");

    public static void main(String[] args) throws Exception {
        new Thread(() -> {
            try {
                net.minecraft.server.Main.main(new String[]{"nogui"});
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "starter").start();
        server = findServer();
        while (!server.isReady()) Thread.sleep(200);
        level = server.overworld();
        registries = server.registryAccess();
        System.out.println("HARNESS ready, seed " + level.getSeed());
        try {
            switch (args[0]) {
                case "biomes" -> biomes();
                case "steps" -> steps(Integer.parseInt(args[1]));
                case "clusters" -> clusters(Integer.parseInt(args[1]), Long.parseLong(args[2]), args[3],
                        args.length > 4 && args[4].equals("desert"));
                default -> System.out.println("unknown mode " + args[0]);
            }
        } catch (Throwable t) {
            t.printStackTrace(System.out);
        } finally {
            System.out.flush();
            Runtime.getRuntime().halt(0);
        }
    }

    static MinecraftServer findServer() throws Exception {
        while (true) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (!t.getName().equals("Server thread")) continue;
                Field holderField = Thread.class.getDeclaredField("holder");
                holderField.setAccessible(true);
                Object holder = holderField.get(t);
                Field taskField = holder.getClass().getDeclaredField("task");
                taskField.setAccessible(true);
                Object task = taskField.get(holder);
                for (Field f : task.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.get(task) instanceof AtomicReference<?> ref && ref.get() instanceof MinecraftServer s) return s;
                }
            }
            Thread.sleep(100);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, List<StructureFeature<?>>> structuresByStep(Biome biome) throws Exception {
        Field f = Biome.class.getDeclaredField("structuresByStep");
        f.setAccessible(true);
        return (Map<Integer, List<StructureFeature<?>>>) f.get(biome);
    }

    static ResourceLocation featureKey(ConfiguredFeature<?, ?> feature) {
        return registries.registryOrThrow(Registry.CONFIGURED_FEATURE_REGISTRY).getKey(feature);
    }

    // the index setFeatureSeed gets for the rare dripstone cluster in this biome, or -1
    static int clusterIndex(Biome biome) throws Exception {
        int n = structuresByStep(biome).getOrDefault(UNDERGROUND_DECORATION, Collections.emptyList()).size();
        List<List<Supplier<ConfiguredFeature<?, ?>>>> features = biome.getGenerationSettings().features();
        if (features.size() <= UNDERGROUND_DECORATION) return -1;
        for (Supplier<ConfiguredFeature<?, ?>> s : features.get(UNDERGROUND_DECORATION)) {
            if (RARE_CLUSTER.equals(featureKey(s.get()))) return n;
            n++;
        }
        return -1;
    }

    static void biomes() throws Exception {
        Registry<Biome> biomes = registries.registryOrThrow(Registry.BIOME_REGISTRY);
        for (Biome biome : biomes) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StructureFeature<?> s : structuresByStep(biome).getOrDefault(UNDERGROUND_DECORATION, Collections.emptyList())) {
                sb.append(n++).append(':').append(Registry.STRUCTURE_FEATURE.getKey(s)).append(' ');
            }
            List<List<Supplier<ConfiguredFeature<?, ?>>>> features = biome.getGenerationSettings().features();
            if (features.size() > UNDERGROUND_DECORATION) {
                for (Supplier<ConfiguredFeature<?, ?>> s : features.get(UNDERGROUND_DECORATION)) {
                    sb.append(n++).append(':').append(featureKey(s.get())).append(' ');
                }
            }
            System.out.printf("BIOME %s cluster=%d | %s%n", biomes.getKey(biome), clusterIndex(biome), sb);
        }
    }

    // one generation step of every biome, as "index:feature"
    static void steps(int step) throws Exception {
        Registry<Biome> biomes = registries.registryOrThrow(Registry.BIOME_REGISTRY);
        for (Biome biome : biomes) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StructureFeature<?> s : structuresByStep(biome).getOrDefault(step, Collections.emptyList())) {
                sb.append(n++).append(':').append(Registry.STRUCTURE_FEATURE.getKey(s)).append(' ');
            }
            List<List<Supplier<ConfiguredFeature<?, ?>>>> features = biome.getGenerationSettings().features();
            if (features.size() > step) {
                for (Supplier<ConfiguredFeature<?, ?>> s : features.get(step)) {
                    sb.append(n++).append(':').append(featureKey(s.get())).append(' ');
                }
            }
            System.out.printf("STEP%d %s | %s%n", step, biomes.getKey(biome), sb);
        }
    }

    static final byte STONE = 0, AIR = 1, WATER = 2, LAVA = 3, OTHER = 4, DRIPSTONE_BLOCK = 5, POINTED_DRIPSTONE = 6,
            DIRT = 7, POINTED_DRIPSTONE_WATERLOGGED = 8, WATERLOGGED = 9;

    static byte category(BlockState state) {
        if (state.isAir()) return AIR;
        if (state.is(Blocks.WATER)) return WATER;
        if (state.is(Blocks.LAVA)) return LAVA;
        if (state.is(BlockTags.BASE_STONE_OVERWORLD)) return STONE;
        if (state.is(Blocks.DIRT)) return DIRT;
        if (state.is(Blocks.DRIPSTONE_BLOCK)) return DRIPSTONE_BLOCK;
        if (state.is(Blocks.POINTED_DRIPSTONE)) {
            return state.getValue(BlockStateProperties.WATERLOGGED) ? POINTED_DRIPSTONE_WATERLOGGED : POINTED_DRIPSTONE;
        }
        if (state.getFluidState().is(FluidTags.WATER)) return WATERLOGGED;
        return OTHER;
    }

    // the 3x3 chunks around the center, x then z then y
    static byte[] snapshot(WorldGenRegion region, ChunkPos center) {
        byte[] out = new byte[48 * 48 * HEIGHT];
        int minX = center.getMinBlockX() - 16, minZ = center.getMinBlockZ() - 16;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int x = 0; x < 48; x++) {
            for (int z = 0; z < 48; z++) {
                for (int y = 0; y < HEIGHT; y++) {
                    out[i++] = category(region.getBlockState(pos.set(minX + x, y, minZ + z)));
                }
            }
        }
        return out;
    }

    record Replay(byte[] before, byte[] beforeCluster, byte[] afterCluster, short[] oceanFloor, String biome, int index,
                  long decorationSeed) {
    }

    // ChunkStatus.FEATURES then ChunkGenerator#applyBiomeDecoration and Biome#generate, stopping after the cluster
    static Replay replay(ChunkPos pos) throws Exception {
        ServerChunkCache source = level.getChunkSource();
        ChunkGenerator generator = source.getGenerator();
        List<ChunkAccess> chunks = new ArrayList<>();
        for (int dz = -8; dz <= 8; dz++) {
            for (int dx = -8; dx <= 8; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                ChunkStatus status = d <= 1 ? ChunkStatus.LIQUID_CARVERS : ChunkStatus.STRUCTURE_STARTS;
                ChunkAccess chunk = source.getChunk(pos.x + dx, pos.z + dz, status, true);
                if (d <= 1 && chunk.getStatus() != ChunkStatus.LIQUID_CARVERS) return null;
                chunks.add(chunk);
            }
        }
        WorldGenRegion region = new WorldGenRegion(level, chunks, ChunkStatus.FEATURES, 1);
        StructureFeatureManager structures = level.structureFeatureManager().forWorldGenRegion(region);
        byte[] before = snapshot(region, pos);
        short[] oceanFloor = new short[48 * 48];
        for (int x = 0; x < 48; x++) {
            for (int z = 0; z < 48; z++) {
                oceanFloor[x * 48 + z] = (short) region.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,
                        pos.getMinBlockX() - 16 + x, pos.getMinBlockZ() - 16 + z);
            }
        }

        BlockPos origin = new BlockPos(pos.getMinBlockX(), region.getMinBuildHeight(), pos.getMinBlockZ());
        Biome biome = generator.getBiomeSource().getPrimaryBiome(pos);
        WorldgenRandom random = new WorldgenRandom();
        long decorationSeed = random.setDecorationSeed(region.getSeed(), origin.getX(), origin.getZ());
        List<List<Supplier<ConfiguredFeature<?, ?>>>> features = biome.getGenerationSettings().features();
        Map<Integer, List<StructureFeature<?>>> byStep = structuresByStep(biome);
        int minY = region.getMinBuildHeight() + 1, maxY = region.getMaxBuildHeight() - 1;
        for (int step = 0; step <= UNDERGROUND_DECORATION; step++) {
            int n = 0;
            if (structures.shouldGenerateFeatures()) {
                for (StructureFeature<?> structure : byStep.getOrDefault(step, Collections.emptyList())) {
                    random.setFeatureSeed(decorationSeed, n, step);
                    BoundingBox box = new BoundingBox(origin.getX(), minY, origin.getZ(), origin.getX() + 15, maxY, origin.getZ() + 15);
                    structures.startsForFeature(SectionPos.of(origin), structure)
                            .forEach(start -> start.placeInChunk(region, structures, generator, random, box, pos));
                    n++;
                }
            }
            if (features.size() <= step) continue;
            for (Supplier<ConfiguredFeature<?, ?>> supplier : features.get(step)) {
                ConfiguredFeature<?, ?> feature = supplier.get();
                random.setFeatureSeed(decorationSeed, n, step);
                if (step == UNDERGROUND_DECORATION && RARE_CLUSTER.equals(featureKey(feature))) {
                    byte[] beforeCluster = snapshot(region, pos);
                    feature.place(region, generator, random, origin);
                    byte[] afterCluster = snapshot(region, pos);
                    String name = String.valueOf(registries.registryOrThrow(Registry.BIOME_REGISTRY).getKey(biome));
                    return new Replay(before, beforeCluster, afterCluster, oceanFloor, name, n, decorationSeed);
                }
                feature.place(region, generator, random, origin);
                n++;
            }
        }
        return null;
    }

    static void clusters(int count, long pickSeed, String out, boolean desertOnly) throws Exception {
        Random pick = new Random(pickSeed);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        long seed = level.getSeed();
        Set<Long> used = new HashSet<>();
        int written = 0, rolled = 0, looked = 0;
        try (DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(out)))) {
            while (written < count) {
                int cx = (pick.nextBoolean() ? 1 : -1) * (100 + pick.nextInt(3000));
                int cz = (pick.nextBoolean() ? 1 : -1) * (100 + pick.nextInt(3000));
                ChunkPos pos = new ChunkPos(cx, cz);
                looked++;
                Biome primary = generator.getBiomeSource().getPrimaryBiome(pos);
                int index = clusterIndex(primary);
                if (index < 0) continue;
                if (desertOnly && !String.valueOf(registries.registryOrThrow(Registry.BIOME_REGISTRY).getKey(primary)).contains("desert")) continue;
                WorldgenRandom random = new WorldgenRandom();
                long decorationSeed = random.setDecorationSeed(seed, pos.getMinBlockX(), pos.getMinBlockZ());
                random.setFeatureSeed(decorationSeed, index, UNDERGROUND_DECORATION);
                if (!(random.nextFloat() < 1.0f / 25)) continue;
                rolled++;
                // keep the chunks each replay touches apart from the others
                boolean near = false;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) near |= used.contains(ChunkPos.asLong(cx + dx, cz + dz));
                }
                if (near) continue;
                used.add(pos.toLong());
                Replay replay = server.submit(() -> {
                    try {
                        return replay(pos);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).join();
                if (replay == null) {
                    System.out.printf("SKIP %d %d%n", cx, cz);
                    continue;
                }
                dos.writeLong(seed);
                dos.writeInt(cx);
                dos.writeInt(cz);
                dos.writeInt(replay.index);
                dos.writeUTF(replay.biome);
                dos.write(replay.before);
                dos.write(replay.beforeCluster);
                dos.write(replay.afterCluster);
                for (short h : replay.oceanFloor) dos.writeShort(h);
                written++;
                int changed = 0;
                for (int i = 0; i < replay.afterCluster.length; i++) {
                    if (replay.afterCluster[i] != replay.beforeCluster[i]) changed++;
                }
                System.out.printf("CASE %d chunk %d %d biome %s index %d, %d blocks changed by the cluster%n",
                        written, cx, cz, replay.biome, replay.index, changed);
            }
        }
        System.out.printf("done: looked at %d chunks, %d rolled a cluster, wrote %d%n", looked, rolled, written);
    }
}
