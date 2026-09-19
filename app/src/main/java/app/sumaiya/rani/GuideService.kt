package app.sumaiya.rani

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class GuideResult(val answer: String, val marks: List<Mark>)

class GuideService : Service(), TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var bg: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private val lock = Any()
    private var held: Image? = null
    private var bubble: TextView? = null
    private var marksView: MarksView? = null
    private var banner: TextView? = null
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var busy = false
    private val autoClear = Runnable {
        clearMarks()
        hideBanner()
    }

    private val systemPrompt = """তুমি 'সুমাইয়া রানী', মিষ্টি ও ভদ্র মেয়েলি স্বভাবের সহকারী। ব্যবহারকারীকে 'বস' বলে ডাকো। তোমাকে ফোনের স্ক্রিনের ছবি ও বসের প্রশ্ন দেওয়া হবে। বস কোথায় ট্যাপ করবেন তা দেখিয়ে দাও। শুধু একটি JSON দাও, আর কিছু নয়: {"answer":"বাংলায় ছোট, সহজ, আদুরে নির্দেশ (জোরে পড়ে শোনানো হবে, তাই মার্কডাউন নয়)","marks":[{"x":50,"y":50,"label":"ছোট বর্ণনা"}]}। x ও y হলো ছবির বাম-উপরের কোণ থেকে শতাংশে (০ থেকে ১০০) যেখানে ট্যাপ করতে হবে তার ঠিক কেন্দ্র। ক্রমানুসারে সাজাও, একবারে সর্বোচ্চ ৩টি ধাপ। নিশ্চিত না হলে marks খালি রাখো এবং answer-এ বলো।"""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null || projection != null) return START_NOT_STICKY
        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        try {
            setupProjection(code, data)
        } catch (e: Exception) {
            toast("স্ক্রিন ক্যাপচার চালু হয়নি: " + (e.message ?: ""))
            stopSelf()
            return START_NOT_STICKY
        }
        showBubble()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val chId = "sr"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(chId, "সুমাইয়া রানী", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = Intent(this, GuideService::class.java)
        stopIntent.action = "STOP"
        val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, chId)
            .setContentTitle("সুমাইয়া রানী চালু আছে")
            .setContentText("ভাসমান 'সু' বাটন চেপে জিজ্ঞেস করুন")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_delete, "বন্ধ করুন", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun setupProjection(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        val dm = DisplayMetrics()
        val disp = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        disp.getRealMetrics(dm)
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val scale = min(1f, 1280f / max(sw, sh).toFloat())
        val capW = max(2, (sw * scale).toInt())
        val capH = max(2, (sh * scale).toInt())

        val t = HandlerThread("sr-bg")
        t.start()
        bg = t
        val h = Handler(t.looper)
        bgHandler = h

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener(ImageReader.OnImageAvailableListener { rd -> onFrame(rd) }, h)
        reader = r
        vdisplay = p.createVirtualDisplay(
            "sr", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
    }

    private fun onFrame(r: ImageReader) {
        val n: Image? = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (n == null) return
        synchronized(lock) {
            held?.close()
            held = n
        }
    }

    private fun snapshot(): Bitmap? {
        return synchronized(lock) {
            val img = held
            if (img == null) {
                null
            } else {
                try {
                    val p = img.planes[0]
                    val buf = p.buffer
                    buf.rewind()
                    val rowPadding = p.rowStride - p.pixelStride * img.width
                    val full = Bitmap.createBitmap(img.width + rowPadding / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(buf)
                    Bitmap.createBitmap(full, 0, 0, img.width, img.height)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    // ---------- Floating button ----------
    private fun showBubble() {
        val w = wm ?: return
        val d = resources.displayMetrics.density
        val b = TextView(this)
        b.text = "সু"
        b.textSize = 18f
        b.setTextColor(Color.WHITE)
        b.gravity = Gravity.CENTER
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.OVAL
        shape.setColor(Color.parseColor("#2B3BE8"))
        shape.setStroke((2 * d).toInt(), Color.WHITE)
        b.background = shape
        val size = (64 * d).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (16 * d).toInt()
        lp.y = (220 * d).toInt()

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 12f || abs(dy) > 12f) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        w.updateViewLayout(v, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleTap()
                    true
                }
                else -> false
            }
        }
        w.addView(b, lp)
        bubble = b
    }

    private fun onBubbleTap() {
        if (marksView != null) {
            clearMarks()
            hideBanner()
            tts?.stop()
            return
        }
        if (busy) return
        busy = true
        tts?.stop()
        main.removeCallbacks(autoClear)
        showBanner("শুনছি বস… বলুন কী করতে চান")
        listen { q -> askAboutScreen(q) }
    }

    // ---------- Voice input ----------
    private fun listen(done: (String) -> Unit) {
        val fallback = "এই স্ক্রিনে আমি এখন কী করব?"
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(this)
        ) {
            done(fallback)
            return
        }
        var finished = false
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                if (finished) return
                finished = true
                main.post { rec.destroy() }
                recognizer = null
                done(fallback)
            }
            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                main.post { rec.destroy() }
                recognizer = null
                done(list?.firstOrNull() ?: fallback)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
        rec.startListening(i)
    }

    // ---------- Ask the model about the current screen ----------
    private fun askAboutScreen(q: String) {
        val prefs = getSharedPreferences("sr", Context.MODE_PRIVATE)
        val key = prefs.getString("key", "") ?: ""
        val model = prefs.getString("model", "openai/gpt-4o-mini") ?: "openai/gpt-4o-mini"
        clearMarks()
        hideBanner()
        bubble?.visibility = View.INVISIBLE
        main.postDelayed({
            val bmp = snapshot()
            bubble?.visibility = View.VISIBLE
            if (bmp == null) {
                finishWith(GuideResult("বস, স্ক্রিন এখনো ধরতে পারিনি। আরেকবার বাটন চাপুন।", emptyList()))
            } else {
                showBanner("দেখছি বস…")
                bgHandler?.post {
                    val result = try {
                        callModel(key, model, q, bmp)
                    } catch (e: Exception) {
                        GuideResult("বস, একটা সমস্যা হয়েছে: " + (e.message ?: ""), emptyList())
                    }
                    main.post { finishWith(result) }
                }
            }
        }, 500)
    }

    private fun callModel(key: String, model: String, q: String, bmp: Bitmap): GuideResult {
        if (!key.startsWith("sk-or-")) throw Exception("OpenRouter কী সেট করা নেই")
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 72, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", q))
        content.put(
            JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b64))
        )
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        messages.put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", model).put("messages", messages)

        val conn = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 90000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer " + key)
        conn.setRequestProperty("X-Title", "Sumaiya Rani")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = (if (code >= 400) conn.errorStream else conn.inputStream)
            ?: throw Exception("সার্ভার থেকে উত্তর আসেনি (কোড " + code + ")")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(text)
        if (code >= 400 || json.has("error")) {
            val msg = json.optJSONObject("error")?.optString("message") ?: ("কোড " + code)
            throw Exception(msg)
        }
        val reply = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")

        var answer = reply.trim()
        val marks = ArrayList<Mark>()
        val s = reply.indexOf('{')
        val e = reply.lastIndexOf('}')
        if (s >= 0 && e > s) {
            try {
                val o = JSONObject(reply.substring(s, e + 1))
                val a = o.optString("answer", "")
                if (a.isNotEmpty()) answer = a
                val arr = o.optJSONArray("marks") ?: JSONArray()
                val raw = ArrayList<Triple<Double, Double, String>>()
                for (i in 0 until min(arr.length(), 5)) {
                    val m = arr.getJSONObject(i)
                    raw.add(Triple(m.optDouble("x", -1.0), m.optDouble("y", -1.0), m.optString("label", "")))
                }
                val valid = raw.filter { it.first in 0.0..100.0 && it.second in 0.0..100.0 }
                val fractions = valid.isNotEmpty() && valid.all { it.first <= 1.0 && it.second <= 1.0 }
                val k = if (fractions) 1.0 else 100.0
                for (t in valid) {
                    marks.add(Mark((t.first / k).toFloat(), (t.second / k).toFloat(), t.third))
                }
            } catch (ex: Exception) {
                // answer stays as the raw reply
            }
        }
        if (answer.isEmpty()) answer = "বস, ঠিক বুঝতে পারলাম না। আবার চেষ্টা করবেন?"
        return GuideResult(answer, marks)
    }

    private fun finishWith(r: GuideResult) {
        busy = false
        val sb = StringBuilder(r.answer)
        r.marks.forEachIndexed { i, m ->
            sb.append("\n").append(i + 1).append(") ").append(m.label)
        }
        showBanner(sb.toString())
        if (r.marks.isNotEmpty()) showMarks(r.marks)
        speak(r.answer)
        main.removeCallbacks(autoClear)
        main.postDelayed(autoClear, 40000)
    }

    // ---------- Overlays ----------
    private fun showBanner(text: String) {
        val w = wm ?: return
        val d = resources.displayMetrics.density
        var b = banner
        if (b == null) {
            b = TextView(this)
            b.setTextColor(Color.WHITE)
            b.textSize = 16f
            val g = GradientDrawable()
            g.setColor(Color.parseColor("#E60F1E26"))
            g.cornerRadius = 16 * d
            b.background = g
            val pad = (14 * d).toInt()
            b.setPadding(pad, pad, pad, pad)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.BOTTOM
            lp.y = (24 * d).toInt()
            lp.horizontalMargin = 0.04f
            w.addView(b, lp)
            banner = b
        }
        b.text = text
    }

    private fun hideBanner() {
        val b = banner ?: return
        try {
            wm?.removeView(b)
        } catch (e: Exception) {
        }
        banner = null
    }

    private fun showMarks(marks: List<Mark>) {
        val w = wm ?: return
        clearMarks()
        val v = MarksView(this)
        v.marks = marks
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        w.addView(v, lp)
        marksView = v
    }

    private fun clearMarks() {
        val v = marksView ?: return
        try {
            wm?.removeView(v)
        } catch (e: Exception) {
        }
        marksView = null
    }

    // ---------- Voice output (female, cute) ----------
    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val r = t.setLanguage(Locale("bn", "BD"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale("bn", "IN"))
        }
        try {
            val all = t.voices
            if (all != null) {
                val bn = all.filter { it.locale.language == "bn" }
                val pick = bn.firstOrNull {
                    val n = it.name.lowercase()
                    n.contains("female") || n.contains("bnf") || n.contains("-f-")
                } ?: bn.firstOrNull()
                if (pick != null) t.setVoice(pick)
            }
        } catch (e: Exception) {
        }
        t.setPitch(1.3f)
        t.setSpeechRate(1.0f)
        speak("বস, আপনার জন্য এখন কী কাজ করে দেব? ভাসমান বাটন চেপে বলুন।")
    }

    private fun speak(text: String) {
        val t = tts ?: return
        val s = if (text.length > 600) text.substring(0, 600) else text
        t.speak(s, TextToSpeech.QUEUE_FLUSH, null, "sr")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        main.removeCallbacks(autoClear)
        clearMarks()
        hideBanner()
        try {
            bubble?.let { wm?.removeView(it) }
        } catch (e: Exception) {
        }
        bubble = null
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        try {
            vdisplay?.release()
        } catch (e: Exception) {
        }
        try {
            reader?.setOnImageAvailableListener(null, null)
        } catch (e: Exception) {
        }
        synchronized(lock) {
            held?.close()
            held = null
        }
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        bg?.quitSafely()
        super.onDestroy()
    }
}
