# Redesign UI v2 — notes

## Ce qui a changé

### Architecture

`MainActivity.kt` est passé de ~1400 à ~495 lignes. Elle ne contient plus que
l'état, la navigation entre les 3 écrans, les permissions et la réception des
évènements des services. Tout le reste vit dans `com.anezium.blescanner.ui`:

| Fichier | Rôle |
| --- | --- |
| `ui/Theme.kt` | `Colors`, `Dimens` (grille 4dp), fabriques `dp()`, `rounded()`, `buttonBackground()`, `CategoryPalette` |
| `ui/Widgets.kt` | composants partagés: boutons, chips, segments, panneaux, cartes de métrique, header de page, racine d'écran + insets |
| `ui/MainScreen.kt` | construction de l'écran principal + `MainScreenViews` |
| `ui/FilesScreen.kt` | construction de l'écran fichiers, accès disque, mise en forme, export/suppression |
| `ui/FilesController.kt` | état de l'écran fichiers: liste, sélection multiple, actions |
| `ui/ViewerScreen.kt` | construction du lecteur CSV + `ViewerScreenViews` |
| `ui/LiveFeedAdapter.kt` | `BaseAdapter` du flux live (ViewHolder) |
| `ui/FileListAdapter.kt` | `BaseAdapter` de la liste de fichiers (ViewHolder) |
| `ui/LiveFeedBuffer.kt` | buffer 200 lignes, compteurs, rendu throttlé 500 ms |
| `ui/CsvPageSource.kt` | lecture / filtrage / mise en forme des CSV (logique d'origine inchangée) |
| `ui/BackNavigation.kt` | `OnBackInvokedCallback` Android 13+ |

Chaque écran expose un petit holder (`MainScreenViews`, `FilesScreenViews`,
`ViewerScreenViews`) avec les seules vues que l'activité doit mettre à jour, et
reçoit ses callbacks en lambdas. Aucun XML de layout, ni AppCompat / Material /
Compose. Seule dépendance ajoutée après coup : `play-services-location`, pour le
dialog système « activer la localisation » en un tap au démarrage du scan (avec
fallback vers l'écran de réglages si Play Services est absent).

### Écran principal

- Un seul gros bouton 64dp `Démarrer le scan` / `Arrêter le scan` remplace les
  quatre boutons Start/Stop BLE + cell.
- Un sélecteur à deux segments `Bluetooth` / `Réseau mobile` choisit ce que
  démarre ce bouton. Le mode est mémorisé pendant la session et verrouillé
  pendant un scan.
- Le chrono devient la troisième carte de métrique, à côté de trames et
  appareils.
- `Clear` devient `Effacer`, dans le header du panneau des trames.
- Pastille d'état à trois valeurs: « Prêt » (vert), « Scan en cours » (teal),
  « Arrêté » (neutre).
- Le rouge (`#B3402F`) n'est utilisé que pour `Arrêter le scan`.

### Écran fichiers

- `storagePanel` + `fileSummaryPanel` fusionnés en une seule carte
  « N fichiers · X MB · dernier : HH:mm » + chemin monospace ellipsé au milieu.
- Le bouton `Ouvrir` par ligne est supprimé: la ligne entière est la cible.
- `Tout` / sélection mettent à jour l'adapter en place; l'écran n'est plus
  reconstruit (l'ancien `toggleAllLogFiles()` rappelait `showFileListPage()`).

### Lecteur CSV

- Les trois `CheckBox` de filtre deviennent des pastilles à cocher 40dp.
- `Préc.` / `Suiv.` deviennent `Précédent` / `Suivant` en 48dp, et `Précédent`
  est désactivé en page 0.
- Changer de page remet le contenu en haut.

## Choix de performance

1. **Flux live en `ListView` + `BaseAdapter` + ViewHolder.** L'ancien
   `renderLogRows()` faisait `removeAllViews()` puis recréait jusqu'à 200
   `TextView` toutes les 500 ms. Désormais seules les ~15 lignes visibles sont
   bindées; le coût d'un rafraîchissement est constant quel que soit le débit de
   trames.

2. **Zéro allocation de drawable dans les chemins chauds.** Plutôt que de
   partager un `GradientDrawable` par catégorie entre plusieurs vues — ce qui
   pose un problème réel de `bounds` partagés entre lignes de hauteurs
   différentes — chaque ligne recyclée possède **son propre** `GradientDrawable`,
   créé une seule fois avec la vue. Le bind ne fait que `setColor()` /
   `setStroke()` dessus, avec des couleurs récupérées via `CategoryPalette` qui
   retourne des `Int` (aucune allocation). Même principe pour les lignes de la
   liste de fichiers (sélectionnée / non sélectionnée), les pastilles d'état et
   les segments de mode.

3. **Mises à jour en place partout.** Compteurs, chrono, statut, libellés et
   états de boutons passent par `setText` / `setEnabled` / mutation de drawable.
   Aucun écran n'est reconstruit pour refléter un changement d'état. Les deux
   fonds du gros bouton (teal et rouge) sont construits une fois à la
   construction de l'écran et simplement échangés.

4. **Copie du buffer sans allocation.** `LiveFeedBuffer.visible` est une
   `ArrayList` pré-dimensionnée à 200 que l'adapter garde par référence. Le flush
   fait `clear()` + `addAll()` sur le thread principal sous verrou, puis
   `notifyDataSetChanged()`. Plus de `toList()` toutes les 500 ms.

5. **Libellés de fichiers pré-calculés.** `formatBytes` / `formatTime` sont
   appelés une fois par fichier au chargement de la liste (`FileEntry`), jamais
   dans `getView()`.

6. **`setTextIsSelectable(true)` retiré du flux live** (il était appliqué à
   chaque ligne). Il ne reste que dans le lecteur CSV, comme demandé. Un appui
   long sur une ligne du flux la copie dans le presse-papiers.

7. Pas d'animation, pas d'élévation, pas de bitmap. Les `ListView` ont un
   sélecteur transparent, `overScrollMode = NEVER` et pas de barre de défilement,
   pour éviter le travail de dessin inutile.

8. Les références de vues d'un écran quitté sont mises à `null`
   (`mainViews`, `viewerViews`, `FilesController.release()`), donc aucune vue
   morte n'est retenue après un `setContentView`.

## Écarts par rapport à la spec

- **Découpage en 11 fichiers `ui/` au lieu des 7 indicatifs.** Trois fichiers
  supplémentaires (`FilesController.kt`, `LiveFeedBuffer.kt`,
  `CsvPageSource.kt`, `BackNavigation.kt`, `FileListAdapter.kt`) ont été
  extraits pour tenir la contrainte « MainActivity < ~500 lignes » avec une
  séparation état / écrans nette. La spec précise que le découpage est
  indicatif.
- **Drawables par vue plutôt que cache global par catégorie.** La spec suggérait
  un `GradientDrawable` par catégorie créé une seule fois. Partager une même
  instance entre plusieurs vues simultanément visibles de hauteurs différentes
  fait que la dernière `bounds` posée gagne, ce qui casse le rendu des lignes.
  La solution retenue (un drawable par vue recyclée, muté au bind) atteint le
  même objectif — zéro allocation dans `getView()` — sans ce défaut.
- **Padding horizontal d'écran à 16dp** au lieu des 18dp d'origine, pour rester
  sur la grille 4dp demandée.
- **Segments de mode sans `StateListDrawable`.** Ils utilisent un
  `GradientDrawable` mutable pour pouvoir changer d'état sans réallouer; ils
  n'ont donc pas de retour visuel « pressé ». La spec interdit les animations et
  le changement d'état actif est immédiat et visible.
- **`dernier : HH:mm`** dans le résumé, comme écrit dans la spec. La date
  complète reste visible sur chaque ligne de la liste, ce qui lève l'ambiguïté
  pour un fichier ancien.

## Correction incluse

`applyScreenPadding()` référençait `android.graphics.Insets` dans la branche
« avant Android 11 ». Or cette classe n'existe qu'à partir d'Android 10, alors
que `minSdk` vaut 26: sur Android 8 et 9 ce chemin lève un
`NoClassDefFoundError`. La branche Android 11+ est désormais isolée dans un
objet dédié (`SystemBarsApi30`) et la branche héritée n'utilise que les getters
dépréciés de `WindowInsets`, qui existent depuis l'API 20. Les gardes
`Build.VERSION` d'origine sont conservés à l'identique.

## Non vérifié

Il n'y a pas de SDK Android dans l'environnement de travail: le projet n'a pas
pu être compilé ni exécuté. La relecture a porté sur les imports, les
références de vues, la résolution des receivers implicites et la checklist de
non-régression, mais le rendu réel (métriques de hauteur, troncature des
libellés de boutons sur petit écran) reste à valider sur appareil.
