#!/usr/bin/env python3
"""Génère deux petits modèles plats, posés au sol (y = 0) :

- app/src/main/assets/models/shadow.glb : ombre floue + nappe de brume, placée sous les pieds
  de la créature pour qu'elle ait l'air posée sur le sol ;
- app/src/main/assets/models/marker.glb : cercle rouge avec une croix, utilisé pendant
  l'étalonnage du sol.

Matériaux non éclairés (KHR_materials_unlit) et transparents : le rendu ne dépend pas de la lumière.
"""
import json
import math
import struct
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "models"


def annulus(r0, r1, y=0.0, seg=48):
    """Anneau horizontal entre les rayons r0 et r1 (r0 = 0 donne un disque)."""
    pos, idx = [], []
    for i in range(seg):
        a = 2 * math.pi * i / seg
        c, s = math.cos(a), math.sin(a)
        pos += [(r0 * c, y, r0 * s), (r1 * c, y, r1 * s)]
    for i in range(seg):
        a, b = 2 * i, 2 * i + 1
        c, d = 2 * ((i + 1) % seg), 2 * ((i + 1) % seg) + 1
        idx += [a, c, b, b, c, d]
    return pos, idx


def bar(cx, cz, w, l, angle, y=0.0):
    """Rectangle horizontal (une branche de la croix)."""
    ca, sa = math.cos(angle), math.sin(angle)
    pts = []
    for dx, dz in ((-l / 2, -w / 2), (l / 2, -w / 2), (l / 2, w / 2), (-l / 2, w / 2)):
        pts.append((cx + dx * ca - dz * sa, y, cz + dx * sa + dz * ca))
    return pts, [0, 2, 1, 0, 3, 2]


def write_glb(path, parts):
    """parts : liste de (nom, positions, indices, rgba)."""
    blob = bytearray()
    views, accessors, meshes_prims, materials = [], [], [], []

    def add(data, target):
        while len(blob) % 4:
            blob.append(0)
        views.append({"buffer": 0, "byteOffset": len(blob), "byteLength": len(data), "target": target})
        blob.extend(data)
        return len(views) - 1

    for name, pos, idx, rgba in parts:
        pdata = b"".join(struct.pack("<3f", *p) for p in pos)
        ndata = b"".join(struct.pack("<3f", 0.0, 1.0, 0.0) for _ in pos)
        idata = b"".join(struct.pack("<H", i) for i in idx)
        pv, nv, iv = add(pdata, 34962), add(ndata, 34962), add(idata, 34963)
        xs, ys, zs = zip(*pos)
        accessors.append({"bufferView": pv, "componentType": 5126, "count": len(pos), "type": "VEC3",
                          "min": [min(xs), min(ys), min(zs)], "max": [max(xs), max(ys), max(zs)]})
        accessors.append({"bufferView": nv, "componentType": 5126, "count": len(pos), "type": "VEC3"})
        accessors.append({"bufferView": iv, "componentType": 5123, "count": len(idx), "type": "SCALAR"})
        materials.append({
            "name": name,
            "pbrMetallicRoughness": {"baseColorFactor": list(rgba), "metallicFactor": 0.0, "roughnessFactor": 1.0},
            "alphaMode": "BLEND",
            "doubleSided": True,
            "extensions": {"KHR_materials_unlit": {}},
        })
        a = len(accessors) - 3
        meshes_prims.append({"attributes": {"POSITION": a, "NORMAL": a + 1}, "indices": a + 2,
                             "material": len(materials) - 1})

    gltf = {
        "asset": {"version": "2.0", "generator": "make_markers.py"},
        "extensionsUsed": ["KHR_materials_unlit"],
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": path.stem, "mesh": 0}],
        "meshes": [{"primitives": meshes_prims}],
        "materials": materials,
        "accessors": accessors,
        "bufferViews": views,
        "buffers": [{"byteLength": len(blob)}],
    }
    js = json.dumps(gltf, separators=(",", ":")).encode()
    js += b" " * ((4 - len(js) % 4) % 4)
    while len(blob) % 4:
        blob.append(0)
    total = 12 + 8 + len(js) + 8 + len(blob)
    out = struct.pack("<III", 0x46546C67, 2, total)
    out += struct.pack("<II", len(js), 0x4E4F534A) + js
    out += struct.pack("<II", len(blob), 0x004E4942) + bytes(blob)
    path.write_bytes(out)
    print(f"{path.relative_to(OUT.parent.parent.parent.parent.parent)} : {len(out)} octets")


def shadow():
    parts = []
    # Nappe de brume grise, large et très légère (en dessous de l'ombre).
    rings = 7
    for i in range(rings):
        r0, r1 = 0.95 * i / rings, 0.95 * (i + 1) / rings
        a = 0.16 * (1 - i / rings) ** 1.3
        p, ix = annulus(r0, r1, y=0.004)
        parts.append((f"brume{i}", p, ix, (0.55, 0.6, 0.55, a)))
    # Ombre noire, plus petite et plus dense au centre.
    for i in range(rings):
        r0, r1 = 0.42 * i / rings, 0.42 * (i + 1) / rings
        a = 0.6 * (1 - i / rings) ** 1.5
        p, ix = annulus(r0, r1, y=0.008)
        parts.append((f"ombre{i}", p, ix, (0.0, 0.0, 0.0, a)))
    write_glb(OUT / "shadow.glb", parts)


def marker():
    red = (1.0, 0.15, 0.1, 0.95)
    parts = []
    p, ix = annulus(0.13, 0.17, y=0.01)
    parts.append(("cercle", p, ix, red))
    for k in range(2):
        p, ix = bar(0, 0, 0.025, 0.42, math.pi / 4 + k * math.pi / 2, y=0.012)
        parts.append((f"croix{k}", p, ix, red))
    write_glb(OUT / "marker.glb", parts)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    shadow()
    marker()
