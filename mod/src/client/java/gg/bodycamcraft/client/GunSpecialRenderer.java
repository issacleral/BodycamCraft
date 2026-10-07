package gg.bodycamcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import gg.bodycamcraft.BodycamCraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.function.Consumer;

/**
 * The Bodycam gun (or just its magazine) wherever an item is drawn outside the first-person hands: inventory slots,
 * other players' hands, on the ground, in item frames.
 */
public final class GunSpecialRenderer implements SpecialModelRenderer<Boolean> {
	private final String weapon;
	private final String part;

	private GunSpecialRenderer(String weapon, String part) {
		this.weapon = weapon;
		this.part = part;
	}

	// hook: special_model
	static void register() {
		SpecialModelRenderers.ID_MAPPER.put(Identifier.fromNamespaceAndPath(BodycamCraft.MOD_ID, "gun"), Unbaked.MAP_CODEC);
	}

	@Override
	public Boolean extractArgument(ItemStack stack) {
		return Boolean.TRUE;
	}

	@Override
	public void submit(Boolean argument, ItemDisplayContext context, PoseStack pose, SubmitNodeCollector collector, int light, int overlay,
			boolean foil, int outline) {
		ClientAssets.GunModel model = ClientAssets.gun(weapon);
		if (model == null) {
			return;
		}
		String only = part.equals("all") ? null : part;
		float[] min = model.min, max = model.max;
		if (only != null) {
			min = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
			max = new float[] {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (ClientAssets.PartMesh mesh : model.parts) {
				if (!only.equals(mesh.motion)) {
					continue;
				}
				for (int i = 0; i < mesh.positions.length; i++) {
					min[i % 3] = Math.min(min[i % 3], mesh.positions[i]);
					max[i % 3] = Math.max(max[i % 3], mesh.positions[i]);
				}
			}
		}
		float size = Math.max(max[1] - min[1], max[2] - min[2]);
		pose.pushPose();
		pose.translate(0.5f, 0.5f, 0.5f);
		switch (context) {
			case GUI, FIXED, ON_SHELF -> {
				// Side on, filling the slot.
				pose.mulPose(Axis.YP.rotationDegrees(context == ItemDisplayContext.FIXED ? 90 : -90));
				pose.scale(0.9f / size, 0.9f / size, 0.9f / size);
				pose.translate(-(min[0] + max[0]) / 2, -(min[1] + max[1]) / 2, -(min[2] + max[2]) / 2);
			}
			case GROUND -> {
				// Lying on its side, a little larger than life so it can be found again.
				pose.mulPose(Axis.ZP.rotationDegrees(90));
				pose.scale(1.6f, 1.6f, 1.6f);
				pose.translate(-(min[0] + max[0]) / 2, -(min[1] + max[1]) / 2, -(min[2] + max[2]) / 2);
			}
			default -> {
				// In a hand: life size, the grip in the palm.
				pose.mulPose(Axis.XP.rotationDegrees(-90));
				pose.translate(0, 0.02f, 0.05f);
			}
		}
		GunRenderer.submit(collector, pose, model, light, 0, 0, only);
		pose.popPose();
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		output.accept(new Vector3f(0, 0, 0));
		output.accept(new Vector3f(1, 1, 1));
	}

	public record Unbaked(String weapon, String part) implements SpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("weapon").forGetter(Unbaked::weapon),
				Codec.STRING.optionalFieldOf("part", "all").forGetter(Unbaked::part)
		).apply(instance, Unbaked::new));

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}

		@Override
		public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
			return new GunSpecialRenderer(weapon, part);
		}
	}
}
