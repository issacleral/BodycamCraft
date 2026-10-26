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
- Animations (2026-10-26, step 1 of hands + real animations): every first-person gun AnimSequence imports
  /ACLPlugin/ACLAnimBoneCompressionSettings and ACLAnimCurveCompressionSettings. The .uexp holds ACL
  compressed_tracks buffers (tag 0xac11ac11, preceded by size + hash): version 10 = ACL 2.1.0, algorithm 0
  (uniformly sampled), bones as track_type 12 (qvvf), curves as track_type 0 (float1f), 30 fps. Arms use
  ALS_Mannequin_Skeleton (86 bone tracks; additive ones like Fire carry 179). Glock: A_FP_Glock_* (fire, draw,
  equip, holster, idle, magCheck, reloadEmpty/Partial x Calm/Stressed 01-04) with matching gun-side A_W_Glock_*
  (13 tracks) and G17_* reload sounds of the same names. M4A1: A_FP_M4A1_* likewise. Arms mesh:
  BodycamSkin/Arms/Basic/SKM_HumanArmsLongSleeves_Morphers. Some A_FP_Glock17_* (inspect, flinch) have no
  0xac11ac11 buffer: not checked why. REA had no disassembler configured (no Ghidra), so the exe was not opened;
  the data plus ACL's public version table answered the question.
- Step 2 (2026-10-26): native/bcacl (ACL 2.1.0 + RTM headers, MIT, vendored in native/bcacl/include) builds
  bcacl.dll with `zig c++ -shared -target x86_64-windows-gnu -O2 -std=c++17 -DNDEBUG
  -Wno-missing-template-arg-list-after-template-kw -Iinclude -ffile-prefix-map=<project>=.` (newer clang rejects
  ACL 2.1's `::template is_bound_to(` without that flag). Gotchas: (1) Unreal's ACL plugin strips sub-tracks equal
  to the bind pose; decoding them as ACL's default (identity/zero) collapses every bone onto its parent, so the
  writer uses default_sub_track_mode::skipped, the DLL leaves NaN, and the caller fills from the reference pose.
  (2) The .uexp of an AnimSequence starts with a header, then at byte 66 an int32 track count followed by one int32
  skeleton bone index per track (tables are 2 bytes off 4-byte alignment). (3) ALS_Mannequin_Skeleton has 158 real
  bones (reference skeleton = [n][name, parent]*n then [n] double transforms, name map of 158 after); track indices
  >= 158 are virtual bones and can be ignored for the arms. Verified: Glock reloadEmpty_Calm01 posed on the
  skeleton draws a two-handed grip, the left hand going to the belt and back, and the same pose at both ends.
  Not yet: curves, additive anims (Fire carries scale 0 = additive deltas), the gun-side A_W_ tracks.
- Step 3a (2026-10-26): arms in game. Skeleton has 21 virtual bones stored as 26-byte elements [2][source][target][name]
  ("VB ..."), index 158+i in element order; a VB hangs off its source with the target's ref transform. Gun origin =
  ik_hand_gun (== VB ik_hand_gun) with no offset; the camera = head + (0, 0, -4) cm looking along +Y (the FP_Camera
  socket and VB CameraPoint did not give a usable view). Arms mesh: 3 sections (MI_Arms, MI_Gloves, MI_Sleeves), each
  with its own bone map; skin weights after the UVs, 4 x u8 bone + 4 x u8 weight per vertex. Sheets arms.json and
  anims.json; RigReader / ArmsAssets / ArmsRenderer (CPU skinning, 22,642 vertices). While aiming the whole rig turns
  and shifts so the sight sits on the view axis. Autotest PASS; Glock hip/aim/reload look right. M4A1: hands on the
  rifle, but too close to the camera and the stock fills the aimed view (to tune).
- M4A1 tuning (2026-10-26): the rifle anims hold it shouldered (right hand 12 cm from the head), so weapons now have
  rig_camera_cm (M4A1: 0, -10, 7) and aim_eye_above_cm (M4A1 4.5: its sight_part is the flat-top frame, the eye has
  to sit above the rail or the stock fills the view; Glock 0.3, the old global drop, now removed from camera.json).
- Step 3b (2026-10-26): gun-side animations (A_W_*) play with the arms, same variant and moment. They are made for a
  skeleton that is not always the one the gun is assembled from (M4A1: M4_Skeleton, 34 bones, not M4A1A_Skeleton),
  hence weapons.anim_skeleton. Parts name the bone they follow (parts.bone); a part's transform is
  animated(bone) * inverse(reference(bone)), mapped to model space like the arms. Bodycam hides magazines with a scale
  track of 0. The fresh magazine is the magazine part drawn again on magazine_spare (weapons.spare_magazine_bone).
  Verified on the Glock partial reload: magazine out, the left hand pushes the fresh one in. M4A1 reload not looked at.
- Python: the Microsoft Store python behind tools/pyenv stopped starting (even `print(1)` hangs). tools/py312 is a uv
  venv on a uv-managed CPython 3.12.11 with numpy and Pillow; use it for design/sheets.py and recon. Heredocs with
  backslashes still get mangled by the shell: edit Python files with the editor, not with `python - <<EOF` patches.
- bcoodle.dll must be built with path remapping (CARGO_ENCODED_RUSTFLAGS --remap-path-prefix for the home and project
  folders, project last) or the Rust panic strings carry the builder's Windows user name and folders. 0.1.0 had them; 0.1.1 does not.
