package com.Maul.lotmmi.events;

import com.Maul.lotmmi.LotmMysticalItems;
import com.Maul.lotmmi.command.CreepingHungerCommand;
import com.Maul.lotmmi.item.custom.AllBlackEyeItem;
import com.Maul.lotmmi.item.custom.CreepingHungerItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return; // every second

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
            ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);

            if (main.getItem() instanceof AllBlackEyeItem) {
                AllBlackEyeItem.tickEye(player, main);
            }
            if (off.getItem() instanceof AllBlackEyeItem) {
                AllBlackEyeItem.tickEye(player, off);
            }
        }
    }
}
