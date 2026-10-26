package gg.bodycamcraft.bodycam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads what first-person arms need from Bodycam: a skeleton with its virtual bones, ACL-compressed AnimSequences,
 * and a SkeletalMesh with its skin weights. Transforms are 7 floats: rotation x y z w, translation x y z (cm).
 */
public final class RigReader {
	/** Bones in parent-first order. Names are lower case. */
	public record Bones(String[] names, int[] parents, float[][] local) {
		public int index(String name) {
			String key = name.toLowerCase(Locale.ROOT);
			for (int i = 0; i < names.length; i++) {
				if (names[i].equals(key)) {
					return i;
				}
			}
			return -1;
		}
	}

	/**
	 * One animation: {@code data} holds samples x tracks x 10 floats (rotation, translation, scale); NaN means the
	 * bone keeps its reference value there. {@code trackBones} maps each track to a skeleton bone index.
	 */
	public record Anim(String path, int[] trackBones, int samples, float rate, float[] data) {
		public float seconds() {
			return samples > 1 ? (samples - 1) / rate : 0;
		}
	}

	/** A range of triangles drawn with one material, and the vertices it owns. */
	public record Section(int material, int firstTriangle, int triangles, int firstVertex, int vertices) {
	}

	/** A skinned mesh: four influences per vertex, as indices into {@code bones} and weights that sum to 1. */
	public record Skinned(MeshReader.Mesh mesh, List<Section> sections, int[] influenceBones, float[] influenceWeights, Bones bones) {
	}

	private RigReader() {
	}

	/** The reference skeleton of a Skeleton asset, with its virtual bones appended in the order animations use. */
	public static Bones skeleton(PakArchive pak, String path) throws IOException {
		UPackage pkg = new UPackage(pak, path);
		UPackage.Export e = pkg.export(path.substring(path.lastIndexOf('/') + 1));
		Bones raw = reference(pkg, e, path);
		return withVirtualBones(pkg, e, raw);
	}

	/** An AnimSequence: the track-to-bone table and the decoded ACL bone tracks. */
	public static Anim anim(PakArchive pak, String path) throws IOException {
		UPackage pkg = new UPackage(pak, path);
		UPackage.Export e = pkg.export(path.substring(path.lastIndexOf('/') + 1));
		ByteBuffer u = pkg.data;
		byte[] all = u.array();
		int end = e.offset() + e.size();
		for (int tag = e.offset() + 8; tag < end - 32; tag++) {
			if (u.getInt(tag) != 0xac11ac11) {
				continue;
			}
			int size = u.getInt(tag - 8);
			if (size < 32 || tag - 8 + size > end) {
				continue;
			}
			byte[] buffer = Arrays.copyOfRange(all, tag - 8, tag - 8 + size);
			int[] info = new int[4];
			float[] rate = new float[1];
			if (NativeCodecs.aclInfo(buffer, info, rate) != 0 || info[2] != 10) {
				continue;
			}
			int tracks = info[0], samples = info[1];
			int[] table = trackTable(u, e.offset(), tag - 8, tracks);
			if (table == null) {
				throw new IOException("No track table in " + path);
			}
			float[] data = new float[tracks * samples * 10];
			long written = NativeCodecs.aclDecode(buffer, data);
			if (written != data.length) {
				throw new IOException("ACL decode failed (" + written + ") in " + path);
			}
			return new Anim(path, table, samples, rate[0], data);
		}
		throw new IOException("No ACL bone tracks in " + path);
	}

	/** A SkeletalMesh with its sections, skin weights and own reference skeleton. */
	public static Skinned skinned(PakArchive pak, String path) throws IOException {
		MeshReader.Mesh mesh = MeshReader.readSkeletal(pak, path);
		UPackage pkg = new UPackage(pak, path);
		UPackage.Export e = pkg.export(path.substring(path.lastIndexOf('/') + 1));
		ByteBuffer u = pkg.data;
		int end = e.offset() + e.size();
		Bones bones = reference(pkg, e, path);
		int n = mesh.vertexCount();
		int triangles = mesh.indices().length / 3;

		// Skin weights follow the UVs: [u32 variable][u32 influences][u32][u32 vertices][u32][u32][u32 1][u32 bytes][data].
		int p = MeshReader.findPositions(u, e.offset(), end, path);
		int after = MeshReader.vertexStreams(u, p, n).end();
		int weights = -1, influences = 0;
		for (int o = after; o < after + 64 && weights < 0; o++) {
			int k = u.getInt(o);
			if ((k == 4 || k == 8) && u.getInt(o + 8) == n && u.getInt(o + 20) == 1 && u.getInt(o + 24) == n * k * 2) {
				weights = o + 28;
				influences = k;
			}
		}
		if (weights < 0) {
			throw new IOException("No skin weights in " + path);
		}

		// Sections: [u16 strip][u16 material][u32 first index][u32 triangles][u32][9 bytes][u32 first vertex]
		// [u32 cloth][u32 n][u16 bone map * n][u32 vertices]. Pick the chain that covers every vertex and triangle.
		List<int[]> candidates = new ArrayList<>();
		Map<Integer, short[]> boneMaps = new HashMap<>();
		for (int h = e.offset(); h < end - 48; h++) {
			int material = u.getShort(h + 2) & 0xffff, first = u.getInt(h + 4), count = u.getInt(h + 8);
			int firstVertex = u.getInt(h + 25), cloth = u.getInt(h + 29), mapSize = u.getInt(h + 33);
			if (material > 32 || cloth != 0 || mapSize < 1 || mapSize > 256 || count <= 0 || count > triangles || first < 0
					|| first % 3 != 0 || first / 3 + count > triangles || firstVertex < 0 || firstVertex >= n || h + 41 + 2 * mapSize > end) {
				continue;
			}
			short[] map = new short[mapSize];
			boolean ok = true;
			for (int i = 0; i < mapSize && ok; i++) {
				map[i] = u.getShort(h + 37 + 2 * i);
				ok = map[i] >= 0 && map[i] < bones.names().length;
				for (int j = 0; j < i && ok; j++) {
					ok = map[j] != map[i];
				}
			}
			int vertices = u.getInt(h + 37 + 2 * mapSize);
			if (ok && vertices > 0 && firstVertex + vertices <= n) {
				candidates.add(new int[] {material, first / 3, count, firstVertex, vertices, h});
				boneMaps.put(h, map);
			}
		}
		List<int[]> chain = chain(candidates, 0, 0, 0, n, triangles);
		if (chain == null) {
			throw new IOException("No section layout in " + path);
		}
		List<Section> sections = new ArrayList<>();
		int[] influenceBones = new int[n * 4];
		float[] influenceWeights = new float[n * 4];
		for (int[] s : chain) {
			sections.add(new Section(s[0], s[1], s[2], s[3], s[4]));
			short[] map = boneMaps.get(s[5]);
			for (int v = s[3]; v < s[3] + s[4]; v++) {
				int at = weights + v * influences * 2;
				int total = 0;
				for (int k = 0; k < 4; k++) {
					total += u.get(at + influences + k) & 0xff;
				}
				for (int k = 0; k < 4; k++) {
					int local = u.get(at + k) & 0xff;
					influenceBones[v * 4 + k] = local < map.length ? map[local] : 0;
					influenceWeights[v * 4 + k] = total == 0 ? (k == 0 ? 1 : 0) : (u.get(at + influences + k) & 0xff) / (float) total;
				}
			}
		}
		return new Skinned(mesh, sections, influenceBones, influenceWeights, bones);
	}

	private static List<int[]> chain(List<int[]> candidates, int material, int firstTriangle, int firstVertex, int vertices, int triangles) {
		if (firstVertex == vertices && firstTriangle == triangles) {
			return new ArrayList<>();
		}
		for (int[] c : candidates) {
			if (c[0] == material && c[1] == firstTriangle && c[3] == firstVertex) {
				List<int[]> rest = chain(candidates, material + 1, firstTriangle + c[2], firstVertex + c[4], vertices, triangles);
				if (rest != null) {
					rest.add(0, c);
					return rest;
				}
			}
		}
		return null;
	}

	/** The int32 track count and per-track bone indices that sit in front of the ACL buffer. */
	private static int[] trackTable(ByteBuffer u, int start, int stop, int tracks) {
		for (int o = start; o + 4 + 4L * tracks <= stop; o++) {
			if (u.getInt(o) != tracks) {
				continue;
			}
			int[] table = new int[tracks];
			boolean ok = true;
			for (int i = 0; i < tracks && ok; i++) {
				table[i] = u.getInt(o + 4 + 4 * i);
				ok = table[i] >= 0 && table[i] < 1024;
			}
			if (ok) {
				return table;
			}
		}
		return null;
	}

	/** The reference skeleton is [n][name, parent]*n then [n][transform as 10 doubles]*n; find it by its run of unit quaternions. */
	static Bones reference(UPackage pkg, UPackage.Export e, String path) throws IOException {
		ByteBuffer u = pkg.data;
		int end = e.offset() + e.size();
		for (int t = e.offset() + 20; t < end - 80; t++) {
			int n = u.getInt(t - 4);
			if (n < 1 || n > 512 || (long) t + 80L * n > end || !unitTransform(u, t)) {
				continue;
			}
			int infos = t - 4 - 12 * n;
			if (infos - 4 < e.offset() || u.getInt(infos - 4) != n) {
				continue;
			}
			boolean ok = true;
			for (int i = 1; i < n && ok; i++) {
				ok = unitTransform(u, t + i * 80);
			}
			if (!ok) {
				continue;
			}
			String[] names = new String[n];
			int[] parents = new int[n];
			float[][] local = new float[n][];
			for (int i = 0; i < n; i++) {
				names[i] = pkg.fname(infos + i * 12).toLowerCase(Locale.ROOT);
				parents[i] = u.getInt(infos + i * 12 + 8);
				local[i] = new float[7];
				for (int k = 0; k < 7; k++) {
					local[i][k] = (float) u.getDouble(t + i * 80 + k * 8);
				}
			}
			return new Bones(names, parents, local);
		}
		throw new IOException("No reference skeleton in " + path);
	}

	private static boolean unitTransform(ByteBuffer u, int at) {
		double len = 0;
		for (int i = 0; i < 4; i++) {
			double q = u.getDouble(at + i * 8);
			if (!(Math.abs(q) <= 1.0001)) {
				return false;
			}
			len += q * q;
		}
		if (Math.abs(len - 1) > 1e-4) {
			return false;
		}
		for (int i = 4; i < 10; i++) {
			if (!(Math.abs(u.getDouble(at + i * 8)) < 1e5)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Virtual bones are stored as 26-byte elements [2 bytes][source][target][name], names starting "VB ". A virtual
	 * bone hangs off its source; its reference transform is the target's, seen from the source.
	 */
	private static Bones withVirtualBones(UPackage pkg, UPackage.Export e, Bones raw) {
		ByteBuffer u = pkg.data;
		int end = e.offset() + e.size();
		int bestStart = -1, bestCount = 0;
		for (int o = e.offset(); o < end - 26; o++) {
			int count = 0;
			List<String> known = new ArrayList<>(Arrays.asList(raw.names()));
			while (o + 26 * (count + 1) <= end) {
				String[] vb = virtualBone(pkg, u, o + 26 * count);
				if (vb == null || !known.contains(vb[0]) || !known.contains(vb[1])) {
					break;
				}
				known.add(vb[2]);
				count++;
			}
			if (count > bestCount) {
				bestCount = count;
				bestStart = o;
			}
		}
		if (bestCount == 0) {
			return raw;
		}
		int n = raw.names().length;
		String[] names = Arrays.copyOf(raw.names(), n + bestCount);
		int[] parents = Arrays.copyOf(raw.parents(), n + bestCount);
		float[][] local = Arrays.copyOf(raw.local(), n + bestCount);
		float[][] world = new float[n + bestCount][];
		for (int i = 0; i < n; i++) {
			world[i] = parents[i] < 0 ? local[i].clone() : Xf.mul(world[parents[i]], local[i]);
		}
		for (int k = 0; k < bestCount; k++) {
			String[] vb = virtualBone(pkg, u, bestStart + 26 * k);
			int source = indexOf(names, vb[0], n + k), target = indexOf(names, vb[1], n + k);
			names[n + k] = vb[2];
			parents[n + k] = source;
			local[n + k] = Xf.mul(Xf.inverse(world[source]), world[target]);
			world[n + k] = world[target].clone();
		}
		return new Bones(names, parents, local);
	}

	private static String[] virtualBone(UPackage pkg, ByteBuffer u, int o) {
		String[] out = new String[3];
		for (int k = 0; k < 3; k++) {
			int index = u.getInt(o + 2 + 8 * k), number = u.getInt(o + 6 + 8 * k);
			if (index < 0 || index >= pkg.names.size() || number != 0) {
				return null;
			}
			out[k] = pkg.names.get(index).toLowerCase(Locale.ROOT);
		}
		return out[2].startsWith("vb ") ? out : null;
	}

	private static int indexOf(String[] names, String name, int limit) {
		for (int i = 0; i < limit; i++) {
			if (name.equals(names[i])) {
				return i;
			}
		}
		throw new IllegalStateException("Unknown bone " + name);
	}

	/** Rigid transforms as 7 floats: quaternion x y z w, translation x y z. */
	public static final class Xf {
		private Xf() {
		}

		/** a then b: the transform of b's space into a's parent space. */
		public static float[] mul(float[] a, float[] b) {
			float[] q = qmul(a, b);
			float[] t = rotate(a, b[4], b[5], b[6]);
			return new float[] {q[0], q[1], q[2], q[3], a[4] + t[0], a[5] + t[1], a[6] + t[2]};
		}

		public static float[] inverse(float[] a) {
			float[] c = {-a[0], -a[1], -a[2], a[3], 0, 0, 0};
			float[] t = rotate(c, -a[4], -a[5], -a[6]);
			c[4] = t[0];
			c[5] = t[1];
			c[6] = t[2];
			return c;
		}

		public static float[] qmul(float[] a, float[] b) {
			return new float[] {
				a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
				a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
				a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
				a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
		}

		public static float[] rotate(float[] q, float x, float y, float z) {
			float qx = q[0], qy = q[1], qz = q[2], qw = q[3];
			float tx = 2 * (qy * z - qz * y), ty = 2 * (qz * x - qx * z), tz = 2 * (qx * y - qy * x);
			return new float[] {x + qw * tx + (qy * tz - qz * ty), y + qw * ty + (qz * tx - qx * tz), z + qw * tz + (qx * ty - qy * tx)};
		}
	}
}
