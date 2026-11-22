package com.application.nox

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Les mains de Nox : service d'accessibilité qui lit l'écran de n'importe quelle appli
 * et y agit (toucher, écrire, faire défiler, retour, accueil…). Rien n'est envoyé hors du téléphone.
 */
class NoxAccess : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        NoxLog.i("ECRAN", "Service d'accessibilité connecté : Nox peut piloter les applis")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName && pkg != "com.android.systemui") lastApp = pkg
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Bubble.hide()
        instance = null
        NoxLog.i("ECRAN", "Service d'accessibilité déconnecté")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ================================================================ lecture

    private data class El(val node: AccessibilityNodeInfo, val label: String, val kind: String)

    private var elements: List<El> = emptyList()

    private fun root(): AccessibilityNodeInfo? =
        rootInActiveWindow ?: windows.firstOrNull { it.isActive }?.root ?: windows.firstOrNull()?.root

    /** Résumé numéroté de l'écran : ce qu'on y lit et ce qu'on peut toucher. */
    fun readScreen(): String {
        val r = root() ?: return "Écran illisible pour le moment."
        val pkg = r.packageName?.toString().orEmpty()
        val els = ArrayList<El>()
        val texts = ArrayList<String>()
        walk(r, els, texts, 0)
        elements = els
        val app = appLabel(pkg)
        val sb = StringBuilder("Appli à l'écran : $app.")
        if (pkg == packageName) sb.append(" (C'est Nox lui-même : ouvre d'abord l'appli voulue avec « ouvrir ».)")
        if (texts.isNotEmpty()) sb.append("\nTextes : ").append(texts.distinct().take(40).joinToString(" | ") { it.take(80) })
        if (els.isNotEmpty()) {
            sb.append("\nÉléments : ")
            els.take(MAX_ELEMENTS).forEachIndexed { i, e -> sb.append("\n[${i + 1}] ${e.kind} « ${e.label.take(60)} »") }
        }
        return sb.toString().take(MAX_CHARS)
    }

    private fun walk(n: AccessibilityNodeInfo?, els: MutableList<El>, texts: MutableList<String>, depth: Int) {
        if (n == null || depth > 40 || !n.isVisibleToUser) return
        val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: n.hintText?.toString() ?: "")
            .replace('\n', ' ').trim()
        val kind = when {
            n.isEditable -> "champ"
            n.isCheckable -> if (n.isChecked) "case cochée" else "case"
            n.isClickable || n.isLongClickable -> "bouton"
            n.isScrollable -> "liste"
            else -> null
        }
        if (kind != null && (label.isNotEmpty() || kind == "champ")) {
            els += El(n, label.ifEmpty { n.viewIdResourceName?.substringAfter('/') ?: "sans nom" }, kind)
        } else if (label.isNotEmpty() && n.childCount == 0) texts += label
        for (i in 0 until n.childCount) walk(n.getChild(i), els, texts, depth + 1)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg.ifEmpty { "inconnue" } }

    // ================================================================ actions

    private fun find(target: String?, index: Int?): El? {
        if (elements.isEmpty()) readScreen()
        if (index != null && index in 1..elements.size) return elements[index - 1]
        val q = Text.fold(target ?: return null).trim()
        if (q.isEmpty()) return null
        return elements.firstOrNull { Text.fold(it.label) == q }
            ?: elements.firstOrNull { Text.fold(it.label).contains(q) }
            ?: run {
                // Texte simple non cliquable : on remonte jusqu'à un parent cliquable
                val r = root() ?: return null
                r.findAccessibilityNodeInfosByText(target).firstNotNullOfOrNull { n ->
                    var p: AccessibilityNodeInfo? = n
                    while (p != null && !p.isClickable) p = p.parent
                    p?.let { El(it, target, "bouton") }
                }
            }
    }

    fun labelOf(index: Int?): String? = index?.let { elements.getOrNull(it - 1)?.label }

    fun click(target: String?, index: Int?, long: Boolean): String {
        val e = find(target, index) ?: return "Je ne trouve pas « ${target ?: index} » à l'écran."
        var n: AccessibilityNodeInfo? = e.node
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        while (n != null && !(if (long) n.isLongClickable else n.isClickable)) n = n.parent
        val ok = n?.performAction(action) == true || tapCenter(e.node)
        return if (ok) settle("Touché « ${e.label} ».") else "Le toucher sur « ${e.label} » n'a pas marché."
    }

    /** Dernier recours : un vrai geste au centre de l'élément. */
    private fun tapCenter(node: AccessibilityNodeInfo): Boolean {
        val b = Rect().also { node.getBoundsInScreen(it) }
        if (b.isEmpty) return false
        val path = android.graphics.Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun type(text: String, target: String?, index: Int?): String {
        val field = when {
            target != null || index != null -> find(target, index)?.node
            else -> root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: elements.firstOrNull { it.kind == "champ" }?.node
                ?: run { readScreen(); elements.firstOrNull { it.kind == "champ" }?.node }
        } ?: return "Aucun champ de saisie à l'écran."
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) settle("Texte saisi.") else "Ce champ refuse la saisie."
    }

    fun scroll(down: Boolean): String {
        val r = root() ?: return "Écran illisible."
        val list = findScrollable(r) ?: return "Rien à faire défiler ici."
        val ok = list.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        return if (ok) settle(if (down) "Défilé vers le bas." else "Défilé vers le haut.") else "Impossible d'aller plus loin."
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) findScrollable(n.getChild(i))?.let { return it }
        return null
    }

    fun global(what: String): String {
        val (action, label) = when (what) {
            "retour" -> GLOBAL_ACTION_BACK to "Retour."
            "accueil" -> GLOBAL_ACTION_HOME to "Écran d'accueil."
            "recents" -> GLOBAL_ACTION_RECENTS to "Applis récentes."
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS to "Volet des notifications ouvert."
            "reglages_rapides" -> GLOBAL_ACTION_QUICK_SETTINGS to "Réglages rapides ouverts."
            "verrouiller" -> GLOBAL_ACTION_LOCK_SCREEN to "Écran verrouillé."
            "capture" -> GLOBAL_ACTION_TAKE_SCREENSHOT to "Capture d'écran faite."
            else -> return "Action inconnue : $what"
        }
        return if (performGlobalAction(action)) settle(label, read = what !in setOf("verrouiller", "capture")) else "Android a refusé : $label"
    }

    /** Laisse l'interface se poser, puis renvoie le nouvel écran pour que Gemma enchaîne. */
    private fun settle(done: String, read: Boolean = true): String {
        Thread.sleep(SETTLE_MS)
        return if (read) "$done\n${readScreen()}" else done
    }

    companion object {
        @Volatile var instance: NoxAccess? = null; private set
        @Volatile var lastApp: String? = null
        private const val MAX_ELEMENTS = 45
        private const val MAX_CHARS = 3200
        private const val SETTLE_MS = 900L

        val enabled get() = instance != null

        fun isOn(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }

        private val SENSITIVE = Regex("envoy|send|payer|paiement|acheter|achat|commander|virement|supprim|effac|delete|publier|poster|confirmer|valider|souscri|abonner|appeler")

        fun isSensitive(label: String) = SENSITIVE.containsMatchIn(Text.fold(label))
    }
}
