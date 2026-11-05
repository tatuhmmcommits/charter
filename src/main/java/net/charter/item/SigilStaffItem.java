package net.charter.item;

import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import net.charter.config.CharterConfig;
import net.charter.logic.Contracts;
import net.charter.logic.Judgement;
import net.charter.logic.Targeting;
import net.charter.logic.Verdict;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * The Sigil Staff: a lesser tool of Charter that never runs out and never touches the land. It draws a small
 * sigil where its wielder looks; everything inside is chained up and, depending on the mode, held for a while
 * and let go (bind), kicked, banned or exiled. Crouching and using it changes the mode.
 */
public class SigilStaffItem extends Item implements CharterWeapon {
	private static final String MODE = "Mode";
	private static final Verdict[] MODES = {Verdict.BIND, Verdict.KICK, Verdict.BAN, Verdict.EXILE};

	public SigilStaffItem(Settings settings) {
		super(settings);
	}

	/** The verdict this staff is set to. */
	public static Verdict modeOf(ItemStack stack) {
		NbtCompound nbt = stack.getNbt();
		int index = nbt == null ? 0 : nbt.getInt(MODE);
		return MODES[Math.floorMod(index, MODES.length)];
	}

	private static Text modeName(Verdict verdict) {
		return Text.translatable("tooltip.charter.sigil_staff.mode." + verdict.name().toLowerCase(Locale.ROOT));
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return modeOf(stack) == Verdict.BAN || modeOf(stack) == Verdict.EXILE;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient || !(user instanceof ServerPlayerEntity)) {
			return TypedActionResult.pass(stack);
		}
		ServerPlayerEntity player = (ServerPlayerEntity) user;
		MinecraftServer server = player.getServer();
		if (server == null) {
			return TypedActionResult.pass(stack);
		}
		ServerWorld serverWorld = player.getServerWorld();

		if (player.isSneaking()) {
			int next = Math.floorMod(stack.getOrCreateNbt().getInt(MODE) + 1, MODES.length);
			stack.getOrCreateNbt().putInt(MODE, next);
			player.sendMessage(Text.translatable("message.charter.sigil_staff.mode", modeName(MODES[next])).formatted(Formatting.GOLD), true);
			serverWorld.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_CHAIN_PLACE, player.getSoundCategory(), 1.0f, 1.2f);
			return TypedActionResult.success(stack);
		}

		CharterConfig config = CharterConfig.get();
		if (config.weaponsRequireContract && !Contracts.isBound(server, player.getUuid())) {
			player.sendMessage(Text.literal("The Sigil Staff does not answer to you.").formatted(Formatting.GRAY, Formatting.ITALIC), true);
			return TypedActionResult.fail(stack);
		}
		Vec3d target = Targeting.aim(player, config.sigilStaffRange);
		if (target == null) {
			player.sendMessage(Text.literal("Nothing within Charter's sight.").formatted(Formatting.GRAY, Formatting.ITALIC), true);
			return TypedActionResult.fail(stack);
		}

		Judgement.sigilStrike(serverWorld, target, config.sigilStaffRadius, modeOf(stack), player, null);
		if (config.sigilStaffCooldownTicks > 0) {
			player.getItemCooldownManager().set(this, config.sigilStaffCooldownTicks);
		}
		return TypedActionResult.success(stack);
	}

	@Override
	public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.translatable("tooltip.charter.sigil_staff.current", modeName(modeOf(stack))).formatted(Formatting.GOLD));
		tooltip.add(Text.translatable("tooltip.charter.sigil_staff.1").formatted(Formatting.GRAY));
		tooltip.add(Text.translatable("tooltip.charter.sigil_staff.2").formatted(Formatting.GRAY));
		tooltip.add(Text.translatable("tooltip.charter.sigil_staff.3").formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
	}
}
