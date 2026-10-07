package gg.bodycamcraft.client.mixin;

import gg.bodycamcraft.client.GunClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While a gun is held, the mouse buttons fire and aim instead of punching, mining and using. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	// hook: attack_cancel
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void bodycamcraft$fireInsteadOfPunch(CallbackInfoReturnable<Boolean> cir) {
		if (GunClient.holdingGun()) {
			cir.setReturnValue(false);
		}
	}

	// hook: attack_hold_cancel
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void bodycamcraft$noMiningWithAGun(boolean held, CallbackInfo ci) {
		if (GunClient.holdingGun()) {
			ci.cancel();
		}
	}

	// hook: use_cancel
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void bodycamcraft$aimInsteadOfUse(CallbackInfo ci) {
		if (GunClient.holdingGun() && !GunClient.useBelongsToTheWorld((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}
}
