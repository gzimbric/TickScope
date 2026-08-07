#!/usr/bin/env python3
"""Generate the TickScope icon as true pixel art: a 32x32 grid scaled up with
nearest-neighbour, so it stays crisp and blocky the way Minecraft textures do.
Pure stdlib -- writes the PNG by hand rather than pulling in Pillow."""

import struct, zlib, sys

G = 32                      # design grid
SCALE = 16                  # 32 * 16 = 512, Modrinth's preferred icon size

BG      = (0x1B, 0x1F, 0x24, 255)   # deep slate, reads dark on any theme
RIM     = (0x33, 0x3B, 0x45, 255)   # subtle bevel so it isn't a flat void
RING    = (0x2F, 0x6B, 0x45, 255)   # dim green scope reticle
RING_HI = (0x3E, 0x8C, 0x59, 255)   # reticle tick marks
LINE    = (0x55, 0xE0, 0x6B, 255)   # healthy baseline: Minecraft XP green
SPIKE   = (0xFF, 0xB4, 0x54, 255)   # the lag spike, amber
PEAK    = (0xFF, 0xE9, 0xA8, 255)   # highlight on the very top of the spike
CLEAR   = (0, 0, 0, 0)

px = [[CLEAR] * G for _ in range(G)]


def put(x, y, c):
    if 0 <= x < G and 0 <= y < G:
        px[y][x] = c


# --- rounded-square body -------------------------------------------------
CORNER = 3
for y in range(G):
    for x in range(G):
        # chop the corners to fake a radius on a pixel grid
        cx = min(x, G - 1 - x)
        cy = min(y, G - 1 - y)
        if cx + cy < CORNER:
            continue
        edge = cx == 0 or cy == 0 or (cx + cy == CORNER)
        put(x, y, RIM if edge else BG)

# --- scope reticle -------------------------------------------------------
CXF = CYF = (G - 1) / 2.0
R = 12.0
for y in range(G):
    for x in range(G):
        d = ((x - CXF) ** 2 + (y - CYF) ** 2) ** 0.5
        if R - 0.6 <= d <= R + 0.6 and px[y][x] != CLEAR:
            put(x, y, RING)

# crosshair ticks at N/E/S/W, the cue that says "scope" rather than "circle"
for i in range(3):
    put(16, 3 + i, RING_HI)          # top
    put(16, G - 4 - i, RING_HI)      # bottom
    put(3 + i, 16, RING_HI)          # left
    put(G - 4 - i, 16, RING_HI)      # right

# --- the trace: flat, one spike, flat -----------------------------------
# Reads as an MSPT graph, which is exactly what the plugin is for.
BASE = 21
TRACE = {x: BASE for x in range(6, 26)}
TRACE.update({14: 21, 15: 17, 16: 11, 17: 8, 18: 11, 19: 17, 20: 21})
SPIKE_ABOVE = 15                      # anything above this line is the lag spike


def trace_colour(y):
    return SPIKE if y < SPIKE_ABOVE else LINE


xs = sorted(TRACE)
for i in range(len(xs) - 1):
    x0, y0 = xs[i], TRACE[xs[i]]
    x1, y1 = xs[i + 1], TRACE[xs[i + 1]]
    put(x0, y0, trace_colour(y0))
    # vertical connector at the next column, so the polyline is a continuous
    # staircase rather than a row of disconnected dashes
    lo, hi = sorted((y0, y1))
    for yy in range(lo, hi + 1):
        put(x1, yy, trace_colour(yy))
put(xs[-1], TRACE[xs[-1]], LINE)

# Thicken only the flat baseline; doing it on the steep segments would fill
# the spike into a solid wedge.
for x in xs:
    if TRACE[x] == BASE:
        put(x, BASE + 1, LINE)
put(17, 8, PEAK)


def png(path, grid, scale):
    w = h = len(grid) * scale
    raw = bytearray()
    for row in grid:
        for _ in range(scale):
            raw.append(0)                     # filter type 0 for this scanline
            for c in row:
                raw.extend(bytes(c) * scale)
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))
    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    out += chunk(b"IEND", b"")
    open(path, "wb").write(out)
    return w, h, len(out)


if __name__ == "__main__":
    for path, sc in ((sys.argv[1], SCALE), (sys.argv[2], 3)):
        print("%s  %dx%d  %d bytes" % ((path,) + png(path, px, sc)))
