# Changelog

Each version names the Minecraft and Bodycam builds it was tested with. After a game update, run the in-game
check (`cd mod && gradlew runClient -Pautotest`) before releasing again.

## 0.3.0 (2026-10-26)
Minecraft 1.21.11, Fabric Loader 0.19.5, Bodycam Steam build 25505592.
- Bodycam's first-person arms (gloves, sleeves) hold the guns, read from the player's own copy like everything else.
- Bodycam's own animations: idle, aiming, drawing the gun, the recoil of each shot, and every reload variant, each
  played with the reload sound of the same name.
- The guns move with their own Bodycam animations: the magazine comes out, the fresh one comes in from the left hand,
  the slide and bolt cycle, the slide locks back on the last round.
- The M4A1 sits at chest height and aims along the rail; its magazine is seated where Bodycam puts it.
- A third native decoder, `bcacl.dll` (ACL 2.1, MIT), reads Bodycam's compressed animations.

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
