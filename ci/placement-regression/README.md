# Stair and slab placement regression

This runs a real Paper 26.3 server in an isolated temporary world. It checks the half a
furniture stair or slab takes when placed against a side, by vanilla's rule: the top
half only when the click lands above the side's middle, so exactly at the middle it is
the bottom half. The sides are a furniture stair's, a stair's riser, a top slab's and a
real block's, clicked normally and sneaking, with the eye above or below the point. A
horizontal stair placed against a piece's side must wrap the half of the side that was
hit. The controls aim where the look direction alone gives the right half, click real
blocks, and click the top and the underside of a piece.

No client is needed. A server-side player is aimed at each point and fires the event
CraftEngine fires for that click, `FurnitureAttemptPlaceEvent`. The runner turns on
`features.horizontal-stairs.cmb` in CMB's default config.

Prepare a disposable Paper 26.3 test server with CraftEngine and Java 25. Then run:

```sh
./gradlew build
python3 ci/placement-regression/run.py \
  --cache-server /path/to/prepared-test-server \
  --cmb build/libs/cmb-1.0.4.jar \
  --java-home /path/to/java-25 \
  --output build/placement-regression
```

The temporary server shares only the prepared server's Paper library and version
caches. Its configuration, plugins, and world are separate. CMB 1.0.4 without the fix
fails 8 of the 21 checks; the controls pass on both builds.
