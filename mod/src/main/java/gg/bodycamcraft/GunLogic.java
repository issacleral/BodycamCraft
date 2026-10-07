package gg.bodycamcraft;

import gg.bodycamcraft.gen.Sheets;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The server side of the guns: fire rate, ammunition, the hit test, damage, reloading and the starting kit. */
public final class GunLogic {
	private static final String KIT_TAG = "bodycamcraft_kit";
	private static final boolean DEBUG = System.getProperty("bodycamcraft.autotest") != null;

	/** The client fired; yaw and pitch are where the gun pointed (it aims freely inside a box around the view). */
	public record FirePayload(float yaw, float pitch, boolean aiming) implements CustomPacketPayload {
		public static final Type<FirePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BodycamCraft.MOD_ID, "fire"));
		public static final StreamCodec<RegistryFriendlyByteBuf, FirePayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.FLOAT, FirePayload::yaw, ByteBufCodecs.FLOAT, FirePayload::pitch, ByteBufCodecs.BOOL, FirePayload::aiming,
				FirePayload::new);

		@Override
		public Type<FirePayload> type() {
			return TYPE;
		}
	}

	/** The client started a reload that takes this many ticks (the length of Bodycam's reload sound). */
	public record ReloadPayload(int ticks) implements CustomPacketPayload {
		public static final Type<ReloadPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(BodycamCraft.MOD_ID, "reload"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ReloadPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, ReloadPayload::ticks, ReloadPayload::new);

		@Override
		public Type<ReloadPayload> type() {
			return TYPE;
		}
	}

	private static final class State {
		long lastFire = Long.MIN_VALUE / 2;
		long reloadEnd;
		int reloadSlot;
		Item reloadGun;
		boolean magazineTaken;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private GunLogic() {
	}

	static void register() {
		PayloadTypeRegistry.playC2S().register(FirePayload.TYPE, FirePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ReloadPayload.TYPE, ReloadPayload.CODEC);
		// hook: fire_payload
		ServerPlayNetworking.registerGlobalReceiver(FirePayload.TYPE, (payload, context) -> fire(payload, context.player()));
		// hook: reload_payload
		ServerPlayNetworking.registerGlobalReceiver(ReloadPayload.TYPE, (payload, context) -> reload(payload, context.player()));
		// hook: server_tick
		ServerTickEvents.END_SERVER_TICK.register(GunLogic::tick);
		// hook: join_kit
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> giveKit(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> STATES.remove(handler.getPlayer().getUUID()));
	}

	private static void fire(FirePayload payload, ServerPlayer player) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof ModItems.GunItem gun) || player.isSpectator() || !player.isAlive()) {
			return;
		}
		Sheets.Weapon weapon = gun.weapon;
		ServerLevel level = (ServerLevel) player.level();
		State state = STATES.computeIfAbsent(player.getUUID(), id -> new State());
		long now = level.getServer().getTickCount();
		if (state.reloadEnd > now || now - state.lastFire < weapon.fireIntervalTicks() - 1) {
			return;
		}
		int rounds = stack.getOrDefault(ModItems.ROUNDS, 0);
		if (rounds <= 0) {
			return;
		}
		stack.set(ModItems.ROUNDS, rounds - 1);
		state.lastFire = now;

		// The gun may point anywhere inside the free-aim box around the player's view, and no further.
		float maxYaw = Sheets.Cam.FREE_AIM_YAW_DEGREES + 2, maxPitch = Sheets.Cam.FREE_AIM_PITCH_DEGREES + 2;
		float spread = payload.aiming() ? weapon.spreadAdsDeg() : weapon.spreadHipDeg();
		RandomSource random = player.getRandom();
		float yaw = player.getYRot() + Mth.clamp(Mth.wrapDegrees(payload.yaw() - player.getYRot()), -maxYaw, maxYaw)
				+ (float) random.triangle(0, spread);
		float pitch = Mth.clamp(player.getXRot() + Mth.clamp(payload.pitch() - player.getXRot(), -maxPitch, maxPitch)
				+ (float) random.triangle(0, spread), -90, 90);

		// Shots leave from the chest camera, which sits below the eyes (lower still when crouched or swimming).
		Vec3 from = player.getEyePosition().add(0, -Sheets.Cam.MOUNT_EYE_DROP_BLOCKS * player.getEyeHeight() / 1.62f, 0);
		Vec3 to = from.add(Vec3.directionFromRotation(pitch, yaw).scale(weapon.rangeBlocks()));
		BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();

		Entity hit = null;
		Vec3 hitAt = null;
		double best = from.distanceToSqr(end);
		for (Entity entity : level.getEntities(player, new AABB(from, end).inflate(1), e -> e.isPickable() && e.isAlive())) {
			Optional<Vec3> at = entity.getBoundingBox().inflate(0.12).clip(from, end);
			if (at.isPresent() && from.distanceToSqr(at.get()) < best) {
				best = from.distanceToSqr(at.get());
				hit = entity;
				hitAt = at.get();
			}
		}
		if (hit != null) {
			float damage = weapon.damage();
			if (hit instanceof LivingEntity living && hitAt.y >= living.getY() + living.getBbHeight() * 0.78) {
				damage *= weapon.headshotMultiplier();
			}
			hit.invulnerableTime = 0;
			boolean hurt = hit.hurtServer(level, level.damageSources().playerAttack(player), damage);
			BodycamCraft.LOGGER.debug("{} hit {} for {}: {} (health {})", weapon.id(), hit.getType(), damage, hurt, hit instanceof LivingEntity l ? l.getHealth() : -1);
			if (DEBUG) BodycamCraft.LOGGER.info("SHOT {} hit {} dmg {} hurt {} health {}", weapon.id(), hit.getType().toShortString(), damage, hurt, hit instanceof LivingEntity l2 ? l2.getHealth() : -1);
			level.sendParticles(ParticleTypes.CRIT, hitAt.x, hitAt.y, hitAt.z, 6, 0.05, 0.05, 0.05, 0.2);
		} else if (block.getType() == HitResult.Type.BLOCK) {
			BlockState struck = level.getBlockState(block.getBlockPos());
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, struck), end.x, end.y, end.z, 10, 0.05, 0.05, 0.05, 0.1);
			level.sendParticles(ParticleTypes.SMOKE, end.x, end.y, end.z, 2, 0.02, 0.02, 0.02, 0.01);
			level.playSound(null, block.getBlockPos(), struck.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.9f, 1.3f);
		}
		if (DEBUG && hit == null) BodycamCraft.LOGGER.info("SHOT {} missed: yaw {} pitch {} block {}", weapon.id(), yaw, pitch, block.getType());
		level.gameEvent(player, GameEvent.PROJECTILE_SHOOT, from);
	}

	private static void reload(ReloadPayload payload, ServerPlayer player) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof ModItems.GunItem gun) || !player.isAlive()) {
			return;
		}
		State state = STATES.computeIfAbsent(player.getUUID(), id -> new State());
		long now = ((ServerLevel) player.level()).getServer().getTickCount();
		if (state.reloadEnd > now || stack.getOrDefault(ModItems.ROUNDS, 0) >= BodycamLibrary.magazineSize(gun.weapon)) {
			return;
		}
		state.magazineTaken = false;
		if (!player.isCreative()) {
			Inventory inventory = player.getInventory();
			Item magazine = ModItems.ITEMS.get(gun.weapon.magazineItem());
			int slot = -1;
			for (int i = 0; i < inventory.getContainerSize() && slot < 0; i++) {
				if (inventory.getItem(i).is(magazine)) {
					slot = i;
				}
			}
			if (slot < 0) {
				return;
			}
			inventory.getItem(slot).shrink(1);
			state.magazineTaken = true;
		}
		state.reloadEnd = now + Mth.clamp(payload.ticks(), 20, 160);
		state.reloadSlot = player.getInventory().getSelectedSlot();
		state.reloadGun = gun;
	}

	private static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		for (Iterator<Map.Entry<UUID, State>> it = STATES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, State> entry = it.next();
			State state = entry.getValue();
			if (state.reloadEnd == 0 || state.reloadEnd > now) {
				continue;
			}
			state.reloadEnd = 0;
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				it.remove();
				continue;
			}
			ItemStack stack = player.getMainHandItem();
			if (stack.getItem() == state.reloadGun && player.getInventory().getSelectedSlot() == state.reloadSlot && stack.getItem() instanceof ModItems.GunItem gun) {
				stack.set(ModItems.ROUNDS, BodycamLibrary.magazineSize(gun.weapon));
			} else if (state.magazineTaken && state.reloadGun instanceof ModItems.GunItem gun) {
				// Put the gun away mid-reload: the magazine goes back in the bag.
				player.getInventory().add(new ItemStack(ModItems.ITEMS.get(gun.weapon.magazineItem())));
			}
		}
	}

	private static void giveKit(ServerPlayer player) {
		if (player.getTags().contains(KIT_TAG)) {
			return;
		}
		player.addTag(KIT_TAG);
		Inventory inventory = player.getInventory();
		for (Sheets.Kit row : Sheets.KIT) {
			Item item = ModItems.ITEMS.get(row.item());
			ItemStack stack = new ItemStack(item, row.count());
			if (row.loaded() && item instanceof ModItems.GunItem gun) {
				stack.set(ModItems.ROUNDS, BodycamLibrary.magazineSize(gun.weapon));
			}
			if (inventory.getItem(row.slot()).isEmpty()) {
				inventory.setItem(row.slot(), stack);
			} else {
				inventory.add(stack);
			}
		}
	}
}
