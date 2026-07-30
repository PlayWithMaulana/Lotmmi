package com.Maul.lotmmi.item.custom;

import de.jakob.lotm.LOTMCraft;
import de.jakob.lotm.attachments.DoorAuthorityData;
import de.jakob.lotm.beyonders.abilities.core.Ability;
import de.jakob.lotm.beyonders.artifacts.NegativeEffect;
import de.jakob.lotm.beyonders.artifacts.SealedArtifactData;
import de.jakob.lotm.gamerule.ModGameRules;
import de.jakob.lotm.util.BeyonderData;
import de.jakob.lotm.util.helper.AbilityUtil;
import de.jakob.lotm.util.helper.marionettes.MarionetteUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * All-Black Eye - a hand-built Sealed Artifact on the Fool / Hanged Man Pathway.
 *
 * Just like {@link CreepingHungerItem}, this reuses LotMCraft's own
 * de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA and
 * SEALED_ARTIFACT_SELECTED components directly. The Artifact Wheel keybind/menu
 * detect eligible stacks purely by component presence (stack.has(...)), NOT by
 * checking for the SealedArtifactItem class, so this gets the existing wheel,
 * ability selection, and right-click-to-cast (with normal per-ability cooldown
 * and spirituality cost, fully repeatable) completely for free.
 *
 * Core: a fixed 2-ability wheel wrapping the base mod's Fool marionette powers:
 *   - marionette_controlling_ability (Fool Seq 4)
 *   - puppeteering_ability           (Fool Seq 5)
 * These are baked in the first time the item is used or its tooltip is built,
 * so a creative-spawned eye is immediately usable with no grazing required.
 *
 * Hanged Man extras (the "corruption cost" layer, all built on confirmed base-mod
 * APIs - no invented effects):
 *   1. LISTENING / remote sight  - holding the eye tags nearby entities with the
 *      base mod's SPIRIT_CALLED... we can't read that safely across versions, so
 *      instead we grant the WIELDER short GLOWING vision of nearby living things
 *      when the eye is the selected/held item, mimicking Hanged Man remote sense.
 *   2. Marionette empowerment    - the wielder's active marionettes are kept
 *      FOOLING (the base mod's puppet effect) so they stay firmly under control.
 *   3. Sin-bearing cost          - the eye slowly drinks the wielder's own
 *      spirituality while held, and periodically inflicts a brief WEAKNESS as the
 *      "All-Black" corruption gnaws back. This is the downside that also keeps the
 *      base mod's container-open cleanup from deleting the artifact (non-empty
 *      negative-effect token list, see tokenNegatives()).
 */
public class AllBlackEyeItem extends Item {

    private static final String MARIONETTE_ABILITY_ID = "marionette_controlling_ability";
    private static final String PUPPETEER_ABILITY_ID  = "puppeteering_ability";

    // Fool pathway, Sequence 5 (Marionettist) - what the wheel abilities scale off.
    private static final String SCALE_PATHWAY  = "fool";
    private static final int    SCALE_SEQUENCE = 5;

    // Corruption cadence: once every 30s the eye drinks spirituality + nips the wielder.
    private static final long CORRUPTION_INTERVAL_TICKS = 20L * 30;
    private static final float SPIRITUALITY_DRAIN = 10f;
    private static final double SIGHT_RADIUS = 24.0;

    public AllBlackEyeItem(Properties properties) {
        super(properties);
    }

    /**
     * Inert token so SEALED_ARTIFACT_DATA.negativeEffect() is never empty. The base
     * mod deletes any SEALED_ARTIFACT_DATA stack with an empty negative list when a
     * container is opened (unless the allowArtifactsWithNoNegatives gamerule is set).
     * The effect never actually fires on us because SealedArtifactEffectHandler only
     * ticks negatives on stacks that are `instanceof SealedArtifactItem`, which this
     * is not. Our real downside is applyCorruption().
     */
    private static List<NegativeEffect> tokenNegatives() {
        List<NegativeEffect> list = new ArrayList<>();
        list.add(new NegativeEffect(NegativeEffect.NegativeEffectType.CURSED, 0, null, 0));
        return list;
    }

    /**
     * Bake the two Fool marionette abilities into the shared SEALED_ARTIFACT_DATA
     * pool if they aren't there yet. Idempotent - safe to call every tick / tooltip.
     */
    private static void ensureAbilities(ItemStack stack) {
        SealedArtifactData existing = stack.get(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get());
        if (existing != null && existing.abilities().size() >= 2) return;

        List<Ability> abilities = new ArrayList<>();
        Ability marionette = LOTMCraft.abilityHandler.getById(MARIONETTE_ABILITY_ID);
        Ability puppeteer = LOTMCraft.abilityHandler.getById(PUPPETEER_ABILITY_ID);
        if (marionette != null) abilities.add(marionette);
        if (puppeteer != null) abilities.add(puppeteer);
        if (abilities.isEmpty()) return;

        SealedArtifactData data = new SealedArtifactData(SCALE_PATHWAY, SCALE_SEQUENCE, abilities, tokenNegatives());
        stack.set(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get(), data);
        if (!stack.has(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get())) {
            stack.set(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get(), 0);
        }
    }

    // ---------------------------------------------------------------
    // Right-click to cast the currently-selected ability - mirrors
    // LotMCraft's SealedArtifactItem.use() so cooldowns, spirituality
    // cost and the Door Authority / artifact gamerule checks all match
    // a real sealed artifact exactly.
    // ---------------------------------------------------------------

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        ensureAbilities(stack);

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
            player.sendSystemMessage(Component.literal("The All-Black Eye stares, but finds no strings to pull.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(stack);
        }

        int selectedIndex = stack.getOrDefault(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get(), 0);
        if (selectedIndex >= data.abilities().size()) selectedIndex = 0;
        Ability ability = data.abilities().get(selectedIndex);

        AbilityUtil.setArtifactScaling(player, SCALE_PATHWAY, SCALE_SEQUENCE);

        // consumeSpirituality=true, hasToHaveAbility=false, hasToMeetRequirements=true, isCopied=false
        // - identical to a real Sealed Artifact, so normal cooldown + spirituality cost apply.
        ability.useAbility((ServerLevel) level, player, true, false, true, false);

        return InteractionResultHolder.success(stack);
    }

    // ---------------------------------------------------------------
    // Hanged Man corruption + remote sight, ticked while held.
    // ---------------------------------------------------------------

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) return;

        ensureAbilities(stack);

        long now = level.getGameTime();

        // 1 & 2: while actively held/selected, the eye "watches" for the wielder and
        // keeps their marionettes bound. Cheap, runs a couple times a second at most.
        if (isSelected && now % 20 == 0) {
            grantRemoteSight(player, (ServerLevel) level);
            keepMarionettesBound(player, (ServerLevel) level);
        }

        // 3: the corruption cost, on its own slower cadence.
        long lastCorruption = readCorruptionTick(stack, now);
        if (now - lastCorruption >= CORRUPTION_INTERVAL_TICKS) {
            applyCorruption(player);
            writeCorruptionTick(stack, now);
        }
    }

    // The eye reveals nearby living things to its bearer (Hanged Man remote sense).
    private static void grantRemoteSight(ServerPlayer player, ServerLevel level) {
        AABB box = player.getBoundingBox().inflate(SIGHT_RADIUS);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != player && e.isAlive());
        for (LivingEntity target : nearby) {
            // brief glow so the wielder "sees" them through the eye
            target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false, false));
        }
    }

    // Keep the wielder's active marionettes firmly fooled/controlled while the eye is held.
    private static void keepMarionettesBound(ServerPlayer player, ServerLevel level) {
        AABB box = player.getBoundingBox().inflate(64.0);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive);
        for (LivingEntity e : nearby) {
            if (MarionetteUtils.isMarionette(e)) {
                e.addEffect(new MobEffectInstance(de.jakob.lotm.effect.ModEffects.FOOLING, 60, 0, false, false, false));
            }
        }
    }

    // Sin-bearing: the eye drinks the wielder's spirituality and nips back at them.
    private static void applyCorruption(ServerPlayer player) {
        if (BeyonderData.isBeyonder(player)) {
            BeyonderData.reduceSpirituality(player, SPIRITUALITY_DRAIN);
        }
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0, false, true, true));
        player.sendSystemMessage(Component.literal("The All-Black Eye drinks a little of you in return.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

    // We piggyback the corruption timer on a dedicated read/write of game-time stored
    // in the same style as the rest of the mod. We keep it inside the item's own NBT
    // via a lightweight custom key on the stack's persistent tag.
    private static final String CORRUPTION_TAG = "AllBlackEyeCorruptionTick";

    private static long readCorruptionTick(ItemStack stack, long fallback) {
        var custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (custom == null) return fallback;
        var tag = custom.copyTag();
        return tag.contains(CORRUPTION_TAG) ? tag.getLong(CORRUPTION_TAG) : fallback;
    }

    private static void writeCorruptionTick(ItemStack stack, long value) {
        var custom = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY);
        var tag = custom.copyTag();
        tag.putLong(CORRUPTION_TAG, value);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable(this.getDescriptionId(stack));
    }

    /** Always shimmer so the eye reads as a mystical artifact. */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ensureAbilities(stack);

        // --- Flavor / explanation ---------------------------------------
        tooltip.add(Component.literal("Sealed Artifact - Fool / Hanged Man Pathway")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD));
        tooltip.add(Component.literal("\"A lidless eye set in blackened stone. Through it the")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.literal(" bearer sees every soul nearby and binds them as")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.literal(" marionettes - but the All-Black stares back, and feeds.\"")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.empty());

        // --- Abilities on the wheel ------------------------------------
        tooltip.add(Component.literal("Artifact Wheel:").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.literal("  - Puppeteering (Fool Seq 5)").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("  - Marionette Controlling (Fool Seq 4)").withStyle(ChatFormatting.GRAY));

        SealedArtifactData data = stack.get(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get());
        if (data != null && !data.abilities().isEmpty()) {
            int selected = stack.getOrDefault(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get(), 0);
            if (selected < data.abilities().size()) {
                tooltip.add(Component.literal("Selected ability: " + data.abilities().get(selected).getId())
                        .withStyle(ChatFormatting.GOLD));
            }
        }

        // --- How to use + cost -----------------------------------------
        tooltip.add(Component.empty());
        tooltip.add(Component.literal("Hold it to see nearby souls (they glow) and keep your marionettes bound")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Open the Artifact Wheel to select, right-click to cast")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Corruption: it drinks your spirituality and weakens you over time")
                .withStyle(ChatFormatting.DARK_RED));
    }
}
