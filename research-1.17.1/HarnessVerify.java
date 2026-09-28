import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/*
Checks a finder result in the vanilla 1.17.1 server (Mojang-mapped jar, run in this JVM in a folder with
server.properties holding the seed and eula.txt): force-loads the chunks around the temple so the outpost's golem
ticks, watches for it to set off the TNT, and reports what a player falling down each of the shaft's 9 columns lands
on.

  verify SHAFT_X SHAFT_Z SECONDS
 */
public class HarnessVerify {
    static MinecraftServer server;
    static ServerLevel level;

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
        System.out.println("HARNESS ready, seed " + level.getSeed());
        try {
            verify(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
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

    static <T> T onServer(java.util.function.Supplier<T> task) {
        return server.submit(task).join();
    }

    static String name(BlockState state) {
        return Registry.BLOCK.getKey(state.getBlock()).getPath();
    }

    static void verify(int shaftX, int shaftZ, int seconds) throws Exception {
        // the shaft is at 9..11 in the temple's chunk
        int px = shaftX - 10, pz = shaftZ - 10;
        int cx = px >> 4, cz = pz >> 4;
        onServer(() -> {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) level.setChunkForced(cx + dx, cz + dz, true);
            }
            return null;
        });
        long start = System.currentTimeMillis();
        boolean exploded = false;
        String golems = "";
        while (System.currentTimeMillis() - start < seconds * 1000L) {
            Thread.sleep(1000);
            int tnt = onServer(() -> {
                int n = 0;
                for (int x = px + 9; x <= px + 11; x++) {
                    for (int z = pz + 9; z <= pz + 11; z++) {
                        if (level.getBlockState(new BlockPos(x, 51, z)).is(Blocks.TNT)) n++;
                    }
                }
                return n;
            });
            golems = onServer(() -> {
                StringBuilder sb = new StringBuilder();
                for (IronGolem g : level.getEntitiesOfClass(IronGolem.class, new AABB(px - 16, 0, pz - 16, px + 36, 256, pz + 36))) {
                    sb.append(String.format(" (%.1f %.1f %.1f)", g.getX(), g.getY(), g.getZ()));
                }
                return sb.toString();
            });
            long secs = (System.currentTimeMillis() - start) / 1000;
            System.out.printf("VERIFY t=%ds tnt=%d golems:%s%n", secs, tnt, golems);
            if (tnt == 0 && secs > 5) {
                exploded = true;
                // let the explosion and falling blocks settle
                Thread.sleep(5000);
                break;
            }
        }
        List<String> landings = onServer(() -> {
            List<String> out = new ArrayList<>();
            for (int x = px + 9; x <= px + 11; x++) {
                for (int z = pz + 9; z <= pz + 11; z++) {
                    int y = 63;
                    while (y > 0 && level.getBlockState(new BlockPos(x, y, z)).isAir()) y--;
                    // from the floor of the crater down: the column below the pyramid's floor
                    int top = y;
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("%d %d: first block below the floor at y=%d %s", x, z, top,
                            name(level.getBlockState(new BlockPos(x, top, z)))));
                    // under the chamber: from Y=48 down through air to the landing
                    int fall = 48;
                    while (fall > 0 && level.getBlockState(new BlockPos(x, fall, z)).isAir()) fall--;
                    sb.append(String.format(", falling from y=48 lands at y=%d on %s", fall,
                            name(level.getBlockState(new BlockPos(x, fall, z)))));
                    out.add(sb.toString());
                }
            }
            return out;
        });
        System.out.printf("VERIFY exploded=%s%n", exploded);
        for (String l : landings) System.out.println("VERIFY " + l);
    }
}
