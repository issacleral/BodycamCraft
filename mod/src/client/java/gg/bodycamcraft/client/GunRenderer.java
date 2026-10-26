package gg.bodycamcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;

/** Draws a Bodycam gun from its parts, with the slide and magazine where their motion puts them. */
public final class GunRenderer {
	/** How far the magazine travels when it drops out, in metres. */
	private static final float MAGAZINE_DROP = 0.13f;

	private GunRenderer() {
	}

	/**
	 * @param slideBack how far the slide is back, in metres
	 * @param magazine  0 seated, up to 1 on its way out, above 1 not there
	 * @param onlyMotion draw only parts with this motion (the magazine item), or null for the whole gun
	 */
	public static void submit(SubmitNodeCollector collector, PoseStack pose, ClientAssets.GunModel model, int light, float slideBack,
			float magazine, String onlyMotion) {
		submit(collector, pose, model, light, slideBack, magazine, onlyMotion, Map.of(), null);
	}

	/**
	 * @param animated parts placed by a Bodycam gun animation, by part id: a transform in model space, or null to hide the part
	 * @param spare    where the fresh magazine is during a reload (model space), or null when it is not shown
	 */
	public static void submit(SubmitNodeCollector collector, PoseStack pose, ClientAssets.GunModel model, int light, float slideBack,
			float magazine, String onlyMotion, Map<String, Matrix4f> animated, Matrix4f spare) {
		collector.submitCustomGeometry(pose, RenderTypes.entityCutoutNoCull(model.texture),
				(at, buffer) -> draw(at, buffer, model, light, slideBack, magazine, onlyMotion, animated, spare));
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer buffer, ClientAssets.GunModel model, int light, float slideBack,
			float magazine, String onlyMotion, Map<String, Matrix4f> animated, Matrix4f spare) {
		for (ClientAssets.PartMesh part : model.parts) {
			if (onlyMotion != null && !onlyMotion.equals(part.motion)) {
				continue;
			}
			if (spare != null && part.motion.equals("magazine")) {
				drawPart(pose, buffer, part, light, spare, 0, 0);
			}
			if (animated.containsKey(part.id)) {
				Matrix4f at = animated.get(part.id);
				if (at != null) {
					drawPart(pose, buffer, part, light, at, 0, 0);
				}
				continue;
			}
			float dy = 0, dz = 0;
			if (part.motion.equals("slide")) {
				dz = slideBack;
			} else if (part.motion.equals("magazine") && onlyMotion == null) {
				if (magazine > 1) {
					continue;
				}
				dy = -magazine * MAGAZINE_DROP;
			}
			drawPart(pose, buffer, part, light, null, dy, dz);
		}
	}

	private static void drawPart(PoseStack.Pose pose, VertexConsumer buffer, ClientAssets.PartMesh part, int light, Matrix4f at, float dy, float dz) {
		int[] idx = part.indices;
		Vector3f p = new Vector3f(), n = new Vector3f();
		// The entity render types draw quads: each triangle goes in as a quad with its last corner doubled.
		for (int i = 0; i < idx.length; i += 3) {
			vertex(pose, buffer, part, idx[i], at, dy, dz, light, p, n);
			vertex(pose, buffer, part, idx[i + 1], at, dy, dz, light, p, n);
			vertex(pose, buffer, part, idx[i + 2], at, dy, dz, light, p, n);
			vertex(pose, buffer, part, idx[i + 2], at, dy, dz, light, p, n);
		}
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, ClientAssets.PartMesh part, int v, Matrix4f at, float dy, float dz,
			int light, Vector3f p, Vector3f n) {
		p.set(part.positions[v * 3], part.positions[v * 3 + 1] + dy, part.positions[v * 3 + 2] + dz);
		n.set(part.normals[v * 3], part.normals[v * 3 + 1], part.normals[v * 3 + 2]);
		if (at != null) {
			at.transformPosition(p);
			at.transformDirection(n);
		}
		buffer.addVertex(pose, p.x, p.y, p.z)
				.setColor(-1)
				.setUv(part.uvs[v * 2], part.uvs[v * 2 + 1])
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, n.x, n.y, n.z);
	}
}
