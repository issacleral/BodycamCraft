package gg.bodycamcraft.bodycam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Read-only view of the legacy v11 {@code .pak} files in the Bodycam install on this PC. */
public final class PakArchive {
	private static final int MAGIC = 0x5A6F12E1;
	private static final int FOOTER_SIZE = 221;

	public record Entry(Path pak, long offset, long size, long uncompressedSize, int method, boolean encrypted,
			int blockSize, int[] blocks, int headerSize) {
	}

	private final Map<String, Entry> entries = new HashMap<>();

	public static PakArchive open(Path paksDir) throws IOException {
		PakArchive archive = new PakArchive();
		List<Path> paks;
		try (Stream<Path> files = Files.list(paksDir)) {
			paks = files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pak")).sorted().toList();
		}
		if (paks.isEmpty()) {
			throw new IOException("No .pak files in " + paksDir);
		}
		for (Path pak : paks) {
			archive.readIndex(pak);
		}
		return archive;
	}

	public int size() {
		return entries.size();
	}

	public boolean has(String path) {
		return entries.containsKey(key(path));
	}

	public byte[] read(String path) throws IOException {
		Entry e = entries.get(key(path));
		if (e == null) {
			throw new IOException("Not in the Bodycam files: " + path);
		}
		if (e.encrypted) {
			throw new IOException("Encrypted entry: " + path);
		}
		if (e.uncompressedSize > Integer.MAX_VALUE - 16) {
			throw new IOException("Too large: " + path);
		}
		try (FileChannel ch = FileChannel.open(e.pak, StandardOpenOption.READ)) {
			byte[] out = new byte[(int) e.uncompressedSize];
			long pos = e.offset + e.headerSize;
			if (e.method == 0) {
				readFully(ch, ByteBuffer.wrap(out), pos);
				return out;
			}
			int blockSize = e.blockSize > 0 ? e.blockSize : out.length;
			int written = 0;
			for (int compressed : e.blocks) {
				int n = Math.min(blockSize, out.length - written);
				byte[] src = new byte[compressed];
				readFully(ch, ByteBuffer.wrap(src), pos);
				pos += compressed;
				byte[] dst = new byte[n];
				long r = NativeCodecs.oodle(src, dst);
				if (r != n) {
					if (compressed != n) {
						throw new IOException("Could not decompress " + path);
					}
					dst = src;
				}
				System.arraycopy(dst, 0, out, written, n);
				written += n;
			}
			return out;
		}
	}

	private static String key(String path) {
		return path.toLowerCase(Locale.ROOT);
	}

	private void readIndex(Path pak) throws IOException {
		try (FileChannel ch = FileChannel.open(pak, StandardOpenOption.READ)) {
			long fileSize = ch.size();
			if (fileSize < FOOTER_SIZE) {
				return;
			}
			ByteBuffer footer = le(FOOTER_SIZE);
			readFully(ch, footer, fileSize - FOOTER_SIZE);
			if (footer.getInt(17) != MAGIC || footer.getInt(21) != 11) {
				throw new IOException("Unsupported pak format: " + pak.getFileName());
			}
			if (footer.get(16) != 0) {
				throw new IOException("Encrypted pak index: " + pak.getFileName());
			}
			long indexOffset = footer.getLong(25);
			int indexSize = (int) footer.getLong(33);
			ByteBuffer index = le(indexSize);
			readFully(ch, index, indexOffset);
			String mount = string(index);
			index.getInt();
			index.getLong();
			if (index.getInt() != 0) {
				index.position(index.position() + 36);
			}
			if (index.getInt() == 0) {
				throw new IOException("Pak has no directory index: " + pak.getFileName());
			}
			long dirOffset = index.getLong();
			int dirSize = (int) index.getLong();
			index.position(index.position() + 20);
			int encodedSize = index.getInt();
			ByteBuffer encoded = index.slice(index.position(), encodedSize).order(ByteOrder.LITTLE_ENDIAN);

			ByteBuffer dir = le(dirSize);
			readFully(ch, dir, dirOffset);
			String root = mount.replace("../../../", "");
			int dirs = dir.getInt();
			for (int d = 0; d < dirs; d++) {
				String dirName = string(dir);
				int files = dir.getInt();
				for (int f = 0; f < files; f++) {
					String fileName = string(dir);
					int at = dir.getInt();
					if (at >= 0) {
						entries.put(key(root + dirName + fileName), decode(pak, encoded, at));
					}
				}
			}
		}
	}

	private static Entry decode(Path pak, ByteBuffer b, int at) {
		b.position(at);
		int v = b.getInt();
		int blockSize = v & 0x3f;
		blockSize = blockSize == 0x3f ? b.getInt() : blockSize << 11;
		int blockCount = (v >>> 6) & 0xffff;
		boolean encrypted = ((v >>> 22) & 1) != 0;
		int method = (v >>> 23) & 0x3f;
		long offset = (v >>> 31) != 0 ? Integer.toUnsignedLong(b.getInt()) : b.getLong();
		long usize = ((v >>> 30) & 1) != 0 ? Integer.toUnsignedLong(b.getInt()) : b.getLong();
		long size = usize;
		if (method != 0) {
			size = ((v >>> 29) & 1) != 0 ? Integer.toUnsignedLong(b.getInt()) : b.getLong();
		}
		int header = 53 + (method != 0 ? 4 + 16 * blockCount : 0);
		int[] blocks;
		if (blockCount == 1 && !encrypted) {
			blocks = new int[] {(int) size};
		} else {
			blocks = new int[blockCount];
			for (int i = 0; i < blockCount; i++) {
				blocks[i] = b.getInt();
			}
		}
		return new Entry(pak, offset, size, usize, method, encrypted, blockSize, blocks, header);
	}

	private static String string(ByteBuffer b) {
		int n = b.getInt();
		if (n >= 0) {
			byte[] raw = new byte[n];
			b.get(raw);
			return new String(raw, 0, Math.max(0, n - 1), StandardCharsets.ISO_8859_1);
		}
		byte[] raw = new byte[-2 * n];
		b.get(raw);
		return new String(raw, 0, raw.length - 2, StandardCharsets.UTF_16LE);
	}

	private static ByteBuffer le(int size) {
		return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static void readFully(FileChannel ch, ByteBuffer dst, long pos) throws IOException {
		while (dst.hasRemaining()) {
			int n = ch.read(dst, pos);
			if (n < 0) {
				throw new IOException("Unexpected end of pak file");
			}
			pos += n;
		}
		dst.flip();
	}
}
