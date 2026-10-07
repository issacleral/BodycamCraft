package gg.bodycamcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.bodycamcraft.BodycamLibrary;
import gg.bodycamcraft.GunLogic;
import gg.bodycamcraft.ModItems;
import gg.bodycamcraft.gen.Sheets;
import gg.bodycamcraft.gen.Sheets.Cam;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The gun in the player's hands: free aim, firing, recoil, the slide, reloading, and drawing it in first person.
 * The gun points at (view + aim offset); shots go there, not at the centre of the screen.
 */
public final class GunClient {
	/** Half the field of view the game draws the hands with. */
	private static final float HAND_HALF_FOV = (float) Math.toRadians(35);
	private static final int EQUIP_TICKS = 7;

	private static float aimYaw, aimPitch, aimYawOld, aimPitchOld;
	private static float returnYaw, returnPitch;
	private static float ads, adsOld;
	private static float kick, kickOld;
	private static float slide, slideOld;
	private static int cooldown, flash, equip, equipOld;
	private static int reloadTicks, reloadTotal;
	private static boolean triggerWasDown, useStartedOnBlock, useWasDown;
	private static int lastSlot = -1;
	private static Sheets.Weapon held;
	/** Counts shots and reloads the server was asked for, for the scripted test. */
	public static int shotsFired, reloadsStarted;
	/** Input the scripted test presses in place of the mouse and keyboard. */
	static boolean testTrigger, testAim, testReload;

	private GunClient() {
	}

	static void register() {
		// hook: client_tick
		ClientTickEvents.END_CLIENT_TICK.register(GunClient::tick);
	}

	/** True while a gun is in the main hand, whether or not its model has loaded. */
	public static boolean holdingGun() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && player.getMainHandItem().getItem() instanceof ModItems.GunItem;
	}

	/** Points the gun straight ahead again; the scripted tour does this before each burst. */
	static void testTurn(LocalPlayer player, double dx, double dy) {
		scripted = true;
		onTurn(player, dx, dy);
		scripted = false;
	}

	private static boolean scripted;

	static void testCentre() {
		aimYaw = aimPitch = returnYaw = returnPitch = 0;
	}

	public static float aimProgress() {
		return ads;
	}

	public static boolean reloading() {
		return reloadTicks > 0;
	}

	/** Right click on something that opens or toggles keeps its normal meaning; anywhere else it aims. */
	public static boolean useBelongsToTheWorld(Minecraft mc) {
		HitResult target = mc.hitResult;
		if (target == null || mc.level == null) {
			return false;
		}
		if (target.getType() == HitResult.Type.ENTITY) {
			return true;
		}
		if (target instanceof BlockHitResult block && target.getType() == HitResult.Type.BLOCK) {
			BlockState state = mc.level.getBlockState(block.getBlockPos());
			return state.hasBlockEntity() || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock
					|| state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof ButtonBlock
					|| state.getBlock() instanceof LeverBlock || state.getBlock() instanceof CraftingTableBlock
					|| state.getBlock() instanceof BedBlock;
		}
		return false;
	}

	/** Mouse movement goes to the gun first, inside its free-aim box; what does not fit turns the player. */
	public static void onTurn(LocalPlayer player, double dx, double dy) {
		if (Autotest.ACTIVE && !scripted) {
			// The scripted test owns the view; a real mouse over the window must not disturb it.
			return;
		}
		if (held == null || !BodycamView.active()) {
			player.turn(dx, dy);
			return;
		}
		float limitYaw = Mth.lerp(ads, Cam.FREE_AIM_YAW_DEGREES, Cam.FREE_AIM_AIM_YAW_DEGREES);
		float limitPitch = Mth.lerp(ads, Cam.FREE_AIM_PITCH_DEGREES, Cam.FREE_AIM_AIM_PITCH_DEGREES);
		float yaw = aimYaw + (float) dx * 0.15f, pitch = aimPitch + (float) dy * 0.15f;
		aimYaw = Mth.clamp(yaw, -limitYaw, limitYaw);
		aimPitch = Mth.clamp(pitch, -limitPitch, limitPitch);
		player.turn((yaw - aimYaw) / 0.15, (pitch - aimPitch) / 0.15);
	}

	private static void tick(Minecraft mc) {
		aimYawOld = aimYaw;
		aimPitchOld = aimPitch;
		adsOld = ads;
		kickOld = kick;
		slideOld = slide;
		equipOld = equip;
		LocalPlayer player = mc.player;
		ItemStack stack = player == null ? ItemStack.EMPTY : player.getMainHandItem();
		Sheets.Weapon weapon = stack.getItem() instanceof ModItems.GunItem gun && ClientAssets.gun(gun.weapon.id()) != null ? gun.weapon : null;
		if (weapon == null || mc.isPaused()) {
			if (weapon == null) {
				held = null;
				lastSlot = -1;
				aimYaw = aimPitch = ads = kick = slide = returnYaw = returnPitch = 0;
				reloadTicks = cooldown = flash = 0;
			}
			BodycamView.tick(0);
			return;
		}
		int slot = player.getInventory().getSelectedSlot();
		if (held != weapon || slot != lastSlot) {
			held = weapon;
			lastSlot = slot;
			equip = EQUIP_TICKS;
			reloadTicks = 0;
			BodycamSounds.play(weapon.soundEquip());
		}
		if (equip > 0) {
			equip--;
		}
		if (cooldown > 0) {
			cooldown--;
		}
		if (flash > 0) {
			flash--;
		}
		if (reloadTicks > 0) {
			reloadTicks--;
		}
		kick *= 0.55f;
		slide = Math.max(0, slide - 0.5f);

		boolean free = Autotest.ACTIVE ? mc.screen == null : mc.screen == null && mc.mouseHandler.isMouseGrabbed();
		boolean useDown = free && (mc.options.keyUse.isDown() || testAim);
		if (useDown && !useWasDown) {
			useStartedOnBlock = useBelongsToTheWorld(mc);
		}
		useWasDown = useDown;
		boolean aiming = useDown && !useStartedOnBlock && reloadTicks == 0 && equip == 0;
		ads = Mth.clamp(ads + (aiming ? 1 : -1) / Cam.HOLD_AIM_TICKS, 0, 1);

		// The free-aim box shrinks while aiming, and part of each shot's kick settles back by itself.
		float limitYaw = Mth.lerp(ads, Cam.FREE_AIM_YAW_DEGREES, Cam.FREE_AIM_AIM_YAW_DEGREES);
		float limitPitch = Mth.lerp(ads, Cam.FREE_AIM_PITCH_DEGREES, Cam.FREE_AIM_AIM_PITCH_DEGREES);
		aimYaw = Mth.clamp(aimYaw - returnYaw * 0.3f, -limitYaw, limitYaw);
		aimPitch = Mth.clamp(aimPitch - returnPitch * 0.3f, -limitPitch, limitPitch);
		returnYaw *= 0.7f;
		returnPitch *= 0.7f;

		boolean triggerDown = free && (mc.options.keyAttack.isDown() || testTrigger);
		boolean pull = triggerDown && (weapon.auto() || !triggerWasDown);
		triggerWasDown = triggerDown;
		if (pull && cooldown == 0 && reloadTicks == 0 && equip == 0) {
			if (stack.getOrDefault(ModItems.ROUNDS, 0) > 0) {
				fire(mc, player, weapon);
			} else {
				BodycamSounds.play(weapon.soundDry());
				cooldown = 4;
			}
		}
		while (BodycamCraftClient.RELOAD.consumeClick()) {
			startReload(player, stack, weapon);
		}
		if (testReload) {
			testReload = false;
			startReload(player, stack, weapon);
		}
		BodycamView.tick(ads);
	}

	private static void fire(Minecraft mc, LocalPlayer player, Sheets.Weapon weapon) {
		ClientPlayNetworking.send(new GunLogic.FirePayload(player.getYRot() + aimYaw, player.getXRot() + aimPitch, ads > 0.5f));
		shotsFired++;
		cooldown = weapon.fireIntervalTicks();
		kick = 1;
		slide = 1;
		flash = 2;
		boolean roof = !mc.level.canSeeSky(player.blockPosition().above());
		for (String sound : roof ? weapon.soundFireIndoor() : weapon.soundFire()) {
			BodycamSounds.play(sound);
		}
		// The gun climbs and wanders; half of that comes back down, the rest is the player's to correct.
		float steady = 1 - 0.35f * ads;
		float up = weapon.recoilPitchDeg() * steady * (0.8f + (float) Math.random() * 0.4f);
		float side = weapon.recoilYawDeg() * steady * ((float) Math.random() * 2 - 1);
		aimPitch -= up;
		aimYaw += side;
		returnPitch += -up * Cam.RECOIL_GUN_RETURN;
		returnYaw += side * Cam.RECOIL_GUN_RETURN;
		BodycamView.kick(Cam.RECOIL_SHAKE_DEGREES * steady);
	}

	private static void startReload(LocalPlayer player, ItemStack stack, Sheets.Weapon weapon) {
		int rounds = stack.getOrDefault(ModItems.ROUNDS, 0);
		if (reloadTicks > 0 || equip > 0 || rounds >= BodycamLibrary.magazineSize(weapon)) {
			return;
		}
		if (!player.isCreative() && !player.getInventory().contains(new ItemStack(ModItems.ITEMS.get(weapon.magazineItem())))) {
			BodycamSounds.play(weapon.soundDry());
			return;
		}
		// The reload takes as long as Bodycam's own reload sound for this gun.
		int ticks = BodycamSounds.play(rounds == 0 ? weapon.soundReloadEmpty() : weapon.soundReloadPartial());
		reloadTotal = reloadTicks = Mth.clamp(ticks == 0 ? 50 : ticks, 20, 160);
		reloadsStarted++;
		ClientPlayNetworking.send(new GunLogic.ReloadPayload(reloadTotal));
	}

	public static float chestDrop(LocalPlayer player) {
		return Cam.MOUNT_EYE_DROP_BLOCKS * player.getEyeHeight() / 1.62f;
	}

	/** Draws the held gun in view space. Returns false when the gun's model is not available. */
	public static boolean renderFirstPerson(ItemStack stack, float partialTick, PoseStack pose, SubmitNodeCollector collector, int light) {
		if (!(stack.getItem() instanceof ModItems.GunItem gun)) {
			return false;
		}
		ClientAssets.GunModel model = ClientAssets.gun(gun.weapon.id());
		if (model == null) {
			return false;
		}
		float aim = Mth.lerp(partialTick, adsOld, ads);
		aim = aim * aim * (3 - 2 * aim);
		// The hands are drawn with a narrower field of view than the world, so scale the gun's angle to match
		// where its shots land on screen.
		float scale = Mth.sin(HAND_HALF_FOV) / Mth.cos(HAND_HALF_FOV)
				/ (float) Math.tan(Math.toRadians(BodycamView.currentFov(partialTick) / 2));
		float yaw = (float) Math.toDegrees(Math.atan(Math.tan(Math.toRadians(Mth.lerp(partialTick, aimYawOld, aimYaw))) * scale));
		float pitch = (float) Math.toDegrees(Math.atan(Math.tan(Math.toRadians(Mth.lerp(partialTick, aimPitchOld, aimPitch))) * scale));

		pose.pushPose();
		pose.mulPose(Axis.YP.rotationDegrees(-yaw));
		pose.mulPose(Axis.XP.rotationDegrees(-pitch));
		pose.translate(Mth.lerp(aim, model.weapon.hipRight(), 0),
				Mth.lerp(aim, model.weapon.hipUp(), -model.sightTop - Cam.HOLD_AIM_SIGHT_DROP),
				-Mth.lerp(aim, model.weapon.hipForward(), model.weapon.aimForward() + model.rearZ));

		float raise = Mth.lerp(partialTick, equipOld, equip) / EQUIP_TICKS;
		pose.translate(0, -0.22f * raise, 0);
		pose.mulPose(Axis.XP.rotationDegrees(-45 * raise));

		float reload = reloadTotal == 0 || reloadTicks == 0 ? 1 : 1 - (reloadTicks - partialTick) / reloadTotal;
		float tilt = reloadTicks == 0 ? 0 : Mth.clamp(Math.min(reload, 1 - reload) * 6, 0, 1);
		tilt = tilt * tilt * (3 - 2 * tilt);
		pose.translate(-0.02f * tilt, -0.035f * tilt, 0.03f * tilt);
		pose.mulPose(Axis.ZP.rotationDegrees(-38 * tilt));
		pose.mulPose(Axis.XP.rotationDegrees(16 * tilt));

		float recoil = Mth.lerp(partialTick, kickOld, kick);
		pose.translate(0, 0.004f * recoil, 0.03f * recoil);
		pose.mulPose(Axis.XP.rotationDegrees(6 * recoil));

		boolean empty = stack.getOrDefault(ModItems.ROUNDS, 0) == 0 && reloadTicks == 0;
		float slideBack = empty ? 1 : Mth.lerp(partialTick, slideOld, slide);
		// The magazine drops out, a fresh one comes up.
		float magazine = 0;
		if (reloadTicks > 0) {
			magazine = reload < 0.12f ? 0 : reload < 0.34f ? (reload - 0.12f) / 0.22f : reload < 0.52f ? 2 : reload < 0.74f ? 1 - (reload - 0.52f) / 0.22f : 0;
		}
		GunRenderer.submit(collector, pose, model, flash > 0 ? LightTexture.FULL_BRIGHT : light,
				slideBack * model.weapon.slideTravelCm() * 0.01f, magazine, null);
		pose.popPose();
		return true;
	}
}
