package gg.bodycamcraft.bodycam;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Reads LOD 0 of cooked StaticMesh and SkeletalMesh assets: positions, normals, first UV set and triangles. */
public final class MeshReader {
	/** Positions are in Unreal units (cm), three floats per vertex. */
	public record Mesh(float[] positions, float[] normals, float[] uvs, int[] indices) {
		public int vertexCount() {
			return positions.length / 3;
		}
	}

	private MeshReader() {
	}

	public static Mesh readStatic(PakArchive pak, String path) throws IOException {
		UPackage pkg = new UPackage(pak, path);
		UPackage.Export e = pkg.export(path.substring(path.lastIndexOf(47) + 1));
		ByteBuffer u = pkg.data;
		int end = e.offset() + e.size();
		int p = findPositions(u, e.offset(), end, path);
		int n = u.getInt(p + 4);
		Streams s = vertexStreams(u, p, n);
		int o = s.end + 2;
		int colours = u.getInt(o + 4);
		o += 8;
		if (colours > 0) {
			o += 8 + u.getInt(o) * u.getInt(o + 4);
		}
		boolean wide = u.getInt(o) != 0;
		int elem = u.getInt(o + 4), count = u.getInt(o + 8);
		o += 12;
		int width = elem == 2 || elem == 4 ? elem : (wide ? 4 : 2);
		int total = elem == 2 || elem == 4 ? count : count / width;
		return new Mesh(s.positions, s.normals, s.uvs, indices(u, o, total, width, n, path));
	}

	public static Mesh readSkeletal(PakArchive pak, String path) throws IOException {
		UPackage pkg = new UPackage(pak, path);
		UPackage.Export e = pkg.export(path.substring(path.lastIndexOf(47) + 1));
		ByteBuffer u = pkg.data;
		int p = findPositions(u, e.offset(), e.offset() + e.size(), path);
		int n = u.getInt(p + 4);
		// The index buffer sits right before the positions: [u8 width][i32 width][i32 count][data].
		for (int o = p - 9; o > e.offset(); o--) {
			int width = u.get(o);
			if ((width == 2 || width == 4) && u.getInt(o + 1) == width) {
				int count = u.getInt(o + 5);
				if (count > 0 && (long) o + 9 + (long) width * count == p) {
					Streams s = vertexStreams(u, p, n);
					return new Mesh(s.positions, s.normals, s.uvs, indices(u, o + 9, count, width, n, path));
				}
			}
		}
		throw new IOException("No index buffer in " + path);
	}

	private record Streams(float[] positions, float[] normals, float[] uvs, int end) {
	}

	private static int findPositions(ByteBuffer u, int start, int end, String path) throws IOException {
		for (int o = start; o < end - 16; o++) {
			if (u.getInt(o) == 12 && u.getInt(o + 8) == 12) {
				int n = u.getInt(o + 4);
				if (n > 3 && n < 2_000_000 && n == u.getInt(o + 12) && (long) o + 16 + 12L * n <= end) {
					return o;
				}
			}
		}
		throw new IOException("No vertex buffer in " + path);
	}

	private static Streams vertexStreams(ByteBuffer u, int p, int n) throws IOException {
		float[] pos = new float[n * 3];
		int o = p + 16;
		for (int i = 0; i < pos.length; i++, o += 4) {
			pos[i] = u.getFloat(o);
		}
		o += 2;
		int texCoords = u.getInt(o);
		boolean fullUv = u.getInt(o + 8) != 0;
		o += 16;
		int tanSize = u.getInt(o), tanCount = u.getInt(o + 4);
		o += 8;
		if (tanCount != n || (tanSize != 8 && tanSize != 16) || texCoords < 1) {
			throw new IOException("Unexpected vertex layout");
		}
		float[] normals = new float[n * 3];
		for (int i = 0; i < n; i++) {
			int at = o + i * tanSize + tanSize / 2;
			for (int k = 0; k < 3; k++) {
				normals[i * 3 + k] = tanSize == 8 ? u.get(at + k) / 127f : u.getShort(at + k * 2) / 32767f;
			}
		}
		o += tanSize * tanCount;
		int uvSize = u.getInt(o);
		int uvCount = u.getInt(o + 4);
		o += 8;
		float[] uvs = new float[n * 2];
		for (int i = 0; i < n; i++) {
			int at = o + i * texCoords * uvSize;
			if (fullUv) {
				uvs[i * 2] = u.getFloat(at);
				uvs[i * 2 + 1] = u.getFloat(at + 4);
			} else {
				uvs[i * 2] = Float.float16ToFloat(u.getShort(at));
				uvs[i * 2 + 1] = Float.float16ToFloat(u.getShort(at + 2));
			}
		}
		return new Streams(pos, normals, uvs, o + uvSize * uvCount);
	}

	private static int[] indices(ByteBuffer u, int o, int count, int width, int vertices, String path) throws IOException {
		int[] idx = new int[count - count % 3];
		for (int i = 0; i < idx.length; i++) {
			idx[i] = width == 4 ? u.getInt(o + i * 4) : u.getShort(o + i * 2) & 0xffff;
			if (idx[i] < 0 || idx[i] >= vertices) {
				throw new IOException("Bad triangle index in " + path);
			}
		}
		return idx;
	}
}
