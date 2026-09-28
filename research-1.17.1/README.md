# How the 1.17.1 models were checked against the game

The finder's ports of 1.17.1 world generation were compared with the vanilla 1.17.1 server itself. The `Harness*`
programs run the server inside their own JVM, from a Mojang-mapped server jar (the official `server.jar` remapped
with the official mappings, e.g. with SpecialSource), and call its world generation directly. They start the server
in the current folder, so run them in a folder with a `server.properties` (holding the seed) and an `eula.txt`, and
with `--add-opens java.base/java.lang=ALL-UNNAMED` (they find the running server by reflection):

```
javac --release 21 -cp server-mojmap.jar -d classes HarnessDrip.java
java --add-opens java.base/java.lang=ALL-UNNAMED -cp classes:server-mojmap.jar HarnessDrip clusters 150 1 snapshots.bin
```

## The outpost layout (`HarnessOutposts`)

`outposts N SEED` lays out N outposts on random seeds and chunks with the game's own jigsaw code on the real terrain,
and compares the golems' positions with `OutpostGolems`. 5000 outposts: no difference.

## The dripstone (`HarnessDrip`, `FeatureTest`, `CarverTest`)

`HarnessDrip clusters N PICK OUT [desert]` picks random chunks whose rare dripstone cluster rolls its 1 in 25 and
replays each one's feature decoration the way `ChunkStatus.FEATURES` and `Biome#generate` do it, taking snapshots of
the 3x3 chunks around it: before any of its features, right before the cluster and right after it, plus the
ocean-floor heightmap. `biomes` and `steps STEP` print the feature lists of every biome, which is where the feature
indexes the finder uses come from.

`FeatureTest` (compiled against the finder's classes) runs the ports on those snapshots:

- the cluster port on the game's blocks right before the cluster: identical in all 300 chunks;
- lakes, dungeons, ores and disks on the blocks before any feature, then the clusters: with the chunks that have a
  mineshaft, an amethyst geode or a fossil left out (the finder skips those too), the blocks before the cluster
  matched exactly in all 126 desert chunks, and the dripstone in all 245 chunks of both test worlds.

`CarverTest` builds everything from scratch like the finder does, with its own carvers and terrain: below Y 60 the
carving matched to 99.93% (the rest is the bedrock at Y 2 to 4, which the finder doesn't model), and the dripstone
came out identical in 124 of 126 desert chunks. The two misses are chunks where the clusters reach up into the
layer of sand and sandstone under the surface, which the finder only approximates.

## Results in the game (`HarnessVerify`)

`verify SHAFT_X SHAFT_Z SECONDS` force-loads the chunks around a temple, watches the golem and the TNT, and prints
what a player falling down each column of the shaft lands on. `tools/verify_dripstone.py` does the same with the
unmodified server jar.
