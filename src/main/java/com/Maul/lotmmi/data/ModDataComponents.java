package com.Maul.lotmmi.data;

import com.mojang.serialization.Codec;
import com.Maul.lotmmi.LotmMysticalItems;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModDataComponents {

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LotmMysticalItems.MOD_ID);

    // =========================
    // Creeping Hunger
    // =========================

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> SOULS =
            DATA_COMPONENT_TYPES.register("souls", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> FOOD_SOUL =
            DATA_COMPONENT_TYPES.register("food_soul", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> LAST_FED_TICK =
            DATA_COMPONENT_TYPES.register("last_fed_tick", () -> DataComponentType.<Long>builder()
                    .persistent(Codec.LONG)
                    .networkSynchronized(ByteBufCodecs.VAR_LONG)
                    .build());

    // =========================
    // All Black Eye
    // =========================

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> SIN_STACKS =
            DATA_COMPONENT_TYPES.register("sin_stacks", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> LAST_DEGENERATION_TICK =
            DATA_COMPONENT_TYPES.register("last_degeneration_tick", () -> DataComponentType.<Long>builder()
                    .persistent(Codec.LONG)
                    .networkSynchronized(ByteBufCodecs.VAR_LONG)
                    .build());

    // --- Tinder ---------------------------------------------------------
    // Game-time tick until which Tinder is "charged" (able to steal abilities).
    // Charging costs one of the wearer's own abilities for 12 in-game hours;
    // while charged, stealing is unlimited (gated only by the theft ability's
    // own normal cooldown/spirituality cost).
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> TINDER_CHARGED_UNTIL =
            DATA_COMPONENT_TYPES.register("tinder_charged_until", () -> DataComponentType.<Long>builder()
                    .persistent(Codec.LONG)
                    .networkSynchronized(ByteBufCodecs.VAR_LONG)
                    .build());

    // Serialized list of abilities currently on loan from a theft: "abilityId:expiryGameTick;...".
    // Each entry is stripped from the wearer's Copied Ability wheel once its 10 in-game-minute
    // loan expires (checked in TinderItem#inventoryTick).
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> TINDER_STOLEN =
            DATA_COMPONENT_TYPES.register("tinder_stolen", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    // Game-time tick Tinder last rolled its "wearer is more likely to lose carried items" downside.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> TINDER_LAST_LOSS_TICK =
            DATA_COMPONENT_TYPES.register("tinder_last_loss_tick", () -> DataComponentType.<Long>builder()
                    .persistent(Codec.LONG)
                    .networkSynchronized(ByteBufCodecs.VAR_LONG)
                    .build());

    public static void register(IEventBus eventBus) {
        DATA_COMPONENT_TYPES.register(eventBus);
    }
}
