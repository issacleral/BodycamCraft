package gg.bodycamcraft.client.mixin;

import gg.bodycamcraft.client.GunClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	// hook: free_aim
	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void bodycamcraft$freeAim(LocalPlayer player, double dx, double dy) {
		GunClient.onTurn(player, dx, dy);
	}
}
