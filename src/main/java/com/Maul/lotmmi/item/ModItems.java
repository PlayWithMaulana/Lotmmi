package com.Maul.lotmmi.item;

import com.Maul.lotmmi.LotmMysticalItems;
import com.Maul.lotmmi.item.custom.AllBlackEyeItem;
import com.Maul.lotmmi.item.custom.CreepingHungerItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry for all custom Mystical Items. Add future hand-built artifacts
 * here the same way CREEPING_HUNGER is registered below.
 */
public class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LotmMysticalItems.MOD_ID);

    public static final DeferredItem<CreepingHungerItem> CREEPING_HUNGER =
            ITEMS.register("creeping_hunger", () -> new CreepingHungerItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<AllBlackEyeItem> ALL_BLACK_EYE =
            ITEMS.register("all_black_eye", () -> new AllBlackEyeItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
