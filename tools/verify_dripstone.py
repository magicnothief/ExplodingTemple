#!/usr/bin/env python3
"""Checks a finder result in the vanilla 1.17.1 server: does the golem set off the temple's TNT, and what does a
player falling down each of the shaft's 9 columns land on?

It generates the world, force-loads the chunks around the temple so the outpost's golem ticks and drops onto the
pressure plate, waits for the TNT to go off, stops the server and reads the columns under the pyramid floor from the
saved world.

usage: verify_dripstone.py SEED SHAFT_X SHAFT_Z --accept-eula [--server JAR]

SHAFT_X and SHAFT_Z are the finder's "/tp X 100 Z".
"""
import argparse
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time

import nbt

SERVER_URL = 'https://piston-data.mojang.com/v1/objects/a16d67e5807f57fc4e550299cf20226194497dc2/server.jar'
PASSABLE = ('air', 'cave_air', 'void_air')
DRIPSTONE = ('pointed_dripstone', 'dripstone_block')


def remove_tree(path):
    # on Windows the server's files can stay locked for a moment after it exits
    for _ in range(20):
        shutil.rmtree(path, ignore_errors=True)
        if not os.path.exists(path):
            return
        time.sleep(0.5)


class Server:
    def __init__(self, args, wd):
        with open(os.path.join(wd, 'eula.txt'), 'w') as f:
            f.write('eula=true\n')
        with open(os.path.join(wd, 'server.properties'), 'w') as f:
            f.write(f'level-seed={args.seed}\nonline-mode=false\nspawn-protection=0\nmax-players=1\nview-distance=10\n'
                    f'level-type=default\ngenerate-structures=true\nserver-port={args.port}\nenable-rcon=false\n'
                    f'snooper-enabled=false\n')
        self.proc = subprocess.Popen([args.java, '-Xmx2G', '-jar', os.path.abspath(args.server), 'nogui'], cwd=wd,
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, encoding='utf-8', errors='replace', bufsize=1)
        self.lines = queue.Queue()
        threading.Thread(target=self._reader, daemon=True).start()
        try:
            self.wait_for(r'Done \(', timeout=600)
        except (TimeoutError, RuntimeError):
            self.proc.kill()
            self.proc.wait()
            raise

    def _reader(self):
        for line in self.proc.stdout:
            self.lines.put(line.rstrip('\n'))
        self.lines.put(None)

    def wait_for(self, pattern, timeout=300):
        end, rx = time.time() + timeout, re.compile(pattern)
        while time.time() < end:
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError('server exited')
            if rx.search(line):
                return line
        raise TimeoutError(pattern)

    def cmd(self, c, expect=None, timeout=20):
        while True:
            try:
                self.lines.get_nowait()
            except queue.Empty:
                break
        self.proc.stdin.write(c + '\n')
        self.proc.stdin.flush()
        return self.wait_for(expect, timeout) if expect else None

    def test(self, condition):
        """True, False, or None if the position isn't loaded."""
        line = self.cmd('execute if ' + condition, expect=r'(Test passed|Test failed|not loaded)')
        return None if 'not loaded' in line else 'passed' in line

    def stop(self):
        self.cmd('stop')
        try:
            self.proc.wait(timeout=120)
        except subprocess.TimeoutExpired:
            self.proc.kill()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('seed', type=int)
    ap.add_argument('x', type=int, help="the shaft's x, the finder's /tp x")
    ap.add_argument('z', type=int, help="the shaft's z, the finder's /tp z")
    ap.add_argument('--accept-eula', action='store_true',
                    help='you accept the Minecraft EULA (https://aka.ms/MinecraftEULA), needed to run the server')
    ap.add_argument('--server', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'server-1.17.1.jar'),
                    help='the vanilla 1.17.1 server jar (default: server-1.17.1.jar next to this script)')
    ap.add_argument('--java', default='java', help='the java command to run the server with (Java 16 or newer)')
    ap.add_argument('--work', default='dripstone-tests', help='folder for the test world, deleted afterwards')
    ap.add_argument('--port', type=int, default=25660, help='server port')
    ap.add_argument('--keep', action='store_true', help="keep the test world")
    args = ap.parse_args()
    if not args.accept_eula:
        sys.exit('The server only runs if you accept the Minecraft EULA (https://aka.ms/MinecraftEULA). '
                 'Pass --accept-eula if you do.')
    if not os.path.exists(args.server):
        sys.exit(f'No server jar at {args.server}. Download the 1.17.1 server from\n  {SERVER_URL}\n'
                 f'and save it there, or pass --server with its path.')

    x, z = args.x, args.z
    tx, tz = (x - 10) >> 4, (z - 10) >> 4
    wd = os.path.join(args.work, str(args.seed))
    remove_tree(wd)
    os.makedirs(wd)

    t0 = time.time()
    print(f'generating seed {args.seed} with the temple shaft at {x} {z}...', flush=True)
    server = Server(args, wd)
    # the temple's chunk and 3 around it on every side, which takes in the outpost
    server.cmd(f'forceload add {(tx - 3) * 16} {(tz - 3) * 16} {(tx + 3) * 16 + 15} {(tz + 3) * 16 + 15}',
               expect=r'(Marked|already)', timeout=300)
    pyramid = None
    for _ in range(600):
        pyramid = server.test(f'block {x} 64 {z} minecraft:blue_terracotta')
        if pyramid is not None:
            break
        time.sleep(0.5)
    exploded = False
    if pyramid:
        end = time.time() + 120
        while time.time() < end:
            if server.test(f'block {x} 51 {z} minecraft:tnt') is False and not server.test('entity @e[type=minecraft:tnt]'):
                exploded = True
                break
            time.sleep(0.5)
        # let sand the explosion loosened land
        settle = time.time() + 10
        while exploded and time.time() < settle and server.test('entity @e[type=minecraft:falling_block]'):
            time.sleep(0.5)
        time.sleep(1.0)
    server.stop()

    if not pyramid:
        print(f'RESULT seed={args.seed}: no desert pyramid floor at {x} 64 {z}. Check the seed and the coordinates.')
    else:
        world = nbt.World(os.path.join(wd, 'world', 'region'))
        landings = []
        for cx in range(x - 1, x + 2):
            for cz in range(z - 1, z + 2):
                y = 63
                while y > 0 and world.block(cx, y, cz) in PASSABLE:
                    y -= 1
                landings.append((cx, cz, y, world.block(cx, y, cz)))
                print(f'  column {cx} {cz}: falling from the floor lands at Y={y} on {world.block(cx, y, cz)}')
        dripstone = sum(1 for landing in landings if landing[3] in DRIPSTONE)
        data = nbt.load_gz(os.path.join(wd, 'world', 'level.dat'))['Data']
        spawn = (data['SpawnX'], data['SpawnY'], data['SpawnZ'])
        distance = ((spawn[0] - x) ** 2 + (spawn[2] - z) ** 2) ** 0.5
        print(f'RESULT seed={args.seed} shaft={x},{z}: exploded={exploded}, dripstone under {dripstone} of the '
              f"shaft's 9 columns, world spawn {spawn[0]} {spawn[1]} {spawn[2]} ({distance:.0f} blocks away), "
              f'{time.time() - t0:.0f}s')
        if not exploded:
            print('The TNT is still there, so the golem didn\'t set it off (or the temple is in the spawn chunks '
                  'and went off while the world was created, then this is the crater).')
    if not args.keep:
        remove_tree(wd)


if __name__ == '__main__':
    main()
