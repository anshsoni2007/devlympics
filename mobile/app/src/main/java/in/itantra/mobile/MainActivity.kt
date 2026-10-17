package `in`.itantra.mobile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.function.BiConsumer

class MainActivity : Activity() {

    companion object {
        @JvmField
        @Volatile
        var testObserver: BiConsumer<String, JSONObject>? = null
    }

    private var web: WebView? = null
    private lateinit var transport: LocalTransport
    private lateinit var speech: SpeechEngine
    private var loaded = false
    private var splash: FrameLayout? = null
    private var gravitas: Typeface? = null

    private val commandReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            Log.i("LinC", "Broadcast command received: ${intent?.getStringExtra("action")}")
            handleIntent(intent)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = -0x1
        window.navigationBarColor = -0x1

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val decor = window.decorView
            var flags = decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            decor.systemUiVisibility = flags
        }

        gravitas = try {
            Typeface.createFromAsset(assets, "GravitasOne.ttf")
        } catch (e: Exception) {
            Typeface.create("serif", Typeface.BOLD)
        }

        try {
            MeshService.start(this)
            checkBatteryOptimizations()
        } catch (e: Exception) {
            Log.w("MainActivity", "MeshService start error: ${e.message}")
        }

        web = WebView(this)
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        splash = FrameLayout(this).apply {
            setBackgroundColor(-0x1000000)
            elevation = 100f
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, _ -> true }
        }
        root.addView(splash, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)

        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        WebView.setWebContentsDebuggingEnabled(true)

        web?.apply {
            setBackgroundColor(-0x1)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            addJavascriptInterface(Bridge(), "SWAR")
            addJavascriptInterface(Bridge(), "iTantra")
            addJavascriptInterface(Bridge(), "LinC")
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                    Log.d("WebViewConsole", "[${cm?.messageLevel()}] ${cm?.message()} (${cm?.sourceId()}:${cm?.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val reqUrl = request.url.toString()
                    if ("https://app.swar.local/" == reqUrl || "https://app.itantra.local/" == reqUrl) {
                        try {
                            return WebResourceResponse("text/html", "UTF-8", assets.open("index.html"))
                        } catch (ignored: IOException) {}
                    }
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }

                override fun onPageFinished(v: WebView, url: String) {
                    loaded = true
                    event("device", LocalTransport.json("name", Build.MODEL, "android", Build.VERSION.RELEASE))
                    transport.requestSelfP2pInfo()
                    speech.status()
                    handleIntent(intent)

                    web?.evaluateJavascript(
                        "(() => { const el = document.querySelector('.hero-brand-title'); if(!el) return '0,0,0,0'; el.style.opacity = '0'; const rect = el.getBoundingClientRect(); return rect.left + ',' + rect.top + ',' + rect.width + ',' + rect.height; })()"
                    ) { value ->
                        if (splash == null || splash?.parent == null) return@evaluateJavascript
                        try {
                            val clean = value.replace("\"", "")
                            val parts = clean.split(",")
                            val density = resources.displayMetrics.density
                            if (parts.size == 4) {
                                val cssLeft = parts[0].toFloat()
                                val cssTop = parts[1].toFloat()
                                val cssWidth = parts[2].toFloat()
                                val cssHeight = parts[3].toFloat()

                                val targetCenterX = (cssLeft + cssWidth / 2f) * density
                                val targetCenterY = (cssTop + cssHeight / 2f) * density
                                startSplashAnimation(targetCenterX, targetCenterY, cssHeight * density)
                            } else {
                                startSplashAnimation(resources.displayMetrics.widthPixels / 2f, 300f * density, 56f * density)
                            }
                        } catch (e: Exception) {
                            val density = resources.displayMetrics.density
                            startSplashAnimation(resources.displayMetrics.widthPixels / 2f, 300f * density, 56f * density)
                        }
                    }
                }
            }
        }

        transport = MeshService.getTransport(applicationContext)
        attachUiListener()

        speech = SpeechEngine(this, object : SpeechEngine.Listener {
            override fun event(type: String, data: JSONObject) {
                this@MainActivity.event(type, data)
            }

            override fun transcript(text: String, language: String) {
                Log.i("LinC", "Spoken transcript captured: '$text' ($language)")
                transport.sendText(text, language)
                event("sent", LocalTransport.json("text", text, "language", language))
            }
        })

        transport.discover()
        requestP2pPermissions()
        transport.registerP2pReceiver()

        val cmdFilter = android.content.IntentFilter("in.itantra.mobile.COMMAND")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commandReceiver, cmdFilter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, cmdFilter)
        }

        web?.loadUrl("https://app.swar.local/")
    }

    private fun startSplashAnimation(finalX: Float, finalY: Float, fontSizePx: Float) {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        val parentView = splash?.parent as? View
        val padLeft = parentView?.paddingLeft ?: 0
        val padTop = parentView?.paddingTop ?: 0
        val startX = (screenWidth / 2f) - padLeft
        val startY = (screenHeight / 2f) - padTop
        val texts = arrayOf("ಸ್ವರ", "ਸਵਰ", "स्वर", "ஸ்வர்", "స్వర్", "স্বর", "SWAR")
        val targetYsDp = floatArrayOf(-240f, -160f, -80f, 80f, 160f, 240f)
        val targetXsDp = floatArrayOf(-35f, 55f, -45f, 35f, -30f, 50f)
        val rotations = floatArrayOf(-8f, 5f, -10f, 4f, -6f, 8f)

        val density = resources.displayMetrics.density
        val textViews = arrayOfNulls<TextView>(texts.size)

        for (i in texts.indices) {
            val tv: TextView
            if (i == texts.size - 1) {
                tv = TextView(this)
                tv.setTextColor(-0x1)
                val span = SpannableString(texts[i])
                span.setSpan(ForegroundColorSpan(0xFF10B981.toInt()), span.length - 1, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                tv.text = span
            } else {
                tv = object : TextView(this) {
                    override fun onDraw(canvas: android.graphics.Canvas) {
                        val states = textColors
                        paint.style = android.graphics.Paint.Style.STROKE
                        paint.strokeWidth = 5f
                        setTextColor(-0x1)
                        super.onDraw(canvas)
                        paint.style = android.graphics.Paint.Style.FILL
                        setTextColor(-0x1000000)
                        super.onDraw(canvas)
                        setTextColor(states)
                    }
                }
                tv.text = texts[i]
            }

            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx)
            tv.typeface = gravitas
            tv.gravity = android.view.Gravity.CENTER
            tv.includeFontPadding = false
            tv.alpha = 0f
            tv.scaleX = 0.5f
            tv.scaleY = 0.5f

            val lp = FrameLayout.LayoutParams(-2, -2)
            splash?.addView(tv, lp)

            tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            tv.translationX = startX - (tv.measuredWidth / 2f)
            tv.translationY = startY - (tv.measuredHeight / 2f)

            textViews[i] = tv
        }

        // PHASE 1: Main pops in
        val mainTv = textViews[6] ?: return
        mainTv.alpha = 1f
        mainTv.scaleX = 0.9f
        mainTv.scaleY = 0.9f

        val p1_sX = ObjectAnimator.ofFloat(mainTv, "scaleX", 0.9f, 1.1f, 1.0f)
        val p1_sY = ObjectAnimator.ofFloat(mainTv, "scaleY", 0.9f, 1.1f, 1.0f)
        val pop = AnimatorSet().apply {
            playTogether(p1_sX, p1_sY)
            duration = 350
            interpolator = OvershootInterpolator(1.2f)
        }

        // PHASE 2: Translations burst out
        val burst = AnimatorSet()
        val burstAnims = arrayListOf<Animator>()
        for (i in 0 until 6) {
            val tv = textViews[i] ?: continue
            burstAnims.add(ObjectAnimator.ofFloat(tv, "alpha", 0f, 1f))
            burstAnims.add(ObjectAnimator.ofFloat(tv, "scaleX", 0.5f, 1.15f))
            burstAnims.add(ObjectAnimator.ofFloat(tv, "scaleY", 0.5f, 1.15f))
            val baseX = startX - (tv.measuredWidth / 2f)
            val baseY = startY - (tv.measuredHeight / 2f)
            burstAnims.add(ObjectAnimator.ofFloat(tv, "translationY", baseY, baseY + targetYsDp[i] * density))
            burstAnims.add(ObjectAnimator.ofFloat(tv, "translationX", baseX, baseX + targetXsDp[i] * density))
            burstAnims.add(ObjectAnimator.ofFloat(tv, "rotation", 0f, rotations[i]))
        }
        burst.playTogether(burstAnims)
        burst.duration = 600
        burst.interpolator = DecelerateInterpolator()

        // PHASE 3: Scale Up & Translations fade out
        val p3_sX_up = ObjectAnimator.ofFloat(mainTv, "scaleX", 1.0f, 1.25f)
        val p3_sY_up = ObjectAnimator.ofFloat(mainTv, "scaleY", 1.0f, 1.25f)
        val fadeTrans = AnimatorSet()
        val fades = arrayListOf<Animator>()
        for (i in 0 until 6) {
            val tv = textViews[i] ?: continue
            fades.add(ObjectAnimator.ofFloat(tv, "alpha", 1f, 0f))
        }
        fadeTrans.playTogether(fades)

        val outroPart1 = AnimatorSet().apply {
            playTogether(p3_sX_up, p3_sY_up, fadeTrans)
            duration = 350
            interpolator = AccelerateDecelerateInterpolator()
        }

        // PHASE 4: Scale Down & Translate & Delayed Fade out
        val p4_sX_down = ObjectAnimator.ofFloat(mainTv, "scaleX", 1.25f, 1.0f).apply { duration = 550 }
        val p4_sY_down = ObjectAnimator.ofFloat(mainTv, "scaleY", 1.25f, 1.0f).apply { duration = 550 }

        val currentTransX = startX - (mainTv.measuredWidth / 2f)
        val currentTransY = startY - (mainTv.measuredHeight / 2f)
        val endTransX = finalX - (mainTv.measuredWidth / 2f)
        val endTransY = finalY - (mainTv.measuredHeight / 2f)

        val p4_tX = ObjectAnimator.ofFloat(mainTv, "translationX", currentTransX, endTransX).apply { duration = 550 }
        val p4_tY = ObjectAnimator.ofFloat(mainTv, "translationY", currentTransY, endTransY).apply { duration = 550 }

        val bgFadeOut = ValueAnimator.ofObject(ArgbEvaluator(), -0x1000000, 0x00000000).apply {
            duration = 480
            addUpdateListener { a ->
                splash?.setBackgroundColor(a.animatedValue as Int)
            }
        }

        val colorMorph = ValueAnimator.ofObject(ArgbEvaluator(), -0x1, 0xFF111827.toInt()).apply {
            duration = 450
            addUpdateListener { a ->
                val c = a.animatedValue as Int
                val s = SpannableString("LinC.")
                s.setSpan(ForegroundColorSpan(c), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(ForegroundColorSpan(0xFF10B981.toInt()), 4, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                mainTv.text = s
            }
        }

        val outroPart2 = AnimatorSet().apply {
            playTogether(p4_sX_down, p4_sY_down, p4_tX, p4_tY, bgFadeOut, colorMorph)
            interpolator = AccelerateDecelerateInterpolator()
        }

        val fullSequence = AnimatorSet()
        val intro = AnimatorSet().apply { playSequentially(pop, burst) }
        val outro = AnimatorSet().apply {
            playSequentially(outroPart1, outroPart2)
            startDelay = 300
        }

        fullSequence.playSequentially(intro, outro)
        fullSequence.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                web?.evaluateJavascript("const el = document.querySelector('.hero-brand-title'); if(el) { el.style.opacity = '1'; el.style.visibility = 'visible'; } if (window.onBootCompleted) window.onBootCompleted();", null)
                splash?.let { sp ->
                    (sp.parent as? FrameLayout)?.removeView(sp)
                }
            }
        })
        fullSequence.start()
    }

    private fun event(type: String, data: JSONObject) {
        Log.d("LinC", "$type: $data")
        testObserver?.accept(type, data)
        runOnUiThread {
            if (loaded && web != null) {
                web?.evaluateJavascript("window.onNative(${JSONObject.quote(type)},$data)", null)
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun command(action: String, encoded: String) {
            runOnUiThread {
                try {
                    val data = JSONObject(encoded)
                    when (action) {
                        "host" -> transport.host()
                        "discover" -> transport.discover()
                        "discoverP2p" -> transport.discoverP2pPeers()
                        "connect" -> transport.connect(
                            data.optString("address"),
                            data.optInt("port", 8988),
                            data.optString("pin"),
                            data.optString("callsign")
                        )
                        "connectP2p" -> {
                            val cs = data.optString("callsign")
                            if (cs.isNotEmpty()) transport.callsign(cs)
                            transport.connectP2p(data.optString("p2pAddress"))
                        }
                        "connectBluetooth" -> transport.connectBluetooth(data.optString("btAddress"))
                        "discoverBluetooth" -> transport.discoverBluetoothPeers()
                        "fallbackBluetooth" -> transport.triggerBluetoothFallback()
                        "disconnectP2p" -> transport.disconnectP2pGroup()
                        "respondConnection" -> transport.respondRequest(data.optBoolean("accept", false))
                        "cancelRequest" -> transport.cancelRequest()
                        "callsign" -> transport.callsign(data.optString("callsign"))
                        "disconnect" -> {
                            speech.pause()
                            speech.resume()
                            transport.disconnect()
                        }
                        "send" -> transport.sendText(data.optString("text"), data.optString("language", "hi"))
                        "start" -> speech.start()
                        "release" -> speech.release()
                        "cancel" -> speech.cancelCapture()
                        "conversation" -> speech.conversation(data.optBoolean("enabled"))
                        "lock" -> speech.setLocked(data.optBoolean("locked", false))
                        "stopPlayback" -> speech.stopPlayback()
                        "language" -> speech.language(data.optString("language", "hi"))
                        "bitrate" -> transport.bitrate(data.optInt("bps", 2000))
                        "capabilities" -> speech.status()
                        "downloadModel" -> speech.downloadModel()
                        "downloadSelected" -> {
                            val arr = data.optJSONArray("languages")
                            val list = mutableListOf<String>()
                            if (arr != null) {
                                for (i in 0 until arr.length()) list.add(arr.optString(i))
                            }
                            speech.downloadSelected(list)
                        }
                        "speechSettings" -> {
                            try {
                                if (Build.VERSION.SDK_INT >= 34) {
                                    startActivity(Intent("android.speech.action.MANAGE_ON_DEVICE_SPEECH_RECOGNITION_MODELS"))
                                } else {
                                    startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                                }
                            } catch (e: Exception) {
                                try {
                                    startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                                } catch (e2: Exception) {
                                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                                }
                            }
                        }
                        "ttsSettings" -> startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                        "installTtsData" -> {
                            try {
                                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                            } catch (e: Exception) {
                                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                            }
                        }
                        "wifiSettings" -> startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                        "sendEmergency" -> transport.sendEmergency(
                            data.optString("text", "EMERGENCY ALERT"),
                            data.optString("language", "hi")
                        )
                        else -> throw IllegalArgumentException("Unknown action: $action")
                    }
                } catch (e: Exception) {
                    event("error", LocalTransport.json("message", e.message ?: "Action unavailable"))
                }
            }
        }
    }

    private val P2P_PERMISSION_REQUEST_CODE = 1001

    private fun requestP2pPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), P2P_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == P2P_PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Log.i("LinC", "Permissions granted by user; discovering peers via Wi-Fi and Bluetooth")
                transport.requestSelfP2pInfo()
                transport.discoverP2pPeers()
                transport.discoverBluetoothPeers()
            } else {
                Log.w("LinC", "Permissions partially denied by user")
            }
        }
    }

    override fun onBackPressed() {
        // Move task to background instead of finishing, keeping mesh connection alive
        moveTaskToBack(true)
    }

    override fun onPause() {
        super.onPause()
        speech.pause()
        MeshService.onAppBackgrounded()
    }

    override fun onStop() {
        super.onStop()
        MeshService.onAppBackgrounded()
    }

    override fun onResume() {
        super.onResume()
        MeshService.onAppForegrounded()
        speech.resume()
        attachUiListener()
        if (transport.isConnected) {
            event("connection", LocalTransport.json("state", "CONNECTED", "peer", transport.peer, "transport", transport.transportType))
        } else {
            transport.discover()
            transport.discoverP2pPeers()
            transport.discoverBluetoothPeers()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.getStringExtra("action") ?: return
        Log.i("LinC", "Handling intent action: $action")
        when (action) {
            "start" -> {
                speech.resume()
                speech.start()
            }
            "release" -> speech.release()
            "lock" -> {
                speech.resume()
                speech.setLocked(intent.getBooleanExtra("locked", true))
            }
            "conversation" -> {
                speech.resume()
                speech.conversation(intent.getBooleanExtra("enabled", true))
            }
            "language" -> intent.getStringExtra("language")?.let { speech.language(it) }
            "speak" -> {
                val text = intent.getStringExtra("text") ?: "परीक्षण सफल रहा"
                val lang = intent.getStringExtra("language") ?: "hi"
                speech.receive(ItpPacket.Decoded(java.util.UUID.randomUUID(), 1L, lang, text, false, 0, text.length, text.length))
            }
            "send" -> {
                val text = intent.getStringExtra("text") ?: "नमस्ते"
                val lang = intent.getStringExtra("language") ?: "hi"
                Log.i("LinC", "Sending message to peer from intent: '$text' ($lang)")
                transport.sendText(text, lang)
            }
            "connectP2p" -> {
                val p2p = intent.getStringExtra("p2pAddress")
                if (!p2p.isNullOrEmpty()) {
                    Log.i("LinC", "Connecting to P2P peer from intent: $p2p")
                    transport.connectP2p(p2p)
                }
            }
            "host" -> {
                Log.i("LinC", "Hosting from intent")
                transport.host()
            }
            "disconnectP2p" -> {
                Log.i("LinC", "Disconnecting P2P group from intent")
                transport.disconnectP2pGroup()
            }
            "connect" -> {
                val addr = intent.getStringExtra("address") ?: ""
                val port = intent.getIntExtra("port", 8988)
                val pin = intent.getStringExtra("pin") ?: ""
                val callsign = intent.getStringExtra("callsign") ?: ""
                if (addr.isNotEmpty()) {
                    Log.i("LinC", "Connecting to peer from intent: $addr:$port")
                    transport.connect(addr, port, pin, callsign)
                }
            }
            "emergency" -> {
                val text = intent.getStringExtra("text") ?: "EMERGENCY ALERT"
                val lang = intent.getStringExtra("language") ?: "hi"
                Log.i("LinC", "Sending emergency from intent: $text ($lang)")
                transport.sendEmergency(text, lang)
            }
            "connectBluetooth" -> {
                val bt = intent.getStringExtra("btAddress") ?: ""
                if (bt.isNotEmpty()) {
                    Log.i("LinC", "Connecting to Bluetooth peer from intent: $bt")
                    transport.connectBluetooth(bt)
                }
            }
            "discoverBluetooth" -> transport.discoverBluetoothPeers()
            "fallbackBluetooth" -> transport.triggerBluetoothFallback()
        }
    }

    override fun onDestroy() {
        loaded = false
        speech.close()
        try {
            unregisterReceiver(commandReceiver)
        } catch (ignored: Exception) {}
        MeshService.uiListener = null
        // DO NOT close transport or unregister P2P receiver here!
        // MeshService keeps the connection alive when app is closed or backgrounded.
        web?.apply {
            removeJavascriptInterface("iTantra")
            removeJavascriptInterface("LinC")
            destroy()
        }
        web = null
        super.onDestroy()
    }

    private fun attachUiListener() {
        MeshService.uiListener = object : LocalTransport.Listener {
            override fun event(type: String, data: JSONObject) {
                this@MainActivity.event(type, data)
            }

            override fun received(message: ItpPacket.Decoded) {
                if (message.emergency()) {
                    Log.i("SWAR", "🚨 EMERGENCY message from peer: '${message.text()}', dispatching to emergency pipeline")
                    event(
                        "received",
                        LocalTransport.json(
                            "text", message.text(),
                            "language", message.language(),
                            "sequence", message.sequence(),
                            "fecCorrected", message.correctedCodewords(),
                            "emergency", true
                        )
                    )
                    runOnUiThread { speech.receiveEmergency(message) }
                } else {
                    Log.i("SWAR", "MainActivity received message from peer: '${message.text()}', dispatching to TTS")
                    event(
                        "received",
                        LocalTransport.json(
                            "text", message.text(),
                            "language", message.language(),
                            "sequence", message.sequence(),
                            "fecCorrected", message.correctedCodewords()
                        )
                    )
                    runOnUiThread { speech.receive(message) }
                }
            }
        }
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w("SWAR", "Could not request ignore battery optimizations: ${e.message}")
                }
            }
        }
    }
}
