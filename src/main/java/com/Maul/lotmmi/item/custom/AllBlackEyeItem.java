package com.Maul.lotmmi.item.custom;

import de.jakob.lotm.LOTMCraft;
import de.jakob.lotm.attachments.DoorAuthorityData;
import de.jakob.lotm.attachments.ModAttachments;
import de.jakob.lotm.beyonders.abilities.core.Ability;
import de.jakob.lotm.beyonders.artifacts.NegativeEffect;
import de.jakob.lotm.beyonders.artifacts.SealedArtifactData;
import de.jakob.lotm.entity.ModEntities;
import de.jakob.lotm.entity.custom.BeyonderNPCEntity;
import de.jakob.lotm.gamerule.ModGameRules;
import de.jakob.lotm.item.custom.MarionetteControllerItem;
import de.jakob.lotm.util.BeyonderData;
import de.jakob.lotm.util.helper.AbilityUtil;
import de.jakob.lotm.util.helper.AbilityWheelHelper;
import de.jakob.lotm.util.helper.ParticleUtil;
import de.jakob.lotm.util.helper.VectorUtil;
import de.jakob.lotm.util.helper.marionettes.MarionetteComponent;
import de.jakob.lotm.util.helper.marionettes.MarionetteUtils;
import de.jakob.lotm.util.scheduling.ServerScheduler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * All-Black Eye - a hand-built Sealed Artifact on the Fool / Hanged Man Pathway.
 *
 * Right-click ensnares a target (a custom capture loop owned entirely by this
 * class - it does NOT call LotMCraft's shared PuppeteeringAbility instance,
 * since that instance is also used by every real Fool-pathway player in the
 * world; rebalancing it here would rebalance it for them too). On a
 * successful capture the target becomes a marionette exactly like the base
 * mod's own puppeteering flow (MarionetteUtils.turnEntityIntoMarionette -
 * same AI attachment, same persistence), but:
 *   - it starts inert (no follow/attack AI) until actively possessed,
 *   - it does NOT hand over a MarionetteController item,
 *   - instead the player's own Ability Wheel gains a one-click "Control"
 *     entry ("marionette_controlling_ability:2:copied") that works even if
 *     the player isn't a Fool beyonder, because the base mod's wheel-cast
 *     packet strips the pathway/sequence gate for entries flagged ":copied".
 *
 * Everything after that point - becoming the marionette, the original body
 * left behind, the base mod's own "return to main body" keybind, and dying
 * while possessed bouncing you back with zero repercussion and the corpse
 * left permanently dead - is the base mod's existing ControllingUtil /
 * MarionetteControllingAbility.control() machinery. We don't reimplement any
 * of that; we just unlock it properly instead of handing out an item.
 *
 * Hanged Man extras (unchanged from before): remote sight, keeping any
 * marionette in range "fooled", and a slow spirituality/weakness corruption
 * cost while held. The tokenNegatives() list stays purely as an inert marker
 * so SealedArtifactEffectHandler + BeyonderEventHandler.onContainerOpen never
 * treat this stack as a "no negative effect" cheat item and delete it - see
 * the class-level note there for why that check exists.
 */
public class AllBlackEyeItem extends Item {

    private static final String MARIONETTE_ABILITY_ID = "marionette_controlling_ability";
    private static final String PUPPETEER_ABILITY_ID  = "puppeteering_ability";

    // Fool pathway, Sequence 5 (Marionettist) - just used for this item's own display/tooltip info.
    private static final String SCALE_PATHWAY  = "fool";
    private static final int    SCALE_SEQUENCE = 5;

    // Corruption cadence: once every 30s the eye drinks spirituality + nips the wielder.
    private static final long CORRUPTION_INTERVAL_TICKS = 20L * 30;
    private static final float SPIRITUALITY_DRAIN = 10f;
    private static final double SIGHT_RADIUS = 24.0;

    // ---- Ensnare (capture) tuning ----
    private static final int ENSNARE_RANGE = 20;

    // Sequence 5-9 (and non-beyonder mobs): 1-2 minutes.
    private static final int LOW_MIN_TICKS = 20 * 60;
    private static final int LOW_MAX_TICKS = 20 * 120;
    // Sequence 1-4: 2-10 minutes.
    private static final int HIGH_MIN_TICKS = 20 * 120;
    private static final int HIGH_MAX_TICKS = 20 * 600;
    // Sequence 0: immune, handled as an early bail-out.

    // Aggro-risk pulse fires once, at ~90% completion.
    private static final double AGGRO_PROGRESS_THRESHOLD = 0.9;
    private static final double AGGRO_ALERT_RADIUS = 16.0;

    private static final Map<UUID, LivingEntity> activeEnsnares = new HashMap<>();
    private static final java.util.Random RANDOM = new java.util.Random();

    public AllBlackEyeItem(Properties properties) {
        super(properties);
    }

    /**
     * Inert token so SEALED_ARTIFACT_DATA.negativeEffect() is never empty. See class javadoc.
     */
    private static List<NegativeEffect> tokenNegatives() {
        List<NegativeEffect> list = new ArrayList<>();
        list.add(new NegativeEffect(NegativeEffect.NegativeEffectType.CURSED, 0, null, 0));
        return list;
    }

    /**
     * Bake the (single) Puppeteering ability reference into the shared SEALED_ARTIFACT_DATA
     * pool if it isn't there yet - kept purely for tooltip naming + so this component keeps
     * matching the shape the rest of the mod expects. The actual cast is handled entirely by
     * ensnare() below, never by calling this ability's useAbility().
     */
    private static void ensureAbilities(ItemStack stack) {
        SealedArtifactData existing = stack.get(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get());
        if (existing != null && !existing.abilities().isEmpty()) return;

        List<Ability> abilities = new ArrayList<>();
        Ability puppeteer = LOTMCraft.abilityHandler.getById(PUPPETEER_ABILITY_ID);
        if (puppeteer != null) abilities.add(puppeteer);
        if (abilities.isEmpty()) return;

        SealedArtifactData data = new SealedArtifactData(SCALE_PATHWAY, SCALE_SEQUENCE, abilities, tokenNegatives());
        stack.set(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_DATA.get(), data);
        if (!stack.has(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get())) {
            stack.set(de.jakob.lotm.data.ModDataComponents.SEALED_ARTIFACT_SELECTED.get(), 0);
        }
    }

    // ---------------------------------------------------------------
    // Right-click to ensnare a target.
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

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.fail(stack);
        }

        ensnare((ServerLevel) level, serverPlayer);

        return InteractionResultHolder.success(stack);
    }

    // ---------------------------------------------------------------
    // Custom ensnare/capture loop - owned by this item, not the shared
    // base-mod PuppeteeringAbility instance.
    // ---------------------------------------------------------------

    private void ensnare(ServerLevel level, ServerPlayer player) {
        if (activeEnsnares.containsKey(player.getUUID())) {
            // Right-clicking again while ensnaring cancels the attempt, mirroring the base mod's own toggle feel.
            activeEnsnares.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("The strings go slack.").withStyle(ChatFormatting.GRAY));
            return;
        }

        LivingEntity target = AbilityUtil.getTargetEntity(player, ENSNARE_RANGE, 3);
        if (target == null || target == player || target instanceof Phantom) {
            player.sendSystemMessage(Component.literal("There is no soul there to bind.").withStyle(ChatFormatting.RED));
            return;
        }

        boolean isBeyonder = BeyonderData.isBeyonder(target);
        int targetSeq = isBeyonder ? BeyonderData.getSequence(target) : 9;

        if (isBeyonder && targetSeq <= 0) {
            player.sendSystemMessage(Component.literal("Its will is absolute - the strings cannot reach it.")
                    .withStyle(ChatFormatting.DARK_RED));
            return;
        }

        int minTicks, maxTicks;
        if (targetSeq >= 5) {
            minTicks = LOW_MIN_TICKS;
            maxTicks = LOW_MAX_TICKS;
        } else {
            minTicks = HIGH_MIN_TICKS;
            maxTicks = HIGH_MAX_TICKS;
        }
        int totalTicks = minTicks + RANDOM.nextInt(maxTicks - minTicks + 1);
        int aggroAtTick = (int) (totalTicks * AGGRO_PROGRESS_THRESHOLD);

        activeEnsnares.put(player.getUUID(), target);
        // Note: deliberately NOT calling target.setTarget(player) here. The whole point of the
        // rework is that nothing threatens you until the near-finish risk pulse - the target
        // should not be able to fight back or aggro anything from the very first tick.

        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicBoolean aggroFired = new AtomicBoolean(false);
        AtomicInteger elapsed = new AtomicInteger(0);
        double startHealth = target.getHealth();
        double casterStartHealth = player.getHealth();

        // Bezier "strings" particle path from caster to target - same technique as the base
        // mod's own Puppeteering visual, computed once so the three strands stay stable across
        // the whole channel instead of jittering to a new random path every tick.
        Vec3 startTemp = player.getEyePosition().add(player.getLookAngle().normalize());
        Vec3 endTemp = target.getEyePosition();
        Vec3 perp1 = VectorUtil.getRandomPerpendicular(endTemp.subtract(startTemp));
        Vec3 perp2 = VectorUtil.getRandomPerpendicular(endTemp.subtract(startTemp));
        Vec3 perp3 = VectorUtil.getRandomPerpendicular(endTemp.subtract(startTemp));
        DustParticleOptions stringParticle = new DustParticleOptions(new Vector3f(.65f, .35f, .95f), 1.35f);

        player.sendSystemMessage(Component.literal("The eye fixes on its prey. Do not move, and do not be found.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));

        ServerScheduler.scheduleForDuration(0, 2, totalTicks, () -> {
            if (stopped.get()) return;

            elapsed.addAndGet(2);

            if (!target.isAlive() || target.isRemoved() || target.level() != level) {
                stopped.set(true);
                activeEnsnares.remove(player.getUUID());
                return;
            }
            if (target.distanceTo(player) > ENSNARE_RANGE * 1.5) {
                stopped.set(true);
                activeEnsnares.remove(player.getUUID());
                player.sendSystemMessage(Component.literal("It slips out of range - the binding unravels.").withStyle(ChatFormatting.RED));
                return;
            }
            if (target.getHealth() < startHealth) {
                stopped.set(true);
                activeEnsnares.remove(player.getUUID());
                player.sendSystemMessage(Component.literal("Something hurt it - the binding shatters.").withStyle(ChatFormatting.RED));
                return;
            }
            if (player.getHealth() < casterStartHealth * 0.5) {
                stopped.set(true);
                activeEnsnares.remove(player.getUUID());
                player.sendSystemMessage(Component.literal("You're too hurt to hold the strings steady.").withStyle(ChatFormatting.RED));
                return;
            }
            if (!activeEnsnares.containsKey(player.getUUID())) {
                stopped.set(true);
                return;
            }

            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 4, false, false, false));
            target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 5, false, false, false));

            // Three wavering "string" strands of particles from caster to target.
            Vec3 end = target.getEyePosition();
            for (int i = 0; i < 3; i++) {
                double right = i == 0 ? -2 : (i == 1 ? 1.4 : 2.2);
                double up = i == 2 ? -.2 : (i == 1 ? 0 : 1.2);
                Vec3 perp = i == 0 ? perp1 : (i == 1 ? perp2 : perp3);
                Vec3 startLoc = VectorUtil.getRelativePosition(
                        player.getEyePosition().add(player.getLookAngle().normalize()),
                        player.getLookAngle().normalize(), 0, right, up);

                float distance = (float) end.distanceTo(startLoc);
                int maxPoints = Math.max(2, Math.min(10, (int) Math.ceil(distance * 1.5)));
                List<Vec3> points = VectorUtil.createBezierCurve(startLoc, end, perp, .025f,
                        RANDOM.nextInt(1, maxPoints + 1));

                for (Vec3 point : points) {
                    ParticleUtil.spawnParticles(level, stringParticle, point, 1, 0, 0, 0, 0);
                }
            }

            // Progress feedback, once a second - "how do I know when it's done".
            if (elapsed.get() % 20 == 0) {
                sendEnsnareProgress(player, elapsed.get(), totalTicks);
            }

            if (!aggroFired.get() && elapsed.get() >= aggroAtTick) {
                aggroFired.set(true);
                triggerAggroPulse(level, player);
            }
        }, () -> {
            activeEnsnares.remove(player.getUUID());
            if (stopped.get()) return;
            completeEnsnare(level, player, target);
        }, level);
    }

    private static void sendEnsnareProgress(ServerPlayer player, int elapsedTicks, int totalTicks) {
        int percent = Math.min(100, (int) (100.0 * elapsedTicks / totalTicks));
        int secondsLeft = Math.max(0, (totalTicks - elapsedTicks) / 20);
        int mm = secondsLeft / 60;
        int ss = secondsLeft % 60;
        Component bar = Component.literal(String.format("Ensnaring... %d%% (%d:%02d left)", percent, mm, ss))
                .withStyle(ChatFormatting.LIGHT_PURPLE);
        player.connection.send(new ClientboundSetActionBarTextPacket(bar));
    }

    /**
     * The risk that replaces "hide and wait with zero consequence": once, at ~90% completion,
     * the eye's grip slips a little - it nips the caster and can alert nearby hostiles to their position.
     */
    private static void triggerAggroPulse(ServerLevel level, ServerPlayer player) {
        player.hurt(player.damageSources().magic(), 1.0f);

        List<LivingEntity> nearby = AbilityUtil.getNearbyEntities(player, level, player.position(), AGGRO_ALERT_RADIUS);
        for (LivingEntity e : nearby) {
            if (e instanceof Monster monster && monster.getTarget() == null) {
                monster.setTarget(player);
            }
        }

        level.playSound(null, player.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 0.6f, 1.4f);
        player.sendSystemMessage(Component.literal("The strings pull taut - something nearby feels it.")
                .withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
    }

    private static void completeEnsnare(ServerLevel level, ServerPlayer player, LivingEntity capturedTarget) {
        LivingEntity marionette = capturedTarget;

        // Real players get killed-and-cloned into a controllable NPC body first, mirroring the
        // base mod's own PuppeteeringAbility.turnIntoMarionette() handling of player targets.
        if (marionette instanceof Player) {
            Vec3 pos = marionette.position();
            if (BeyonderData.isBeyonder(marionette)) {
                int seq = BeyonderData.getSequence(marionette);
                String pathway = BeyonderData.getPathway(marionette);
                marionette.hurt(marionette.damageSources().generic(), Float.MAX_VALUE);
                marionette = new BeyonderNPCEntity(ModEntities.BEYONDER_NPC.get(), marionette.level(), false, pathway, seq);
            } else {
                marionette.hurt(marionette.damageSources().generic(), Float.MAX_VALUE);
                marionette = new BeyonderNPCEntity(ModEntities.BEYONDER_NPC.get(), marionette.level(), false, "none", 10);
            }
            marionette.setPos(pos);
            marionette.level().addFreshEntity(marionette);
        }

        marionette.setHealth(marionette.getMaxHealth());
        if (marionette instanceof Mob mob) {
            mob.setTarget(null);
            mob.getNavigation().stop();
        }

        if (!MarionetteUtils.turnEntityIntoMarionette(marionette, player)) {
            player.sendSystemMessage(Component.literal("The strings slip loose - the binding failed.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // This artifact never hands out a physical controller item - strip whatever
        // turnEntityIntoMarionette() just granted for this specific marionette.
        removeGrantedControllerItem(player, marionette);

        // Keep it a true puppet: no independent movement/following/attacking until it is
        // actively possessed via the ability wheel entry granted below.
        MarionetteComponent component = marionette.getData(ModAttachments.MARIONETTE_COMPONENT.get());
        component.setFollowMode(false);
        component.setShouldAttack(false);

        // One-click possession from the player's own Ability Wheel. ":2" pins the shared
        // marionette_controlling_ability to its "control" sub-ability; ":copied" flags this
        // wheel entry as a granted/copied ability, which bypasses the normal Fool pathway
        // requirement check on cast (see UseSelectedAbilityPacket.handle in the base mod).
        AbilityWheelHelper.addAbility(player, MARIONETTE_ABILITY_ID + ":2:copied");

        player.sendSystemMessage(Component.literal("The strings catch. It is yours now - open your Ability Wheel to take it.")
                .withStyle(ChatFormatting.DARK_PURPLE));
    }

    private static void removeGrantedControllerItem(ServerPlayer player, LivingEntity marionette) {
        String targetUUID = marionette.getStringUUID();

        player.getInventory().items.removeIf(stack -> isControllerFor(stack, targetUUID));

        // Cover the edge case where the player's inventory was full and the item was dropped instead.
        for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(3))) {
            if (e instanceof ItemEntity itemEntity && isControllerFor(itemEntity.getItem(), targetUUID)) {
                itemEntity.discard();
            }
        }
    }

    private static boolean isControllerFor(ItemStack stack, String targetUUID) {
        if (!(stack.getItem() instanceof MarionetteControllerItem)) return false;
        var custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (custom == null) return false;
        return targetUUID.equals(custom.copyTag().getString("MarionetteUUID"));
    }

    // ---------------------------------------------------------------
    // Hanged Man corruption + remote sight, ticked while held. Unchanged.
    // ---------------------------------------------------------------

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) return;

        ensureAbilities(stack);

        long now = level.getGameTime();

        if (isSelected && now % 20 == 0) {
            grantRemoteSight(player, (ServerLevel) level);
            keepMarionettesBound(player, (ServerLevel) level);
        }

        long lastCorruption = readCorruptionTick(stack, now);
        if (now - lastCorruption >= CORRUPTION_INTERVAL_TICKS) {
            applyCorruption(player);
            writeCorruptionTick(stack, now);
        }
    }

    private static void grantRemoteSight(ServerPlayer player, ServerLevel level) {
        AABB box = player.getBoundingBox().inflate(SIGHT_RADIUS);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != player && e.isAlive());
        for (LivingEntity target : nearby) {
            target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false, false));
        }
    }

    private static void keepMarionettesBound(ServerPlayer player, ServerLevel level) {
        AABB box = player.getBoundingBox().inflate(64.0);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive);
        for (LivingEntity e : nearby) {
            if (MarionetteUtils.isMarionette(e)) {
                e.addEffect(new MobEffectInstance(de.jakob.lotm.effect.ModEffects.FOOLING, 60, 0, false, false, false));
            }
        }
    }

    private static void applyCorruption(ServerPlayer player) {
        if (BeyonderData.isBeyonder(player)) {
            BeyonderData.reduceSpirituality(player, SPIRITUALITY_DRAIN);
        }
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0, false, true, true));
        player.sendSystemMessage(Component.literal("The All-Black Eye drinks a little of you in return.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

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

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ensureAbilities(stack);

        tooltip.add(Component.literal("Sealed Artifact - Fool / Hanged Man Pathway")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD));
        tooltip.add(Component.literal("\"A lidless eye set in blackened stone. Through it the")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.literal(" bearer sees every soul nearby and binds one as a")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.literal(" marionette - but the All-Black stares back, and feeds.\"")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.empty());

        tooltip.add(Component.literal("Right-click: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("ensnare a target and bind it as a marionette").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("  Weaker souls (Seq 5-9): ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("1-2 minutes to bind").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("  Stronger souls (Seq 1-4): ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("2-10 minutes to bind").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("  Sequence 0: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("cannot be bound").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("  Right-click again to cancel an attempt in progress").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.empty());

        tooltip.add(Component.literal("On success: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("adds \"Control\" to your Ability Wheel - no item needed").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("  The marionette stands inert until you take control of it").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("  While bound as it, use the base mod's own \"return to main body\" key to come back").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("  If it dies while you're in it, you're returned safely - it will not come back").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.empty());

        tooltip.add(Component.literal("Risk: ").withStyle(ChatFormatting.DARK_RED)
                .append(Component.literal("near the end of a capture, the strings can slip and draw nearby attention").withStyle(ChatFormatting.RED)));
        tooltip.add(Component.literal("Corruption: it drinks your spirituality and weakens you over time")
                .withStyle(ChatFormatting.DARK_RED));
    }
}
