"""BodycamCraft design sheets: preflight check and code generation.

    python design/sheets.py preflight   list every unfilled cell and unresolved reference
    python design/sheets.py gen         write the generated Java and resource files (runs preflight first)

The sheets in design/sheets/*.json are the source of truth. Change a sheet, then run gen.
"""
import glob
import json
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHEETS = os.path.join(ROOT, "design", "sheets")
MOD = os.path.join(ROOT, "mod")
SOURCES = [os.path.join(ROOT, "tools", "mc-src"), os.path.join(ROOT, "tools", "fabric-src")]
MODID = "bodycamcraft"


def load():
    sheets = {}
    for path in sorted(glob.glob(os.path.join(SHEETS, "*.json"))):
        with open(path, encoding="utf-8") as f:
            sheets[os.path.splitext(os.path.basename(path))[0]] = json.load(f)
    return sheets


# ---------------------------------------------------------------------------------------------- preflight

def bodycam_files():
    """Lower-case paths of every file in the installed Bodycam's paks, or None when it is not installed."""
    paks = None
    for lib in [r"C:\Program Files (x86)\Steam", r"C:\Program Files\Steam"]:
        vdf = os.path.join(lib, "steamapps", "libraryfolders.vdf")
        roots = [lib]
        if os.path.isfile(vdf):
            roots += [m.replace("\\\\", "\\") for m in re.findall(r'"path"\s+"([^"]+)"', open(vdf, encoding="utf-8").read())]
        for root in roots:
            candidate = os.path.join(root, "steamapps", "common", "Bodycam", "Bodycam", "Content", "Paks")
            if os.path.isdir(candidate):
                paks = candidate
    if paks is None:
        return None

    def fstr(b, o):
        n, = struct.unpack_from("<i", b, o)
        o += 4
        if n >= 0:
            return b[o:o + n].split(b"\0")[0].decode("latin1"), o + n
        return b[o:o - 2 * n].decode("utf-16-le").rstrip("\0"), o - 2 * n

    files = set()
    for pak in glob.glob(os.path.join(paks, "*.pak")):
        with open(pak, "rb") as h:
            h.seek(os.path.getsize(pak) - 221)
            footer = h.read(221)
            index_offset, index_size = struct.unpack_from("<QQ", footer, 25)
            h.seek(index_offset)
            b = h.read(index_size)
            mount, o = fstr(b, 0)
            o += 12
            has_path_hash_index, = struct.unpack_from("<I", b, o)
            o += 4 + (36 if has_path_hash_index else 0)
            o += 4
            dir_offset, dir_size = struct.unpack_from("<qq", b, o)
            h.seek(dir_offset)
            d = h.read(dir_size)
        count, = struct.unpack_from("<i", d, 0)
        o = 4
        root = mount.replace("../../../", "")
        for _ in range(count):
            dirname, o = fstr(d, o)
            n, = struct.unpack_from("<i", d, o)
            o += 4
            for _ in range(n):
                name, o = fstr(d, o)
                o += 4
                files.add((root + dirname + name).lower())
    return files


def preflight(sheets, quiet=False):
    problems, pending = [], []
    keys = {name: {row.get("id") for row in sheet["rows"]} for name, sheet in sheets.items()}
    files = bodycam_files()
    if files is None:
        print("note: Bodycam is not installed here, so Bodycam paths were not checked")

    def exists(path):
        return files is None or (path.lower() + ".uasset") in files

    for name, sheet in sheets.items():
        columns = sheet["_columns"]
        seen = set()
        for row in sheet["rows"]:
            where = "%s.%s" % (name, row.get("id", "?"))
            if row.get("id") in seen:
                problems.append("%s: duplicate id" % where)
            seen.add(row.get("id"))
            for column, kind in columns.items():
                if column not in row:
                    problems.append("%s: no cell for column '%s'" % (where, column))
                    continue
                value = row[column]
                may_be_empty = "empty" in kind or kind.endswith("list")
                if value is None or value == "TODO" or (value in ("", [], {}) and not may_be_empty):
                    problems.append("%s.%s: unfilled" % (where, column))
                    continue
                values = value if isinstance(value, list) else [value]
                values = [v for v in values if not (may_be_empty and v == "")]
                if kind.startswith("ref "):
                    target = kind.split()[1].split(".")[0].rstrip(",")
                    for v in values:
                        if v not in keys.get(target, ()):
                            problems.append("%s.%s: '%s' is not a row of %s" % (where, column, v, target))
                elif kind.startswith("bodycam path"):
                    for v in values:
                        if not exists(v):
                            problems.append("%s.%s: not in Bodycam's files: %s" % (where, column, v))
            for column in row:
                if column not in columns and column != "verified":
                    problems.append("%s: cell '%s' is not a column of the sheet" % (where, column))
            verified = row.get("verified", "")
            if not verified:
                problems.append("%s.verified: unfilled" % where)
            elif verified.startswith("pending"):
                pending.append("%s: %s" % (where, verified))

    for row in sheets["hooks"]["rows"]:
        where = "hooks.%s" % row["id"]
        source = next((os.path.join(s, row["target"] + ".java") for s in SOURCES if os.path.isfile(os.path.join(s, row["target"] + ".java"))), None)
        if source is None:
            problems.append("%s.target: no such class in the decompiled sources: %s" % (where, row["target"]))
        elif row["member"] not in open(source, encoding="utf-8").read():
            problems.append("%s.member: '%s' is not in %s" % (where, row["member"], row["target"]))
        path = os.path.join(MOD, row["file"])
        if not os.path.isfile(path):
            problems.append("%s.file: not written yet: %s" % (where, row["file"]))
        elif ("hook: %s" % row["id"]) not in open(path, encoding="utf-8").read():
            problems.append("%s.file: %s does not mark 'hook: %s'" % (where, row["file"], row["id"]))
    for row in sheets["systems"]["rows"]:
        if not os.path.isfile(os.path.join(MOD, row["file"])):
            problems.append("systems.%s.file: not written yet: %s" % (row["id"], row["file"]))
    for row in sheets["parts"]["rows"]:
        weapon = next((w for w in sheets["weapons"]["rows"] if w["id"] == row["weapon"]), None)
        if weapon and row["motion"] == "slide" and weapon["slide_part"] != row["id"]:
            problems.append("parts.%s: motion is slide but weapons.%s.slide_part is %s" % (row["id"], weapon["id"], weapon["slide_part"]))

    if not quiet:
        cells = sum(len(s["_columns"]) * len(s["rows"]) for s in sheets.values())
        print("%d sheets, %d rows, %d cells" % (len(sheets), sum(len(s["rows"]) for s in sheets.values()), cells))
        for p in problems:
            print("  PROBLEM  " + p)
        for p in pending:
            print("  pending  " + p)
        print("preflight: %s (%d problems, %d rows not yet verified in game)" % ("CLEAN" if not problems else "NOT CLEAN", len(problems), len(pending)))
    return problems


# ---------------------------------------------------------------------------------------------- generation

def jstr(v):
    return json.dumps(v, ensure_ascii=True)


def jlist(values):
    return "List.of(" + ", ".join(jstr(v) for v in values) + ")"


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def write_json(path, data):
    write(path, json.dumps(data, indent=2) + "\n")


def gen(sheets):
    weapons, parts, sounds = sheets["weapons"]["rows"], sheets["parts"]["rows"], sheets["sounds"]["rows"]
    items, kit, camera = sheets["items"]["rows"], sheets["kit"]["rows"], sheets["camera"]["rows"]
    out = ["// Generated by design/sheets.py from design/sheets/*.json. Do not edit: change the sheet and run gen.",
           "package gg.bodycamcraft.gen;", "", "import java.util.List;", "",
           "public final class Sheets {", "\tprivate Sheets() {", "\t}", ""]

    out += ["\tpublic record Weapon(String id, String name, String item, String magazineItem, String skeleton, String material,",
            "\t\t\tString muzzleSocket, String magTable, String magRow, int magFallback, boolean auto, int fireIntervalTicks,",
            "\t\t\tfloat damage, float headshotMultiplier, int rangeBlocks, float spreadHipDeg, float spreadAdsDeg,",
            "\t\t\tfloat recoilPitchDeg, float recoilYawDeg, String slidePart, float slideTravelCm, String magazinePart,",
            "\t\t\tint textureSize, List<String> soundFire, List<String> soundFireIndoor, String soundDry,",
            "\t\t\tString soundReloadEmpty, String soundReloadPartial, String soundEquip, String sightPart, float hipRight, float hipUp, float hipForward, float aimForward) {", "\t}", ""]
    out.append("\tpublic static final List<Weapon> WEAPONS = List.of(")
    rows = []
    for w in weapons:
        rows.append("\t\t\tnew Weapon(%s, %s, %s, %s, %s, %s,\n\t\t\t\t\t%s, %s, %s, %d, %s, %d,\n\t\t\t\t\t%sf, %sf, %d, %sf, %sf,\n\t\t\t\t\t%sf, %sf, %s, %sf, %s,\n\t\t\t\t\t%d, %s, %s, %s,\n\t\t\t\t\t%s, %s, %s, %s, %sf, %sf, %sf, %sf)" % (
            jstr(w["id"]), jstr(w["name"]), jstr(w["item"]), jstr(w["magazine_item"]), jstr(w["skeleton"]), jstr(w["material"]),
            jstr(w["muzzle_socket"]), jstr(w["mag_table"]), jstr(w["mag_row"]), w["mag_fallback"], "true" if w["fire_mode"] == "auto" else "false", w["fire_interval_ticks"],
            w["damage"], w["headshot_multiplier"], w["range_blocks"], w["spread_hip_deg"], w["spread_ads_deg"],
            w["recoil_pitch_deg"], w["recoil_yaw_deg"], jstr(w["slide_part"]), w["slide_travel_cm"], jstr(w["magazine_part"]),
            w["texture_size"], jlist(w["sound_fire"]), jlist(w["sound_fire_indoor"]), jstr(w["sound_dry"]),
            jstr(w["sound_reload_empty"]), jstr(w["sound_reload_partial"]), jstr(w["sound_equip"]), jstr(w["sight_part"]), w["hip_right"], w["hip_up"], w["hip_forward"], w["aim_forward"]))
    out.append(",\n".join(rows) + ");")
    out.append("")

    out += ["\tpublic record Part(String id, String weapon, String mesh, boolean skeletal, String socket, String motion, String socketSkeleton) {", "\t}", ""]
    out.append("\tpublic static final List<Part> PARTS = List.of(")
    out.append(",\n".join("\t\t\tnew Part(%s, %s, %s, %s, %s, %s, %s)" % (jstr(p["id"]), jstr(p["weapon"]), jstr(p["mesh"]), "true" if p["kind"] == "skeletal" else "false", jstr(p["socket"]), jstr(p["motion"]), jstr(p["socket_skeleton"])) for p in parts) + ");")
    out.append("")

    out += ["\tpublic record Sound(String id, List<String> paths, float volume, float pitchJitter) {", "\t}", ""]
    out.append("\tpublic static final List<Sound> SOUNDS = List.of(")
    out.append(",\n".join("\t\t\tnew Sound(%s, %s, %sf, %sf)" % (jstr(s["id"]), jlist(s["paths"]), s["volume"], s["pitch_jitter"]) for s in sounds) + ");")
    out.append("")

    out += ["\tpublic record Item(String id, String kind, String name, int stack, String weapon) {", "\t}", ""]
    out.append("\tpublic static final List<Item> ITEMS = List.of(")
    out.append(",\n".join("\t\t\tnew Item(%s, %s, %s, %d, %s)" % (jstr(i["id"]), jstr(i["kind"]), jstr(i["name"]), i["stack"], jstr(i["weapon"])) for i in items) + ");")
    out.append("")

    out += ["\tpublic record Kit(String id, String item, int count, int slot, boolean loaded) {", "\t}", ""]
    out.append("\tpublic static final List<Kit> KIT = List.of(")
    out.append(",\n".join("\t\t\tnew Kit(%s, %s, %d, %d, %s)" % (jstr(k["id"]), jstr(k["item"]), k["count"], k["slot"], "true" if k["loaded"] else "false") for k in kit) + ");")
    out.append("")

    out.append("\t/** The camera sheet: one constant per number, named ROW_PARAM. */")
    out.append("\tpublic static final class Cam {")
    for row in camera:
        for key, value in row["params"].items():
            name = (row["id"] + "_" + key).upper()
            if isinstance(value, str):
                out.append("\t\tpublic static final String %s = %s;" % (name, jstr(value)))
            else:
                out.append("\t\tpublic static final float %s = %sf;" % (name, float(value)))
    out += ["", "\t\tprivate Cam() {", "\t\t}", "\t}", ""]

    out += ["\tpublic static Weapon weapon(String id) {", "\t\tfor (Weapon w : WEAPONS) {", "\t\t\tif (w.id().equals(id)) {", "\t\t\t\treturn w;", "\t\t\t}", "\t\t}", "\t\treturn null;", "\t}", "",
            "\tpublic static Sound sound(String id) {", "\t\tfor (Sound s : SOUNDS) {", "\t\t\tif (s.id().equals(id)) {", "\t\t\t\treturn s;", "\t\t\t}", "\t\t}", "\t\treturn null;", "\t}", "}", ""]
    write(os.path.join(MOD, "src/main/java/gg/bodycamcraft/gen/Sheets.java"), "\n".join(out))

    assets = os.path.join(MOD, "src/main/resources/assets", MODID)
    data = os.path.join(MOD, "src/main/resources/data", MODID)
    lang = {
        "key.category.bodycamcraft.main": "BodycamCraft",
        "key.bodycamcraft.reload": "Reload",
        "key.bodycamcraft.toggle_view": "Bodycam view on/off",
        "bodycamcraft.missing": "Bodycam is not installed. Install Bodycam on Steam to get its guns and sounds.",
        "bodycamcraft.loading": "Loading the guns from your copy of Bodycam...",
        "bodycamcraft.failed": "Could not read Bodycam's files: %s",
        "bodycamcraft.tooltip.rounds": "%s / %s rounds",
        "bodycamcraft.tooltip.magazine": "%s rounds",
        "bodycamcraft.view.on": "Bodycam view on",
        "bodycamcraft.view.off": "Bodycam view off",
        "subtitles.bodycamcraft.gun": "Gun sound",
    }
    for item in items:
        lang["item.%s.%s" % (MODID, item["id"])] = item["name"]
        kind, part = item["model"].split(":")
        write_json(os.path.join(assets, "items", item["id"] + ".json"), {"model": {
            "type": "minecraft:special", "base": "%s:item/%s_base" % (MODID, part),
            "model": {"type": "%s:gun" % MODID, "weapon": item["weapon"], "part": "all" if part == "gun" else part}}})
    write_json(os.path.join(assets, "lang", "en_us.json"), lang)
    write_json(os.path.join(assets, "sounds.json"), {"gun": {"subtitle": "subtitles.bodycamcraft.gun", "sounds": [{"name": "fabric-sound-api-v1:empty", "stream": True}]}})

    lens = next(r for r in camera if r["id"] == "lens")["params"]
    uniforms = [{"name": k.capitalize(), "type": "float", "value": float(v)} for k, v in lens.items()]
    write_json(os.path.join(assets, "post_effect", "bodycam.json"), {
        "targets": {"swap": {}},
        "passes": [
            {"vertex_shader": "minecraft:core/screenquad", "fragment_shader": "%s:post/bodycam" % MODID,
             "inputs": [{"sampler_name": "In", "target": "minecraft:main"}], "output": "swap",
             "uniforms": {"BodycamConfig": uniforms}},
            {"vertex_shader": "minecraft:core/screenquad", "fragment_shader": "minecraft:post/blit",
             "inputs": [{"sampler_name": "In", "target": "swap"}],
             "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}]},
             "output": "minecraft:main"}]})
    for recipe in sheets["recipes"]["rows"]:
        write_json(os.path.join(data, "recipe", recipe["id"] + ".json"), {
            "type": "minecraft:crafting_shapeless", "category": "equipment", "ingredients": recipe["ingredients"],
            "result": {"id": "%s:%s" % (MODID, recipe["result"]), "count": recipe["count"]}})
    print("generated Sheets.java, %d item models, lang, sounds.json, post effect, %d recipes" % (len(items), len(sheets["recipes"]["rows"])))


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "preflight"
    all_sheets = load()
    found = preflight(all_sheets)
    if command == "gen":
        gen(all_sheets)
    elif found:
        sys.exit(1)
