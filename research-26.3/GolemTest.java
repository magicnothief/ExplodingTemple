import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/*
Research harness: runs the vanilla 26.3 server in this JVM and uses its own structure generation to see whether a
pillager outpost's golem can end up in a desert pyramid's hidden shaft. Run it in a directory with a server.properties
(any seed, default world type) and the eula.txt the server needs; see README.md.

  find N [START]          N random structure seeds: outposts whose golem or allays stand over a pyramid shaft
  sisters S SAMPLES       structure seed S, up to SAMPLES world seeds where both structures spawn: each mob over the
                          shaft, its feet against the lowest ground no outpost piece covers in the pyramid's footprint
  scan N START SAMPLES    both of the above for N random structure seeds, with a tally of those margins
  order                   the order the surface structures are placed in within a chunk
 */
public class GolemTest {
    static MinecraftServer server;
    static ServerLevel level;
    static RegistryAccess registries;
    static ChunkGenerator generator;
    static NoiseGeneratorSettings settings;
    static HolderGetter<NormalNoise> noises;
    static StructureTemplateManager templates;
    static Holder<Structure> outpost, pyramid;
    static RandomSpreadStructurePlacement outpostPlacement, pyramidPlacement;

    public static void main(String[] args) throws Exception {
        Thread starter = new Thread(() -> {
            try {
                net.minecraft.server.Main.main(new String[]{"--nogui"});
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "starter");
        starter.start();
        server = findServer();
        while (!server.isReady()) Thread.sleep(200);
        level = server.overworld();
        registries = server.registryAccess();
        generator = level.getChunkSource().getGenerator();
        settings = ((NoiseBasedChunkGenerator) generator).generatorSettings().value();
        noises = registries.lookupOrThrow(Registries.NOISE);
        templates = server.getStructureTemplateManager();
        Registry<Structure> structures = registries.lookupOrThrow(Registries.STRUCTURE);
        outpost = structures.getOrThrow(BuiltinStructures.PILLAGER_OUTPOST);
        pyramid = structures.getOrThrow(BuiltinStructures.DESERT_PYRAMID);
        Registry<StructureSet> sets = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        outpostPlacement = (RandomSpreadStructurePlacement) sets.getValue(BuiltinStructureSets.PILLAGER_OUTPOSTS).placement();
        pyramidPlacement = (RandomSpreadStructurePlacement) sets.getValue(BuiltinStructureSets.DESERT_PYRAMIDS).placement();
        System.out.println("HARNESS ready");

        try {
            switch (args[0]) {
                case "find" -> find(Long.parseLong(args[1]), args.length > 2 ? Long.parseLong(args[2]) : 1);
                case "sisters" -> sisters(Long.parseLong(args[1]), Integer.parseInt(args[2]));
                case "order" -> registries.lookupOrThrow(Registries.STRUCTURE).listElements()
                        .filter(h -> h.value().step() == net.minecraft.world.level.levelgen.GenerationStep.Decoration.SURFACE_STRUCTURES)
                        .forEach(h -> System.out.println("ORDER " + h.key().identifier()));
                case "scan" -> scan(Long.parseLong(args[1]), Long.parseLong(args[2]), Integer.parseInt(args[3]));
                default -> System.out.println("unknown mode " + args[0]);
            }
        } finally {
            System.out.flush();
            Runtime.getRuntime().halt(0);
        }
    }

    // The server thread runs a lambda holding the server in an AtomicReference (MinecraftServer.spin)
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

    static RandomState randomState(long seed) {
        return RandomState.create(noises, seed, settings);
    }

    static StructureStart generate(Holder<Structure> structure, long seed, RandomState rs, ChunkPos chunk, boolean checkBiome) {
        Climate.Sampler sampler = rs.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
        return structure.value().generate(structure, Level.OVERWORLD, registries, generator, generator.getBiomeSource(), sampler, rs,
                templates, seed, chunk, 0, level, checkBiome ? structure.value().biomes()::contains : b -> true);
    }

    // legacy_type_1 frequency reduction (the 1.16 outpost weak-seed check)
    static boolean outpostFrequency(long seed, int chunkX, int chunkZ) {
        int i = chunkX >> 4, j = chunkZ >> 4;
        Random r = new Random((long) (i ^ j << 4) ^ seed);
        r.nextInt();
        return r.nextInt(5) == 0;
    }

    record Mob(String type, Vec3 pos, BoundingBox cage) {}

    // golems and allays the outpost's cages spawn, in world coordinates
    static List<Mob> mobs(StructureStart start) {
        List<Mob> mobs = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece p) || !(p.getElement() instanceof SinglePoolElement e)) continue;
            String name = e.getTemplateLocation().getPath();
            if (!name.contains("cage")) continue;
            StructureTemplate template = templates.getOrCreate(e.getTemplateLocation());
            for (StructureTemplate.StructureEntityInfo info : entities(template)) {
                Vec3 local = StructureTemplate.transform(info.pos, Mirror.NONE, p.getRotation(), BlockPos.ZERO);
                Vec3 world = local.add(p.getPosition().getX(), p.getPosition().getY(), p.getPosition().getZ());
                String id = info.nbt.getStringOr("id", "?");
                mobs.add(new Mob(id.replace("minecraft:", ""), world, p.getBoundingBox()));
            }
        }
        return mobs;
    }

    @SuppressWarnings("unchecked")
    static List<StructureTemplate.StructureEntityInfo> entities(StructureTemplate template) {
        try {
            Field f = StructureTemplate.class.getDeclaredField("entityInfoList");
            f.setAccessible(true);
            return (List<StructureTemplate.StructureEntityInfo>) f.get(template);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    static boolean inShaft(Vec3 pos, ChunkPos pyramidChunk) {
        int x = (int) Math.floor(pos.x) - pyramidChunk.getMinBlockX(), z = (int) Math.floor(pos.z) - pyramidChunk.getMinBlockZ();
        return x >= 9 && x <= 11 && z >= 9 && z <= 11;
    }

    static void find(long count, long startSeed) {
        Random random = new Random(startSeed);
        long close = 0, generated = 0;
        java.util.Map<String, Integer> mobCount = new java.util.TreeMap<>();
        double nearest = 1e9;
        long t0 = System.nanoTime();
        for (long n = 0; n < count; n++) {
            long seed = random.nextLong() & ((1L << 48) - 1);
            ChunkPos p = pyramidPlacement.getPotentialStructureChunk(seed, 0, 0);
            ChunkPos o = outpostPlacement.getPotentialStructureChunk(seed, 0, 0);
            if (Math.abs(o.x() - p.x()) > 2 || Math.abs(o.z() - p.z()) > 2 || !outpostFrequency(seed, o.x(), o.z())) continue;
            close++;
            RandomState rs = randomState(seed);
            StructureStart start = generate(outpost, seed, rs, o, false);
            generated++;
            for (Mob m : mobs(start)) {
                mobCount.merge(m.type(), 1, Integer::sum);
                double dx = m.pos().x - (p.getMinBlockX() + 10.5), dz = m.pos().z - (p.getMinBlockZ() + 10.5);
                nearest = Math.min(nearest, Math.hypot(dx, dz));
                if (inShaft(m.pos(), p)) {
                    System.out.printf("CANDIDATE %d pyramid %d %d outpost %d %d %s at %.2f %.2f %.2f%n", seed, p.x(), p.z(), o.x(), o.z(),
                            m.type(), m.pos().x, m.pos().y, m.pos().z);
                }
            }
            if (generated % 500 == 0) System.out.printf("progress %d seeds, %d outposts generated%n", n + 1, generated);
        }
        System.out.printf("done %d seeds, %d outposts close to a pyramid, mobs %s, nearest to a shaft centre %.1f, %.0f s%n",
                count, close, mobCount, nearest, (System.nanoTime() - t0) / 1e9);
    }

    static void sisters(long structureSeed, int samples) {
        ChunkPos p = pyramidPlacement.getPotentialStructureChunk(structureSeed, 0, 0);
        ChunkPos o = outpostPlacement.getPotentialStructureChunk(structureSeed, 0, 0);
        int valid = 0, inShaft = 0, best = Integer.MIN_VALUE;
        for (long upper = 0; upper < 65536 && valid < samples; upper++) {
            long seed = upper << 48 | structureSeed;
            RandomState rs = randomState(seed);
            StructureStart pyr = generate(pyramid, seed, rs, p, true);
            if (!pyr.isValid()) continue;
            StructureStart out = generate(outpost, seed, rs, o, true);
            if (!out.isValid()) continue;
            valid++;
            BoundingBox foot = pyr.getPieces().get(0).getBoundingBox();
            for (Mob m : mobs(out)) {
                if (!inShaft(m.pos(), p)) continue;
                inShaft++;
                int feet = (int) Math.floor(m.pos().y);
                // the lowest untouched ground in the footprint: columns no outpost piece covers
                int lowest = Integer.MAX_VALUE, lowX = 0, lowZ = 0;
                for (int x = foot.minX(); x <= foot.maxX(); x++) {
                    for (int z = foot.minZ(); z <= foot.maxZ(); z++) {
                        if (covered(out, x, z)) continue;
                        int h = generator.getFirstFreeHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                        if (h < lowest) {
                            lowest = h;
                            lowX = x;
                            lowZ = z;
                        }
                    }
                }
                int margin = lowest - feet;
                best = Math.max(best, margin);
                System.out.printf("SISTER %d " + m.type() + " feet %d cage bottom %d lowest free ground %d at %d %d margin %d%n",
                        seed, feet, m.cage().minY(), lowest, lowX, lowZ, margin);
            }
        }
        System.out.printf("done structure seed %d: %d sisters with both structures, golem in the shaft in %d, best margin %d%n",
                structureSeed, valid, inShaft, best);
    }

    // Candidates where a golem or allay stands within 4.5 blocks of a shaft's centre in the structure seed's own world,
    // then every sister seed (up to samples with both structures) where it is really over the shaft: its feet against
    // the lowest untouched ground in the pyramid's footprint. The check stops at the first column below the feet, so
    // a margin of 0 or more is exact but the negative ones are only upper bounds.
    static void scan(long count, long startSeed, int samples) {
        Random random = new Random(startSeed);
        java.util.Map<Integer, Integer> margins = new java.util.TreeMap<>();
        long candidates = 0, tested = 0;
        long t0 = System.nanoTime();
        for (long n = 0; n < count; n++) {
            long structureSeed = random.nextLong() & ((1L << 48) - 1);
            ChunkPos p = pyramidPlacement.getPotentialStructureChunk(structureSeed, 0, 0);
            ChunkPos o = outpostPlacement.getPotentialStructureChunk(structureSeed, 0, 0);
            if (Math.abs(o.x() - p.x()) > 2 || Math.abs(o.z() - p.z()) > 2 || !outpostFrequency(structureSeed, o.x(), o.z())) continue;
            boolean near = false;
            for (Mob m : mobs(generate(outpost, structureSeed, randomState(structureSeed), o, false))) {
                double dx = m.pos().x - (p.getMinBlockX() + 10.5), dz = m.pos().z - (p.getMinBlockZ() + 10.5);
                if (Math.abs(dx) <= 4.5 && Math.abs(dz) <= 4.5) near = true;
            }
            if (!near) continue;
            candidates++;
            int valid = 0;
            for (long upper = 0; upper < 65536 && valid < samples; upper++) {
                long seed = upper << 48 | structureSeed;
                RandomState rs = randomState(seed);
                StructureStart pyr = generate(pyramid, seed, rs, p, true);
                if (!pyr.isValid()) continue;
                StructureStart out = generate(outpost, seed, rs, o, true);
                if (!out.isValid()) continue;
                valid++;
                BoundingBox foot = pyr.getPieces().get(0).getBoundingBox();
                for (Mob m : mobs(out)) {
                    if (!inShaft(m.pos(), p)) continue;
                    tested++;
                    int feet = (int) Math.floor(m.pos().y);
                    int lowest = Integer.MAX_VALUE;
                    for (int x = foot.minX(); x <= foot.maxX() && lowest >= feet; x++) {
                        for (int z = foot.minZ(); z <= foot.maxZ() && lowest >= feet; z++) {
                            if (covered(out, x, z)) continue;
                            lowest = Math.min(lowest, generator.getFirstFreeHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs));
                        }
                    }
                    int margin = Math.max(-3, lowest - feet);
                    margins.merge(margin, 1, Integer::sum);
                    if (margin >= 0) {
                        System.out.printf("REACHES %d %s feet %d lowest free ground %d margin %d pyramid %d %d%n", seed, m.type(), feet, lowest, margin, p.x(), p.z());
                    }
                }
            }
            System.out.printf("candidate %d: %d sisters with both structures (%.0f s), margins so far %s%n", structureSeed, valid,
                    (System.nanoTime() - t0) / 1e9, margins);
        }
        System.out.printf("done %d structure seeds, %d candidates, %d golems/allays over a shaft, margins (-3 means -3 or less) %s%n",
                count, candidates, tested, margins);
    }

    static boolean covered(StructureStart start, int x, int z) {
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox b = piece.getBoundingBox();
            if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) {
                if (piece instanceof PoolElementStructurePiece pe && pe.getElement() instanceof SinglePoolElement e
                        && e.getTemplateLocation().getPath().endsWith("feature_plate")) continue;
                return true;
            }
        }
        return false;
    }
}
