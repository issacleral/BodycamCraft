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
	/** -Dbodycamcraft.autotest=demo plays a short captioned tour and saves every frame, to cut a clip from. */
	public static final boolean DEMO = "demo".equals(System.getProperty("bodycamcraft.autotest"));
	public static final boolean ACTIVE = DEMO || Boolean.getBoolean("bodycamcraft.autotest");
	/** Text the HUD shows under the picture while the tour runs. */
	public static String caption;
	private static final String RUN = "demo" + System.currentTimeMillis() / 1000 % 100000;

	private static boolean worldRequested;
	private static int waited;
	private static int tick = -1;
	private static int glockShots = -1, m4Start = -1, m4End = -1;
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
		if (DEMO) {
			demo(mc);
			return;
		}
		if (tick >= 405 && tick < 417) {
			GunClient.testTrigger = true;
		}
		switch (tick) {
			case 379 -> glockShots = GunClient.shotsFired;
			case 380 -> mc.player.getInventory().setSelectedSlot(2);
			case 400 -> {
				m4Start = mc.player.getMainHandItem().getOrDefault(ModItems.ROUNDS, -1);
				shot(mc, "07_m4_hip");
			}
			case 425 -> GunClient.testAim = true;
			case 445 -> shot(mc, "08_m4_aim");
			case 447 -> GunClient.testAim = false;
			case 470 -> m4End = mc.player.getMainHandItem().getOrDefault(ModItems.ROUNDS, -1);
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
			case 480 -> {
				int m4Magazine = BodycamLibrary.magazineSize(ModItems.ITEMS.get("m4a1") instanceof ModItems.GunItem rifle ? rifle.weapon : null);
				int magazine = BodycamLibrary.magazineSize(ModItems.ITEMS.get("glock17") instanceof ModItems.GunItem gun ? gun.weapon : null);
				boolean pass = ClientAssets.ready() && roundsAtStart == magazine && glockShots == 3 && m4Start == m4Magazine && m4Start - m4End >= 4 && husksAfterShots == 0
						&& GunClient.reloadsStarted == 1 && roundsAfterReload == magazine && reloadDone == 1;
				BodycamCraft.LOGGER.info("AUTOTEST {}: bodycam={} assets={} roundsAtStart={} shots={} husksAliveAfterTwoShots={} reloads={} roundsAfterReload={} magazine={} m4Rounds={}->{} of {}",
						pass ? "PASS" : "FAIL", BodycamLibrary.state(), ClientAssets.ready(), roundsAtStart, glockShots, husksAfterShots,
						GunClient.reloadsStarted, roundsAfterReload, magazine, m4Start, m4End, m4Magazine);
			}
			case 500 -> mc.stop();
			default -> {
			}
		}
	}

	private static void demo(Minecraft mc) {
		int t = tick;
		if (t == 20) {
			command(mc, "time set 11900");
			command(mc, "weather clear");
			command(mc, "tp @s 0.5 -60 0.5 0 0");
			command(mc, "fill -7 -60 22 8 -56 22 minecraft:stone_bricks");
			command(mc, "fill -7 -60 12 -7 -57 22 minecraft:oak_planks");
			command(mc, "setblock 4 -60 12 minecraft:lantern");
			command(mc, "setblock -4 -60 16 minecraft:lantern");
			command(mc, "summon minecraft:husk 0.5 -60 14.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f]}");
			command(mc, "summon minecraft:husk -2.5 -60 17.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f]}");
			command(mc, "summon minecraft:husk 3.5 -60 18.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f]}");
		}
		if (t >= 62) {
			mc.gui.getChat().clearMessages(false);
		}
		if (t == 70) {
			caption = "Minecraft survival, seen through a chest camera. No crosshair.";
		}
		if (t == 95) {
			caption = "Free aim: the gun moves first, then the camera turns.";
		}
		if (t >= 95 && t < 155) {
			GunClient.onTurn(mc.player, t < 110 || t >= 140 ? 5 : -5, 0);
		}
		if (t == 155) {
			caption = "The world, mobs and building are plain Minecraft.";
		}
		mc.options.keyUp.setDown(t >= 155 && t < 185);
		if (t == 192) {
			caption = "Left click fires. Shots go where the gun points.";
		}
		if (t == 198 || t == 208 || t == 218) {
			GunClient.testTrigger = true;
		}
		if (t >= 226 && t < 236) {
			mc.player.turn(11.5, 0);
		}
		if (t == 236) {
			caption = "Hold right click to aim down the sights.";
			GunClient.testAim = true;
		}
		if (t == 256 || t == 266 || t == 276) {
			GunClient.testTrigger = true;
		}
		if (t == 284) {
			GunClient.testAim = false;
		}
		if (t >= 286 && t < 302) {
			mc.player.turn(-13.6, 0);
		}
		if (t == 304) {
			caption = "The real Glock, its sounds and 17 rounds: read from your Bodycam.";
		}
		if (t >= 306 && t < 356 && (t - 306) % 4 == 0) {
			GunClient.testTrigger = true;
		}
		if (t == 366) {
			caption = "R reloads. It takes as long as Bodycam's own reload.";
			GunClient.testReload = true;
		}
		if (t == 450) {
			caption = "Craft more magazines: iron ingot + copper ingot + gunpowder.";
			mc.setScreen(new InventoryScreen(mc.player));
		}
		if (t == 495) {
			mc.setScreen(null);
			caption = "BodycamCraft. Needs Minecraft: Java Edition and Bodycam on Steam.";
		}
		if (t >= 70 && t < 540) {
			Screenshot.grab(mc.gameDirectory, String.format("%s_%04d.png", RUN, t - 70), mc.getMainRenderTarget(), 1, message -> {
			});
		}
		if (t == 540) {
			BodycamCraft.LOGGER.info("AUTOTEST demo frames saved as {}_NNNN.png; shots {} reloads {}", RUN, GunClient.shotsFired, GunClient.reloadsStarted);
		}
		if (t == 560) {
			mc.stop();
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
