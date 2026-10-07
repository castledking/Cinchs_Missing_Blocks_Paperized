#!/usr/bin/env bash
# ci/boot-test.sh <minecraft-version> <cmb-jar> [craftengine-version]
#
# Boots a real Paper server with CraftEngine and the CMB jar, runs /cmb reload all, stops
# it, and fails on any sign the jar doesn't work there:
#   - the class file is too new for this Java (UnsupportedClassVersionError)
#   - CMB doesn't enable, install its pack, or reload
#   - CraftEngine reports issues in CMB's pack files
#   - an exception passes through CMB's code
#
# KEEP=1 keeps the server directory for inspection.
#
# Java is whatever `java` is on PATH (or $JAVA): the matrix picks the one each Paper version
# needs - 21 for 1.21.x, 25 for 26.x. The port is $PORT, 25565 by default. Needs curl and jq.
set -euo pipefail

mc=${1:?minecraft version}
jar=$(realpath "${2:?cmb jar}")
ce=${3:-26.9.2}
java=${JAVA:-java}
port=${PORT:-25565}
ua="cmb-boot-test (github.com/castledking/Cinchs_Missing_Blocks_Paperized)"

dir=$(mktemp -d)
server=
# The verdict is the script's exit status, whatever cleanup does: stop the server and wait
# for it, so nothing is still writing while the directory goes, and never fail on cleanup.
cleanup() {
    local status=$?
    if [ -n "$server" ]; then kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; fi
    if [ -n "${KEEP:-}" ]; then echo "kept $dir"; else rm -rf "$dir" 2>/dev/null || true; fi
    exit "$status"
}
trap cleanup EXIT
cd "$dir"
mkdir plugins

echo "== Paper $mc, CraftEngine $ce, $("$java" -version 2>&1 | head -1)"
paper=$(curl -fsSL -H "User-Agent: $ua" "https://fill.papermc.io/v3/projects/paper/versions/$mc/builds/latest" \
    | jq -r '.downloads["server:default"].url')
curl -fsSL -H "User-Agent: $ua" -o paper.jar "$paper"
craftengine=$(curl -fsSL -H "User-Agent: $ua" \
    "https://api.modrinth.com/v2/project/craftengine/version?loaders=%5B%22paper%22%5D" \
    | jq -r --arg v "$ce" '[.[] | select(.version_number == $v)][0].files[] | select(.primary) | .url')
curl -fsSL -H "User-Agent: $ua" -o plugins/craftengine.jar "$craftengine"
cp "$jar" plugins/cmb.jar

echo "eula=true" > eula.txt
cat > server.properties <<PROPS
server-port=$port
online-mode=false
level-type=minecraft\:flat
generate-structures=false
view-distance=4
simulation-distance=4
white-list=false
PROPS

mkfifo console
# The FIFO stays open for writing on fd 3, so the server never reads an EOF on stdin.
exec 3<>console
"$java" -Xmx2G -jar paper.jar --nogui < console > server.log 2>&1 &
server=$!

wait_for() {  # wait_for <regex> <seconds> [from-line]
    local deadline=$((SECONDS + $2))
    until tail -n "+${3:-1}" server.log | grep -qE "$1"; do
        if ! kill -0 "$server" 2>/dev/null || (( SECONDS > deadline )); then
            return 1
        fi
        sleep 1
    done
}

problems=()
# A stalled step is recorded and the run goes on to the checks below, which name the cause
# (a class-version error, say) rather than just the step that stalled.
if wait_for 'Done \(' 600; then
    from=$(($(wc -l < server.log) + 1))
    echo "cmb reload all" >&3
    if wait_for 'Reloaded successfully|Reload failed' 120 "$from"; then
        # CMB answers once it has asked CraftEngine to reload; CraftEngine then rebuilds
        # on its own threads. Stopping mid-rebuild makes it report every template as
        # invalid, so stop only once it says the pack is done.
        wait_for 'Resource pack generated|Failed to generate the resource pack' 300 "$from" \
            || problems+=("CraftEngine did not finish rebuilding the pack after /cmb reload all")
    else
        problems+=("/cmb reload all did not answer")
    fi
    echo "stop" >&3
else
    problems+=("the server did not finish starting")
fi
for _ in $(seq 1 60); do kill -0 "$server" 2>/dev/null || break; sleep 1; done
kill "$server" 2>/dev/null || true
wait "$server" 2>/dev/null || true
server=

# Paper's plugin remapper reports it as "Unsupported class file major version"; the JVM
# itself as UnsupportedClassVersionError.
grep -qE 'UnsupportedClassVersionError|Unsupported class file major version' server.log \
    && problems+=("the jar's class file is too new for this Java")
grep -q "Could not load plugin 'cmb.jar'" server.log && problems+=("Paper could not load the jar")
grep -q '\[CMB\] Enabling CMB' server.log || problems+=("CMB did not enable")
grep -q 'Installed the CMB pack' server.log || problems+=("CMB did not install its pack")
grep -q 'Reload failed' server.log && problems+=("/cmb reload all failed")
grep -q 'Failed to generate the resource pack' server.log && problems+=("CraftEngine could not build the pack")
grep -q 'issue(s) in file .*cinchsmissingblocks' server.log && problems+=("CraftEngine reported issues in CMB's pack")
grep -qE 'at net\.cinchtail\.' server.log && problems+=("an exception passed through CMB's code")

grep -E '\[CMB\] (Enabling|Installed|Reloaded)' server.log | sed 's/^/  /' || true
if (( ${#problems[@]} )); then
    printf 'FAIL: %s\n' "${problems[@]}"
    if grep -q 'issue(s) in file' server.log; then
        echo "---- CraftEngine's issues ----"
        grep -A12 'issue(s) in file' server.log | head -60 || true
    fi
    echo "---- log lines mentioning CMB or errors ----"
    grep -nE 'CMB|cinchtail|ERROR|Exception' server.log | head -80 || true
    echo "---- last 30 log lines ----"
    tail -n 30 server.log
    exit 1
fi
echo "PASS: Paper $mc"
