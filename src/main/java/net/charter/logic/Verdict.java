package net.charter.logic;

/**
 * What Charter decides to do with someone it has judged. Creatures that are not players cannot be banned or
 * exiled: BAN and KILL kill them, KICK and EXILE make them vanish, and BIND only holds them.
 */
public enum Verdict {
	/** Permanent ban: the player is disconnected and can never join again. */
	BAN,
	/** The player is thrown millions of blocks away, unable to die. */
	EXILE,
	/** The player is only disconnected (no ban list entry). */
	KICK,
	/** The player is crushed to death (and respawns normally). */
	KILL,
	/** The target is only held in golden chains for a while and then let go: nothing else happens. */
	BIND
}
