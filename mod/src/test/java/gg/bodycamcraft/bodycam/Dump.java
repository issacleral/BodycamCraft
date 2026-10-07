package gg.bodycamcraft.bodycam;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Developer check: reads the Glock from the installed Bodycam and writes an OBJ, a PNG and a WAV to look at. */
public final class Dump {
	public static void main(String[] args) throws Exception {
		Path out = Path.of(args[0]);
		Files.createDirectories(out);
		Path bodycam = SteamLocator.findBodycam();
		System.out.println("Bodycam: " + bodycam);
		NativeCodecs.load(out.resolve("natives"));
		long t0 = System.nanoTime();
		PakArchive pak = PakArchive.open(SteamLocator.paks(bodycam));
		System.out.println("files: " + pak.size() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
		String g = "Bodycam/Content/BodycamWeapons/Guns/Glock17/";
		t0 = System.nanoTime();
		GunAssets.Gun gun = GunAssets.load(pak, g + "SKM_Glock17_Skeleton", List.of(
				new GunAssets.PartDef("frame", g + "SKM_Glock17", true, "", ""),
				new GunAssets.PartDef("slide", g + "Glock17_Default_Slide", false, "Slide", ""),
				new GunAssets.PartDef("barrel", g + "Glock17_Default_Barrel", false, "Threaded_Barrel", ""),
				new GunAssets.PartDef("magazine", g + "Glock17_Magazine", false, "magazine", ""),
				new GunAssets.PartDef("trigger", g + "Glock17_Default_Firemods", false, "Trigger", "")),
				g + "M_Glock17", "SOCKET_Muzzle", 1024);
		System.out.println("gun loaded in " + (System.nanoTime() - t0) / 1_000_000 + " ms, muzzle " + java.util.Arrays.toString(gun.muzzle()));
		try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("glock.obj")))) {
			int base = 1;
			for (GunAssets.Part p : gun.parts()) {
				MeshReader.Mesh m = p.mesh();
				System.out.println(p.id() + ": " + m.vertexCount() + " verts, " + m.indices().length / 3 + " tris, at " + java.util.Arrays.toString(p.offset()));
				w.println("o " + p.id());
				for (int i = 0; i < m.vertexCount(); i++) {
					w.println("v " + (m.positions()[i * 3] + p.offset()[0]) + " " + (m.positions()[i * 3 + 1] + p.offset()[1]) + " " + (m.positions()[i * 3 + 2] + p.offset()[2]));
					w.println("vt " + m.uvs()[i * 2] + " " + m.uvs()[i * 2 + 1]);
					w.println("vn " + m.normals()[i * 3] + " " + m.normals()[i * 3 + 1] + " " + m.normals()[i * 3 + 2]);
				}
				for (int i = 0; i < m.indices().length; i += 3) {
					int a = m.indices()[i] + base, b = m.indices()[i + 1] + base, c = m.indices()[i + 2] + base;
					w.println("f " + a + "/" + a + "/" + a + " " + b + "/" + b + "/" + b + " " + c + "/" + c + "/" + c);
				}
				base += m.vertexCount();
			}
		}
		TextureReader.Image tex = gun.texture();
		BufferedImage img = new BufferedImage(tex.width(), tex.height(), BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, tex.width(), tex.height(), tex.argb(), 0, tex.width());
		ImageIO.write(img, "png", out.resolve("glock.png").toFile());
		System.out.println("texture " + tex.width() + "x" + tex.height());
		System.out.println("magazine: " + MagTable.capacity(pak, "Bodycam/Content/BodycamWeapons/Core/DATA/DT/DT_MAG", "Glock17"));
		for (String s : args.length > 1 ? args[1].split(";") : new String[0]) {
			SoundReader.Pcm pcm = SoundReader.read(pak, s);
			int peak = 0;
			for (short v : pcm.samples()) {
				peak = Math.max(peak, Math.abs(v));
			}
			System.out.println(s.substring(s.lastIndexOf(47) + 1) + ": " + pcm.frames() + " frames, " + pcm.sampleRate() + " Hz, " + pcm.channels() + " ch, peak " + peak);
		}
	}
}
