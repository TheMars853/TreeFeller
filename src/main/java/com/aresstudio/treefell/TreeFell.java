package com.aresstudio.treefell;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TreeFell
 * Quando un blocco appartenente a un tronco d'albero (tag minecraft:logs) viene rotto,
 * l'intera struttura di tronco connessa viene abbattuta istantaneamente.
 * Le foglie non vengono toccate: decadono naturalmente come da comportamento vanilla.
 */
public class TreeFell implements ModInitializer {

	public static final String MOD_ID = "treefell";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Numero massimo di blocchi di tronco abbattibili in un colpo solo (anti-lag / anti-griefing).
	private static final int MAX_LOGS = 512;

	// Le 6 direzioni assiali + le 12 diagonali sul piano orizzontale e verticale:
	// usiamo un raggio di ricerca 3x3x3 attorno a ogni tronco trovato per seguire
	// anche alberi con tronchi "spessi" (2x2) o leggermente irregolari.
	private static final int[][] OFFSETS = buildOffsets();

	@Override
	public void onInitialize() {
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			// Solo lato server: evitiamo desync rompendo blocchi anche sul client.
			if (world.isClientSide()) {
				return;
			}
			if (!(world instanceof ServerLevel serverLevel)) {
				return;
			}

			// Si attiva solo se il blocco rotto era effettivamente un tronco/legno (tag vanilla minecraft:logs).
			if (!state.is(BlockTags.LOGS)) {
				return;
			}

			Block logBlock = state.getBlock();
			fellTree(serverLevel, pos, logBlock, player == null ? ItemStack.EMPTY : player.getMainHandItem());
		});

		LOGGER.info("[TreeFell] Initialized. Max logs per tree-fell: {}", MAX_LOGS);
	}

	/**
	 * Esegue una BFS a partire dal blocco già rotto, esplorando tutti i blocchi di tronco
	 * adiacenti (stesso tipo di legno, tramite tag LOGS) e li rompe a cascata.
	 */
	private void fellTree(ServerLevel world, BlockPos origin, Block originBlock, ItemStack tool) {
		Set<BlockPos> visited = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		List<BlockPos> toBreak = new ArrayList<>();

		visited.add(origin);

		// Partiamo dai vicini del blocco appena rotto dal giocatore.
		for (int[] off : OFFSETS) {
			queue.add(origin.offset(off[0], off[1], off[2]));
		}

		while (!queue.isEmpty() && toBreak.size() < MAX_LOGS) {
			BlockPos current = queue.poll();
			if (!visited.add(current)) {
				continue;
			}

			BlockState currentState = world.getBlockState(current);
			if (!currentState.is(BlockTags.LOGS)) {
				continue;
			}

			toBreak.add(current);

			for (int[] off : OFFSETS) {
				BlockPos next = current.offset(off[0], off[1], off[2]);
				if (!visited.contains(next)) {
					queue.add(next);
				}
			}
		}

		// Rompe tutti i blocchi trovati, droppando gli item come una normale rottura
		// (così funzionano anche fortuna/incantesimi sull'attrezzo, se presente).
		for (BlockPos pos : toBreak) {
			BlockState state = world.getBlockState(pos);
			if (!state.is(BlockTags.LOGS)) {
				continue;
			}
			world.destroyBlock(pos, true);
		}
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
