package gg.bodycamcraft;

import com.mojang.serialization.Codec;
import gg.bodycamcraft.gen.Sheets;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** The items of the items sheet, and the component that stores the rounds left in a gun. */
public final class ModItems {
	// hook: rounds_component
	public static final DataComponentType<Integer> ROUNDS = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
			Identifier.fromNamespaceAndPath(BodycamCraft.MOD_ID, "rounds"),
			DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

	/** Registered items by their id in the items sheet. */
	public static final Map<String, Item> ITEMS = new LinkedHashMap<>();

	private ModItems() {
	}

	// hook: items
	static void register() {
		for (Sheets.Item row : Sheets.ITEMS) {
			Sheets.Weapon weapon = Sheets.weapon(row.weapon());
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(BodycamCraft.MOD_ID, row.id()));
			Item.Properties properties = new Item.Properties().stacksTo(row.stack());
			Item item = row.kind().equals("gun")
					? Items.registerItem(key, p -> new GunItem(p, weapon), properties.component(ROUNDS, 0))
					: Items.registerItem(key, p -> new MagazineItem(p, weapon), properties);
			ITEMS.put(row.id(), item);
		}
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.COMBAT).register(entries -> ITEMS.values().forEach(entries::accept));
	}

	public static final class GunItem extends Item {
		public final Sheets.Weapon weapon;

		GunItem(Properties properties, Sheets.Weapon weapon) {
			super(properties);
			this.weapon = weapon;
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> lines, TooltipFlag flag) {
			lines.accept(Component.translatable("bodycamcraft.tooltip.rounds", stack.getOrDefault(ROUNDS, 0), BodycamLibrary.magazineSize(weapon)));
		}

		/** Firing changes the round count on the stack; that must not replay the equip animation. */
		@Override
		public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
			return false;
		}
	}

	public static final class MagazineItem extends Item {
		public final Sheets.Weapon weapon;

		MagazineItem(Properties properties, Sheets.Weapon weapon) {
			super(properties);
			this.weapon = weapon;
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> lines, TooltipFlag flag) {
			lines.accept(Component.translatable("bodycamcraft.tooltip.magazine", BodycamLibrary.magazineSize(weapon)));
		}
	}
}
