# AR Perso 🦊

Petite application Android de réalité augmentée : elle détecte le sol (ou une table) et y pose un renard animé.

- Toucher une surface détectée → le renard apparaît, tourné vers toi.
- Toucher le renard → il change d'animation (regarde autour / marche / court).
- Toucher ailleurs → il se déplace.

## Installer sur le téléphone

Télécharger le dernier APK : **[ar-perso.apk](../../releases/latest/download/ar-perso.apk)**, l'ouvrir et autoriser l'installation d'applications inconnues.
Sur un téléphone compatible ARCore (« Services Google Play pour la RA »), l'appli détecte vraiment les surfaces.
Sinon, elle passe automatiquement en **mode simple** : caméra en fond + gyroscope, le renard est posé sur un sol imaginaire
~1,3 m sous le téléphone. Il reste en place quand on tourne le téléphone, mais pas quand on marche.

Chaque push sur `main` recompile l'APK via GitHub Actions et publie une nouvelle release.

## Technique

- Kotlin + Jetpack Compose
- [ARCore](https://developers.google.com/ar) pour la détection de surfaces
- [SceneView](https://github.com/SceneView/sceneview-android) 2.2.1 pour le rendu 3D
- Modèle : [Fox](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/Fox) des glTF Sample Assets de Khronos (modèle CC0 par PixelMannen, rigging et animation CC-BY 4.0 par @tomkranis).
