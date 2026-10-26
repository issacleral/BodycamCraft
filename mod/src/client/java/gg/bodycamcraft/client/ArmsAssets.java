package gg.bodycamcraft.client;

import gg.bodycamcraft.BodycamCraft;
import gg.bodycamcraft.bodycam.GunAssets;
import gg.bodycamcraft.bodycam.PakArchive;
import gg.bodycamcraft.bodycam.RigReader;
import gg.bodycamcraft.bodycam.TextureReader;
import gg.bodycamcraft.gen.Sheets;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bodycam's first-person arms: skeleton, skinned mesh, textures and the animations of each gun. */
public final class ArmsAssets {
	public static final class Rig {
		public final Sheets.Arms sheet;
		public final RigReader.Bones skeleton;
		public final int gunBone, cameraBone;
		public final float[] positions, normals, uvs;
		public final int[] indices;
		public final List<RigReader.Section> sections;
		public final Identifier[] textures;
		/** Four influences per vertex, as mesh bone indices, and their weights. */
		public final int[] influences;
		public final float[] weights;
		/** For each mesh bone: the skeleton bone that drives it (-1 if none) and the inverse of its bind transform. */
		public final int[] meshToSkeleton;
		public final float[][] inverseBind;
		/** weapon id, then kind (idle, aim, reload_empty, reload_partial; "gun_" + kind for the gun's own), then variants. */
		public final Map<String, Map<String, List<RigReader.Anim>>> anims = new HashMap<>();
		/** Per weapon: the skeleton its gun-side animations are made for, and that skeleton's reference world transforms. */
		public final Map<String, RigReader.Bones> gunSkeletons = new HashMap<>();
		public final Map<String, float[][]> gunRest = new HashMap<>();

		Rig(Sheets.Arms sheet, RigReader.Bones skeleton, RigReader.Skinned mesh, Identifier[] textures) {
			this.sheet = sheet;
			this.skeleton = skeleton;
			this.gunBone = skeleton.index(sheet.gunBone());
			this.cameraBone = skeleton.index(sheet.cameraBone());
			this.positions = mesh.mesh().positions();
			this.normals = mesh.mesh().normals();
			this.uvs = mesh.mesh().uvs();
			this.indices = mesh.mesh().indices();
			this.sections = mesh.sections();
			this.textures = textures;
			this.influences = mesh.influenceBones();
			this.weights = mesh.influenceWeights();
			RigReader.Bones bones = mesh.bones();
			int n = bones.names().length;
			meshToSkeleton = new int[n];
			inverseBind = new float[n][];
			float[][] world = new float[n][];
			for (int i = 0; i < n; i++) {
				world[i] = bones.parents()[i] < 0 ? bones.local()[i].clone() : RigReader.Xf.mul(world[bones.parents()[i]], bones.local()[i]);
				inverseBind[i] = RigReader.Xf.inverse(world[i]);
				meshToSkeleton[i] = skeleton.index(bones.names()[i]);
			}
		}

		/** The variants of one kind of animation for a weapon; empty when there are none. */
		public List<RigReader.Anim> anims(String weapon, String kind) {
			return anims.getOrDefault(weapon, Map.of()).getOrDefault(kind, List.of());
		}
	}

	private static volatile Rig rig;

	private ArmsAssets() {
	}

	/** The arms, or null while loading, when Bodycam is missing, or when they could not be read. */
	public static Rig rig() {
		return ClientAssets.ready() ? rig : null;
	}

	/** Reads the arms on the loading thread. Returns the textures to upload; the rig goes live once they are. */
	static Map<Identifier, TextureReader.Image> load(PakArchive pak) {
		Map<Identifier, TextureReader.Image> textures = new HashMap<>();
		if (Sheets.ARMS.isEmpty()) {
			return textures;
		}
		Sheets.Arms sheet = Sheets.ARMS.get(0);
		try {
			long start = System.nanoTime();
			RigReader.Bones skeleton = RigReader.skeleton(pak, sheet.skeleton());
			RigReader.Skinned mesh = RigReader.skinned(pak, sheet.mesh());
			Identifier[] ids = new Identifier[sheet.materials().size()];
			for (int i = 0; i < ids.length; i++) {
				ids[i] = Identifier.fromNamespaceAndPath(BodycamCraft.MOD_ID, "dynamic/" + sheet.id() + "_" + i);
				textures.put(ids[i], TextureReader.read(pak, GunAssets.baseColour(pak, sheet.materials().get(i)), sheet.textureSize()));
			}
			Rig loaded = new Rig(sheet, skeleton, mesh, ids);
			if (loaded.gunBone < 0 || loaded.cameraBone < 0) {
				throw new IllegalStateException("No bone " + (loaded.gunBone < 0 ? sheet.gunBone() : sheet.cameraBone()));
			}
			for (Sheets.Weapon weapon : Sheets.WEAPONS) {
				RigReader.Bones bones = RigReader.skeleton(pak, weapon.animSkeleton());
				loaded.gunSkeletons.put(weapon.id(), bones);
				loaded.gunRest.put(weapon.id(), ArmsRenderer.world(bones, ArmsRenderer.referencePose(bones)));
			}
			int count = 0;
			for (Sheets.Anim anim : Sheets.ANIMS) {
				List<RigReader.Anim> variants = new ArrayList<>();
				for (String path : anim.paths()) {
					variants.add(RigReader.anim(pak, path));
					count++;
				}
				List<RigReader.Anim> gunVariants = new ArrayList<>();
				for (String path : anim.gunPaths()) {
					gunVariants.add(RigReader.anim(pak, path));
					count++;
				}
				Map<String, List<RigReader.Anim>> byKind = loaded.anims.computeIfAbsent(anim.weapon(), k -> new HashMap<>());
				byKind.put(anim.kind(), variants);
				byKind.put("gun_" + anim.kind(), gunVariants);
			}
			rig = loaded;
			BodycamCraft.LOGGER.info("Loaded the arms ({} vertices, {} bones, {} sections) and {} animations from Bodycam in {} ms",
					loaded.positions.length / 3, skeleton.names().length, loaded.sections.size(), count, (System.nanoTime() - start) / 1_000_000);
		} catch (Throwable e) {
			rig = null;
			textures.clear();
			BodycamCraft.LOGGER.error("Could not load the arms from Bodycam; guns are drawn without them", e);
		}
		return textures;
	}
}
