package gg.bodycamcraft.bodycam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Reads the reference pose and the sockets of a cooked Skeleton asset, enough to place weapon parts. */
public final class SkeletonReader {
	/** Socket positions in the space of the skeletal mesh, in Unreal units (cm), keyed by lower-case name. */
	public record Skeleton(Map<String, double[]> sockets, Map<String, double[]> bones) {
		public double[] position(String name) throws IOException {
			String key = name.toLowerCase(Locale.ROOT);
			double[] at = sockets.get(key);
			if (at == null) {
				at = bones.get(key);
			}
			if (at == null) {
				throw new IOException("No socket or bone named " + name);
			}
			return at;
		}
	}

	private SkeletonReader() {
	}

	public static Skeleton read(PakArchive pak, String path) throws IOException {
		UPackage pkg = new UPackage(pak, path);
		UPackage.Export e = pkg.export(path.substring(path.lastIndexOf(47) + 1));
		ByteBuffer u = pkg.data;
		int end = e.offset() + e.size();
		// The reference skeleton is [n][name, parent]*n [n][transform]*n; find it by its run of unit quaternions.
		for (int t = e.offset() + 20; t < end - 80; t++) {
			int n = u.getInt(t - 4);
			if (n < 1 || n > 512 || (long) t + 80L * n > end || !transform(u, t)) {
				continue;
			}
			int infos = t - 4 - 12 * n;
			if (infos - 4 < e.offset() || u.getInt(infos - 4) != n) {
				continue;
			}
			boolean ok = true;
			for (int i = 1; i < n && ok; i++) {
				ok = transform(u, t + i * 80);
			}
			if (ok) {
				return build(pkg, infos, t, n);
			}
		}
		throw new IOException("No reference skeleton in " + path);
	}

	private static boolean transform(ByteBuffer u, int at) {
		double len = 0;
		for (int i = 0; i < 4; i++) {
			double q = u.getDouble(at + i * 8);
			len += q * q;
		}
		if (Math.abs(len - 1) > 1e-4) {
			return false;
		}
		for (int i = 4; i < 10; i++) {
			double v = u.getDouble(at + i * 8);
			if (!(Math.abs(v) < 1e5)) {
				return false;
			}
		}
		return true;
	}

	private static Skeleton build(UPackage pkg, int infos, int transforms, int n) {
		ByteBuffer u = pkg.data;
		double[][] rot = new double[n][];
		double[][] pos = new double[n][];
		Map<String, double[]> bones = new HashMap<>();
		Map<String, Integer> index = new HashMap<>();
		for (int i = 0; i < n; i++) {
			int parent = u.getInt(infos + i * 12 + 8);
			int t = transforms + i * 80;
			double[] q = {u.getDouble(t), u.getDouble(t + 8), u.getDouble(t + 16), u.getDouble(t + 24)};
			double[] p = {u.getDouble(t + 32), u.getDouble(t + 40), u.getDouble(t + 48)};
			if (parent >= 0 && parent < i) {
				p = add(pos[parent], rotate(rot[parent], p));
				q = multiply(rot[parent], q);
			}
			rot[i] = q;
			pos[i] = p;
			String name = pkg.fname(infos + i * 12).toLowerCase(Locale.ROOT);
			bones.put(name, p);
			index.put(name, i);
		}
		Map<String, double[]> sockets = new HashMap<>();
		for (UPackage.Export s : pkg.exports) {
			if (!pkg.exportClass(s).equals("SkeletalMeshSocket") || s.size() < 46) {
				continue;
			}
			int o = s.offset();
			int header = u.getShort(o) & 0xffff;
			if ((header & 0xff) != 0 || ((header >> 9) & 0x7f) < 3) {
				continue;
			}
			Integer bone = index.get(pkg.fname(o + 10).toLowerCase(Locale.ROOT));
			if (bone == null) {
				continue;
			}
			double[] local = {u.getDouble(o + 18), u.getDouble(o + 26), u.getDouble(o + 34)};
			sockets.put(pkg.fname(o + 2).toLowerCase(Locale.ROOT), add(pos[bone], rotate(rot[bone], local)));
		}
		return new Skeleton(sockets, bones);
	}

	private static double[] add(double[] a, double[] b) {
		return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
	}

	private static double[] multiply(double[] a, double[] b) {
		return new double[] {
			a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
			a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
			a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
			a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
	}

	private static double[] rotate(double[] q, double[] v) {
		double x = q[0], y = q[1], z = q[2], w = q[3];
		double tx = 2 * (y * v[2] - z * v[1]), ty = 2 * (z * v[0] - x * v[2]), tz = 2 * (x * v[1] - y * v[0]);
		return new double[] {
			v[0] + w * tx + (y * tz - z * ty),
			v[1] + w * ty + (z * tx - x * tz),
			v[2] + w * tz + (x * ty - y * tx)};
	}
}
