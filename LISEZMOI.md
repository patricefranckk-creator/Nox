# Nox — IA vocale 100 % locale

Nox est un assistant qui vit entièrement dans le téléphone : le modèle de langage, la reconnaissance vocale et la voix tournent sur l'appareil. Internet ne sert qu'une fois, pour télécharger le « cerveau ».

## Ce qui tourne où

| Brique | Techno | Matériel |
|---|---|---|
| Cerveau | Gemma 4 E2B (ou E4B) via **LiteRT-LM 0.18.1** | GPU Adreno + décodage spéculatif MTP, repli CPU automatique |
| Oreille | `SpeechRecognizer.createOnDeviceSpeechRecognizer` (fr-FR) | sur l'appareil, sans cloud |
| Voix | TextToSpeech, voix françaises hors-ligne uniquement, **en streaming** | Nox parle dès la 1re phrase générée |
| Interface | Kotlin pur, vues construites en code, **zéro dépendance** à part LiteRT-LM | orbe animé 60 fps |

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

- **Oreille Gemma** : ta voix va directement dans le modèle (16 kHz, fin de phrase détectée, bruit réduit). Il écrit ce qu'il a compris dans ta bulle. Touche l'orbe quand tu as fini.
- **Vue 📷** : bouton ＋ pour joindre une photo, Gemma la regarde (texte, objets, documents…).
- **Bibliothèque (RAG hybride)** : documents texte, notes et anciennes conversations. Recherche par mots-clés (BM25, toujours dispo) + par le sens (EmbeddingGemma 2, à installer dans ◉), fusionnées (RRF). Les extraits très pertinents sont glissés automatiquement avant la réponse ; sinon Gemma va chercher lui-même avec l'outil « bibliotheque ».
- **14 compétences** (appelées par Gemma) : heure, calculatrice exacte, minuteur/réveil, rappels (notifications), notes & listes, mémoire longue, bibliothèque, musique (jouer, pause, suivant…), appels & SMS préparés (contacts), itinéraire GPS, ouvrir une appli ou un réglage (wifi, bluetooth, son…), téléphone (lampe, volume, batterie), agenda, partager/copier/recherche web.
- **Mémoire** : longue (« retiens que… ») injectée dans chaque conversation ; épisodique (les échanges passés deviennent cherchables).
- **Partage vers Nox** : depuis n'importe quelle appli, partage un texte → l'ajouter à la bibliothèque ou en parler.
- **Performances** : consigne + outils + historique pré-calculés au chargement, réflexion désactivée, compactage du contexte, cascade GPU → CPU avec repli sans vue / sans oreille / sans outils plutôt que planter.

## Gestes

- **Toucher l'orbe** : parler · pendant qu'il parle ou réfléchit : l'interrompre et reprendre la parole.
- **Appui long sur l'orbe** : mode mains libres (Nox réécoute après chaque réponse, l'écran reste allumé).
- **Appui long sur une bulle** : copier ou relire à voix haute.
- **✦** nouvelle conversation · **⚡** compétences & mémoire · **◉** modèles · **⚙** réglages.

## Réglages utiles

- **Turbo MTP** : décodage spéculatif de Gemma 4, gros gain de vitesse sur GPU.
- **Contexte 2K / 4K / 8K** : 4K conseillé avec 8 Go de RAM. Quand la mémoire sature, Nox compacte la conversation tout seul.
- **Garde-fou** : si un démarrage GPU fait planter l'appli, le lancement suivant passe automatiquement en CPU.
- La conversation est sauvegardée et réinjectée au redémarrage : Nox garde le fil.

## Structure

```
app/src/main/java/com/application/nox/
  MainActivity.kt   orchestration écoute → réflexion → parole
  LlmEngine.kt      LiteRT-LM : cascade GPU+MTP → GPU → CPU, streaming, compactage du contexte
  VoiceIn.kt        reconnaissance vocale sur l'appareil + repli
  VoiceOut.kt       synthèse vocale en flux, nettoyage du markdown
  AudioCapture.kt   oreille native : micro 16 kHz + détection de fin de parole
  Library.kt        bibliothèque RAG hybride (BM25 + EmbeddingGemma 2, fusion RRF, SQLite)
  Skills.kt         14 compétences (function calling) + mémoire longue
  ReminderReceiver  rappels en notification
  MathEval.kt       calculatrice exacte
  OrbView.kt        l'orbe animé (5 états)
  ModelStore.kt     catalogue, téléchargement (DownloadManager), import .litertlm
  ModelsDialog.kt / SettingsDialog.kt / Markdown.kt / Ui.kt / Prefs.kt / ChatStore.kt
```

## Catalogue (◉)

| Composant | Taille | Rôle |
|---|---|---|
| Gemma 4 E2B | 2,6 Go | cœur conseillé (rapide) |
| Gemma 4 E4B | 3,65 Go | cœur puissance max |
| EmbeddingGemma 2 | ~0,5 Go | recherche par le sens pour la bibliothèque |

Gemma 3n (E2B/E4B) fonctionne aussi : télécharge-le depuis ton navigateur (licence à accepter sur Hugging Face) puis ◉ › Importer.

