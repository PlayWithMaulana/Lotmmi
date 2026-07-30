package com.yourname.lotmmi.events;

import com.yourname.lotmmi.LotmMysticalItems;
import com.yourname.lotmmi.command.CreepingHungerCommand;
import com.yourname.lotmmi.item.custom.CreepingHungerItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = LotmMysticalItems.MOD_ID)
public class ModEvents {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CreepingHungerCommand.register(event.getDispatcher());
    }

    /**
     * Automatically grazes a dying Beyonder's soul if the killer is holding
     * Creeping Hunger in either hand - matches canon's "grazing happens as
     * they die" flavor, and removes the need to also sneak+right-click a
     * target that's about to die anyway.
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide) return;

        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;

        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);

        ItemStack glove;
        if (main.getItem() instanceof CreepingHungerItem) {
            glove = main;
        } else if (off.getItem() instanceof CreepingHungerItem) {
            glove = off;
        } else {
            return;
        }

        CreepingHungerItem.grazeSoul(player, target, glove);
    }

    /**
     * BUGFIX: Creeping Hunger vanishing when opening any container (chest,
     * barrel, ender chest, Sophisticated Backpacks, etc.) after grazing a soul.
     *
     * ROOT CAUSE: LotMCraft's BeyonderEventHandler.onContainerOpen (a
     * PlayerContainerEvent.Open listener) scans every slot on container open
     * and deletes any stack carrying SEALED_ARTIFACT_DATA when the artifact
     * has no negative effects (the allowArtifactsWithNoNegatives gamerule is
     * false by default) or when the holder isn't "valid" for that
     * pathway/sequence. Creeping Hunger reuses SEALED_ARTIFACT_DATA and
     * intentionally stores an empty negative-effects list (it uses its own
     * hunger downside instead), so that anti-cheat handler wipes it every time
     * a container opens - but only once a soul has been grazed and the
     * component actually exists, which is exactly the reported symptom.
     *
     * FIX: we listen to the same event at LOWEST priority. NeoForge dispatches
     * listeners high -> low, so the base mod's deletion runs first. We snapshot
     * any Creeping Hunger stacks beforehand and restore any that were emptied.
     * Real Sealed Artifacts are never touched because we only ever act on our
     * own CreepingHungerItem instances.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void protectCreepingHunger(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        AbstractContainerMenu menu = event.getContainer();
        if (menu == null) return;

        List<Guarded> guarded = new ArrayList<>();
        for (Slot slot : menu.slots) {
            ItemStack inSlot = slot.getItem();
            if (inSlot.getItem() instanceof CreepingHungerItem) {
                guarded.add(new Guarded(slot, inSlot.copy()));
            }
        }
        if (guarded.isEmpty()) return;

        boolean restored = false;
        for (Guarded g : guarded) {
            if (g.slot().getItem().isEmpty()) {
                g.slot().set(g.backup().copy());
                restored = true;
            }
        }

        if (restored) {
            menu.broadcastChanges();
            MinecraftServer server = player.getServer();
            if (server != null) {
                // Re-assert next tick as well, in case the base handler defers
                // its own removal, so our restore always has the final say.
                server.execute(() -> {
                    boolean reRestored = false;
                    for (Guarded g : guarded) {
                        if (g.slot().getItem().isEmpty()) {
                            g.slot().set(g.backup().copy());
                            reRestored = true;
                        }
                    }
                    if (reRestored) menu.broadcastChanges();
                });
            }
        }
    }

    private record Guarded(Slot slot, ItemStack backup) {}
}
