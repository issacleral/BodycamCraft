package gg.bodycamcraft.client.mixin;

import gg.bodycamcraft.client.BodycamView;
import gg.bodycamcraft.client.GunClient;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	@Final
	private static Vector3f FORWARDS;
	@Shadow
	@Final
	private static Vector3f UP;
	@Shadow
	@Final
	private static Vector3f LEFT;
	@Shadow
	private Vec3 position;
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;
	@Shadow
	@Final
	private Quaternionf rotation;
	@Shadow
	@Final
	private Vector3f forwards;
	@Shadow
	@Final
	private Vector3f up;
	@Shadow
	@Final
	private Vector3f left;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	// hook: camera_mount
	@Inject(method = "setup", at = @At("TAIL"))
	private void bodycamcraft$chestCamera(Level level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
		if (detached || !BodycamView.active() || !(entity instanceof LocalPlayer player)) {
			return;
		}
		BodycamView.update(partialTick);
		setPosition(position.x, position.y - GunClient.chestDrop(player), position.z);
		setRotation(yRot + BodycamView.yaw, xRot + BodycamView.pitch);
		rotation.rotateZ((float) Math.toRadians(BodycamView.roll));
		FORWARDS.rotate(rotation, forwards);
		UP.rotate(rotation, up);
		LEFT.rotate(rotation, left);
	}
}
