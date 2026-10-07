package gg.bodycamcraft.client;

import gg.bodycamcraft.BodycamCraft;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class BodycamCraftClient implements ClientModInitializer {
	private static final KeyMapping.Category KEYS = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BodycamCraft.MOD_ID, "main"));
	public static KeyMapping RELOAD;
	public static KeyMapping TOGGLE_VIEW;

	@Override
	public void onInitializeClient() {
		// hook: keys
		RELOAD = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bodycamcraft.reload", GLFW.GLFW_KEY_R, KEYS));
		TOGGLE_VIEW = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.bodycamcraft.toggle_view", GLFW.GLFW_KEY_V, KEYS));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (TOGGLE_VIEW.consumeClick()) {
				boolean on = BodycamView.toggle();
				if (mc.player != null) {
					mc.player.displayClientMessage(Component.translatable(on ? "bodycamcraft.view.on" : "bodycamcraft.view.off"), true);
				}
			}
		});
		GunSpecialRenderer.register();
		BodycamHud.register();
		GunClient.register();
		ClientAssets.start();
		Autotest.register();
		BodycamCraft.LOGGER.info("BodycamCraft loaded");
	}
}
