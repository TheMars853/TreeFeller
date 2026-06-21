package com.aresstudio.treefell;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
 * Tiene traccia, per il server, delle posizioni dei blocchi di tronco piazzati
 * manualmente da un giocatore (in contrapposizione a quelli generati naturalmente
 * dalla crescita di un albero). Persistito su disco tramite il sistema SavedData
 * di Minecraft (salvato come file .dat nella cartella del mondo, dentro
 * data/treefell_placed_logs.dat), quindi sopravvive a riavvii del server.
 *
 * Limite noto: tronchi piazzati PRIMA dell'installazione della mod (mondi già
 * esistenti) non sono nel registro, e vengono trattati come "naturali" finché
 * non vengono ripiazzati dopo l'installazione.
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
			PlacedLogTracker::new, // Crea un nuovo PlacedLogTracker dalla lista salvata.
			PlacedLogTracker::getPlacedLogPositions // Ritorna la lista da salvare su disco.
	);

	private static final SavedDataType<PlacedLogTracker> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(TreeFell.MOD_ID, "placed_logs"), // Nome univoco del file salvato.
			PlacedLogTracker::new, // Se non esiste ancora, ne crea uno nuovo vuoto.
			CODEC, // Codec usato per serializzazione/deserializzazione.
			null // Data fixer, non necessario qui.
	);

	public static PlacedLogTracker get(ServerLevel world) {
		MinecraftServer server = world.getServer();
		// Usiamo sempre l'overworld come storage condiviso: il tracking dei tronchi
		// piazzati ha senso a livello di intero server, non per singola dimensione,
		// così funziona in modo coerente anche se un giocatore costruisce nel Nether.
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
