package com.Maul.lotmmi.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.Maul.lotmmi.item.custom.CreepingHungerItem;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * /creepinghunger release <slot>  - releases a specific held soul on purpose
 * /creepinghunger list            - lists currently held souls
 *
 * Available to all players (permission level 0) - normal gameplay, not an
 * admin/debug tool.
 */
public class CreepingHungerCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("creepinghunger")
                .then(Commands.literal("release")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    int slot = IntegerArgumentType.getInteger(context, "slot");

                                    if (!(source.getEntity() instanceof ServerPlayer player)) {
                                        source.sendFailure(Component.literal("This command can only be used by a player."));
                                        return 0;
                                    }

                                    ItemStack held = findGlove(player);
                                    if (held.isEmpty()) {
                                        source.sendFailure(Component.literal("You must be holding Creeping Hunger in your main or off hand."));
                                        return 0;
                                    }

                                    List<CreepingHungerItem.SoulSlot> souls = CreepingHungerItem.getSouls(held);
                                    if (slot < 0 || slot >= souls.size()) {
                                        source.sendFailure(Component.literal(
                                                "Invalid slot. Creeping Hunger currently holds " + souls.size() + " soul(s) (valid: 0-" + (souls.size() - 1) + ")."
                                        ));
                                        return 0;
                                    }

                                    CreepingHungerItem.releaseSoul(player, held, slot);
                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("reserve")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    int slot = IntegerArgumentType.getInteger(context, "slot");

                                    if (!(source.getEntity() instanceof ServerPlayer player)) {
                                        source.sendFailure(Component.literal("This command can only be used by a player."));
                                        return 0;
                                    }

                                    ItemStack held = findGlove(player);
                                    if (held.isEmpty()) {
                                        source.sendFailure(Component.literal("You must be holding Creeping Hunger in your main or off hand."));
                                        return 0;
                                    }

                                    List<CreepingHungerItem.SoulSlot> souls = CreepingHungerItem.getSouls(held);
                                    if (slot < 0 || slot >= souls.size()) {
                                        source.sendFailure(Component.literal(
                                                "Invalid slot. Creeping Hunger currently holds " + souls.size() + " active soul(s)."
                                        ));
                                        return 0;
                                    }

                                    CreepingHungerItem.moveToReserve(player, held, slot);
                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("list")
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();

                            if (!(source.getEntity() instanceof ServerPlayer player)) {
                                source.sendFailure(Component.literal("This command can only be used by a player."));
                                return 0;
                            }

                            ItemStack held = findGlove(player);
                            if (held.isEmpty()) {
                                source.sendFailure(Component.literal("You must be holding Creeping Hunger in your main or off hand."));
                                return 0;
                            }

                            List<CreepingHungerItem.SoulSlot> souls = CreepingHungerItem.getSouls(held);
                            CreepingHungerItem.SoulSlot food = CreepingHungerItem.getFoodSoul(held);

                            if (souls.isEmpty() && food == null) {
                                player.sendSystemMessage(Component.literal("Creeping Hunger holds no souls.")
                                        .withStyle(ChatFormatting.GRAY));
                                return 1;
                            }

                            player.sendSystemMessage(Component.literal("Creeping Hunger's held souls:")
                                    .withStyle(ChatFormatting.DARK_PURPLE));
                            for (int i = 0; i < souls.size(); i++) {
                                CreepingHungerItem.SoulSlot s = souls.get(i);
                                player.sendSystemMessage(Component.literal(
                                        "[" + i + "] " + s.ownerName() + " - " + s.pathway() + " Seq " + s.sequence()
                                                + " (" + s.abilityIds().size() + " abilities)"
                                ).withStyle(ChatFormatting.LIGHT_PURPLE));
                            }
                            if (food != null) {
                                player.sendSystemMessage(Component.literal(
                                        "Reserve (food): " + food.ownerName() + " - " + food.pathway()
                                                + " Seq " + food.sequence()
                                ).withStyle(ChatFormatting.DARK_GREEN));
                            } else {
                                player.sendSystemMessage(Component.literal(
                                        "Reserve (food): empty"
                                ).withStyle(ChatFormatting.DARK_RED));
                            }
                            return 1;
                        })
                )
        );
    }

    private static ItemStack findGlove(ServerPlayer player) {
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (main.getItem() instanceof CreepingHungerItem) return main;
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        if (off.getItem() instanceof CreepingHungerItem) return off;
        return ItemStack.EMPTY;
    }
}
