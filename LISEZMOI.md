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

## Le harnais

- **Oreille Gemma (écoute native)** : avec Gemma 4 E2B/E4B, ta voix part directement dans le modèle (16 kHz, détection automatique de fin de phrase, suppression du bruit, 30 s max). Il entend le ton, les hésitations, et écrit ce qu'il a compris dans ta bulle. Touche l'orbe quand tu as fini pour envoyer tout de suite. Autres modèles : reconnaissance vocale du téléphone, automatiquement.
- **Compétences (⚡)** : outils réels que le modèle appelle tout seul — heure/date, calculatrice exacte, minuteur, réveil, notes et listes (courses…), mémoire longue (« retiens que… » / « oublie… »), lampe torche, volume, ouvrir une appli, préparer un événement d'agenda, état du téléphone. Une puce s'affiche à chaque action.
- **Mémoire longue** : les faits retenus sont réinjectés dans chaque conversation ; consultables et effaçables dans ⚡.
- **Optimisations** : consigne + outils + historique pré-calculés au chargement (premier mot plus rapide), réflexion désactivée, compactage du contexte, cascade GPU → CPU avec repli sans oreille si besoin.

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
  OrbView.kt        l'orbe animé (5 états)
  ModelStore.kt     catalogue, téléchargement (DownloadManager), import .litertlm
  ModelsDialog.kt / SettingsDialog.kt / Markdown.kt / Ui.kt / Prefs.kt / ChatStore.kt
```

## Catalogue (◉)

| Modèle | Taille | Remarque |
|---|---|---|
| Gemma 4 E2B | 2,6 Go | conseillé, turbo MTP |
| Gemma 4 E4B | 3,65 Go | plus intelligent, plus lent |
| Qwen3 1.7B | 0,9 Go | très rapide, réflexion coupée |
| Qwen3.5 0.8B | 1 Go | ultra léger, CPU |
| Qwen3.5 4B | 2,75 Go | costaud, CPU uniquement |
| Phi-4 mini | 3,9 Go | Microsoft, contexte 4K |

Autres modèles : colle un lien Hugging Face vers un `.litertlm` dans ◉ › « Depuis un lien Hugging Face », ou importe un fichier déjà présent sur le téléphone.
Les modèles marqués CPU ignorent le réglage GPU (leur démarrage GPU sature les téléphones 8 Go). Le contexte est automatiquement limité à ce que chaque fichier accepte.
