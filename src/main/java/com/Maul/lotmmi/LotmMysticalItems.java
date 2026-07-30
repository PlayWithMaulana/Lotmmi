package com.yourname.lotmmi;

import com.yourname.lotmmi.data.ModDataComponents;
import com.yourname.lotmmi.item.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(LotmMysticalItems.MOD_ID)
public class LotmMysticalItems {

    // Must match the modId in neoforge.mods.toml exactly.
    public static final String MOD_ID = "lotmmi";

    public LotmMysticalItems(IEventBus modEventBus) {
        ModDataComponents.register(modEventBus);
        ModItems.register(modEventBus);
    }
}
