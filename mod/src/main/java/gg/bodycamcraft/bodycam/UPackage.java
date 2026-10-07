package gg.bodycamcraft.bodycam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Names, imports and exports of one cooked, unversioned UE5 package ({@code .uasset} + {@code .uexp}). */
public final class UPackage {
	public record Import(String className, int outer, String name) {
	}

	public record Export(int classIndex, String name, int offset, int size) {
	}

	public final String path;
	public final List<String> names = new ArrayList<>();
	public final List<Import> imports = new ArrayList<>();
	public final List<Export> exports = new ArrayList<>();
	/** The export data ({@code .uexp}). */
	public final ByteBuffer data;

	public UPackage(PakArchive pak, String path) throws IOException {
		this.path = path;
		ByteBuffer a = ByteBuffer.wrap(pak.read(path + ".uasset")).order(ByteOrder.LITTLE_ENDIAN);
		this.data = ByteBuffer.wrap(pak.read(path + ".uexp")).order(ByteOrder.LITTLE_ENDIAN);
		if (a.getInt(0) != 0x9E2A83C1) {
			throw new IOException("Not a UE package: " + path);
		}
		int o = 24;
		o += 4 + a.getInt(o) * 20;
		int headerSize = a.getInt(o);
		o += 4;
		o += 4 + a.getInt(o);
		o += 4;
		int nameCount = a.getInt(o), nameOffset = a.getInt(o + 4);
		int exportCount = a.getInt(o + 24), exportOffset = a.getInt(o + 28);
		int importCount = a.getInt(o + 32), importOffset = a.getInt(o + 36);
		int dependsOffset = a.getInt(o + 40);

		int q = nameOffset;
		for (int i = 0; i < nameCount; i++) {
			int len = a.getInt(q);
			q += 4;
			if (len >= 0) {
				names.add(new String(a.array(), q, Math.max(0, len - 1), StandardCharsets.ISO_8859_1));
				q += len;
			} else {
				names.add(new String(a.array(), q, -2 * len - 2, StandardCharsets.UTF_16LE));
				q += -2 * len;
			}
			q += 4;
		}
		int importSize = importCount > 0 ? (exportOffset - importOffset) / importCount : 0;
		for (int i = 0; i < importCount; i++) {
			q = importOffset + i * importSize;
			imports.add(new Import(names.get(a.getInt(q + 8)), a.getInt(q + 16), name(a, q + 20)));
		}
		int exportSize = exportCount > 0 ? (dependsOffset - exportOffset) / exportCount : 0;
		for (int i = 0; i < exportCount; i++) {
			q = exportOffset + i * exportSize;
			exports.add(new Export(a.getInt(q), name(a, q + 16), (int) (a.getLong(q + 36) - headerSize), (int) a.getLong(q + 28)));
		}
	}

	private String name(ByteBuffer b, int at) {
		int number = b.getInt(at + 4);
		String base = names.get(b.getInt(at));
		return number == 0 ? base : base + "_" + (number - 1);
	}

	/** Reads an FName stored in the export data. */
	public String fname(int at) {
		return name(data, at);
	}

	public Export export(String name) throws IOException {
		for (Export e : exports) {
			if (e.name.equalsIgnoreCase(name)) {
				return e;
			}
		}
		throw new IOException("No export " + name + " in " + path);
	}

	public String exportClass(Export e) {
		return e.classIndex < 0 ? imports.get(-e.classIndex - 1).name : "";
	}

	/** Game path ("/Game/...") of the package an imported object lives in. */
	public String importPackage(Import im) {
		while (im.outer < 0) {
			im = imports.get(-im.outer - 1);
		}
		return im.name;
	}

	/** Turns "/Game/A/B" into the pak path "Bodycam/Content/A/B". */
	public static String pakPath(String gamePath) {
		return gamePath.startsWith("/Game/") ? "Bodycam/Content/" + gamePath.substring(6) : gamePath;
	}
}
