package gg.bodycamcraft.bodycam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the Steam copy of Bodycam (app 2406770) on this PC. */
public final class SteamLocator {
	public static final String APP_ID = "2406770";
	private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"([^\"]+)\"");
	private static final Pattern INSTALL_DIR = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"");
	private static final Pattern REG_VALUE = Pattern.compile("REG_SZ\\s+(.+)$");

	private SteamLocator() {
	}

	/** The folder holding Bodycam.exe, or null when Bodycam is not installed. */
	public static Path findBodycam() {
		String override = System.getProperty("bodycamcraft.bodycamDir", System.getenv("BODYCAMCRAFT_BODYCAM_DIR"));
		if (override != null && !override.isBlank() && Files.isDirectory(paks(Path.of(override)))) {
			return Path.of(override);
		}
		Set<Path> steamRoots = new LinkedHashSet<>();
		addRegistry(steamRoots, "HKCU\\Software\\Valve\\Steam", "SteamPath");
		addRegistry(steamRoots, "HKLM\\SOFTWARE\\WOW6432Node\\Valve\\Steam", "InstallPath");
		steamRoots.add(Path.of("C:/Program Files (x86)/Steam"));
		steamRoots.add(Path.of("C:/Program Files/Steam"));

		Set<Path> libraries = new LinkedHashSet<>();
		for (Path root : steamRoots) {
			libraries.add(root);
			Path vdf = root.resolve("steamapps").resolve("libraryfolders.vdf");
			if (Files.isRegularFile(vdf)) {
				try {
					Matcher m = VDF_PATH.matcher(Files.readString(vdf, StandardCharsets.UTF_8));
					while (m.find()) {
						libraries.add(Path.of(m.group(1).replace("\\\\", "\\")));
					}
				} catch (Exception ignored) {
					// An unreadable library list just means fewer places to look.
				}
			}
		}
		for (Path library : libraries) {
			Path manifest = library.resolve("steamapps").resolve("appmanifest_" + APP_ID + ".acf");
			if (!Files.isRegularFile(manifest)) {
				continue;
			}
			try {
				Matcher m = INSTALL_DIR.matcher(Files.readString(manifest, StandardCharsets.UTF_8));
				Path dir = library.resolve("steamapps").resolve("common").resolve(m.find() ? m.group(1) : "Bodycam");
				if (Files.isDirectory(paks(dir))) {
					return dir;
				}
			} catch (Exception ignored) {
				// Try the next library.
			}
		}
		return null;
	}

	public static Path paks(Path bodycamDir) {
		return bodycamDir.resolve("Bodycam").resolve("Content").resolve("Paks");
	}

	private static void addRegistry(Set<Path> out, String key, String value) {
		if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
			return;
		}
		try {
			Process p = new ProcessBuilder("reg", "query", key, "/v", value).redirectErrorStream(true).start();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				String line;
				while ((line = r.readLine()) != null) {
					Matcher m = REG_VALUE.matcher(line.trim());
					if (m.find()) {
						out.add(Path.of(m.group(1).trim()));
					}
				}
			}
			p.waitFor(5, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			// No registry entry: fall back to the default install folders.
		}
	}
}
