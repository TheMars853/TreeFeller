package com.aresstudio.treefell;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PlacedLogTracker
 * Tracks the positions of log blocks manually placed by a player (as opposed to
 * those generated naturally by tree growth). Persisted to disk via Minecraft's
 * SavedData system (stored as data/treefell_placed_logs.dat inside the world
 * folder), so it survives server restarts.
 *
 * Known limitation: logs placed BEFORE installing this mod (pre-existing worlds)
 * are not in the registry and will be treated as natural until re-placed.
 */
public class PlacedLogTracker extends SavedData {

	private final Set<BlockPos> placedLogPositions;

	public PlacedLogTracker() {
		this.placedLogPositions = new HashSet<>();
	}

	private PlacedLogTracker(List<BlockPos> loadedPositions) {
		this.placedLogPositions = new HashSet<>(loadedPositions);
	}

	private List<BlockPos> getPlacedLogPositions() {
		return List.copyOf(placedLogPositions);
	}

	private static final Codec<PlacedLogTracker> CODEC = BlockPos.CODEC.listOf().xmap(
			PlacedLogTracker::new,            // Create a new PlacedLogTracker from the saved list.
			PlacedLogTracker::getPlacedLogPositions // Return the list to be saved to disk.
	);

	private static final SavedDataType<PlacedLogTracker> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(TreeFell.MOD_ID, "placed_logs"), // Unique file identifier.
			PlacedLogTracker::new, // Factory: creates an empty instance if no saved data exists yet.
			CODEC,                 // Codec used for serialization / deserialization.
			null                   // Data fixer — not needed here.
	);

	public static PlacedLogTracker get(ServerLevel world) {
		MinecraftServer server = world.getServer();
		// Always use the overworld as shared storage: placement tracking is server-wide,
		// not per-dimension, so it works consistently even when building in the Nether.
		ServerLevel storageLevel = server.overworld();
		return storageLevel.getDataStorage().computeIfAbsent(TYPE);
	}

	public void markPlaced(BlockPos pos) {
		placedLogPositions.add(pos.immutable());
		setDirty();
	}

	public boolean wasPlacedByPlayer(BlockPos pos) {
		return placedLogPositions.contains(pos);
	}

	public void unmark(BlockPos pos) {
		if (placedLogPositions.remove(pos)) {
			setDirty();
		}
	}
}
