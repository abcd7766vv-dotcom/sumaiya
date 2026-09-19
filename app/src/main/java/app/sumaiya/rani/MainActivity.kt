package app.sumaiya.rani

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val reqProjection = 1001
    private val reqPerms = 1002
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("sr", Context.MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad * 2, pad, pad)

        val title = TextView(this)
        title.text = "সুমাইয়া রানী"
        title.textSize = 34f
        title.setTypeface(title.typeface, Typeface.BOLD)
        col.addView(title)

        val intro = TextView(this)
        intro.text = "শুরু করলে একটি ভাসমান 'সু' বাটন আসবে। যেকোনো অ্যাপে গিয়ে বাটন চেপে বলুন কী করতে চান। আমি স্ক্রিন দেখে গোল চিহ্ন ও তীর দিয়ে দেখিয়ে দেব কোথায় ট্যাপ করতে হবে।"
        intro.textSize = 16f
        intro.setPadding(0, pad / 2, 0, pad)
        col.addView(intro)

        val keyLabel = TextView(this)
        keyLabel.text = "OpenRouter API কী"
        col.addView(keyLabel)
        keyInput = EditText(this)
        keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyInput.hint = "sk-or-v1-..."
        keyInput.setText(prefs.getString("key", ""))
        col.addView(keyInput)

        val modelLabel = TextView(this)
        modelLabel.text = "মডেল (ছবি বুঝতে পারে এমন)"
        modelLabel.setPadding(0, pad / 2, 0, 0)
        col.addView(modelLabel)
        modelInput = EditText(this)
        modelInput.inputType = InputType.TYPE_CLASS_TEXT
        modelInput.setText(prefs.getString("model", "openai/gpt-4o-mini"))
        col.addView(modelInput)

        val startBtn = Button(this)
        startBtn.text = "শুরু করুন"
        startBtn.setOnClickListener { start() }
        col.addView(startBtn)

        val stopBtn = Button(this)
        stopBtn.text = "বন্ধ করুন"
        stopBtn.setOnClickListener {
            stopService(Intent(this, GuideService::class.java))
            status.text = "বন্ধ করা হয়েছে।"
        }
        col.addView(stopBtn)

        status = TextView(this)
        status.setPadding(0, pad, 0, 0)
        status.textSize = 16f
        col.addView(status)

        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)
    }

    private fun cleanKey(s: String): String? {
        return Regex("sk-or-[A-Za-z0-9_\\-]+").find(s)?.value
    }

    private fun start() {
        val key = cleanKey(keyInput.text.toString())
        if (key == null) {
            Toast.makeText(this, "OpenRouter কী ঠিক নেই। sk-or-v1- দিয়ে শুরু হওয়া কী দিন।", Toast.LENGTH_LONG).show()
            return
        }
        var model = modelInput.text.toString().trim()
        if (!Regex("^[A-Za-z0-9_.\\-]+/[A-Za-z0-9_.:\\-]+$").matches(model)) {
            model = "openai/gpt-4o-mini"
            modelInput.setText(model)
        }
        keyInput.setText(key)
        getSharedPreferences("sr", Context.MODE_PRIVATE).edit()
            .putString("key", key)
            .putString("model", model)
            .apply()

        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), reqPerms)
            return
        }
        afterPermissions()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqPerms) afterPermissions()
    }

    private fun afterPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'অন্য অ্যাপের ওপরে দেখানো' চালু করে ফিরে এসে আবার 'শুরু করুন' চাপুন।", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqProjection) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, GuideService::class.java)
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
            status.text = "চালু আছে। অন্য অ্যাপে গিয়ে ভাসমান 'সু' বাটন চাপুন।"
            moveTaskToBack(true)
        } else {
            status.text = "স্ক্রিন দেখার অনুমতি দেওয়া হয়নি।"
        }
    }
}
