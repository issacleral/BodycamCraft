package gg.bodycamcraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.bodycamcraft.client.GunClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
	// hook: first_person_gun
	@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
	private void bodycamcraft$gun(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swing,
			ItemStack stack, float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (hand == InteractionHand.MAIN_HAND && GunClient.renderFirstPerson(stack, partialTick, pose, collector, light)) {
			ci.cancel();
		}
	}
}
