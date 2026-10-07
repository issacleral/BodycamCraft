package gg.bodycamcraft.bodycam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Decodes a cooked SoundWave (Bink Audio, "ABEU" container) to 16-bit PCM. */
public final class SoundReader {
	/** Interleaved 16-bit samples. */
	public record Pcm(int sampleRate, int channels, short[] samples) {
		public int frames() {
			return samples.length / channels;
		}
	}

	private SoundReader() {
	}

	public static Pcm read(PakArchive pak, String path) throws IOException {
		byte[] uexp = pak.read(path + ".uexp");
		int h = indexOf(uexp, "ABEU".getBytes(StandardCharsets.US_ASCII));
		if (h < 0) {
			throw new IOException("Not a Bink Audio sound: " + path);
		}
		ByteBuffer u = ByteBuffer.wrap(uexp).order(ByteOrder.LITTLE_ENDIAN);
		int version = u.get(h + 4), channels = u.get(h + 5);
		int rate = u.getInt(h + 8), frames = u.getInt(h + 12);
		int seekEntries = u.getShort(h + 0x18) & 0xffff;
		if (version != 1 || channels < 1 || channels > 2 || frames <= 0 || frames > 48000 * 600) {
			throw new IOException("Unsupported Bink Audio header in " + path);
		}
		// Chunk 0 (header, sometimes with the first packets) is inline; the rest is in the .ubulk.
		int inlineStart = h + 0x1c + seekEntries * 2;
		int inlineLen = 0;
		if (inlineStart + 4 <= uexp.length && (isPacket(uexp, inlineStart))) {
			inlineLen = packetsLength(uexp, inlineStart);
		}
		byte[] bulk = pak.has(path + ".ubulk") ? pak.read(path + ".ubulk") : new byte[0];
		byte[] packets = new byte[inlineLen + bulk.length];
		System.arraycopy(uexp, inlineStart, packets, 0, inlineLen);
		System.arraycopy(bulk, 0, packets, inlineLen, bulk.length);
		short[] out = new short[frames * channels];
		int done = NativeCodecs.binka(packets, rate, channels, out, frames);
		if (done <= 0) {
			throw new IOException("Could not decode " + path + " (" + done + ")");
		}
		if (done < frames) {
			short[] cut = new short[done * channels];
			System.arraycopy(out, 0, cut, 0, cut.length);
			out = cut;
		}
		return new Pcm(rate, channels, out);
	}

	private static boolean isPacket(byte[] d, int at) {
		return (d[at] == (byte) 0x99 && d[at + 1] == (byte) 0x99) || (d[at] == 0x53 && d[at + 1] == 0x45 && d[at + 2] == 0x45 && d[at + 3] == 0x4b);
	}

	/** Length of the run of whole packets and SEEK chunks starting at {@code at}. */
	private static int packetsLength(byte[] d, int at) {
		int o = at;
		while (o + 4 <= d.length && isPacket(d, o)) {
			int next;
			if (d[o] == 0x53) {
				if (o + 0x0f > d.length) {
					break;
				}
				int entries = (d[o + 0x0b] & 0xff) | (d[o + 0x0c] & 0xff) << 8 | (d[o + 0x0d] & 0xff) << 16 | (d[o + 0x0e] & 0xff) << 24;
				if (entries <= 0) {
					break;
				}
				next = o + 0x0f + entries * 2;
			} else {
				int size = (d[o + 2] & 0xff) | (d[o + 3] & 0xff) << 8;
				next = o + 4;
				if (size == 0xffff) {
					if (next + 4 > d.length) {
						break;
					}
					size = (d[next] & 0xff) | (d[next + 1] & 0xff) << 8;
					next += 4;
				}
				next += size;
			}
			if (next > d.length) {
				break;
			}
			o = next;
		}
		return o - at;
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
