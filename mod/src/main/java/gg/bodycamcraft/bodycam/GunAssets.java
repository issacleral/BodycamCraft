package gg.bodycamcraft.bodycam;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Puts a Bodycam weapon together from its frame, the parts attached to its sockets, and its base colour texture. */
public final class GunAssets {
	/** One row of the parts sheet: which mesh, and the skeleton socket it hangs on ("" for the frame itself). */
	public record PartDef(String id, String mesh, boolean skeletal, String socket, String socketSkeleton) {
	}

	/** A mesh and where its origin sits in the space of the weapon, in Unreal units (cm). */
	public record Part(String id, MeshReader.Mesh mesh, float[] offset) {
	}

	public record Gun(List<Part> parts, TextureReader.Image texture, float[] muzzle) {
		public Part part(String id) {
			for (Part p : parts) {
				if (p.id.equals(id)) {
					return p;
				}
			}
			return null;
		}
	}

	private GunAssets() {
	}

	public static Gun load(PakArchive pak, String skeletonPath, List<PartDef> defs, String materialPath, String muzzleSocket,
			int textureSize) throws IOException {
		SkeletonReader.Skeleton skeleton = SkeletonReader.read(pak, skeletonPath);
		List<Part> parts = new ArrayList<>();
		for (PartDef def : defs) {
			MeshReader.Mesh mesh = def.skeletal ? MeshReader.readSkeletal(pak, def.mesh) : MeshReader.readStatic(pak, def.mesh);
			float[] offset = new float[3];
			if (!def.socket.isEmpty()) {
				SkeletonReader.Skeleton owner = def.socketSkeleton.isEmpty() ? skeleton : SkeletonReader.read(pak, def.socketSkeleton);
				double[] at = owner.position(def.socket);
				offset = new float[] {(float) at[0], (float) at[1], (float) at[2]};
			}
			parts.add(new Part(def.id, mesh, offset));
		}
		double[] m = skeleton.position(muzzleSocket);
		return new Gun(parts, TextureReader.read(pak, baseColour(pak, materialPath), textureSize),
				new float[] {(float) m[0], (float) m[1], (float) m[2]});
	}

	/** The base colour texture a material instance points at. */
	public static String baseColour(PakArchive pak, String materialPath) throws IOException {
		UPackage pkg = new UPackage(pak, materialPath);
		String first = null;
		for (UPackage.Import im : pkg.imports) {
			if (!im.className().equals("Texture2D")) {
				continue;
			}
			String path = UPackage.pakPath(pkg.importPackage(im));
			if (im.name().toLowerCase(Locale.ROOT).contains("basecolor")) {
				return path;
			}
			if (first == null) {
				first = path;
			}
		}
		if (first == null) {
			throw new IOException("Material has no texture: " + materialPath);
		}
		return first;
	}
}
