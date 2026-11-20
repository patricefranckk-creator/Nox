# Nox — IA vocale 100 % locale

Nox est un assistant qui vit entièrement dans le téléphone : le modèle de langage, la reconnaissance vocale et la voix tournent sur l'appareil. Internet ne sert qu'une fois, pour télécharger le « cerveau ».

## Ce qui tourne où

| Brique | Techno | Matériel |
|---|---|---|
| Cerveau | Gemma 4 E2B (ou E4B) via **LiteRT-LM 0.18.1** | GPU Adreno + décodage spéculatif MTP, repli CPU automatique |
| Oreille | `SpeechRecognizer.createOnDeviceSpeechRecognizer` (fr-FR) | sur l'appareil, sans cloud |
| Voix | TextToSpeech, voix françaises hors-ligne uniquement, **en streaming** | Nox parle dès la 1re phrase générée |
| Interface | Kotlin pur, vues construites en code, **zéro dépendance** à part LiteRT-LM | visuel de Lia : neurone animé 60 fps |

## Construire l'APK sans PC (GitHub Actions)

1. Crée un dépôt GitHub vide (ex. `nox`).
2. Envoie tout le contenu de ce dossier à la racine du dépôt (le dossier `.github` compris).
3. Onglet **Actions** › « Construire l'APK Nox » se lance tout seul (sinon *Run workflow*).
4. Au bout de ~5 min : ouvre l'exécution › **Artifacts** › `Nox-apk` › dézippe › installe `Nox.apk`.

> AIDE / Code Assist ne savent pas embarquer les bibliothèques natives GPU de LiteRT-LM : la compilation se fait donc dans le cloud GitHub.

## Premier lancement

1. Autorise le micro (et les notifications pour suivre le téléchargement).
2. Le catalogue s'ouvre : **Gemma 4 E2B** (2,6 Go, conseillé) ou **Gemma 4 E4B** (3,65 Go, plus malin, plus lent). Wi-Fi conseillé.
3. À la fin du téléchargement, Nox se branche tout seul.
4. Si la reconnaissance vocale signale un pack manquant : Réglages Android › Google › Voix › Reconnaissance vocale hors connexion › Français.

## Le harnais (cœur verrouillé : Gemma série E)

Le modèle ne change pas : Gemma 4 E2B (vitesse) ou E4B (qualité). Tout le reste est le harnais.

- **Oreille Gemma** : ta voix va directement dans le modèle (16 kHz, fin de phrase détectée, bruit réduit). Il écrit ce qu'il a compris dans ton message. Touche le neurone quand tu as fini.
- **Vue** : appareil photo ou galerie (en bas à gauche), Gemma regarde la photo (texte, objets, documents…).
- **Bibliothèque (RAG hybride)** : documents texte, notes et anciennes conversations. Recherche par mots-clés (BM25, toujours dispo) + par le sens (EmbeddingGemma 2, à installer dans Menu › Modèles), fusionnées (RRF). Les extraits très pertinents sont glissés automatiquement avant la réponse ; sinon Gemma va chercher lui-même avec l'outil « bibliotheque ». **Par la voix aussi** : EmbeddingGemma 2 vectorise directement ta question parlée (10 premières secondes) et la compare aux extraits, sans attendre la transcription.
- **16 compétences** (appelées par Gemma) : heure, calculatrice exacte, minuteur/réveil, rappels (notifications), notes & listes, mémoire longue, bibliothèque, musique (jouer, pause, suivant…), appels & SMS préparés (contacts), itinéraire GPS, ouvrir une appli ou un réglage (wifi, bluetooth, son…), téléphone (lampe, volume, batterie), agenda (lire et ajouter), partager/copier/recherche web, **écran** (piloter n'importe quelle appli) et **notifications** (lire, répondre, ouvrir, effacer).
- **Mémoire** : longue (« retiens que… ») injectée dans chaque conversation ; épisodique (les échanges passés deviennent cherchables).
- **Partage vers Nox** : depuis n'importe quelle appli, partage un texte → l'ajouter à la bibliothèque ou en parler.
- **Performances** : consigne + outils + historique pré-calculés au chargement, réflexion désactivée, compactage du contexte, cascade GPU → CPU avec repli sans vue / sans oreille / sans outils plutôt que planter.

## Version 2.1 « Lia »

- **Fil façon Lia** : bleu nuit, pas de bulles, étiquettes TOI / NOX, copier / relire sous chaque message, marqueurs « dit / lu à voix haute ».
- **Conversation enrichie** : carte « Dans ta bibliothèque » (ce que Nox a relu), puces d'actions, relances suggérées à toucher, mémorisation proactive de ce que tu confies, salutation selon l'heure.
- **Freekipedia** : Menu › Importer Freekipedia accepte les fichiers `lia_maj_….db` et `lia_base_complete_….db` (même schéma que Lia, idempotent). Les passages sont revectorisés par EmbeddingGemma 2, en pause pendant que Nox parle.
- **Écoute longue** : patience réglable (1,2 / 2 / 3 / 5 s de silence), jusqu'à 3 min, découpée en clips de 30 s pour Gemma, compteur visible.
- **Jauges** : RAM, CPU de Nox + fréquences, GPU Adreno (si Android laisse lire), batterie (%, °C, mA), thermique, contexte, résumé des perfs.

## Version 2.2 « Lia, en vrai »

- **Le visuel de Lia, à l'identique** : écran de démarrage (neurone sur son halo, nom, version), en-tête avec le neurone cerclé qui ouvre le menu, état « prêt · modèle · GPU », bouton jauges et bouton voix ; fil avec la ligne verticale, TOI en orange, NOX en bleu ; barre du bas photo · galerie · « ou écris ici… » · micro.
- **Le neurone vivant** : il respire au repos, devient vert à l'écoute, bleu avec une orbite quand il réfléchit, ambré quand il parle, et réagit au volume de la voix.
- **Page vocale** : le grand neurone en plein écran, légende avec compteur, ce que Nox a entendu, bouton rond de retour.
- **Popups pro** : toutes les fenêtres sont des cartes ou des panneaux arrondis gris violacé, titres gras, boutons-pilules (bleu plein, contour, corail pour les actions destructives).
- **Menu façon Lia** : 3 favoris en tuiles (appui long pour les changer), puis Moi / Conversation / Connaissances / Réglages, et « Fermer l'app ».
- **Jauges façon Lia** : panneau qui descend sous l'en-tête, cartes à barre (RAM, Nox, CPU, GPU, batterie), « Détail RAM › » et « Journal › ».
- **Arrière-plan** : Nox garde le modèle chargé derrière une notification discrète (« Parler », « Quitter »).
- **Bouton power** : Nox est un assistant numérique ; appui long sur power → page vocale à l'écoute, même écran verrouillé.
- **Pilotage des applis** (accessibilité) et **lecture des notifications**, avec confirmation avant toute action sensible.
- **Centre des autorisations** au premier lancement (et Menu › Autorisations) : micro, notifications, contacts, agenda, pilotage, notifications, bouton power, batterie sans limite.

## Activer le bouton power

Menu › Autorisations › **Bouton power** ouvre « Assistant numérique par défaut » : choisis **Nox**, puis active « Appui long sur le bouton marche/arrêt » (sur HONOR : Paramètres › Assistance › Bouton marche/arrêt). 

> Android 13+ : pour une appli installée hors Play Store, « Pilotage des applis » et « Lire les notifications » sont d'abord grisés. Ouvre Infos de l'appli › ⋮ › **Autoriser les paramètres restreints**, puis réactive l'accès.

## Versions et journal

- **Numéro de version automatique** : `2.2.N`, où N est le numéro du build GitHub. Visible sur l'écran de démarrage et dans Menu › Aide & infos (avec date de build et commit).
- **Journal de bord** (Menu › Journal de bord, ou Jauges › Journal ›) : chargement du moteur essai par essai, temps avant le 1er mot, jetons/s, durée du RAG, chaque appel d'outil (paramètres, résultat, durée), mémoire libre et température à chaque étape, erreurs avec pile d'appel, plantages Java et raison de la dernière fermeture donnée par Android (y compris plantage natif GPU). Filtres Perfs / Erreurs / Moteur / Outils, résumé chiffré, mode détaillé, bouton Partager.

## Réglages utiles

- **Turbo MTP** : décodage spéculatif de Gemma 4, gros gain de vitesse sur GPU.
- **Contexte 2K / 4K / 8K** : 4K conseillé avec 8 Go de RAM. Quand la mémoire sature, Nox compacte la conversation tout seul.
- **Garde-fou** : si un démarrage GPU fait planter l'appli, le lancement suivant passe automatiquement en CPU.
- La conversation est sauvegardée et réinjectée au redémarrage : Nox garde le fil.

## Structure

```
app/src/main/java/com/application/nox/
  Nox.kt            le cœur (moteur, bibliothèque, compétences) qui survit à l'écran
  NoxService.kt     service de premier plan : Nox prêt en arrière-plan
  AssistActivity.kt appui long sur power → page vocale
  NoxAccess.kt      pilotage des applis (accessibilité)
  NoxNotifs.kt      lecture et réponse aux notifications
  MainActivity.kt   écran principal : en-tête, fil, barre du bas, écoute → réflexion → parole
  NeuronView.kt     le neurone de Lia animé (en-tête, démarrage, page vocale)
  VoicePage.kt      page vocale plein écran
  MenuSheet.kt      menu façon Lia (favoris + sections)
  GaugePanel        (Gauges.kt) panneau des jauges + mesures système
  NoxDialog.kt      popups et panneaux pro, boutons-pilules
  PermissionsDialog centre des autorisations
  TurnView.kt       un tour de parole sur le fil
  LlmEngine.kt      LiteRT-LM : cascade GPU+MTP → GPU → CPU, streaming, compactage du contexte
  VoiceIn.kt / VoiceOut.kt / AudioCapture.kt   oreilles et voix
  Library.kt        bibliothèque RAG hybride (BM25 + EmbeddingGemma 2 texte et voix, RRF, SQLite)
  Skills.kt         16 compétences (function calling) + mémoire longue
  ModelStore.kt     catalogue, téléchargement, import .litertlm
  ModelsDialog.kt / SettingsDialog.kt / SkillsDialog.kt / InfoDialogs.kt / Markdown.kt / Ui.kt / Prefs.kt / ChatStore.kt
```

## Catalogue (Menu › Modèles)

| Composant | Taille | Rôle |
|---|---|---|
| Gemma 4 E2B | 2,6 Go | cœur conseillé (rapide) |
| Gemma 4 E4B | 3,65 Go | cœur puissance max |
| EmbeddingGemma 2 | ~0,5 Go | recherche par le sens pour la bibliothèque |

Gemma 3n (E2B/E4B) fonctionne aussi : télécharge-le depuis ton navigateur (licence à accepter sur Hugging Face) puis Menu › Modèles › Importer.

