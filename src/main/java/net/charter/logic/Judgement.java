package net.charter.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.mojang.authlib.GameProfile;

import net.charter.CharterMod;
import net.charter.body.Bodies;
import net.charter.config.CharterConfig;
import net.charter.fx.Fx;
import net.charter.registry.ModDamageTypes;
import net.charter.state.CharterState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The judgement sequence. A verdict is never instant: first Charter shows what it is about to do (a golden
 * sigil on the ground, golden chains that seize whoever stands in it), then a beam of light falls and the verdict
 * is carried out. It works on players and on every other living creature alike.
 *
 * <p>There are three ways in: a sigil that chains up everything inside it ({@link #sigilStrike}), a single
 * creature seized by chains ({@link #bindAndJudge}), and the swing of Dusk's Epitaph, where the blade turns into
 * a beam of light that the wielder brings down on the chained creature ({@link #slamJudge}).
 */
public final class Judgement {
	/** Command tag on every creature that is held in chains, so a restart can never leave one frozen. */
	public static final String TAG_HELD = "charter_held";
	/** Command tag on a mob whose AI was switched off by the chains (and has to be switched on again). */
	public static final String TAG_THAW = "charter_thaw";

	private static final List<Sequence> ACTIVE = new ArrayList<>();
	private static final Set<UUID> CONDEMNED = ConcurrentHashMap.newKeySet();

	private Judgement() {
	}

	/** One creature held in the chains of a sequence. */
	private static final class Held {
		final UUID id;
		final String name;
		final boolean player;
		final Vec3d anchor;

		Held(UUID id, String name, boolean player, Vec3d anchor) {
			this.id = id;
			this.name = name;
			this.player = player;
			this.anchor = anchor;
		}
	}

	private static final class Sequence {
		final ServerWorld world;
		final Vec3d center;
		/** More than zero for a sigil that judges everyone standing in it; zero for a single chained target. */
		final double radius;
		final Verdict verdict;
		@Nullable
		final UUID source;
		@Nullable
		final Runnable after;
		final Map<UUID, Held> held = new LinkedHashMap<>();
		final int total;
		int ticksLeft;

		// Dusk's Epitaph: the wielder raises the blade, then brings it down
		boolean slam;
		int chargeTicks;
		int slamTicks;
		@Nullable
		UUID slamTarget;
		@Nullable
		Vec3d wielderAnchor;

		Sequence(ServerWorld world, Vec3d center, double radius, Verdict verdict, @Nullable UUID source, int ticks, @Nullable Runnable after) {
			this.world = world;
			this.center = center;
			this.radius = radius;
			this.verdict = verdict;
			this.source = source;
			this.total = ticks;
			this.ticksLeft = ticks;
			this.after = after;
		}
	}

	/** True while the creature is held in chains awaiting judgement: it cannot be hurt, killed or act. */
	public static boolean isCondemned(UUID id) {
		return CONDEMNED.contains(id);
	}

	/** Everything is let go: used when the server starts or stops. */
	public static void reset() {
		for (Sequence sequence : new ArrayList<>(ACTIVE)) {
			for (Held held : sequence.held.values()) {
				Entity entity = sequence.world.getEntity(held.id);
				if (entity instanceof LivingEntity) {
					release((LivingEntity) entity);
				}
			}
		}
		ACTIVE.clear();
		CONDEMNED.clear();
	}

	/**
	 * A creature that was held when the server went down is let go as soon as it is loaded again, unless a
	 * sequence that is still running holds it.
	 */
	public static void thawOnLoad(LivingEntity entity) {
		if (!entity.getCommandTags().contains(TAG_HELD) || CONDEMNED.contains(entity.getUuid())) {
			return;
		}
		release(entity);
	}

	/** How long the swing of the Epitaph lasts, from the first chain to the beam landing. */
	public static int slamTicks() {
		CharterConfig config = CharterConfig.get();
		return config.epitaphChargeTicks + config.epitaphSlamTicks;
	}

	// ------------------------------------------------------------------ who can be judged

	/** True when this creature must not be chained or judged by a sequence started by {@code source}. */
	private static boolean exempt(@Nullable UUID source, LivingEntity entity, boolean sparePets) {
		if (!entity.isAlive() || entity.isSpectator() || entity instanceof ArmorStandEntity) {
			return true;
		}
		if (CONDEMNED.contains(entity.getUuid()) || Immunity.protects(entity)) {
			return true;
		}
		if (source != null) {
			if (source.equals(entity.getUuid())) {
				return true;
			}
			MinecraftServer server = entity.getServer();
			if (entity instanceof ServerPlayerEntity && server != null && Contracts.protectsCreator(server, source, entity.getUuid())) {
				// the creator of the wielder's contract stands untouched
				return true;
			}
			if (sparePets && entity instanceof Tameable && source.equals(((Tameable) entity).getOwnerUuid())) {
				return true;
			}
		}
		return false;
	}

	/** Every judgeable creature standing inside a sigil, players first. */
	private static List<LivingEntity> inside(Sequence sequence) {
		Vec3d c = sequence.center;
		double r = sequence.radius;
		boolean sparePets = CharterConfig.get().sigilSparesPets;
		UUID source = sequence.source;
		Box box = new Box(c.x - r, c.y - 8.0, c.z - r, c.x + r, c.y + 8.0, c.z + r);
		List<LivingEntity> found = sequence.world.getEntitiesByClass(LivingEntity.class, box, entity -> {
			double dx = entity.getX() - c.x;
			double dz = entity.getZ() - c.z;
			return dx * dx + dz * dz <= r * r && !exempt(source, entity, sparePets);
		});
		found.sort((a, b) -> Boolean.compare(b instanceof ServerPlayerEntity, a instanceof ServerPlayerEntity));
		return found;
	}

	// ------------------------------------------------------------------ holding creatures

	private static void hold(Sequence sequence, LivingEntity entity, int ticks) {
		Held held = new Held(entity.getUuid(), entity.getName().getString(), entity instanceof ServerPlayerEntity, entity.getPos());
		sequence.held.put(held.id, held);
		CONDEMNED.add(held.id);

		entity.addCommandTag(TAG_HELD);
		if (entity instanceof MobEntity) {
			MobEntity mob = (MobEntity) entity;
			if (!mob.isAiDisabled()) {
				mob.setAiDisabled(true);
				mob.addCommandTag(TAG_THAW);
			}
			mob.getNavigation().stop();
			mob.setTarget(null);
		}
		Fx.chains(sequence.world, entity, ticks + 15);
		Fx.sound(sequence.world, entity.getPos(), SoundEvents.BLOCK_CHAIN_PLACE, 2.5f, 0.6f);
	}

	private static void release(LivingEntity entity) {
		CONDEMNED.remove(entity.getUuid());
		if (entity instanceof MobEntity && entity.getCommandTags().contains(TAG_THAW)) {
			((MobEntity) entity).setAiDisabled(false);
		}
		entity.removeScoreboardTag(TAG_THAW);
		entity.removeScoreboardTag(TAG_HELD);
	}

	/** Keeps a held creature exactly where the chains caught it. */
	private static void holdTick(Sequence sequence, Held held) {
		Entity entity = sequence.world.getEntity(held.id);
		if (!(entity instanceof LivingEntity) || entity.isRemoved()) {
			return;
		}
		Vec3d anchor = held.anchor;
		entity.setVelocity(0.0, Math.min(0.0, entity.getVelocity().y), 0.0);
		entity.velocityModified = true;
		double dx = entity.getX() - anchor.x;
		double dz = entity.getZ() - anchor.z;
		if (dx * dx + dz * dz > 0.01) {
			if (entity instanceof ServerPlayerEntity) {
				((ServerPlayerEntity) entity).networkHandler.requestTeleport(anchor.x, entity.getY(), anchor.z, entity.getYaw(), entity.getPitch());
			} else {
				entity.requestTeleport(anchor.x, entity.getY(), anchor.z);
			}
		}
		if (sequence.ticksLeft % 5 == 0) {
			Fx.sparks(sequence.world, entity.getPos().add(0.0, entity.getHeight() * 0.5, 0.0), 6, Math.max(0.3, entity.getWidth() * 0.5));
		}
	}

	// ------------------------------------------------------------------ starting a sequence

	/**
	 * Lesser Divinity and the Sigil Staff: a golden sigil is drawn at {@code center}. Everything standing inside it
	 * (except the wielder, the wielder's pets and the creator of the wielder's contract) is seized by golden chains;
	 * when the telegraph ends, a beam falls and everyone inside is judged. {@code afterStrike} runs once the
	 * strike has landed. A sigil in {@link Verdict#BIND} mode only holds its captives for a while and lets them go.
	 */
	public static void sigilStrike(ServerWorld world, Vec3d center, double radius, Verdict verdict, @Nullable ServerPlayerEntity source, @Nullable Runnable afterStrike) {
		CharterConfig config = CharterConfig.get();
		int ticks = verdict == Verdict.BIND ? config.bindHoldTicks : config.sigilTelegraphTicks;
		Sequence sequence = new Sequence(world, center, radius, verdict, source == null ? null : source.getUuid(), ticks, afterStrike);
		ACTIVE.add(sequence);
		Fx.sigil(world, center, radius, ticks + 30);
		Fx.sound(world, center, SoundEvents.BLOCK_BEACON_ACTIVATE, 4.0f, 0.55f);
		Fx.sound(world, center, SoundEvents.BLOCK_CHAIN_PLACE, 3.0f, 0.5f);
		chainUp(sequence);
	}

	/** Seizes everything inside the sigil that is not held yet, up to the configured limit. */
	private static void chainUp(Sequence sequence) {
		int limit = CharterConfig.get().sigilMaxChained;
		if (sequence.held.size() >= limit) {
			return;
		}
		for (LivingEntity entity : inside(sequence)) {
			if (sequence.held.size() >= limit) {
				break;
			}
			if (!sequence.held.containsKey(entity.getUuid())) {
				hold(sequence, entity, sequence.ticksLeft);
			}
		}
	}

	/**
	 * Golden chains seize the target and hold it in place until the strike lands. Does nothing if the target is
	 * already condemned, is protected from Charter, or is the creator of the contract the source signed.
	 */
	public static void bindAndJudge(LivingEntity target, Verdict verdict, @Nullable ServerPlayerEntity source) {
		bindAndJudge(target, verdict, source, false);
	}

	/** As {@link #bindAndJudge(LivingEntity, Verdict, ServerPlayerEntity)}; {@code force} overrides every protection. */
	public static void bindAndJudge(LivingEntity target, Verdict verdict, @Nullable ServerPlayerEntity source, boolean force) {
		Sequence sequence = begin(target, verdict, source, force, CharterConfig.get().chainTelegraphTicks);
		if (sequence != null) {
			Fx.sound(sequence.world, target.getPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, 3.0f, 0.8f);
		}
	}

	/**
	 * Dusk's Epitaph: the target is seized by chains, the wielder raises the blade, which turns into a beam of
	 * light, and brings it down on the target. Returns false when nothing was started.
	 */
	public static boolean slamJudge(LivingEntity target, Verdict verdict, ServerPlayerEntity wielder) {
		CharterConfig config = CharterConfig.get();
		Sequence sequence = begin(target, verdict, wielder, false, slamTicks());
		if (sequence == null) {
			return false;
		}
		sequence.slam = true;
		sequence.chargeTicks = config.epitaphChargeTicks;
		sequence.slamTicks = config.epitaphSlamTicks;
		sequence.slamTarget = target.getUuid();
		sequence.wielderAnchor = wielder.getPos();
		Fx.blade(sequence.world, target.getPos(), wielder, sequence.chargeTicks, sequence.slamTicks, target.getHeight(), sequence.total + 20);
		Fx.sound(sequence.world, wielder.getPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, 3.0f, 1.4f);
		Fx.sound(sequence.world, target.getPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, 3.0f, 0.8f);
		faceTarget(wielder, target, true);
		return true;
	}

	@Nullable
	private static Sequence begin(LivingEntity target, Verdict verdict, @Nullable ServerPlayerEntity source, boolean force, int ticks) {
		if (target.getWorld().isClient || !(target.getWorld() instanceof ServerWorld)) {
			return null;
		}
		ServerWorld world = (ServerWorld) target.getWorld();
		UUID sourceId = source == null ? null : source.getUuid();
		if (!force) {
			if (CONDEMNED.contains(target.getUuid()) || !target.isAlive() || target.isSpectator() || target instanceof ArmorStandEntity) {
				return null;
			}
			if (Immunity.protects(target)) {
				if (source != null) {
					Bodies.nag(source, target.getName().getString() + " is beyond the reach of Charter.");
				}
				return null;
			}
			MinecraftServer server = target.getServer();
			if (source != null && server != null && target instanceof ServerPlayerEntity && Contracts.protectsCreator(server, source.getUuid(), target.getUuid())) {
				// whoever signed a contract cannot turn Charter against the one who drew it up
				Bodies.nag(source, "You signed a contract with " + target.getName().getString() + ". You cannot harm them.");
				return null;
			}
		} else if (CONDEMNED.contains(target.getUuid())) {
			return null;
		}
		Sequence sequence = new Sequence(world, target.getPos(), 0.0, verdict, sourceId, ticks, null);
		ACTIVE.add(sequence);
		hold(sequence, target, ticks);
		Fx.sigil(world, target.getPos(), Math.max(2.6, target.getWidth() * 1.7), ticks + 30);
		return sequence;
	}

	/** Turns the wielder toward the target (and, when {@code snap}, immediately). */
	private static void faceTarget(ServerPlayerEntity wielder, Entity target, boolean snap) {
		double dx = target.getX() - wielder.getX();
		double dz = target.getZ() - wielder.getZ();
		double flat = Math.sqrt(dx * dx + dz * dz);
		if (flat < 0.05) {
			return;
		}
		float yaw = (float) (MathHelper.atan2(dz, dx) * 57.2957763671875) - 90.0f;
		double dy = target.getY() + target.getHeight() * 0.5 - wielder.getEyeY();
		float pitch = MathHelper.clamp((float) (-(MathHelper.atan2(dy, flat) * 57.2957763671875)), -50.0f, 50.0f);
		if (snap || Math.abs(MathHelper.wrapDegrees(wielder.getYaw() - yaw)) > 8.0f) {
			wielder.networkHandler.requestTeleport(wielder.getX(), wielder.getY(), wielder.getZ(), yaw, pitch);
		}
	}

	// ------------------------------------------------------------------ ticking

	public static void tick(MinecraftServer server) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		for (Sequence sequence : new ArrayList<>(ACTIVE)) {
			sequence.ticksLeft--;
			if (sequence.ticksLeft <= 0) {
				ACTIVE.remove(sequence);
				strike(server, sequence);
			} else {
				telegraph(server, sequence);
			}
		}
	}

	private static void telegraph(MinecraftServer server, Sequence sequence) {
		ServerWorld world = sequence.world;
		if (sequence.radius > 0.0) {
			if (sequence.ticksLeft % 10 == 0) {
				Fx.ring(world, sequence.center, sequence.radius, 28);
				float progress = 1.0f - (float) sequence.ticksLeft / (float) Math.max(1, sequence.total);
				Fx.sound(world, sequence.center, SoundEvents.BLOCK_BEACON_AMBIENT, 2.0f, 0.5f + 0.5f * progress);
			}
			if (sequence.ticksLeft % 4 == 0) {
				world.spawnParticles(ParticleTypes.END_ROD, sequence.center.x, sequence.center.y + 0.2, sequence.center.z, 2, sequence.radius * 0.5, 0.1, sequence.radius * 0.5, 0.01);
			}
			if (sequence.ticksLeft % 5 == 0) {
				chainUp(sequence);
			}
		}
		for (Held held : sequence.held.values()) {
			holdTick(sequence, held);
		}
		if (sequence.ticksLeft % 12 == 0) {
			Fx.sound(world, sequence.center, SoundEvents.ITEM_ARMOR_EQUIP_CHAIN, 2.0f, 0.6f);
		}
		if (sequence.slam) {
			tickSwing(server, sequence);
		}
	}

	/** The wielder stands where they are, facing the target, while the blade rises and falls. */
	private static void tickSwing(MinecraftServer server, Sequence sequence) {
		if (sequence.source == null || sequence.slamTarget == null) {
			return;
		}
		int elapsed = sequence.total - sequence.ticksLeft;
		if (elapsed % 6 == 0 && elapsed < sequence.chargeTicks) {
			float rise = (float) elapsed / (float) Math.max(1, sequence.chargeTicks);
			Fx.sound(sequence.world, sequence.center, SoundEvents.BLOCK_BEACON_AMBIENT, 2.5f, 0.7f + 0.9f * rise);
		}
		if (elapsed == sequence.chargeTicks) {
			Fx.sound(sequence.world, sequence.center, SoundEvents.ITEM_TRIDENT_RIPTIDE_3, 4.0f, 0.55f);
		}
		ServerPlayerEntity wielder = server.getPlayerManager().getPlayer(sequence.source);
		Entity target = sequence.world.getEntity(sequence.slamTarget);
		if (wielder == null || wielder.isRemoved() || target == null || sequence.wielderAnchor == null || !CharterConfig.get().epitaphHoldsWielder) {
			return;
		}
		Vec3d anchor = sequence.wielderAnchor;
		wielder.setVelocity(0.0, Math.min(0.0, wielder.getVelocity().y), 0.0);
		wielder.velocityModified = true;
		double dx = wielder.getX() - anchor.x;
		double dz = wielder.getZ() - anchor.z;
		if (dx * dx + dz * dz > 0.09) {
			wielder.networkHandler.requestTeleport(anchor.x, wielder.getY(), anchor.z, wielder.getYaw(), wielder.getPitch());
		}
		faceTarget(wielder, target, false);
	}

	private static void strike(MinecraftServer server, Sequence sequence) {
		CharterConfig config = CharterConfig.get();
		ServerWorld world = sequence.world;
		Vec3d at = sequence.center;
		double beamRadius = sequence.radius > 0.0 ? Math.max(1.0, sequence.radius * 0.45) : 1.0;

		// the captives are let go first: whatever happens next, nobody stays frozen
		List<LivingEntity> victims = new ArrayList<>();
		List<Held> gone = new ArrayList<>();
		for (Held held : sequence.held.values()) {
			Entity entity = world.getEntity(held.id);
			if (entity instanceof LivingEntity && !entity.isRemoved()) {
				release((LivingEntity) entity);
				victims.add((LivingEntity) entity);
				if (sequence.radius <= 0.0) {
					at = entity.getPos();
					beamRadius = Math.max(1.0, entity.getWidth() * 0.9);
				}
			} else {
				CONDEMNED.remove(held.id);
				if (held.player) {
					gone.add(held);
				}
			}
		}

		if (sequence.verdict == Verdict.BIND) {
			Fx.sound(world, at, SoundEvents.BLOCK_CHAIN_BREAK, 4.0f, 0.6f);
			Fx.sound(world, at, SoundEvents.BLOCK_BEACON_DEACTIVATE, 3.0f, 0.7f);
			if (sequence.after != null) {
				sequence.after.run();
			}
			return;
		}

		Fx.beam(world, at, beamRadius, config.strikeBeamTicks);
		Fx.sound(world, at, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 10.0f, 0.7f);
		Fx.sound(world, at, SoundEvents.ITEM_TRIDENT_THUNDER, 8.0f, 0.6f);
		Fx.sound(world, at, SoundEvents.BLOCK_BEACON_DEACTIVATE, 4.0f, 0.5f);
		Fx.sparks(world, at.add(0.0, 1.0, 0.0), 120, 1.2);

		if (sequence.radius > 0.0) {
			// anyone who slipped in too late to be chained (or did not fit in the limit) is judged all the same
			for (LivingEntity entity : inside(sequence)) {
				if (!victims.contains(entity)) {
					victims.add(entity);
				}
			}
		}

		ServerPlayerEntity attacker = sequence.source == null ? null : server.getPlayerManager().getPlayer(sequence.source);
		for (LivingEntity victim : victims) {
			deliverTo(server, victim, sequence.verdict, attacker);
		}
		for (Held held : gone) {
			deliver(server, held.id, held.name, sequence.verdict);
		}
		if (sequence.after != null) {
			sequence.after.run();
		}
	}

	// ------------------------------------------------------------------ carrying out a verdict

	/**
	 * Carries a verdict out on a living creature at once, with no telegraph. Players are banned, kicked, exiled or
	 * crushed; every other creature is killed by a ban or a kill and simply vanishes when it is kicked or exiled.
	 */
	public static void deliverTo(MinecraftServer server, LivingEntity entity, Verdict verdict, @Nullable ServerPlayerEntity attacker) {
		if (entity instanceof ServerPlayerEntity) {
			deliver(server, entity.getUuid(), entity.getName().getString(), verdict, attacker);
			return;
		}
		switch (verdict) {
			case BAN:
			case KILL:
				killCreature(entity, attacker);
				break;
			case KICK:
			case EXILE:
				vanish(entity);
				break;
			default:
				break;
		}
	}

	private static void killCreature(LivingEntity entity, @Nullable ServerPlayerEntity attacker) {
		if (!(entity.getWorld() instanceof ServerWorld)) {
			return;
		}
		ServerWorld world = (ServerWorld) entity.getWorld();
		DamageSource source = attacker == null
				? ModDamageTypes.source(world, ModDamageTypes.JUDGEMENT)
				: ModDamageTypes.source(world, ModDamageTypes.JUDGEMENT, attacker);
		entity.damage(source, 1.0E6f);
		if (entity.isAlive()) {
			// something refused the damage (a boss that cannot be hurt directly, say): the verdict stands all the same
			entity.setHealth(0.0f);
			entity.onDeath(source);
		}
	}

	private static void vanish(LivingEntity entity) {
		if (!(entity.getWorld() instanceof ServerWorld)) {
			return;
		}
		ServerWorld world = (ServerWorld) entity.getWorld();
		Fx.sparks(world, entity.getPos().add(0.0, entity.getHeight() * 0.5, 0.0), 40, Math.max(0.3, entity.getWidth() * 0.6));
		Fx.sound(world, entity.getPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.6f);
		entity.discard();
	}

	/**
	 * Carries out a verdict on a player at once, with no telegraph. A ban that is switched off in the config, or
	 * aimed at an exempt player (the host of a local world), turns into the configured fallback verdict instead.
	 */
	public static void deliver(MinecraftServer server, UUID id, String fallbackName, Verdict verdict) {
		deliver(server, id, fallbackName, verdict, null);
	}

	public static void deliver(MinecraftServer server, UUID id, String fallbackName, Verdict verdict, @Nullable ServerPlayerEntity attacker) {
		CharterConfig config = CharterConfig.get();
		GameProfile profile = Banisher.profileOf(server, id, fallbackName);
		ServerPlayerEntity online = server.getPlayerManager().getPlayer(id);

		Verdict actual = verdict;
		if (actual == Verdict.BAN && (!config.permanentBans || Banisher.isExempt(server, profile))) {
			actual = "KICK".equals(config.fallbackVerdict) ? Verdict.KICK : Verdict.EXILE;
		}
		switch (actual) {
			case BAN:
				Banisher.banish(server, profile);
				break;
			case KICK:
				Banisher.kick(server, profile);
				break;
			case EXILE:
				if (online != null) {
					ExileManager.exile(online);
				} else {
					CharterState.get(server).setPendingExile(id, true);
				}
				break;
			case KILL:
				if (online != null) {
					ServerWorld world = online.getServerWorld();
					DamageSource source = attacker == null || attacker == online
							? ModDamageTypes.source(world, ModDamageTypes.JUDGEMENT)
							: ModDamageTypes.source(world, ModDamageTypes.JUDGEMENT, attacker);
					online.damage(source, 1000.0f);
				}
				break;
			case BIND:
				break;
			default:
				CharterMod.LOGGER.warn("Unknown verdict {}", actual);
				break;
		}
	}
}
