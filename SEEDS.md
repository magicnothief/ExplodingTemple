# Exploding temples over dripstone (Java 1.17.1)

In these worlds the desert temple blows itself up: a pillager outpost's iron golem is placed inside the temple's
hidden shaft, drops onto the pressure plate and sets off the TNT as soon as the temple is loaded. The shaft then
opens straight into a ravine with dripstone on its floor. Break through the blue terracotta in the middle of the
temple floor and drop down.

Each temple comes with many world seeds that share it (sister seeds): the temple, the outpost, the ravine and the
dripstone are the same, while the biomes further away and the world spawn differ. Distances are from the world spawn
to the shaft.

## Temple at 26 -998: dripstone under 1 of the shaft's 9 columns

| Seed | World spawn | Distance |
|---|---|---|
| `-7221521733626352479` | -40 76 -222 | 779 blocks |
| `363384463795694753` | 240 48 -240 | 788 blocks |
| `-5305521567156917087` | -240 56 -256 | 788 blocks |
| `239816949019716769` | -256 60 -256 | 794 blocks |
| `3110580236491697313` | -256 61 -256 | 794 blocks |

Structure seed `268862237857`, 43 sister seeds within 1000 blocks. The first one was checked in the vanilla server
with `tools/verify_dripstone.py`: the golem was on the plate 2 seconds after the chunks loaded and the TNT went off 3
seconds later. The ravine floor is at Y 28; the column at 26 -999 has pointed dripstone on it (at Y 30), two
columns have a small dripstone pool, the rest is stone and diorite. So only one column in nine lands on dripstone:
fall down the north-middle column.

## Temple at 202 1178: dripstone under 5 of the shaft's 9 columns

| Seed | World spawn | Distance |
|---|---|---|
| `69805531629439488` | 224 68 256 | 922 blocks |
| `1860267858485922304` | 64 66 256 | 932 blocks |
| `635851709794568704` | 160 73 240 | 939 blocks |
| `6815353348500310528` | 64 56 248 | 940 blocks |
| `3591901915209878016` | 128 66 240 | 941 blocks |
| `3041899810717256192` | 96 72 224 | 960 blocks |

Structure seed `281212381907456`, 41 sister seeds within 1000 blocks. Checked `3041899810717256192` in the vanilla
server: it blew up within 5 seconds, and falling from the temple floor lands on dripstone blocks at Y 22 and 23 or
pointed dripstone at Y 24 in 5 of the 9 columns (the whole north row and the east row), on stone in the others. The
finder predicted 4.

## How these were found

`Main 0 281474976710656 1000 4`, the first 5 minutes of a search on 4 cores. The finder lists results in
`results.txt`, closest first; these tables are the closest few of each temple.
