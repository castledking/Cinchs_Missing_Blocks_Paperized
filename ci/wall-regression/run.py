#!/usr/bin/env python3
"""Exercise furniture wall placement and breaking on an isolated Paper 26.3 server."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache-server', type=Path, required=True,
                        help='prepared Paper server used only for its jar, plugins, libraries and versions caches')
    parser.add_argument('--cmb', type=Path, required=True, help='CMB jar under test')
    parser.add_argument('--protocol', type=Path, required=True, help='node-minecraft-protocol with 26.3 support')
    parser.add_argument('--java-home', type=Path, help='Java 25 home; otherwise use java, javac and jar on PATH')
    parser.add_argument('--port', type=int, default=25593)
    parser.add_argument('--output', type=Path, help='copy server and client logs to this directory')
    args = parser.parse_args()
    cache = args.cache_server.resolve(strict=True)
    cmb = args.cmb.resolve(strict=True)
    protocol = args.protocol.resolve(strict=True)
    java = lambda name: str(args.java_home / 'bin' / name) if args.java_home else name
    craftengine = cache / 'plugins' / 'craft-engine.jar'
    paper = cache / 'paper.jar'
    for path in (craftengine, paper, cache / 'libraries', cache / 'versions'):
        if not path.exists():
            parser.error(f'missing prepared Paper server file: {path}')

    with tempfile.TemporaryDirectory(prefix='cmb-wall-regression-') as temp:
        root = Path(temp)
        server = root / 'server'
        plugins = server / 'plugins'
        plugins.mkdir(parents=True)
        for name in ('libraries', 'versions', 'paper.jar'):
            (server / name).symlink_to((cache / name).resolve(), target_is_directory=name != 'paper.jar')
        shutil.copy2(craftengine, plugins / 'craft-engine.jar')
        shutil.copy2(cmb, plugins / 'cmb.jar')
        (server / 'eula.txt').write_text('eula=true\n')
        (server / 'server.properties').write_text(
            f'server-port={args.port}\nonline-mode=false\nlevel-type=minecraft\\:flat\n'
            'generate-structures=false\nview-distance=4\nsimulation-distance=4\n'
            'spawn-protection=0\nwhite-list=false\n')
        classes = root / 'classes'
        classes.mkdir()
        libraries = list((cache / 'libraries').resolve().rglob('*.jar'))
        libraries += list((cache / 'versions').resolve().rglob('*.jar'))
        libraries += [craftengine, cmb]
        subprocess.run([java('javac'), '-cp', os.pathsep.join(map(str, libraries)), '-d', str(classes),
                        str(HERE / 'WallProbe.java')], check=True)
        subprocess.run([java('jar'), 'cf', str(plugins / 'WallProbe.jar'), '-C', str(classes), '.',
                        '-C', str(HERE), 'plugin.yml'], check=True)
        server_log, client_log = root / 'server.log', root / 'client.log'
        client = None
        with server_log.open('w') as out, client_log.open('w') as client_out:
            process = subprocess.Popen([java('java'), '-Xms256M', '-Xmx1536M', '-XX:ActiveProcessorCount=2',
                                        '-jar', 'paper.jar', '--nogui'], cwd=server, stdin=subprocess.PIPE,
                                       stdout=out, stderr=subprocess.STDOUT, text=True)
            try:
                deadline = time.monotonic() + 180
                while process.poll() is None and time.monotonic() < deadline:
                    log = server_log.read_text(errors='replace')
                    if 'Done (' in log and 'recipe advancements from the mod' in log:
                        environment = dict(os.environ, CMB_WALL_PROTOCOL=str(protocol), CMB_WALL_PORT=str(args.port))
                        client = subprocess.Popen(['node', str(HERE / 'client.cjs')], env=environment,
                                                  stdout=client_out, stderr=subprocess.STDOUT)
                        break
                    time.sleep(0.5)
                if client:
                    deadline = time.monotonic() + 120
                    while process.poll() is None and client.poll() is None and time.monotonic() < deadline:
                        time.sleep(0.5)
                    if process.poll() is None and client.poll() is None:
                        print('Wall probe timed out', file=sys.stderr)
            finally:
                if process.poll() is None:
                    process.stdin.write('stop\n')
                    process.stdin.flush()
                    try:
                        process.wait(timeout=25)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                if client and client.poll() is None:
                    client.terminate()
                    client.wait(timeout=10)
        log = server_log.read_text(errors='replace')
        for line in log.splitlines():
            if '[WALLTEST]' in line:
                print(line)
        if args.output:
            args.output.mkdir(parents=True, exist_ok=True)
            shutil.copy2(server_log, args.output / 'server.log')
            shutil.copy2(client_log, args.output / 'client.log')
        if '[WALLTEST] FINISHED failures=0' not in log:
            print('\n'.join(log.splitlines()[-40:]), file=sys.stderr)
            return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
