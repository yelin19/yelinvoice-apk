package com.yelinvoice.offline

import android.app.Activity
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var makeButton: Button
    private lateinit var playButton: Button
    private var lastOutput: File? = null
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = VoiceModelRepository(this)
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        buildScreen()
        downloadSelectedVoiceAtFirstLaunch()
    }

    private fun buildScreen() {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "အင်တာနက်မလိုဘဲ မြန်မာစာမှ အသံထုတ်ရန်"
            textSize = 22f
        }, matchWidth())
        root.addView(TextView(this).apply {
            text = "အသုံးပြုမည့် အသံ"
            textSize = 16f
            setPadding(0, pad, 0, 4)
        }, matchWidth())
        voicePicker = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, repository.voices.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voicePicker.adapter = adapter
        root.addView(voicePicker, matchWidth())

        textInput = EditText(this).apply {
            hint = "ဖတ်စေလိုသော မြန်မာစာသားကို ရေးပါ"
            minLines = 5
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setSingleLine(false)
        }
        root.addView(textInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = pad
            bottomMargin = pad
        })

        makeButton = Button(this).apply { text = "အသံထုတ်ရန်" }
        root.addView(makeButton, matchWidth())
        playButton = Button(this).apply { text = "ထုတ်ထားသောအသံကို နားထောင်ရန်"; isEnabled = false }
        root.addView(playButton, matchWidth())
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        root.addView(progress, matchWidth())
        status = TextView(this).apply {
            text = "ပထမအကြိမ်ဖွင့်ရာတွင် အသံမော်ဒယ်ကို ဒေါင်းလုဒ်လုပ်ပါမည်။ ထို့နောက် အင်တာနက်မလိုဘဲ အသုံးပြုနိုင်သည်။"
            textSize = 14f
            setPadding(0, 8, 0, 0)
        }
        root.addView(status, matchWidth())
        setContentView(root)

        makeButton.setOnClickListener { generateSpeech() }
        playButton.setOnClickListener { playOutput() }
    }

    private fun downloadSelectedVoiceAtFirstLaunch() {
        val selected = repository.voices.first().id
        if (repository.installed(selected)) {
            status.text = "Shoon အသံမော်ဒယ်ကို သိမ်းထားပြီးဖြစ်သည်။ အင်တာနက်မလိုဘဲ အသုံးပြုနိုင်သည်။"
            return
        }
        setBusy(true)
        status.text = "ပထမအကြိမ်တွင် Shoon အသံမော်ဒယ်ကို ဒေါင်းလုဒ်လုပ်နေပါသည်။ Wi-Fi အသုံးပြုရန် အကြံပြုပါသည်။"
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
                    playButton.isEnabled = true
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
        voicePicker.isEnabled = !busy
        textInput.isEnabled = !busy
        progress.isIndeterminate = busy
    }

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        player?.release()
        player = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
