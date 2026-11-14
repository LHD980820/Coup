#!/usr/bin/env python3
"""카드 초상을 그린다 (docs/DESIGN.md "카드 초상").

역할 다섯의 초상을 이 스크립트가 도형으로 그려 Android 벡터 드로어블로 내보낸다. 사진이나 남의 그림을 쓰지 않는다 —
이 저장소에서 만든 그림이라 출처 문제가 없고, 여기 숫자를 고쳐 다시 돌리면 그림이 바뀐다.

    python3 tools/card-art/portraits.py                 # app/src/main/res/drawable/portrait_*.xml 을 다시 쓴다
    python3 tools/card-art/portraits.py --preview DIR   # 확인용 SVG 와 preview.html 도 DIR 에 쓴다

그림판은 300x400(가로:세로 3:4)이고 앱은 그 안의 타원만 보인다. 자리의 작은 카드는 가운데(머리)를 당겨 보이므로
머리와 모자는 x 60~240, y 60~260 안에 둔다. 빛은 왼쪽 위에서 온다(얼굴 오른쪽이 그늘).
"""
import argparse
import math
import os

W, H, CX = 300, 400, 150


# ---- 도형 ---------------------------------------------------------------------------------------

def n(v):
    s = '%.1f' % v
    return s.rstrip('0').rstrip('.')


def poly(points, close=True):
    d = 'M' + ' L'.join('%s,%s' % (n(p[0]), n(p[1])) for p in points)
    return d + (' Z' if close else '')


def smooth(points, close=True):
    """점들을 지나는 부드러운 곡선(Catmull-Rom). (x, y, 'c')로 적은 점은 모서리로 꺾인다."""
    pts = list(points)
    k = len(pts)

    def at(i):
        return pts[i % k] if close else pts[max(0, min(k - 1, i))]

    def tension(i):
        p = at(i)
        return 0.0 if len(p) > 2 and p[2] == 'c' else 1.0

    d = 'M%s,%s' % (n(at(0)[0]), n(at(0)[1]))
    for i in range(k if close else k - 1):
        p0, p1, p2, p3 = at(i - 1), at(i), at(i + 1), at(i + 2)
        t1, t2 = tension(i), tension(i + 1)
        c1 = (p1[0] + t1 * (p2[0] - p0[0]) / 6, p1[1] + t1 * (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - t2 * (p3[0] - p1[0]) / 6, p2[1] - t2 * (p3[1] - p1[1]) / 6)
        d += ' C%s,%s %s,%s %s,%s' % (n(c1[0]), n(c1[1]), n(c2[0]), n(c2[1]), n(p2[0]), n(p2[1]))
    return d + (' Z' if close else '')


def ellipse(cx, cy, rx, ry=None):
    ry = rx if ry is None else ry
    return 'M%s,%s A%s,%s 0 1,0 %s,%s A%s,%s 0 1,0 %s,%s Z' % (
        n(cx - rx), n(cy), n(rx), n(ry), n(cx + rx), n(cy), n(rx), n(ry), n(cx - rx), n(cy))


def mirror(points):
    return [(2 * CX - p[0],) + tuple(p[1:]) for p in points]


def sym(half):
    """세로 가운데 선 위에서 시작해 오른쪽으로 돌아 가운데 선 위에서 끝나는 반쪽을 좌우 대칭으로 닫는다."""
    return list(half) + list(reversed(mirror(half[1:-1])))


def both(points):
    """오른쪽에 그린 것과 그 왼쪽 거울상."""
    return [list(points), mirror(points)]


def crescent(c, radius, cut, cut_radius):
    """원 c 에서 원 cut 과 겹치는 부분을 덜어 낸 초승달."""
    dx, dy = cut[0] - c[0], cut[1] - c[1]
    dist = math.hypot(dx, dy)
    along = (radius ** 2 - cut_radius ** 2 + dist ** 2) / (2 * dist)
    half = math.sqrt(radius ** 2 - along ** 2)
    px, py = c[0] + along * dx / dist, c[1] + along * dy / dist
    p1 = (px - half * dy / dist, py + half * dx / dist)
    p2 = (px + half * dy / dist, py - half * dx / dist)
    # 바깥 호는 덜어 내는 원의 반대쪽으로 크게 돌고, 안쪽 호는 덜어 내는 원을 따라 되돌아온다
    return 'M%s,%s A%s,%s 0 1,1 %s,%s A%s,%s 0 %d,0 %s,%s Z' % (
        n(p1[0]), n(p1[1]), n(radius), n(radius), n(p2[0]), n(p2[1]),
        n(cut_radius), n(cut_radius), 1 if along < 0 else 0, n(p1[0]), n(p1[1]))


def star(cx, cy, outer, inner, spikes, turn=-90.0):
    pts = []
    for i in range(spikes * 2):
        r = outer if i % 2 == 0 else inner
        a = math.radians(turn + i * 180.0 / spikes)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return poly(pts)


# ---- 그림판 -------------------------------------------------------------------------------------

class Gradient:
    def __init__(self, kind, coords, stops):
        self.kind, self.coords, self.stops = kind, coords, stops


def linear(x1, y1, x2, y2, stops):
    return Gradient('linear', (x1, y1, x2, y2), stops)


def radial(cx, cy, r, stops):
    return Gradient('radial', (cx, cy, r), stops)


def rgba(color):
    """'#RRGGBB' 또는 ('#RRGGBB', 투명도) -> (rgb, 투명도)."""
    return (color, 1.0) if isinstance(color, str) else (color[0], color[1])


class Art:
    def __init__(self):
        self.shapes = []

    def add(self, d, fill, alpha=1.0):
        if isinstance(fill, tuple):  # ('#RRGGBB', 투명도)로 적은 단색
            fill, alpha = fill[0], alpha * fill[1]
        self.shapes.append((d, fill, alpha))

    def each(self, paths, fill, alpha=1.0):
        for d in paths:
            self.add(d, fill, alpha)

    def svg(self):
        defs, body = [], []
        for i, (d, fill, alpha) in enumerate(self.shapes):
            if isinstance(fill, Gradient):
                gid = 'g%d' % i
                stops = ''.join(
                    '<stop offset="%s" stop-color="%s" stop-opacity="%s"/>' % (n(o), rgba(c)[0], n(rgba(c)[1])) for o, c in fill.stops)
                if fill.kind == 'linear':
                    x1, y1, x2, y2 = fill.coords
                    defs.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="%s" y1="%s" x2="%s" y2="%s">%s</linearGradient>'
                                % (gid, n(x1), n(y1), n(x2), n(y2), stops))
                else:
                    cx, cy, r = fill.coords
                    defs.append('<radialGradient id="%s" gradientUnits="userSpaceOnUse" cx="%s" cy="%s" r="%s">%s</radialGradient>'
                                % (gid, n(cx), n(cy), n(r), stops))
                paint = 'url(#%s)' % gid
            else:
                paint = fill
            body.append('<path d="%s" fill="%s"%s/>' % (d, paint, '' if alpha >= 1 else ' fill-opacity="%s"' % n(alpha)))
        return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d"><defs>%s</defs>%s</svg>\n'
                % (W, H, ''.join(defs), ''.join(body)))

    def vector_drawable(self):
        def argb(color):
            rgb, a = rgba(color)
            return '#%02X%s' % (round(a * 255), rgb[1:].upper())

        out = ['<?xml version="1.0" encoding="utf-8"?>',
               '<!-- tools/card-art/portraits.py 가 만든 파일이다. 직접 고치지 말고 스크립트를 고쳐 다시 만든다. -->',
               '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
               '    xmlns:aapt="http://schemas.android.com/aapt"',
               '    android:width="%ddp" android:height="%ddp"' % (W, H),
               '    android:viewportWidth="%d" android:viewportHeight="%d">' % (W, H)]
        for d, fill, alpha in self.shapes:
            a = '' if alpha >= 1 else ' android:fillAlpha="%s"' % n(alpha)
            if isinstance(fill, Gradient):
                out.append('    <path android:pathData="%s"%s>' % (d, a))
                out.append('        <aapt:attr name="android:fillColor">')
                if fill.kind == 'linear':
                    x1, y1, x2, y2 = fill.coords
                    out.append('            <gradient android:type="linear" android:startX="%s" android:startY="%s" android:endX="%s" android:endY="%s">'
                               % (n(x1), n(y1), n(x2), n(y2)))
                else:
                    cx, cy, r = fill.coords
                    out.append('            <gradient android:type="radial" android:centerX="%s" android:centerY="%s" android:gradientRadius="%s">'
                               % (n(cx), n(cy), n(r)))
                for o, c in fill.stops:
                    out.append('                <item android:offset="%s" android:color="%s"/>' % (n(o), argb(c)))
                out.append('            </gradient>')
                out.append('        </aapt:attr>')
                out.append('    </path>')
            else:
                out.append('    <path android:pathData="%s" android:fillColor="%s"%s/>' % (d, fill, a))
        out.append('</vector>')
        return '\n'.join(out) + '\n'


# ---- 함께 쓰는 조각 -----------------------------------------------------------------------------

INK = '#17110D'
GOLD, GOLD_LIGHT, GOLD_DARK = '#E6B855', '#FFE39A', '#9A7424'
FULL = 'M0,0 H%d V%d H0 Z' % (W, H)


def background(a, light, mid, dark, glow=(150, 150)):
    """역할 색 바탕: 머리 뒤가 밝고 가장자리가 어둡다. 그 위에 머리 뒤에서 퍼지는 엷은 빛살."""
    a.add(FULL, radial(glow[0], glow[1], 300, [(0, light), (0.5, mid), (1, dark)]))
    for i in range(12):
        t = math.radians(i * 30 + 15)
        u, v = math.radians(i * 30 + 15 - 5), math.radians(i * 30 + 15 + 5)
        a.add(poly([glow, (glow[0] + 420 * math.cos(u), glow[1] + 420 * math.sin(u)), (glow[0] + 420 * math.cos(v), glow[1] + 420 * math.sin(v))]),
              light, 0.07)


def vignette(a, color):
    """아래쪽을 어둡게 눌러 이름 띠와 이어지게 한다."""
    a.add(FULL, linear(0, 250, 0, 400, [(0, (color, 0.0)), (1, (color, 0.6))]))


FACE = [(150, 104), (172, 107), (189, 125), (196, 154), (192, 183), (180, 207), (164, 222), (150, 226)]
FACE_SHADE = [(156, 104), (172, 107), (189, 125), (196, 154), (192, 183), (180, 207), (164, 222), (150, 226),
              (158, 208), (163, 184), (159, 154), (161, 126)]


def widen(points, jaw):
    """얼굴형: 광대 아래(y > 150)의 점을 가운데 선에서 jaw 배만큼 벌리거나 좁힌다."""
    return [(CX + (p[0] - CX) * (jaw if p[1] > 150 else 1.0),) + tuple(p[1:]) for p in points]


def head(a, skin, shade, deep, neck_to=266, jaw=1.0, neck=23):
    """귀, 목, 얼굴과 얼굴 오른쪽의 그늘. jaw 가 1보다 작으면 갸름하고 크면 각진 턱이다."""
    a.each([ellipse(101, 167, 7, 12), ellipse(199, 167, 7, 12)], shade)
    a.add(poly([(CX - neck, 200), (CX + neck, 200), (CX + neck + 5, neck_to), (CX - neck - 5, neck_to)]), shade)
    a.add(smooth([(CX - neck, 212), (150, 234), (CX + neck, 212), (CX + neck + 3, 240), (150, 252), (CX - neck - 3, 240)]), deep, 0.55)  # 턱 아래 그늘
    a.add(smooth(sym(widen(FACE, jaw))), skin)
    a.add(smooth(widen(FACE_SHADE, jaw)), shade, 0.9)


def eyes(a, y=160, color=INK, white=None, gap=19):
    for cx in (CX - gap, CX + gap):
        if white:
            a.add(smooth([(cx - 10, y), (cx, y - 5.5), (cx + 10, y), (cx, y + 4.5)]), white)
        a.add(ellipse(cx, y, 4.6, 4.6), color)
        a.add(ellipse(cx - 1.6, y - 1.6, 1.3), '#FFFFFF')  # 눈빛
        a.add(smooth([(cx - 11, y - 1), (cx, y - 6.5), (cx + 11, y - 1), (cx, y - 4)]), color)  # 윗눈꺼풀


def brows(a, color, y=146, inner_drop=3.0, thick=4.5):
    """눈썹. inner_drop 이 크면 안쪽이 내려와 엄한 얼굴이 된다."""
    right = [(CX + 7, y + inner_drop), (CX + 31, y - 3), (CX + 33, y + 1), (CX + 8, y + inner_drop + thick)]
    a.each([poly(p) for p in both(right)], color)


def nose(a, shade):
    a.add(smooth([(151, 164), (158, 188), (151, 195), (144, 192), (152, 187)]), shade, 0.85)


def mouth(a, color, y=206, width=13, smile=1.5):
    a.add(smooth([(CX - width, y - smile), (CX, y + 1), (CX + width, y - smile), (CX, y + 4)]), color)


# ---- 공작 ---------------------------------------------------------------------------------------

def duke():
    a = Art()
    background(a, '#9A7BE0', '#5A3B9E', '#22123F')
    # 망토: 어깨를 덮는 짙은 보라
    a.add(smooth([(150, 250), (196, 262, 'c'), (250, 290), (284, 340), (292, 400, 'c'), (8, 400, 'c'), (16, 340), (50, 290), (104, 262, 'c')]), '#3A2170')
    a.add(smooth([(150, 262), (196, 262, 'c'), (250, 290), (284, 340), (292, 400, 'c'), (200, 400, 'c'), (190, 330)]), '#27124F', 0.75)
    head(a, '#EBC09A', '#C99468', '#8C5E3E')
    # 흰 담비 털 깃: 목에서 어깨로 넓게 흘러내린다
    collar = [(150, 276), (176, 256, 'c'), (214, 268), (256, 296), (276, 330), (246, 344), (206, 322), (176, 316), (150, 334)]
    a.add(smooth(sym(collar)), '#F4EEE2')
    a.add(smooth([(150, 334), (176, 316), (206, 322), (246, 344), (276, 330), (268, 318), (238, 330), (204, 310), (176, 304), (150, 322)]), '#CFC4B2', 0.8)
    for x, y in [(190, 284), (222, 300), (250, 320), (176, 300), (208, 314)]:
        a.each([smooth([(px, y - 6), (px + 3.5, y + 2), (px, y + 6), (px - 3.5, y + 2)]) for px in (x, 300 - x)], INK)
    # 목걸이 사슬과 메달
    a.add(smooth([(112, 300), (150, 346), (188, 300), (192, 305), (150, 356), (108, 305)]), GOLD)
    a.add(ellipse(150, 358, 15), GOLD_DARK)
    a.add(ellipse(150, 357, 12.5), GOLD)
    a.add(star(150, 357, 8, 3.4, 4), '#7B2FBE')
    # 머리카락과 구레나룻
    hair = '#3B2A22'
    a.each([smooth(p) for p in both([(188, 112), (201, 128), (203, 160), (197, 176), (193, 150), (190, 128)])], hair)
    # 수염: 콧수염과 턱수염
    a.each([smooth(p) for p in both([(150, 194), (164, 196), (176, 204), (166, 204), (154, 201)])], hair)
    a.add(smooth([(141, 214), (150, 212), (159, 214), (155, 230), (150, 236), (145, 230)]), hair)
    eyes(a, color='#241711', white='#F7EFE3')
    brows(a, hair, inner_drop=4.5, thick=5)
    nose(a, '#B07E55')
    mouth(a, '#7A3B2E', y=207, width=9, smile=-1.0)
    # 왕관
    crown = [(104, 112, 'c'), (100, 76, 'c'), (118, 92, 'c'), (128, 62, 'c'), (139, 88, 'c'), (150, 52, 'c'),
             (161, 88, 'c'), (172, 62, 'c'), (182, 92, 'c'), (200, 76, 'c'), (196, 112, 'c')]
    a.add(poly(crown), linear(100, 60, 200, 112, [(0, GOLD_LIGHT), (0.55, GOLD), (1, GOLD_DARK)]))
    a.add(poly([(104, 100), (196, 100), (196, 114), (104, 114)]), GOLD_DARK)
    a.add(poly([(104, 100), (196, 100), (196, 104), (104, 104)]), GOLD_LIGHT, 0.8)
    for x, color in [(150, '#C8324A'), (126, '#5B8DEF'), (174, '#5B8DEF')]:
        a.add(ellipse(x, 108, 4.2), color)
    for x, y in [(150, 52), (128, 62), (172, 62), (100, 76), (200, 76)]:
        a.add(ellipse(x, y, 4.5), GOLD_LIGHT)
    vignette(a, '#12082A')
    return a


# ---- 암살자 -------------------------------------------------------------------------------------

def assassin():
    a = Art()
    background(a, '#6E7B92', '#3A4354', '#12151C', glow=(150, 140))
    # 달
    a.add(ellipse(224, 88, 40), radial(224, 88, 40, [(0, ('#DCE8F7', 0.30)), (1, ('#DCE8F7', 0.0))]))
    a.add(crescent((224, 88), 26, (237, 79), 24), '#EEF3FA', 0.92)
    # 망토
    a.add(smooth([(150, 236), (206, 250), (262, 292), (290, 350), (296, 400, 'c'), (4, 400, 'c'), (10, 350), (38, 292), (94, 250)]), '#20242E')
    a.add(smooth([(150, 262), (206, 250), (262, 292), (290, 350), (296, 400, 'c'), (190, 400, 'c'), (178, 320)]), '#14171E', 0.8)
    # 두건: 뾰족한 꼭대기에서 어깨까지
    hood = [(150, 44), (176, 62), (206, 104), (224, 160), (228, 214), (212, 262), (150, 290)]
    a.add(smooth(sym(hood)), linear(70, 60, 230, 280, [(0, '#5A6373'), (0.5, '#353C49'), (1, '#1C2029')]))
    a.add(smooth([(150, 44), (176, 62), (206, 104), (224, 160), (228, 214), (212, 262), (196, 270), (206, 214), (200, 160), (182, 104), (160, 66)]), '#12151B', 0.55)
    # 두건 안: 얼굴은 어둠에 잠겨 있다
    inside = [(150, 86), (172, 100), (190, 134), (196, 174), (188, 214), (170, 244), (150, 254)]
    a.add(smooth(sym(inside)), '#07080B')
    # 얼굴 아래를 가린 복면
    mask = [(150, 176), (174, 180), (192, 174), (188, 214), (170, 244), (150, 254)]
    a.add(smooth(sym(mask)), '#232834')
    a.add(smooth([(150, 176), (174, 180), (192, 174), (188, 214), (170, 244), (150, 254), (158, 226), (160, 200)]), '#12151B', 0.7)
    a.add(poly([(148, 178), (152, 178), (153, 252), (147, 252)]), '#3A4252', 0.9)
    # 눈: 어둠 속에서 빛난다
    for cx, s in ((129, 1), (171, -1)):
        a.add(ellipse(cx, 160, 20, 12), radial(cx, 160, 20, [(0, ('#9FE3FF', 0.55)), (1, ('#9FE3FF', 0.0))]))
        a.add(smooth([(cx - 13 * s, 163), (cx - 2 * s, 153), (cx + 13 * s, 155), (cx + 3 * s, 166)]), '#BFEFFF')
        a.add(smooth([(cx - 8 * s, 161.5), (cx - 1 * s, 156.5), (cx + 8 * s, 157.5), (cx + 2 * s, 163)]), '#FFFFFF')
    # 망토 고리
    a.add(poly([(150, 282), (162, 296), (150, 312), (138, 296)]), '#AEB8C8')
    a.add(poly([(150, 282), (162, 296), (150, 312)]), '#6F7A8C')
    # 단검: 오른쪽 아래에서 치켜든 칼날
    a.add(poly([(232, 212), (246, 300), (226, 304)]), linear(226, 212, 248, 300, [(0, '#F4F8FC'), (0.5, '#B5C0CF'), (1, '#6F7A8C')]))
    a.add(poly([(232, 212), (236, 302), (226, 304)]), '#FFFFFF', 0.55)
    a.add(poly([(212, 300), (260, 292), (262, 302), (214, 310)]), '#8D97A8')
    a.add(poly([(228, 306), (246, 303), (250, 340), (232, 343)]), '#2B303B')
    a.add(smooth([(218, 318), (256, 312), (268, 334), (262, 362), (228, 368), (214, 344)]), '#191C24')
    vignette(a, '#07080B')
    return a


# ---- 사령관 -------------------------------------------------------------------------------------

def captain():
    a = Art()
    background(a, '#6FB1F2', '#2C68AB', '#0B2040')
    navy, navy_dark = '#17365F', '#0D2242'
    # 제복 어깨
    a.add(smooth([(150, 256), (198, 264, 'c'), (252, 284), (286, 326), (294, 400, 'c'), (6, 400, 'c'), (14, 326), (48, 284), (102, 264, 'c')]), navy)
    a.add(smooth([(150, 264), (198, 264, 'c'), (252, 284), (286, 326), (294, 400, 'c'), (196, 400, 'c'), (186, 330)]), navy_dark, 0.75)
    head(a, '#E2B48A', '#BC8A5E', '#7E5637', jaw=1.05, neck=26)
    # 높은 깃과 금 테
    for p in both([(150, 300), (158, 262, 'c'), (182, 250), (192, 276), (176, 312)]):
        a.add(smooth(p), navy)
    for p in both([(158, 262), (182, 250), (184, 256), (162, 268)]):
        a.add(poly(p), GOLD)
    a.add(poly([(146, 300), (154, 300), (156, 400), (144, 400)]), navy_dark)
    for y in (322, 352, 382):
        a.each([ellipse(x, y, 6) for x in (128, 172)], GOLD)
        a.each([ellipse(x - 1.5, y - 1.5, 2.2) for x in (128, 172)], GOLD_LIGHT)
    # 견장: 금빛 판과 술
    for p in both([(214, 276), (262, 286), (280, 312), (270, 326), (224, 308), (206, 290)]):
        a.add(smooth(p), linear(206, 276, 280, 326, [(0, GOLD_LIGHT), (0.6, GOLD), (1, GOLD_DARK)]))
    for i in range(6):
        x, y = 228 + i * 9, 310 + i * 3.2
        a.each([poly(p) for p in both([(x, y), (x + 6, y + 2), (x + 4, y + 20), (x - 2, y + 18)])], GOLD_DARK if i % 2 else GOLD)
    # 흰 수염: 턱선을 따라 귀에서 귀까지
    beard = [(150, 198), (168, 196), (186, 186), (196, 166), (200, 196), (190, 226), (172, 246), (150, 254)]
    a.add(smooth(sym(beard)), '#EEF1F4')
    a.add(smooth([(156, 200), (168, 196), (186, 186), (196, 166), (200, 196), (190, 226), (172, 246), (150, 254), (160, 232), (164, 212)]), '#B9C3CE', 0.85)
    a.each([smooth(p) for p in both([(150, 192), (166, 192), (182, 202), (168, 206), (154, 202)])], '#FFFFFF')  # 콧수염
    a.add(smooth([(140, 208), (150, 206), (160, 208), (150, 213)]), '#7A4A3A')
    eyes(a, y=158, color='#1B2A3A', white='#F4F1EA')
    brows(a, '#DDE3EA', y=144, inner_drop=3.5, thick=5.5)
    nose(a, '#A8784F')
    # 제모: 흰 윗판, 남색 띠, 검은 챙, 금 휘장
    a.add(smooth([(150, 52), (190, 58), (216, 78), (212, 104, 'c'), (88, 104, 'c'), (84, 78), (110, 58)]), linear(84, 52, 216, 104, [(0, '#FFFFFF'), (1, '#C9D3DF')]))
    a.add(poly([(92, 100), (208, 100), (206, 124), (94, 124)]), navy_dark)
    a.add(poly([(92, 100), (208, 100), (208, 104), (92, 104)]), GOLD)
    a.add(smooth([(92, 122, 'c'), (150, 118), (208, 122, 'c'), (214, 136), (150, 142), (86, 136)]), '#0B0F16')
    a.add(smooth([(100, 126), (150, 122), (200, 126), (150, 128)]), '#3B4656', 0.9)
    a.add(ellipse(150, 88, 15, 13), GOLD_DARK)
    a.add(ellipse(150, 88, 12, 10.5), GOLD)
    # 휘장의 닻
    a.add(poly([(148.4, 80), (151.6, 80), (151.6, 95), (148.4, 95)]), navy_dark)
    a.add(poly([(144, 83.5), (156, 83.5), (156, 86), (144, 86)]), navy_dark)
    a.add(smooth([(141, 90), (150, 97), (159, 90), (157, 89), (150, 94), (143, 89)]), navy_dark)
    vignette(a, '#061426')
    return a


# ---- 대사 ---------------------------------------------------------------------------------------

def ambassador():
    a = Art()
    background(a, '#7FDFAA', '#2E8C5D', '#0B2C1C')
    coat, coat_dark = '#1E2B26', '#121B17'
    a.add(smooth([(150, 256), (198, 266, 'c'), (250, 288), (284, 332), (292, 400, 'c'), (8, 400, 'c'), (16, 332), (50, 288), (102, 266, 'c')]), coat)
    a.add(smooth([(150, 266), (198, 266, 'c'), (250, 288), (284, 332), (292, 400, 'c'), (198, 400, 'c'), (188, 330)]), coat_dark, 0.75)
    head(a, '#C08A5C', '#96643E', '#5F3D24', jaw=0.98)
    # 흰 셔츠 깃과 초록 나비넥타이
    a.add(poly([(124, 262), (176, 262), (168, 318), (150, 400), (132, 318)]), '#F3F1EA')
    a.add(poly([(150, 262), (176, 262), (168, 318), (150, 400)]), '#CFCBBF', 0.8)
    for p in both([(150, 284), (182, 266), (186, 302)]):
        a.add(smooth(p), '#1F9A5F')
    for p in both([(150, 284), (186, 302), (184, 288)]):
        a.add(poly(p), '#0F6038', 0.7)
    a.add(ellipse(150, 284, 7.5, 9), '#157A49')
    # 옷깃
    for p in both([(176, 262), (200, 268), (176, 344), (158, 400), (150, 400), (168, 318)]):
        a.add(poly(p), coat)
    for p in both([(176, 262), (200, 268), (186, 306), (170, 300)]):
        a.add(poly(p), '#2C3E37', 0.9)
    # 어깨에서 허리로 내려오는 장식 띠와 별 훈장
    a.add(poly([(204, 270), (236, 282), (132, 400), (92, 400)]), linear(92, 400, 236, 270, [(0, '#1F9A5F'), (1, '#58D494')]), 0.95)
    a.add(poly([(204, 270), (212, 273), (104, 400), (92, 400)]), GOLD, 0.9)
    a.add(poly([(228, 279), (236, 282), (132, 400), (120, 400)]), GOLD, 0.9)
    a.add(star(96, 338, 19, 8, 8), GOLD_DARK)
    a.add(star(96, 337, 16, 6.8, 8), linear(80, 322, 112, 354, [(0, GOLD_LIGHT), (1, GOLD)]))
    a.add(ellipse(96, 337, 5), '#C8324A')
    # 검은 머리: 옆 가르마로 빗어 넘겼다
    hair = '#15110E'
    a.add(smooth([(150, 86), (184, 90), (202, 116), (204, 150), (196, 162), (194, 132), (178, 112), (140, 110), (112, 122), (106, 162), (98, 150), (98, 116), (116, 92)]), hair)
    a.add(smooth([(120, 98), (150, 90), (176, 96), (150, 96), (126, 106)]), '#3A332C', 0.9)
    a.add(smooth([(98, 150), (106, 140), (108, 168), (102, 176)]), hair)
    eyes(a, y=160, color='#1A120C', white='#F6F0E6')
    brows(a, hair, y=146, inner_drop=1.0, thick=4)
    nose(a, '#84552F')
    # 가는 콧수염과 옅은 미소
    a.each([smooth(p) for p in both([(150, 196), (162, 196), (172, 201), (162, 201)])], hair)
    mouth(a, '#6B3523', y=208, width=11, smile=2.5)
    # 둥근 금테 안경
    for cx in (131, 169):
        a.add(ellipse(cx, 160, 16.5) + ' ' + ellipse(cx, 160, 13.5).replace('1,0', '1,1'), GOLD)
        a.add(ellipse(cx, 160, 13.5), ('#FFFFFF', 0.10))
    a.add(poly([(144, 157), (156, 157), (156, 160.5), (144, 160.5)]), GOLD)
    for p in both([(185, 157), (200, 160), (200, 163.5), (185, 160.5)]):
        a.add(poly(p), GOLD)
    # 두루마리 문서와 붉은 인장
    a.add(poly([(200, 330), (268, 312), (276, 342), (208, 360)]), '#F2E6C8')
    a.add(poly([(204, 345), (272, 327), (276, 342), (208, 360)]), '#CDBE98', 0.8)
    a.each([ellipse(204, 345, 7, 16), ellipse(272, 327, 7, 16)], '#E0D2AE')
    a.add(ellipse(238, 342, 10), '#B3202F')
    a.add(ellipse(238, 342, 5.5), '#DA4B57')
    vignette(a, '#06180F')
    return a


# ---- 백작부인 -----------------------------------------------------------------------------------

def contessa():
    a = Art()
    background(a, '#FF9AA8', '#B8364D', '#3A0C17')
    skin, shade, deep = '#F4D0B8', '#D9A688', '#A8745A'
    hair, hair_light = '#3E1A1C', '#6B2E2E'
    # 올림머리의 뒤쪽 덩어리
    a.add(smooth([(150, 34), (184, 40), (210, 66), (218, 108), (212, 152), (200, 178), (100, 178), (88, 152), (82, 108), (90, 66), (116, 40)]), hair)
    # 드러난 어깨
    a.add(smooth([(150, 262), (184, 268, 'c'), (236, 282), (274, 304), (290, 340), (294, 400, 'c'), (6, 400, 'c'), (10, 340), (26, 304), (64, 282), (116, 268, 'c')]), skin)
    a.add(smooth([(150, 270), (184, 268, 'c'), (236, 282), (274, 304), (290, 340), (294, 400, 'c'), (196, 400, 'c'), (186, 320)]), shade, 0.7)
    head(a, skin, shade, deep, neck_to=276, jaw=0.9, neck=19)
    # 드레스: 하트 모양 목선과 금 테
    gown = [(150, 344), (174, 326), (206, 322), (246, 332), (286, 322), (296, 400, 'c'), (150, 400, 'c')]
    a.add(smooth(sym(gown)), linear(0, 320, 0, 400, [(0, '#C8324A'), (1, '#7A1626')]))
    a.add(smooth([(150, 344), (174, 326), (206, 322), (246, 332), (286, 322), (288, 330), (246, 341), (206, 331), (176, 335), (150, 354)]), GOLD)
    a.add(smooth([(126, 335), (94, 331), (54, 341), (12, 330), (14, 322), (54, 332), (94, 322), (126, 326), (150, 344), (150, 354)]), GOLD)
    # 목걸이: 가는 금줄과 붉은 보석
    a.add(smooth([(126, 258), (150, 268), (174, 258), (175, 262), (150, 273), (125, 262)]), GOLD)
    a.add(smooth([(150, 270), (157, 282), (150, 294), (143, 282)]), '#C8324A')
    a.add(smooth([(150, 273), (154, 282), (150, 288)]), '#F08A96', 0.9)
    # 앞머리: 가운데 가르마에서 양옆으로 흘러 귀를 덮는다
    for p in both([(150, 96), (176, 100), (196, 122), (204, 158), (200, 190), (192, 170), (188, 138), (172, 116)]):
        a.add(smooth(p), hair)
    # 머리 결: 빛 받는 왼쪽 위에 밝은 결 두 줄
    a.add(smooth([(100, 104), (106, 72), (130, 48), (150, 42), (128, 56), (112, 78), (106, 108)]), hair_light, 0.9)
    a.add(smooth([(160, 44), (186, 56), (202, 84), (204, 110), (196, 86), (180, 62)]), hair_light, 0.55)
    # 귀걸이
    for x in (99, 201):
        a.add(ellipse(x, 184, 3.2), GOLD)
        a.add(smooth([(x, 188), (x + 5, 200), (x, 208), (x - 5, 200)]), '#C8324A')
        a.add(ellipse(x - 1.2, 198, 1.6), '#F6B0B8')
    # 눈: 속눈썹이 길다
    eyes(a, y=160, color='#2A1512', white='#FBF6F0')
    for cx, s in ((131, -1), (169, 1)):
        a.add(smooth([(cx + 8 * s, 156), (cx + 17 * s, 150), (cx + 12 * s, 158)]), '#2A1512')
    brows(a, hair, y=145, inner_drop=0.5, thick=3)
    nose(a, '#C89478')
    # 붉은 입술
    a.add(smooth([(139, 205), (146, 202), (150, 203.5), (154, 202), (161, 205), (150, 207)]), '#A81B2E')
    a.add(smooth([(139, 205), (150, 207), (161, 205), (150, 213)]), '#C8324A')
    a.add(ellipse(170, 196, 1.7), '#5A2B22')  # 점
    # 작은 왕관: 가르마 위의 금 장식과 루비
    tiara = [(118, 84, 'c'), (126, 70, 'c'), (134, 80, 'c'), (142, 62, 'c'), (150, 50, 'c'), (158, 62, 'c'), (166, 80, 'c'), (174, 70, 'c'), (182, 84, 'c'), (150, 92)]
    a.add(smooth(tiara), linear(118, 50, 182, 92, [(0, GOLD_LIGHT), (0.6, GOLD), (1, GOLD_DARK)]))
    a.add(smooth([(150, 60), (156, 70), (150, 80), (144, 70)]), '#C8324A')
    a.each([ellipse(x, y, 2.6) for x, y in ((126, 72), (174, 72), (150, 50))], GOLD_LIGHT)
    vignette(a, '#200509')
    return a


PORTRAITS = {'duke': duke, 'assassin': assassin, 'captain': captain, 'ambassador': ambassador, 'contessa': contessa}

PREVIEW = """<!doctype html><meta charset="utf-8"><body style="margin:0;background:#1b1511;padding:16px;font:12px sans-serif;color:#cdbfa9">
<div style="display:flex;gap:14px">%s</div></body>
"""
# 큰 그림(타원으로 잘라 본다)과, 앱이 작은 카드에 쓰는 것과 같은 당겨 보기
PREVIEW_ITEM = """<div><div style="width:240px;height:320px;border-radius:50%%;overflow:hidden;border:2px solid #e6b855"><img src="%(n)s.svg" width="240" height="320"></div>
<div style="margin:10px 0 4px">%(n)s</div>
<div style="display:flex;gap:10px;align-items:flex-end">
<div style="width:124px;height:132px;overflow:hidden;border-radius:8px"><img src="%(n)s.svg" style="width:124px;height:165.3px;margin-top:-6.7px;transform:scale(1.5);transform-origin:50%% 42%%"></div>
<div style="width:31px;height:33px;overflow:hidden;border-radius:2px"><img src="%(n)s.svg" style="width:31px;height:41.3px;margin-top:-1.7px;transform:scale(1.5);transform-origin:50%% 42%%"></div>
</div></div>"""


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--out', default=os.path.join(here, '..', '..', 'app', 'src', 'main', 'res', 'drawable'), help='벡터 드로어블을 쓸 폴더')
    parser.add_argument('--preview', help='확인용 SVG 와 preview.html 을 쓸 폴더')
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    for name, draw in PORTRAITS.items():
        art = draw()
        with open(os.path.join(args.out, 'portrait_%s.xml' % name), 'w', encoding='utf-8') as f:
            f.write(art.vector_drawable())
        if args.preview:
            os.makedirs(args.preview, exist_ok=True)
            with open(os.path.join(args.preview, name + '.svg'), 'w', encoding='utf-8') as f:
                f.write(art.svg())
    if args.preview:
        with open(os.path.join(args.preview, 'preview.html'), 'w', encoding='utf-8') as f:
            f.write(PREVIEW % ''.join(PREVIEW_ITEM % {'n': name} for name in PORTRAITS))
    print('portraits:', ', '.join(PORTRAITS), '->', os.path.normpath(args.out))


if __name__ == '__main__':
    main()
