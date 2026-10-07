package gg.bodycamcraft.client;

import gg.bodycamcraft.BodycamCraft;
import gg.bodycamcraft.bodycam.SoundReader;
import gg.bodycamcraft.gen.Sheets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Plays the sounds decoded from the player's Bodycam. They are PCM in memory, so each is fed to the engine as a stream. */
public final class BodycamSounds {
	private static final Identifier EVENT = Identifier.fromNamespaceAndPath(BodycamCraft.MOD_ID, "gun");
	private static final RandomSource RANDOM = RandomSource.create();

	private BodycamSounds() {
	}

	public static int variants(String soundId) {
		return ClientAssets.sound(soundId).size();
	}

	/** Plays a random variant of a sound of the sounds sheet. Returns its length in ticks, or 0 if it is not loaded. */
	public static int play(String soundId) {
		int variants = variants(soundId);
		return variants == 0 ? 0 : play(soundId, RANDOM.nextInt(variants));
	}

	// hook: sound_play
	public static int play(String soundId, int variant) {
		List<SoundReader.Pcm> pcms = ClientAssets.sound(soundId);
		Sheets.Sound row = Sheets.sound(soundId);
		if (row == null || variant < 0 || variant >= pcms.size()) {
			return 0;
		}
		SoundReader.Pcm pcm = pcms.get(variant);
		float pitch = 1 + (RANDOM.nextFloat() * 2 - 1) * row.pitchJitter();
		Minecraft.getInstance().getSoundManager().play(new PcmSound(pcm, row.volume(), pitch));
		return Math.round(pcm.frames() * 20f / pcm.sampleRate());
	}

	/** A sound that follows the listener: the player's own gun. */
	private static final class PcmSound extends AbstractSoundInstance {
		private final SoundReader.Pcm pcm;

		PcmSound(SoundReader.Pcm pcm, float volume, float pitch) {
			super(EVENT, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
			this.pcm = pcm;
			this.volume = volume;
			this.pitch = pitch;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
		}

		@Override
		public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, Identifier id, boolean repeatInstantly) {
			return CompletableFuture.completedFuture(new PcmStream(pcm));
		}
	}

	private static final class PcmStream implements AudioStream {
		private final SoundReader.Pcm pcm;
		private int position;

		PcmStream(SoundReader.Pcm pcm) {
			this.pcm = pcm;
		}

		@Override
		public AudioFormat getFormat() {
			return new AudioFormat(pcm.sampleRate(), 16, pcm.channels(), true, false);
		}

		@Override
		public ByteBuffer read(int size) {
			int samples = Math.min(size / 2, pcm.samples().length - position);
			samples -= samples % pcm.channels();
			ByteBuffer out = ByteBuffer.allocateDirect(Math.max(samples, 0) * 2).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < samples; i++) {
				out.putShort(pcm.samples()[position + i]);
			}
			position += Math.max(samples, 0);
			out.flip();
			return out;
		}

		@Override
		public void close() {
		}
	}
}
