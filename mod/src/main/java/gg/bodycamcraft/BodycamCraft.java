package gg.bodycamcraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BodycamCraft implements ModInitializer {
	public static final String MOD_ID = "bodycamcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		BodycamLibrary.start(FabricLoader.getInstance().getGameDir().resolve(MOD_ID));
		ModItems.register();
		GunLogic.register();
	}
}
