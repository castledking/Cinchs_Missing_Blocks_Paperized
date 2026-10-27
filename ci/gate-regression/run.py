#!/usr/bin/env python3
"""Check gate connections and existing-world recovery across a real server restart."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

HERE = Path(__file__).resolve().parent
CLIENT = HERE.parent / 'wall-regression' / 'client.cjs'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache-server', type=Path, required=True,
                        help='prepared test server used for its Paper and CraftEngine caches')
    parser.add_argument('--cmb', type=Path, required=True)
    parser.add_argument('--protocol', type=Path, required=True, help='node-minecraft-protocol with 26.3 support')
    parser.add_argument('--java-home', type=Path, help='Java 25 home')
    parser.add_argument('--port', type=int, default=25593)
    parser.add_argument('--output', type=Path, help='copy the two server logs to this directory')
    args = parser.parse_args()
    cache = args.cache_server.resolve(strict=True)
    cmb = args.cmb.resolve(strict=True)
    protocol = args.protocol.resolve(strict=True)
    java = lambda name: str(args.java_home / 'bin' / name) if args.java_home else name
    craftengine = cache / 'plugins' / 'craft-engine.jar'
    for path in (craftengine, cache / 'paper.jar', cache / 'libraries', cache / 'versions'):
        if not path.exists():
            parser.error(f'missing prepared Paper server file: {path}')

    with tempfile.TemporaryDirectory(prefix='cmb-gate-regression-') as temp:
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
            f'server-port={args.port}\nonline-mode=false\nwhite-list=false\nlevel-type=minecraft\\:flat\n'
            'view-distance=4\nsimulation-distance=4\nspawn-protection=0\n')
        classes = root / 'classes'
        classes.mkdir()
        libraries = list((cache / 'libraries').resolve().rglob('*.jar'))
        libraries += list((cache / 'versions').resolve().rglob('*.jar'))
        libraries += [craftengine, cmb]
        subprocess.run([java('javac'), '-cp', os.pathsep.join(map(str, libraries)), '-d', str(classes),
                        str(HERE / 'GateProbe.java')], check=True)
        subprocess.run([java('jar'), 'cf', str(plugins / 'GateProbe.jar'), '-C', str(classes), '.',
                        '-C', str(HERE), 'plugin.yml'], check=True)
        for phase in (1, 2):
            logfile = root / f'phase{phase}.log'
            client = None
            with logfile.open('w') as log:
                process = subprocess.Popen([java('java'), '-Xms256M', '-Xmx1536M', '-XX:ActiveProcessorCount=2',
                                            '-jar', 'paper.jar', '--nogui'], cwd=server, stdin=subprocess.PIPE,
                                           stdout=log, stderr=subprocess.STDOUT, text=True)
                try:
                    deadline = time.monotonic() + 180
                    while process.poll() is None and time.monotonic() < deadline:
                        if 'Done (' in logfile.read_text(errors='replace'):
                            env = dict(os.environ, CMB_WALL_PROTOCOL=str(protocol), CMB_WALL_PORT=str(args.port))
                            client = subprocess.Popen(['node', str(CLIENT)], env=env,
                                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                            break
                        time.sleep(0.5)
                    process.wait(timeout=120)
                except subprocess.TimeoutExpired:
                    print(f'Gate test phase {phase} timed out', file=sys.stderr)
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
            content = logfile.read_text(errors='replace')
            if args.output:
                args.output.mkdir(parents=True, exist_ok=True)
                shutil.copy2(logfile, args.output / logfile.name)
            for line in content.splitlines():
                if '[WALLTEST]' in line and any(word in line for word in ('FAIL', 'PHASE1', 'FINISHED', 'reloaded', 'startup chunk')):
                    print(line)
            marker = '[WALLTEST] PHASE1 failures=0' if phase == 1 else '[WALLTEST] FINISHED failures=0'
            if marker not in content:
                print('\n'.join(content.splitlines()[-30:]), file=sys.stderr)
                return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
