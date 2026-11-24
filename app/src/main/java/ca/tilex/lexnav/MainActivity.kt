package ca.tilex.lexnav

// =============================================================================
//  LexNav Android — Navigateur LexOS pour Samsung (ti-lex)
//  Thème noir / écriture blanche / accents orange, tout arrondi.
//  Onglets, Claude intégré, bloqueur de pubs, favoris, téléchargements.
// =============================================================================

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class MainActivity : Activity() {

    companion object {
        const val ACCUEIL = "file:///android_asset/accueil.html"
        const val CLAUDE = "https://claude.ai/new"
        const val CODE_FICHIER = 42
        val ORANGE = Color.parseColor("#FF7A1A")
        val ORANGE_SOMBRE = Color.parseColor("#3A1C06")
        val GRIS_FOND = Color.parseColor("#0A0A0A")

        val MOTEURS = linkedMapOf(
            "DuckDuckGo" to "https://duckduckgo.com/?q={}",
            "Google" to "https://www.google.com/search?q={}",
            "Brave" to "https://search.brave.com/search?q={}",
            "Startpage" to "https://www.startpage.com/do/search?q={}"
        )

        val BLOQUES = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "adservice.google.com",
            "connect.facebook.net", "scorecardresearch.com", "taboola.com", "outbrain.com",
            "criteo.com", "adnxs.com", "amazon-adsystem.com", "hotjar.com", "quantserve.com",
            "moatads.com", "pubmatic.com", "rubiconproject.com", "applovin.com", "unityads.unity3d.com"
        )

        const val FAVORIS_DEFAUT = """[
            {"titre":"GitHub","url":"https://github.com"},
            {"titre":"YouTube","url":"https://m.youtube.com"},
            {"titre":"Wikipédia","url":"https://fr.m.wikipedia.org"},
            {"titre":"Météo","url":"https://meteo.gc.ca"}
        ]"""
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var police: Typeface
    private lateinit var conteneur: FrameLayout
    private lateinit var adresse: EditText
    private lateinit var progres: ProgressBar
    private lateinit var bCompteur: TextView
    private lateinit var bClaude: TextView
    private lateinit var panneauClaude: LinearLayout
    private lateinit var zoneClaude: FrameLayout
    private lateinit var plein: FrameLayout

    private var webClaude: WebView? = null
    private val onglets = mutableListOf<WebView>()
    private var actif = -1
    private val fermes = ArrayDeque<String>()
    private var fichierCallback: ValueCallback<Array<Uri>>? = null
    private var vuePleinEcran: View? = null
    private var rappelPleinEcran: WebChromeClient.CustomViewCallback? = null

    // ------------------------------------------------------------------ Outils
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun rond(fond: Int, bord: Int = Color.TRANSPARENT, rayon: Int = 18, epaisseur: Int = 1) =
        GradientDrawable().apply {
            setColor(fond)
            cornerRadius = dp(rayon).toFloat()
            if (bord != Color.TRANSPARENT) setStroke(dp(epaisseur), bord)
        }

    private fun texte(t: String, taille: Float = 15f, couleur: Int = Color.WHITE) =
        TextView(this).apply {
            text = t
            textSize = taille
            setTextColor(couleur)
            typeface = police
        }

    private fun bouton(t: String, taille: Float = 19f, action: () -> Unit) =
        texte(t, taille).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = rond(Color.TRANSPARENT, rayon = 16)
            setOnClickListener { action() }
        }

    private fun courant(): WebView? = onglets.getOrNull(actif)

    private fun moteurNom(): String = prefs.getString("moteur", "DuckDuckGo") ?: "DuckDuckGo"

    private fun bloqueurActif(): Boolean = prefs.getBoolean("bloqueur", true)

    private fun favoris(): JSONArray =
        try { JSONArray(prefs.getString("favoris", FAVORIS_DEFAUT)) } catch (e: Exception) { JSONArray(FAVORIS_DEFAUT) }

    private fun sauverFavoris(a: JSONArray) = prefs.edit().putString("favoris", a.toString()).apply()

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ Démarrage
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        prefs = getSharedPreferences("lexnav", Context.MODE_PRIVATE)
        police = try {
            Typeface.create(Typeface.createFromAsset(assets, "Montserrat.ttf"), Typeface.BOLD)
        } catch (e: Exception) {
            Typeface.DEFAULT_BOLD
        }
        CookieManager.getInstance().setAcceptCookie(true)
        construireInterface()

        val lien = intent?.dataString
        nouvelOnglet(lien)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { nouvelOnglet(it) }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    // ------------------------------------------------------------------ Interface
    private fun construireInterface() {
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // Barre d'adresse arrondie
        val haut = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(6), dp(6))
        }
        adresse = EditText(this).apply {
            hint = "Rechercher ou entrer une adresse"
            setHintTextColor(Color.parseColor("#888888"))
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = police
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            background = rond(GRIS_FOND, ORANGE_SOMBRE, 22)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE) {
                    aller(text.toString()); true
                } else false
            }
            setOnFocusChangeListener { v, focus ->
                v.background = rond(GRIS_FOND, if (focus) ORANGE else ORANGE_SOMBRE, 22)
            }
        }
        haut.addView(adresse, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        haut.addView(bouton("⟳", 20f) { courant()?.reload() },
            LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT))
        racine.addView(haut)

        // Barre de chargement orange arrondie
        progres = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = android.graphics.drawable.ClipDrawable(
                rond(ORANGE, rayon = 2), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            background = rond(Color.parseColor("#1A0D03"), rayon = 2)
            visibility = View.GONE
        }
        racine.addView(progres, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
            setMargins(dp(14), 0, dp(14), dp(2))
        })

        // Zone des pages (carte arrondie) + panneau Claude par-dessus
        val milieu = FrameLayout(this)
        conteneur = FrameLayout(this).apply {
            background = rond(Color.BLACK, rayon = 20)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        milieu.addView(conteneur, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(6), dp(2), dp(6), dp(2))
        })

        panneauClaude = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rond(Color.BLACK, ORANGE, 24, 1)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            visibility = View.GONE
            elevation = dp(12).toFloat()
        }
        val enteteClaude = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(6), dp(2))
        }
        enteteClaude.addView(texte("✳  Claude", 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        enteteClaude.addView(bouton("⤢", 18f) { ouvrirClaudeEnOnglet() },
            LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        enteteClaude.addView(bouton("✕", 18f) { cacherClaude() },
            LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        panneauClaude.addView(enteteClaude)
        zoneClaude = FrameLayout(this)
        panneauClaude.addView(zoneClaude, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        milieu.addView(panneauClaude, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.72).toInt(), Gravity.BOTTOM).apply {
            setMargins(dp(4), 0, dp(4), dp(2))
        })
        racine.addView(milieu, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Barre du bas
        val bas = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
        val p = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        bas.addView(bouton("←") { courant()?.let { if (it.canGoBack()) it.goBack() } }, p())
        bas.addView(bouton("→") { courant()?.let { if (it.canGoForward()) it.goForward() } }, p())
        bas.addView(bouton("⌂") { courant()?.loadUrl(ACCUEIL) }, p())
        bCompteur = bouton("1", 15f) { menuPages() }.apply {
            background = rond(Color.TRANSPARENT, Color.WHITE, 8, 2)
        }
        bas.addView(FrameLayout(this).apply {
            addView(bCompteur, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            setOnClickListener { menuPages() }
        }, p())
        bClaude = bouton("✳") { basculerClaude() }
        bas.addView(bClaude, p())
        bas.addView(bouton("☰") { menuPrincipal() }, p())
        racine.addView(bas)

        // Couche plein écran (vidéos)
        plein = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        val tout = FrameLayout(this)
        tout.addView(racine)
        tout.addView(plein)
        setContentView(tout)
    }

    // ------------------------------------------------------------------ WebView
    @SuppressLint("SetJavaScriptEnabled")
    private fun creerWebView(pourClaude: Boolean): WebView {
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        if (!pourClaude) w.addJavascriptInterface(Pont(), "LexNav")

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val hote = req.url.host?.lowercase() ?: return null
                if (bloqueurActif() && BLOQUES.any { hote == it || hote.endsWith(".$it") }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val sch = u.scheme?.lowercase() ?: return false
                if (sch in listOf("http", "https", "file", "about", "data", "blob", "javascript")) return false
                try {
                    val i = if (sch == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                            else Intent(Intent.ACTION_VIEW, u)
                    startActivity(i)
                } catch (e: Exception) {
                    if (sch == "intent") {
                        try {
                            val i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                            i.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) }
                        } catch (_: Exception) { }
                    }
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (view === courant()) majAdresse()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view === courant()) {
                    majAdresse()
                    progres.visibility = View.GONE
                }
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (view === courant()) {
                    progres.progress = p
                    progres.visibility = if (p < 100) View.VISIBLE else View.GONE
                }
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                vuePleinEcran?.let { callback.onCustomViewHidden(); return }
                vuePleinEcran = view
                rappelPleinEcran = callback
                plein.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                plein.visibility = View.VISIBLE
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }

            override fun onHideCustomView() {
                quitterPleinEcran()
            }

            override fun onShowFileChooser(
                webView: WebView, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams
            ): Boolean {
                fichierCallback?.onReceiveValue(null)
                fichierCallback = callback
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(params.createIntent(), CODE_FICHIER)
                    true
                } catch (e: Exception) {
                    fichierCallback = null
                    false
                }
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val nouvelle = creerWebView(false)
                onglets.add(nouvelle)
                montrer(onglets.size - 1)
                if (pourClaude) cacherClaude()
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = nouvelle
                resultMsg.sendToTarget()
                return true
            }
        }

        w.setDownloadListener(DownloadListener { url, agent, disposition, mime, _ ->
            if (!url.startsWith("http")) {
                toast("Ce téléchargement n'est pas supporté")
                return@DownloadListener
            }
            try {
                val nom = URLUtil.guessFileName(url, disposition, mime)
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mime)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("cookie", it) }
                    addRequestHeader("User-Agent", agent)
                    setTitle(nom)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nom)
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("⬇ Téléchargement : $nom")
            } catch (e: Exception) {
                toast("Erreur de téléchargement")
            }
        })
        return w
    }

    /** Petites infos lisibles par la page d'accueil (rien de sensible). */
    inner class Pont {
        @JavascriptInterface fun favoris(): String = this@MainActivity.favoris().toString()
        @JavascriptInterface fun moteur(): String = MOTEURS[moteurNom()] ?: MOTEURS.values.first()
        @JavascriptInterface fun nomMoteur(): String = moteurNom()
    }

    // ------------------------------------------------------------------ Onglets
    private fun nouvelOnglet(url: String?) {
        val w = creerWebView(false)
        onglets.add(w)
        montrer(onglets.size - 1)
        w.loadUrl(url ?: ACCUEIL)
    }

    private fun montrer(i: Int) {
        if (i !in onglets.indices) return
        conteneur.removeAllViews()
        val w = onglets[i]
        (w.parent as? ViewGroup)?.removeView(w)
        conteneur.addView(w, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        actif = i
        // petit effet de zoom à l'arrivée de la page
        w.scaleX = 0.94f; w.scaleY = 0.94f; w.alpha = 0f
        w.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
        majAdresse()
        majCompteur()
    }

    private fun estAccueil(u: String?) = u == null || u.startsWith("file:///android_asset/")

    private fun fermerOnglet(i: Int, message: Boolean = true) {
        if (i !in onglets.indices) return
        val w = onglets[i]
        val u = w.url
        if (!estAccueil(u)) {
            fermes.addLast(u!!)
            while (fermes.size > 25) fermes.removeFirst()
        }
        if (onglets.size == 1) {
            w.loadUrl(ACCUEIL)
            return
        }
        val etaitActif = i == actif
        onglets.removeAt(i)
        (w.parent as? ViewGroup)?.removeView(w)
        w.destroy()
        when {
            etaitActif -> montrer(minOf(i, onglets.size - 1))
            i < actif -> { actif -= 1; majCompteur() }
            else -> majCompteur()
        }
        if (message) toast("Page fermée")
    }

    private fun fermerAutres() {
        val garder = courant() ?: return
        for (i in onglets.indices.reversed()) if (onglets[i] !== garder) fermerOnglet(i, false)
        montrer(onglets.indexOf(garder))
    }

    private fun fermerTout() {
        for (i in onglets.indices.reversed()) fermerOnglet(i, false)
        toast("Toutes les pages sont fermées")
    }

    private fun rouvrir() {
        if (fermes.isEmpty()) toast("Aucune page à rouvrir") else nouvelOnglet(fermes.removeLast())
    }

    private fun majCompteur() {
        bCompteur.text = if (onglets.size > 99) ":D" else onglets.size.toString()
    }

    private fun majAdresse() {
        if (adresse.hasFocus()) return
        val u = courant()?.url
        adresse.setText(if (estAccueil(u)) "" else u)
    }

    // ------------------------------------------------------------------ Navigation
    private fun aller(t: String) {
        val s = t.trim()
        if (s.isEmpty()) return
        val url = when {
            s.contains("://") -> s
            s.startsWith("localhost") || (s.contains(".") && !s.contains(" ")) -> "https://$s"
            else -> (MOTEURS[moteurNom()] ?: MOTEURS.values.first())
                .replace("{}", URLEncoder.encode(s, "UTF-8"))
        }
        courant()?.loadUrl(url)
        adresse.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(adresse.windowToken, 0)
        courant()?.requestFocus()
    }

    // ------------------------------------------------------------------ Claude
    private fun basculerClaude() {
        if (panneauClaude.visibility == View.VISIBLE) cacherClaude() else montrerClaude()
    }

    private fun montrerClaude() {
        if (webClaude == null) {
            webClaude = creerWebView(true).also {
                zoneClaude.addView(it, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                it.loadUrl(CLAUDE)
            }
        }
        panneauClaude.visibility = View.VISIBLE
        panneauClaude.translationY = panneauClaude.height.toFloat().takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels * 0.72f)
        panneauClaude.animate().translationY(0f).setDuration(260).start()
        bClaude.setTextColor(ORANGE)
    }

    private fun cacherClaude() {
        if (panneauClaude.visibility != View.VISIBLE) return
        panneauClaude.animate().translationY(panneauClaude.height.toFloat()).setDuration(220)
            .withEndAction { panneauClaude.visibility = View.GONE }.start()
        bClaude.setTextColor(Color.WHITE)
    }

    private fun ouvrirClaudeEnOnglet() {
        cacherClaude()
        nouvelOnglet(webClaude?.url ?: CLAUDE)
    }

    // ------------------------------------------------------------------ Menus arrondis
    private fun act(nom: String, f: () -> Unit): Pair<String, () -> Unit> = Pair(nom, f)

    private fun menuRond(titre: String, items: List<Pair<String, () -> Unit>>, fermerApres: Boolean = true): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val boite = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rond(Color.BLACK, ORANGE_SOMBRE, 22)
            setPadding(dp(8), dp(14), dp(8), dp(10))
        }
        boite.addView(texte(titre, 13f, Color.parseColor("#999999")).apply {
            setPadding(dp(14), 0, dp(14), dp(8))
        })
        for ((nom, action) in items) {
            val ligne = texte(nom, 15f).apply {
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = rond(Color.TRANSPARENT, rayon = 14)
                setOnClickListener {
                    if (fermerApres) d.dismiss()
                    action()
                }
            }
            boite.addView(ligne)
        }
        val defile = ScrollView(this).apply { addView(boite) }
        d.setContentView(defile)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.88).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
        return d
    }

    private fun menuPages() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val boite = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rond(Color.BLACK, ORANGE_SOMBRE, 22)
            setPadding(dp(10), dp(14), dp(10), dp(10))
        }
        boite.addView(texte("${onglets.size} page(s) ouverte(s)", 13f, Color.parseColor("#999999")).apply {
            setPadding(dp(12), 0, dp(12), dp(8))
        })
        onglets.forEachIndexed { i, w ->
            val ligne = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rond(if (i == actif) Color.parseColor("#140A02") else GRIS_FOND,
                    if (i == actif) ORANGE else Color.parseColor("#1F0F04"), 16)
                setPadding(dp(14), dp(4), dp(4), dp(4))
            }
            val titre = if (estAccueil(w.url)) "Nouvel onglet" else (w.title ?: w.url ?: "Page")
            ligne.addView(texte(titre, 14f).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { d.dismiss(); montrer(i) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            ligne.addView(bouton("✕", 16f) { d.dismiss(); fermerOnglet(i); menuPages() },
                LinearLayout.LayoutParams(dp(44), dp(44)))
            boite.addView(ligne, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(3), 0, dp(3))
            })
        }
        val actions = listOf(
            act("+  Nouvel onglet") { nouvelOnglet(null) },
            act("Fermer les autres pages") { fermerAutres() },
            act("✕  Fermer toutes les pages") { fermerTout() },
            act("↺  Rouvrir la page fermée") { rouvrir() }
        )
        for ((nom, action) in actions) {
            boite.addView(texte(nom, 15f, if (nom.startsWith("+")) ORANGE else Color.WHITE).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { d.dismiss(); action() }
            })
        }
        d.setContentView(ScrollView(this).apply { addView(boite) })
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }

    private fun menuPrincipal() {
        val w = courant()
        val u = w?.url
        val estFavori = (0 until favoris().length()).any { favoris().getJSONObject(it).optString("url") == u }
        menuRond("LexNav", listOf(
            act("+  Nouvel onglet") { nouvelOnglet(null) },
            act("✳  Claude (panneau)") { montrerClaude() },
            act(if (estFavori) "★  Retirer des favoris" else "☆  Ajouter aux favoris") { basculerFavori() },
            act("★  Mes favoris") { menuFavoris() },
            act("🔍  Moteur : ${moteurNom()}") { menuMoteur() },
            act(if (bloqueurActif()) "🛡  Bloqueur de pubs : ACTIVÉ" else "🛡  Bloqueur de pubs : désactivé") {
                prefs.edit().putBoolean("bloqueur", !bloqueurActif()).apply()
                toast(if (bloqueurActif()) "Bloqueur activé" else "Bloqueur désactivé")
            },
            act("↗  Partager la page") { partager() },
            act("⧉  Copier l'adresse") { copierAdresse() },
            act("↺  Rouvrir la page fermée") { rouvrir() },
            act("🗑  Effacer historique et cookies") { effacerDonnees() },
            act("ⓘ  À propos") {
                menuRond("LexNav 1.0.0 · Android", listOf(
                    act("Navigateur de LexOS · ti-lex") {},
                    act("Produit du Québec ⚜") {}
                ))
                Unit
            }
        ))
    }

    private fun basculerFavori() {
        val w = courant() ?: return
        val u = w.url ?: return
        if (estAccueil(u)) { toast("Ouvre une page d'abord"); return }
        val a = favoris()
        val n = JSONArray()
        var retire = false
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("url") == u) retire = true else n.put(o)
        }
        if (!retire) n.put(JSONObject().put("titre", w.title ?: u).put("url", u))
        sauverFavoris(n)
        toast(if (retire) "Retiré des favoris" else "Ajouté aux favoris ★")
    }

    private fun menuFavoris() {
        val a = favoris()
        if (a.length() == 0) { toast("Aucun favori"); return }
        val items = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            act("★  " + o.optString("titre").take(40)) { courant()?.loadUrl(o.optString("url")) }
        }
        menuRond("Mes favoris", items)
    }

    private fun menuMoteur() {
        menuRond("Moteur de recherche", MOTEURS.keys.map { nom ->
            act((if (nom == moteurNom()) "●  " else "○  ") + nom) {
                prefs.edit().putString("moteur", nom).apply()
                toast("Moteur : $nom")
                if (estAccueil(courant()?.url)) courant()?.reload()
                Unit
            }
        })
    }

    private fun partager() {
        val u = courant()?.url ?: return
        if (estAccueil(u)) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, u)
        }, "Partager"))
    }

    private fun copierAdresse() {
        val u = courant()?.url ?: return
        if (estAccueil(u)) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("adresse", u))
        toast("Adresse copiée")
    }

    private fun effacerDonnees() {
        menuRond("Tout effacer ? (historique, cookies, connexions)", listOf(
            act("Oui, tout effacer") {
                onglets.forEach { it.clearHistory(); it.clearCache(true) }
                webClaude?.clearCache(true)
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                toast("Données effacées")
            },
            act("Annuler") {}
        ))
    }

    // ------------------------------------------------------------------ Système
    private fun quitterPleinEcran() {
        vuePleinEcran?.let { plein.removeView(it) }
        vuePleinEcran = null
        plein.visibility = View.GONE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        rappelPleinEcran?.onCustomViewHidden()
        rappelPleinEcran = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == CODE_FICHIER) {
            fichierCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fichierCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            vuePleinEcran != null -> quitterPleinEcran()
            panneauClaude.visibility == View.VISIBLE -> {
                val c = webClaude
                if (c != null && c.canGoBack()) c.goBack() else cacherClaude()
            }
            courant()?.canGoBack() == true -> courant()?.goBack()
            onglets.size > 1 -> fermerOnglet(actif, false)
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }
}
