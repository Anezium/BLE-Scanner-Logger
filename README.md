# BLE Scanner Logger

Application Android native Kotlin pour scanner et archiver des advertising BLE hors connexion.

## Objectif

Le comportement principal est volontairement simple: enregistrer tout ce qu'Android remonte au scanner BLE, sans filtre, sans connexion GATT, avec une ligne CSV par advertising reçu.

Le fichier CSV brut est la source de verite. L'affichage live sert seulement au controle rapide sur le terrain et garde les 200 dernieres lignes visibles.

## Fonctionnement

- Scan BLE continu via `BluetoothLeScanner`.
- Aucun filtre BLE par defaut: `startScan(null, settings, callback)`.
- Mode scan low latency.
- Foreground service pour maintenir le scan.
- Fonctionnement hors connexion.
- CSV local dans le dossier applicatif:
  `Android/data/com.example.blescanner/files/Documents/ble_logs/`
- Chemin complet typique sur le téléphone:
  `/sdcard/Android/data/com.example.blescanner/files/Documents/ble_logs/`
- Ce dossier est un stockage externe specifique a l'application. L'app peut le lire/ecrire sans permission fichier globale. Sur Android recent, il est souvent masque ou limite dans les gestionnaires de fichiers, mais il reste accessible via l'application, via l'export Android, et via ADB.
- Les fichiers de ce dossier sont supprimés si l'application est désinstallée.
- Rotation automatique des fichiers bruts:
  `ble_scan_YYYYMMDD_HHMMSS_part001.csv`, `part002.csv`, etc.
- Rotation a 10 Mo ou 100 000 lignes par fichier.
- L'affichage live est volontairement rafraîchi par paquets, environ deux fois par seconde, pour éviter de saturer le téléphone quand beaucoup de trames sont reçues.
- L'écriture CSV reste exhaustive. Le flush disque est périodique, toutes les 100 lignes ou toutes les secondes, puis forcé à l'arrêt du scan.

## Interface

Ecran principal:

- `Start`: démarre le scan.
- `Stop`: arrête le scan et ferme proprement le fichier CSV.
- `Fichiers`: ouvre la page des fichiers CSV locaux.
- `Clear`: vide uniquement l'affichage live de l'application. Les fichiers CSV ne sont pas supprimés.

Page fichiers:

- Liste les CSV présents sur le téléphone.
- Ouvre un fichier pour le parcourir.
- Affiche le chemin du dossier local dans un bloc compact.
- Resume le nombre de fichiers, leur taille totale et le fichier le plus recent.
- Sélection multiple des CSV.
- `Exporter`: ouvre le partage Android pour envoyer/copier les CSV sélectionnés.
- `Supprimer`: supprime les CSV sélectionnés après confirmation. La suppression est désactivée pendant un scan actif pour éviter de supprimer un fichier en cours d'écriture.
- Le lecteur CSV affiche 200 lignes par page.
- Les filtres `iBeacon`, `DATI`, `Eddystone` peuvent être cochés.
- Si aucun filtre n'est coche, toutes les lignes du fichier sont affichees.

## Permissions Android

L'application demande les permissions necessaires selon la version Android:

- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION`
- `POST_NOTIFICATIONS` sur Android 13+
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`

La localisation précise est demandée car certains téléphones Android filtrent ou bloquent le scan BLE si elle est refusée.

## Schema CSV brut

Chaque ligne correspond à un `ScanResult` reçu.

| Colonne | Description |
|---|---|
| `wall_time_iso` | Date/heure de reception au format ISO UTC avec millisecondes (`Z`). |
| `wall_time_local` | Date/heure locale du telephone avec offset de fuseau, par exemple `2026-05-19T16:18:28.357+02:00`. |
| `wall_time_ms_epoch` | Date/heure de reception en millisecondes epoch. |
| `timestamp_nanos_android` | Timestamp Android du `ScanResult`, en nanosecondes. |
| `address` | Adresse BLE vue par Android. Peut être randomisée. |
| `rssi_dbm` | RSSI en dBm. |
| `raw_scan_record_hex` | Payload brut complet du scan record en hexadecimal. Source de verite. |
| `device_name` | Nom device si présent dans l'advertising. |
| `manufacturer_data` | Manufacturer data extrait par Android, format `0xID=HEX`. |
| `service_uuids` | Service UUIDs annonces. |
| `service_data` | Service data annonce, format `UUID=HEX`. |
| `ibeacon_uuid` | UUID iBeacon si parse. |
| `ibeacon_major` | Major iBeacon si parse. |
| `ibeacon_minor` | Minor iBeacon si parse. |
| `ibeacon_tx_power` | Tx Power iBeacon si parse. |
| `eddystone_uid_namespace` | Namespace Eddystone UID si parse. |
| `eddystone_uid_instance` | Instance Eddystone UID si parse. |
| `eddystone_uid_tx_power` | Tx Power Eddystone UID si parse. |
| `eddystone_tlm_battery_mv` | Batterie Eddystone TLM en mV si parse. |
| `eddystone_tlm_temperature_c` | Température Eddystone TLM en degrés C si parsée. |
| `eddystone_tlm_adv_count` | Compteur ADV/PDU Eddystone TLM si parse. |
| `eddystone_tlm_sec_count` | Compteur temps Eddystone TLM si parse. |
| `dati_room` | Emplacement DATI/INVIRTUS, ASCII 12 octets, si parse. |
| `dati_autonomy` | Autonomie DATI/INVIRTUS en pourcentage. |
| `dati_temperature_c` | Température DATI/INVIRTUS, int8 signé. |
| `dati_flags` | Indicateurs DATI/INVIRTUS, uint8. |
| `dati_firmware_version` | Version firmware DATI/INVIRTUS. |

Exemple de ligne brute simplifiee:

```csv
2026-05-19T10:29:48.270Z,1779186588270,2328787496100388,C8:A6:EF:59:1E:1B,-48,0201181BFF75004204010167C8A6EF591E1B...,"32"" Odyssey OLED G8",0x0075=4204010167C8A6EF591E1B...
```

## Formats de trames

### iBeacon standard

La trame iBeacon standard est contenue dans le manufacturer data.

Structure du bloc manufacturer data après l'AD type `0xFF`:

| Offset | Taille | Champ |
|---|---:|---|
| 0..1 | 2 | Company ID, typiquement `0x004C` pour Apple. |
| 2 | 1 | Beacon Type `0x02`. |
| 3 | 1 | Beacon Length `0x15`. |
| 4..19 | 16 | UUID. |
| 20..21 | 2 | Major, big-endian. |
| 22..23 | 2 | Minor, big-endian. |
| 24 | 1 | Measured Power / Tx Power. |

Dans Android, `ScanRecord.getManufacturerSpecificData(companyId)` retire deja le Company ID. Le parseur lit donc `0x02 0x15` au debut du payload manufacturer.

### DATI / INVIRTUS

Le format DATI/INVIRTUS fourni reutilise une structure proche iBeacon avec Company ID `0xFFFF`.

Structure du payload manufacturer data après Company ID `0xFFFF`:

| Offset | Taille | Champ | Format |
|---|---:|---|---|
| 0 | 1 | Beacon Type | `0x02` |
| 1 | 1 | Beacon Length | `0x15` |
| 2..13 | 12 | Location | ASCII 12 caracteres |
| 14 | 1 | Battery level | uint8, pourcentage |
| 15 | 1 | Température | int8 signé |
| 16 | 1 | Flags / indicateurs | uint8 |
| 17 | 1 | Firmware version | uint8 |
| 18..19 | 2 | Major | tag ID, MAC[2..3], big-endian |
| 20..21 | 2 | Minor | tag ID, MAC[4..5], big-endian |
| 22 | 1 | Measured Power | TX @ 1 m |

Quand une trame DATI est reconnue, elle est affichee en vert dans le live et renseigne les colonnes `dati_*` du CSV.

Le payload brut reste toujours conserve dans `raw_scan_record_hex`, meme si le parseur DATI ne reconnait pas une variation de format.

### Eddystone UID

Eddystone utilise le Service UUID `FEAA`.

Contenu service data UID:

| Offset | Taille | Champ |
|---|---:|---|
| 0 | 1 | Frame Type `0x00`. |
| 1 | 1 | Tx Power, int8 signe. |
| 2..11 | 10 | Namespace ID. |
| 12..17 | 6 | Instance ID. |
| 18..19 | 2 | Reserved, normalement `0x0000`. |

### Eddystone TLM

Contenu service data TLM:

| Offset | Taille | Champ |
|---|---:|---|
| 0 | 1 | Frame Type `0x20`. |
| 1 | 1 | TLM Version. |
| 2..3 | 2 | Battery Voltage, uint16 big-endian, mV. |
| 4..5 | 2 | Beacon Temp, fixe 8.8 signe. |
| 6..9 | 4 | ADV/PDU Count, uint32 big-endian. |
| 10..13 | 4 | SEC Count, uint32 big-endian, pas de 0,1 seconde. |

## Validation des parseurs

Oui, on peut valider les parseurs avec de fausses trames.

Options pratiques:

1. iBeacon via iPhone
   - Utiliser une app type Beacon Simulator.
   - Laisser l'app ouverte au premier plan.
   - Verifier que `ibeacon_uuid`, `ibeacon_major`, `ibeacon_minor`, `ibeacon_tx_power` sont remplis.

2. Eddystone via Android ou outil BLE
   - Utiliser `nRF Connect` sur un deuxième téléphone Android.
   - Creer un advertiser avec Service UUID `FEAA`.
   - Pour UID, service data commencant par `00`.
   - Pour TLM, service data commencant par `20`.

3. DATI/INVIRTUS via Android, ESP32 ou nRF52840
   - Le plus fiable est un deuxième Android avec nRF Connect, un ESP32, ou une carte Nordic.
   - Generer un advertising manufacturer data Company ID `0xFFFF`.
   - Payload: `02 15` + 12 octets ASCII location + batterie + temperature + flags + firmware + major + minor + tx.

4. Tests instrumentes Android
   - Ajouter des payloads hex connus.
   - Construire un `ScanRecord` via `ScanRecord.parseFromBytes(...)`.
   - Verifier les champs retournes par `BeaconParser`.

## Exemple DATI synthetique

Payload manufacturer data après Company ID `FFFF`:

```text
02 15 53 41 4C 4C 45 5F 30 30 30 31 20 20 64 15 01 03 12 34 56 78 C5
```

Interpretation:

- `02 15`: type et longueur iBeacon.
- `53 41 4C 4C 45 5F 30 30 30 31 20 20`: `SALLE_0001  `.
- `64`: batterie 100%.
- `15`: temperature 21 C.
- `01`: flags.
- `03`: firmware version 3.
- `12 34`: major.
- `56 78`: minor.
- `C5`: measured power, -59 dBm en int8.

## Build

```powershell
.\gradlew.bat assembleDebug
```

APK debug:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Installation via ADB:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
