# BodycamCraft

A Fabric mod for Minecraft: Java Edition 1.21.11 that shows survival as bodycam footage and reads the guns and
their sounds from the player's own copy of Bodycam (Steam app 2406770) while the game runs. Someone who opens
an agent here usually wants to **remix it**: add a gun, tune the camera, or fix something.

## Start here
1. Read `MODLOG.md` (the build journal: versions, route, gotchas) and `README.md`.
2. The design lives in `design/sheets/*.json`. They are the source of truth. Change a sheet, then run
   `python design/sheets.py gen`: it checks every cell and reference and writes `mod/.../gen/Sheets.java`, the
   item models, lang, `sounds.json`, the post effect and the recipes. Never edit generated files by hand.
3. Build: `cd mod && gradlew build` with `JAVA_HOME` set to a JDK 25 (Loom 1.18 needs it; the mod targets
   Java 21).
4. Verify in the real game: `cd mod && gradlew runClient -Pautotest`. It logs one `AUTOTEST PASS` or
   `AUTOTEST FAIL` line and saves screenshots to `mod/run/screenshots`. `-Pautotest=demo` records the
   captioned tour.
5. Package: `python package.py` writes the jar and the Prism Launcher bundle to `dist/`. `melty.json` is the
   install recipe.

## Where things are
- `mod/src/main/java/gg/bodycamcraft/bodycam/`: readers for Bodycam's files (pak index, cooked packages,
  meshes, skeleton sockets, virtual textures, Bink Audio, the magazine table).
- `mod/src/main/java/gg/bodycamcraft/`: items, server-side gun logic (`GunLogic`), the asset library.
- `mod/src/client/java/gg/bodycamcraft/client/`: view, gun rendering, HUD, sounds, mixins, `Autotest`.
- `native/`: sources of the two decoders shipped in the jar (`bcoodle`, Rust; `bcbinka`, C).
- `design/sheets/hooks.json`: every place the mod hooks Minecraft. Each hooked file carries a
  `// hook: <id>` comment, and `preflight` checks the target still exists.

## Adding a gun
Add a row to `weapons.json`, its rows in `parts.json` and `sounds.json`, an item and a magazine in
`items.json`, and optionally `kit.json` and `recipes.json`. Run `gen`, then the in-game check. Parts attach
to sockets of the gun's Skeleton asset, not to bones. Magazine sizes come from Bodycam's `DT_MAG` table.

## Rules
- **Nothing of Bodycam's or Minecraft's is committed or shipped:** no game files, extracted assets or
  decompiled code. Extracted data and decompiles stay in `tools/` and `recon/`, which git ignores.
  `package.py` refuses to pack a jar that contains game files.
- **Bodycam is only read.** The mod never launches, patches or injects into it, and never touches its
  anti-cheat or multiplayer.
- **No look-alikes.** If something can't be read from Bodycam, say so instead of substituting other content.
- **No secrets** in files, commits, logs or screenshots.
- **Rebuilding `bcoodle.dll`:** set `CARGO_ENCODED_RUSTFLAGS` with `--remap-path-prefix` for the home folder
  and the project folder (project last), or the DLL carries the builder's folder names.
- **Ask the human first** before publishing anything (releases, Melty, pull requests) and before driving
  their mouse and keyboard.
- **Be honest about verification.** Rows in the sheets carry a `verified` cell; leave it at `pending: ...`
  until it was seen working in the game.
- Keep `MODLOG.md` and `CHANGELOG.md` current.
