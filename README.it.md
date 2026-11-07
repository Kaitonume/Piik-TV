<h1 align="center">PIIK TV</h1>
<p align="center"><strong>Guarda le stanze di <a href="https://github.com/TNTcraftHIM/Piik">Piik</a> su Android TV e Google TV, fluide e con bassa latenza.</strong></p>
<p align="center"><a href="./README.md">English</a> · Italiano · <a href="./README.zh-CN.md">简体中文</a></p>

PIIK TV è un'app per Android TV che entra in una stanza Piik come spettatore e la
mostra a schermo intero. È pensata per vedere un PC sulla TV del salotto attraverso
la rete di casa: decodifica video hardware direttamente sullo schermo, fotogrammi
scanditi sulla frequenza del display e un'interfaccia fatta per il telecomando.

> PIIK TV è un'app indipendente: non è realizzata dal progetto Piik né collegata a
> esso.

## Funzioni

- **Trova da sola il PC.** Cerca Piik nella rete locale (porta 8787) e si entra con
  il codice stanza di 4 cifre. La volta dopo si ricollega da sola all'ultimo PC e
  all'ultima stanza (si può disattivare). Dalla 1.7.0, con VibePiik 1.2.0 entra nella
  stanza aperta sul PC anche se il codice è cambiato; se quella stanza non c'è più
  chiede il nuovo codice invece di riprovare il vecchio.
- **Link di invito**, anche per le stanze Public invite raggiunte da fuori casa
  (`https://….trycloudflare.com/r/1234#v=…`).
- **Link mandati dal PC.** Mentre l'app è aperta, la TV ascolta sulla porta TCP
  8790: [VibePiik](https://github.com/Kaitonume/VibePiik) (Piik con il supporto a PIIK TV, per Windows; facoltativa: PIIK TV funziona anche con Piik ufficiale) manda il link della
  stanza con un clic, e qualsiasi browser può mandarne uno da
  `http://<indirizzo della TV>:8790`.
- **Decodifica hardware** di H.264, HEVC (dalla 1.7.0), VP8, VP9 e AV1 quando la TV li supporta, con la
  modalità a bassa latenza dove disponibile; decodifica software di riserva per
  VP8, VP9 e AV1.
- **Cadenza dei fotogrammi** sulla frequenza del display: i fotogrammi che arrivano
  irregolari via Wi-Fi vengono mostrati a ritmo costante, scegliendo la modalità a
  60 Hz se la TV la offre.
- **Tre modalità di latenza**: minima (nessun buffer, scarta i fotogrammi in
  ritardo), bilanciata e massima fluidità.
- **Statistiche a schermo** (OK sul telecomando).
- **Italiano, inglese e cinese**: automatica (lingua della TV, inglese per tutte le altre) o scelta in Impostazioni → Lingua.
- **Aggiornamenti dalla TV** (dalla 1.6.0): quando esce una nuova versione, un pulsante
  nella schermata iniziale ne mostra il changelog e la installa sul momento, dopo aver
  verificato l'impronta SHA-256 (Android chiede una volta di permettere a PIIK TV di
  installare app). Gli aggiornamenti importanti si aprono da soli una volta. Alcune TV (per esempio TCL)
  impediscono alle app di avviarsi da sole: lì, dopo l'aggiornamento, riapri PIIK TV.
  Impostazioni → Aggiornamenti mostra la versione installata e controlla subito (dalla 1.7.1).

## Requisiti

- Android 5.0 o successivo (API 21): Android TV, Google TV, Fire TV, box e
  chiavette. Si può installare anche su telefoni e tablet (solo in orizzontale).
- ARM (32 o 64 bit) o x86 (32 o 64 bit).

Provata su una TV TCL con Google TV (Android 12, ARM 32 bit, decoder Realtek) con
Piik avviato da Windows e dall'app Piik per Android. Gli altri dispositivi e le
altre versioni di Android sono supportati dalla compilazione ma non ancora provati.

## Installazione

Scarica un APK dalla [pagina Releases](https://github.com/Kaitonume/Piik-TV/releases):

| File | Per |
| --- | --- |
| `PiikTV-<versione>-universal.apk` | Tutti i dispositivi (consigliato). |
| `PiikTV-<versione>-armeabi-v7a.apk` | ARM 32 bit: la maggior parte di TV e chiavette. |
| `PiikTV-<versione>-arm64-v8a.apk` | ARM 64 bit. |
| `PiikTV-<versione>-x86.apk`, `-x86_64.apk` | Dispositivi Intel/AMD ed emulatori. |

Copialo sulla TV (chiavetta USB e un file manager, un'app per inviare file alla TV
o `adb install`) e consenti l'installazione da origini sconosciute. Ogni APK della
stessa versione aggiorna qualsiasi altro.

## Uso

1. Sul PC apri Piik (Local room o Public invite) e avvia la condivisione.
2. Apri PIIK TV: scegli il PC trovato e scrivi il codice stanza, oppure usa
   **Indirizzo o link…** per un link di invito, oppure manda il link dal PC.

Telecomando durante la visione: **OK** statistiche, **Su** chiede un keyframe al
PC, **Indietro** chiude lo stream e torna al menu (di nuovo Indietro esce),
**Home** chiude lo stream (riaprendo l'app si ricollega all'ultimo PC).

Per l'immagine più fluida usa H.264 sul PC (la TV lo decodifica in hardware) e una
connessione a 5 GHz o via cavo per il PC; su una rete 2,4 GHz affollata abbassa
bitrate o risoluzione in Piik. Con [VibePiik](https://github.com/Kaitonume/VibePiik)
1.2.0 puoi condividere anche in HEVC o AV1: circa metà bitrate per la stessa
immagine e fino al 4K dove il decoder della TV lo consente (molte TV decodificano
H.264 solo fino al 1080p).

## Ricevitore dei link, compilazione e licenza

Vedi il [README in inglese](./README.md#link-receiver): API del ricevitore sulla
porta 8790, compilazione con `./gradlew assembleRelease` e firma. Licenza
[MIT](./LICENSE); librerie incluse in [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md).
