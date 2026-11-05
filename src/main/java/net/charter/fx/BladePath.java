package net.charter.fx;

/**
 * The motion of the blade of Dusk's Epitaph, as pure arithmetic shared by everything that draws it: the beam of
 * light itself and the arms of the wielder.
 *
 * <p>The blade is described by one angle, {@code phi}, measured in radians from straight up, positive toward the
 * target. The wielder's arms always point along the blade, so the arm pitch of the player model is simply
 * {@code phi - PI} (zero hangs down, minus PI points straight up).
 *
 * <p>The swing has four parts. In the first {@link #RISE_TICKS} ticks the blade leaves the two-handed hold and
 * rises over the wielder's head, turning into a beam of light as it goes. It then leans further back and
 * trembles until {@code charge} ticks have passed. The slam takes {@code slam} ticks, speeding up all the way
 * down to the target. After the impact the blade is held there for a moment, fades, and the arms come back down.
 */
public final class BladePath {
	/** Where the arms are before the swing: the two-handed hold of the Epitaph (see the client class TwoHanded). */
	public static final float REST = 2.05F;
	/** Ticks the blade needs to rise from the hold to over the head. */
	public static final float RISE_TICKS = 9.0F;
	/** How far the blade leans back over the head (a negative angle leans away from the target). */
	public static final float BACK = -0.30F;
	/** Extra lean during the wind-up. */
	public static final float WIND_UP = 0.14F;
	/** Ticks after the impact before the arms come down again. */
	public static final float HOLD_TICKS = 8.0F;
	/** Ticks the arms take to come down. */
	public static final float RETURN_TICKS = 10.0F;

	/** Height of the shoulders above the feet of a standing player. */
	public static final float SHOULDER = 1.375F;
	/** Distance from the shoulder to the hands when the arms are straight. */
	public static final float GRIP = 0.70F;
	/** The longest the beam of light gets. */
	public static final float MAX_LENGTH = 34.0F;
	/** Length of the blade before it turns into light: the length of the sword. */
	public static final float SWORD_LENGTH = 1.9F;

	private BladePath() {
	}

	private static float clamp01(float value) {
		return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
	}

	private static float smooth(float t) {
		return t * t * (3.0F - 2.0F * t);
	}

	private static float lerp(float from, float to, float t) {
		return from + (to - from) * t;
	}

	/** The age at which the beam lands. */
	public static float impact(int charge, int slam) {
		return (float) (charge + slam);
	}

	/** The age at which the arms are back where they started and the animation is over. */
	public static float finish(int charge, int slam) {
		return impact(charge, slam) + HOLD_TICKS + RETURN_TICKS;
	}

	/**
	 * The angle of the blade, from straight up, at the given age.
	 *
	 * @param target the angle of the blade at the moment of impact (it points at the target)
	 */
	public static float phi(float age, int charge, int slam, float target) {
		float impact = impact(charge, slam);
		float pull = BACK - WIND_UP;
		if (age < (float) charge) {
			float rise = smooth(clamp01(age / RISE_TICKS));
			float windup = clamp01((age - RISE_TICKS) / Math.max(1.0F, (float) charge - RISE_TICKS));
			float tremble = (float) Math.sin((double) (age * 1.7F)) * 0.025F * windup;
			return lerp(REST, BACK - WIND_UP * windup, rise) + tremble;
		}
		if (age < impact) {
			float s = clamp01((age - (float) charge) / (float) Math.max(1, slam));
			return lerp(pull, target, s * s);
		}
		float back = clamp01((age - impact - HOLD_TICKS) / RETURN_TICKS);
		return lerp(target, REST, smooth(back));
	}

	/** How long the beam is at the given age: the sword stretches into the light in the first few ticks. */
	public static float length(float age) {
		float t = clamp01(age / 8.0F);
		float eased = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
		return lerp(SWORD_LENGTH, MAX_LENGTH, eased);
	}

	/** How strongly the beam shines: it brightens during the wind-up, flares on impact and fades after it. */
	public static float glow(float age, int charge, int slam) {
		float impact = impact(charge, slam);
		if (age < impact) {
			return 0.55F + 0.45F * clamp01(age / (float) Math.max(1, charge));
		}
		float since = age - impact;
		float flare = 1.0F + 0.8F * clamp01(1.0F - since / 5.0F);
		return flare * clamp01(1.0F - since / (HOLD_TICKS + 2.0F));
	}

	/** True while the beam replaces the sword in the wielder's hands. */
	public static boolean hidesSword(float age, int charge, int slam) {
		return age >= 0.0F && age < impact(charge, slam) + HOLD_TICKS;
	}
}
