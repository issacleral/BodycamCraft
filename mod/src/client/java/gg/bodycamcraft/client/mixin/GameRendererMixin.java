package gg.bodycamcraft.client.mixin;

import gg.bodycamcraft.client.BodycamView;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Shadow
	private Identifier postEffectId;
	@Shadow
	private boolean effectActive;

	// hook: lens_effect
	@Inject(method = "render", at = @At("HEAD"))
	private void bodycamcraft$lens(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
		if (BodycamView.active()) {
			postEffectId = BodycamView.LENS;
			effectActive = true;
		} else if (BodycamView.LENS.equals(postEffectId)) {
			postEffectId = null;
			effectActive = false;
		}
	}

	// hook: fov
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void bodycamcraft$wideFov(Camera camera, float partialTick, boolean world, CallbackInfoReturnable<Float> cir) {
		if (world && BodycamView.active()) {
			cir.setReturnValue(BodycamView.fov(cir.getReturnValue(), partialTick));
		}
	}
}
