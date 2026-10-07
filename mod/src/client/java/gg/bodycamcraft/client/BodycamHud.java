package gg.bodycamcraft.client;

import gg.bodycamcraft.BodycamCraft;
import gg.bodycamcraft.BodycamLibrary;
import gg.bodycamcraft.gen.Sheets.Cam;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Bodycam footage has no game HUD. The crosshair is gone; the hotbar and status bars only peek in after a slot
 * change or a hit; a recording stamp sits in the corner.
 */
public final class BodycamHud {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm:ss");
	private static final List<Identifier> PEEKING = List.of(VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR,
			VanillaHudElements.ARMOR_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH,
			VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP);

	private static int lastSlot = -1;
	private static float lastHealth = -1;
	private static int peekUntil;

	private BodycamHud() {
	}

	// hook: hud
	static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (graphics, delta) -> {
			if (!BodycamView.active()) {
				original.render(graphics, delta);
			}
		});
		for (Identifier id : PEEKING) {
			HudElementRegistry.replaceElement(id, original -> (graphics, delta) -> {
				if (!BodycamView.active() || peeking()) {
					original.render(graphics, delta);
				}
			});
		}
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BodycamCraft.MOD_ID, "stamp"), BodycamHud::stamp);
	}

	private static boolean peeking() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return true;
		}
		int slot = player.getInventory().getSelectedSlot();
		float health = player.getHealth();
		if (slot != lastSlot || health < lastHealth || player.isCreative()) {
			peekUntil = player.tickCount + (int) Cam.HUD_PEEK_TICKS;
		}
		lastSlot = slot;
		lastHealth = health;
		return player.tickCount < peekUntil || player.tickCount < 60;
	}

	private static void stamp(GuiGraphics graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui) {
			return;
		}
		Font font = mc.font;
		int width = graphics.guiWidth();
		if (BodycamView.active()) {
			String time = LocalDateTime.now().format(STAMP);
			graphics.drawString(font, time, width - font.width(time) - 8, 8, 0xE0FFFFFF, true);
			String name = "BODYCAMCRAFT  X1";
			graphics.drawString(font, name, width - font.width(name) - 8, 19, 0xB0FFFFFF, true);
			if (System.currentTimeMillis() / 600 % 2 == 0) {
				graphics.drawString(font, "REC", 16, 8, 0xE0FFFFFF, true);
				graphics.fill(8, 9, 13, 14, 0xFFE02020);
			}
		}
		if (Autotest.caption != null) {
			int w = font.width(Autotest.caption);
			int y = graphics.guiHeight() - 40;
			graphics.fill(width / 2 - w / 2 - 6, y - 4, width / 2 + w / 2 + 6, y + 12, 0xB0000000);
			graphics.drawCenteredString(font, Autotest.caption, width / 2, y, 0xFFFFFFFF);
		}
		Component notice = switch (BodycamLibrary.state()) {
			case MISSING -> Component.translatable("bodycamcraft.missing");
			case FAILED -> Component.translatable("bodycamcraft.failed", BodycamLibrary.error());
			case LOADING -> Component.translatable("bodycamcraft.loading");
			case READY -> ClientAssets.ready() ? null : Component.translatable("bodycamcraft.loading");
		};
		if (notice != null) {
			graphics.drawCenteredString(font, notice, width / 2, 34, 0xFFFFD060);
		}
	}
}
