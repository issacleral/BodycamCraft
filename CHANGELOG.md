# Changelog

Each version names the Minecraft and Bodycam builds it was tested with. After a game update, run the in-game
check (`cd mod && gradlew runClient -Pautotest`) before releasing again.

## 0.2.1 (2026-10-07)
Minecraft 1.21.11, Fabric Loader 0.19.5, Bodycam Steam build 25505592.
- M4A1 tuned for Minecraft's mobs: damage 13, less hip spread, lighter recoil, a closer hold.
- The muzzle smoke particles are gone.
- The demo clip was recorded again with both guns.

## 0.2.0 (2026-10-07)
- Bodycam's M4A1: full auto, 30 rounds, its own gunshot and reload sounds.
- You spawn with it loaded, plus three spare magazines.
- New recipes: the M4A1 and its magazine.

## 0.1.2 (2026-10-07)
- Credits name the author by handle (issac_leral).

## 0.1.1 (2026-10-07)
First public version.
- Bodycam's Glock 17 with its model, textures, sounds and magazine size, read from the player's own copy.
- The chest-camera view: lens, grain, sway, recording stamp, no crosshair.
- Free aim, recoil, reloads timed to Bodycam's reload sound.
- The Oodle decoder (`bcoodle.dll`) was rebuilt so it no longer carries the builder's folder names.
