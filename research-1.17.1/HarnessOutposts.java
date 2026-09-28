import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.worldgen.StructureFeatures;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.StructureFeature;
import net.minecraft.world.level.levelgen.feature.configurations.JigsawConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.StructureFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.feature.structures.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/*
Checks the finder's OutpostGolems port against the vanilla 1.17.1 server's own outpost generation, run in this JVM
from the Mojang-mapped jar. Runs in a folder with server.properties and eula.txt.

  outposts N START    N random world seeds and outpost chunks: the golems' hitbox blocks from the game against the port
 */
public class HarnessOutposts {
    static MinecraftServer server;
    static ServerLevel level;
    static RegistryAccess registries;
    static ChunkGenerator baseGenerator;
    static StructureManager templates;

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
        baseGenerator = level.getChunkSource().getGenerator();
        templates = server.getStructureManager();
        System.out.println("HARNESS ready");
        try {
            switch (args[0]) {
                case "outposts" -> outposts(Integer.parseInt(args[1]), Long.parseLong(args[2]));
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
    static StructureStart<JigsawConfiguration> outpostStart(ChunkGenerator gen, long seed, ChunkPos chunk) {
        var configured = StructureFeatures.PILLAGER_OUTPOST;
        StructureFeature<JigsawConfiguration> feature = (StructureFeature<JigsawConfiguration>) configured.feature;
        StructureStart<JigsawConfiguration> start = feature.getStartFactory().create(feature, chunk, 0, seed);
        Biome biome = gen.getBiomeSource().getNoiseBiome((chunk.x << 2) + 2, 0, (chunk.z << 2) + 2);
        start.generatePieces(registries, gen, templates, chunk, biome, configured.config, level);
        return start;
    }

    @SuppressWarnings("unchecked")
    static List<int[]> gameGolems(StructureStart<?> start) throws Exception {
        List<int[]> golems = new ArrayList<>();
        Field entities = StructureTemplate.class.getDeclaredField("entityInfoList");
        entities.setAccessible(true);
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece p) || !(p.getElement() instanceof SinglePoolElement e)) continue;
            if (!e.toString().contains("feature_cage1")) continue;
            StructureTemplate template = templates.getOrCreate(new net.minecraft.resources.ResourceLocation("pillager_outpost/feature_cage1"));
            for (StructureTemplate.StructureEntityInfo info : (List<StructureTemplate.StructureEntityInfo>) entities.get(template)) {
                Vec3 local = StructureTemplate.transform(info.pos, Mirror.NONE, p.getRotation(), BlockPos.ZERO);
                double x = local.x + p.getPosition().getX(), z = local.z + p.getPosition().getZ();
                golems.add(new int[]{(int) Math.floor(x - 0.7 + 1e-6), (int) Math.floor(z - 0.7 + 1e-6),
                        (int) Math.ceil(x + 0.7 - 1e-6) - 1, (int) Math.ceil(z + 0.7 - 1e-6) - 1, p.getBoundingBox().minY()});
            }
        }
        return golems;
    }

    static void outposts(int count, long startSeed) throws Exception {
        Random random = new Random(startSeed);
        OutpostGolems port = new OutpostGolems();
        int layouts = 0, withGolems = 0, golemTotal = 0, mismatches = 0;
        for (int n = 0; n < count; n++) {
            long seed = random.nextLong();
            ChunkPos chunk = new ChunkPos(random.nextInt(24), random.nextInt(24));
            ChunkGenerator gen = baseGenerator.withSeed(seed);
            List<int[]> game = gameGolems(outpostStart(gen, seed, chunk));
            int portCount = port.layOut(seed & ((1L << 48) - 1), chunk.x, chunk.z,
                    (x, z) -> gen.getFirstFreeHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level));
            List<int[]> ported = new ArrayList<>();
            for (int i = 0; i < portCount; i++) {
                ported.add(new int[]{port.golem(i, 0), port.golem(i, 1), port.golem(i, 2), port.golem(i, 3), port.golem(i, 4)});
            }
            layouts++;
            if (!game.isEmpty()) withGolems++;
            golemTotal += game.size();
            boolean same = game.size() == ported.size();
            for (int[] g : game) {
                boolean found = false;
                for (int[] q : ported) found |= java.util.Arrays.equals(g, q);
                same &= found;
            }
            if (!same) {
                mismatches++;
                if (mismatches <= 10) {
                    System.out.printf("MISMATCH seed %d chunk %d %d game %s port %s%n", seed, chunk.x, chunk.z,
                            toString(game), toString(ported));
                }
            }
        }
        System.out.printf("done %d layouts, %d with golems, %d golems, %d mismatches%n", layouts, withGolems, golemTotal, mismatches);
    }

    static String toString(List<int[]> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int[] a : list) sb.append(java.util.Arrays.toString(a));
        return sb.append("]").toString();
    }
}
