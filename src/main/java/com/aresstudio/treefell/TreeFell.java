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
 * Quando un blocco appartenente a un tronco d'albero (tag minecraft:logs) viene rotto,
 * l'intera struttura di tronco connessa viene abbattuta istantaneamente — ma solo se il
 * cluster sembra plausibilmente un albero vero (foglie nei dintorni + spread orizzontale
 * limitato). Questo evita di demolire case o costruzioni interamente fatte di tronco.
 * Le foglie non vengono toccate: decadono naturalmente come da comportamento vanilla.
 */
public class TreeFell implements ModInitializer {

	public static final String MOD_ID = "treefell";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Numero massimo di blocchi di tronco abbattibili in un colpo solo (anti-lag / anti-griefing).
	private static final int MAX_LOGS = 512;

	// --- Protezione "non è un albero vero" (anti-griefing su case di legno) ---

	// Raggio (in blocchi) entro cui cerchiamo foglie attorno a ogni tronco del cluster.
	private static final int LEAF_SEARCH_RADIUS = 2;

	// Percentuale minima di tronchi del cluster che devono avere almeno una foglia
	// nelle vicinanze, perché il cluster venga considerato un albero vero.
	private static final double MIN_LEAF_COVERAGE_RATIO = 0.25;

	// Se il cluster di tronchi supera questa larghezza orizzontale (X o Z), lo trattiamo
	// come probabile costruzione (un vero albero raramente ha un tronco così esteso in
	// orizzontale) e annulliamo l'abbattimento.
	private static final int MAX_HORIZONTAL_SPREAD = 6;

	// Le 6 direzioni assiali + le 12 diagonali sul piano orizzontale e verticale:
	// usiamo un raggio di ricerca 3x3x3 attorno a ogni tronco trovato per seguire
	// anche alberi con tronchi "spessi" (2x2) o leggermente irregolari.
	private static final int[][] OFFSETS = buildOffsets();

	// Posizioni "candidate" dove un giocatore potrebbe aver appena piazzato un tronco.
	// UseBlockCallback scatta PRIMA che vanilla processi il piazzamento, quindi non
	// possiamo controllare lo stato del blocco nello stesso istante: accodiamo la
	// posizione e la verifichiamo a fine tick (END_WORLD_TICK), quando il piazzamento
	// vanilla è sicuramente già avvenuto.
	private final ConcurrentLinkedQueue<CandidatePlacement> pendingPlacementChecks = new ConcurrentLinkedQueue<>();

	private record CandidatePlacement(ServerLevel world, BlockPos pos) {
	}

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

			// Il blocco è stato rotto: se era marcato come "piazzato da giocatore", rimuoviamo
			// il marker dal registro (non serve più tenerlo, il blocco non esiste più).
			PlacedLogTracker.get(serverLevel).unmark(pos);

			Block logBlock = state.getBlock();
			fellTree(serverLevel, pos, logBlock, player == null ? ItemStack.EMPTY : player.getMainHandItem());
		});

		// Tracciamo i tronchi piazzati manualmente dal giocatore. UseBlockCallback scatta
		// PRIMA che vanilla processi il piazzamento, quindi qui ci limitiamo ad accodare
		// la posizione candidata: la verifica vera avviene a fine tick (vedi sotto).
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

			// Controlliamo solo se l'item in mano può effettivamente piazzare un tronco
			// (un BlockItem il cui blocco risultante ha il tag LOGS), per evitare di fare
			// controlli inutili su ogni right-click in gioco.
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

		// A fine tick del mondo, il piazzamento vanilla (se avvenuto) è già completato:
		// verifichiamo ogni posizione candidata e, se contiene davvero un tronco, la
		// segniamo nel registro persistente come "piazzata da giocatore".
		ServerTickEvents.END_LEVEL_TICK.register(world -> {
			CandidatePlacement candidate;
			while ((candidate = pendingPlacementChecks.poll()) != null) {
				if (candidate.world() != world) {
					// Non è il mondo di questo tick: rimettiamola in coda per dopo.
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
	 * Esegue una BFS a partire dal blocco già rotto, esplorando tutti i blocchi di tronco
	 * adiacenti (stesso tipo di legno, tramite tag LOGS) — ESCLUDENDO quelli marcati come
	 * piazzati manualmente da un giocatore (vedi PlacedLogTracker). Questo è il fix per il
	 * caso "casa-albero": se hai costruito con tronchi attorno o dentro un albero vero, i
	 * tuoi tronchi piazzati a mano interrompono la cascata, anche se il resto del cluster
	 * soddisfa i check di "albero vero" (foglie + spread).
	 * Prima di romperli, verifica comunque che il cluster (al netto dei tronchi esclusi)
	 * sia plausibilmente un albero vero.
	 */
	private void fellTree(ServerLevel world, BlockPos origin, Block originBlock, ItemStack tool) {
		PlacedLogTracker tracker = PlacedLogTracker.get(world);

		Set<BlockPos> visited = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		List<BlockPos> cluster = new ArrayList<>();

		visited.add(origin);

		// Partiamo dai vicini del blocco appena rotto dal giocatore.
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

			// Tronco piazzato manualmente dal giocatore: la cascata si ferma qui,
			// esattamente come se fosse un muro. Non viene aggiunto al cluster e non
			// vengono esplorati i suoi vicini.
			if (tracker.wasPlacedByPlayer(current)) {
				continue;
			}

			BlockState currentState = world.getBlockState(current);
			if (!currentState.is(BlockTags.LOGS)) {
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

		// --- Check 1: spread orizzontale. Un vero albero non si estende molto in larghezza. ---
		int horizontalSpread = Math.max(maxX - minX, maxZ - minZ);
		if (horizontalSpread > MAX_HORIZONTAL_SPREAD) {
			LOGGER.debug("[TreeFell] Cluster scartato: troppo esteso orizzontalmente ({} blocchi). Probabile costruzione.", horizontalSpread);
			return;
		}

		// --- Check 2: presenza di foglie vicine. Un vero albero ha foglie nei dintorni. ---
		if (!hasEnoughNearbyLeaves(world, cluster)) {
			LOGGER.debug("[TreeFell] Cluster scartato: foglie insufficienti nei dintorni. Probabile costruzione.");
			return;
		}

		// Tutti i check superati: il cluster è considerato un albero vero, procediamo a romperlo.
		for (BlockPos pos : cluster) {
			BlockState state = world.getBlockState(pos);
			if (!state.is(BlockTags.LOGS)) {
				continue;
			}
			world.destroyBlock(pos, true);
		}
	}

	/**
	 * Controlla, per ogni blocco di tronco del cluster, se esistono blocchi di foglie
	 * (tag minecraft:leaves) entro LEAF_SEARCH_RADIUS. Se almeno MIN_LEAF_COVERAGE_RATIO
	 * dei tronchi del cluster ha foglie vicine, consideriamo il cluster un albero vero.
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
