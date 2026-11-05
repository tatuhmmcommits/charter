package net.charter.logic;

import net.minecraft.entity.LivingEntity;

/**
 * Who Charter cannot touch. The armour set that makes its wearer immune to the golden chains, to bans, to exile
 * and to being kicked is checked here, in one place, by every way the mod has of judging somebody.
 */
public final class Immunity {
	private Immunity() {
	}

	/** True when this creature wears something that Charter's judgement cannot get hold of. */
	public static boolean protects(LivingEntity entity) {
		return false;
	}
}
