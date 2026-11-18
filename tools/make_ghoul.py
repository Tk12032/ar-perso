#!/usr/bin/env python3
"""Génère app/src/main/assets/models/ghoul.glb : une ghoule maigre, voûtée, aux yeux luisants.

Modèle 100 % procédural (aucun asset externe) : des pièces rigides (formes de révolution)
articulées dans une hiérarchie de nœuds glTF, avec trois animations :
  - "Idle"   : respiration + tics nerveux de la tête et de la mâchoire
  - "Walk"   : marche raide et boiteuse
  - "Scream" : se redresse, bras tendus, mâchoire grande ouverte, tête qui tremble

Usage : python3 tools/make_ghoul.py [chemin_sortie.glb]
"""
import json
import math
import struct
import sys

import numpy as np

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/models/ghoul.glb"

# --------------------------------------------------------------------------- géométrie


def lathe(profile, seg=16, sx=1.0, sz=1.0, center=(0, 0, 0), axis="y"):
    """Surface de révolution. profile = [(h, r), ...] le long de l'axe (r=0 aux extrémités = fermé)."""
    pos, idx = [], []
    rings = len(profile)
    for h, r in profile:
        for s in range(seg):
            a = 2 * math.pi * s / seg
            u, v = math.cos(a) * r * sx, math.sin(a) * r * sz
            if axis == "y":
                p = (u, h, v)
            elif axis == "z":
                p = (u, v, h)
            else:
                p = (h, u, v)
            pos.append((p[0] + center[0], p[1] + center[1], p[2] + center[2]))
    for i in range(rings - 1):
        for s in range(seg):
            a = i * seg + s
            b = i * seg + (s + 1) % seg
            c = (i + 1) * seg + s
            d = (i + 1) * seg + (s + 1) % seg
            idx += [a, c, b, b, c, d]
    return np.array(pos, np.float32), np.array(idx, np.uint32)


def sphere(r, center, scale=(1, 1, 1), seg=12, rings=8):
    prof = [(-r * math.cos(math.pi * i / rings), r * math.sin(math.pi * i / rings)) for i in range(rings + 1)]
    p, i = lathe(prof, seg)
    p = p * np.array(scale, np.float32) + np.array(center, np.float32)
    return p, i


def cone(r, h, base, direction=-1, seg=6):
    """Petite dent pointue : base en `base`, pointe vers le bas (-1) ou le haut (+1)."""
    prof = [(0, 0.0), (0, r), (direction * h, 0.0)] if direction < 0 else [(0, 0.0), (0, r), (h, 0.0)]
    p, i = lathe(prof, seg)
    return p + np.array(base, np.float32), i


def merge(*parts):
    ps, ids, off = [], [], 0
    for p, i in parts:
        ps.append(p)
        ids.append(i + off)
        off += len(p)
    return np.concatenate(ps), np.concatenate(ids)


def normals(p, idx):
    """Normales lissées ; retourne aussi les indices réorientés vers l'extérieur."""
    tri = idx.reshape(-1, 3)
    fn = np.cross(p[tri[:, 1]] - p[tri[:, 0]], p[tri[:, 2]] - p[tri[:, 0]])
    # Orientation : on veut des faces tournées vers l'extérieur (loin du centre de la pièce).
    centroid = p.mean(axis=0)
    out = ((p[tri].mean(axis=1) - centroid) * fn).sum(axis=1)
    if (out < 0).sum() > (out > 0).sum():
        tri = tri[:, [0, 2, 1]]
        fn = -fn
    n = np.zeros_like(p)
    for k in range(3):
        np.add.at(n, tri[:, k], fn)
    ln = np.linalg.norm(n, axis=1, keepdims=True)
    ln[ln == 0] = 1
    return (n / ln).astype(np.float32), tri.reshape(-1).astype(np.uint32)


def limb(length, r_top, r_mid, r_bot, seg=10, knobby=1.0):
    """Membre maigre le long de -Y, avec articulations noueuses."""
    L = length
    prof = [
        (0.0, 0.0), (-0.01 * L, r_top * 0.8 * knobby), (-0.05 * L, r_top * knobby), (-0.15 * L, r_top * 0.8),
        (-0.5 * L, r_mid), (-0.85 * L, r_bot * 0.9), (-0.95 * L, r_bot * knobby), (-1.0 * L, 0.0),
    ]
    return lathe(prof, seg)


# --------------------------------------------------------------------------- matériaux

MATERIALS = [
    {"name": "peau", "pbrMetallicRoughness": {"baseColorFactor": [0.50, 0.53, 0.47, 1], "metallicFactor": 0.0, "roughnessFactor": 0.85}},
    {"name": "trou", "pbrMetallicRoughness": {"baseColorFactor": [0.0, 0.0, 0.0, 1], "metallicFactor": 0.0, "roughnessFactor": 1.0}},
    {"name": "yeux", "pbrMetallicRoughness": {"baseColorFactor": [1, 1, 0.9, 1], "metallicFactor": 0.0, "roughnessFactor": 0.3},
     "emissiveFactor": [1.0, 0.95, 0.75]},
    {"name": "dents", "pbrMetallicRoughness": {"baseColorFactor": [0.72, 0.66, 0.48, 1], "metallicFactor": 0.0, "roughnessFactor": 0.5}},
    {"name": "peau_sombre", "pbrMetallicRoughness": {"baseColorFactor": [0.22, 0.22, 0.19, 1], "metallicFactor": 0.0, "roughnessFactor": 0.9}},
]
SKIN, HOLE, EYES, TEETH, DARK = range(5)

# --------------------------------------------------------------------------- maillages

meshes = {}  # nom -> liste de (positions, indices, matériau)


def add_mesh(name, *prims):
    meshes[name] = prims


# Bassin et torse décharné (côtes suggérées par des anneaux)
add_mesh("pelvis", (*lathe([(-0.06, 0), (-0.05, 0.075), (0.0, 0.105), (0.06, 0.08), (0.08, 0)], 14, 1.0, 0.75), SKIN))
torso_prof = [(0.0, 0.0), (0.0, 0.07), (0.07, 0.062), (0.15, 0.058), (0.21, 0.085)]
for k in range(6):  # côtes saillantes au-dessus d'un ventre creux
    y = 0.25 + k * 0.045
    torso_prof += [(y - 0.018, 0.105 + k * 0.007), (y, 0.125 + k * 0.007)]
torso_prof += [(0.54, 0.14), (0.58, 0.135), (0.62, 0.09), (0.64, 0.0)]
add_mesh("torso", (*lathe(torso_prof, 18, 1.3, 0.72), SKIN))
add_mesh("neck", (*lathe([(0, 0), (0, 0.035), (0.07, 0.03), (0.14, 0.035), (0.15, 0)], 10), SKIN))

# Crâne allongé, orbites creuses, yeux luisants, bouche noire et dents
skull = lathe([(0, 0), (0.02, 0.055), (0.06, 0.08), (0.12, 0.09), (0.2, 0.097), (0.26, 0.085), (0.3, 0.05), (0.315, 0)], 18, 0.85, 1.15)
sockets = merge(sphere(0.042, (0.04, 0.18, 0.082), (1, 0.85, 0.7)), sphere(0.042, (-0.04, 0.18, 0.082), (1, 0.85, 0.7)))
eyes = merge(sphere(0.0065, (0.04, 0.176, 0.1)), sphere(0.0065, (-0.04, 0.176, 0.1)))
mouth = sphere(0.056, (0, 0.055, 0.068), (1.0, 0.8, 0.8))
hair = []
rng = np.random.default_rng(7)
for k in range(22):  # mèches filasses qui pendent du crâne
    a = math.radians(rng.uniform(-200, 20))  # surtout sur l'arrière et les côtés
    y0 = rng.uniform(0.2, 0.3)
    rr = 0.09 * math.sqrt(max(0.05, 1 - ((y0 - 0.2) / 0.115) ** 2))
    strand, si = limb(rng.uniform(0.22, 0.5), 0.006, 0.004, 0.002, 5)
    strand[:, 0] += strand[:, 1] * -math.cos(a) * 0.15
    strand[:, 2] += strand[:, 1] * -math.sin(a) * 0.15
    hair.append((strand + np.array([math.cos(a) * rr * 0.85, y0, math.sin(a) * rr * 1.15], np.float32), si))
hair = merge(*hair)
upper_teeth = merge(*[cone(0.007, 0.022 + 0.008 * (k % 2), (x, 0.075, 0.098 - abs(x) * 0.4), -1)
                      for k, x in enumerate(np.linspace(-0.038, 0.038, 9))])
add_mesh("head", (*skull, SKIN), (*sockets, HOLE), (*eyes, EYES), (*mouth, HOLE), (*upper_teeth, TEETH), (*hair, DARK))

jaw = sphere(0.062, (0, -0.03, 0.045), (0.9, 0.6, 1.05))
lower_teeth = merge(*[cone(0.006, 0.018 + 0.006 * (k % 2), (x, -0.005, 0.098 - abs(x) * 0.5), +1)
                      for k, x in enumerate(np.linspace(-0.034, 0.034, 8))])
add_mesh("jaw", (*jaw, SKIN), (*lower_teeth, TEETH))

add_mesh("upper_arm", (*merge(limb(0.44, 0.036, 0.024, 0.03), sphere(0.045, (0, 0, 0))), SKIN))
add_mesh("forearm", (*limb(0.42, 0.03, 0.019, 0.022), SKIN))
palm = sphere(0.04, (0, -0.04, 0), (0.9, 1.2, 0.45))
fingers = []
for k, x in enumerate([-0.03, -0.01, 0.01, 0.03]):
    f, fi = limb(0.17 + 0.02 * (1 - abs(k - 1.5) / 1.5), 0.009, 0.006, 0.004, 6)
    # doigts légèrement écartés et recourbés vers l'avant (griffes)
    ang = math.radians((k - 1.5) * 6)
    rot = np.array([[math.cos(ang), -math.sin(ang), 0], [math.sin(ang), math.cos(ang), 0], [0, 0, 1]], np.float32)
    f = f @ rot.T
    f[:, 2] += (f[:, 1] ** 2) * 2.5  # courbure
    fingers.append((f + np.array([x, -0.08, 0], np.float32), fi))
add_mesh("hand", (*merge(palm, *fingers), DARK))
add_mesh("thigh", (*merge(limb(0.48, 0.045, 0.03, 0.04), sphere(0.05, (0, 0, 0))), SKIN))
add_mesh("shin", (*limb(0.46, 0.038, 0.022, 0.028), SKIN))
add_mesh("foot", (*sphere(0.05, (0, -0.02, 0.06), (0.7, 0.4, 2.0)), DARK))

# --------------------------------------------------------------------------- hiérarchie

nodes = []  # dicts glTF
node_index = {}


def node(name, mesh=None, t=(0, 0, 0), parent=None):
    n = {"name": name, "translation": list(map(float, t))}
    if mesh:
        n["mesh"] = mesh
    node_index[name] = len(nodes)
    nodes.append(n)
    if parent is not None:
        nodes[node_index[parent]].setdefault("children", []).append(node_index[name])
    return name


node("Ghoul")
node("hips", "pelvis", (0, 1.0, 0), "Ghoul")
node("spine", "torso", (0, 0.05, 0), "hips")
node("neck", "neck", (0, 0.6, 0.02), "spine")
node("head", "head", (0, 0.14, 0), "neck")
node("jaw", "jaw", (0, 0.07, 0.0), "head")
for side, sx in (("L", 1), ("R", -1)):
    node("upper_arm" + side, "upper_arm", (0.2 * sx, 0.56, 0), "spine")
    node("forearm" + side, "forearm", (0, -0.44, 0), "upper_arm" + side)
    node("hand" + side, "hand", (0, -0.42, 0), "forearm" + side)
    node("thigh" + side, "thigh", (0.1 * sx, 0, 0), "hips")
    node("shin" + side, "shin", (0, -0.48, 0), "thigh" + side)
    node("foot" + side, "foot", (0, -0.46, 0), "shin" + side)

# Pose de base (degrés, X puis Y puis Z)
BASE = {
    "spine": (26, 0, 0), "neck": (28, 0, 0), "head": (-38, 0, 8), "jaw": (14, 0, 0),
    "upper_armL": (-12, 0, 10), "upper_armR": (-12, 0, -10),
    "forearmL": (-18, 0, 0), "forearmR": (-18, 0, 0),
    "handL": (-10, 0, 0), "handR": (-10, 0, 0),
    "thighL": (-6, 0, 2), "thighR": (-6, 0, -2), "shinL": (10, 0, 0), "shinR": (10, 0, 0),
    "footL": (-4, 0, 0), "footR": (-4, 0, 0),
}


def quat(e):
    x, y, z = (math.radians(a) / 2 for a in e)
    qx = np.array([math.sin(x), 0, 0, math.cos(x)])
    qy = np.array([0, math.sin(y), 0, math.cos(y)])
    qz = np.array([0, 0, math.sin(z), math.cos(z)])

    def mul(a, b):
        ax, ay, az, aw = a
        bx, by, bz, bw = b
        return np.array([aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx,
                         aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz])
    q = mul(qz, mul(qy, qx))  # X appliqué en premier
    return (q / np.linalg.norm(q)).tolist()


for name, e in BASE.items():
    nodes[node_index[name]]["rotation"] = quat(e)

# --------------------------------------------------------------------------- animations
# Chaque piste : nœud -> liste de (temps, (dx, dy, dz)) en degrés AJOUTÉS à la pose de base.

IDLE = {
    "spine": [(0, (0, 0, 0)), (1.5, (3, 0, 2)), (3.0, (0, 0, 0))],
    "head": [(0, (0, 0, 0)), (0.8, (0, 12, 6)), (0.86, (12, -28, 32)), (1.6, (10, -26, 28)),
             (2.2, (0, 14, -6)), (2.26, (-6, 32, -38)), (2.7, (-4, 28, -34)), (3.0, (0, 0, 0))],
    "jaw": [(0, (0, 0, 0)), (0.8, (3, 0, 0)), (0.86, (22, 0, 0)), (1.0, (6, 0, 0)),
            (2.26, (16, 0, 0)), (2.4, (2, 0, 0)), (3.0, (0, 0, 0))],
    "upper_armL": [(0, (0, 0, 0)), (1.5, (-4, 0, 3)), (3.0, (0, 0, 0))],
    "upper_armR": [(0, (0, 0, 0)), (1.2, (-3, 0, -2)), (2.26, (-14, 0, -8)), (2.5, (-5, 0, -3)), (3.0, (0, 0, 0))],
    "forearmL": [(0, (0, 0, 0)), (1.5, (-6, 0, 0)), (3.0, (0, 0, 0))],
    "handR": [(0, (0, 0, 0)), (2.26, (-25, 0, 0)), (2.5, (-5, 0, 0)), (3.0, (0, 0, 0))],
}

WALK = {
    "thighL": [(0, (-28, 0, 0)), (0.7, (18, 0, 0)), (1.4, (-28, 0, 0))],
    "thighR": [(0, (18, 0, 0)), (0.55, (-20, 0, 0)), (1.4, (18, 0, 0))],
    "shinL": [(0, (5, 0, 0)), (0.35, (0, 0, 0)), (0.9, (45, 0, 0)), (1.2, (20, 0, 0)), (1.4, (5, 0, 0))],
    "shinR": [(0, (0, 0, 0)), (0.2, (40, 0, 0)), (0.5, (15, 0, 0)), (0.9, (2, 0, 0)), (1.4, (0, 0, 0))],
    "spine": [(0, (0, 0, -6)), (0.7, (4, 0, 7)), (1.4, (0, 0, -6))],
    "head": [(0, (0, 0, 8)), (0.6, (6, 0, -6)), (0.64, (-8, 18, 20)), (1.0, (-4, 14, 14)), (1.4, (0, 0, 8))],
    "upper_armL": [(0, (14, 0, 0)), (0.7, (-14, 0, 4)), (1.4, (14, 0, 0))],
    "upper_armR": [(0, (-12, 0, 0)), (0.7, (12, 0, -4)), (1.4, (-12, 0, 0))],
    "forearmL": [(0, (0, 0, 0)), (0.7, (-12, 0, 0)), (1.4, (0, 0, 0))],
    "jaw": [(0, (0, 0, 0)), (0.64, (14, 0, 0)), (0.9, (4, 0, 0)), (1.4, (0, 0, 0))],
}
WALK_HIPS_T = [(0, (0, 1.0, 0)), (0.35, (0, 0.96, 0)), (0.7, (0, 1.0, 0)), (1.05, (0, 0.97, 0)), (1.4, (0, 1.0, 0))]

shake = [(0, (0, 0, 0)), (0.15, (-28, 0, 0))]
t, s = 0.2, 1
while t < 1.15:
    shake.append((t, (-28, 26 * s, 10 * s)))
    t += 0.055
    s = -s
shake.append((1.2, (-26, 0, 0)))
SCREAM = {
    "head": shake,
    "jaw": [(0, (0, 0, 0)), (0.15, (48, 0, 0)), (1.0, (52, 0, 0)), (1.2, (45, 0, 0))],
    "spine": [(0, (0, 0, 0)), (0.15, (-18, 0, 0)), (1.2, (-12, 0, 0))],
    "neck": [(0, (0, 0, 0)), (0.15, (-10, 0, 0)), (1.2, (-10, 0, 0))],
    "upper_armL": [(0, (0, 0, 0)), (0.15, (-85, 0, 15)), (1.2, (-80, 0, 12))],
    "upper_armR": [(0, (0, 0, 0)), (0.15, (-85, 0, -15)), (1.2, (-80, 0, -12))],
    "forearmL": [(0, (0, 0, 0)), (0.15, (10, 0, 0)), (1.2, (8, 0, 0))],
    "forearmR": [(0, (0, 0, 0)), (0.15, (10, 0, 0)), (1.2, (8, 0, 0))],
    "handL": [(0, (0, 0, 0)), (0.15, (-40, 0, 0)), (1.2, (-35, 0, 0))],
    "handR": [(0, (0, 0, 0)), (0.15, (-40, 0, 0)), (1.2, (-35, 0, 0))],
}

# --------------------------------------------------------------------------- écriture glTF

buf = bytearray()
accessors, views = [], []


def add_view(data: bytes, target=None):
    while len(buf) % 4:
        buf.append(0)
    v = {"buffer": 0, "byteOffset": len(buf), "byteLength": len(data)}
    if target:
        v["target"] = target
    buf.extend(data)
    views.append(v)
    return len(views) - 1


def add_accessor(arr, comp, typ, target=None, minmax=False):
    view = add_view(arr.tobytes(), target)
    a = {"bufferView": view, "componentType": comp, "count": int(arr.shape[0]), "type": typ}
    if minmax:
        a["min"] = arr.min(axis=0).astype(float).tolist() if arr.ndim > 1 else [float(arr.min())]
        a["max"] = arr.max(axis=0).astype(float).tolist() if arr.ndim > 1 else [float(arr.max())]
    accessors.append(a)
    return len(accessors) - 1


gl_meshes, mesh_index = [], {}
for name, prims in meshes.items():
    out = []
    for p, i, mat in prims:
        n, i = normals(p, i)
        pa = add_accessor(p.astype(np.float32), 5126, "VEC3", 34962, True)
        na = add_accessor(n, 5126, "VEC3", 34962)
        attrs = {"POSITION": pa, "NORMAL": na}
        if mat in (SKIN, DARK):  # peau marbrée : taches sombres, veines
            x, y, z = p[:, 0] * 40, p[:, 1] * 40, p[:, 2] * 40
            m = np.sin(x * 1.3 + y * 0.7) * np.sin(y * 1.1 - z * 1.7) * np.sin(z * 0.9 + x * 2.1)
            c = np.clip(0.8 + 0.35 * m, 0.4, 1.0)
            col = np.stack([c * 1.0, c * 0.97, c * 0.9, np.ones_like(c)], axis=1).astype(np.float32)
            attrs["COLOR_0"] = add_accessor(col, 5126, "VEC4", 34962)
        ia = add_accessor(i.astype(np.uint16), 5123, "SCALAR", 34963)
        out.append({"attributes": attrs, "indices": ia, "material": mat})
    mesh_index[name] = len(gl_meshes)
    gl_meshes.append({"name": name, "primitives": out})
for n in nodes:
    if "mesh" in n:
        n["mesh"] = mesh_index[n["mesh"]]


def add_animation(name, tracks, hips_t=None):
    samplers, channels = [], []
    for node_name, keys in tracks.items():
        base = BASE.get(node_name, (0, 0, 0))
        times = np.array([k[0] for k in keys], np.float32)
        rots = np.array([quat(tuple(b + d for b, d in zip(base, k[1]))) for k in keys], np.float32)
        ti = add_accessor(times, 5126, "SCALAR", minmax=True)
        ro = add_accessor(rots, 5126, "VEC4")
        samplers.append({"input": ti, "output": ro, "interpolation": "LINEAR"})
        channels.append({"sampler": len(samplers) - 1, "target": {"node": node_index[node_name], "path": "rotation"}})
    if hips_t:
        times = np.array([k[0] for k in hips_t], np.float32)
        tr = np.array([k[1] for k in hips_t], np.float32)
        samplers.append({"input": add_accessor(times, 5126, "SCALAR", minmax=True),
                         "output": add_accessor(tr, 5126, "VEC3"), "interpolation": "LINEAR"})
        channels.append({"sampler": len(samplers) - 1, "target": {"node": node_index["hips"], "path": "translation"}})
    return {"name": name, "samplers": samplers, "channels": channels}


animations = [add_animation("Idle", IDLE), add_animation("Walk", WALK, WALK_HIPS_T), add_animation("Scream", SCREAM)]

while len(buf) % 4:
    buf.append(0)
gltf = {
    "asset": {"version": "2.0", "generator": "ar-perso make_ghoul.py"},
    "scene": 0,
    "scenes": [{"nodes": [0]}],
    "nodes": nodes,
    "meshes": gl_meshes,
    "materials": MATERIALS,
    "accessors": accessors,
    "bufferViews": views,
    "buffers": [{"byteLength": len(buf)}],
    "animations": animations,
}
js = json.dumps(gltf, separators=(",", ":")).encode()
while len(js) % 4:
    js += b" "
glb = struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(buf))
glb += struct.pack("<II", len(js), 0x4E4F534A) + js
glb += struct.pack("<II", len(buf), 0x004E4942) + bytes(buf)
with open(OUT, "wb") as f:
    f.write(glb)
print(f"{OUT}: {len(glb) / 1024:.0f} Ko, {len(nodes)} nœuds, {len(gl_meshes)} maillages, {len(animations)} animations")
