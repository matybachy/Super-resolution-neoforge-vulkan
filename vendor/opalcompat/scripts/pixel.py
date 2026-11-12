"""Prints RGB values of pixels in a PNG without third-party modules: pixel.py file.png x,y [x,y ...]"""
import struct
import sys
import zlib


def read_png(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    pos = 8
    idat = b''
    width = height = bit_depth = colour = 0
    while pos < len(data):
        length, kind = struct.unpack('>I4s', data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if kind == b'IHDR':
            width, height, bit_depth, colour = struct.unpack('>IIBB', body[:10])
        elif kind == b'IDAT':
            idat += body
        pos += 12 + length
    channels = {2: 3, 6: 4}[colour]
    raw = zlib.decompress(idat)
    stride = width * channels
    rows = []
    previous = bytearray(stride)
    at = 0
    for _ in range(height):
        kind = raw[at]
        line = bytearray(raw[at + 1:at + 1 + stride])
        at += 1 + stride
        for i in range(stride):
            a = line[i - channels] if i >= channels else 0
            b = previous[i]
            c = previous[i - channels] if i >= channels else 0
            if kind == 1:
                line[i] = (line[i] + a) & 255
            elif kind == 2:
                line[i] = (line[i] + b) & 255
            elif kind == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif kind == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        previous = line
    return width, height, channels, rows


width, height, channels, rows = read_png(sys.argv[1])
print('size', width, height)
for spec in sys.argv[2:]:
    x, y = (int(v) for v in spec.split(','))
    px = rows[y][x * channels:x * channels + 3]
    print(spec, tuple(px), tuple(round(v / 255, 3) for v in px))
