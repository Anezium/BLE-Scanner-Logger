# BLE Scanner Logger

Application Android native Kotlin pour scanner les advertising BLE et archiver les capteurs IMU du téléphone hors connexion.

## Objectif

Le comportement principal est volontairement simple: enregistrer tout ce qu'Android remonte au scanner BLE et aux capteurs du téléphone, sans filtre et sans connexion GATT. Chaque advertising ou échantillon capteur conserve sa propre ligne et son propre timestamp.

Le journal CSV brut est la source de vérité. L'affichage live BLE sert seulement au contrôle rapide sur le terrain et garde les 200 dernières trames visibles; les centaines d'échantillons IMU par seconde ne sont pas injectées dans le flux live.

## Fonctionnement

- Scan BLE continu via `BluetoothLeScanner`.
- Aucun filtre BLE par defaut: `startScan(null, settings, callback)`.
- Mode scan low latency.
- Collecte IMU simultanée pendant chaque session BLE:
  - accéléromètre 100 Hz;
  - gyroscope 100 Hz;
  - vecteur de rotation et game rotation vector 50 Hz;
  - champ magnétique 50 Hz;
  - pression 10 Hz;
  - détecteur et compteur de pas à leur cadence événementielle.
- Les variantes non calibrées de l'accéléromètre, du gyroscope et du magnétomètre sont privilégiées afin de garder le biais estimé. La variante calibrée sert automatiquement de fallback.
- Foreground service `connectedDevice` pour maintenir le scan et les capteurs écran éteint.
- Fonctionnement hors connexion.
- Détection dynamique du matériel: un capteur absent ou refusé ne bloque pas la session et son état est écrit dans les métadonnées.
- CSV local dans le dossier applicatif:
  `Android/data/com.anezium.blescanner/files/Documents/ble_logs/`
- Chemin complet typique sur le téléphone:
  `/sdcard/Android/data/com.anezium.blescanner/files/Documents/ble_logs/`
- Ce dossier est un stockage externe specifique a l'application. L'app peut le lire/ecrire sans permission fichier globale. Sur Android recent, il est souvent masque ou limite dans les gestionnaires de fichiers, mais il reste accessible via l'application, via l'export Android, et via ADB.
- Les fichiers de ce dossier sont supprimés si l'application est désinstallée.
- Rotation automatique des fichiers bruts:
  `ble_imu_session_YYYYMMDD_HHMMSS_SSS_part001.csv`, `part002.csv`, etc.
- Rotation a 10 Mo ou 100 000 lignes par fichier.
- L'affichage live est volontairement rafraîchi par paquets, environ deux fois par seconde, pour éviter de saturer le téléphone quand beaucoup de trames sont reçues.
- L'écriture CSV reste exhaustive. Le flush disque est périodique, toutes les 100 lignes ou toutes les secondes, puis forcé à l'arrêt du scan.
- À l'arrêt, une ligne `sensor_summary` donne le nombre d'échantillons et la fréquence moyenne réellement obtenue pour chaque capteur. Cela permet de comparer facilement les comportements Pixel/Samsung.

## Interface

L'interface est entièrement en français et construite en vues Android natives
(aucun XML de layout, aucune bibliothèque d'UI tierce). Trois écrans, thème
clair unique, accent teal `#147B6C`.

### Écran principal

- En-tête: titre « BLE Scanner » et une pastille d'état: « Prêt », « Scan en
  cours » ou « Arrêté ».
- Carte « Scanner »:
  - un sélecteur à deux choix, `Bluetooth` ou `Réseau mobile` (le choix est
    mémorisé pendant la session, il est verrouillé pendant un scan);
  - un seul gros bouton: `Démarrer le scan` (teal), qui devient
    `Arrêter le scan` (rouge) pendant un scan. Le rouge n'est utilisé que pour
    cette action.
- Trois compteurs BLE: nombre de trames reçues, nombre d'appareils distincts et
  durée du scan en `HH:MM:SS`.
- Panneau « Dernières trames »: les 200 dernières trames, la plus récente en
  haut, chacune sur un fond coloré selon son type (BLE, iBeacon, Eddystone UID,
  Eddystone TLM, DATI, réseau mobile, messages système). Un appui long sur une
  ligne la copie dans le presse-papiers.
- `Effacer`, en haut du panneau: vide uniquement l'affichage et les compteurs.
  Les fichiers CSV ne sont pas supprimés.
- `Fichiers CSV`, en bas: ouvre la page des fichiers, avec le nombre de fichiers
  présents.

### Page fichiers

- Résumé sur une ligne: nombre de fichiers, taille totale et heure du dernier
  fichier écrit, suivi du chemin du dossier local.
- `Tout` / `Aucun`: sélectionne ou désélectionne tous les fichiers.
- `Exporter`: ouvre le partage Android pour envoyer ou copier les CSV
  sélectionnés.
- `Supprimer`: supprime les CSV sélectionnés après confirmation. L'action est
  désactivée pendant un scan actif (libellé « Stop requis ») pour éviter de
  supprimer un fichier en cours d'écriture.
- Liste des fichiers, du plus récent au plus ancien: un appui sur une ligne
  ouvre le fichier, un appui long le sélectionne, la case à gauche fait de même.

### Lecteur CSV

- Filtres `iBeacon`, `DATI`, `Eddystone` sous forme de pastilles à cocher. Si
  aucun filtre n'est coché, toutes les lignes du fichier sont affichées.
- Champ `Adresse MAC`: filtre les lignes dont l'adresse contient la valeur
  saisie (séparateurs et casse ignorés).
- Pagination de 200 lignes par page, avec `Précédent` et `Suivant`.
- Le contenu affiché est sélectionnable pour copier-coller.

## Permissions Android

L'application demande les permissions necessaires selon la version Android:

- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION`
- `POST_NOTIFICATIONS` sur Android 13+
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `ACTIVITY_RECOGNITION` sur Android 10+ pour le détecteur et le compteur de pas

La permission d'activité physique est optionnelle: si elle est refusée, seuls le détecteur et le compteur de pas sont ignorés. Les autres capteurs et le BLE continuent normalement. La localisation précise est demandée car certains téléphones Android filtrent ou bloquent le scan BLE si elle est refusée.

Au démarrage d'un scan, l'application vérifie aussi que le Bluetooth et la localisation de l'appareil sont activés (Android ne livre aucun résultat de scan BLE si la localisation système est éteinte, même avec toutes les permissions accordées). Si l'un des deux est éteint, un dialog système propose de l'activer en un tap, puis le scan démarre automatiquement. Si Play Services est absent, l'application ouvre directement l'écran de réglages concerné.

## Schéma CSV événementiel BLE + IMU

Le CSV est volontairement « tout-en-un », mais il ne recopie jamais une dernière valeur IMU sur une ligne BLE. Chaque ligne est un événement réel identifié par `event_type`. Les cellules qui ne concernent pas cet événement restent vides.

Types de lignes principaux:

- `ble`;
- `accelerometer` ou `accelerometer_uncalibrated`;
- `gyroscope` ou `gyroscope_uncalibrated`;
- `rotation_vector` et `game_rotation_vector`;
- `magnetic_field` ou `magnetic_field_uncalibrated`;
- `pressure`, `step_detector`, `step_counter`;
- `session_metadata`, `sensor_metadata`, `sensor_accuracy`, `sensor_summary`.

Colonnes communes:

| Colonne | Description |
|---|---|
| `session_id` | Identifiant UTC partagé par toutes les parties rotatives d'une session. |
| `sequence` | Ordre d'écriture strict dans le journal. |
| `event_type` | Nature de l'événement. |
| `wall_time_iso` | Heure UTC estimée de l'événement à partir de l'horloge monotone. |
| `wall_time_local` | Même instant dans le fuseau du téléphone. |
| `wall_time_ms_epoch` | Même instant en millisecondes epoch. |
| `event_time_nanos` | Timestamp source Android du `ScanResult` ou `SensorEvent`, base `elapsedRealtimeNanos`. Référence principale pour la synchronisation. |
| `received_time_nanos` | Instant de traitement du callback dans la même base monotone. |
| `callback_latency_nanos` | Différence positive entre réception et événement. |

Colonnes capteurs:

| Colonne | Description |
|---|---|
| `sensor_channel` | Canal logique stable (`accelerometer`, `gyroscope`, etc.), indépendamment du fallback calibré/non calibré. |
| `sensor_type` | Type effectivement choisi sur l'appareil. |
| `sensor_x/y/z` | Axes SI; quaternion x/y/z pour les vecteurs de rotation. |
| `sensor_w` | Composante w du quaternion. |
| `sensor_bias_x/y/z` | Biais estimé fourni par les capteurs non calibrés. |
| `sensor_heading_accuracy_rad` | Précision de cap du rotation vector lorsqu'Android la fournit. |
| `sensor_scalar` | Pression ou valeur d'un capteur de pas. |
| `sensor_values_raw` | Copie exhaustive du tableau `SensorEvent.values`, séparée par `|`. |
| `sensor_accuracy` | Niveau de précision Android. |
| `sensor_requested_period_us` | Période demandée lors de l'enregistrement. La cadence réellement observée est mesurée séparément. |
| `sensor_available/registered` | Disponibilité matérielle et succès d'enregistrement. |
| `sensor_sample_count`, `sensor_observed_rate_hz` | Statistiques écrites à l'arrêt de la session. |

Les colonnes BLE existantes sont conservées:

| Colonne | Description |
|---|---|
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

Exemple simplifié:

```csv
session_id,sequence,event_type,wall_time_iso,...,event_time_nanos,received_time_nanos,...,sensor_x,sensor_y,sensor_z,...,address,rssi_dbm,raw_scan_record_hex,...
20260903_121500_123,401,accelerometer_uncalibrated,2026-09-03T12:15:01.000Z,...,2328787496100388,2328787496200388,...,0.12,-0.31,9.74,...,,,
20260903_121500_123,402,ble,2026-09-03T12:15:01.003Z,...,2328787499100388,2328787499300388,...,,,,...,C8:A6:EF:59:1E:1B,-48,0201181B...
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
