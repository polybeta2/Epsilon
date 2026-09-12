package com.github.epsilon.modules.impl.combat.killaura;

import com.github.epsilon.managers.rotation.RotationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

import static com.github.epsilon.Constants.mc;

/**
 * KillAura 的 AutoBlock 拆分层。Matrix 模式移植自 Lyasim：
 * 目标处于格挡范围内时通过服务端包维持举盾（客户端不真实使用物品），
 * 攻击瞬间按 C07(释放)→攻击→C08(补盾) 括弧发包，保证攻击到达服务端时
 * 不处于"正在使用物品"状态，规避 Matrix 的格挡-攻击互斥校验。
 */
class KillAuraAutoBlock {

    private final KillAura aura;
    private boolean blocking;
    private boolean blockingState;

    KillAuraAutoBlock(KillAura aura) {
        this.aura = aura;
    }

    /**
     * 每 tick 维护：范围内举盾；目标丢失、超距或条件不满足时收盾。
     */
    void tick(LivingEntity target) {
        if (!aura.autoBlockMode.is(KillAura.AutoBlockMode.Matrix)) {
            reset();
            return;
        }

        if (canBlock(target)) {
            if (!blocking) {
                raiseShield();
                blocking = true;
            }
        } else {
            reset();
            blocking = false;
        }
    }

    /**
     * 攻击前释放盾；返回攻击后是否需要补盾。
     */
    boolean beginAttack() {
        if (!blockingState) {
            return false;
        }
        sendReleaseUseItem();
        blockingState = false;
        return true;
    }

    /**
     * 攻击后重新举盾，维持服务端格挡状态。
     */
    void endAttack(boolean wasBlocking) {
        if (!wasBlocking) {
            return;
        }
        raiseShield();
    }

    /**
     * 收盾并清除全部状态；服务端仍认为举盾时先补发释放包。
     */
    void reset() {
        if (blockingState) {
            sendReleaseUseItem();
        }
        blocking = false;
        blockingState = false;
    }

    private boolean canBlock(LivingEntity target) {
        if (target == null || !aura.isEnabled()) {
            return false;
        }
        return holdsShield()
                && mc.player.distanceToSqr(target) <= Mth.square(aura.blockRange.getValue());
    }

    private boolean holdsShield() {
        return mc.player.getOffhandItem().is(Items.SHIELD) || mc.player.getMainHandItem().is(Items.SHIELD);
    }

    private void raiseShield() {
        InteractionHand hand = mc.player.getOffhandItem().is(Items.SHIELD) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        mc.getConnection().send(new ServerboundUseItemPacket(
                hand,
                mc.level.getBlockStatePredictionHandler().startPredicting().currentSequence(),
                RotationManager.INSTANCE.getYaw(),
                RotationManager.INSTANCE.getPitch()
        ));
        blockingState = true;
    }

    private void sendReleaseUseItem() {
        mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                BlockPos.ZERO,
                Direction.DOWN
        ));
    }

}
