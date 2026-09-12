package com.github.epsilon.mixins;

import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.impl.*;
import com.github.epsilon.modules.impl.combat.killaura.KillAura;
import com.github.epsilon.modules.impl.movement.NoPacketSprint;
import com.github.epsilon.modules.impl.movement.Velocity;
import com.github.epsilon.modules.impl.player.InvManager;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public class MixinLocalPlayer extends AbstractClientPlayer {

    @Shadow
    protected int sprintTriggerTime;

    @Shadow
    public ClientInput input;

    @Unique
    private SendPositionEvent epsilon$sendPositionEvent;
    @Unique
    private SlowdownEvent epsilon$slowdownEvent;
    @Unique
    private boolean epsilon$slowdownUsing;

    protected MixinLocalPlayer(ClientLevel level, GameProfile gameProfile) {
        super(level, gameProfile);
    }

    @Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
    private void preventSprintDuringInventorySorting(CallbackInfoReturnable<Boolean> cir) {
        InvManager invManager = InvManager.INSTANCE;
        if (invManager.isEnabled() && invManager.isSprintTransitionPending()) {
            this.sprintTriggerTime = 0;
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V", shift = At.Shift.BEFORE, ordinal = 0), cancellable = true)
    private void onPreTick(CallbackInfo ci) {
        PlayerTickEvent.Pre event = EventBus.INSTANCE.post(new PlayerTickEvent.Pre());
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V", shift = At.Shift.AFTER, ordinal = 0))
    private void onPostTick(CallbackInfo ci) {
        EventBus.INSTANCE.post(new PlayerTickEvent.Post());
    }

    @Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
    private void onPreSendPosition(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        epsilon$sendPositionEvent = EventBus.INSTANCE.post(new SendPositionEvent(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), player.onGround()));
        if (epsilon$sendPositionEvent.isCancelled()) {
            ci.cancel();
            EventBus.INSTANCE.post(new AfterSendPositionEvent());
        }
    }

    @Inject(method = "sendPosition", at = @At("TAIL"))
    private void onPostSendPosition(CallbackInfo ci) {
        EventBus.INSTANCE.post(new AfterSendPositionEvent());
    }

    @Inject(method = "swing", at = @At("HEAD"), cancellable = true)
    private void onSwing(InteractionHand hand, CallbackInfo ci) {
        SwingHandEvent event = EventBus.INSTANCE.post(new SwingHandEvent());
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 redirectPosition(LocalPlayer instance, Operation<Vec3> original) {
        return new Vec3(epsilon$sendPositionEvent.getX(), epsilon$sendPositionEvent.getY(), epsilon$sendPositionEvent.getZ());
    }

    @WrapOperation(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"))
    private double redirectGetX(LocalPlayer instance, Operation<Double> original) {
        return epsilon$sendPositionEvent.getX();
    }

    @WrapOperation(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"))
    private double redirectGetY(LocalPlayer instance, Operation<Double> original) {
        return epsilon$sendPositionEvent.getY();
    }

    @WrapOperation(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"))
    private double redirectGetZ(LocalPlayer instance, Operation<Double> original) {
        return epsilon$sendPositionEvent.getZ();
    }

    @WrapOperation(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
    private float redirectGetYRot(LocalPlayer instance, Operation<Float> original) {
        return epsilon$sendPositionEvent.getYaw();
    }

    @WrapOperation(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"))
    private float redirectGetXRot(LocalPlayer instance, Operation<Float> original) {
        return epsilon$sendPositionEvent.getPitch();
    }

    @WrapOperation(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;onGround()Z"))
    private boolean redirectOnGround(LocalPlayer instance, Operation<Boolean> original) {
        return epsilon$sendPositionEvent.isOnGround();
    }

    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
    private void hookPushOutOfBlocks(double x, double d, CallbackInfo info) {
        if (Velocity.INSTANCE.isEnabled() && Velocity.INSTANCE.mode.is(Velocity.Mode.Cancel) && Velocity.INSTANCE.blockPush.getValue()) {
            info.cancel();
        }
    }

    @WrapOperation(method = "modifyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
    private boolean onSlowdown(LocalPlayer localPlayer, Operation<Boolean> original) {
        // KillAura AutoBlock 的服务端格挡同样套用 1.8 使用物品减速（默认 0.2 倍率）
        boolean usingOrBlocking = original.call(localPlayer) || KillAura.INSTANCE.isBlockingServerSide();
        SlowdownEvent event = EventBus.INSTANCE.post(new SlowdownEvent(usingOrBlocking));
        epsilon$slowdownEvent = event;
        epsilon$slowdownUsing = event.isSlowdown();
        return event.isSlowdown();
    }

    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;aiStep()V"))
    private void noPacketSprintReassert(CallbackInfo ci) {
        // 位于原版疾跑校验之后、travel 之前：任何来源的疾跑覆盖都在此被纠正
        //（LocalPlayer 的 super.aiStep() 经 AbstractClientPlayer 转发）
        NoPacketSprint.INSTANCE.reassertAllDir();
    }

    @ModifyExpressionValue(method = "canStartSprinting", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;hasForwardImpulse()Z"))
    private boolean noPacketSprintAllDirStart(boolean original) {
        // AllDir 启动侧：侧移/后退时也允许疾跑启动（仍受 isSprintingPossible、使用物品等原版条件约束）
        if (!original && NoPacketSprint.INSTANCE.shouldKeepSprint()) {
            return true;
        }
        return original;
    }

    @ModifyExpressionValue(method = "shouldStopRunSprinting", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;hasForwardImpulse()Z"))
    private boolean noPacketSprintAllDirKeep(boolean original) {
        // AllDir 维持侧：仅当前向判定本身为假时才需要补；仍要求玩家处于移动状态，与原版"静止停疾跑"一致
        if (!original && NoPacketSprint.INSTANCE.shouldKeepSprint()) {
            return true;
        }
        return original;
    }

    @WrapOperation(method = "modifyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;itemUseSpeedMultiplier()F"))
    private float modifySlowdownMultiplier(LocalPlayer localPlayer, Operation<Float> original) {
        // isUsingItem 与 itemUseSpeedMultiplier 在同一 modifyInput 调用中先后触发，
        // 若本次没有发布 SlowdownEvent 则沿用物品自身的倍率
        SlowdownEvent event = epsilon$slowdownEvent;
        if (event != null && epsilon$slowdownUsing && event.hasMultiplier()) {
            epsilon$slowdownEvent = null;
            epsilon$slowdownUsing = false;
            return event.getMultiplier();
        }
        return original.call(localPlayer);
    }

    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), cancellable = true)
    private void onMove(MoverType moverType, Vec3 delta, CallbackInfo ci) {
        MoveEvent event = EventBus.INSTANCE.post(new MoveEvent(delta.x, delta.y, delta.z));
        if (event.isCancelled()) {
            super.move(moverType, new Vec3(event.getX(), event.getY(), event.getZ()));
            ci.cancel();
        }
    }

}
