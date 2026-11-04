#!/usr/bin/env python3
# Drift launcher packager. Copyright (c) 2026 Babar Manzoor. All rights reserved.
"""
Drift launcher packager. Builds the APK without Gradle/aapt:
  - encodes AndroidManifest.xml to Android binary XML
  - writes a minimal resources.arsc (just the launcher icon)
  - draws the icon PNG
  - zips everything with 4-byte alignment for stored entries
Usage: build.py <classes.dex> <out-unsigned.apk>
"""
import struct, sys, io, zipfile, math

PKG = "com.drift.launcher"
ANDROID_NS = "http://schemas.android.com/apk/res/android"

# android:attr ids (from android.R$attr, API 23)
ATTR = {
    "theme": 0x01010000, "label": 0x01010001, "icon": 0x01010002, "name": 0x01010003,
    "permission": 0x01010006, "exported": 0x01010010, "launchMode": 0x0101001d,
    "screenOrientation": 0x0101001e, "configChanges": 0x0101001f, "stateNotNeeded": 0x01010016,
    "clearTaskOnLaunch": 0x01010015, "minSdkVersion": 0x0101020c, "versionCode": 0x0101021b,
    "versionName": 0x0101021c, "targetSdkVersion": 0x01010270, "allowBackup": 0x01010280,
    "hardwareAccelerated": 0x010102d3, "required": 0x0101028e, "authorities": 0x01010018, "grantUriPermissions": 0x0101001b, "excludeFromRecents": 0x01010017,
}
T_REF, T_STR, T_DEC, T_HEX, T_BOOL = 0x01, 0x03, 0x10, 0x11, 0x12
ICON_ID = 0x7F010000
THEME_MATERIAL_NOACTIONBAR = 0x0103022E

def A(name, typ, val):  # android-namespaced attribute
    return ("android", name, typ, val)

def P(name, typ, val):  # attribute without namespace
    return (None, name, typ, val)

MANIFEST = ("manifest", [P("package", T_STR, PKG), A("versionCode", T_DEC, 18), A("versionName", T_STR, "1.1.0")], [
    ("uses-sdk", [A("minSdkVersion", T_DEC, 23), A("targetSdkVersion", T_DEC, 25)], []),
    ("uses-permission", [A("name", T_STR, "android.permission.BLUETOOTH")], []),
    ("uses-permission", [A("name", T_STR, "android.permission.ACCESS_NETWORK_STATE")], []),
    ("uses-permission", [A("name", T_STR, "android.permission.ACCESS_FINE_LOCATION")], []),
    ("uses-permission", [A("name", T_STR, "android.permission.ACCESS_COARSE_LOCATION")], []),
    ("uses-permission", [A("name", T_STR, "android.permission.READ_EXTERNAL_STORAGE")], []),
    ("uses-permission", [A("name", T_STR, "android.permission.INTERNET")], []),
    ("uses-permission", [A("name", T_STR, "android.permission.WAKE_LOCK")], []),
    ("uses-feature", [A("name", T_STR, "android.hardware.location.gps"), A("required", T_BOOL, False)], []),
    ("application", [
        A("theme", T_REF, THEME_MATERIAL_NOACTIONBAR), A("label", T_STR, "Drift"), A("icon", T_REF, ICON_ID),
        A("allowBackup", T_BOOL, False), A("hardwareAccelerated", T_BOOL, True)], [
        ("activity", [
            A("label", T_STR, "Drift"), A("name", T_STR, PKG + ".Launcher"), A("exported", T_BOOL, True),
            A("launchMode", T_DEC, 2), A("screenOrientation", T_DEC, 0), A("configChanges", T_HEX, 0x40001FF8),
            A("stateNotNeeded", T_BOOL, True), A("clearTaskOnLaunch", T_BOOL, True)], [
            ("intent-filter", [], [
                ("action", [A("name", T_STR, "android.intent.action.MAIN")], []),
                ("category", [A("name", T_STR, "android.intent.category.HOME")], []),
                ("category", [A("name", T_STR, "android.intent.category.DEFAULT")], []),
                ("category", [A("name", T_STR, "android.intent.category.LAUNCHER")], []),
            ]),
        ]),
        ("service", [
            A("label", T_STR, "Drift music info"), A("name", T_STR, PKG + ".MediaWatcher"),
            A("permission", T_STR, "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
            A("exported", T_BOOL, True)], [
            ("intent-filter", [], [
                ("action", [A("name", T_STR, "android.service.notification.NotificationListenerService")], []),
            ]),
        ]),
        ("activity", [
            A("label", T_STR, "Drift Music"), A("name", T_STR, PKG + ".MusicActivity"), A("exported", T_BOOL, True),
            A("theme", T_REF, 0x0103000F), A("excludeFromRecents", T_BOOL, True)], []),
        ("service", [A("name", T_STR, PKG + ".MusicService"), A("exported", T_BOOL, False)], []),
        ("provider", [
            A("name", T_STR, PKG + ".ApkProvider"), A("authorities", T_STR, PKG + ".update"),
            A("exported", T_BOOL, False), A("grantUriPermissions", T_BOOL, True)], []),
        ("service", [
            A("label", T_STR, "Drift recent apps button"), A("name", T_STR, PKG + ".RecentsService"),
            A("permission", T_STR, "android.permission.BIND_ACCESSIBILITY_SERVICE"),
            A("exported", T_BOOL, True)], [
            ("intent-filter", [], [
                ("action", [A("name", T_STR, "android.accessibilityservice.AccessibilityService")], []),
            ]),
        ]),
    ]),
])

# ---------------------------------------------------------------- string pools
def string_pool(strings, utf8=True):
    data = b""
    offsets = []
    for s in strings:
        offsets.append(len(data))
        if utf8:
            b = s.encode("utf-8")
            def ln(n):
                return bytes([n]) if n < 0x80 else bytes([(n >> 8) | 0x80, n & 0xFF])
            data += ln(len(s)) + ln(len(b)) + b + b"\x00"
        else:
            u = s.encode("utf-16-le")
            n = len(u) // 2
            data += (struct.pack("<H", n) if n < 0x8000 else struct.pack("<HH", (n >> 16) | 0x8000, n & 0xFFFF)) + u + b"\x00\x00"
    while len(data) % 4:
        data += b"\x00"
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(data)
    flags = 0x100 if utf8 else 0
    hdr = struct.pack("<HHIIIIII", 0x0001, header_size, size, len(strings), 0, flags, strings_start, 0)
    return hdr + b"".join(struct.pack("<I", o) for o in offsets) + data

# ---------------------------------------------------------------- binary XML
def encode_axml(root):
    # collect strings: android attribute names first (they need the resource map), then the rest
    attr_names, others = [], []
    def walk(node):
        tag, attrs, kids = node
        for ns, name, typ, val in attrs:
            if ns == "android":
                if name not in attr_names: attr_names.append(name)
            elif name not in others: others.append(name)
            if typ == T_STR and val not in others: others.append(val)
        if tag not in others: others.append(tag)
        for k in kids: walk(k)
    walk(root)
    for s in ("android", ANDROID_NS):
        if s not in others: others.append(s)
    others = [s for s in others if s not in attr_names]
    strings = attr_names + others
    idx = {s: i for i, s in enumerate(strings)}

    pool = string_pool(strings, utf8=True)
    resmap = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(attr_names)) + b"".join(struct.pack("<I", ATTR[n]) for n in attr_names)

    body = b""
    NONE = 0xFFFFFFFF
    body += struct.pack("<HHIII", 0x0100, 16, 24, 1, NONE) + struct.pack("<II", idx["android"], idx[ANDROID_NS])

    def elem(node, line=[2]):
        nonlocal body
        tag, attrs, kids = node
        def sort_key(a):
            ns, name, _, _ = a
            return (0, ATTR[name]) if ns == "android" else (1, 0)
        attrs = sorted(attrs, key=sort_key)
        ab = b""
        for ns, name, typ, val in attrs:
            ns_i = idx[ANDROID_NS] if ns == "android" else NONE
            if typ == T_STR:
                raw, data = idx[val], idx[val]
            elif typ == T_BOOL:
                raw, data = NONE, (0xFFFFFFFF if val else 0)
            else:
                raw, data = NONE, val
            ab += struct.pack("<III", ns_i, idx[name], raw) + struct.pack("<HBBI", 8, 0, typ, data)
        size = 16 + 20 + len(ab)
        body += struct.pack("<HHIII", 0x0102, 16, size, line[0], NONE)
        body += struct.pack("<IIHHHHHH", NONE, idx[tag], 20, 20, len(attrs), 0, 0, 0) + ab
        line[0] += 1
        for k in kids: elem(k)
        body += struct.pack("<HHIII", 0x0103, 16, 24, line[0], NONE) + struct.pack("<II", NONE, idx[tag])
        line[0] += 1

    elem(root)
    body += struct.pack("<HHIII", 0x0101, 16, 24, 99, NONE) + struct.pack("<II", idx["android"], idx[ANDROID_NS])
    total = 8 + len(pool) + len(resmap) + len(body)
    return struct.pack("<HHI", 0x0003, 8, total) + pool + resmap + body

# ---------------------------------------------------------------- resources.arsc
ICON_PATH = "res/drawable-xxxhdpi-v4/ic_launcher.png"

def encode_arsc():
    gpool = string_pool([ICON_PATH], utf8=True)
    type_pool = string_pool(["drawable"], utf8=False)
    key_pool = string_pool(["ic_launcher"], utf8=False)

    # typeSpec: one entry that varies by density
    spec = struct.pack("<HHIBBHI", 0x0202, 16, 16 + 4, 1, 0, 0, 1) + struct.pack("<I", 0x0100)

    # ResTable_config (64 bytes): density 640 (xxxhdpi), sdkVersion 4
    cfg = bytearray(64)
    struct.pack_into("<I", cfg, 0, 64)
    struct.pack_into("<H", cfg, 14, 640)
    struct.pack_into("<H", cfg, 24, 4)
    header_size = 20 + 64
    entries_start = header_size + 4
    entry = struct.pack("<HHI", 8, 0, 0) + struct.pack("<HBBI", 8, 0, T_STR, 0)
    tsize = entries_start + len(entry)
    typ = struct.pack("<HHIBBHII", 0x0201, header_size, tsize, 1, 0, 0, 1, entries_start) + bytes(cfg) + struct.pack("<I", 0) + entry

    pkg_header = 288
    name = PKG.encode("utf-16-le").ljust(256, b"\x00")
    type_off = pkg_header
    key_off = type_off + len(type_pool)
    pkg_size = pkg_header + len(type_pool) + len(key_pool) + len(spec) + len(typ)
    pkg = struct.pack("<HHII", 0x0200, pkg_header, pkg_size, 0x7F) + name + struct.pack("<IIIII", type_off, 1, key_off, 1, 0)
    assert len(pkg) == pkg_header
    pkg += type_pool + key_pool + spec + typ
    total = 12 + len(gpool) + len(pkg)
    return struct.pack("<HHII", 0x0002, 12, total, 1) + gpool + pkg

# ---------------------------------------------------------------- icon
def make_icon(size=192):
    from PIL import Image, ImageDraw, ImageFilter
    import os
    srcp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon_source.png")
    if os.path.isfile(srcp):  # the app icon artwork (rounded square, transparent corners)
        final = Image.open(srcp).convert("RGBA").resize((size, size), Image.LANCZOS)
        out = io.BytesIO()
        final.save(out, "PNG", optimize=True)
        return out.getvalue(), final
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # dark squircle base
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([S*0.04, S*0.04, S*0.96, S*0.96], radius=S*0.26, fill=255)
    base = Image.new("RGBA", (S, S))
    px = base.load()
    for y in range(S):
        t = y / S
        r = int(10 + 10 * t); g = int(10 + 5 * t); b = int(24 + 22 * t)
        for x in range(S):
            px[x, y] = (r, g, b, 255)
    img.paste(base, (0, 0), mask)
    # neon arc: magenta -> cyan, drawn as many short segments
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy, R, W = S * 0.5, S * 0.5, S * 0.28, S * 0.085
    steps = 220
    a0, a1 = 140, 140 + 300
    for i in range(steps):
        t = i / (steps - 1)
        a = math.radians(a0 + (a1 - a0) * t)
        c = (int(255 * (1 - t) + 0 * t), int(46 * (1 - t) + 229 * t), int(147 * (1 - t) + 255 * t), 255)
        x, y = cx + R * math.cos(a), cy + R * math.sin(a)
        gd.ellipse([x - W / 2, y - W / 2, x + W / 2, y + W / 2], fill=c)
    blur = glow.filter(ImageFilter.GaussianBlur(S * 0.04))
    img = Image.alpha_composite(img, blur)
    img = Image.alpha_composite(img, glow)
    # spark dot at the arc head
    sd = ImageDraw.Draw(img)
    a = math.radians(a1)
    x, y = cx + R * math.cos(a), cy + R * math.sin(a)
    sd.ellipse([x - W * 0.32, y - W * 0.32, x + W * 0.32, y + W * 0.32], fill=(255, 255, 255, 255))
    # clip glow to squircle
    final = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    final.paste(img, (0, 0), mask)
    final = final.resize((size, size), Image.LANCZOS)
    out = io.BytesIO()
    final.save(out, "PNG", optimize=True)
    return out.getvalue(), final

# ---------------------------------------------------------------- aligned zip
def write_apk(path, files):
    """files: list of (name, bytes, compress). Stored entries get 4-byte aligned data."""
    with open(path, "wb") as fh:
        zf = zipfile.ZipFile(fh, "w")
        for name, data, compress in files:
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.create_system = 0
            zi.external_attr = 0
            if compress:
                zi.compress_type = zipfile.ZIP_DEFLATED
            else:
                zi.compress_type = zipfile.ZIP_STORED
                offset = fh.tell()
                base = offset + 30 + len(name.encode())
                pad = (4 - base % 4) % 4
                zi.extra = b"\x00" * pad
            zf.writestr(zi, data)
        zf.close()

if __name__ == "__main__":
    dex, out = sys.argv[1], sys.argv[2]
    icon_png, icon_img = make_icon(192)
    if len(sys.argv) > 3:
        icon_img.save(sys.argv[3])
    files = [
        ("AndroidManifest.xml", encode_axml(MANIFEST), True),
        ("classes.dex", open(dex, "rb").read(), True),
        ("resources.arsc", encode_arsc(), False),
        (ICON_PATH, icon_png, False),
    ]
    import os
    here = os.path.dirname(os.path.abspath(__file__))
    adir = os.path.join(here, "..", "assets")
    if os.path.isdir(adir):
        for name in sorted(os.listdir(adir)):
            p = os.path.join(adir, name)
            if os.path.isfile(p) and name != "car.png":  # car.png is a leftover from 1.0, no longer used
                # fonts and images are already compressed; store them so they can be read directly
                files.append(("assets/" + name, open(p, "rb").read(), False))
    write_apk(out, files)
    print("wrote", out)
