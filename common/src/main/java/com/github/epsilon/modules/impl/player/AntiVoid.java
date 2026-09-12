package com.github.epsilon.modules.impl.player;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.BlockCollisionEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.movement.Flight;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.player.FallingPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Anti Void 通过模拟未来若干 tick 的下落轨迹判断玩家是否处于危险坠落。
 * 触发后在救援位置下方注入客户端幽灵方块碰撞，玩家落在虚拟地板上等待服务器拉回（lagback），
 * 避免客户端直接坠入虚空死亡。
 */
public class AntiVoid extends Module {

    public static final AntiVoid INSTANCE = new AntiVoid();

    private AntiVoid() {
        super("Anti Void", Category.PLAYER);
    }

    private enum Mode {
        // 坠落预测：模拟轨迹坠入虚空（到 Void Level 之间无碰撞）才触发
        Predict,
        // 最大摔落：按当前速度再坠落 Max Fall 格仍无落点即触发，不限于虚空
        MaxFall
    }

    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Predict);
    private final IntSetting voidLevel = intSetting("Void Level", 0, -256, 0, 1, () -> mode.is(Mode.Predict));
    private final IntSetting maxFall = intSetting("Max Fall", 10, 1, 100, 1, () -> mode.is(Mode.MaxFall));
    private final DoubleSetting fallDistance = doubleSetting("Fall Distance", 0.0, 0.0, 40.0, 0.1);

    // 用于判断坠落的未来模拟 tick 数
    private static final int SAFE_TICKS_THRESHOLD = 10;
    // ticksToFall 的估算上限，防止速度异常时死循环
    private static final int MAX_SIM_TICKS = 200;

    private boolean likelyFalling;
    private Vec3 rescuePos;
    // Fall Distance 门槛闩锁：武装后保持幽灵方块，直到恢复安全（落到真实方块或被服务器拉回）才解除，
    // 否则落在幽灵地板上 fallDistance 归零会让幽灵方块反复消失，玩家无限坠落
    private boolean armed;

    // 模块自身做碰撞检查期间必须屏蔽幽灵方块注入，否则检查总会命中自己放置的幽灵碰撞
    private boolean checkingCollisions;

    @Override
    protected void onEnable() {
        likelyFalling = false;
        rescuePos = null;
        armed = false;
    }

    @Override
    protected void onDisable() {
        likelyFalling = false;
        rescuePos = null;
        armed = false;
    }

    private boolean isExempt() {
        return mc.player.isDeadOrDying() || Flight.INSTANCE.isEnabled();
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) {
            likelyFalling = false;
            armed = false;
            return;
        }

        checkingCollisions = true;
        boolean falling;
        try {
            falling = mode.is(Mode.Predict) ? isPredictingFall() : isMaxFallReached();
        } finally {
            checkingCollisions = false;
        }

        likelyFalling = falling;

        // 安全时持续刷新救援位置；危险坠落中保留锚点
        if (!likelyFalling) {
            armed = false;
            rescuePos = mc.player.position();
        } else if (!armed && mc.player.fallDistance >= fallDistance.getValue()) {
            armed = true;
            // Fall Distance 门槛延迟武装后，把锚点下移到当前深度；门槛为 0 时保持最后安全位置
            if (fallDistance.getValue() > 0.0) {
                rescuePos = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            }
        }
    }

    /**
     * 坠落预测：模拟未来若干 tick，一旦进入下落状态就检查该位置到虚空阈值之间是否存在碰撞。
     */
    private boolean isPredictingFall() {
        FallingPlayer simulation = new FallingPlayer(mc.player);
        double previousY = mc.player.getY();
        for (int tick = 0; tick < SAFE_TICKS_THRESHOLD; tick++) {
            simulation.calculate(1);
            double y = simulation.getY();
            if (y < previousY) {
                return isSafeForRescue(simulation.getX(), y, simulation.getZ());
            }
            previousY = y;
        }
        return false;
    }

    /**
     * 最大摔落：以当前竖直速度模拟下落 Max Fall 格，路径上没有任何可落地方块才触发。
     */
    private boolean isMaxFallReached() {
        FallingPlayer simulation = new FallingPlayer(mc.player);
        return simulation.findCollision(ticksToFall(maxFall.getValue())) == null;
    }

    /**
     * 估算按当前竖直速度再下落指定格数所需的 tick 数。
     */
    private int ticksToFall(int blocks) {
        double motionY = mc.player.getDeltaMovement().y;
        double fallen = 0.0;
        int ticks = 0;
        while (fallen < blocks && ticks < MAX_SIM_TICKS) {
            motionY = (motionY - 0.08) * 0.98;
            if (motionY < 0.0) {
                fallen -= motionY;
            }
            ticks++;
        }
        return ticks;
    }

    private boolean isSafeForRescue(double x, double y, double z) {
        Vec3 playerPos = mc.player.position();
        AABB box = mc.player.getBoundingBox()
                .move(x - playerPos.x, y - playerPos.y, z - playerPos.z)
                .setMinY(voidLevel.getValue());

        // BlockCollisions 迭代器只产出非空碰撞形状，能迭代即说明存在碰撞
        for (VoxelShape ignored : mc.level.getBlockCollisions(mc.player, box)) {
            return false;
        }
        return true;
    }

    @EventHandler
    private void onBlockCollision(BlockCollisionEvent event) {
        if (checkingCollisions || !likelyFalling || !armed || isExempt()) {
            return;
        }
        if (rescuePos == null) {
            return;
        }

        // 只在救援位置（锚点）下方、原本没有碰撞形状的位置放置幽灵方块
        BlockPos pos = event.getPos();
        if (pos.getY() >= Math.floor(rescuePos.y)) {
            return;
        }
        if (!event.getState().getCollisionShape(mc.level, pos).isEmpty()) {
            return;
        }

        event.setState(Blocks.STONE.defaultBlockState());
    }

}
