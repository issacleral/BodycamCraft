package gg.bodycamcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;

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
		collector.submitCustomGeometry(pose, RenderTypes.entityCutoutNoCull(model.texture),
				(at, buffer) -> draw(at, buffer, model, light, slideBack, magazine, onlyMotion));
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer buffer, ClientAssets.GunModel model, int light, float slideBack,
			float magazine, String onlyMotion) {
		for (ClientAssets.PartMesh part : model.parts) {
			if (onlyMotion != null && !onlyMotion.equals(part.motion)) {
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
			int[] idx = part.indices;
			// The entity render types draw quads: each triangle goes in as a quad with its last corner doubled.
			for (int i = 0; i < idx.length; i += 3) {
				vertex(pose, buffer, part, idx[i], dy, dz, light);
				vertex(pose, buffer, part, idx[i + 1], dy, dz, light);
				vertex(pose, buffer, part, idx[i + 2], dy, dz, light);
				vertex(pose, buffer, part, idx[i + 2], dy, dz, light);
			}
		}
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, ClientAssets.PartMesh part, int v, float dy, float dz, int light) {
		buffer.addVertex(pose, part.positions[v * 3], part.positions[v * 3 + 1] + dy, part.positions[v * 3 + 2] + dz)
				.setColor(-1)
				.setUv(part.uvs[v * 2], part.uvs[v * 2 + 1])
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, part.normals[v * 3], part.normals[v * 3 + 1], part.normals[v * 3 + 2]);
	}
}
