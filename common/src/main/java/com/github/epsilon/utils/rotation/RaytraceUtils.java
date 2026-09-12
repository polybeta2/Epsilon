package com.github.epsilon.utils.rotation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.github.epsilon.Constants.mc;

public class RaytraceUtils {

    /**
     * 攻击选点结果：包围盒上的目标点及其可见性。
     */
    public record AttackSpot(Vec3 pos, boolean visible) {
    }

    // 包围盒表面扫描步长
    private static final double SPOT_SCAN_STEP = 0.5;

    /**
     * 在目标包围盒上寻找可攻击点（参照 BMW raytraceBox 的双轨策略）：
     * 可见点在 range 内选取，不可见点只允许 wallRange 内穿墙；
     * 两条轨都按与上次旋转的角度差打分，优先返回可见点。
     *
     * @param target       目标实体
     * @param range        可见攻击距离
     * @param wallRange    穿墙攻击距离
     * @param lastRotation 上一次旋转，用于打分保持旋转连续性
     * @return 选点结果；眼睛在盒内或无可行点时返回 null
     */
    public static AttackSpot findAttackSpot(Entity target, double range, double wallRange, Rot2f lastRotation) {
        if (mc.level == null || mc.player == null) {
            return null;
        }

        AABB box = target.getBoundingBox();
        Vec3 eyes = mc.player.getEyePosition();
        if (box.contains(eyes)) {
            return null;
        }

        double wallCap = Math.min(wallRange, range);
        Vec3 preferenceVec = Vec3.directionFromRotation(lastRotation.getPitch(), lastRotation.getYaw());

        AttackSpot bestVisible = null;
        AttackSpot bestHidden = null;
        double bestVisibleScore = Double.MAX_VALUE;
        double bestHiddenScore = Double.MAX_VALUE;

        for (Vec3 spot : collectSpots(box, eyes, preferenceVec, range)) {
            double dist = eyes.distanceTo(spot);
            boolean visible = canSeePointFrom(eyes, spot, ClipContext.Block.COLLIDER);
            if (visible ? dist > range : dist > wallCap) {
                continue;
            }

            Rot2f rotation = RotationUtils.calculate(eyes, spot);
            double score = Math.abs(Mth.wrapDegrees(rotation.getYaw() - lastRotation.getYaw()))
                    + Math.abs(rotation.getPitch() - lastRotation.getPitch());

            if (visible) {
                if (score < bestVisibleScore) {
                    bestVisibleScore = score;
                    bestVisible = new AttackSpot(spot, true);
                }
            } else if (score < bestHiddenScore) {
                bestHiddenScore = score;
                bestHidden = new AttackSpot(spot, false);
            }
        }

        return bestVisible != null ? bestVisible : bestHidden;
    }

    /**
     * 收集包围盒上的候选攻击点：上次旋转射线与盒子的交点、距眼睛最近点、六个面的网格采样。
     */
    private static List<Vec3> collectSpots(AABB box, Vec3 eyes, Vec3 preferenceVec, double range) {
        List<Vec3> spots = new ArrayList<>();

        // 上一旋转方向的射线与盒子表面交点，保证旋转连续时优先打在原命中位置附近
        box.clip(eyes, eyes.add(preferenceVec.scale(range * 2.0))).ifPresent(spots::add);

        // 距眼睛最近的盒子表面点（范围边缘收益最大的点）
        spots.add(new Vec3(
                Mth.clamp(eyes.x, box.minX, box.maxX),
                Mth.clamp(eyes.y, box.minY, box.maxY),
                Mth.clamp(eyes.z, box.minZ, box.maxZ)
        ));

        double eps = 1.0E-7;
        double[] xs = {box.minX, box.maxX};
        double[] ys = {box.minY, box.maxY};
        double[] zs = {box.minZ, box.maxZ};
        for (double x : xs) {
            for (double y = box.minY; y <= box.maxY + eps; y += SPOT_SCAN_STEP) {
                for (double z = box.minZ; z <= box.maxZ + eps; z += SPOT_SCAN_STEP) {
                    spots.add(new Vec3(x, y, z));
                }
            }
        }
        for (double y : ys) {
            for (double x = box.minX; x <= box.maxX + eps; x += SPOT_SCAN_STEP) {
                for (double z = box.minZ; z <= box.maxZ + eps; z += SPOT_SCAN_STEP) {
                    spots.add(new Vec3(x, y, z));
                }
            }
        }
        for (double z : zs) {
            for (double x = box.minX; x <= box.maxX + eps; x += SPOT_SCAN_STEP) {
                for (double y = box.minY; y <= box.maxY + eps; y += SPOT_SCAN_STEP) {
                    spots.add(new Vec3(x, y, z));
                }
            }
        }

        return spots;
    }

    /**
     * 判断两点之间是否没有方块遮挡。
     *
     * @param eyes 射线起点
     * @param vec3 射线终点
     * @return 判断结果
     */
    public static boolean canSeePointFrom(Vec3 eyes, Vec3 vec3) {
        return canSeePointFrom(eyes, vec3, ClipContext.Block.OUTLINE);
    }

    /**
     * 判断两点之间是否没有方块遮挡，可指定方块形状类型。
     * COLLIDER 不受草类等非碰撞方块影响，适合战斗可见性判定。
     *
     * @param eyes 射线起点
     * @param vec3 射线终点
     * @param block 方块形状类型
     * @return 判断结果
     */
    public static boolean canSeePointFrom(Vec3 eyes, Vec3 vec3, ClipContext.Block block) {
        return mc.level.clip(new ClipContext(eyes, vec3, block, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    /**
     * 按指定旋转执行方块和实体射线追踪。
     *
     * @param rotation 旋转角
     * @param range    射线追踪或自适应选点距离
     * @return 操作结果
     */
    public static HitResult raytrace(Rot2f rotation, double range) {
        return raytrace(rotation, range, 0);
    }

    /**
     * 按指定旋转执行方块和实体射线追踪。
     *
     * @param rotation 旋转角
     * @param range    射线追踪或自适应选点距离
     * @param expand   实体包围盒扩大量
     * @return 操作结果
     */
    public static HitResult raytrace(Rot2f rotation, double range, float expand) {
        return raytrace(rotation, range, expand, mc.player);
    }

    /**
     * 按指定旋转执行方块和实体射线追踪。
     *
     * @param rotation 旋转角
     * @param range    射线追踪或自适应选点距离
     * @param expand   实体包围盒扩大量
     * @param entity   实体
     * @return 操作结果
     */
    public static HitResult raytrace(Rot2f rotation, double range, float expand, Entity entity) {
        if (mc.level == null || entity == null) return null;

        float partialTicks = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);

        Vec3 eyePos = entity.getEyePosition(partialTicks);
        Vec3 lookVec = Vec3.directionFromRotation(rotation.getPitch(), rotation.getYaw());
        Vec3 endVec = eyePos.add(lookVec.scale(range));

        HitResult objectMouseOver = mc.level.clip(new ClipContext(
                eyePos,
                endVec,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                entity
        ));

        double distToBlock = range;
        if (objectMouseOver.getType() != HitResult.Type.MISS) {
            distToBlock = objectMouseOver.getLocation().distanceTo(eyePos);
        }

        Vec3 entitySearchEndVec = eyePos.add(lookVec.scale(range));

        Entity pointedEntity = null;
        Vec3 hitVec = null;
        double currentDist = distToBlock;

        AABB searchBox = entity.getBoundingBox().expandTowards(lookVec.scale(range)).inflate(1.0);

        List<Entity> list = mc.level.getEntities(entity, searchBox, e -> !e.isSpectator() && e.isPickable());

        for (Entity candidate : list) {
            float collisionSize = candidate.getPickRadius() + expand;
            AABB entityBox = candidate.getBoundingBox().inflate(collisionSize);

            Optional<Vec3> intercept = entityBox.clip(eyePos, entitySearchEndVec);

            if (entityBox.contains(eyePos)) {
                if (currentDist >= 0.0) {
                    pointedEntity = candidate;
                    hitVec = intercept.orElse(eyePos);
                    currentDist = 0.0;
                }
            } else if (intercept.isPresent()) {
                Vec3 interceptVec = intercept.get();
                double d3 = eyePos.distanceTo(interceptVec);

                if (d3 < currentDist || currentDist == 0.0) {
                    if (candidate.getRootVehicle() == entity.getRootVehicle()) {
                        if (currentDist == 0.0) {
                            pointedEntity = candidate;
                            hitVec = interceptVec;
                        }
                    } else {
                        pointedEntity = candidate;
                        hitVec = interceptVec;
                        currentDist = d3;
                    }
                }
            }
        }

        if (pointedEntity != null && (currentDist < distToBlock || objectMouseOver.getType() == HitResult.Type.MISS)) {
            return new EntityHitResult(pointedEntity, hitVec);
        }

        return objectMouseOver;
    }

    /**
     * 判断指定旋转是否命中目标方块或指定方块面。
     *
     * @param rotation 旋转角
     * @param dir      预期命中的方块面
     * @param pos      目标位置
     * @param strict   是否要求命中指定方块面
     * @return 判断结果
     */
    public static boolean overBlock(Rot2f rotation, Direction dir, BlockPos pos, boolean strict) {
        Vec3 lookVec = Vec3.directionFromRotation(rotation.getPitch(), rotation.getYaw());

        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        double reach = 4.5;
        Vec3 endVec = eyePos.add(lookVec.scale(reach));

        BlockHitResult result = mc.level.clip(new ClipContext(
                eyePos,
                endVec,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                mc.player
        ));

        if (result.getType() == HitResult.Type.MISS) {
            return false;
        }

        return result.getBlockPos().equals(pos) && (!strict || result.getDirection() == dir);
    }

    /**
     * 判断指定旋转是否命中目标方块或指定方块面。
     *
     * @param rotation 旋转角
     * @param pos      目标位置
     * @param strict   是否要求命中指定方块面
     * @return 判断结果
     */
    public static boolean overBlock(Rot2f rotation, BlockPos pos, boolean strict) {
        return overBlock(rotation, Direction.UP, pos, strict);
    }

    /**
     * 判断指定旋转是否命中目标方块或指定方块面。
     *
     * @param rotation 旋转角
     * @param pos      目标位置
     * @return 判断结果
     */
    public static boolean overBlock(Rot2f rotation, BlockPos pos) {
        return overBlock(rotation, Direction.UP, pos, false);
    }

    /**
     * 判断指定旋转是否命中目标方块或指定方块面。
     *
     * @param rotation   旋转角
     * @param pos        目标位置
     * @param enumFacing 预期命中的方块面
     * @return 判断结果
     */
    public static boolean overBlock(Rot2f rotation, BlockPos pos, Direction enumFacing) {
        return overBlock(rotation, enumFacing, pos, true);
    }

}
