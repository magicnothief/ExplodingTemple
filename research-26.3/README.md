# Can a desert pyramid still blow itself up in 26.3?

The plan for this branch is a 26.3 seed where a desert pyramid's TNT goes off at world generation, as the pillager
outpost's iron golem does in 1.16.1 (see the main README), and the crater drops straight into an ancient city. Ancient
cities only exist from 1.19 on, so before building a finder this checks whether anything during generation can
still set off a pyramid's TNT in 26.3.

**It can't.** The golem trick stopped working in 1.18, and nothing else placed during generation can reach the
pressure plate or the TNT.

## The golem trick

In 1.16.1 the pyramid floor is always at Y 64. An outpost golem standing on ground at sea level ends up with its feet
in the floor block, falls down the hidden shaft onto the pressure plate and sets off the TNT.

Since 1.18 (checked in 1.18.2, 1.19, 1.19.2, 1.19.4, 1.20.1, 1.21.11 and 26.3) the pyramid calls
`updateHeightPositionToLowestGroundHeight(level, -random.nextInt(3))`: its floor goes on the lowest ground in its
21×21 footprint, and 0 to 2 blocks lower than that.

The outpost puts each cage's bottom on the first free block above the ground at the plate connector it hangs from.
Since 1.18 outposts also have `terrain_adaptation: beard_thin`, which pulls the ground around the cage to that same
level (in `Beardifier` the ground level is the cage's bottom plus its ground level delta, which is 0 for cages). The
golem stands 1 block above the cage's bottom (the template has it at y 1.0), and the two allays of
`feature_cage_with_allays` at 1.05 and 1.93.

So the mob's feet are at least 1 block above ground in the pyramid's footprint, and the pyramid floor only goes lower
than that ground. The mob ends up standing on the floor, above the shaft.

Tested with the game's own structure generation (`GolemTest.java`). It looked for outposts whose golem or allays spawn
over a pyramid's shaft among random structure seeds, and for each of 81 such outposts it checked up to 400 world seeds
where both structures generate. In all 1,851 cases of a golem or allay over the shaft, its feet were above the
lowest ground in the pyramid's footprint, even counting only ground that no outpost piece covers. Two of these worlds
generated in the 26.3 server (`verify26.py`):

| World seed | Pyramid floor | Allays over the shaft | TNT |
|---|---|---|---|
| `749894685657815373` | Y 63 | Y 64.98 and 66.40 | still there |
| `209351508936470937` | Y 63 | Y 64.95 and 64.00 | still there |

## Anything else

- A stone pressure plate only reacts to living mobs. Falling sand, items, minecarts and the new cushions can't press
  it.
- Within a chunk, 26.3 places structures in alphabetical order of their names (checked in the running game with
  `GolemTest order`). Pillager outposts and villages come after the desert pyramid and build their own blocks over it,
  under their mobs. The only surface structures before it are the abandoned camps, whose cushions aren't mobs, and
  bastions, which are in the Nether.
- Mobs that structures place at generation, from every structure template: outposts (iron golem, 2 allays), villages
  (villagers, zombie villagers, cats, iron golem, horses, cows, sheep, pigs, camel), igloos (villager, zombie
  villager) and bastions (piglins, hoglin).
- Animals spawned at generation go on the surface, which here is the pyramid's roof.
- The TNT sits inside the pyramid's own cut sandstone, a 5×5 box from 14 to 11 blocks below the floor, and the
  generation steps after structures (ores, springs) only replace natural stone. So lava, fire or water can't get to
  it.

The one natural way left is hostile mobs spawning in the dark hidden chamber, on the 8 blocks around the plate and in
the 2 side alcoves, and walking onto the plate. That only happens while a player is 24 to 128 blocks away, so it
isn't decided by the seed.

## Running the tools

Both need Java 25 and the vanilla 26.3 server jar. `GolemTest` compiles against the game itself: the server jar
bundles it with its libraries.

```
unzip server-26.3.jar 'META-INF/versions/*' 'META-INF/libraries/*' -d bundle
CP="bundle/META-INF/versions/26.3/server-26.3.jar:$(find bundle/META-INF/libraries -name '*.jar' | tr '\n' ':')"
javac -cp "$CP" -d classes GolemTest.java
java --add-opens java.base/java.lang=ALL-UNNAMED -cp "classes:$CP" GolemTest scan 3000000 101 400
```

`GolemTest` starts the vanilla server inside its own JVM to get the game's registries, so run it in a folder with a
`server.properties` and the `eula.txt` the server needs (`eula=true` once you've accepted the Minecraft EULA at
https://aka.ms/MinecraftEULA). The modes are listed at the top of the file.

`verify26.py` generates one world in the real server and reports the pyramid floor, the TNT and where the golems and
allays are:

```
python3 verify26.py 749894685657815373 20 1 --accept-eula --server server-26.3.jar --java path/to/java25
```
