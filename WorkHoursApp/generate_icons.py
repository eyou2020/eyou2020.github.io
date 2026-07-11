"""
런처 아이콘 PNG 생성 스크립트
파란 배경(#1565C0)에 흰색 W 글자
"""
import struct
import zlib
import os


def make_png(width, height, pixels):
    """pixels: list of (r, g, b) tuples, row-major"""
    def chunk(tag, data):
        c = tag + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xFFFFFFFF)

    sig   = b'\x89PNG\r\n\x1a\n'
    ihdr  = chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
    raw   = b''.join(b'\x00' + bytes([c for px in row for c in px])
                     for row in [pixels[y * width:(y + 1) * width] for y in range(height)])
    idat  = chunk(b'IDAT', zlib.compress(raw, 9))
    iend  = chunk(b'IEND', b'')
    return sig + ihdr + idat + iend


def draw_icon(size):
    """파란 배경 + 흰색 W 글자 아이콘 생성"""
    BG  = (0x15, 0x65, 0xC0)   # #1565C0 파랑
    FG  = (0xFF, 0xFF, 0xFF)   # 흰색

    pixels = [BG] * (size * size)

    def set_pixel(x, y):
        if 0 <= x < size and 0 <= y < size:
            pixels[y * size + x] = FG

    def fill_rect(x0, y0, x1, y1):
        for y in range(y0, y1):
            for x in range(x0, x1):
                set_pixel(x, y)

    # W 글자 좌표 비율로 계산
    p  = size // 8          # 패딩
    th = max(size // 10, 2) # 획 두께

    # W의 왼쪽 수직 획
    fill_rect(p,        p,        p + th,      size - p)
    # W의 오른쪽 수직 획
    fill_rect(size - p - th, p,  size - p,    size - p)
    # W의 가운데 V (중간 아래쪽 꼭짓점)
    mid = size // 2
    for i in range(size // 3):
        xi = p + th + i
        yi = p + (size - 2 * p) * i // (size // 3)
        fill_rect(xi, size - p - th - (size - 2 * p - yi), xi + th, size - p - (size - 2 * p - yi))

    # 단순하게 W를 다시 그리기 (직선 기반)
    pixels = [BG] * (size * size)
    s = size

    def line_v(cx, y0, y1):
        w = max(s // 12, 2)
        fill_rect(cx - w // 2, y0, cx - w // 2 + w, y1)

    def line_d(x0, y0, x1, y1):
        steps = max(abs(x1 - x0), abs(y1 - y0))
        if steps == 0:
            return
        w = max(s // 14, 2)
        for i in range(steps + 1):
            cx = x0 + (x1 - x0) * i // steps
            cy = y0 + (y1 - y0) * i // steps
            fill_rect(cx - w // 2, cy - w // 2, cx - w // 2 + w, cy - w // 2 + w)

    t  = s // 10   # 획 두께
    m  = s // 8    # 여백

    # W: 왼쪽 기둥
    fill_rect(m, m, m + t, s - m)
    # W: 오른쪽 기둥
    fill_rect(s - m - t, m, s - m, s - m)
    # W: 왼쪽 대각선 (위-왼 → 아래-중앙)
    line_d(m + t, m, s // 2, s - m)
    # W: 오른쪽 대각선 (아래-중앙 → 위-오른)
    line_d(s // 2, s - m, s - m - t, m)

    return pixels


def save_icon(path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pixels = draw_icon(size)
    data   = make_png(size, size, pixels)
    with open(path, 'wb') as f:
        f.write(data)
    print(f'생성: {path}  ({size}x{size}px, {len(data)} bytes)')


BASE = os.path.join(os.path.dirname(__file__), 'app', 'src', 'main', 'res')

DENSITIES = {
    'mipmap-mdpi':    48,
    'mipmap-hdpi':    72,
    'mipmap-xhdpi':   96,
    'mipmap-xxhdpi':  144,
    'mipmap-xxxhdpi': 192,
}

for folder, size in DENSITIES.items():
    save_icon(os.path.join(BASE, folder, 'ic_launcher.png'), size)
    save_icon(os.path.join(BASE, folder, 'ic_launcher_round.png'), size)

print('\n완료! 모든 mipmap PNG 아이콘이 생성되었습니다.')
