package com.application.nox

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ressources du portrait animé : import (le ZIP ou les PNG produits d'après le cahier des ressources),
 * contrôle d'alignement, découpe automatique de la zone qui change dans chaque variante (bords adoucis),
 * carte de lumière (veines, gemme, distance à la gemme) et stockage dans les fichiers privés de Nox.
 */
object PortraitAssets {

    const val REQ_IMPORT = 41
    const val W = 1024
    const val H = 1120

    /** Une variante découpée : son nom (« bouche_A »), sa place sur la toile, son image avec transparence. */
    class Layer(val name: String, val x: Int, val y: Int, val w: Int, val h: Int, val bitmap: Bitmap)

    class Loaded(val base: Bitmap, val light: Bitmap, val layers: Map<String, Layer>, val gemX: Float, val gemY: Float)

    fun dir(ctx: Context) = File(ctx.filesDir, "portrait")

    fun ready(ctx: Context) = File(dir(ctx), "manifest.json").exists() && File(dir(ctx), "base.png").exists()

    /** Noms des variantes importées (pour le diagnostic). */
    fun names(ctx: Context): List<String> = try {
        val m = JSONObject(File(dir(ctx), "manifest.json").readText()).getJSONArray("layers")
        (0 until m.length()).map { m.getJSONObject(it).getString("name") }
    } catch (_: Throwable) { emptyList() }

    fun pick(a: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "image/png"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        a.startActivityForResult(i, REQ_IMPORT)
    }

    /** Résultat du sélecteur : un ZIP et/ou des PNG. L'import tourne en fond puis affiche un bilan. */
    fun onResult(a: MainActivity, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        data.data?.let { if (it !in uris) uris += it }
        if (uris.isEmpty()) return
        a.toast("Import du portrait en cours…")
        Thread {
            val report = try { import(a, uris) } catch (t: Throwable) {
                NoxLog.e("PORTRAIT", "Import du portrait impossible", t)
                "Import impossible : ${t.message}"
            }
            a.runOnUiThread { a.portraitImported(report) }
        }.start()
    }

    // ------------------------------------------------------------ import

    private fun import(ctx: Context, uris: List<Uri>): String {
        val c = NoxLog.chrono()
        val files = LinkedHashMap<String, ByteArray>()
        for (u in uris) {
            val name = displayName(ctx, u).lowercase()
            ctx.contentResolver.openInputStream(u)?.use { input ->
                if (name.endsWith(".zip")) ZipInputStream(input).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val n = e.name.substringAfterLast('/').lowercase()
                        if (!e.isDirectory && n.endsWith(".png") && !n.startsWith(".")) files[key(n)] = z.readBytes()
                    }
                } else if (name.endsWith(".png")) files[key(name)] = input.readBytes()
            }
        }
        NoxLog.i("PORTRAIT", "Import : ${files.size} image(s) reçue(s) : ${files.keys.joinToString()}")
        val baseBytes = files["base"] ?: return "Il manque nox_base.png : c'est le portrait de référence, indispensable."
        val notes = ArrayList<String>()
        val base = decode(baseBytes, "base", notes)
        val bp = IntArray(W * H).also { base.getPixels(it, 0, W, 0, 0, W, H) }

        val out = dir(ctx)
        out.deleteRecursively(); out.mkdirs()
        save(base, File(out, "base.png"))

        // carte de lumière : R = veines, G = gemme, B = distance à la gemme le long du visage
        val veins = files["masque_veines"]?.let { gray(decode(it, "masque_veines", notes)) }
        val gem = files["masque_gemme"]?.let { gray(decode(it, "masque_gemme", notes)) }
        if (veins == null) notes += "nox_masque_veines.png absent : les veines ne s'animeront pas."
        if (gem == null) notes += "nox_masque_gemme.png absent : la gemme ne s'animera pas."
        var gx = 515f; var gy = 273f
        gem?.let { g ->
            var sx = 0.0; var sy = 0.0; var n = 0
            for (i in g.indices) if (g[i] > 128) { sx += i % W; sy += i / W; n++ }
            if (n > 0) { gx = (sx / n).toFloat(); gy = (sy / n).toFloat() }
        }
        var maxD = 1f
        if (veins != null) for (i in veins.indices) if (veins[i] > 40) {
            val dx = i % W - gx; val dy = i / W - gy
            maxD = max(maxD, sqrt(dx * dx + dy * dy))
        }
        val lp = IntArray(W * H)
        for (i in lp.indices) {
            val v = veins?.get(i) ?: 0
            val g = gem?.get(i) ?: 0
            val dx = i % W - gx; val dy = i / W - gy
            val d = if (v > 40) (sqrt(dx * dx + dy * dy) / maxD * 255).toInt().coerceIn(0, 255) else 0
            lp[i] = (0xFF shl 24) or (v shl 16) or (g shl 8) or d
        }
        save(Bitmap.createBitmap(lp, W, H, Bitmap.Config.ARGB_8888), File(out, "light.png"))

        // variantes : seule la zone qui change est gardée, bords adoucis
        val layers = JSONArray()
        val done = ArrayList<String>()
        for ((name, bytes) in files) {
            if (name == "base" || name.startsWith("masque_")) continue
            val v = decode(bytes, name, notes)
            val vp = IntArray(W * H).also { v.getPixels(it, 0, W, 0, 0, W, H) }
            val layer = cut(name, bp, vp, notes) ?: continue
            save(layer.bitmap, File(out, "$name.png"))
            layers.put(JSONObject().put("name", name).put("x", layer.x).put("y", layer.y).put("w", layer.w).put("h", layer.h))
            done += name
        }
        File(out, "manifest.json").writeText(JSONObject().put("layers", layers).put("gemX", gx).put("gemY", gy).toString())
        NoxLog.p("PORTRAIT", "Portrait importé en ${c.ms()} ms : ${done.size} variante(s) ${done.joinToString()} · ${notes.size} remarque(s)")
        notes.forEach { NoxLog.w("PORTRAIT", it) }
        return buildString {
            append("Portrait importé : ${done.size} variante(s)")
            if (done.isNotEmpty()) append(" (${done.joinToString(", ")})")
            append(".")
            if (veins != null || gem != null) append(" Lumière : ${listOfNotNull(veins?.let { "veines" }, gem?.let { "gemme" }).joinToString(" et ")}.")
            if (notes.isNotEmpty()) append("\n\nÀ vérifier :\n• " + notes.joinToString("\n• "))
        }
    }

    private fun key(file: String) = file.removeSuffix(".png").removePrefix("nox_")

    private fun displayName(ctx: Context, u: Uri): String = try {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: u.lastPathSegment.orEmpty()
    } catch (_: Throwable) { u.lastPathSegment.orEmpty() }

    private fun decode(bytes: ByteArray, name: String, notes: MutableList<String>): Bitmap {
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: error("nox_$name.png illisible")
        if (b.width == W && b.height == H) return b
        val ratio = b.width.toFloat() / b.height
        if (abs(ratio - W.toFloat() / H) > 0.01f) notes += "nox_$name.png fait ${b.width}×${b.height} au lieu de ${W}×${H} : proportions différentes, l'alignement risque d'être faux."
        else notes += "nox_$name.png fait ${b.width}×${b.height} : remis à ${W}×${H}."
        return Bitmap.createScaledBitmap(b, W, H, true)
    }

    private fun gray(b: Bitmap): IntArray {
        val p = IntArray(W * H).also { b.getPixels(it, 0, W, 0, 0, W, H) }
        for (i in p.indices) { val c = p[i]; p[i] = (((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)) / 3 }
        return p
    }

    /** Découpe ce qui change entre le portrait et la variante ; contrôle qu'ailleurs rien n'a bougé. */
    private fun cut(name: String, bp: IntArray, vp: IntArray, notes: MutableList<String>): Layer? {
        val a = FloatArray(W * H)
        var x0 = W; var y0 = H; var x1 = -1; var y1 = -1
        var outside = 0.0; var outsideN = 0
        for (i in bp.indices) {
            val p = bp[i]; val q = vp[i]
            val d = abs(((p shr 16) and 255) - ((q shr 16) and 255)) + abs(((p shr 8) and 255) - ((q shr 8) and 255)) + abs((p and 255) - (q and 255))
            val v = ((d - 12f) / 36f).coerceIn(0f, 1f)
            a[i] = v
            if (v > 0f) { val x = i % W; val y = i / W; x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y) }
            else { outside += d; outsideN++ }
        }
        if (x1 < 0) { notes += "nox_$name.png est identique au portrait : ignorée."; return null }
        val area = (x1 - x0 + 1) * (y1 - y0 + 1)
        if (area > W * H * 0.45f) { notes += "nox_$name.png change presque toute l'image (décalée ou régénérée ?) : ignorée, à refaire."; return null }
        val drift = if (outsideN > 0) outside / outsideN else 0.0
        if (drift > 4.0) notes += "nox_$name.png : le reste de l'image a légèrement changé (écart moyen ${"%.1f".format(drift)}) ; possible bord visible."
        val m = 10
        val cx0 = max(0, x0 - m); val cy0 = max(0, y0 - m); val cx1 = min(W - 1, x1 + m); val cy1 = min(H - 1, y1 + m)
        val cw = cx1 - cx0 + 1; val ch = cy1 - cy0 + 1
        // dilatation puis flou : la zone déborde un peu et se fond dans le portrait
        val local = FloatArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            var best = 0f
            for (dy in -3..3) for (dx in -3..3) {
                val gx = cx0 + x + dx; val gy = cy0 + y + dy
                if (gx in 0 until W && gy in 0 until H) best = max(best, a[gy * W + gx])
            }
            local[y * cw + x] = best
        }
        val soft = blur(blur(local, cw, ch, 3), cw, ch, 3)
        val px = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val al = (soft[y * cw + x] * 255).toInt().coerceIn(0, 255)
            px[y * cw + x] = (al shl 24) or (vp[(cy0 + y) * W + cx0 + x] and 0xFFFFFF)
        }
        val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, cw, 0, 0, cw, ch)
        NoxLog.i("PORTRAIT", "Variante $name : zone $cw×$ch à ($cx0, $cy0) · écart hors zone ${"%.1f".format(drift)}")
        return Layer(name, cx0, cy0, cw, ch, bmp)
    }

    private fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (k in -r..r) { val xx = x + k; if (xx in 0 until w) { s += src[y * w + xx]; n++ } }
            tmp[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (k in -r..r) { val yy = y + k; if (yy in 0 until h) { s += tmp[yy * w + x]; n++ } }
            out[y * w + x] = s / n
        }
        return out
    }

    private fun save(b: Bitmap, f: File) = f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    // ------------------------------------------------------------ chargement

    fun load(ctx: Context): Loaded? {
        if (!ready(ctx)) return null
        val d = dir(ctx)
        val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false }
        val base = BitmapFactory.decodeFile(File(d, "base.png").path, opt) ?: return null
        val light = BitmapFactory.decodeFile(File(d, "light.png").path, opt) ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val m = JSONObject(File(d, "manifest.json").readText())
        val arr = m.getJSONArray("layers")
        val layers = LinkedHashMap<String, Layer>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.getString("name")
            val b = BitmapFactory.decodeFile(File(d, "$name.png").path, opt) ?: continue
            layers[name] = Layer(name, o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"), b)
        }
        return Loaded(base, light, layers, m.optDouble("gemX", 515.0).toFloat(), m.optDouble("gemY", 273.0).toFloat())
    }
}
