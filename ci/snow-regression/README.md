# Snow and ice regression

This runs a real Paper 26.3 server in an isolated temporary world. It puts furniture
slabs, stairs, walls and doubles in snowy columns and runs vanilla's own weather on
them through `ServerLevel.tickPrecipitation`. It checks that snow never settles in a
piece's cell or under a roof of pieces, and that water in or under a piece never
freezes. It also checks that a snow golem's trail and frost walker are kept out of a
piece's cell but not from under a roof. Snow left inside a piece by an older build
must be cleared when its chunk loads again. Each case has an open-column control.
No client is needed.

Prepare a disposable Paper 26.3 test server with CraftEngine and Java 25. Then run:

```sh
./gradlew build
python3 ci/snow-regression/run.py \
  --cache-server /path/to/prepared-test-server \
  --cmb build/libs/cmb-1.0.4.jar \
  --java-home /path/to/java-25 \
  --output build/snow-regression
```

The temporary server shares only the prepared server's Paper library and version
caches. Its configuration, plugins, and world are separate. CMB 1.0.3 without the
fix fails 12 of the 30 checks; the controls pass on both builds.
