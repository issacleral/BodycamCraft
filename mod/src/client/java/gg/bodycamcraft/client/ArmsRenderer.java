package gg.bodycamcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gg.bodycamcraft.bodycam.RigReader;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;

/**
 * Poses Bodycam's arm skeleton from its animations, skins the arms mesh on the CPU and draws it in view space.
 * Unreal component space (cm; x, y forward, z up) maps to view space (metres; x right, y up, -z forward) as
 * (-x, z, -y) around the camera, the same mapping the gun models use.
 */
public final class ArmsRenderer {
	private ArmsRenderer() {
	}

	/** A pose: one local transform (7 floats) per skeleton bone, starting from the reference pose. */
	public static float[][] referencePose(RigReader.Bones skeleton) {
		float[][] ref = skeleton.local();
		float[][] out = new float[ref.length][];
		for (int i = 0; i < ref.length; i++) {
			out[i] = ref[i].clone();
		}
		return out;
	}

	/** Samples an animation at a time in seconds, looping or held at its ends, into a fresh pose. */
	public static float[][] sample(RigReader.Bones skeleton, RigReader.Anim anim, float seconds, boolean loop) {
		float[][] out = referencePose(skeleton);
		int samples = anim.samples();
		int tracks = anim.trackBones().length;
		float frame = seconds * anim.rate();
		if (loop && samples > 1) {
			frame %= samples - 1;
			if (frame < 0) {
				frame += samples - 1;
			}
		}
		frame = Math.max(0, Math.min(samples - 1, frame));
		int a = (int) frame, b = Math.min(a + 1, samples - 1);
		float t = frame - a;
		float[] d = anim.data();
		for (int track = 0; track < tracks; track++) {
			int bone = anim.trackBones()[track];
			if (bone >= out.length) {
				continue;
			}
			int pa = (a * tracks + track) * 10, pb = (b * tracks + track) * 10;
			float[] o = out[bone];
			if (!Float.isNaN(d[pa])) {
				float sign = d[pa] * d[pb] + d[pa + 1] * d[pb + 1] + d[pa + 2] * d[pb + 2] + d[pa + 3] * d[pb + 3] < 0 ? -1 : 1;
				for (int k = 0; k < 4; k++) {
					o[k] = d[pa + k] + (sign * d[pb + k] - d[pa + k]) * t;
				}
				normalize(o);
			}
			if (!Float.isNaN(d[pa + 4])) {
				for (int k = 4; k < 7; k++) {
					o[k] = d[pa + k] + (d[pb + k] - d[pa + k]) * t;
				}
			}
		}
		return out;
	}

	/** The scale an animation gives a bone at a time (Bodycam hides magazines by scaling them to 0); 1 when it has none. */
	public static float scale(RigReader.Anim anim, int bone, float seconds) {
		int tracks = anim.trackBones().length;
		for (int track = 0; track < tracks; track++) {
			if (anim.trackBones()[track] != bone) {
				continue;
			}
			int frame = Math.max(0, Math.min(anim.samples() - 1, Math.round(seconds * anim.rate())));
			float s = anim.data()[(frame * tracks + track) * 10 + 7];
			return Float.isNaN(s) ? 1 : s;
		}
		return 1;
	}

	/**
	 * Where a gun part goes in the gun model's space (metres, -z muzzle), given the animated transform of the bone it
	 * follows and the reference transform of the bone it was modelled at, both in Unreal component space.
	 */
	public static Matrix4f partInModel(float[] animated, float[] rest) {
		return boneInView(RigReader.Xf.mul(animated, RigReader.Xf.inverse(rest)), new float[3]);
	}

	/** Moves pose {@code a} towards pose {@code b} by {@code t}, in place. */
	public static void blend(float[][] a, float[][] b, float t) {
		if (t <= 0) {
			return;
		}
		for (int i = 0; i < a.length; i++) {
			float[] p = a[i], q = b[i];
			float sign = p[0] * q[0] + p[1] * q[1] + p[2] * q[2] + p[3] * q[3] < 0 ? -1 : 1;
			for (int k = 0; k < 4; k++) {
				p[k] += (sign * q[k] - p[k]) * t;
			}
			normalize(p);
			for (int k = 4; k < 7; k++) {
				p[k] += (q[k] - p[k]) * t;
			}
		}
	}

	/** Component-space transforms of every bone of a pose. */
	public static float[][] world(RigReader.Bones skeleton, float[][] local) {
		int[] parents = skeleton.parents();
		float[][] world = new float[local.length][];
		for (int i = 0; i < local.length; i++) {
			world[i] = parents[i] < 0 ? local[i] : RigReader.Xf.mul(world[parents[i]], local[i]);
		}
		return world;
	}

	/** The camera position for a posed skeleton, in component space. */
	public static float[] camera(ArmsAssets.Rig rig, float[][] world) {
		float[] head = world[rig.cameraBone];
		float[] offset = {0, 0, 0};
		for (int k = 0; k < 3 && k < rig.sheet.cameraOffsetCm().size(); k++) {
			offset[k] = rig.sheet.cameraOffsetCm().get(k);
		}
		return new float[] {head[4] + offset[0], head[5] + offset[1], head[6] + offset[2]};
	}

	/** A bone's transform in view space, for placing the gun model on it. */
	public static Matrix4f boneInView(float[] bone, float[] camera) {
		float x = bone[0], y = bone[1], z = bone[2], w = bone[3];
		// Rotation matrix of the quaternion, in Unreal axes.
		float r00 = 1 - 2 * (y * y + z * z), r01 = 2 * (x * y - z * w), r02 = 2 * (x * z + y * w);
		float r10 = 2 * (x * y + z * w), r11 = 1 - 2 * (x * x + z * z), r12 = 2 * (y * z - x * w);
		float r20 = 2 * (x * z - y * w), r21 = 2 * (y * z + x * w), r22 = 1 - 2 * (x * x + y * y);
		// C R C^-1 with C = (x, y, z) -> (-x, z, -y).
		Matrix4f m = new Matrix4f();
		m.m00(r00).m10(-r02).m20(r01);
		m.m01(-r20).m11(r22).m21(-r21);
		m.m02(r10).m12(-r12).m22(r11);
		m.m30(-(bone[4] - camera[0]) * 0.01f).m31((bone[6] - camera[2]) * 0.01f).m32(-(bone[5] - camera[1]) * 0.01f);
		return m;
	}

	/** Skins the mesh with a posed skeleton and submits it, one draw per material. */
	public static void submit(SubmitNodeCollector collector, PoseStack pose, ArmsAssets.Rig rig, float[][] world, float[] camera, int light) {
		int bones = rig.inverseBind.length;
		float[][] palette = new float[bones][];
		for (int m = 0; m < bones; m++) {
			int s = rig.meshToSkeleton[m];
			palette[m] = matrix(s < 0 ? new float[] {0, 0, 0, 1, 0, 0, 0} : RigReader.Xf.mul(world[s], rig.inverseBind[m]));
		}
		int n = rig.positions.length / 3;
		float[] pos = new float[n * 3], nrm = new float[n * 3];
		for (int v = 0; v < n; v++) {
			float px = rig.positions[v * 3], py = rig.positions[v * 3 + 1], pz = rig.positions[v * 3 + 2];
			float nx = rig.normals[v * 3], ny = rig.normals[v * 3 + 1], nz = rig.normals[v * 3 + 2];
			float ox = 0, oy = 0, oz = 0, mx = 0, my = 0, mz = 0;
			for (int k = 0; k < 4; k++) {
				float w = rig.weights[v * 4 + k];
				if (w == 0) {
					continue;
				}
				float[] p = palette[rig.influences[v * 4 + k]];
				ox += w * (p[0] * px + p[1] * py + p[2] * pz + p[3]);
				oy += w * (p[4] * px + p[5] * py + p[6] * pz + p[7]);
				oz += w * (p[8] * px + p[9] * py + p[10] * pz + p[11]);
				mx += w * (p[0] * nx + p[1] * ny + p[2] * nz);
				my += w * (p[4] * nx + p[5] * ny + p[6] * nz);
				mz += w * (p[8] * nx + p[9] * ny + p[10] * nz);
			}
			pos[v * 3] = -(ox - camera[0]) * 0.01f;
			pos[v * 3 + 1] = (oz - camera[2]) * 0.01f;
			pos[v * 3 + 2] = -(oy - camera[1]) * 0.01f;
			nrm[v * 3] = -mx;
			nrm[v * 3 + 1] = mz;
			nrm[v * 3 + 2] = -my;
		}
		for (RigReader.Section section : rig.sections) {
			if (section.material() >= rig.textures.length) {
				continue;
			}
			collector.submitCustomGeometry(pose, RenderTypes.entityCutoutNoCull(rig.textures[section.material()]),
					(at, buffer) -> draw(at, buffer, rig, section, pos, nrm, light));
		}
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer buffer, ArmsAssets.Rig rig, RigReader.Section section, float[] pos, float[] nrm,
			int light) {
		int[] idx = rig.indices;
		int end = (section.firstTriangle() + section.triangles()) * 3;
		// The entity render types draw quads: each triangle goes in as a quad with its last corner doubled.
		for (int i = section.firstTriangle() * 3; i < end; i += 3) {
			vertex(pose, buffer, rig, idx[i], pos, nrm, light);
			vertex(pose, buffer, rig, idx[i + 1], pos, nrm, light);
			vertex(pose, buffer, rig, idx[i + 2], pos, nrm, light);
			vertex(pose, buffer, rig, idx[i + 2], pos, nrm, light);
		}
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, ArmsAssets.Rig rig, int v, float[] pos, float[] nrm, int light) {
		buffer.addVertex(pose, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2])
				.setColor(-1)
				.setUv(rig.uvs[v * 2], rig.uvs[v * 2 + 1])
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, nrm[v * 3], nrm[v * 3 + 1], nrm[v * 3 + 2]);
	}

	/** A 3x4 row-major matrix of a rigid transform. */
	private static float[] matrix(float[] t) {
		float x = t[0], y = t[1], z = t[2], w = t[3];
		return new float[] {
			1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), t[4],
			2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), t[5],
			2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), t[6]};
	}

	private static void normalize(float[] q) {
		float len = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
		if (len > 0) {
			for (int k = 0; k < 4; k++) {
				q[k] /= len;
			}
		}
	}
}
