# BodycamCraft build journal

## Idea
Minecraft survival played as Bodycam footage: spawn armed with a Bodycam pistol, chest-cam view, Minecraft's
own mobs. Solo. Host = Minecraft Java (Fabric), Bodycam = content source read from the player's Steam copy.

## Recon (2026-10-07)
- Bodycam: Steam app 2406770, `steamapps/common/Bodycam`, build 25505592. Unreal Engine 5, 83 legacy `.pak`
  v11 files (no IoStore), index unencrypted, entries Oodle-compressed (106 of 114,186 entries encrypted).
- No anti-cheat files in the install. The mod only reads the paks; Bodycam is never launched or changed.
- Guns: `Bodycam/Content/BodycamWeapons/Guns/<gun>/` (SKM_* skeletal mesh, part meshes, M_/MI_ materials,
  T_* textures in .ubulk). Glock17 is the starting pistol.
- Gun audio: `Bodycam/Content/Audio/Guns/Wav/<family>/` SoundWaves (.ubulk) and `Audio/Guns/Cue/` cues.
  The Glock's sounds are named "Mlock19"/"Mlock" under `Wav/Pistol`.
- Weapon data tables: `BodycamWeapons/Core/DATA/DT/` (DT_MAG, Actor/DT_SecondaryWep, ...).
- Oodle: Bodycam ships no Oodle DLL. Decoder = `oozextract` (Rust, MIT) wrapped as `bcoodle.dll`.

## Tools installed (all inside `tools/`, nothing system-wide)
- universal-modder (git clone), Temurin JDK 21, Rust stable (x86_64-pc-windows-gnu).

## Rules
- Never commit or ship Bodycam files; extracted data stays in the scratchpad / the instance's cache folder.

## State on 2026-10-07 (evening)
- Route: Fabric mod on Minecraft 1.21.11 (Mojang mappings, Loom 1.18, Gradle needs JDK 25: set JAVA_HOME to tools/jdk-25*).
- Sheets in design/sheets are the source of truth; `python design/sheets.py gen` checks them and writes gen/Sheets.java and resources.
- In-game check: `cd mod && gradlew runClient -Pautotest` -> one "AUTOTEST PASS/FAIL" log line and screenshots in mod/run/screenshots.
- Release: `cd mod && gradlew build`, then `python package.py` -> dist/ (jar + Prism bundle). Recipe: melty.json.
- Melty draft: modId a7cc7d58-4421-4844-8c3b-8bc73ff9466d (slug bodycamcraft), MIT, remixes allowed, title/tagline/description agreed.
- Gotchas: zombies have 2 armour (damage 10 does not two-shot them); Bodycam weapon textures are virtual textures (DXT tiles in
  Morton order, 4 px border); weapon parts hang on Skeleton sockets named like the part (Slide, magazine, Trigger, Threaded_Barrel);
  sounds are Bink Audio (ABEU header inline, packets in .ubulk); never run two Gradle builds at once.
- Still to check by a person: free aim with the mouse, sounds by ear, real clicks/keys, crafting, install through Melty.
- bcoodle.dll must be built with path remapping (CARGO_ENCODED_RUSTFLAGS --remap-path-prefix for the home and project
  folders, project last) or the Rust panic strings carry the builder's Windows user name and folders. 0.1.0 had them; 0.1.1 does not.
