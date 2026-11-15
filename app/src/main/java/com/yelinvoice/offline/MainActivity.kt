package com.yelinvoice.offline

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var repository: VoiceModelRepository
    private lateinit var voicePicker: Spinner
    private lateinit var textInput: EditText
    private lateinit var characterCount: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var makeButton: Button
    private lateinit var playButton: Button
    private var lastOutput: File? = null
    private var player: MediaPlayer? = null

    private val pageColor = Color.parseColor("#F5F6FA")
    private val surfaceColor = Color.WHITE
    private val inkColor = Color.parseColor("#20243A")
    private val mutedColor = Color.parseColor("#70758A")
    private val primaryColor = Color.parseColor("#58648F")
    private val borderColor = Color.parseColor("#E5E7EF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = pageColor
        window.navigationBarColor = pageColor
        val systemUiFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.decorView.systemUiVisibility = if (Build.VERSION.SDK_INT >= 26) {
            systemUiFlags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            systemUiFlags
        }
        repository = VoiceModelRepository(this)
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        buildScreen()
        downloadSelectedVoiceAtFirstLaunch()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(pageColor)
            isFillViewport = true
            clipToPadding = false
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(28))
        }
        scroll.addView(page, ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val brand = TextView(this).apply {
            text = "NARA MINI  ·  OFFLINE"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primaryColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.parseColor("#E9ECF8"), 18)
        }
        page.addView(brand, wrapWidth())
        page.addView(space(18), matchWidth())

        page.addView(label("စာသားမှ အသံဖိုင်သို့", 29f, inkColor, true), matchWidth())
        page.addView(space(8), matchWidth())
        page.addView(label(
            "မြန်မာစာကို ရေးထည့်ပြီး အသံဖိုင်အဖြစ် ဖန်တီးပါ။",
            15f,
            mutedColor
        ), matchWidth())
        page.addView(space(24), matchWidth())

        val voiceCard = card()
        voiceCard.addView(label("အသံရွေးချယ်ရန်", 16f, inkColor, true), matchWidth())
        voiceCard.addView(space(12), matchWidth())
        voicePicker = Spinner(this).apply {
            background = rounded(surfaceColor, 14, borderColor)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, repository.voices.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voicePicker.adapter = adapter
        voiceCard.addView(voicePicker, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52)
        ))
        page.addView(voiceCard, matchWidth())
        page.addView(space(16), matchWidth())

        val textCard = card()
        textCard.addView(label("ဖတ်စေလိုသော စာသား", 16f, inkColor, true), matchWidth())
        textCard.addView(space(10), matchWidth())
        textInput = EditText(this).apply {
            hint = "ဥပမာ - မင်္ဂလာပါ။ နေကောင်းပါသလား။"
            textSize = 17f
            setTextColor(inkColor)
            setHintTextColor(Color.parseColor("#A1A5B4"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            setSingleLine(false)
            setPadding(0, dp(4), 0, dp(4))
            background = null
        }
        textCard.addView(textInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(148)
        ))
        characterCount = label("0 / 2,000", 12f, mutedColor).apply {
            gravity = Gravity.END
        }
        textCard.addView(characterCount, matchWidth())
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                characterCount.text = "${s?.length ?: 0} / 2,000"
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        page.addView(textCard, matchWidth())
        page.addView(space(18), matchWidth())

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        makeButton = Button(this).apply {
            text = "အသံဖန်တီးရန်"
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            background = rounded(primaryColor, 16)
            minHeight = dp(56)
            stateListAnimator = null
            setPadding(dp(8), 0, dp(8), 0)
        }
        playButton = Button(this).apply {
            text = "နားထောင်ရန်"
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primaryColor)
            background = rounded(Color.parseColor("#E9ECF8"), 16)
            minHeight = dp(56)
            stateListAnimator = null
            setPadding(dp(6), 0, dp(6), 0)
            isEnabled = false
            alpha = 0.55f
        }
        actions.addView(makeButton, LinearLayout.LayoutParams(0, dp(56), 1.2f))
        actions.addView(spaceWidth(10), LinearLayout.LayoutParams(dp(10), 1))
        actions.addView(playButton, LinearLayout.LayoutParams(0, dp(56), 0.9f))
        page.addView(actions, matchWidth())
        page.addView(space(16), matchWidth())

        val statusCard = card().apply {
            background = rounded(Color.parseColor("#EEF0F8"), 20)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(primaryColor)
            indeterminateTintList = ColorStateList.valueOf(primaryColor)
        }
        statusCard.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(4)
        ))
        statusCard.addView(space(10), matchWidth())
        status = label(
            "ပထမဆုံးအကြိမ်တွင် အသံမော်ဒယ်ကို ဒေါင်းလုဒ်လုပ်ပါမည်။ ဒေါင်းလုဒ်ပြီးလျှင် အင်တာနက်မလိုဘဲ အသုံးပြုနိုင်ပါသည်။",
            13f,
            mutedColor
        )
        statusCard.addView(status, matchWidth())
        page.addView(statusCard, matchWidth())

        makeButton.setOnClickListener { generateSpeech() }
        playButton.setOnClickListener { playOutput() }
        setContentView(scroll)
    }

    private fun downloadSelectedVoiceAtFirstLaunch() {
        val selected = repository.voices.first().id
        if (repository.installed(selected)) {
            status.text = "Shoon အသံမော်ဒယ်ကို သိမ်းထားပြီးပါပြီ။ အင်တာနက်မလိုဘဲ အသုံးပြုနိုင်ပါသည်။"
            return
        }
        setBusy(true)
        status.text = "Shoon အသံမော်ဒယ်ကို ဒေါင်းလုဒ်လုပ်နေပါသည်။ Wi-Fi အသုံးပြုရန် အကြံပြုပါသည်။"
        worker.execute {
            try {
                repository.ensureInstalled(selected) { message -> mainHandler.post { status.text = message } }
                mainHandler.post { setBusy(false) }
            } catch (error: Exception) {
                mainHandler.post {
                    setBusy(false)
                    status.text = "ဒေါင်းလုဒ် မအောင်မြင်ပါ။ ${error.message ?: "အင်တာနက်နှင့် ဖုန်းသိုလှောင်မှုကို စစ်ဆေးပါ။"}"
                }
            }
        }
    }

    private fun generateSpeech() {
        val text = textInput.text.toString().trim()
        if (text.isEmpty()) {
            status.text = "အသံထုတ်ရန် မြန်မာစာသားကို အရင်ထည့်ပါ။"
            return
        }
        if (text.length > 2_000) {
            status.text = "စာသားရှည်လွန်းပါသည်။ ၂,၀၀၀ စာလုံးအောက်ဖြစ်အောင် ခွဲထည့်ပါ။"
            return
        }
        val selected = repository.voices.getOrNull(voicePicker.selectedItemPosition)?.id ?: "shoon"
        setBusy(true)
        status.text = "အသံမော်ဒယ်ကို စစ်ဆေးပြီး အသံဖိုင် ဖန်တီးနေပါသည်…"
        worker.execute {
            try {
                val files = repository.ensureInstalled(selected) { message -> mainHandler.post { status.text = message } }
                val output = File(files.folder, "last-speech.wav")
                OfflineTtsEngine(files).use { engine -> engine.synthesize(text, output) }
                mainHandler.post {
                    lastOutput = output
                    setBusy(false)
                    status.text = "အသံဖိုင် ဖန်တီးပြီးပါပြီ။ ဖုန်းထဲတွင် သိမ်းထားပါသည်။ (${output.name})"
                }
            } catch (error: Exception) {
                mainHandler.post {
                    setBusy(false)
                    status.text = "အသံဖိုင် ဖန်တီးမရပါ။ ${error.message ?: "အမှားတစ်ခု ဖြစ်ပွားခဲ့သည်။"}"
                }
            }
        }
    }

    private fun playOutput() {
        val output = lastOutput ?: return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(output.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepareAsync()
            }
            status.text = "ဖန်တီးထားသောအသံကို ဖွင့်နေပါသည်။"
        } catch (error: Exception) {
            status.text = "အသံဖိုင် ဖွင့်မရပါ။ ${error.message ?: "ဖိုင်ကို ရှာမတွေ့ပါ။"}"
        }
    }

    private fun setBusy(busy: Boolean) {
        makeButton.isEnabled = !busy
        makeButton.alpha = if (busy) 0.65f else 1f
        voicePicker.isEnabled = !busy
        textInput.isEnabled = !busy
        playButton.isEnabled = !busy && lastOutput != null
        playButton.alpha = if (playButton.isEnabled) 1f else 0.55f
        progress.isIndeterminate = busy
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = rounded(surfaceColor, 20, borderColor)
        elevation = dp(2).toFloat()
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = true
    }

    private fun rounded(color: Int, radiusDp: Int, outline: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        outline?.let { setStroke(dp(1), it) }
    }

    private fun space(heightDp: Int) = View(this).apply { minimumHeight = dp(heightDp) }
    private fun spaceWidth(widthDp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun wrapWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        player?.release()
        player = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
