package com.aresstudio.treefell;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * TreeFell
 * When a block belonging to a tree trunk (tag minecraft:logs) is broken, the entire
 * connected log structure is felled instantly — but only if the cluster is plausibly
 * a real tree (nearby leaves + limited horizontal spread). This prevents demolishing
 * log cabins or wooden structures built by the player.
 * Leaves are never touched: they remain and decay naturally per vanilla rules.
 */
public class TreeFell implements ModInitializer {

	public static final String MOD_ID = "treefell";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Maximum number of log blocks that can be felled in a single operation (anti-lag / anti-griefing).
	private static final int MAX_LOGS = 512;

	// --- "Not a real tree" protection (anti-griefing on wooden builds) ---

	// Radius (in blocks) within which we search for leaves around each log in the cluster.
	// Set to 4 to cover cherry trees, whose leaves are far from the lower branches.
	private static final int LEAF_SEARCH_RADIUS = 4;

	// Minimum fraction of logs in the cluster that must have at least one leaf block
	// within LEAF_SEARCH_RADIUS for the cluster to be considered a real tree.
	private static final double MIN_LEAF_COVERAGE_RATIO = 0.25;

	// If the log cluster spans more than this many blocks horizontally (X or Z axis),
	// it is treated as a probable player-built structure rather than a tree trunk.
	// Set to 12 to cover cherry trees, whose branches extend up to 5 blocks from the
	// central trunk (total horizontal span ~10 blocks).
	private static final int MAX_HORIZONTAL_SPREAD = 12;

	// 26-direction neighbourhood (3x3x3 minus center) used for the BFS expansion.
	// Covers thick trunks (2x2) and slightly irregular natural tree shapes.
	private static final int[][] OFFSETS = buildOffsets();

	// Candidate positions where a player may have just placed a log block.
	// UseBlockCallback fires BEFORE vanilla processes the placement, so we cannot
	// read the resulting block state immediately. We queue the position here and
	// verify it at END_LEVEL_TICK, once vanilla has finished placing the block.
	private final ConcurrentLinkedQueue<CandidatePlacement> pendingPlacementChecks = new ConcurrentLinkedQueue<>();

	private record CandidatePlacement(ServerLevel world, BlockPos pos) {
	}

	@Override
	public void onInitialize() {
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			// Server-side only: avoids client/server desync.
			if (world.isClientSide()) {
				return;
			}
			if (!(world instanceof ServerLevel serverLevel)) {
				return;
			}

			// Only trigger if the broken block was actually a log (vanilla tag minecraft:logs).
			if (!state.is(BlockTags.LOGS)) {
				return;
			}

			// The block has been broken: remove it from the player-placed registry if present
			// (no need to keep tracking a position that no longer has a block).
			PlacedLogTracker.get(serverLevel).unmark(pos);

			Block logBlock = state.getBlock();
			fellTree(serverLevel, pos, logBlock, player == null ? ItemStack.EMPTY : player.getMainHandItem());
		});

		// Track logs placed manually by the player. UseBlockCallback fires BEFORE vanilla
		// processes the placement, so we only queue the candidate position here; the actual
		// block-state check happens at END_LEVEL_TICK (see below).
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClientSide()) {
				return InteractionResult.PASS;
			}
			if (!(world instanceof ServerLevel serverLevel)) {
				return InteractionResult.PASS;
			}
			if (hand != InteractionHand.MAIN_HAND) {
				return InteractionResult.PASS;
			}

			ItemStack heldStack = player.getMainHandItem();
			if (heldStack.isEmpty()) {
				return InteractionResult.PASS;
			}

			// Only proceed if the held item is a BlockItem whose block has the LOGS tag,
			// to avoid unnecessary work on every right-click in the world.
			if (!(heldStack.getItem() instanceof BlockItem blockItem)) {
				return InteractionResult.PASS;
			}
			if (!blockItem.getBlock().defaultBlockState().is(BlockTags.LOGS)) {
				return InteractionResult.PASS;
			}

			BlockPos targetPos = hitResult.getBlockPos().relative(hitResult.getDirection());
			pendingPlacementChecks.add(new CandidatePlacement(serverLevel, targetPos));

			return InteractionResult.PASS;
		});

		// At the end of each world tick, vanilla placement has already completed.
		// Verify each queued candidate position: if it now contains a log block,
		// mark it in the persistent registry as player-placed.
		ServerTickEvents.END_LEVEL_TICK.register(world -> {
			CandidatePlacement candidate;
			while ((candidate = pendingPlacementChecks.poll()) != null) {
				if (candidate.world() != world) {
					// Belongs to a different level; put it back for the correct tick.
					pendingPlacementChecks.add(candidate);
					continue;
				}
				BlockState resultState = candidate.world().getBlockState(candidate.pos());
				if (resultState.is(BlockTags.LOGS)) {
					PlacedLogTracker.get(candidate.world()).markPlaced(candidate.pos());
				}
			}
		});

		LOGGER.info("[TreeFell] Initialized. Max logs per tree-fell: {}, leaf-coverage check: {}%, max horizontal spread: {}", MAX_LOGS, (int) (MIN_LEAF_COVERAGE_RATIO * 100), MAX_HORIZONTAL_SPREAD);
	}

	/**
	 * Runs a BFS from the already-broken block, expanding through all connected log blocks
	 * (tag minecraft:logs), mangrove roots, and muddy mangrove roots — EXCLUDING any blocks
	 * marked as player-placed in PlacedLogTracker. Player-placed logs act as hard boundaries:
	 * the cascade stops there, preventing demolition of log cabins built around real trees.
	 * Before breaking anything, validates that the resulting cluster is plausibly a real tree.
	 */
	private void fellTree(ServerLevel world, BlockPos origin, Block originBlock, ItemStack tool) {
		PlacedLogTracker tracker = PlacedLogTracker.get(world);

		Set<BlockPos> visited = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		List<BlockPos> cluster = new ArrayList<>();

		visited.add(origin);

		// Seed the queue from the neighbours of the block the player just broke.
		for (int[] off : OFFSETS) {
			queue.add(origin.offset(off[0], off[1], off[2]));
		}

		int minX = origin.getX(), maxX = origin.getX();
		int minZ = origin.getZ(), maxZ = origin.getZ();

		while (!queue.isEmpty() && cluster.size() < MAX_LOGS) {
			BlockPos current = queue.poll();
			if (!visited.add(current)) {
				continue;
			}

			// Player-placed log: the cascade stops here, exactly like a wall.
			// Not added to the cluster; its neighbours are not explored.
			if (tracker.wasPlacedByPlayer(current)) {
				continue;
			}

			BlockState currentState = world.getBlockState(current);
			if (!isTreeBlock(currentState)) {
				continue;
			}

			cluster.add(current);
			minX = Math.min(minX, current.getX());
			maxX = Math.max(maxX, current.getX());
			minZ = Math.min(minZ, current.getZ());
			maxZ = Math.max(maxZ, current.getZ());

			for (int[] off : OFFSETS) {
				BlockPos next = current.offset(off[0], off[1], off[2]);
				if (!visited.contains(next)) {
					queue.add(next);
				}
			}
		}

		if (cluster.isEmpty()) {
			return;
		}

		// --- Check 1: horizontal spread. Real trees do not extend far horizontally. ---
		int horizontalSpread = Math.max(maxX - minX, maxZ - minZ);
		if (horizontalSpread > MAX_HORIZONTAL_SPREAD) {
			LOGGER.debug("[TreeFell] Cluster rejected: horizontal spread {} blocks exceeds limit. Probable player build.", horizontalSpread);
			return;
		}

		// --- Check 2: nearby leaves. Real trees always have leaves in the vicinity. ---
		if (!hasEnoughNearbyLeaves(world, cluster)) {
			LOGGER.debug("[TreeFell] Cluster rejected: insufficient leaf coverage. Probable player build.");
			return;
		}

		// All checks passed: cluster is considered a real tree. Proceed with the fell.
		for (BlockPos pos : cluster) {
			BlockState state = world.getBlockState(pos);
			if (!isTreeBlock(state)) {
				continue;
			}
			world.destroyBlock(pos, true);
		}
	}

	/**
	 * Returns true if enough logs in the cluster have at least one leaf block
	 * (tag minecraft:leaves) within LEAF_SEARCH_RADIUS.
	 */
	private boolean hasEnoughNearbyLeaves(ServerLevel world, List<BlockPos> cluster) {
		int withLeavesNearby = 0;

		for (BlockPos logPos : cluster) {
			if (hasLeafNearby(world, logPos)) {
				withLeavesNearby++;
			}
		}

		double ratio = (double) withLeavesNearby / cluster.size();
		return ratio >= MIN_LEAF_COVERAGE_RATIO;
	}

	private boolean hasLeafNearby(ServerLevel world, BlockPos center) {
		for (int dx = -LEAF_SEARCH_RADIUS; dx <= LEAF_SEARCH_RADIUS; dx++) {
			for (int dy = -LEAF_SEARCH_RADIUS; dy <= LEAF_SEARCH_RADIUS; dy++) {
				for (int dz = -LEAF_SEARCH_RADIUS; dz <= LEAF_SEARCH_RADIUS; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) continue;
					BlockPos checkPos = center.offset(dx, dy, dz);
					if (world.getBlockState(checkPos).is(BlockTags.LEAVES)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Returns true if the block is part of the "fellable" structure of a tree:
	 * - Any log/wood block (tag minecraft:logs)
	 * - Mangrove roots (minecraft:mangrove_roots)
	 * - Muddy mangrove roots (minecraft:muddy_mangrove_roots)
	 */
	private static boolean isTreeBlock(BlockState state) {
		return state.is(BlockTags.LOGS)
				|| state.is(Blocks.MANGROVE_ROOTS)
				|| state.is(Blocks.MUDDY_MANGROVE_ROOTS);
	}

	private static int[][] buildOffsets() {
		List<int[]> list = new ArrayList<>();
		for (int x = -1; x <= 1; x++) {
			for (int y = -1; y <= 1; y++) {
				for (int z = -1; z <= 1; z++) {
					if (x == 0 && y == 0 && z == 0) continue;
					list.add(new int[]{x, y, z});
				}
			}
		}
		return list.toArray(new int[0][]);
	}
}
