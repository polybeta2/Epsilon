package com.github.epsilon.modules.impl.render;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.Render2DEvent;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.graphics.schedulers.render2d.Render2DScheduler;
import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler;
import com.github.epsilon.graphics.text.StaticFontLoader;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.SettingGroup;
import com.github.epsilon.settings.impl.*;
import com.github.epsilon.utils.entity.ProjectileSimulator;
import com.github.epsilon.utils.player.ChatUtils;
import com.github.epsilon.utils.render.WorldToScreen;
import com.google.common.base.Suppliers;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public class FireballPredict extends Module {

    public static final FireballPredict INSTANCE = new FireballPredict();

    private FireballPredict() {
        super("Fireball Predict", Category.RENDER);
    }

    private enum PhysicsMode {
        Simulator,
        LinearRaycast
    }

    private final SettingGroup generalGroup = settingGroup("General");
    private final SettingGroup renderGroup = settingGroup("Render");

    private final EnumSetting<PhysicsMode> physicsMode = enumSetting("Physics Mode", PhysicsMode.Simulator).group(generalGroup);
    private final DoubleSetting maxRenderDistance = doubleSetting("Max Render Distance", 96.0, 16.0, 512.0, 8.0).group(generalGroup);
    private final IntSetting maxSimulatedTicks = intSetting("Max Simulated Ticks", 240, 1, 1000, 10, () -> physicsMode.is(PhysicsMode.Simulator)).group(generalGroup);
    private final DoubleSetting raycastDistance = doubleSetting("Raycast Distance", 500.0, 64.0, 1000.0, 16.0, () -> physicsMode.is(PhysicsMode.LinearRaycast)).group(generalGroup);
    private final DoubleSetting explosionRadius = doubleSetting("Explosion Radius", 2.0, 0.5, 8.0, 0.5).group(generalGroup);
    private final BoolSetting includeWitherSkulls = boolSetting("Include Wither Skulls", false).group(generalGroup);
    private final BoolSetting handheldMode = boolSetting("Handheld Mode", true).group(generalGroup);
    private final BoolSetting showWarning = boolSetting("Warning", true).group(generalGroup);

    private final BoolSetting showTrajectory = boolSetting("Trajectory Line", true).group(renderGroup);
    private final DoubleSetting lineWidth = doubleSetting("Line Width", 1.5, 0.5, 4.0, 0.25, showTrajectory::getValue).group(renderGroup);
    private final BoolSetting dangerColorMode = boolSetting("Distance Color", true).group(renderGroup);
    private final BoolSetting showLandingMarker = boolSetting("Landing Marker", true).group(renderGroup);
    private final BoolSetting showExplosionSphere = boolSetting("Explosion Sphere", true).group(renderGroup);
    private final BoolSetting showETA = boolSetting("ETA", true).group(renderGroup);
    private final BoolSetting adaptiveText = boolSetting("Adaptive Text", true, showETA::getValue).group(renderGroup);
    private final BoolSetting showDangerBoxes = boolSetting("Danger Boxes", true).group(renderGroup);

    private static final int SPHERE_SEGMENTS = 24;
    private static final float LANDING_MARKER_SIZE = 0.15F;

    private final Supplier<Render2DScheduler> textScheduler = Suppliers.memoize(Render2DScheduler::new);
    private final List<EtaLabel> etaLabels = new ArrayList<>();
    private int warningCounter;

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;
        etaLabels.clear();
        Render3DScheduler scheduler = Render3DScheduler.INSTANCE;
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        double maxDistSq = Mth.square(maxRenderDistance.getValue());

        boolean renderedAnyFireball = false;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(mc.player) > maxDistSq) continue;
            if (!isFireballEntity(entity)) continue;
            if (entity.getDeltaMovement().lengthSqr() < 1.0E-7) continue;

            SimulationResult result = simulate(entity, partialTick);
            if (result == null || result.positions().size() < 2) continue;

            renderedAnyFireball = true;
            Vec3 hitPos = result.hit() != null ? result.hit().getLocation() : null;
            double distance = hitPos != null ? entity.position().distanceTo(hitPos) : 0.0;
            Color color = getDistanceColor(distance);

            drawResult(scheduler, result, color, hitPos);
            if (hitPos != null) checkPlayerWarning(hitPos);
        }

        if (!renderedAnyFireball && handheldMode.getValue()) {
            drawHandheldPrediction(scheduler);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent.Level event) {
        if (nullCheck() || etaLabels.isEmpty()) return;

        Render2DScheduler render = textScheduler.get();
        render.clear();
        Render2DScheduler.LayerHandle layer = render.layer(0);
        Vec3 camera = mc.gameRenderer.mainCamera().position();

        for (EtaLabel label : etaLabels) {
            Vector3f screen = WorldToScreen.calcWorld2Screen(label.pos());
            if (screen == null || !Float.isFinite(screen.x) || !Float.isFinite(screen.y)) continue;

            float scale = 1.0F;
            if (adaptiveText.getValue()) {
                double camDist = camera.distanceTo(label.pos());
                scale = (float) Math.max(1.0, Math.min(6.0, camDist / 8.0));
            }

            float width = render.textMetrics().getWidth(label.text(), scale);
            layer.addText(label.text(), screen.x - width * 0.5F, screen.y, scale, Color.WHITE, StaticFontLoader.defaultFont());
        }
        render.flushAndClear();
    }

    private boolean isFireballEntity(Entity entity) {
        if (entity instanceof Fireball || entity instanceof SmallFireball || entity instanceof DragonFireball) {
            return true;
        }
        return includeWitherSkulls.getValue() && entity instanceof WitherSkull;
    }

    private SimulationResult simulate(Entity entity, float partialTick) {
        if (physicsMode.is(PhysicsMode.LinearRaycast)) {
            return linearRaycast(entity);
        }

        if (!(entity instanceof AbstractHurtingProjectile projectile)) return null;

        ProjectileSimulator simulator = new ProjectileSimulator(mc.level);
        if (!simulator.configureFired(projectile)) return null;

        List<Vec3> positions = new ArrayList<>();
        positions.add(entity.getPosition(partialTick));
        HitResult hit = null;
        int limit = maxSimulatedTicks.getValue();

        for (int i = 0; i < limit; i++) {
            ProjectileSimulator.Step step = simulator.tick();
            positions.add(step.position());
            if (step.hit() != null) {
                hit = step.hit();
                break;
            }
            if (step.stop()) break;
        }

        return new SimulationResult(hit, positions, entity.position(), entity.getDeltaMovement().length());
    }

    private SimulationResult linearRaycast(Entity fireball) {
        Vec3 start = fireball.position();
        Vec3 velocity = fireball.getDeltaMovement();
        if (velocity.lengthSqr() < 1.0E-7) return null;

        Vec3 dir = velocity.normalize();
        Vec3 end = start.add(dir.scale(raycastDistance.getValue()));
        HitResult hit = mc.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, fireball));

        List<Vec3> positions = new ArrayList<>(2);
        positions.add(start);
        if (hit.getType() != HitResult.Type.MISS) {
            positions.add(hit.getLocation());
            return new SimulationResult(hit, positions, start, velocity.length());
        }

        positions.add(end);
        return new SimulationResult(null, positions, start, velocity.length());
    }

    private void drawResult(Render3DScheduler scheduler, SimulationResult result, Color color, Vec3 hitPos) {
        if (showTrajectory.getValue()) {
            List<Vec3> positions = result.positions();
            float width = lineWidth.getValue().floatValue();
            for (int i = 1; i < positions.size(); i++) {
                scheduler.addLine(positions.get(i - 1), positions.get(i), color, width);
            }
        }

        if (hitPos == null) return;

        if (showLandingMarker.getValue()) {
            AABB marker = boxAround(hitPos, LANDING_MARKER_SIZE);
            scheduler.addOutlineBox(marker, color, Math.max(1.0F, lineWidth.getValue().floatValue()));
        }

        if (showExplosionSphere.getValue()) {
            drawSphereWireframe(scheduler, hitPos, explosionRadius.getValue().floatValue(),
                    withAlpha(color, 100), lineWidth.getValue().floatValue());
        }

        if (showDangerBoxes.getValue()) {
            drawDangerBoxes(scheduler, hitPos);
        }

        if (showETA.getValue()) {
            double speed = result.speed();
            if (speed > 1.0E-6) {
                double eta = hitPos.distanceTo(result.origin()) / (speed * 20.0);
                String text = String.format(Locale.US, "%.1fs", eta);
                etaLabels.add(new EtaLabel(hitPos.add(0.0, 1.5, 0.0), text));
            }
        }
    }

    private void drawSphereWireframe(Render3DScheduler scheduler, Vec3 center, float radius, Color color, float width) {
        // 三轴正交圆环近似球体线框
        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i < SPHERE_SEGMENTS; i++) {
                double a0 = Math.PI * 2.0 * i / SPHERE_SEGMENTS;
                double a1 = Math.PI * 2.0 * (i + 1) / SPHERE_SEGMENTS;
                scheduler.addLine(offsetOnSphere(center, radius, axis, a0), offsetOnSphere(center, radius, axis, a1), color, width);
            }
        }
    }

    private static Vec3 offsetOnSphere(Vec3 center, double radius, int axis, double angle) {
        double x = Math.cos(angle) * radius;
        double z = Math.sin(angle) * radius;
        return switch (axis) {
            case 0 -> center.add(x, z, 0.0);   // XY 平面
            case 1 -> center.add(x, 0.0, z);   // XZ 平面
            default -> center.add(0.0, x, z);  // YZ 平面
        };
    }

    private void drawDangerBoxes(Render3DScheduler scheduler, Vec3 hitPos) {
        double r = explosionRadius.getValue();
        BlockPos min = BlockPos.containing(hitPos.x - r, hitPos.y - r, hitPos.z - r);
        BlockPos max = BlockPos.containing(hitPos.x + r, hitPos.y + r, hitPos.z + r);
        Color outline = new Color(255, 0, 0, 220);
        Color fill = new Color(255, 0, 0, 70);
        double rSq = r * r;

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir() || !state.isSolidRender()) continue;

            Vec3 center = Vec3.atCenterOf(pos);
            if (center.distanceToSqr(hitPos) > rSq) continue;

            AABB box = new AABB(pos);
            scheduler.addFilledBox(box, fill);
            scheduler.addOutlineBox(box, outline, 1.0F);
        }
    }

    private void checkPlayerWarning(Vec3 hitPos) {
        if (!showWarning.getValue()) return;

        double dx = Math.abs(mc.player.getX() - hitPos.x);
        double dy = Math.abs(mc.player.getY() - hitPos.y);
        double dz = Math.abs(mc.player.getZ() - hitPos.z);
        double r = explosionRadius.getValue();
        if (dx > r || dy > r || dz > r) return;

        if (++warningCounter % 10 == 0) {
            ChatUtils.addChatMessage(false, "§c[" + getTranslatedName() + "]§7 当前位于烈焰弹爆炸范围内！");
        }
    }

    private void drawHandheldPrediction(Render3DScheduler scheduler) {
        if (!(mc.player.getMainHandItem().getItem() instanceof FireChargeItem)
                && !(mc.player.getOffhandItem().getItem() instanceof FireChargeItem)) {
            return;
        }

        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(raycastDistance.getValue()));
        HitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) return;

        Vec3 hitPos = hit.getLocation();
        Color yellow = new Color(255, 255, 0, 200);
        // 手持射线没有飞行速度语义，speed 传 0 跳过 ETA
        drawResult(scheduler, new SimulationResult(hit, List.of(eye, hitPos), eye, 0.0), yellow, hitPos);
    }

    private Color getDistanceColor(double distance) {
        if (!dangerColorMode.getValue()) return new Color(255, 120, 40, 200);
        if (distance <= 8.0) return new Color(255, 0, 0, 200);
        if (distance >= 48.0) return new Color(0, 255, 0, 200);
        if (distance <= 24.0) {
            float p = (float) ((distance - 8.0) / 16.0);
            return new Color(255, Math.round(255 * p), 0, 200);
        }
        float p = (float) ((distance - 24.0) / 24.0);
        return new Color(Math.round(255 * (1.0F - p)), 255, 0, 200);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static AABB boxAround(Vec3 center, double size) {
        return new AABB(center.x - size, center.y - size, center.z - size,
                center.x + size, center.y + size, center.z + size);
    }

    @Override
    protected void onDisable() {
        warningCounter = 0;
        etaLabels.clear();
    }

    private record SimulationResult(HitResult hit, List<Vec3> positions, Vec3 origin, double speed) {
    }

    private record EtaLabel(Vec3 pos, String text) {
    }

}
