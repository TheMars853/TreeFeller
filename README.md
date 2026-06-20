# TreeFell

Mod Fabric per Minecraft **26.2**: rompi un singolo blocco di tronco e l'intero albero
(solo legno, le foglie restano e decadono normalmente) viene abbattuto all'istante.

## Versioni usate (verificate al 20 giugno 2026)

| Componente      | Versione           |
|-----------------|---------------------|
| Minecraft       | 26.2                |
| Fabric Loader   | 0.19.3               |
| Fabric API      | 0.152.2+26.2          |
| Fabric Loom     | 1.17                |
| Gradle          | 9.5.1                |
| Mappings        | Nessuna — Minecraft 26.2 è già non-obfuscato (nomi Mojang nativi nel jar) |
| Java            | 25                   |

## Setup del progetto

1. Servono **Java 25 (JDK)** e **Gradle 9.5.1** (o usa il wrapper, una volta generato).
2. Genera il Gradle wrapper la prima volta (se non già presente):
   ```bash
   gradle wrapper --gradle-version 9.5.1
   ```
3. Apri la cartella in IntelliJ IDEA (consigliata 2025.3+) come progetto Gradle,
   oppure da terminale:
   ```bash
   ./gradlew build
   ```
4. Il jar compilato si trova in `build/libs/treefell-1.0.0.jar`.

## Testare in gioco

1. Installa Fabric Loader 0.19.3 per Minecraft 26.2 (fabricmc.net/use/installer).
2. Metti nella cartella `mods/` del tuo profilo:
   - `treefell-1.0.0.jar` (questa mod)
   - `fabric-api-0.152.2+26.2.jar` (dipendenza, scaricabile da Modrinth/CurseForge)
3. Avvia il gioco col profilo Fabric 26.2.

Oppure, per development con hot-test diretto da Gradle:
```bash
./gradlew runClient
```

## Come funziona

- Si aggancia all'evento `PlayerBlockBreakEvents.AFTER` di Fabric API (nessun Mixin
  necessario, più stabile su aggiornamenti futuri di Minecraft).
- Quando il blocco rotto appartiene al tag vanilla `minecraft:logs` (copre tronchi
  normali e "stripped" di ogni legno, incluso legno modded che usa lo stesso tag),
  parte una BFS (ricerca in ampiezza) a 26 direzioni (3x3x3 attorno a ogni tronco)
  per trovare tutti i blocchi di tronco collegati.
- Ogni blocco trovato viene distrutto con `destroyBlock(pos, true)`, che droppa gli
  item esattamente come una rottura normale (quindi Fortuna/Silk Touch funzionano).
- Limite di sicurezza: **512 blocchi** per albero, per evitare lag su strutture enormi
  o potenziale abuso/griefing su costruzioni interamente in legno.
- Tutto avviene **solo lato server** (`world.isClientSide()` check), per evitare
  desync tra client e server.
- Le foglie non vengono toccate in alcun modo: restano e decadono secondo le regole
  vanilla normali (mancanza di tronco entro 4-6 blocchi).

## Possibili estensioni future

- Richiedere un'ascia in mano per attivare l'effetto.
- Aggiungere un cooldown o un costo in durabilità proporzionale al numero di blocchi.
- Config file (es. con Cloth Config) per attivare/disattivare il requisito ascia,
  il limite massimo di blocchi, ecc.
