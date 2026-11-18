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

## Gestes

- **Toucher l'orbe** : parler · pendant qu'il parle ou réfléchit : l'interrompre et reprendre la parole.
- **Appui long sur l'orbe** : mode mains libres (Nox réécoute après chaque réponse, l'écran reste allumé).
- **Appui long sur une bulle** : copier ou relire à voix haute.
- **✦** nouvelle conversation · **◉** modèles · **⚙** réglages.

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
  OrbView.kt        l'orbe animé (5 états)
  ModelStore.kt     catalogue, téléchargement (DownloadManager), import .litertlm
  ModelsDialog.kt / SettingsDialog.kt / Markdown.kt / Ui.kt / Prefs.kt / ChatStore.kt
```

Importer un autre modèle : n'importe quel `.litertlm` (Hugging Face › litert-community) via ◉ › Importer.
