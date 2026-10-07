package gg.bodycamcraft.client;

import gg.bodycamcraft.BodycamCraft;
import gg.bodycamcraft.BodycamLibrary;
import gg.bodycamcraft.ModItems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * A scripted check of the real game, run only with -Dbodycamcraft.autotest=true: makes a flat survival world, shoots
 * a husk, aims, reloads, and saves screenshots of each step. It logs one AUTOTEST line with the result and quits.
 */
public final class Autotest {
	public static final boolean ACTIVE = Boolean.getBoolean("bodycamcraft.autotest");

	private static boolean worldRequested;
	private static int waited;
	private static int tick = -1;
	private static int roundsAtStart = -1, husksAfterShots = -1, roundsAfterReload = -1, reloadDone = -1;

	private Autotest() {
	}

	static void register() {
		if (ACTIVE) {
			ClientTickEvents.END_CLIENT_TICK.register(Autotest::tick);
		}
	}

	private static void tick(Minecraft mc) {
		mc.options.pauseOnLostFocus = false;
		if (!worldRequested) {
			boolean loaded = ClientAssets.ready() || BodycamLibrary.state() == BodycamLibrary.State.MISSING
					|| BodycamLibrary.state() == BodycamLibrary.State.FAILED;
			if (mc.screen instanceof TitleScreen && (loaded || ++waited > 1200)) {
				worldRequested = true;
				String name = "bodycamcraft_autotest_" + System.currentTimeMillis();
				LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.NORMAL, true,
						new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
				mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(8675309L, false, false),
						WorldPresets::createFlatWorldDimensions, mc.screen);
			}
			return;
		}
		if (mc.player == null || mc.level == null || (tick < 0 && mc.screen != null)) {
			return;
		}
		tick++;
		GunClient.testTrigger = false;
		switch (tick) {
			case 20 -> {
				command(mc, "time set 12400");
				command(mc, "weather clear");
				command(mc, "tp @s 0.5 -60 0.5 0 0");
				command(mc, "fill -5 -60 11 6 -56 11 minecraft:stone_bricks");
				command(mc, "fill -5 -60 4 -5 -57 11 minecraft:oak_planks");
				command(mc, "setblock 3 -60 7 minecraft:lantern");
				command(mc, "summon minecraft:husk 0.5 -60 6.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f]}");
			}
			case 50 -> {
				ItemStack held = mc.player.getMainHandItem();
				roundsAtStart = held.getItem() instanceof ModItems.GunItem ? held.getOrDefault(ModItems.ROUNDS, 0) : -1;
				BodycamCraft.LOGGER.info("AUTOTEST kit: {} x{} rounds {}, slot 1: {} x{}", held.getItem(), held.getCount(), roundsAtStart,
						mc.player.getInventory().getItem(1).getItem(), mc.player.getInventory().getItem(1).getCount());
			}
			case 64 -> mc.gui.getChat().clearMessages(false);
			case 70 -> shot(mc, "01_hip");
			case 80, 95 -> GunClient.testTrigger = true;
			case 81 -> shot(mc, "02_shot");
			case 115 -> {
				husksAfterShots = husks(mc);
				command(mc, "summon minecraft:husk 0.5 -60 8.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f]}");
				GunClient.testAim = true;
			}
			case 120 -> mc.gui.getChat().clearMessages(false);
			case 140 -> shot(mc, "03_aim");
			case 142 -> GunClient.testTrigger = true;
			case 150 -> GunClient.testAim = false;
			case 160 -> GunClient.testReload = true;
			case 185 -> shot(mc, "04_reload");
			case 330 -> {
				roundsAfterReload = mc.player.getMainHandItem().getOrDefault(ModItems.ROUNDS, -1);
				reloadDone = GunClient.reloading() ? 0 : 1;
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			}
			case 350 -> shot(mc, "05_third_person");
			case 352 -> {
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.setScreen(new InventoryScreen(mc.player));
			}
			case 372 -> shot(mc, "06_inventory");
			case 375 -> mc.setScreen(null);
			case 390 -> {
				int magazine = BodycamLibrary.magazineSize(ModItems.ITEMS.get("glock17") instanceof ModItems.GunItem gun ? gun.weapon : null);
				boolean pass = ClientAssets.ready() && roundsAtStart == magazine && GunClient.shotsFired == 3 && husksAfterShots == 0
						&& GunClient.reloadsStarted == 1 && roundsAfterReload == magazine && reloadDone == 1;
				BodycamCraft.LOGGER.info("AUTOTEST {}: bodycam={} assets={} roundsAtStart={} shots={} husksAliveAfterTwoShots={} reloads={} roundsAfterReload={} magazine={}",
						pass ? "PASS" : "FAIL", BodycamLibrary.state(), ClientAssets.ready(), roundsAtStart, GunClient.shotsFired, husksAfterShots,
						GunClient.reloadsStarted, roundsAfterReload, magazine);
			}
			case 410 -> mc.stop();
			default -> {
			}
		}
	}

	private static int husks(Minecraft mc) {
		int alive = 0;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof Husk husk && husk.isAlive()) {
				alive++;
			}
		}
		return alive;
	}

	private static void command(Minecraft mc, String command) {
		mc.player.connection.sendCommand(command);
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "autotest_" + name + ".png", mc.getMainRenderTarget(), 1,
				message -> BodycamCraft.LOGGER.info("AUTOTEST {}", message.getString()));
	}
}
