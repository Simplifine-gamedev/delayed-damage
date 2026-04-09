package com.delayeddamage.mixin;

import com.delayeddamage.DelayedDamageMod;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @ModifyVariable(method = "heal", at = @At("HEAD"), argsOnly = true)
    private float modifyHealAmount(float amount) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (self instanceof PlayerEntity player) {
            // Let healing counteract pending damage
            return DelayedDamageMod.reducePendingDamage(player, amount);
        }

        return amount;
    }
}
