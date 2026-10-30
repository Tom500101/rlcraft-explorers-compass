package com.tom500101.rlcompass.client;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.item.IItemPropertyGetter;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Drives the 32-frame compass model registered as the {@code angle} item property.
 *
 * <p>The frames are just a 0..1 dial: the baked model overrides pick the frame whose 1/32 bucket
 * the returned value falls into. Everything here is pure trigonometry plus a damped follow, so
 * the class stays free of {@code net.minecraft.client.*} and is safe to instantiate from the item
 * constructor on a dedicated server.
 *
 * <p>The target comes from {@link ClientCompassState} rather than item NBT: this mod keeps a single
 * per-client target shared with the HUD, so duplicating it into every compass stack would just
 * create two sources of truth. With no target, or a target in another dimension, the needle idles
 * by sweeping one full turn per 360 ticks.
 */
public class CompassAngleProperty implements IItemPropertyGetter {

    private static final double TICKS_PER_TURN = 360.0D;
    private static final double FOLLOW_STRENGTH = 0.1D;
    private static final double FOLLOW_DAMPING = 0.8D;

    private double rotation;
    private double rotationVelocity;
    private long lastUpdateTick;

    @Override
    public float apply(ItemStack stack, World world, EntityLivingBase entity) {
        if (entity == null && !stack.isOnItemFrame()) {
            return 0.0F;
        }
        boolean onItemFrame = stack.isOnItemFrame();
        Entity holder = onItemFrame ? stack.getItemFrame() : entity;
        if (holder == null) {
            return 0.0F;
        }
        World effectiveWorld = world != null ? world : holder.world;
        if (effectiveWorld == null) {
            return 0.0F;
        }

        double angle = targetAngle(holder, effectiveWorld, onItemFrame);
        // Nothing renders server-side, so skip the easing there and return the raw dial value.
        if (entity != null && !effectiveWorld.isRemote) {
            return (float) angle;
        }
        return (float) wobble(effectiveWorld, angle);
    }

    private double targetAngle(Entity holder, World world, boolean onItemFrame) {
        BlockPos target = ClientCompassState.getPos();
        if (target == null || ClientCompassState.getDimension() != world.provider.getDimension()) {
            return idleSpin(world);
        }
        double yaw = onItemFrame && holder instanceof EntityItemFrame
                ? wrap(((EntityItemFrame) holder).getHorizontalFacing().getHorizontalIndex() * 90.0D)
                : wrap(holder.rotationYaw);
        double dx = target.getX() + 0.5D - holder.posX;
        double dz = target.getZ() + 0.5D - holder.posZ;
        double bearing = Math.atan2(dz, dx) * 180.0D / Math.PI;
        return wrap((yaw - 90.0D - bearing) / 360.0D);
    }

    private double idleSpin(World world) {
        return wrap((double) (world.getTotalWorldTime() % (long) TICKS_PER_TURN) / TICKS_PER_TURN);
    }

    /**
     * Second-order follow so the needle eases to a new bearing instead of snapping when the target
     * arrives or the player turns. Stepping is per world tick, not per render frame, so the motion
     * is frame-rate independent.
     */
    private double wobble(World world, double angle) {
        long now = world.getTotalWorldTime();
        if (now != lastUpdateTick) {
            lastUpdateTick = now;
            double delta = angle - rotation;
            // Take the short way round instead of unwinding across the 0/1 seam.
            delta -= Math.floor(delta + 0.5D);
            rotationVelocity += delta * FOLLOW_STRENGTH;
            rotationVelocity *= FOLLOW_DAMPING;
            rotation = wrap(rotation + rotationVelocity);
        }
        return rotation;
    }

    private double wrap(double value) {
        value %= 1.0D;
        if (value < 0.0D) {
            value += 1.0D;
        }
        return value;
    }
}
