package net.charter.client;

import java.util.HashMap;
import java.util.Map;

import net.charter.fx.BladePath;
import net.charter.fx.CharterFxEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * What the client knows about the swings of Dusk's Epitaph that are going on: for every wielder, when the swing
 * began and at what angle the blade will land. The effect entity reports in once per tick; the player model reads
 * the angle every frame, so the arms rise and fall exactly with the beam of light that {@link CharterFxRenderer}
 * draws (both ask {@link BladePath}). Only ever touched from the client thread.
 */
public final class BladeAnim {
	private static final class State {
		long start;
		int charge;
		int slam;
		float target;
	}

	private static final Map<Integer, State> STATES = new HashMap<>();

	private BladeAnim() {
	}

	/** Called every client tick by each blade effect that is alive. */
	public static void report(CharterFxEntity fx) {
		Entity wielder = fx.getWorld().getEntityById(fx.getSource());
		if (wielder == null) {
			return;
		}
		if (STATES.size() > 32) {
			STATES.clear();
		}
		State state = STATES.computeIfAbsent(wielder.getId(), id -> new State());
		state.start = (long) fx.getStart();
		state.charge = fx.getArm();
		state.slam = fx.getShrink();

		// the arms point from the shoulder at the middle of the target
		Vec3d shoulder = wielder.getPos().add(0.0, (double) BladePath.SHOULDER, 0.0);
		Vec3d aim = fx.getPos().add(0.0, (double) (fx.getSize() * 0.5F), 0.0);
		double dx = aim.x - shoulder.x;
		double dz = aim.z - shoulder.z;
		state.target = (float) Math.atan2(Math.sqrt(dx * dx + dz * dz), aim.y - shoulder.y);
	}

	public static void clear() {
		STATES.clear();
	}

	private static float ageOf(LivingEntity entity, State state) {
		float tickDelta = MinecraftClient.getInstance().getTickDelta();
		return (float) (entity.getWorld().getTime() - state.start) + tickDelta;
	}

	/** The angle of the blade held by this entity, from straight up, or NaN when it is not swinging. */
	public static float phi(LivingEntity entity) {
		State state = STATES.get(entity.getId());
		if (state == null) {
			return Float.NaN;
		}
		float age = ageOf(entity, state);
		if (age < 0.0F || age > BladePath.finish(state.charge, state.slam) + 1.0F) {
			STATES.remove(entity.getId());
			return Float.NaN;
		}
		return BladePath.phi(age, state.charge, state.slam, state.target);
	}

	/** True while the beam of light replaces the sword in this entity's hands. */
	public static boolean hidesSword(LivingEntity entity) {
		State state = STATES.get(entity.getId());
		return state != null && BladePath.hidesSword(ageOf(entity, state), state.charge, state.slam);
	}

	/** Puts both arms on the blade, whatever angle it is at; called after the vanilla pose has been computed. */
	public static void pose(ModelPart right, ModelPart left, float phi) {
		float pitch = phi - (float) Math.PI;
		// the hands meet on the grip as long as the arms reach forward; held straight up they hardly need to
		float reach = Math.max(0.5F, MathHelper.abs(MathHelper.sin(pitch)));
		float inward = Math.min(0.5F, (float) Math.asin((double) Math.min(1.0F, 0.5F / reach)));
		right.pitch = pitch;
		right.yaw = -inward;
		right.roll = 0.0F;
		left.pitch = pitch;
		left.yaw = inward;
		left.roll = 0.0F;
	}
}
