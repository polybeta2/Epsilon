package com.github.epsilon.utils.player;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 击退轨迹预测器：复现 LivingEntity.travelInAir + Entity.move 的核心物理，
 * 纯函数不修改任何游戏状态。物理参数以 26.2 参考源码为准：
 * 空中加速 0.026/0.02、地面加速 speed*(0.21600002/friction^3)、重力取 Attributes.GRAVITY、
 * 水平阻力 friction*0.91、垂直阻力 0.98，碰撞复用 vanilla 的 collideBoundingBox。
 */
public final class KnockbackPredictor {

    private static final double COLLISION_EPSILON = 1.0E-7D;
    private static final float GROUND_ACCEL_FACTOR = 0.21600002F;
    private static final float AIR_ACCEL_SPRINT = 0.025999999F;
    private static final float AIR_ACCEL_NORMAL = 0.02F;

    private KnockbackPredictor() {
    }

    public record Input(float strafe, float forward, float yaw, boolean sprinting) {
    }

    public record Result(List<Vec3> path, AABB landingBox, boolean voidDanger, int simulatedTicks) {
    }

    /**
     * 从玩家当前状态提取输入快照并预测；输入在模拟期间保持不变。
     */
    public static Result predictCurrentInput(LocalPlayer player, ClientLevel level, Vec3 initialVelocity, int ticks) {
        Input input = new Input(player.xxa, player.zza, player.getYRot(), player.isSprinting());
        return predict(player, level, initialVelocity, ticks, input);
    }

    public static Result predict(LocalPlayer player, ClientLevel level, Vec3 initialVelocity, int ticks, Input input) {
        AABB sourceBox = player.getBoundingBox();
        double width = sourceBox.getXsize();
        double height = sourceBox.getYsize();
        Vec3 position = player.position();
        AABB box = playerBox(position, width, height);
        Vec3 motion = initialVelocity;
        boolean onGround = player.onGround();

        float airDragModifier = (float) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.AIR_DRAG_MODIFIER);
        float frictionModifier = (float) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FRICTION_MODIFIER);
        double gravity = player.getGravity();

        List<Vec3> path = new ArrayList<>(ticks + 1);
        path.add(position.add(0.0, height * 0.5, 0.0));

        for (int tick = 1; tick <= ticks; tick++) {
            boolean wasOnGround = onGround;
            AABB boxBeforeMove = box;

            // 步骤 1：按 travelInAir 的顺序先算本 tick 摩擦，再叠加输入加速度
            float blockFriction = wasOnGround
                    ? computeModifiedFriction(groundFriction(level, boxBeforeMove), frictionModifier)
                    : 1.0F;
            float inputSpeed = wasOnGround
                    ? (blockFriction > 0.6F
                            ? player.getSpeed() * (GROUND_ACCEL_FACTOR / (blockFriction * blockFriction * blockFriction))
                            : player.getSpeed())
                    : (input.sprinting() ? AIR_ACCEL_SPRINT : AIR_ACCEL_NORMAL);
            motion = addInput(motion, input, inputSpeed);

            // 步骤 2：碰撞移动，直接复用 vanilla 的轴序碰撞解析
            Vec3 allowed = Entity.collideBoundingBox((Entity) null, motion, box, level, List.of());
            box = box.move(allowed);
            boolean collidedX = Math.abs(allowed.x - motion.x) > COLLISION_EPSILON;
            boolean collidedY = Math.abs(allowed.y - motion.y) > COLLISION_EPSILON;
            boolean collidedZ = Math.abs(allowed.z - motion.z) > COLLISION_EPSILON;
            position = new Vec3((box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5);
            path.add(position.add(0.0, height * 0.5, 0.0));

            // 步骤 3：落地判定，落地即返回当前盒作为落点
            boolean landed = collidedY && motion.y < 0.0;
            if (landed) {
                return new Result(Collections.unmodifiableList(path), box, false, tick);
            }

            // 步骤 4：碰撞轴速度清零后应用重力与阻力；地面摩擦取移动前的状态
            double motionY = (collidedY ? 0.0 : motion.y) - getEffectiveGravity(player, gravity);
            float horizontalDrag = blockFriction * computeModifiedFriction(0.91F, airDragModifier);
            float verticalDrag = computeModifiedFriction(0.98F, airDragModifier);
            motion = new Vec3(
                    (collidedX ? 0.0 : motion.x) * horizontalDrag,
                    motionY * verticalDrag,
                    (collidedZ ? 0.0 : motion.z) * horizontalDrag);
            onGround = landed;

            // 步骤 5：整盒跌出世界底部视为入虚空
            if (box.maxY < level.getMinY()) {
                return new Result(Collections.unmodifiableList(path), null, true, tick);
            }
        }

        boolean voidDanger = isDefinitelyVoidBelow(level, box);
        return new Result(Collections.unmodifiableList(path), null, voidDanger, ticks);
    }

    /**
     * 复现 moveRelative + getInputVector：输入向量长度超过 1 时先归一化，防止轻按方向键被放大。
     */
    private static Vec3 addInput(Vec3 motion, Input input, float speed) {
        Vec3 travelInput = new Vec3(input.strafe(), 0.0, input.forward());
        double length = travelInput.lengthSqr();
        if (length < 1.0E-7) {
            return motion;
        }

        Vec3 scaled = (length > 1.0 ? travelInput.normalize() : travelInput).scale(speed);
        float sin = (float) Math.sin(input.yaw() * (float) (Math.PI / 180.0));
        float cos = (float) Math.cos(input.yaw() * (float) (Math.PI / 180.0));
        return motion.add(scaled.x * cos - scaled.z * sin, 0.0, scaled.z * cos + scaled.x * sin);
    }

    private static float groundFriction(ClientLevel level, AABB box) {
        BlockPos below = BlockPos.containing(
                (box.minX + box.maxX) * 0.5,
                box.minY - 0.500001F,
                (box.minZ + box.maxZ) * 0.5);
        return level.getBlockState(below).getBlock().getFriction();
    }

    /**
     * 复现 LivingEntity.computeModifiedFriction 与 getEffectiveGravity（含缓慢下落钳制）。
     */
    private static float computeModifiedFriction(float friction, float modifier) {
        return net.minecraft.util.Mth.clamp(1.0F - (1.0F - friction) * modifier, 0.0F, 1.0F);
    }

    private static double getEffectiveGravity(LocalPlayer player, double gravity) {
        boolean isFalling = player.getDeltaMovement().y <= 0.0;
        return isFalling && player.hasEffect(MobEffects.SLOW_FALLING) ? Math.min(gravity, 0.01) : gravity;
    }

    /**
     * 模拟耗尽仍未落地时判断"下方整列无任何方块"，区块未加载时保守返回 false。
     */
    private static boolean isDefinitelyVoidBelow(ClientLevel level, AABB box) {
        int minChunkX = net.minecraft.util.Mth.floor(box.minX) >> 4;
        int maxChunkX = net.minecraft.util.Mth.floor(box.maxX) >> 4;
        int minChunkZ = net.minecraft.util.Mth.floor(box.minZ) >> 4;
        int maxChunkZ = net.minecraft.util.Mth.floor(box.maxZ) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }

        if (box.minY <= level.getMinY()) {
            return true;
        }

        AABB column = new AABB(
                box.minX + 1.0E-5, level.getMinY(), box.minZ + 1.0E-5,
                box.maxX - 1.0E-5, box.minY, box.maxZ - 1.0E-5);
        return level.noCollision(column);
    }

    private static AABB playerBox(Vec3 position, double width, double height) {
        double halfWidth = width * 0.5;
        return new AABB(
                position.x - halfWidth, position.y, position.z - halfWidth,
                position.x + halfWidth, position.y + height, position.z + halfWidth);
    }

}
