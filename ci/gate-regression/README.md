# Nether brick gate regression

This runs a real Paper 26.3 server twice in an isolated temporary world. It checks
all six gate variants in all four orientations against vanilla and CMB walls,
nether brick fences, wooden fences, both placement orders, and removal. The second
boot checks that saved gates and walls reconnect in startup and newly loaded chunks.
A native client keeps the test area loaded during the matrix.

Prepare a disposable Paper 26.3 test server with CraftEngine, Java 25, and a
`node-minecraft-protocol` installation supporting protocol 777. Then run:

```sh
./gradlew build
python3 ci/gate-regression/run.py \
  --cache-server /path/to/prepared-test-server \
  --cmb build/libs/cmb-1.0.4.jar \
  --protocol /path/to/node-minecraft-protocol \
  --java-home /path/to/java-25 \
  --output build/gate-regression
```

The temporary server shares only the prepared server's Paper library and version
caches. Its configuration, plugins, and world are separate.
