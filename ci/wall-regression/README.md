# Furniture wall regression

This is an end-to-end check using Paper 26.3, CraftEngine, and a native 26.3 client.
It tests custom and vanilla wall connections, a wall on top and beside a custom wall,
normal and sneak clicks, and cancellation by a protection plugin's `BlockPlaceEvent`.
It sends the main-hand and off-hand packets from a single click, checks both top
and diagonal duplicate placement, and verifies an off-hand-only placement still works.
It also replaces snow through native placement clicks: vanilla walls, CMB walls,
fences and gates, grass/podzol/mycelium below, adjacent snow, and cancelled placement.
The probe creates its own temporary world and removes it afterwards.

Use an already prepared **test server** for Paper's `paper.jar`, `libraries/`,
`versions/`, and `plugins/craft-engine.jar` caches. A Node installation of
`node-minecraft-protocol` that supports protocol 777 (26.3) and its dependencies is
also required. Use Java 25 and a free port:

```sh
./gradlew build
python3 ci/wall-regression/run.py \
  --cache-server /path/to/prepared-test-server \
  --cmb build/libs/cmb-1.0.4.jar \
  --protocol /path/to/node-minecraft-protocol \
  --java-home /path/to/java-25 \
  --output build/wall-regression
```

The temporary server shares only the prepared server's Paper libraries and version
caches. Its plugins, configuration and world are separate. The runner exits nonzero
if any check fails or the server/client cannot complete the probe.
