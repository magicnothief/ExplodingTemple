# Exploding temple over dripstone (Minecraft Java 1.17.1)

Finds 1.17.1 world seeds where a pillager outpost's iron golem is placed inside a desert temple's hidden shaft,
drops onto the pressure plate and blows the temple up by itself, and the shaft opens into a ravine with dripstone
right where you land. Results are sorted by how far the temple is from the world spawn. The seeds found so far are
in [SEEDS.md](SEEDS.md).

1.17 is the last version where this works: the pyramid still sits at Y 64 whatever the ground is like, so a golem
standing on low ground next to it ends up inside the shaft. From 1.18 on the pyramid sinks into the ground and the
golem stands on its floor (see the `claude/ancient-city-pyramid` branch). Normal 1.17 worlds have no dripstone caves
biome, but every biome gets small dripstone clusters in its caves, in 1 chunk in 25. That is the dripstone this
looks for: a patch of dripstone blocks and pointed dripstone on the ravine floor under the shaft.

## Searching for seeds

The cave library the finder uses calls native code through Java's foreign function API and needs Java 23. Gradle
itself runs on whatever Java you have (8 to 23) and compiles and runs the finder with a JDK 23: one that is
installed, or else one it downloads by itself (about 200 MB, once).

```
gradlew.bat run --args="0 281474976710656 1000"      (Windows)
./gradlew run --args="0 281474976710656 1000"        (Linux, macOS)
```

The arguments are the first base seed, the base seed to stop before (281474976710656 is the end, so it runs until
you stop it), how far the temple may be from the world spawn in blocks (1000 by default), and optionally the number
of threads (all cores by default). There is no minimum: every temple found within the distance is a result. A
smaller distance only checks temples closer to 0 0 and gets through base seeds faster: on 4 cores, 300 blocks went
through about 4 times as many base seeds per second as 1000.

Each result is printed as it is found and kept in `results.txt`, closest first:

```
Got full world seed: 779 blocks | -7221521733626352479 | /tp 26 100 -998 | spawn -40 76 -222 | golem cage Y=63 | dripstone under 1 of the shaft's 9 columns (the closest so far)
```

That is the distance from the world spawn to the shaft, the seed, a teleport to the shaft, the world spawn, where
the golem's cage is, and under how many of the shaft's 9 columns a player falling from the temple floor lands on
dripstone. A new run reads `results.txt` back, so the list stays sorted over stopped and resumed searches. To
resume, start again at the first base seed plus the count on the last `[progress]` line.

`got a candidate structure seed` is not a result yet. A structure seed decides where the structures, caves and
dripstone go, but the biomes, the terrain and the spawn come from the whole world seed, and 65536 world seeds share
each structure seed. The finder checks all of them and prints how many get through each check:

```
  structure seed 268862237857, sister seeds left after each check: biomes 2179, spawn estimate close 1436, structures spawn 1436, golem drops 67, spawn close 43, ravine and dripstone with vanilla carvers 43
```

- `biomes`: a desert at the temple and an outpost biome at the outpost
- `spawn estimate close`, `structures spawn`: quick versions of the spawn and biome checks
- `golem drops`: built on the real terrain, the outpost puts its golem low enough in the shaft to fall. The ground
  there has to be at sea level, which rules out most structure seeds
- `spawn close`: the world spawn is within the distance you gave
- `ravine and dripstone with vanilla carvers`: the ravine is still under the shaft with the game's own caves, and
  there is still dripstone where you land. These are the results

Sister seeds share the temple and its dripstone, so the results come in groups with the same `/tp`.

## Checking a seed

`tools/verify_dripstone.py` generates the world in the vanilla 1.17.1 server, lets the golem set off the TNT and
reports what a player falling down each column of the shaft lands on, and where the world spawn is.

1. Download the official 1.17.1 server from
   https://piston-data.mojang.com/v1/objects/a16d67e5807f57fc4e550299cf20226194497dc2/server.jar
   and save it as `tools/server-1.17.1.jar`.
2. Run it with the seed and the `/tp` x and z (Python 3.8 or newer, and Java 16 or newer for the server):

   ```
   python tools/verify_dripstone.py -7221521733626352479 26 -998 --accept-eula
   ```

   `--accept-eula` means you accept the Minecraft EULA (https://aka.ms/MinecraftEULA), which the server needs.

It takes about a minute and prints a line per column and then

```
RESULT seed=-7221521733626352479 shaft=26,-998: exploded=True, dripstone under 1 of the shaft's 9 columns, world spawn -40 76 -222 (779 blocks away), 70s
```

In game, break through the blue terracotta in the middle of the temple floor and drop straight down.

## What the finder checks

1. The outpost's layout: it gets up to 15 features around its base plate, and a golem cage lands over the shaft
   of a temple 1 or 2 chunks away. This is the game's jigsaw placement, checked against the 1.17.1 server on 5000
   outposts with no difference.
2. A ravine under the whole shaft from Y 30 up to Y 48 with solid ground at Y 59, so the explosion opens the shaft
   into it and the temple's sandstone foundation doesn't plug it.
3. The dripstone. The clusters depend on every block around them, so the finder builds the blocks the game has when
   they are placed: the caves and ravines, the terrain, and then for the temple's chunk and its 8 neighbours the
   lava lakes, dungeons, the pyramid, the ores, dirt and gravel, and the dripstone clusters themselves, all with the
   game's own random numbers. Checked against the game on 300 chunks: given the game's blocks, the clusters come
   out identical every time, and built from scratch they come out identical in 124 of 126 desert chunks. The game
   decorates chunks in the order it happens to generate them, so the finder only keeps temples with dripstone both
   when the temple's chunk is decorated before its neighbours and after them. Amethyst geodes, fossils and
   mineshafts aren't modelled, so temples with one nearby are skipped.
4. With the whole world seed: the biomes, the real spawn, the outpost built on the real terrain and the game's own
   caves (which skip everything next to water), then the dripstone again with the real biomes.

Two results were checked with `verify_dripstone.py`: both blew up within 5 seconds of being loaded, with dripstone
under 1 and under 5 of the shaft's columns as predicted or better, and the spawn where the finder said.
`research-1.17.1` has the code these checks against the game were made with.
