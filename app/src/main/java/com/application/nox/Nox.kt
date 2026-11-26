package com.application.nox

import android.content.Context

/**
 * Le cœur de Nox, indépendant de l'écran : moteur Gemma, bibliothèque, compétences, conversation.
 * Il survit à la fermeture de l'activité (service de premier plan) : Nox reste chargé et prêt,
 * l'appui long sur le bouton power le réveille instantanément.
 */
object Nox {
    lateinit var app: Context; private set
    lateinit var prefs: Prefs; private set
    lateinit var store: ModelStore; private set
    lateinit var chats: ChatStore; private set
    lateinit var library: Library; private set
    lateinit var skills: Skills; private set
    lateinit var engine: LlmEngine; private set

    /** Fil de la conversation en cours (partagé entre les activités). */
    val messages = mutableListOf<ChatMsg>()

    /** Écran actuellement au premier plan, s'il y en a un. */
    @Volatile var ui: MainActivity? = null
    @Volatile var loading = false

    val started get() = ::engine.isInitialized

    fun init(ctx: Context) {
        if (started) return
        app = ctx.applicationContext
        prefs = Prefs(app)
        NoxLog.init(app, "v${AppInfo.version(app)} (build ${AppInfo.build(app)})")
        NoxLog.verbose = prefs.verboseLog
        ResourceManager.init(app)
        store = ModelStore(app)
        chats = ChatStore(app)
        library = Library(app, store)
        skills = Skills(app, library)
        skills.onUsed = { label -> ui?.onToolUsed(label) }
        skills.onSources = { list -> ui?.onSources(list) }
        skills.askPermission = { perm -> ui?.requestPermissions(arrayOf(perm), MainActivity.REQ_PERM) }
        engine = LlmEngine(app, prefs, store, skills, library)
        messages.addAll(chats.load())
        NoxLog.i("APP", "Cœur de Nox initialisé (${messages.size} messages repris)")
    }

    /** « Fermer l'app » : on libère tout et on termine le processus. */
    fun shutdown() {
        Bubble.hide()
        NoxLog.i("APP", "Fermeture complète demandée")
        if (loading) prefs.loadInProgress = false
        try { chats.save(messages) } catch (_: Throwable) {}
        NoxService.stop(app)
        try { engine.release() } catch (_: Throwable) {}
        try { library.release() } catch (_: Throwable) {}
        NoxLog.flush()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
