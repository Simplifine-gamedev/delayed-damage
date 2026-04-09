package com.delayeddamage;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DelayedDamageMod implements ModInitializer {
    public static final String MOD_ID = "delayed-damage";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Track pending damage per player: UUID -> PendingDamage
    private static final Map<UUID, PendingDamage> pendingDamageMap = new ConcurrentHashMap<>();

    // Damage drain duration in ticks (3 seconds = 60 ticks)
    private static final int DRAIN_DURATION_TICKS = 60;

    // How often to apply damage ticks (every 3 ticks = 10 damage applications over 3 seconds)
    private static final int TICK_INTERVAL = 6;

    @Override
    public void onInitialize() {
        LOGGER.info("Delayed Damage mod initialized! All damage will now be applied over time.");

        // Listen for damage events and intercept them
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::onEntityDamage);

        // Tick handler to apply pending damage over time
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerWorld world : server.getWorlds()) {
                for (ServerPlayerEntity player : world.getPlayers()) {
                    tickPendingDamage(player);
                }
            }
        });

        // Clean up when player disconnects
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            pendingDamageMap.remove(handler.player.getUuid());
        });
    }

    private boolean onEntityDamage(LivingEntity entity, DamageSource source, float amount) {
        // Only delay damage for players
        if (!(entity instanceof ServerPlayerEntity player)) {
            return true; // Allow normal damage for non-players
        }

        LOGGER.info("Intercepted damage: {} took {} damage from {}",
            player.getName().getString(), amount, source.getName());

        // Don't delay certain damage types that should be instant
        String sourceName = source.getName();
        if (sourceName.equals("outOfWorld") ||
            sourceName.equals("starve") ||
            sourceName.equals("genericKill") ||
            sourceName.equals("kill") ||
            amount <= 0) {
            LOGGER.info("Allowing instant damage for type: {}", sourceName);
            return true; // Allow instant damage
        }

        // Add to pending damage instead of applying instantly
        addPendingDamage(player, amount, source);

        // Cancel the original damage
        return false;
    }

    private void addPendingDamage(ServerPlayerEntity player, float amount, DamageSource source) {
        UUID playerId = player.getUuid();

        PendingDamage pending = pendingDamageMap.computeIfAbsent(playerId,
            k -> new PendingDamage());

        // Stack damage
        pending.addDamage(amount, DRAIN_DURATION_TICKS);

        // Notify player of incoming damage
        float totalPending = pending.getTotalPending();
        player.sendMessage(
            Text.literal("Incoming damage: ")
                .formatted(Formatting.RED)
                .append(Text.literal(String.format("%.1f", totalPending))
                    .formatted(Formatting.WHITE, Formatting.BOLD))
                .append(Text.literal(" hearts")
                    .formatted(Formatting.RED)),
            true // action bar
        );

        LOGGER.info("Player {} took {} delayed damage, total pending: {}",
            player.getName().getString(), amount, totalPending);
    }

    private void tickPendingDamage(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        PendingDamage pending = pendingDamageMap.get(playerId);

        if (pending == null || pending.isEmpty()) {
            return;
        }

        // Only apply damage every TICK_INTERVAL ticks
        pending.incrementTick();
        if (pending.getTickCounter() % TICK_INTERVAL != 0) {
            return;
        }

        // Calculate damage to apply this tick
        float damageThisTick = pending.consumeDamage();

        if (damageThisTick > 0) {
            // Apply the damage directly to health, bypassing the event system
            float currentHealth = player.getHealth();
            float newHealth = Math.max(0, currentHealth - damageThisTick);
            player.setHealth(newHealth);

            // Show damage indicator
            if (pending.getTotalPending() > 0) {
                player.sendMessage(
                    Text.literal("Draining: ")
                        .formatted(Formatting.DARK_RED)
                        .append(Text.literal(String.format("%.1f", pending.getTotalPending()))
                            .formatted(Formatting.WHITE, Formatting.BOLD))
                        .append(Text.literal(" remaining")
                            .formatted(Formatting.DARK_RED)),
                    true
                );
            }

            // Check for death
            if (newHealth <= 0 && !player.isDead()) {
                player.damage(player.getDamageSources().generic(), 0.001f);
            }
        }

        // Clean up if no more pending damage
        if (pending.isEmpty()) {
            pendingDamageMap.remove(playerId);
            player.sendMessage(Text.literal("Damage fully applied").formatted(Formatting.GRAY), true);
        }
    }

    // Static method to allow healing to counteract pending damage
    public static float reducePendingDamage(PlayerEntity player, float healAmount) {
        if (!(player instanceof ServerPlayerEntity)) {
            return healAmount;
        }

        UUID playerId = player.getUuid();
        PendingDamage pending = pendingDamageMap.get(playerId);

        if (pending == null || pending.isEmpty()) {
            return healAmount; // No pending damage, full heal
        }

        // Reduce pending damage by heal amount
        float reduced = pending.reduceDamage(healAmount);
        float actualHeal = healAmount - reduced;

        if (reduced > 0) {
            player.sendMessage(
                Text.literal("Healing counteracted ")
                    .formatted(Formatting.GREEN)
                    .append(Text.literal(String.format("%.1f", reduced))
                        .formatted(Formatting.WHITE, Formatting.BOLD))
                    .append(Text.literal(" pending damage!")
                        .formatted(Formatting.GREEN)),
                true
            );
        }

        return actualHeal;
    }

    public static float getPendingDamage(PlayerEntity player) {
        PendingDamage pending = pendingDamageMap.get(player.getUuid());
        return pending != null ? pending.getTotalPending() : 0;
    }

    // Inner class to track pending damage
    private static class PendingDamage {
        private float totalDamage = 0;
        private int remainingTicks = 0;
        private int tickCounter = 0;

        public void addDamage(float amount, int durationTicks) {
            totalDamage += amount;
            // Extend duration when stacking, but cap at 2x normal duration
            remainingTicks = Math.min(remainingTicks + durationTicks, durationTicks * 2);
        }

        public float consumeDamage() {
            if (totalDamage <= 0 || remainingTicks <= 0) {
                return 0;
            }

            // Calculate portion of damage to apply
            float damagePerTick = totalDamage / (remainingTicks / (float) TICK_INTERVAL);
            damagePerTick = Math.min(damagePerTick, totalDamage);

            totalDamage -= damagePerTick;
            remainingTicks -= TICK_INTERVAL;

            if (totalDamage < 0.01f) {
                totalDamage = 0;
            }

            return damagePerTick;
        }

        public float reduceDamage(float amount) {
            float reduced = Math.min(amount, totalDamage);
            totalDamage -= reduced;
            if (totalDamage < 0.01f) {
                totalDamage = 0;
                remainingTicks = 0;
            }
            return reduced;
        }

        public float getTotalPending() {
            return totalDamage;
        }

        public boolean isEmpty() {
            return totalDamage <= 0.01f;
        }

        public void incrementTick() {
            tickCounter++;
        }

        public int getTickCounter() {
            return tickCounter;
        }
    }
}
