#!/usr/bin/env python3
"""Generates a 26.3 world in the vanilla server and reports what happened at a desert pyramid: the pyramid floor
height, whether the TNT under the pressure plate is still there, and where the outpost's golems and allays ended up.

usage: verify26.py SEED PYRAMID_CHUNK_X PYRAMID_CHUNK_Z --accept-eula [--server JAR] [--java JAVA]
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


class Server:
    def __init__(self, args, wd):
        os.makedirs(wd, exist_ok=True)
        with open(os.path.join(wd, 'eula.txt'), 'w') as f:
            f.write('eula=true\n')
        with open(os.path.join(wd, 'server.properties'), 'w') as f:
            f.write(f'level-seed={args.seed}\nonline-mode=false\nserver-port={args.port}\nspawn-protection=0\n'
                    f'view-distance=4\nsimulation-distance=4\nenable-rcon=false\nmax-players=1\n')
        self.proc = subprocess.Popen([args.java, '-Xmx2G', '-jar', os.path.abspath(args.server), 'nogui'], cwd=wd,
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                     bufsize=1, encoding='utf-8', errors='replace')
        self.lines = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()
        self.wait_for(r'Done \(', 600)

    def _read(self):
        for line in self.proc.stdout:
            self.lines.put(line.rstrip('\n'))
        self.lines.put(None)

    def wait_for(self, pattern, timeout=60):
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

    def drain(self):
        out = []
        while True:
            try:
                line = self.lines.get_nowait()
            except queue.Empty:
                return out
            if line is not None:
                out.append(line)

    def cmd(self, c, expect=None, timeout=30):
        self.drain()
        self.proc.stdin.write(c + '\n')
        self.proc.stdin.flush()
        return self.wait_for(expect, timeout) if expect else None

    def test(self, cond):
        line = self.cmd('execute if ' + cond, r'(Test passed|Test failed|not loaded)')
        return 'passed' in line

    def stop(self):
        try:
            self.cmd('stop')
            self.proc.wait(timeout=120)
        except Exception:
            self.proc.kill()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('seed', type=int)
    ap.add_argument('px', type=int, help='pyramid chunk x')
    ap.add_argument('pz', type=int, help='pyramid chunk z')
    ap.add_argument('--accept-eula', action='store_true',
                    help='you accept the Minecraft EULA (https://aka.ms/MinecraftEULA), needed to run the server')
    ap.add_argument('--server', default='server-26.3.jar', help='the vanilla 26.3 server jar')
    ap.add_argument('--java', default='java', help='a Java 25 java command')
    ap.add_argument('--work', default='verify26-worlds', help='folder for the test worlds')
    ap.add_argument('--port', type=int, default=25790)
    ap.add_argument('--wait', type=float, default=30, help='seconds to let the force-loaded chunks generate and tick')
    args = ap.parse_args()
    if not args.accept_eula:
        sys.exit('The server only runs if you accept the Minecraft EULA (https://aka.ms/MinecraftEULA). '
                 'Pass --accept-eula if you do.')
    wd = os.path.join(args.work, str(args.seed))
    shutil.rmtree(wd, ignore_errors=True)
    s = Server(args, wd)
    x0, z0 = args.px * 16, args.pz * 16
    s.cmd(f'forceload add {x0 - 32} {z0 - 32} {x0 + 47} {z0 + 47}', r'(Marked|already)', 300)
    time.sleep(args.wait)
    sx, sz = x0 + 10, z0 + 10
    plate = None
    for y in range(80, 30, -1):
        if s.test(f'block {sx} {y} {sz} minecraft:stone_pressure_plate'):
            plate = y
            break
    tnt = plate is not None and s.test(f'block {sx} {plate - 2} {sz} minecraft:tnt')
    print(f'RESULT seed={args.seed} pyramid chunk {args.px} {args.pz}: pressure plate at Y={plate}, '
          f'pyramid floor Y={plate + 11 if plate else None}, TNT under it={tnt}')
    for mob in ('iron_golem', 'allay'):
        s.drain()
        s.cmd(f'execute as @e[type=minecraft:{mob},x={x0 - 32},y=-64,z={z0 - 32},dx=80,dy=384,dz=80] '
              f'run data get entity @s Pos')
        time.sleep(2)
        for line in s.drain():
            m = re.search(r'has the following entity data: \[(.*)\]', line)
            if m:
                pos = [float(v.strip().rstrip('d')) for v in m.group(1).split(',')]
                over = x0 + 9 <= pos[0] < x0 + 12 and z0 + 9 <= pos[2] < z0 + 12
                print(f'  {mob} at {pos[0]:.2f} {pos[1]:.2f} {pos[2]:.2f}' + ('  (over the shaft)' if over else ''))
    s.stop()
    shutil.rmtree(wd, ignore_errors=True)


if __name__ == '__main__':
    main()
