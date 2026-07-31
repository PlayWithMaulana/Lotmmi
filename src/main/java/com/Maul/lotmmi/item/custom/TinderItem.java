package com.Maul.lotmmi.item.custom;

import com.Maul.lotmmi.data.ModDataComponents;
import de.jakob.lotm.LOTMCraft;
import de.jakob.lotm.attachments.CopiedAbilityComponent;
import de.jakob.lotm.attachments.DisabledAbilitiesComponent;
import de.jakob.lotm.attachments.DoorAuthorityData;
import de.jakob.lotm.attachments.ModAttachments;
import de.jakob.lotm.beyonders.abilities.core.Ability;
import de.jakob.lotm.beyonders.abilities.error.handler.TheftHandler;
import de.jakob.lotm.gamerule.ModGameRules;
import de.jakob.lotm.util.BeyonderData;
import de.jakob.lotm.util.helper.AbilityUtil;
import de.jakob.lotm.util.helper.AbilityWheelHelper;
import de.jakob.lotm.util.helper.CopiedAbilityHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Tinder - a hand-built Grade 2 Sealed Artifact: "a glove that can change its color".
 * Canon: https://lordofthemysteries.fandom.com/wiki/Tinder
 *
 * Unlike {@link CreepingHungerItem} and {@link AllBlackEyeItem}, Tinder does NOT use
 * LotMCraft's SEALED_ARTIFACT_DATA / Artifact Wheel at all - there is nothing to select,
 * so there is nothing for the wheel to show. Its two powers are both driven straight off
 * the item's own right-click:
 *
 *   1. "Enhance the user's charm, making his words very convincing." - PASSIVE.
 *      While held/selected, Tinder periodically looks for a nearby valid target on its own
 *      and quietly casts the base mod's own "charm_ability" (Demoness Sequence 6) on it, no
 *      button press required. It only ever fires when a real target is in range, so it never
 *      spams messages when there's nothing nearby.
 *
 *   2. "Can steal a target's ability and allow the wearer to use it for 10 minutes." - the
 *      one and only ACTIVE use of the item:
 *        - Sneak + right-click a target: attempt to steal one of their abilities.
 *        - Plain right-click: cast whichever stolen ability is still on loan (most recent
 *          if more than one). This IS the "active ability" - Tinder itself has no other
 *          active button.
 *      Stealing is hand-built rather than delegated to the base mod's AbilityTheftAbility
 *      (Error Sequence 6 - the "Prometheus" the wiki compares Tinder to), because that
 *      ability's own grant is USE-limited (1 use) where canon Tinder is TIME-limited (10
 *      minutes, however many casts fit in that window and the stolen ability's own
 *      cooldown). We reuse the real ability instance purely as a scaling/fail-chance
 *      reference so the exact same theft math (TheftHandler.doesTheftFail) applies.
 *
 *   3. "The stolen would need at least 12 hours to recover it." - the target's stolen
 *      ability is disabled on them for a flat 12 in-game hours, rather than the base
 *      ability's per-sequence 35s-900s scale, matching canon's "at least".
 *
 *   4. "Before he can steal... need to let the glove take one of his own power for 12
 *      hours, in exchange he would be allowed to use steal as many times he wants for 12
 *      hours." - Tinder must be "charged" before it can steal at all. Charging picks one
 *      of the WEARER's own currently-known abilities at random and disables it on them for
 *      12 in-game hours, then opens a 12-hour window in which stealing has no additional
 *      gate beyond the steal attempt's own math and the usual per-target fail chance. An
 *      uncharged Tinder auto-charges itself the moment a steal is attempted.
 *
 *   5. "The wearer is more likely to lose his carried items." - while held/selected,
 *      Tinder periodically rolls a chance to drop a random item from the wearer's
 *      inventory - a higher chance while charged, since it is actively spending the
 *      wearer's luck to keep the theft window open.
 *
 * Because Tinder never touches SEALED_ARTIFACT_DATA, it is never a candidate for the base
 * mod's "delete artifacts with no negative effect on container-open" cleanup pass in the
 * first place - the bug that Creeping Hunger and All-Black Eye had to work around with a
 * token negative-effect entry simply doesn't apply here.
 */
public class TinderItem extends Item {

    private static final String CHARM_ABILITY_ID = "charm_ability";
    private static final String CHARM_PATHWAY = "demoness";
    private static final int CHARM_SEQUENCE = 6;
    private static final int CHARM_RANGE = 18; // matches CharmAbility's own target search radius
    private static final long CHARM_PASSIVE_INTERVAL_TICKS = 20L * 4; // try roughly every 4s

    // The base mod's own Error Sequence 6 ability ("Prometheus") - canon's own point of
    // comparison for Tinder's power. Used only as a scaling/fail-chance reference for our
    // custom steal method below, never actually cast via ability.useAbility().
    private static final String THEFT_REFERENCE_ABILITY_ID = "ability_theft_ability";
    private static final int STEAL_SCALE_SEQUENCE = 6;

    private static final long TWELVE_HOURS_TICKS = 20L * 60 * 60 * 12; // 864000 ticks
    private static final long STOLEN_ABILITY_LOAN_TICKS = 20L * 60 * 10; // 10 in-game minutes
    private static final long ITEM_LOSS_CHECK_INTERVAL_TICKS = 20L * 20; // every 20s while held

    private static final Random RANDOM = new Random();

    public TinderItem(Properties properties) {
        super(properties);
    }

    // ---------------------------------------------------------------
    // Right-click: cast the currently loaned stolen ability.
    // Sneak + right-click: attempt a steal.
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

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.fail(stack);
        }

        if (player.isShiftKeyDown()) {
            attemptSteal(serverPlayer, stack, (ServerLevel) level);
        } else {
            castStolenAbility(serverPlayer, stack, (ServerLevel) level);
        }

        return InteractionResultHolder.success(stack);
    }

    // ---------------------------------------------------------------
    // Casting whatever is currently on loan - Tinder's only active button.
    // ---------------------------------------------------------------

    private static void castStolenAbility(ServerPlayer player, ItemStack stack, ServerLevel level) {
        long now = level.getGameTime();
        LoanEntry loan = mostRecentActiveLoan(stack, now);

        if (loan == null) {
            player.sendSystemMessage(Component.literal(
                    "Tinder holds no borrowed power right now - sneak + right-click a target to steal one first."
            ).withStyle(ChatFormatting.RED));
            return;
        }

        Ability ability = LOTMCraft.abilityHandler.getById(loan.abilityId);
        if (ability == null) {
            return;
        }

        // Scale as the ability's original owner, not the wearer - a stolen god's ability
        // should hit like that god's ability, not like a flat Sequence 6 default.
        AbilityUtil.setArtifactScaling(player, loan.pathway, loan.sequence);
        ability.useAbility(level, player, true, false, true, false);
    }

    private record LoanEntry(String abilityId, long expiry, String pathway, int sequence) {
        String serialize() {
            return abilityId + ":" + expiry + ":" + pathway + ":" + sequence;
        }

        static LoanEntry deserialize(String raw) {
            String[] parts = raw.split(":", 4);
            if (parts.length != 4) return null;
            try {
                return new LoanEntry(parts[0], Long.parseLong(parts[1]), parts[2], Integer.parseInt(parts[3]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static List<LoanEntry> getLoans(ItemStack stack) {
        String raw = stack.getOrDefault(ModDataComponents.TINDER_STOLEN.get(), "");
        List<LoanEntry> loans = new ArrayList<>();
        if (raw.isEmpty()) return loans;
        for (String entry : raw.split(";")) {
            LoanEntry loan = LoanEntry.deserialize(entry);
            if (loan != null) loans.add(loan);
        }
        return loans;
    }

    private static void setLoans(ItemStack stack, List<LoanEntry> loans) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < loans.size(); i++) {
            if (i > 0) sb.append(";");
            sb.append(loans.get(i).serialize());
        }
        stack.set(ModDataComponents.TINDER_STOLEN.get(), sb.toString());
    }

    private static LoanEntry mostRecentActiveLoan(ItemStack stack, long now) {
        List<LoanEntry> loans = getLoans(stack);
        for (int i = loans.size() - 1; i >= 0; i--) {
            if (loans.get(i).expiry() > now) return loans.get(i);
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Charm - fully passive. Ticked below, never cast manually.
    // ---------------------------------------------------------------

    private static void tryPassiveCharm(ServerPlayer player, ServerLevel level) {
        Ability charm = LOTMCraft.abilityHandler.getById(CHARM_ABILITY_ID);
        if (charm == null) return;

        // Pre-check for a target ourselves so we never trigger CharmAbility's own
        // "no entity to charm found" action-bar message every few seconds.
        LivingEntity target = AbilityUtil.getTargetEntity(player, CHARM_RANGE, 1.5f);
        if (target == null) return;

        AbilityUtil.setArtifactScaling(player, CHARM_PATHWAY, CHARM_SEQUENCE);
        charm.useAbility(level, player, true, false, true, false);
    }

    // ---------------------------------------------------------------
    // Charging - "let the glove take one of his own power for 12 hours"
    // ---------------------------------------------------------------

    private static boolean isCharged(ItemStack stack, long now) {
        return now < stack.getOrDefault(ModDataComponents.TINDER_CHARGED_UNTIL.get(), 0L);
    }

    private static boolean chargeTinder(ServerPlayer player, ItemStack stack, long now) {
        if (!BeyonderData.isBeyonder(player)) {
            player.sendSystemMessage(Component.literal(
                    "Tinder finds no power of your own to draw upon - only a Beyonder can charge it."
            ).withStyle(ChatFormatting.RED));
            return false;
        }

        List<Ability> ownAbilities = LOTMCraft.abilityHandler.getAbilities().stream()
                .filter(a -> a.hasAbility(player))
                .filter(a -> !a.getId().equals(CHARM_ABILITY_ID) && !a.getId().equals(THEFT_REFERENCE_ABILITY_ID))
                .collect(Collectors.toList());

        if (ownAbilities.isEmpty()) {
            player.sendSystemMessage(Component.literal("You have no power of your own left to feed it.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        Ability sacrificed = ownAbilities.get(RANDOM.nextInt(ownAbilities.size()));
        DisabledAbilitiesComponent ownDisabled = player.getData(ModAttachments.DISABLED_ABILITIES_COMPONENT);
        ownDisabled.disableSpecificAbilityForTime(sacrificed.getId(), "tinder_sacrifice_" + player.getUUID(), (int) TWELVE_HOURS_TICKS);

        stack.set(ModDataComponents.TINDER_CHARGED_UNTIL.get(), now + TWELVE_HOURS_TICKS);

        player.sendSystemMessage(Component.literal(
                "Tinder drinks " + sacrificed.getNameFormatted().getString()
                        + " from you for 12 hours - in exchange, it will let you steal freely until then."
        ).withStyle(ChatFormatting.DARK_PURPLE));
        return true;
    }

    // ---------------------------------------------------------------
    // Stealing - hand-built rather than delegated to the base mod's
    // AbilityTheftAbility, see class javadoc for why.
    // ---------------------------------------------------------------

    private static void attemptSteal(ServerPlayer player, ItemStack stack, ServerLevel level) {
        long now = level.getGameTime();

        if (!isCharged(stack, now)) {
            if (!chargeTinder(player, stack, now)) {
                return;
            }
        }

        LivingEntity target = AbilityUtil.getTargetEntity(player, 15, 2f);
        if (target == null) {
            player.sendSystemMessage(Component.literal("Tinder finds no soul close enough to pluck a secret from.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        if (!BeyonderData.isBeyonder(target)) {
            player.sendSystemMessage(Component.literal("Tinder senses nothing worth stealing there.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        String targetPathway = BeyonderData.getPathway(target);
        int targetSequence = BeyonderData.getSequence(target);

        DisabledAbilitiesComponent targetDisabled = target.getData(ModAttachments.DISABLED_ABILITIES_COMPONENT);
        HashSet<Ability> pool = LOTMCraft.abilityHandler.getByPathwayAndSequence(targetPathway, targetSequence);
        List<Ability> stealable = pool.stream()
                .filter(a -> !a.cannotBeStolen && !targetDisabled.isSpecificAbilityDisabled(a.getId()))
                .collect(Collectors.toList());

        if (stealable.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    target.getName().getString() + " has nothing left for Tinder to take."
            ).withStyle(ChatFormatting.RED));
            return;
        }

        // Borrow the real Error Seq 6 ability instance purely so we can reuse the base
        // mod's own theft-fail-chance math (TheftHandler.doesTheftFail) at Tinder's fixed
        // power level, without ever calling ability.useAbility() on it (see javadoc).
        Ability theftReference = LOTMCraft.abilityHandler.getById(THEFT_REFERENCE_ABILITY_ID);
        boolean tooStrong;
        boolean failed;
        if (theftReference != null) {
            theftReference.artifactScalingMap.put(player.getUUID(), STEAL_SCALE_SEQUENCE);
            try {
                int effectiveSeq = AbilityUtil.getSeqWithArt(player, theftReference);
                tooStrong = AbilityUtil.isTargetSignificantlyStronger(effectiveSeq, targetSequence);
                failed = !tooStrong && TheftHandler.doesTheftFail(player, target, RANDOM, theftReference);
            } finally {
                theftReference.clearArtifactScaling(player);
            }
        } else {
            tooStrong = false;
            failed = false;
        }

        if (tooStrong) {
            player.sendSystemMessage(Component.literal(
                    target.getName().getString() + "'s power is too far beyond Tinder's reach."
            ).withStyle(ChatFormatting.RED));
            return;
        }

        if (failed) {
            player.sendSystemMessage(Component.literal("Tinder's grip slips - the theft fails.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        Ability stolen = stealable.get(RANDOM.nextInt(stealable.size()));

        // Canon: "the stolen would need at least 12 hours to recover it" - a flat 12h
        // floor on the target regardless of sequence, rather than the base ability's
        // 35s-900s per-sequence scale.
        targetDisabled.disableSpecificAbilityForTime(stolen.getId(), "tinder_theft_" + player.getUUID(), (int) TWELVE_HOURS_TICKS);

        // Grant with unlimited uses (-1) - CopiedAbilityHelper/decrementUses treats -1 as
        // "never consumed", so nothing strips this early. We handle its removal ourselves,
        // on our own 10-minute timer, in inventoryTick below. Right-clicking Tinder (not
        // sneaking) is the only way to actually cast it.
        CopiedAbilityHelper.addAbility(player, new CopiedAbilityComponent.CopiedAbilityData(
                stolen.getId(), "tinder_stolen", -1, target.getUUID().toString()
        ));

        List<LoanEntry> loans = getLoans(stack);
        loans.add(new LoanEntry(stolen.getId(), now + STOLEN_ABILITY_LOAN_TICKS, targetPathway, targetSequence));
        setLoans(stack, loans);

        player.sendSystemMessage(Component.literal(
                "Tinder plucks " + stolen.getNameFormatted().getString() + " from " + target.getName().getString()
                        + " - right-click Tinder to use it for the next 10 minutes."
        ).withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    // ---------------------------------------------------------------
    // Ticking: passive Charm, expire loaned stolen abilities, roll the item-loss downside.
    // ---------------------------------------------------------------

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide || !(entity instanceof ServerPlayer player)) return;

        long now = level.getGameTime();
        expireLoans(stack, player, now);

        if (isSelected) {
            if (now % CHARM_PASSIVE_INTERVAL_TICKS == 0) {
                tryPassiveCharm(player, (ServerLevel) level);
            }

            long lastLoss = stack.getOrDefault(ModDataComponents.TINDER_LAST_LOSS_TICK.get(), now);
            if (now - lastLoss >= ITEM_LOSS_CHECK_INTERVAL_TICKS) {
                stack.set(ModDataComponents.TINDER_LAST_LOSS_TICK.get(), now);
                boolean charged = isCharged(stack, now);
                float chance = charged ? 0.10f : 0.04f;
                if (RANDOM.nextFloat() < chance) {
                    dropRandomItem(player);
                }
            }
        }
    }

    private static void expireLoans(ItemStack stack, ServerPlayer player, long now) {
        List<LoanEntry> loans = getLoans(stack);
        if (loans.isEmpty()) return;

        List<LoanEntry> remaining = new ArrayList<>();
        boolean changed = false;
        CopiedAbilityComponent copied = player.getData(ModAttachments.COPIED_ABILITY_COMPONENT);

        for (LoanEntry loan : loans) {
            if (now >= loan.expiry()) {
                changed = true;
                CopiedAbilityComponent.CopiedAbilityData match = copied.getAbilities().stream()
                        .filter(d -> d.abilityId().equals(loan.abilityId()) && "tinder_stolen".equals(d.copyType()))
                        .findFirst().orElse(null);
                if (match != null) {
                    copied.getAbilities().remove(match);
                }
                player.sendSystemMessage(Component.literal(
                        "The power Tinder lent you fades - its loan has ended."
                ).withStyle(ChatFormatting.DARK_GRAY));
            } else {
                remaining.add(loan);
            }
        }

        if (changed) {
            setLoans(stack, remaining);
            AbilityWheelHelper.removeUnusableAbilities(player);
            CopiedAbilityHelper.syncToClient(player);
        }
    }

    // "The wearer is more likely to lose his carried items."
    private static void dropRandomItem(ServerPlayer player) {
        List<Integer> nonEmptySlots = new ArrayList<>();
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack candidate = player.getInventory().items.get(i);
            if (!candidate.isEmpty() && !(candidate.getItem() instanceof TinderItem)) {
                nonEmptySlots.add(i);
            }
        }
        if (nonEmptySlots.isEmpty()) return;

        int slot = nonEmptySlots.get(RANDOM.nextInt(nonEmptySlots.size()));
        ItemStack held = player.getInventory().items.get(slot);
        ItemStack dropped = held.split(1);
        player.drop(dropped, false);

        player.sendSystemMessage(Component.literal(
                "Tinder's color flickers - your grip slips, and " + dropped.getHoverName().getString() + " tumbles free."
        ).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable(this.getDescriptionId(stack));
    }

    /** Always shimmer so the glove reads as a mystical artifact. */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Sealed Artifact - Grade 2, Error Pathway (Prometheus-adjacent)")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
        tooltip.add(Component.literal("\"A glove that changes color with its mood. Its words are")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.literal(" honey, and what it takes from others, it lends to you -")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.literal(" for a while. It always takes a little more back.\"")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.empty());

        Long chargedUntil = stack.get(ModDataComponents.TINDER_CHARGED_UNTIL.get());
        if (chargedUntil != null) {
            tooltip.add(Component.literal("Charged (steals freely until game-time " + chargedUntil + ")")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            tooltip.add(Component.literal("Uncharged - the next steal attempt will charge it")
                    .withStyle(ChatFormatting.GRAY));
        }

        tooltip.add(Component.empty());
        tooltip.add(Component.literal("Passive: quietly charms nearby souls while worn")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Sneak + right-click a target: steal one of their abilities")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Right-click: use the ability currently on loan")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Loans last 10 minutes; the target needs at least 12")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("  hours to recover the ability you took")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Stealing costs one of your own abilities for 12 hours to")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("  charge it, then it steals freely until that runs out")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("You are more likely to drop items while it's charged")
                .withStyle(ChatFormatting.DARK_RED));
    }
}
