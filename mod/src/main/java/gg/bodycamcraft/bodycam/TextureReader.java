package gg.bodycamcraft.bodycam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Reads a cooked Texture2D stored as a virtual texture (raw DXT1 or DXT5 tiles), which is how the weapon
 * textures are stored. The tiles of one mip level are stitched into a single image.
 */
public final class TextureReader {
	/** Pixels are ARGB, row by row from the top. */
	public record Image(int width, int height, int[] argb) {
	}

	private TextureReader() {
	}

	public static Image read(PakArchive pak, String path, int maxSize) throws IOException {
		ByteBuffer u = ByteBuffer.wrap(pak.read(path + ".uexp")).order(ByteOrder.LITTLE_ENDIAN);
		byte[] raw = u.array();
		int pf = indexOf(raw, "PF_".getBytes(StandardCharsets.US_ASCII));
		if (pf < 4) {
			throw new IOException("No pixel format in " + path);
		}
		int len = u.getInt(pf - 4);
		String format = new String(raw, pf, len - 1, StandardCharsets.US_ASCII);
		int blockBytes = switch (format) {
			case "PF_DXT1" -> 8;
			case "PF_DXT5" -> 16;
			default -> throw new IOException("Unsupported texture format " + format + " in " + path);
		};
		int o = pf + len;
		boolean virtual = u.getInt(o + 4) == 0 && u.getInt(o + 8) == 1;
		int layers = u.getInt(o + 16), tileSize = u.getInt(o + 28), border = u.getInt(o + 32);
		if (!virtual || layers != 1 || Integer.bitCount(tileSize) != 1 || tileSize < 32 || tileSize > 1024 || border > 16
				|| u.getInt(o + 36) != 1) {
			throw new IOException("Unsupported texture layout in " + path);
		}
		int tilePixels = tileSize + 2 * border;
		int tileBlocks = (tilePixels + 3) / 4;
		int tileBytes = tileBlocks * tileBlocks * blockBytes;
		if (u.getInt(o + 40) != tileBytes) {
			throw new IOException("Unexpected tile size in " + path);
		}
		int mips = u.getInt(o + 44), width = u.getInt(o + 48), height = u.getInt(o + 52);
		o += 56;
		int[] chunkOfMip = ints(u, o, mips);
		o += 4 + 4 * mips;
		int[] baseOfMip = ints(u, o, mips);
		o += 4 + 4 * mips;
		if (u.getInt(o) != mips) {
			throw new IOException("Unexpected tile table in " + path);
		}
		o += 4;
		int[] tilesWide = new int[mips], tilesHigh = new int[mips];
		for (int m = 0; m < mips; m++) {
			tilesWide[m] = u.getInt(o);
			tilesHigh[m] = u.getInt(o + 4);
			int addresses = u.getInt(o + 12);
			int offsets = u.getInt(o + 16 + 4 * addresses);
			if (addresses != 1 || offsets != 1 || u.getInt(o + 16) != 0 || u.getInt(o + 24) != 0) {
				throw new IOException("Sparse virtual texture is not supported: " + path);
			}
			o += 28;
		}
		// Chunks are stored back to back in the .ubulk; each ends after the last tile of its last mip.
		int chunks = 0;
		for (int c : chunkOfMip) {
			chunks = Math.max(chunks, c + 1);
		}
		long[] chunkSize = new long[chunks];
		for (int m = 0; m < mips; m++) {
			long end = baseOfMip[m] + (long) tilesWide[m] * tilesHigh[m] * tileBytes;
			chunkSize[chunkOfMip[m]] = Math.max(chunkSize[chunkOfMip[m]], end);
		}
		byte[] bulk = pak.read(path + ".ubulk");
		long total = 0;
		long[] chunkStart = new long[chunks];
		for (int c = 0; c < chunks; c++) {
			chunkStart[c] = total;
			total += chunkSize[c];
		}
		if (total != bulk.length) {
			throw new IOException("Texture data size mismatch in " + path);
		}

		int mip = 0;
		while (mip < mips - 1 && (Math.max(width, height) >> mip) > maxSize && (Math.min(width, height) >> (mip + 1)) >= tileSize) {
			mip++;
		}
		int w = width >> mip, h = height >> mip;
		int[] out = new int[w * h];
		int[] tile = new int[tileBlocks * 4 * tileBlocks * 4];
		int count = tilesWide[mip] * tilesHigh[mip];
		for (int address = 0; address < count; address++) {
			int tx = compact(address), ty = compact(address >> 1);
			if (tx >= tilesWide[mip] || ty >= tilesHigh[mip]) {
				continue;
			}
			int at = (int) (chunkStart[chunkOfMip[mip]] + baseOfMip[mip] + (long) address * tileBytes);
			decodeTile(bulk, at, tileBlocks, blockBytes == 16, tile);
			int stride = tileBlocks * 4;
			for (int y = 0; y < tileSize && ty * tileSize + y < h; y++) {
				int rows = Math.min(tileSize, w - tx * tileSize);
				if (rows > 0) {
					System.arraycopy(tile, (y + border) * stride + border, out, (ty * tileSize + y) * w + tx * tileSize, rows);
				}
			}
		}
		return new Image(w, h, out);
	}

	private static int[] ints(ByteBuffer u, int at, int expected) throws IOException {
		if (u.getInt(at) != expected) {
			throw new IOException("Unexpected mip table");
		}
		int[] v = new int[expected];
		for (int i = 0; i < expected; i++) {
			v[i] = u.getInt(at + 4 + 4 * i);
		}
		return v;
	}

	/** Even bits of a Morton code. */
	private static int compact(int v) {
		v &= 0x55555555;
		v = (v | (v >> 1)) & 0x33333333;
		v = (v | (v >> 2)) & 0x0f0f0f0f;
		v = (v | (v >> 4)) & 0x00ff00ff;
		v = (v | (v >> 8)) & 0x0000ffff;
		return v;
	}

	private static void decodeTile(byte[] d, int at, int blocks, boolean dxt5, int[] out) {
		int stride = blocks * 4;
		int[] c = new int[4];
		for (int by = 0; by < blocks; by++) {
			for (int bx = 0; bx < blocks; bx++) {
				int p = dxt5 ? at + 8 : at;
				int c0 = (d[p] & 0xff) | (d[p + 1] & 0xff) << 8, c1 = (d[p + 2] & 0xff) | (d[p + 3] & 0xff) << 8;
				c[0] = rgb565(c0);
				c[1] = rgb565(c1);
				if (c0 > c1 || dxt5) {
					c[2] = mix(c[0], c[1], 2, 1, 3);
					c[3] = mix(c[0], c[1], 1, 2, 3);
				} else {
					c[2] = mix(c[0], c[1], 1, 1, 2);
					c[3] = 0xff000000;
				}
				int bits = (d[p + 4] & 0xff) | (d[p + 5] & 0xff) << 8 | (d[p + 6] & 0xff) << 16 | (d[p + 7] & 0xff) << 24;
				for (int i = 0; i < 16; i++) {
					out[(by * 4 + i / 4) * stride + bx * 4 + i % 4] = c[(bits >>> (i * 2)) & 3];
				}
				at += dxt5 ? 16 : 8;
			}
		}
	}

	private static int rgb565(int v) {
		int r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
		return 0xff000000 | (r * 255 / 31) << 16 | (g * 255 / 63) << 8 | (b * 255 / 31);
	}

	private static int mix(int a, int b, int wa, int wb, int div) {
		int r = (((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb) / div;
		int g = (((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb) / div;
		int bl = ((a & 255) * wa + (b & 255) * wb) / div;
		return 0xff000000 | r << 16 | g << 8 | bl;
	}

	private static int indexOf(byte[] data, byte[] needle) {
		outer:
		for (int i = 0; i <= data.length - needle.length; i++) {
			for (int k = 0; k < needle.length; k++) {
				if (data[i + k] != needle[k]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
