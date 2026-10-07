package gg.bodycamcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import gg.bodycamcraft.BodycamCraft;
import gg.bodycamcraft.BodycamLibrary;
import gg.bodycamcraft.bodycam.GunAssets;
import gg.bodycamcraft.bodycam.MeshReader;
import gg.bodycamcraft.bodycam.PakArchive;
import gg.bodycamcraft.bodycam.SoundReader;
import gg.bodycamcraft.bodycam.TextureReader;
import gg.bodycamcraft.gen.Sheets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The gun models, textures and sounds, read from the player's Bodycam on a background thread at start-up. */
public final class ClientAssets {
	/** One part of a gun in the gun's own space: metres, +Y up, the muzzle towards -Z. */
	public static final class PartMesh {
		public final String id;
		public final String motion;
		public final float[] positions;
		public final float[] normals;
		public final float[] uvs;
		public final int[] indices;

		PartMesh(String id, String motion, float[] positions, float[] normals, float[] uvs, int[] indices) {
			this.id = id;
			this.motion = motion;
			this.positions = positions;
			this.normals = normals;
			this.uvs = uvs;
			this.indices = indices;
		}
	}

	public static final class GunModel {
		public final Sheets.Weapon weapon;
		public final Identifier texture;
		public final List<PartMesh> parts = new ArrayList<>();
		/** Bounds of the whole gun. */
		public final float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		public final float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		/** Height of the sight line and the position of the rear of the slide, for aiming down the sights. */
		public float sightTop;
		public float rearZ;
		public float[] muzzle = new float[3];

		GunModel(Sheets.Weapon weapon) {
			this.weapon = weapon;
			this.texture = Identifier.fromNamespaceAndPath(BodycamCraft.MOD_ID, "dynamic/" + weapon.id());
		}
	}

	private static final Map<String, GunModel> GUNS = new ConcurrentHashMap<>();
	private static final Map<String, List<SoundReader.Pcm>> SOUNDS = new ConcurrentHashMap<>();
	private static volatile boolean ready;

	private ClientAssets() {
	}

	public static boolean ready() {
		return ready;
	}

	/** The model of a weapon, or null while loading or when Bodycam is not installed. */
	public static GunModel gun(String weaponId) {
		return ready ? GUNS.get(weaponId) : null;
	}

	public static List<SoundReader.Pcm> sound(String soundId) {
		return SOUNDS.getOrDefault(soundId, List.of());
	}

	static void start() {
		Thread thread = new Thread(ClientAssets::load, "BodycamCraft assets");
		thread.setDaemon(true);
		thread.start();
	}

	private static void load() {
		try {
			while (BodycamLibrary.state() == BodycamLibrary.State.LOADING) {
				Thread.sleep(50);
			}
			if (BodycamLibrary.state() != BodycamLibrary.State.READY) {
				return;
			}
			long start = System.nanoTime();
			PakArchive pak = BodycamLibrary.pak();
			Map<GunModel, TextureReader.Image> textures = new HashMap<>();
			for (Sheets.Weapon weapon : Sheets.WEAPONS) {
				List<GunAssets.PartDef> defs = new ArrayList<>();
				Map<String, String> motions = new HashMap<>();
				for (Sheets.Part part : Sheets.PARTS) {
					if (part.weapon().equals(weapon.id())) {
						defs.add(new GunAssets.PartDef(part.id(), part.mesh(), part.skeletal(), part.socket(), part.socketSkeleton()));
						motions.put(part.id(), part.motion());
					}
				}
				GunAssets.Gun gun = GunAssets.load(pak, weapon.skeleton(), defs, weapon.material(), weapon.muzzleSocket(), weapon.textureSize());
				GunModel model = convert(weapon, gun, motions);
				GUNS.put(weapon.id(), model);
				textures.put(model, gun.texture());
			}
			int decoded = 0;
			for (Sheets.Sound sound : Sheets.SOUNDS) {
				List<SoundReader.Pcm> variants = new ArrayList<>();
				for (String path : sound.paths()) {
					try {
						variants.add(SoundReader.read(pak, path));
						decoded++;
					} catch (Exception e) {
						BodycamCraft.LOGGER.warn("Could not decode Bodycam sound {}", path, e);
					}
				}
				SOUNDS.put(sound.id(), variants);
			}
			BodycamCraft.LOGGER.info("Loaded {} gun(s) and {} sounds from Bodycam in {} ms", GUNS.size(), decoded, (System.nanoTime() - start) / 1_000_000);
			Minecraft.getInstance().execute(() -> upload(textures));
		} catch (Throwable e) {
			BodycamCraft.LOGGER.error("Could not load the guns from Bodycam", e);
		}
	}

	/** Unreal (x right, y forward, z up, cm, left-handed) to gun space (metres, y up, muzzle towards -z). */
	private static GunModel convert(Sheets.Weapon weapon, GunAssets.Gun gun, Map<String, String> motions) {
		GunModel model = new GunModel(weapon);
		for (GunAssets.Part part : gun.parts()) {
			MeshReader.Mesh mesh = part.mesh();
			int n = mesh.vertexCount();
			float[] positions = new float[n * 3], normals = new float[n * 3];
			for (int i = 0; i < n; i++) {
				float x = -(mesh.positions()[i * 3] + part.offset()[0]) * 0.01f;
				float y = (mesh.positions()[i * 3 + 2] + part.offset()[2]) * 0.01f;
				float z = -(mesh.positions()[i * 3 + 1] + part.offset()[1]) * 0.01f;
				positions[i * 3] = x;
				positions[i * 3 + 1] = y;
				positions[i * 3 + 2] = z;
				normals[i * 3] = -mesh.normals()[i * 3];
				normals[i * 3 + 1] = mesh.normals()[i * 3 + 2];
				normals[i * 3 + 2] = -mesh.normals()[i * 3 + 1];
				float[] p = {x, y, z};
				for (int k = 0; k < 3; k++) {
					model.min[k] = Math.min(model.min[k], p[k]);
					model.max[k] = Math.max(model.max[k], p[k]);
				}
				if (part.id().equals(weapon.sightPart())) {
					model.sightTop = Math.max(model.sightTop, y);
					model.rearZ = Math.max(model.rearZ, z);
				}
			}
			model.parts.add(new PartMesh(part.id(), motions.get(part.id()), positions, normals, mesh.uvs(), mesh.indices()));
		}
		model.muzzle = new float[] {-gun.muzzle()[0] * 0.01f, gun.muzzle()[2] * 0.01f, -gun.muzzle()[1] * 0.01f};
		return model;
	}

	// hook: gun_texture
	private static void upload(Map<GunModel, TextureReader.Image> textures) {
		for (Map.Entry<GunModel, TextureReader.Image> entry : textures.entrySet()) {
			TextureReader.Image image = entry.getValue();
			NativeImage pixels = new NativeImage(image.width(), image.height(), false);
			for (int y = 0; y < image.height(); y++) {
				for (int x = 0; x < image.width(); x++) {
					pixels.setPixel(x, y, image.argb()[y * image.width() + x]);
				}
			}
			Identifier id = entry.getKey().texture;
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, pixels));
		}
		ready = true;
	}
}
