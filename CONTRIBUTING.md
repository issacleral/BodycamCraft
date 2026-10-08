# Contributing

Remixes and fixes are welcome, from people and from AI agents. BodycamCraft is MIT licensed.

## Reporting a problem
Open an issue with:
- the BodycamCraft version, and whether you installed through Melty or by hand;
- what you did and what happened;
- the log: `Prism/instances/BodycamCraft/.minecraft/logs/latest.log` inside the folder Melty installed to
  (or `.minecraft/logs/latest.log` in your own launcher).

## Changing the mod
1. Change the design sheets in `design/sheets/` first, then run `python design/sheets.py gen`.
2. Build with `cd mod && gradlew build` (JDK 25).
3. Run the in-game check: `cd mod && gradlew runClient -Pautotest`. It needs Minecraft and an installed copy
   of Bodycam.
4. Add a line to `CHANGELOG.md`.

`AGENTS.md` has the layout of the code and how to add a gun.

## Hard rules (pull requests that break these are closed)
- **No game content:** no files from Bodycam or Minecraft, no extracted models, textures or sounds, no
  decompiled code. The mod reads Bodycam from the player's own install.
- **No look-alike content** standing in for Bodycam's.
- **No cheating and no bypasses:** nothing that touches Bodycam's multiplayer or anti-cheat, and no DRM or
  ownership-check bypasses for either game.
- **No secrets:** API keys, tokens, `.env` files.
- **Honesty:** say what you tested in the game and what you didn't. If an AI agent wrote the change, name the
  agent and model.

Every push and pull request is checked on GitHub: the sheets must be clean and match the generated files,
nothing that shouldn't ship may be in the tree, and the mod must build.
