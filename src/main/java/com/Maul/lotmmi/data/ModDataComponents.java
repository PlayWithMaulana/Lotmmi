package com.yourname.lotmmi.data;

import com.mojang.serialization.Codec;
import com.yourname.lotmmi.LotmMysticalItems;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Creeping Hunger reuses LotMCraft's own ModDataComponents.SEALED_ARTIFACT_DATA
 * and SEALED_ARTIFACT_SELECTED directly (they're component-based, not tied to
 * the SealedArtifactItem class specifically - confirmed by checking how the
 * Artifact Wheel keybind/menu detect eligible items). That's what gives us
 * the existing wheel, ability selection, cooldown and spirituality cost for
 * free, with zero new GUI code.
 *
 * We only need our own storage for soul-level bookkeeping - which souls are
 * currently held, whose they were, and which abilities in the shared pool
 * belong to which soul (needed so releasing one soul removes only its
 * abilities, not everyone else's).
 */
public class ModDataComponents {

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, LotmMysticalItems.MOD_ID);

    // Serialized soul list: pathway|sequence|ownerName|ownerUUID|abilityId1,abilityId2,abilityId3 ; next soul...
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> SOULS =
            DATA_COMPONENT_TYPES.register("souls", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    // The single "reserve"/food soul (a serialized SoulSlot, or "" when empty).
    // This is the 6th soul: it can NOT grant abilities and can NOT be cast with.
    // It exists purely so the hunger downside eats it instead of the wielder.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> FOOD_SOUL =
            DATA_COMPONENT_TYPES.register("food_soul", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    // Game-time tick this item was last fed - used for the hunger downside.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> LAST_FED_TICK =
            DATA_COMPONENT_TYPES.register("last_fed_tick", () -> DataComponentType.<Long>builder()
                    .persistent(Codec.LONG)
                    .networkSynchronized(ByteBufCodecs.VAR_LONG)
                    .build());

    public static void register(IEventBus eventBus) {
        DATA_COMPONENT_TYPES.register(eventBus);
    }
}
