"""Serveur Found Footage : zones d'apparition, chasses, carnets, classement, commandes de test.

Une seule base SQLite (fichier dans /data), aucune dépendance externe à part FastAPI.
"""
import hashlib
import json
import math
import os
import secrets
import sqlite3
import time
import urllib.parse
import urllib.request
from contextlib import contextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

DB_PATH = os.environ.get("FF_DB", "/data/foundfootage.db")
ADMIN_TOKEN = os.environ.get("FF_ADMIN_TOKEN", "")
OVERPASS_URL = os.environ.get("FF_OVERPASS", "https://overpass-api.de/api/interpreter")
STATIC = Path(__file__).parent / "static"

# Les créatures du MVP : trois ghoules de couleurs différentes, 3 indices chacune.
EVIDENCES = ["camera", "emf5", "light", "voice", "cold", "steps"]
# behavior : comportement quand un joueur est dans la zone (la créature ne sort jamais de sa zone).
#   mode "keep"  : garde ses distances (keep_min..keep_max m), ne s'approche que dans le dos du joueur ;
#   mode "hunt"  : fonce sur le joueur ;
#   mode "stalk" : avance lentement vers le joueur, sans jamais s'arrêter.
# speed = vitesse max (m/s) ; wander = vitesse d'errance ; jump = « sauts » vers le joueur dans les glitchs.
CREATURES = {
    "pale": {"name": "Ghoule pâle", "color": "#9fb59a", "evidences": ["camera", "emf5", "steps"],
             "behavior": {"mode": "keep", "speed": 0.7, "wander": 0.3, "keep_min": 8, "keep_max": 12, "jump": False}},
    "red": {"name": "Ghoule rouge", "color": "#8a1c1c", "evidences": ["emf5", "voice", "light"],
            "behavior": {"mode": "hunt", "speed": 1.6, "wander": 0.4, "keep_min": 0, "keep_max": 0, "jump": True}},
    "black": {"name": "Ghoule noire", "color": "#1d2433", "evidences": ["cold", "voice", "camera"],
              "behavior": {"mode": "stalk", "speed": 0.5, "wander": 0.25, "keep_min": 0, "keep_max": 0, "jump": True}},
}
ZONE_KINDS = ["cemetery", "forest", "ruin", "test", "other"]

app = FastAPI(title="Found Footage")


# ---------------------------------------------------------------- base de données

@contextmanager
def db():
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    try:
        yield con
        con.commit()
    finally:
        con.close()


def init_db():
    Path(DB_PATH).parent.mkdir(parents=True, exist_ok=True)
    with db() as con:
        con.executescript(
            """
            CREATE TABLE IF NOT EXISTS zones (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                radius_m REAL NOT NULL DEFAULT 60,
                active INTEGER NOT NULL DEFAULT 1,
                osm_id TEXT UNIQUE,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS zones_lat ON zones(lat);
            CREATE TABLE IF NOT EXISTS players (
                id INTEGER PRIMARY KEY,
                pseudo TEXT NOT NULL UNIQUE COLLATE NOCASE,
                token TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS hunts (
                id INTEGER PRIMARY KEY,
                player_id INTEGER NOT NULL REFERENCES players(id),
                zone_id INTEGER NOT NULL REFERENCES zones(id),
                creature TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                guess TEXT,
                evidences TEXT,
                survived INTEGER,
                score INTEGER
            );
            CREATE TABLE IF NOT EXISTS commands (
                id INTEGER PRIMARY KEY,
                player_id INTEGER NOT NULL REFERENCES players(id),
                kind TEXT NOT NULL,
                payload TEXT NOT NULL DEFAULT '{}',
                created_at INTEGER NOT NULL,
                delivered_at INTEGER
            );
            """
        )
        # Colonnes ajoutées après la première version (bases existantes).
        cols = {r[1] for r in con.execute("PRAGMA table_info(zones)")}
        if "creature" not in cols:
            con.execute("ALTER TABLE zones ADD COLUMN creature TEXT")  # NULL = au hasard
        if "spawn_chance" not in cols:
            con.execute("ALTER TABLE zones ADD COLUMN spawn_chance REAL NOT NULL DEFAULT 1.0")
        if "polygon" not in cols:
            con.execute("ALTER TABLE zones ADD COLUMN polygon TEXT")  # JSON [[lat, lon], ...] ou NULL = cercle
        if "legend" not in cols:
            con.execute("ALTER TABLE zones ADD COLUMN legend TEXT")
        # Zone cachée qui sert aux chasses lancées depuis le menu test de l'appli.
        con.execute(
            "INSERT OR IGNORE INTO zones (name, kind, lat, lon, radius_m, active, osm_id, created_at) "
            "VALUES ('Zone de test (téléphone)', 'test', 0, 0, 60, 0, 'local-test', ?)",
            (now(),),
        )


def test_zone_id() -> int:
    with db() as con:
        return con.execute("SELECT id FROM zones WHERE osm_id = 'local-test'").fetchone()[0]


@app.on_event("startup")
def _startup():
    init_db()


def now() -> int:
    return int(time.time())


def haversine_m(lat1, lon1, lat2, lon2) -> float:
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def zone_dict(row, lat=None, lon=None):
    d = {k: row[k] for k in ("id", "name", "kind", "lat", "lon", "radius_m", "creature", "spawn_chance", "legend")}
    d["polygon"] = json.loads(row["polygon"]) if row["polygon"] else None
    d["active"] = bool(row["active"])
    if lat is not None:
        d["distance_m"] = round(haversine_m(lat, lon, row["lat"], row["lon"]))
    return d


def point_in_polygon(lat: float, lon: float, poly) -> bool:
    """Test pair-impair classique (assez précis à l'échelle d'une zone)."""
    inside = False
    j = len(poly) - 1
    for i in range(len(poly)):
        yi, xi = poly[i]
        yj, xj = poly[j]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def polygon_anchor(poly):
    """Point affiché dans l'appli et rayon englobant d'une zone dessinée.

    Le point est le centre de gravité s'il tombe dans la forme, sinon le point intérieur
    le plus central trouvé sur une grille (formes en croissant, en L…).
    """
    n = len(poly)
    area = cy = cx = 0.0
    for i in range(n):
        y0, x0 = poly[i]
        y1, x1 = poly[(i + 1) % n]
        c = x0 * y1 - x1 * y0
        area += c
        cx += (x0 + x1) * c
        cy += (y0 + y1) * c
    if abs(area) > 1e-15:
        lat, lon = cy / (3 * area), cx / (3 * area)
    else:
        lat, lon = sum(p[0] for p in poly) / n, sum(p[1] for p in poly) / n
    if not point_in_polygon(lat, lon, poly):
        ys, xs = [p[0] for p in poly], [p[1] for p in poly]
        best = None
        for i in range(1, 30):
            for j in range(1, 30):
                py = min(ys) + (max(ys) - min(ys)) * i / 30
                px = min(xs) + (max(xs) - min(xs)) * j / 30
                if point_in_polygon(py, px, poly):
                    d = (py - lat) ** 2 + (px - lon) ** 2
                    if best is None or d < best[0]:
                        best = (d, py, px)
        if best:
            lat, lon = best[1], best[2]
    radius = max(haversine_m(lat, lon, p[0], p[1]) for p in poly)
    return lat, lon, max(radius, 10.0)


def inside_zone(zone, lat: float, lon: float, margin_m: float = 0) -> bool:
    if zone["polygon"]:
        poly = json.loads(zone["polygon"])
        if point_in_polygon(lat, lon, poly):
            return True
        return margin_m > 0 and haversine_m(lat, lon, zone["lat"], zone["lon"]) <= zone["radius_m"] + margin_m
    return haversine_m(lat, lon, zone["lat"], zone["lon"]) <= zone["radius_m"] + margin_m


def _hash01(*parts) -> float:
    h = hashlib.sha256(":".join(str(p) for p in parts).encode()).digest()
    return int.from_bytes(h[:6], "big") / float(1 << 48)


def encounter_for(zone, at: int, player=None):
    """Ce qui se cache dans une zone : la présence et le type changent toutes les heures (identiques pour
    tous les joueurs), le point de départ est tiré au hasard à chaque mission.

    Renvoie None si la zone est vide cette heure-ci, sinon (créature, latitude, longitude).
    """
    slot = at // 3600
    if _hash01(zone["id"], slot, "presence") >= zone["spawn_chance"]:
        return None
    keys = sorted(CREATURES)
    creature = zone["creature"] if zone["creature"] in CREATURES else keys[int(_hash01(zone["id"], slot, "kind") * len(keys))]
    lat, lon = random_point_in_zone(zone, avoid=player)
    return creature, lat, lon


_rng = secrets.SystemRandom()


def random_point_in_zone(zone, avoid=None, min_dist_m=20.0):
    """Point de départ de la créature, tiré au hasard dans la zone à chaque mission.

    [avoid] = (lat, lon) du joueur : on prend un point à au moins [min_dist_m] de lui, ou à défaut
    (petite zone) le plus éloigné parmi les tirages.
    """
    if avoid is None:
        return _random_point_in_zone(zone)
    best = None
    for _ in range(80):
        lat, lon = _random_point_in_zone(zone)
        d = haversine_m(avoid[0], avoid[1], lat, lon)
        if d >= min_dist_m:
            return lat, lon
        if best is None or d > best[0]:
            best = (d, lat, lon)
    return best[1], best[2]


def _random_point_in_zone(zone):
    if zone["polygon"]:
        poly = json.loads(zone["polygon"])
        ys, xs = [p[0] for p in poly], [p[1] for p in poly]
        for _ in range(500):
            lat, lon = _rng.uniform(min(ys), max(ys)), _rng.uniform(min(xs), max(xs))
            if point_in_polygon(lat, lon, poly):
                return lat, lon
        return zone["lat"], zone["lon"]
    # Jusqu'à 80 % du rayon, réparti uniformément sur le disque.
    r = zone["radius_m"] * 0.8 * math.sqrt(_rng.random())
    a = 2 * math.pi * _rng.random()
    lat = zone["lat"] + (r * math.cos(a)) / 111_320.0
    lon = zone["lon"] + (r * math.sin(a)) / (111_320.0 * max(math.cos(math.radians(zone["lat"])), 0.1))
    return lat, lon


# ---------------------------------------------------------------- authentification

def current_player(authorization: str = Header(default="")):
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(401, "Jeton joueur manquant")
    with db() as con:
        row = con.execute("SELECT * FROM players WHERE token = ?", (token,)).fetchone()
    if not row:
        raise HTTPException(401, "Jeton joueur inconnu")
    return row


def require_admin(authorization: str = Header(default="")):
    token = authorization.removeprefix("Bearer ").strip()
    if not ADMIN_TOKEN or not secrets.compare_digest(token, ADMIN_TOKEN):
        raise HTTPException(401, "Accès admin refusé")


# ---------------------------------------------------------------- API publique

@app.get("/api/health")
def health():
    with db() as con:
        zones = con.execute("SELECT COUNT(*) FROM zones WHERE active = 1").fetchone()[0]
    return {"ok": True, "zones": zones, "test_zone_id": test_zone_id()}


@app.get("/api/creatures")
def creatures():
    return {"evidences": EVIDENCES, "creatures": CREATURES}


@app.get("/api/zones")
def nearby_zones(lat: float, lon: float, limit: int = Query(20, le=100), within_km: float = Query(50, le=500)):
    # Préfiltre grossier sur un carré, puis vraie distance.
    dlat = within_km / 111.0
    dlon = within_km / (111.0 * max(math.cos(math.radians(lat)), 0.1))
    with db() as con:
        rows = con.execute(
            "SELECT * FROM zones WHERE active = 1 AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            (lat - dlat, lat + dlat, lon - dlon, lon + dlon),
        ).fetchall()
    zones = sorted((zone_dict(r, lat, lon) for r in rows), key=lambda z: z["distance_m"])
    return {"zones": [z for z in zones if z["distance_m"] <= within_km * 1000][:limit]}


class NewPlayer(BaseModel):
    pseudo: str = Field(min_length=2, max_length=24, pattern=r"^[\w .\-]+$")


@app.post("/api/players")
def create_player(body: NewPlayer):
    token = secrets.token_urlsafe(24)
    try:
        with db() as con:
            cur = con.execute(
                "INSERT INTO players (pseudo, token, created_at) VALUES (?, ?, ?)",
                (body.pseudo.strip(), token, now()),
            )
            pid = cur.lastrowid
    except sqlite3.IntegrityError:
        raise HTTPException(409, "Ce pseudo est déjà pris")
    return {"id": pid, "pseudo": body.pseudo.strip(), "token": token}


@app.get("/api/me")
def me(player=Depends(current_player)):
    with db() as con:
        s = con.execute(
            "SELECT COALESCE(SUM(score), 0) AS total, COUNT(score) AS hunts FROM hunts WHERE player_id = ?",
            (player["id"],),
        ).fetchone()
    return {"id": player["id"], "pseudo": player["pseudo"], "total": s["total"], "hunts": s["hunts"]}


class HuntStart(BaseModel):
    zone_id: int
    lat: float | None = None
    lon: float | None = None
    test: bool = False


@app.post("/api/hunts")
def start_hunt(body: HuntStart, player=Depends(current_player)):
    with db() as con:
        if body.test and body.zone_id == 0:
            body.zone_id = test_zone_id()
        zone = con.execute(
            "SELECT * FROM zones WHERE id = ? AND (active = 1 OR osm_id = 'local-test')", (body.zone_id,)
        ).fetchone()
        if not zone:
            raise HTTPException(404, "Zone inconnue")
        if not body.test and body.lat is not None and body.lon is not None:
            if not inside_zone(zone, body.lat, body.lon, margin_m=50):
                d = haversine_m(body.lat, body.lon, zone["lat"], zone["lon"])
                raise HTTPException(403, f"Tu n'es pas dans la zone ({round(d)} m)")
        if body.test:
            # Chasse de test : toujours une créature ; la cachette est choisie par le téléphone.
            enc = (zone["creature"] if zone["creature"] in CREATURES else random_creature(), None, None)
        else:
            player_pos = (body.lat, body.lon) if body.lat is not None and body.lon is not None else None
            enc = encounter_for(zone, now(), player_pos)
        if enc is None:
            return {"present": False, "zone": zone_dict(zone)}
        creature, lair_lat, lair_lon = enc
        cur = con.execute(
            "INSERT INTO hunts (player_id, zone_id, creature, started_at) VALUES (?, ?, ?, ?)",
            (player["id"], zone["id"], creature, now()),
        )
    return {"present": True, "hunt_id": cur.lastrowid, "zone": zone_dict(zone), "creature": creature,
            "color": CREATURES[creature]["color"], "evidences": CREATURES[creature]["evidences"],
            "behavior": CREATURES[creature]["behavior"],
            "lair": {"lat": lair_lat, "lon": lair_lon} if lair_lat is not None else None}


def random_creature() -> str:
    return secrets.choice(sorted(CREATURES))


class Notebook(BaseModel):
    guess: str
    evidences: list[str] = []
    survived: bool = True


def score_notebook(creature: str, guess: str, evidences: list[str], survived: bool, seconds: int) -> int:
    truth = set(CREATURES[creature]["evidences"])
    marked = set(e for e in evidences if e in EVIDENCES)
    score = 100 if guess == creature else 0
    score += 20 * len(marked & truth) - 15 * len(marked - truth)
    if survived:
        score += 30
    if guess == creature and seconds < 600:
        score += round(30 * (1 - seconds / 600))  # bonus de rapidité
    return max(score, 0)


@app.post("/api/hunts/{hunt_id}/notebook")
def submit_notebook(hunt_id: int, body: Notebook, player=Depends(current_player)):
    if body.guess not in CREATURES:
        raise HTTPException(400, "Créature inconnue")
    with db() as con:
        hunt = con.execute("SELECT * FROM hunts WHERE id = ? AND player_id = ?", (hunt_id, player["id"])).fetchone()
        if not hunt:
            raise HTTPException(404, "Chasse inconnue")
        if hunt["finished_at"]:
            raise HTTPException(409, "Carnet déjà rendu")
        seconds = now() - hunt["started_at"]
        score = score_notebook(hunt["creature"], body.guess, body.evidences, body.survived, seconds)
        con.execute(
            "UPDATE hunts SET finished_at = ?, guess = ?, evidences = ?, survived = ?, score = ? WHERE id = ?",
            (now(), body.guess, json.dumps(body.evidences), int(body.survived), score, hunt_id),
        )
    return {"score": score, "creature": hunt["creature"], "correct": body.guess == hunt["creature"],
            "truth": CREATURES[hunt["creature"]]["evidences"]}


@app.get("/api/leaderboard")
def leaderboard(limit: int = Query(50, le=200)):
    with db() as con:
        rows = con.execute(
            """SELECT p.pseudo, SUM(h.score) AS total, COUNT(h.score) AS hunts,
                      SUM(CASE WHEN h.guess = h.creature THEN 1 ELSE 0 END) AS identified
               FROM hunts h JOIN players p ON p.id = h.player_id
               WHERE h.score IS NOT NULL
               GROUP BY p.id ORDER BY total DESC LIMIT ?""",
            (limit,),
        ).fetchall()
    return {"players": [dict(r) for r in rows]}


@app.get("/api/commands")
def pull_commands(player=Depends(current_player)):
    """L'appli vient chercher ici les commandes de test envoyées depuis l'admin."""
    with db() as con:
        rows = con.execute(
            "SELECT * FROM commands WHERE player_id = ? AND delivered_at IS NULL ORDER BY id", (player["id"],)
        ).fetchall()
        con.execute("UPDATE commands SET delivered_at = ? WHERE player_id = ? AND delivered_at IS NULL",
                    (now(), player["id"]))
    return {"commands": [{"id": r["id"], "kind": r["kind"], "payload": json.loads(r["payload"])} for r in rows]}


# ---------------------------------------------------------------- API admin

class ZoneIn(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    kind: str = "test"
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    radius_m: float = Field(40, ge=10, le=1000)
    active: bool = True
    creature: str | None = None  # None = au hasard
    spawn_chance: float = Field(1.0, ge=0, le=1)
    polygon: list[list[float]] | None = None  # [[lat, lon], ...] ; None = cercle
    legend: str | None = Field(None, max_length=2000)

    def shape(self):
        """(lat, lon, rayon, polygone JSON) à enregistrer."""
        if self.polygon:
            if len(self.polygon) < 3 or any(len(p) != 2 for p in self.polygon) or len(self.polygon) > 500:
                raise HTTPException(400, "Forme invalide : il faut entre 3 et 500 points")
            lat, lon, radius = polygon_anchor(self.polygon)
            return lat, lon, radius, json.dumps([[round(a, 7), round(b, 7)] for a, b in self.polygon])
        return self.lat, self.lon, self.radius_m, None


def check_zone(z: ZoneIn):
    if z.kind not in ZONE_KINDS:
        raise HTTPException(400, f"Type inconnu, choisir parmi {ZONE_KINDS}")
    if z.creature and z.creature not in CREATURES:
        raise HTTPException(400, f"Créature inconnue, choisir parmi {sorted(CREATURES)}")


@app.get("/api/admin/zones", dependencies=[Depends(require_admin)])
def admin_zones(south: float, west: float, north: float, east: float, limit: int = Query(2000, le=10000)):
    with db() as con:
        rows = con.execute(
            "SELECT * FROM zones WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? "
            "AND COALESCE(osm_id, '') != 'local-test' ORDER BY name LIMIT ?",
            (south, north, west, east, limit),
        ).fetchall()
        total = con.execute("SELECT COUNT(*) FROM zones WHERE COALESCE(osm_id, '') != 'local-test'").fetchone()[0]
    return {"zones": [zone_dict(r) for r in rows], "total": total}


@app.post("/api/admin/zones", dependencies=[Depends(require_admin)])
def admin_create_zone(z: ZoneIn):
    check_zone(z)
    lat, lon, radius, poly = z.shape()
    with db() as con:
        cur = con.execute(
            "INSERT INTO zones (name, kind, lat, lon, radius_m, active, creature, spawn_chance, polygon, legend, "
            "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (z.name, z.kind, lat, lon, radius, int(z.active), z.creature or None, z.spawn_chance, poly,
             (z.legend or "").strip() or None, now()),
        )
        row = con.execute("SELECT * FROM zones WHERE id = ?", (cur.lastrowid,)).fetchone()
    return zone_dict(row)


@app.put("/api/admin/zones/{zone_id}", dependencies=[Depends(require_admin)])
def admin_update_zone(zone_id: int, z: ZoneIn):
    check_zone(z)
    if zone_id == test_zone_id():
        raise HTTPException(400, "Zone interne, non modifiable")
    lat, lon, radius, poly = z.shape()
    with db() as con:
        cur = con.execute(
            "UPDATE zones SET name = ?, kind = ?, lat = ?, lon = ?, radius_m = ?, active = ?, creature = ?, "
            "spawn_chance = ?, polygon = ?, legend = ? WHERE id = ?",
            (z.name, z.kind, lat, lon, radius, int(z.active), z.creature or None, z.spawn_chance, poly,
             (z.legend or "").strip() or None, zone_id),
        )
        if cur.rowcount == 0:
            raise HTTPException(404, "Zone inconnue")
        row = con.execute("SELECT * FROM zones WHERE id = ?", (zone_id,)).fetchone()
    return zone_dict(row)


@app.delete("/api/admin/zones/{zone_id}", dependencies=[Depends(require_admin)])
def admin_delete_zone(zone_id: int):
    if zone_id == test_zone_id():
        raise HTTPException(400, "Zone interne, non supprimable")
    with db() as con:
        used = con.execute("SELECT COUNT(*) FROM hunts WHERE zone_id = ?", (zone_id,)).fetchone()[0]
        if used:
            con.execute("UPDATE zones SET active = 0 WHERE id = ?", (zone_id,))
            return {"deleted": False, "deactivated": True}
        con.execute("DELETE FROM zones WHERE id = ?", (zone_id,))
    return {"deleted": True}


@app.get("/api/admin/players", dependencies=[Depends(require_admin)])
def admin_players():
    with db() as con:
        rows = con.execute("SELECT id, pseudo, created_at FROM players ORDER BY pseudo").fetchall()
    return {"players": [dict(r) for r in rows]}


class CommandIn(BaseModel):
    player_id: int
    kind: str  # spawn_now | in_zone | zone_near
    payload: dict = {}


@app.post("/api/admin/commands", dependencies=[Depends(require_admin)])
def admin_command(c: CommandIn):
    if c.kind not in ("spawn_now", "in_zone", "zone_near"):
        raise HTTPException(400, "Commande inconnue")
    with db() as con:
        if not con.execute("SELECT 1 FROM players WHERE id = ?", (c.player_id,)).fetchone():
            raise HTTPException(404, "Joueur inconnu")
        cur = con.execute(
            "INSERT INTO commands (player_id, kind, payload, created_at) VALUES (?, ?, ?, ?)",
            (c.player_id, c.kind, json.dumps(c.payload), now()),
        )
    return {"id": cur.lastrowid}


@app.post("/api/admin/import-osm", dependencies=[Depends(require_admin)])
def admin_import_osm(area: str = "BE"):
    """Importe les cimetières d'un pays depuis OpenStreetMap (Overpass). Rejouable sans doublons."""
    query = f"""
    [out:json][timeout:180];
    area["ISO3166-1"="{area}"][admin_level=2]->.a;
    (
      way["landuse"="cemetery"](area.a);
      relation["landuse"="cemetery"](area.a);
      way["amenity"="grave_yard"](area.a);
      relation["amenity"="grave_yard"](area.a);
      node["amenity"="grave_yard"](area.a);
    );
    out center tags;
    """
    req = urllib.request.Request(
        OVERPASS_URL,
        data=urllib.parse.urlencode({"data": query}).encode(),
        headers={"User-Agent": "found-footage-admin/1.0"},
    )
    with urllib.request.urlopen(req, timeout=200) as resp:
        data = json.load(resp)
    added = 0
    with db() as con:
        for el in data.get("elements", []):
            c = el.get("center") or {"lat": el.get("lat"), "lon": el.get("lon")}
            if c.get("lat") is None:
                continue
            tags = el.get("tags", {})
            name = tags.get("name") or "Cimetière"
            cur = con.execute(
                "INSERT OR IGNORE INTO zones (name, kind, lat, lon, radius_m, active, osm_id, created_at) "
                "VALUES (?, 'cemetery', ?, ?, 40, 1, ?, ?)",
                (name, c["lat"], c["lon"], f"{el['type']}/{el['id']}", now()),
            )
            added += cur.rowcount
    return {"found": len(data.get("elements", [])), "added": added}


# ---------------------------------------------------------------- page admin

@app.get("/")
def root():
    return RedirectResponse("/admin/")


@app.get("/admin/")
def admin_page():
    return FileResponse(STATIC / "admin.html")


app.mount("/admin/static", StaticFiles(directory=STATIC), name="static")
