package gg.bodycamcraft.bodycam;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Reads the round count of a magazine from the DT_MAG data table (rows of the STR_MAG struct). */
public final class MagTable {
	private MagTable() {
	}

	/** Rounds per magazine for the given row name, e.g. "Glock17". */
	public static int capacity(PakArchive pak, String tablePath, String row) throws IOException {
		UPackage pkg = new UPackage(pak, tablePath);
		UPackage.Export e = pkg.export(tablePath.substring(tablePath.lastIndexOf(47) + 1));
		ByteBuffer u = pkg.data;
		int end = e.offset() + e.size();
		// Table header: [2-byte property header][row struct][4 bytes][row count], then the rows.
		int o = e.offset() + 10;
		int rows = u.getInt(o);
		o += 4;
		if (rows < 1 || rows > 4096) {
			throw new IOException("Unexpected magazine table layout");
		}
		for (int r = 0; r < rows; r++) {
			if (o + 10 > end) {
				break;
			}
			String name = pkg.fname(o);
			// Row: header, MagMesh and BulletMesh (soft object paths), Bullet, ProjectilePellet, ShellInfo, FallingSounds.
			if ((u.getShort(o + 8) & 0xffff) != 0x0d00) {
				throw new IOException("Unexpected magazine row layout at " + name);
			}
			o += 10;
			o = softPath(u, softPath(u, o));
			int bullets = u.getInt(o);
			if (name.equalsIgnoreCase(row)) {
				if (bullets < 1 || bullets > 1000) {
					throw new IOException("Implausible magazine size for " + row + ": " + bullets);
				}
				return bullets;
			}
			o += 8 + 2 + 4 + 8 + 4;
		}
		throw new IOException("No magazine row named " + row);
	}

	private static int softPath(ByteBuffer u, int o) throws IOException {
		int len = u.getInt(o + 16);
		if (len < 0 || len > 1024) {
			throw new IOException("Unexpected soft object path");
		}
		return o + 20 + len;
	}
}
