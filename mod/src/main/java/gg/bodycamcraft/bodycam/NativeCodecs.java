package gg.bodycamcraft.bodycam;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The two small native decoders the Bodycam files need: Oodle blocks (bcoodle.dll, built from the MIT oozextract
 * crate) and Bink Audio (bcbinka.dll, built from the ISC decoder in vgmstream). Both ship inside the mod jar.
 */
public final class NativeCodecs {
	private static Function oodle;
	private static Function binka;

	private NativeCodecs() {
	}

	public static synchronized void load(Path dir) throws IOException {
		if (oodle != null) {
			return;
		}
		Files.createDirectories(dir);
		oodle = NativeLibrary.getInstance(unpack(dir, "bcoodle.dll").toString()).getFunction("bcoodle_decompress");
		binka = NativeLibrary.getInstance(unpack(dir, "bcbinka.dll").toString()).getFunction("bcbinka_decode");
	}

	private static Path unpack(Path dir, String name) throws IOException {
		Path out = dir.resolve(name);
		try (InputStream in = NativeCodecs.class.getResourceAsStream("/natives/" + name)) {
			if (in == null) {
				throw new IOException("Missing native library in the mod jar: " + name);
			}
			try {
				Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				// Already loaded by another running copy of the game: use the file that is there.
				if (!Files.exists(out)) {
					throw e;
				}
			}
		}
		return out;
	}

	/** Decompresses one Oodle block into {@code dst}; returns the bytes written or -1. */
	static long oodle(byte[] src, byte[] dst) {
		return oodle.invokeLong(new Object[] {src, (long) src.length, dst, (long) dst.length});
	}

	/** Decodes a UE Bink Audio packet stream to interleaved 16-bit PCM; returns sample frames or a negative error. */
	static int binka(byte[] packets, int sampleRate, int channels, short[] dst, int maxFrames) {
		return binka.invokeInt(new Object[] {packets, packets.length, sampleRate, channels, dst, maxFrames});
	}
}
