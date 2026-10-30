#!/usr/bin/env -S uv run --script
# SPDX-FileCopyrightText: 2026 amphigourik
# SPDX-License-Identifier: GPL-3.0-or-later
# /// script
# requires-python = ">=3.10"
# dependencies = ["pygame-ce>=2.5"]
# ///
"""Minimal controller for the Parrot Jumping Sumo / Race / Night (ARSDK3 protocol).

Join the drone Wi-Fi first (JumpingRace_xxxx), then:
    ./jumping.py            # window with video + keyboard control
    ./jumping.py --probe    # simple connection test (battery), no window

Controls: arrow keys or ZQSD to drive, Shift for max speed,
Space = long jump, H = high jump, P = change posture,
1-9 = animations, Esc = quit.
"""

import argparse
import io
import json
import socket
import struct
import threading
import time

# --- ARNetworkAL network layer -------------------------------------------
# Frame: type (u8), buffer id (u8), seq (u8), total size (u32 LE), data.
FRAME_ACK, FRAME_DATA, FRAME_LOW_LATENCY, FRAME_DATA_WITH_ACK = 1, 2, 3, 4
HEADER = struct.Struct("<BBBI")

# Controller -> drone buffers
BUF_CMD_NOACK, BUF_CMD_ACK, BUF_VIDEO_ACK = 10, 11, 13
BUF_PONG = 1
# Drone -> controller buffers
BUF_PING = 0
BUF_VIDEO = 125

DISCOVERY_PORT = 44444
D2C_PORT = 54321

# --- ARCommands: project (u8), class (u8), command (u16 LE) -----
PROJECT_COMMON, PROJECT_JS = 0, 3

POSTURES = ["standard", "jumper", "kicker"]
ANIMATIONS = {  # key -> (id, name)
    "1": (1, "spin"), "2": (2, "tap"), "3": (3, "slowshake"),
    "4": (4, "metronome"), "5": (5, "ondulation"), "6": (6, "spinjump"),
    "7": (7, "spintoposture"), "8": (8, "spiral"), "9": (9, "slalom"),
}


def cmd(project, klass, command, fmt="", *args):
    return struct.pack("<BBH" + fmt, project, klass, command, *args)


class Drone:
    def __init__(self, host):
        self.host = host
        self.seq = {}
        self.speed = 0
        self.turn = 0
        self.battery = None
        self.frame = None  # latest complete JPEG frame
        self.frame_count = 0
        self._fragments = {}
        self._running = False
        self._lock = threading.Lock()

    # Connection ------------------------------------------------------------
    def connect(self):
        self.rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.rx.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.rx.bind(("0.0.0.0", D2C_PORT))
        self.rx.settimeout(0.5)

        with socket.create_connection((self.host, DISCOVERY_PORT), timeout=5) as s:
            hello = {"controller_type": "computer", "controller_name": "parrot-poc",
                     "d2c_port": D2C_PORT}
            s.sendall(json.dumps(hello).encode())
            reply = s.recv(4096).rstrip(b"\x00").decode()
        info = json.loads(reply)
        if info.get("status", 0) != 0:
            raise RuntimeError(f"connection refused by the drone: {info}")
        self.c2d_port = info["c2d_port"]
        self.tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

        self._running = True
        threading.Thread(target=self._recv_loop, daemon=True).start()
        threading.Thread(target=self._pcmd_loop, daemon=True).start()

        now = time.localtime()
        self.send(cmd(PROJECT_COMMON, 4, 1) + time.strftime("%Y-%m-%d", now).encode() + b"\0")
        self.send(cmd(PROJECT_COMMON, 4, 2) + time.strftime("T%H%M%S%z", now).encode() + b"\0")
        self.send(cmd(PROJECT_COMMON, 4, 0))  # AllStates -> battery, etc.
        return info

    def close(self):
        self.speed = self.turn = 0
        time.sleep(0.1)
        self._running = False

    # Sending ---------------------------------------------------------------
    def _write(self, frame_type, buf, payload):
        with self._lock:
            seq = self.seq.get(buf, 0)
            self.seq[buf] = (seq + 1) & 0xFF
            data = HEADER.pack(frame_type, buf, seq, HEADER.size + len(payload)) + payload
            self.tx.sendto(data, (self.host, self.c2d_port))

    def send(self, payload):
        """Acknowledged command (jumps, animations, settings)."""
        self._write(FRAME_DATA_WITH_ACK, BUF_CMD_ACK, payload)

    def _pcmd_loop(self):
        # The drone expects the piloting command continuously (~40 Hz).
        while self._running:
            moving = 1 if (self.speed or self.turn) else 0
            self._write(FRAME_DATA, BUF_CMD_NOACK,
                        cmd(PROJECT_JS, 0, 0, "Bbb", moving, self.speed, self.turn))
            time.sleep(0.025)

    def jump(self, high=False):
        self.send(cmd(PROJECT_JS, 2, 3, "i", 1 if high else 0))

    def animation(self, anim_id):
        self.send(cmd(PROJECT_JS, 2, 4, "i", anim_id))

    def posture(self, index):
        self.send(cmd(PROJECT_JS, 0, 1, "i", index))

    def video(self, enable=True):
        self.send(cmd(PROJECT_JS, 18, 0, "B", 1 if enable else 0))

    # Receiving --------------------------------------------------------------
    def _recv_loop(self):
        while self._running:
            try:
                data, _ = self.rx.recvfrom(65535)
            except socket.timeout:
                continue
            # A datagram can contain several frames.
            while len(data) >= HEADER.size:
                ftype, buf, seq, size = HEADER.unpack_from(data)
                if size < HEADER.size or size > len(data):
                    break
                self._handle(ftype, buf, seq, data[HEADER.size:size])
                data = data[size:]

    def _handle(self, ftype, buf, seq, payload):
        if ftype == FRAME_DATA_WITH_ACK:
            self._write(FRAME_ACK, (buf + 128) & 0xFF, bytes([seq]))
        if buf == BUF_PING:
            self._write(FRAME_DATA, BUF_PONG, payload)
        elif buf == BUF_VIDEO:
            self._handle_video(payload)
        elif len(payload) >= 4:
            project, klass, command = struct.unpack_from("<BBH", payload)
            if (project, klass, command) == (PROJECT_COMMON, 5, 1):  # BatteryStateChanged
                self.battery = payload[4]

    def _handle_video(self, payload):
        # ARStream v1: frame number (u16), flags (u8), fragment number (u8),
        # fragment count (u8), then the JPEG chunk.
        frame_no, _flags, frag_no, frag_count = struct.unpack_from("<HBBB", payload)
        chunk = payload[5:]
        frags = self._fragments.setdefault(frame_no, {})
        frags[frag_no] = chunk
        # Acknowledge the received fragments so the drone does not resend them.
        low = high = 0
        for n in frags:
            if n < 64:
                low |= 1 << n
            else:
                high |= 1 << (n - 64)
        self._write(FRAME_DATA, BUF_VIDEO_ACK, struct.pack("<HQQ", frame_no, high, low))
        if len(frags) == frag_count:
            self.frame = b"".join(frags[i] for i in range(frag_count))
            self.frame_count += 1
            # Forget older, incomplete frames.
            self._fragments = {k: v for k, v in self._fragments.items()
                               if (k - frame_no) & 0xFFFF < 0x8000 and k != frame_no}


def probe(drone):
    info = drone.connect()
    print(f"Connected: {info}")
    for _ in range(30):
        if drone.battery is not None:
            break
        time.sleep(0.1)
    print(f"Battery: {drone.battery}%" if drone.battery is not None
          else "No battery level received (check the UDP connection).")
    drone.close()


def pilot(drone):
    import pygame

    info = drone.connect()
    print(f"Connected: {info}")
    drone.video(True)

    pygame.init()
    screen = pygame.display.set_mode((640, 480))
    pygame.display.set_caption("Jumping")
    font = pygame.font.SysFont(None, 24)
    clock = pygame.time.Clock()
    image, shown = None, 0
    posture = 0

    try:
        while True:
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    return
                if event.type != pygame.KEYDOWN:
                    continue
                if event.key == pygame.K_ESCAPE:
                    return
                if event.key == pygame.K_SPACE:
                    drone.jump(high=False)
                elif event.key == pygame.K_h:
                    drone.jump(high=True)
                elif event.key == pygame.K_p:
                    posture = (posture + 1) % len(POSTURES)
                    drone.posture(posture)
                elif event.unicode in ANIMATIONS:
                    drone.animation(ANIMATIONS[event.unicode][0])

            keys = pygame.key.get_pressed()
            fwd = keys[pygame.K_UP] or keys[pygame.K_z] or keys[pygame.K_w]
            back = keys[pygame.K_DOWN] or keys[pygame.K_s]
            left = keys[pygame.K_LEFT] or keys[pygame.K_q] or keys[pygame.K_a]
            right = keys[pygame.K_RIGHT] or keys[pygame.K_d]
            boost = keys[pygame.K_LSHIFT] or keys[pygame.K_RSHIFT]
            speed = 100 if boost else 50
            drone.speed = speed * (fwd - back)
            drone.turn = 40 * (right - left)

            if drone.frame_count != shown and drone.frame:
                shown = drone.frame_count
                try:
                    image = pygame.image.load(io.BytesIO(drone.frame), "frame.jpg")
                    image = pygame.transform.scale(image, screen.get_size())
                except pygame.error:
                    pass  # corrupt image, keep the previous one
            screen.fill((0, 0, 0))
            if image:
                screen.blit(image, (0, 0))
            hud = (f"battery {drone.battery if drone.battery is not None else '?'}%  "
                   f"speed {drone.speed}  turn {drone.turn}  "
                   f"posture {POSTURES[posture]}")
            screen.blit(font.render(hud, True, (255, 255, 0)), (8, 8))
            pygame.display.flip()
            clock.tick(30)
    finally:
        drone.video(False)
        drone.close()
        pygame.quit()


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="192.168.2.1", help="drone address")
    parser.add_argument("--probe", action="store_true",
                        help="test the connection without opening a window")
    args = parser.parse_args()
    drone = Drone(args.host)
    probe(drone) if args.probe else pilot(drone)


if __name__ == "__main__":
    main()
