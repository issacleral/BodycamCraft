package gg.bodycamcraft.client;

import gg.bodycamcraft.BodycamCraft;
import gg.bodycamcraft.gen.Sheets.Cam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/** The chest-camera view: whether it is on, and the motion a body-mounted camera adds on top of where the player looks. */
public final class BodycamView {
	public static final Identifier LENS = Identifier.fromNamespaceAndPath(BodycamCraft.MOD_ID, "bodycam");

	private static boolean enabled = true;
	private static float shakePitch, shakeYaw, shakeRoll;
	private static float shakePitchOld, shakeYawOld, shakeRollOld;
	private static float aimOld, aim;

	/** Extra camera angles for this frame, in degrees. */
	public static float pitch, yaw, roll;

	private BodycamView() {
	}

	public static boolean active() {
		Minecraft mc = Minecraft.getInstance();
		return enabled && mc.player != null && mc.options.getCameraType().isFirstPerson() && !mc.player.isSpectator();
	}

	public static boolean toggle() {
		enabled = !enabled;
		return enabled;
	}

	/** A shot or a hit jolts the camera. */
	public static void kick(float degrees) {
		shakePitch -= degrees;
		shakeYaw += (float) (Math.random() * 2 - 1) * degrees * 0.6f;
		shakeRoll += (float) (Math.random() * 2 - 1) * degrees * 0.8f;
	}

	static void tick(float aimProgress) {
		shakePitchOld = shakePitch;
		shakeYawOld = shakeYaw;
		shakeRollOld = shakeRoll;
		shakePitch *= Cam.RECOIL_RECOVER_PER_TICK - 0.25f;
		shakeYaw *= Cam.RECOIL_RECOVER_PER_TICK - 0.25f;
		shakeRoll *= Cam.RECOIL_RECOVER_PER_TICK - 0.25f;
		aimOld = aim;
		aim = aimProgress;
	}

	/** Works out this frame's sway; called from the camera hook. */
	public static void update(float partialTick) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			pitch = yaw = roll = 0;
			return;
		}
		float time = (player.tickCount + partialTick) / 20f;
		float steady = 1 - 0.6f * Mth.lerp(partialTick, aimOld, aim);
		float idle = Cam.SWAY_IDLE_DEGREES * steady;
		float phase = time * Cam.SWAY_IDLE_HZ * Mth.TWO_PI;
		float speed = Math.min(1, player.avatarState().getInterpolatedBob(partialTick) * 10);
		float walk = player.avatarState().getBackwardsInterpolatedWalkDistance(partialTick) * Mth.PI;
		float turn = Mth.clamp(Mth.wrapDegrees(player.getYRot() - player.yRotO), -40, 40);

		pitch = Mth.sin(phase) * idle + Mth.cos(walk * 2) * Cam.SWAY_STEP_PITCH_DEGREES * speed * steady
				+ Mth.lerp(partialTick, shakePitchOld, shakePitch);
		yaw = Mth.sin(phase * 0.63f + 1.3f) * idle * 0.7f + Mth.lerp(partialTick, shakeYawOld, shakeYaw);
		roll = Mth.sin(walk) * Cam.SWAY_STEP_ROLL_DEGREES * speed * steady - turn * Cam.SWAY_TURN_ROLL
				+ Mth.lerp(partialTick, shakeRollOld, shakeRoll);
	}

	/** The wide chest-camera field of view, keeping whatever the game multiplied its own by (sprinting, potions). */
	public static float fov(float vanilla, float partialTick) {
		float base = Minecraft.getInstance().options.fov().get();
		float zoom = Mth.lerp(Mth.lerp(partialTick, aimOld, aim), 1, Cam.FOV_AIM_FOV_MULTIPLIER);
		return Cam.FOV_FOV_DEGREES * (vanilla / Math.max(base, 1)) * zoom;
	}

	/** The world field of view right now, for lining the drawn gun up with where its shots go. */
	public static float currentFov(float partialTick) {
		float zoom = Mth.lerp(Mth.lerp(partialTick, aimOld, aim), 1, Cam.FOV_AIM_FOV_MULTIPLIER);
		return active() ? Cam.FOV_FOV_DEGREES * zoom : Minecraft.getInstance().options.fov().get();
	}
}
