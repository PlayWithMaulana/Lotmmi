package com.yourname.lotmmi.item.custom;

import com.yourname.lotmmi.data.ModDataComponents;
import de.jakob.lotm.LOTMCraft;
import de.jakob.lotm.attachments.DoorAuthorityData;
import de.jakob.lotm.beyonders.abilities.core.Ability;
import de.jakob.lotm.beyonders.artifacts.SealedArtifactData;
import de.jakob.lotm.beyonders.artifacts.NegativeEffect;
import de.jakob.lotm.beyonders.potions.BeyonderCharacteristicItem;
import de.jakob.lotm.beyonders.potions.BeyonderCharacteristicItemHandler;
import de.jakob.lotm.gamerule.ModGameRules;
import de.jakob.lotm.util.BeyonderData;
import de.jakob.lotm.util.helper.AbilityUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Creeping Hunger - a hand-built Sealed Artifact modeled on the Hanged Man
 * Pathway's Shepherd ability.
 *
 * DESIGN: rather than building a separate custom ability-storage/UI system,
 * this reuses LotMCraft's own de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA
 * and SEALED_ARTIFACT_SELECTED components directly. Those are detected by
 * the Artifact Wheel keybind/menu purely by component presence (stack.has(...)),
 * not by checking for the SealedArtifactItem class - confirmed directly in
 * LotMCraft's KeyInputHandler. That means Creeping Hunger gets the existing
 * wheel, ability selection, and right-click-to-cast (with normal per-ability
 * cooldown and spirituality cost, fully repeatable) completely for free.
 *
 * Mechanic: 5 soul slots. Grazing a Beyonder (sneak+right-click, or
 * automatically on landing the killing blow while holding this in either
 * hand) grants 3 random abilities from their pathway/sequence, permanently
 * usable via the normal Artifact Wheel for as long as that soul is held.
 * It also "eats" that Beyonder's Characteristic (pathway+sequence
 * equivalent) - releasing the soul later spits that Characteristic back out
 * for the player to pick up, and strips that soul's 3 abilities back out of
 * the shared pool.
 */
public class CreepingHungerItem extends Item {

    public static final int MAX_SLOTS = 5;
    public static final int ABILITIES_PER_SOUL = 3;
    public static final long FEED_INTERVAL_TICKS = 20L * 60 * 60 * 24; // 24 in-game hours, matching canon's "once a day" downside
    private static final Random RANDOM = new Random();

    public CreepingHungerItem(Properties properties) {
        super(properties);
    }

    /**
     * A single TOKEN negative effect used only so SEALED_ARTIFACT_DATA.negativeEffect()
     * is NOT empty. The base mod's BeyonderEventHandler.onContainerOpen() DELETES any
     * stack carrying SEALED_ARTIFACT_DATA whose negative-effect list is empty (unless the
     * allowArtifactsWithNoNegatives gamerule is on) - that was wiping this glove every
     * time any container (chest/barrel/ender chest/backpack) was opened after grazing.
     *
     * Giving the data a non-empty list makes it no longer qualify for that deletion.
     * Crucially this token effect NEVER actually harms the wielder: the base mod's
     * SealedArtifactEffectHandler only ticks negative effects on stacks that are
     * `instanceof SealedArtifactItem`, and this glove is our own class, so no effect
     * in this list is ever applied. We use our own hunger mechanic for the downside.
     */
    private static List<NegativeEffect> tokenNegatives() {
        List<NegativeEffect> list = new ArrayList<>();
        // CURSED is a use-only-tick effect in the base mod, so even on a real artifact it
        // would only ever matter on ability use, not passively. On our glove it is inert.
        list.add(new NegativeEffect(NegativeEffect.NegativeEffectType.CURSED, 0, null, 0));
        return list;
    }

    // ---------------------------------------------------------------
    // Soul bookkeeping model (our own component, separate from the
    // shared ability pool that lives in SEALED_ARTIFACT_DATA)
    // ---------------------------------------------------------------

    public record SoulSlot(String pathway, int sequence, String ownerName, String ownerUUID, List<String> abilityIds) {

        public String serialize() {
            return pathway + "|" + sequence + "|" + ownerName + "|" + ownerUUID + "|" + String.join(",", abilityIds);
        }

        public static SoulSlot deserialize(String raw) {
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 5) return null;
            try {
                List<String> ids = parts[4].isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(parts[4].split(",")));
                return new SoulSlot(parts[0], Integer.parseInt(parts[1]), parts[2], parts[3], ids);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public static List<SoulSlot> getSouls(ItemStack stack) {
        String raw = stack.getOrDefault(ModDataComponents.SOULS.get(), "");
        List<SoulSlot> souls = new ArrayList<>();
        if (raw.isEmpty()) return souls;
        for (String entry : raw.split(";")) {
            SoulSlot slot = SoulSlot.deserialize(entry);
            if (slot != null) souls.add(slot);
        }
        return souls;
    }

    public static void setSouls(ItemStack stack, List<SoulSlot> souls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < souls.size(); i++) {
            if (i > 0) sb.append(";");
            sb.append(souls.get(i).serialize());
        }
        stack.set(ModDataComponents.SOULS.get(), sb.toString());
    }

    // ---------------------------------------------------------------
    // The reserve / "food" soul (the 6th soul). It never joins the ability
    // pool and can never be cast with - it exists only to be devoured by the
    // hunger downside so the wielder isn't the one that gets gnawed on.
    // ---------------------------------------------------------------

    public static SoulSlot getFoodSoul(ItemStack stack) {
        String raw = stack.getOrDefault(ModDataComponents.FOOD_SOUL.get(), "");
        if (raw.isEmpty()) return null;
        return SoulSlot.deserialize(raw);
    }

    public static void setFoodSoul(ItemStack stack, SoulSlot soul) {
        stack.set(ModDataComponents.FOOD_SOUL.get(), soul == null ? "" : soul.serialize());
    }

    public static boolean hasFoodSoul(ItemStack stack) {
        return getFoodSoul(stack) != null;
    }

    /**
     * Store a grazed soul directly into the reserve/food slot instead of the 5
     * active slots. Used when the active slots are full, so excess souls can be
     * kept to satisfy the hunger downside later. Does NOT grant any abilities.
     */
    public static boolean feedSoul(ServerPlayer player, ItemStack glove, LivingEntity target) {
        if (!BeyonderData.isBeyonder(target)) {
            player.sendSystemMessage(Component.literal("This target has no soul worth feeding on.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (hasFoodSoul(glove)) {
            player.sendSystemMessage(Component.literal("Creeping Hunger's reserve is already full.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        String pathway = BeyonderData.getPathway(target);
        int sequence = BeyonderData.getSequence(target);
        SoulSlot food = new SoulSlot(pathway, sequence, target.getName().getString(),
                target.getUUID().toString(), new ArrayList<>());
        setFoodSoul(glove, food);

        player.sendSystemMessage(Component.literal(
                "Fed the soul of " + target.getName().getString() + " into the reserve. It will be devoured before you are."
        ).withStyle(ChatFormatting.DARK_PURPLE));
        return true;
    }

    /**
     * Move one of the 5 active souls into the reserve/food slot. Its abilities are
     * stripped from the shared pool (just like releasing it), but instead of being
     * spat back out it's kept as food for the hunger downside.
     */
    public static void moveToReserve(ServerPlayer player, ItemStack glove, int slotIndex) {
        List<SoulSlot> souls = getSouls(glove);
        if (slotIndex < 0 || slotIndex >= souls.size()) return;
        if (hasFoodSoul(glove)) {
            player.sendSystemMessage(Component.literal("The reserve is already full.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        SoulSlot moved = souls.remove(slotIndex);
        setSouls(glove, souls);

        // Strip this soul's abilities out of the shared pool (same as releaseSoul,
        // minus spitting out the characteristic - it becomes food instead).
        SealedArtifactData existing = glove.get(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get());
        if (existing != null) {
            List<Ability> remaining = existing.abilities().stream()
                    .filter(a -> !moved.abilityIds().contains(a.getId()))
                    .toList();
            if (remaining.isEmpty()) {
                glove.remove(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get());
                glove.remove(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get());
            } else {
                SoulSlot fallback = souls.isEmpty() ? moved : souls.get(souls.size() - 1);
                SealedArtifactData newData = new SealedArtifactData(
                        fallback.pathway(), fallback.sequence(), remaining, tokenNegatives());
                glove.set(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get(), newData);
                int selected = glove.getOrDefault(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get(), 0);
                if (selected >= remaining.size()) {
                    glove.set(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get(), 0);
                }
            }
        }

        // The stored food soul grants nothing, so drop its ability ids.
        setFoodSoul(glove, new SoulSlot(moved.pathway(), moved.sequence(),
                moved.ownerName(), moved.ownerUUID(), new ArrayList<>()));

        player.sendSystemMessage(Component.literal(
                "Moved the soul of " + moved.ownerName() + " into the reserve. It will be devoured before you are."
        ).withStyle(ChatFormatting.DARK_PURPLE));
    }

    /** Finds which held soul (if any) originally granted a given ability id. */
    private static SoulSlot findSoulForAbility(List<SoulSlot> souls, String abilityId) {
        for (SoulSlot soul : souls) {
            if (soul.abilityIds().contains(abilityId)) return soul;
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Grazing (stealing a soul) - happens automatically when the wielder lands
    // the killing blow on a Beyonder while holding this in either hand
    // (see ModEvents.onLivingDeath). There is no manual sneak+right-click graze.
    // ---------------------------------------------------------------

    public static void grazeSoul(ServerPlayer player, LivingEntity target, ItemStack glove) {
        if (!BeyonderData.isBeyonder(target)) {
            player.sendSystemMessage(Component.literal("This target has no soul worth grazing.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        List<SoulSlot> souls = getSouls(glove);
        if (souls.size() >= MAX_SLOTS) {
            // Active slots are full. Try to stash this soul in the reserve/food slot
            // instead so it can feed the hunger later, rather than losing it.
            if (!hasFoodSoul(glove)) {
                feedSoul(player, glove, target);
            } else {
                player.sendSystemMessage(Component.literal("Creeping Hunger holds " + MAX_SLOTS
                        + " souls and its reserve is full. Release one first.")
                        .withStyle(ChatFormatting.RED));
            }
            return;
        }

        String targetPathway = BeyonderData.getPathway(target);
        int targetSequence = BeyonderData.getSequence(target);

        // Roll 3 random abilities from the target's pathway/sequence.
        List<Ability> excluded = new ArrayList<>();
        List<Ability> granted = new ArrayList<>();
        for (int i = 0; i < ABILITIES_PER_SOUL; i++) {
            Ability ability = LOTMCraft.abilityHandler.getRandomAbility(
                    targetPathway, targetSequence, RANDOM, i == 0, excluded);
            if (ability == null || !ability.canBeCopied) continue;
            excluded.add(ability);
            granted.add(ability);
        }

        if (granted.isEmpty()) {
            player.sendSystemMessage(Component.literal("This soul's power resists being grazed.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // Merge into the shared ability pool (SEALED_ARTIFACT_DATA), creating it if this is the first soul.
        SealedArtifactData existing = glove.get(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get());
        List<Ability> combinedAbilities = new ArrayList<>();
        if (existing != null) combinedAbilities.addAll(existing.abilities());
        combinedAbilities.addAll(granted);

        SealedArtifactData newData = new SealedArtifactData(
                targetPathway,
                targetSequence,
                combinedAbilities,
                tokenNegatives() // inert token effect - only present so the base mod's
                                 // container-open cleanup doesn't treat this as a no-downside
                                 // cheat artifact and delete it. Our real downside is hunger.
        );
        glove.set(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get(), newData);

        // Track which abilities belong to this soul, and "eat" their Characteristic.
        List<String> grantedIds = granted.stream().map(Ability::getId).toList();
        SoulSlot soul = new SoulSlot(targetPathway, targetSequence, target.getName().getString(), target.getUUID().toString(), grantedIds);
        souls.add(soul);
        setSouls(glove, souls);

        player.sendSystemMessage(Component.literal(
                "Grazed the soul of " + target.getName().getString() + " (" + targetPathway + " Sequence " + targetSequence
                        + ") - " + granted.size() + " abilities added to your Artifact Wheel."
        ).withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    // ---------------------------------------------------------------
    // Releasing a soul - removes its abilities from the shared pool and
    // spits out the eaten Characteristic
    // ---------------------------------------------------------------

    public static void releaseSoul(ServerPlayer player, ItemStack glove, int slotIndex) {
        List<SoulSlot> souls = getSouls(glove);
        if (slotIndex < 0 || slotIndex >= souls.size()) return;

        SoulSlot released = souls.remove(slotIndex);
        setSouls(glove, souls);

        // Strip this soul's abilities out of the shared pool.
        SealedArtifactData existing = glove.get(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get());
        if (existing != null) {
            List<Ability> remaining = existing.abilities().stream()
                    .filter(a -> !released.abilityIds().contains(a.getId()))
                    .toList();

            if (remaining.isEmpty()) {
                glove.remove(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get());
                glove.remove(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get());
            } else {
                // Re-flavor the shared pathway/sequence display using whichever soul is now "freshest".
                SoulSlot fallback = souls.isEmpty() ? released : souls.get(souls.size() - 1);
                SealedArtifactData newData = new SealedArtifactData(
                        fallback.pathway(), fallback.sequence(), remaining, tokenNegatives());
                glove.set(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get(), newData);

                int selected = glove.getOrDefault(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get(), 0);
                if (selected >= remaining.size()) {
                    glove.set(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get(), 0);
                }
            }
        }

        // Spit the eaten Characteristic back out.
        BeyonderCharacteristicItem characteristic = findCharacteristicItem(released.pathway(), released.sequence());
        if (characteristic != null) {
            ItemStack spat = new ItemStack(characteristic);
            if (!player.getInventory().add(spat)) {
                player.drop(spat, false);
            }
        }

        glove.set(ModDataComponents.LAST_FED_TICK.get(), player.level().getGameTime());
        player.heal(4.0f);

        player.sendSystemMessage(Component.literal(
                "Released the soul of " + released.ownerName() + ". Its Characteristic has been spat back out."
        ).withStyle(ChatFormatting.DARK_PURPLE));
    }

    private static BeyonderCharacteristicItem findCharacteristicItem(String pathway, int sequence) {
        return BeyonderCharacteristicItemHandler.ITEMS.getEntries().stream()
                .map(DeferredHolder::get)
                .filter(i -> i instanceof BeyonderCharacteristicItem)
                .map(i -> (BeyonderCharacteristicItem) i)
                .filter(c -> c.getPathway().equals(pathway) && c.getSequence() == sequence)
                .findFirst()
                .orElse(null);
    }

    // ---------------------------------------------------------------
    // Right-click to cast the currently-selected ability - mirrors
    // LotMCraft's own SealedArtifactItem.use() logic exactly, so
    // cooldowns, spirituality cost, and the Door Authority / artifact
    // gamerule checks all behave identically to a real sealed artifact.
    // ---------------------------------------------------------------

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        if (!level.getGameRules().getBoolean(ModGameRules.ALLOW_ARTIFACTS)) {
            return InteractionResultHolder.fail(stack);
        }

        DoorAuthorityData doorData = DoorAuthorityData.get((ServerLevel) level);
        if (doorData.isActive() && doorData.getEffectId().equalsIgnoreCase("strengthen")) {
            de.jakob.lotm.util.helper.ParticleUtil.spawnParticles((ServerLevel) level, ParticleTypes.END_ROD, player.getEyePosition(), 40, .5, .05);
            return InteractionResultHolder.fail(stack);
        }

        SealedArtifactData data = stack.get(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get());
        if (data == null || data.abilities().isEmpty()) {
            player.sendSystemMessage(Component.literal("Creeping Hunger holds no souls to draw power from.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(stack);
        }

        int selectedIndex = stack.getOrDefault(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get(), 0);
        if (selectedIndex >= data.abilities().size()) selectedIndex = 0;
        Ability ability = data.abilities().get(selectedIndex);

        // Scale off whichever soul actually granted this specific ability, rather
        // than the artifact-wide fallback pathway/sequence.
        List<SoulSlot> souls = getSouls(stack);
        SoulSlot owningSoul = findSoulForAbility(souls, ability.getId());
        String scalePathway = owningSoul != null ? owningSoul.pathway() : data.pathway();
        int scaleSequence = owningSoul != null ? owningSoul.sequence() : data.sequence();
        AbilityUtil.setArtifactScaling(player, scalePathway, scaleSequence);

        // consumeSpirituality=true, hasToHaveAbility=false, hasToMeetRequirements=true, isCopied=false
        // - identical parameters to a real Sealed Artifact, so normal per-ability
        // cooldown and spirituality cost apply, fully repeatable.
        ability.useAbility((ServerLevel) level, player, true, false, true, false);

        return InteractionResultHolder.success(stack);
    }

    // ---------------------------------------------------------------
    // The hunger downside
    // ---------------------------------------------------------------

    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) return;

        long lastFed = stack.getOrDefault(ModDataComponents.LAST_FED_TICK.get(), level.getGameTime());
        long ticksSinceFed = level.getGameTime() - lastFed;

        if (ticksSinceFed < FEED_INTERVAL_TICKS) return;

        // The hunger now feeds ONLY on the separate reserve/food soul. The 5 active
        // souls (and the abilities they grant) are never auto-consumed anymore.
        SoulSlot food = getFoodSoul(stack);
        if (food != null) {
            // Devour the reserve soul. It granted no abilities, so nothing to strip
            // from the pool - just clear it and reset the timer.
            setFoodSoul(stack, null);
            stack.set(ModDataComponents.LAST_FED_TICK.get(), level.getGameTime());
            player.sendSystemMessage(Component.literal(
                    "Creeping Hunger devours its reserve soul (" + food.ownerName() + ")."
            ).withStyle(ChatFormatting.DARK_PURPLE));
        } else {
            // No reserve soul stored: the mystical item, unfed, feeds on the wielder.
            player.hurt(player.damageSources().magic(), 4.0f);
            player.sendSystemMessage(Component.literal(
                    "Creeping Hunger, its reserve empty, gnaws at your own flesh instead. Feed it a soul."
            ).withStyle(ChatFormatting.DARK_RED));
            stack.set(ModDataComponents.LAST_FED_TICK.get(), level.getGameTime());
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable(this.getDescriptionId(stack));
    }

    /** Always show the enchantment-glint shimmer so the glove reads as a mystical weapon. */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        List<SoulSlot> souls = getSouls(stack);
        tooltip.add(Component.literal("Souls held: " + souls.size() + "/" + MAX_SLOTS).withStyle(ChatFormatting.DARK_PURPLE));

        for (int i = 0; i < souls.size(); i++) {
            SoulSlot s = souls.get(i);
            tooltip.add(Component.literal("[" + i + "] " + s.ownerName() + " - " + s.pathway() + " Seq " + s.sequence())
                    .withStyle(ChatFormatting.GRAY));
        }

        SoulSlot food = getFoodSoul(stack);
        if (food != null) {
            tooltip.add(Component.literal("Reserve (food): " + food.ownerName() + " - "
                    + food.pathway() + " Seq " + food.sequence()).withStyle(ChatFormatting.DARK_GREEN));
        } else {
            tooltip.add(Component.literal("Reserve (food): empty - feed a soul or it will gnaw on you")
                    .withStyle(ChatFormatting.DARK_RED));
        }

        SealedArtifactData data = stack.get(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get());
        if (data != null && !data.abilities().isEmpty()) {
            int selected = stack.getOrDefault(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get(), 0);
            if (selected < data.abilities().size()) {
                tooltip.add(Component.literal("Selected: " + data.abilities().get(selected).getId())
                        .withStyle(ChatFormatting.GOLD));
            }
        }

        tooltip.add(Component.literal("Kill a Beyonder while holding this (either hand) to graze | Open Artifact Wheel to select/cast")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Killing with 5 souls full stashes the extra in the reserve (food)")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("/creepinghunger list | release <slot> | reserve <slot>")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
