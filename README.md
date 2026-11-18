# Found Footage 👁️

Petite application Android d'horreur : une ghoule rôde autour de toi, et tu ne peux la voir
qu'à travers la « caméra » façon found footage (image basse qualité, grain, défauts VHS, REC).

- Bouton **● CAMÉRA** en haut à droite : passe en mode found footage. **■ STOP** pour revenir.
- Elle avance quand tu ne la regardes pas. Filmée, elle se fige… puis saute plus près dans un glitch.
- Trop près : jumpscare (hurlement, vibration, secousse). Puis elle revient, dans ton dos.
- Les battements de cœur accélèrent quand elle approche, même en mode normal.

## Installer sur le téléphone

Télécharger le dernier APK : **[ar-perso.apk](../../releases/latest/download/ar-perso.apk)**, l'ouvrir et autoriser
l'installation d'applications inconnues. Chaque push sur `main` recompile l'APK via GitHub Actions et publie une release.

## Technique

- Kotlin + Jetpack Compose, CameraX, [SceneView](https://github.com/SceneView/sceneview-android) 2.2.1 (Filament)
- Pas besoin d'ARCore : la caméra 3D suit le gyroscope, la ghoule marche sur un sol imaginaire ~1,3 m sous le téléphone
  (elle reste en place quand on tourne sur soi, pas quand on marche).
- Le modèle `ghoul.glb` est généré par `tools/make_ghoul.py` (procédural, aucun asset externe).
- Les sons (grésillement, cœur, hurlement) sont synthétisés à la volée.
