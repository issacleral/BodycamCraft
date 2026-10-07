package gg.bodycamcraft;

import gg.bodycamcraft.bodycam.MagTable;
import gg.bodycamcraft.bodycam.NativeCodecs;
import gg.bodycamcraft.bodycam.PakArchive;
import gg.bodycamcraft.bodycam.SteamLocator;
import gg.bodycamcraft.gen.Sheets;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player's own copy of Bodycam, opened once at start-up. Everything of Bodycam's that the mod shows or plays is
 * read from here while the game runs; none of it ships with the mod.
 */
public final class BodycamLibrary {
	public enum State {
		LOADING, READY, MISSING, FAILED
	}

	private static volatile State state = State.LOADING;
	private static volatile String error = "";
	private static volatile PakArchive pak;
	private static final Map<String, Integer> MAGAZINE_SIZES = new ConcurrentHashMap<>();

	private BodycamLibrary() {
	}

	static void start(Path workDir) {
		Thread thread = new Thread(() -> open(workDir), "BodycamCraft library");
		thread.setDaemon(true);
		thread.start();
	}

	private static void open(Path workDir) {
		try {
			Path bodycam = SteamLocator.findBodycam();
			if (bodycam == null) {
				BodycamCraft.LOGGER.warn("Bodycam is not installed (Steam app {}); its guns and sounds are unavailable", SteamLocator.APP_ID);
				state = State.MISSING;
				return;
			}
			NativeCodecs.load(workDir.resolve("natives"));
			PakArchive archive = PakArchive.open(SteamLocator.paks(bodycam));
			for (Sheets.Weapon weapon : Sheets.WEAPONS) {
				try {
					MAGAZINE_SIZES.put(weapon.id(), MagTable.capacity(archive, weapon.magTable(), weapon.magRow()));
				} catch (Exception e) {
					BodycamCraft.LOGGER.warn("Could not read the magazine size of {} from Bodycam; using {}", weapon.id(), weapon.magFallback(), e);
				}
			}
			pak = archive;
			state = State.READY;
			BodycamCraft.LOGGER.info("Bodycam found at {}: {} files, magazine sizes {}", bodycam, archive.size(), MAGAZINE_SIZES);
		} catch (Throwable e) {
			error = String.valueOf(e.getMessage());
			state = State.FAILED;
			BodycamCraft.LOGGER.error("Could not open Bodycam's files", e);
		}
	}

	public static State state() {
		return state;
	}

	public static String error() {
		return error;
	}

	/** Bodycam's files, or null unless {@link #state()} is READY. */
	public static PakArchive pak() {
		return pak;
	}

	/** Rounds per magazine: Bodycam's own number when it could be read, else the sheet's fallback. */
	public static int magazineSize(Sheets.Weapon weapon) {
		return MAGAZINE_SIZES.getOrDefault(weapon.id(), weapon.magFallback());
	}
}
