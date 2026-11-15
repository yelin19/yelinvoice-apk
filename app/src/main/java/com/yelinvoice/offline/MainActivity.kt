package com.yelinvoice.offline

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.app.ProgressDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var modelRepository: VoiceModelRepository
    private lateinit var historyRepository: AudioHistoryRepository
    private lateinit var screenFrame: FrameLayout
    private lateinit var navigation: BottomNavigationView

    private var currentTab = TAB_CREATE
    private var selectedVoiceId = "shoon"
    private var draftText = ""
    private var playbackSpeed = 1.0f
    private var busy = false
    private var progressPercent = 0
    private var player: MediaPlayer? = null
    private var lastOutput: AudioRecord? = null
    private var pendingExport: File? = null
    private var downloadDialog: ProgressDialog? = null

    private var voicePicker: MaterialAutoCompleteTextView? = null
    private var textInput: TextInputEditText? = null
    private var makeButton: MaterialButton? = null
    private var listenButton: MaterialButton? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var statusMessage = "အသံရွေးပြီး စာသားထည့်ပါ။ ပထမဆုံးအသုံးပြုချိန်တွင် voice model ဒေါင်းလုဒ်လုပ်ပါမည်။"

    private val prefs by lazy { getSharedPreferences("yelinvoice-settings", MODE_PRIVATE) }
    private val primaryColor by lazy { getColorResource(R.color.brand_primary) }
    private val inkColor by lazy { getColorResource(R.color.brand_on_surface) }
    private val mutedColor by lazy { getColorResource(R.color.brand_on_surface_variant) }
    private val surfaceColor by lazy { getColorResource(R.color.brand_surface) }
    private val pageColor by lazy { getColorResource(R.color.brand_background) }
    private val outlineColor by lazy { getColorResource(R.color.brand_outline) }
    private val errorColor by lazy { getColorResource(R.color.brand_error) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.statusBarColor = pageColor
            window.navigationBarColor = pageColor
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

            modelRepository = VoiceModelRepository(this)
            historyRepository = AudioHistoryRepository(this)

            selectedVoiceId = prefs.getString("selected_voice", "shoon") ?: "shoon"
            if (modelRepository.voices.none { it.id == selectedVoiceId }) {
                selectedVoiceId = modelRepository.voices.firstOrNull()?.id ?: "shoon"
            }
            playbackSpeed = prefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f)
            lastOutput = historyRepository.all().firstOrNull()

            buildShell()
            renderPage(TAB_CREATE)
        } catch (error: Throwable) {
            android.util.Log.e("YelinvoiceOffline", "App startup failed", error)
            showStartupFailure(error)
        }
    }

    private fun showStartupFailure(error: Throwable) {
        val details = TextView(this).apply {
            text = "App စတင်ရာတွင် အမှားတစ်ခု ဖြစ်ပွားခဲ့သည်။\n\n${error.javaClass.simpleName}: ${error.message ?: "အသေးစိတ်မရှိပါ"}\n\nဒီစာမျက်နှာကို screenshot ရိုက်ပြီး ပို့ပေးပါ။"
            textSize = 16f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setTextIsSelectable(true)
        }
        setContentView(details)
    }

    private fun buildShell() {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageColor)
        }
        screenFrame = FrameLayout(this)
        shell.addView(screenFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        navigation = BottomNavigationView(this).apply {
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            menu.add(Menu.NONE, TAB_CREATE, Menu.NONE, "ဖန်တီးမည်").setIcon(R.drawable.ic_create)
            menu.add(Menu.NONE, TAB_HISTORY, Menu.NONE, "အသံဖိုင်များ").setIcon(R.drawable.ic_library)
            menu.add(Menu.NONE, TAB_SETTINGS, Menu.NONE, "ဆက်တင်များ").setIcon(R.drawable.ic_settings)
            setOnItemSelectedListener { item ->
                renderPage(item.itemId)
                true
            }
        }
        shell.addView(navigation, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(shell)
        navigation.selectedItemId = TAB_CREATE
    }

    private fun renderPage(tab: Int) {
        currentTab = tab
        voicePicker = null
        textInput = null
        makeButton = null
        listenButton = null
        statusText = null
        progressBar = null
        when (tab) {
            TAB_HISTORY -> renderHistoryPage()
            TAB_SETTINGS -> renderSettingsPage()
            else -> renderCreatePage()
        }
    }

    private fun newPage(): LinearLayout {
        screenFrame.removeAllViews()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(pageColor)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        scroll.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        screenFrame.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return column
    }

    private fun renderCreatePage() {
        val page = newPage()
        addHeader(page, "စာသားမှ အသံဖိုင်သို့", "အင်တာနက်မလိုဘဲ မြန်မာနှင့် အင်္ဂလိပ်စာကို အသံထုတ်ပါ။")

        card(page) {
            addView(label("အသံရွေးချယ်ရန်", 16f, inkColor, true), matchWidth())
            addView(space(10), matchWidth())
            val voiceLayout = TextInputLayout(this@MainActivity).apply {
                hint = "Voice pack"
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            }
            val voices = modelRepository.voices
            val current = voices.firstOrNull { it.id == selectedVoiceId } ?: voices.first()
            val picker = MaterialAutoCompleteTextView(this@MainActivity).apply {
                inputType = android.text.InputType.TYPE_NULL
                keyListener = null
                setSimpleItems(voices.map { it.label }.toTypedArray())
                setText(current.label, false)
                setOnItemClickListener { _, _, position, _ ->
                    voices.getOrNull(position)?.let { voice ->
                        selectedVoiceId = voice.id
                        prefs.edit().putString("selected_voice", voice.id).apply()
                    }
                }
            }
            voicePicker = picker
            voiceLayout.addView(picker, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(voiceLayout, matchWidth())
            addView(space(8), matchWidth())
            addView(label("${current.repository} · ${current.id}", 12f, mutedColor), matchWidth())
        }

        card(page) {
            addView(label("ဖတ်စေလိုသော စာသား", 16f, inkColor, true), matchWidth())
            addView(space(10), matchWidth())
            val textLayout = TextInputLayout(this@MainActivity).apply {
                hint = "မြန်မာ သို့မဟုတ် English စာသား"
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }
            val edit = TextInputEditText(this@MainActivity).apply {
                setText(draftText)
                textSize = 17f
                minLines = 5
                maxLines = 10
                gravity = Gravity.TOP or Gravity.START
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            textInput = edit
            edit.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    draftText = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            textLayout.addView(edit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
            ))
            addView(textLayout, matchWidth())
        }

        addSpeedControl(page)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val createButton = actionButton("အသံဖန်တီးရန်", filled = true).apply {
            setOnClickListener { generateSpeech() }
        }
        makeButton = createButton
        val latestButton = actionButton("နောက်ဆုံးအသံ", filled = false).apply {
            isEnabled = lastOutput != null
            alpha = if (isEnabled) 1f else 0.55f
            setOnClickListener { lastOutput?.let { playAudio(it.file) } }
        }
        listenButton = latestButton
        actions.addView(createButton, LinearLayout.LayoutParams(0, dp(52), 1.15f))
        actions.addView(spaceWidth(10), LinearLayout.LayoutParams(dp(10), 1))
        actions.addView(latestButton, LinearLayout.LayoutParams(0, dp(52), 0.95f))
        page.addView(actions, matchWidth())
        page.addView(space(14), matchWidth())

        val status = cardView()
        val statusColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(primaryColor)
            visibility = if (busy) View.VISIBLE else View.GONE
            isIndeterminate = false
            this.progress = progressPercent
        }
        progressBar = progress
        statusColumn.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(4)
        ))
        statusColumn.addView(space(8), matchWidth())
        val currentStatus = label(statusMessage, 13f, mutedColor)
        statusText = currentStatus
        statusColumn.addView(currentStatus, matchWidth())
        status.addView(statusColumn)
        page.addView(status, matchWidth())
        applyBusyState()
    }

    private fun renderHistoryPage() {
        val page = newPage()
        val records = historyRepository.all()
        addHeader(page, "အသံဖိုင်များ", "ဖန်တီးထားသောအသံများကို ပြန်နားထောင်၊ သိမ်း၊ မျှဝေ သို့မဟုတ် ဖျက်နိုင်ပါသည်။")
        card(page) {
            addView(label("သိမ်းထားသောအသံ ${records.size} ခု", 16f, inkColor, true), matchWidth())
            addView(space(4), matchWidth())
            addView(label("ဒီစာရင်းကို ဖုန်း၏ app သီးသန့်သိုလှောင်ခန်းမှာ သိမ်းထားပါသည်။", 13f, mutedColor), matchWidth())
        }
        addSpeedControl(page)
        if (records.isEmpty()) {
            card(page) {
                addView(label("အသံဖိုင် မရှိသေးပါ", 17f, inkColor, true), matchWidth())
                addView(space(6), matchWidth())
                addView(label("ဖန်တီးမည် တက်ဘ်မှာ စာသားထည့်ပြီး အသံဖန်တီးပါ။", 14f, mutedColor), matchWidth())
            }
        } else {
            records.forEach { record -> addHistoryCard(page, record) }
        }
    }

    private fun addHistoryCard(page: LinearLayout, record: AudioRecord) {
        card(page) {
            val preview = record.text.replace('\n', ' ').trim()
            addView(label(preview, 16f, inkColor, true).apply { maxLines = 3 }, matchWidth())
            addView(space(7), matchWidth())
            val voice = modelRepository.voices.firstOrNull { it.id == record.voiceId }?.label ?: record.voiceId
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(record.createdAt))
            addView(label("$voice · $date", 12f, mutedColor), matchWidth())
            addView(space(12), matchWidth())

            val firstRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            firstRow.addView(actionButton("နားထောင်", false).apply {
                setOnClickListener { playAudio(record.file) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            firstRow.addView(spaceWidth(8), LinearLayout.LayoutParams(dp(8), 1))
            firstRow.addView(actionButton("ဖုန်းထဲသိမ်း", false).apply {
                setOnClickListener { exportAudio(record.file) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(firstRow, matchWidth())
            addView(space(8), matchWidth())

            val secondRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            secondRow.addView(actionButton("မျှဝေ", false).apply {
                setOnClickListener { shareAudio(record.file) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            secondRow.addView(spaceWidth(8), LinearLayout.LayoutParams(dp(8), 1))
            secondRow.addView(actionButton("ဖျက်", false, errorColor).apply {
                setOnClickListener { confirmDelete(record) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(secondRow, matchWidth())
        }
    }

    private fun renderSettingsPage() {
        val page = newPage()
        addHeader(page, "ဆက်တင်များ", "အသံ pack များကို စီမံပြီး အသံအသစ်များ ထပ်ထည့်ပါ။")

        card(page) {
            addView(label("လက်ရှိ Hugging Face model", 16f, inkColor, true), matchWidth())
            addView(space(6), matchWidth())
            addView(label("yelin201875/Yelinvoice", 14f, primaryColor, true), matchWidth())
            addView(label("ဒီ repository မှ လေးမျိုးသော ONNX voice pack များကို တစ်ခုချင်း ဒေါင်းလုဒ်လုပ်နိုင်ပါသည်။ Model တစ်ခုကို ဒေါင်းလုဒ်ပြီးလျှင် အင်တာနက်မလိုဘဲ အသုံးပြုနိုင်ပါသည်။", 13f, mutedColor), matchWidth())
            addView(space(8), matchWidth())
            addView(label("မှတ်ချက် — လက်ရှိ Yelinvoice repository ထဲက voice weight များသည် Nara Mini v1 voice လေးမျိုးနှင့် တူညီပါသည်။ ကိုယ်ပိုင်အသံအသစ်အတွက် ONNX pack နှင့် voice_catalog.json ကို ထည့်ပေးရပါမည်။", 12f, mutedColor), matchWidth())
        }

        card(page) {
            addView(label("Hugging Face မှ အသံအသစ်ထည့်ရန်", 16f, inkColor, true), matchWidth())
            addView(space(8), matchWidth())
            val repoLayout = TextInputLayout(this@MainActivity).apply {
                hint = "Public model repository URL"
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }
            val repoField = TextInputEditText(this@MainActivity).apply {
                setText(prefs.getString("catalog_url", "https://huggingface.co/yelin201875/Yelinvoice"))
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
            repoLayout.addView(repoField, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(repoLayout, matchWidth())
            addView(space(10), matchWidth())
            addView(actionButton("Repository မှ voice_catalog.json ယူရန်", true).apply {
                setOnClickListener {
                    val value = repoField.text?.toString()?.trim().orEmpty()
                    prefs.edit().putString("catalog_url", value).apply()
                    fetchCatalog(value)
                }
            }, matchWidth())
            addView(space(8), matchWidth())
            addView(actionButton("JSON ဖိုင်မှ အသံထည့်ရန်", false).apply {
                setOnClickListener { openCatalogPicker() }
            }, matchWidth())
            addView(space(8), matchWidth())
            addView(label("အသံအသစ်ထည့်ရန် Hugging Face repository root မှာ checksum ပါသော voice_catalog.json တင်ထားရန်၊ သို့မဟုတ် ဖုန်းထဲက JSON ဖိုင်ကို ရွေးရန် လိုအပ်ပါသည်။", 12f, mutedColor), matchWidth())
        }

        val voices = modelRepository.voices
        card(page) {
            addView(label("Voice packs", 16f, inkColor, true), matchWidth())
            addView(space(5), matchWidth())
            addView(label("အသံတစ်ခုချင်းကို ဒေါင်းလုဒ်၊ ဖယ်ရှားပြီး နောက်မှ ပြန်ဒေါင်းလုဒ်လုပ်နိုင်ပါသည်။", 13f, mutedColor), matchWidth())
        }
        voices.forEach { voice ->
            val installed = modelRepository.installed(voice.id)
            card(page) {
                addView(label(voice.label, 16f, inkColor, true), matchWidth())
                addView(space(4), matchWidth())
                addView(label("${voice.repository} · ${voice.id}", 12f, mutedColor), matchWidth())
                addView(space(6), matchWidth())
                addView(label(if (installed) "ဖုန်းထဲတွင် ရှိသည် · အင်တာနက်မလို" else "မဒေါင်းရသေး · ဒေါင်းလုဒ်ပြီးမှ offline သုံးနိုင်သည်", 13f, if (installed) primaryColor else mutedColor), matchWidth())
                addView(space(8), matchWidth())
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                if (installed) {
                    row.addView(actionButton("အသံဖယ်ရှားရန်", false, errorColor).apply {
                        setOnClickListener { confirmRemoveVoice(voice.id, voice.label) }
                    }, LinearLayout.LayoutParams(0, dp(46), 1f))
                } else {
                    row.addView(actionButton("ဒေါင်းလုဒ်လုပ်ရန်", true).apply {
                        setOnClickListener { downloadVoice(voice.id) }
                    }, LinearLayout.LayoutParams(0, dp(46), 1f))
                }
                addView(row, matchWidth())
            }
        }
    }

    private fun addSpeedControl(page: LinearLayout) {
        card(page) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(label("အသံအမြန်နှုန်း", 16f, inkColor, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val speedValue = label(speedText(playbackSpeed), 14f, primaryColor, true)
            row.addView(speedValue, wrapWidth())
            addView(row, matchWidth())
            val slider = Slider(this@MainActivity).apply {
                valueFrom = 0.5f
                valueTo = 2.0f
                stepSize = 0.25f
                value = playbackSpeed.coerceIn(valueFrom, valueTo)
            }
            slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    playbackSpeed = value
                    speedValue.text = speedText(value)
                    prefs.edit().putFloat("playback_speed", value).apply()
                    applyPlaybackSpeed()
                }
            }
            addView(slider, matchWidth())
            addView(label("ဖွင့်နားထောင်ချိန်တွင်သာ အမြန်နှုန်း ပြောင်းပါသည်။ မူရင်း WAV ဖိုင်ကို မပြင်ပါ။", 12f, mutedColor), matchWidth())
        }
    }

    private fun generateSpeech() {
        if (busy) return
        val text = draftText.trim()
        if (text.isEmpty()) {
            showStatus("အသံထုတ်ရန် စာသားကို အရင်ထည့်ပါ။")
            return
        }
        val selected = modelRepository.voices.firstOrNull { it.id == selectedVoiceId }?.id
            ?: modelRepository.voices.first().id
        busy = true
        setSpeechProgress(1, "အသံမော်ဒယ်ကို စစ်ဆေးပြီး အသံဖိုင် ဖန်တီးနေပါသည်… 1%")
        applyBusyState()
        worker.execute {
            var temporary: File? = null
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                val hasEnglish = text.any { it in 'A'..'Z' || it in 'a'..'z' }
                if (hasEnglish) {
                    val nltkDirectory = prepareEnglishResources()
                    Python.getInstance().getModule("nara_bridge")
                        .callAttr("configure_english_data", nltkDirectory.absolutePath)
                }
                val files = modelRepository.ensureInstalled(selected) { event ->
                    val fraction = if (event.totalBytes > 0L) event.downloadedBytes.toDouble() / event.totalBytes else 0.0
                    val percent = (((event.fileIndex - 1 + fraction) * 100.0) / event.fileCount).toInt().coerceIn(1, 99)
                    val mb = event.downloadedBytes / (1024.0 * 1024.0)
                    val total = if (event.totalBytes > 0L) " / ${"%.1f".format(Locale.US, event.totalBytes / (1024.0 * 1024.0))} MB" else " MB (စုစုပေါင်း အရွယ်အစားမသိ)"
                    mainHandler.post { setSpeechProgress(percent, "${event.fileName}: ${"%.1f".format(Locale.US, mb)}$total · $percent%") }
                }
                temporary = File(cacheDir, "speech-${System.currentTimeMillis()}.wav")
                OfflineTtsEngine(files).use { engine ->
                    engine.synthesize(text, temporary!!) { percent ->
                        mainHandler.post { setSpeechProgress(percent, "အသံဖန်တီးနေသည်… $percent%") }
                    }
                }
                val record = historyRepository.add(temporary!!, text, selected)
                temporary!!.delete()
                mainHandler.post {
                    lastOutput = record
                    busy = false
                    setSpeechProgress(100, "အသံဖိုင် ဖန်တီးပြီးပါပြီ။ 100%")
                    if (currentTab == TAB_CREATE) renderCreatePage()
                    else if (currentTab == TAB_HISTORY) renderHistoryPage()
                    applyBusyState()
                }
            } catch (error: Exception) {
                temporary?.delete()
                mainHandler.post {
                    busy = false
                    showStatus("အသံဖိုင် ဖန်တီးမရပါ။ ${error.message ?: "အမှားတစ်ခု ဖြစ်ပွားခဲ့သည်။"}")
                    applyBusyState()
                    if (currentTab == TAB_CREATE) renderCreatePage()
                }
            }
        }
    }

    private fun prepareEnglishResources(): File {
        val root = File(filesDir, "nltk_data")
        val bundled = listOf(
            "nltk_data/corpora/cmudict.zip",
            "nltk_data/taggers/averaged_perceptron_tagger.zip"
        )
        for (asset in bundled) {
            val relative = asset.removePrefix("nltk_data/")
            val destination = File(root, relative)
            if (!destination.isFile || destination.length() == 0L) {
                destination.parentFile?.mkdirs()
                assets.open(asset).use { input -> destination.outputStream().buffered().use { output -> input.copyTo(output) } }
            }
        }
        return root
    }

    private fun downloadVoice(id: String) {
        if (busy) return
        val voice = modelRepository.voices.firstOrNull { it.id == id } ?: return
        busy = true
        val dialog = ProgressDialog(this).apply {
            setTitle("${voice.label} ဒေါင်းလုဒ်လုပ်နေသည်")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 1
            setCancelable(false)
            setMessage("Model ဖိုင်များကို စစ်ဆေးနေသည်…")
            show()
        }
        downloadDialog = dialog
        worker.execute {
            try {
                modelRepository.ensureInstalled(id) { event ->
                    val fraction = if (event.totalBytes > 0L) event.downloadedBytes.toDouble() / event.totalBytes else 0.0
                    val percent = (((event.fileIndex - 1 + fraction) * 100.0) / event.fileCount).toInt().coerceIn(1, 99)
                    val downloadedMb = event.downloadedBytes / (1024.0 * 1024.0)
                    val sizeText = if (event.totalBytes > 0L) {
                        "${"%.1f".format(Locale.US, downloadedMb)} / ${"%.1f".format(Locale.US, event.totalBytes / (1024.0 * 1024.0))} MB"
                    } else "${"%.1f".format(Locale.US, downloadedMb)} MB"
                    mainHandler.post {
                        dialog.setMessage("${event.fileName} · $sizeText · $percent%")
                        dialog.progress = percent
                    }
                }
                mainHandler.post {
                    dialog.dismiss()
                    downloadDialog = null
                    busy = false
                    toast("${voice.label} ဒေါင်းလုဒ်ပြီးပါပြီ။ အင်တာနက်မလိုဘဲ အသုံးပြုနိုင်ပါသည်။")
                    if (currentTab == TAB_SETTINGS) renderSettingsPage()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    dialog.dismiss()
                    downloadDialog = null
                    busy = false
                    toast("ဒေါင်းလုဒ်မအောင်မြင်ပါ။ ${error.message ?: "ကွန်ရက်ကို စစ်ဆေးပါ။"}")
                    if (currentTab == TAB_SETTINGS) renderSettingsPage()
                }
            }
        }
    }

    private fun confirmRemoveVoice(id: String, label: String) {
        AlertDialog.Builder(this)
            .setTitle("အသံဖိုင်များ ဖယ်ရှားရန်")
            .setMessage("$label model ဖိုင်များကို ဖုန်းမှ ဖယ်ရှားမလား။ လိုအပ်ပါက နောက်မှ ပြန်ဒေါင်းလုဒ်လုပ်နိုင်ပါသည်။")
            .setNegativeButton("မဖယ်ရှားပါ") { dialog, _ -> dialog.dismiss() }
            .setPositiveButton("ဖယ်ရှားမည်") { _, _ ->
                worker.execute {
                    val removed = modelRepository.removeInstalled(id)
                    mainHandler.post {
                        toast(if (removed) "$label ဖယ်ရှားပြီးပါပြီ။" else "ဖယ်ရှားရန် ဖိုင်မတွေ့ပါ။")
                        if (currentTab == TAB_SETTINGS) renderSettingsPage()
                    }
                }
            }
            .show()
    }

    private fun fetchCatalog(repoUrl: String) {
        if (busy) return
        if (repoUrl.isBlank()) {
            toast("Hugging Face repository URL ထည့်ပါ။")
            return
        }
        busy = true
        toast("အသံစာရင်းကို Hugging Face မှ ရယူနေပါသည်…")
        worker.execute {
            try {
                val count = modelRepository.fetchCatalogFromHf(repoUrl)
                mainHandler.post {
                    busy = false
                    toast("အသံ pack $count ခု ထည့်ပြီးပါပြီ။")
                    if (currentTab == TAB_SETTINGS) renderSettingsPage()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    busy = false
                    toast(error.message ?: "အသံစာရင်းကို ရယူမရပါ။")
                    if (currentTab == TAB_SETTINGS) renderSettingsPage()
                }
            }
        }
    }

    private fun openCatalogPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/json", "text/plain"))
        }
        startActivityForResult(intent, REQUEST_IMPORT_CATALOG)
    }

    private fun importCatalog(uri: Uri) {
        if (busy) return
        busy = true
        toast("JSON အသံစာရင်းကို စစ်ဆေးနေပါသည်…")
        worker.execute {
            try {
                val json = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_CATALOG_BYTES) { "JSON အသံစာရင်းသည် 512 KiB ထက်ကြီးနေပါသည်။" }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                } ?: throw IllegalStateException("JSON ဖိုင်ကို ဖတ်မရပါ။")
                val count = modelRepository.importCatalogJson(json)
                mainHandler.post {
                    busy = false
                    toast("အသံ pack $count ခု ထည့်ပြီးပါပြီ။")
                    if (currentTab == TAB_SETTINGS) renderSettingsPage()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    busy = false
                    toast("JSON ကို ထည့်မရပါ။ ${error.message ?: "ဖိုင်ပုံစံကို စစ်ဆေးပါ။"}")
                    if (currentTab == TAB_SETTINGS) renderSettingsPage()
                }
            }
        }
    }

    private fun playAudio(file: File) {
        if (!file.isFile) {
            toast("အသံဖိုင်ကို ရှာမတွေ့ပါ။")
            return
        }
        try {
            player?.release()
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener { media ->
                try {
                    if (Build.VERSION.SDK_INT >= 23) {
                        media.playbackParams = PlaybackParams().setSpeed(playbackSpeed).setPitch(1.0f)
                    }
                } catch (_: Exception) { }
                media.start()
            }
            next.setOnCompletionListener { media ->
                media.release()
                if (player === media) player = null
            }
            next.setOnErrorListener { media, _, _ ->
                media.release()
                if (player === media) player = null
                toast("အသံဖိုင်ကို ဖွင့်မရပါ။")
                true
            }
            next.prepareAsync()
            toast("အသံဖိုင်ကို ဖွင့်နေပါသည်။")
        } catch (error: Exception) {
            player?.release()
            player = null
            toast("အသံဖိုင်ကို ဖွင့်မရပါ။ ${error.message ?: ""}")
        }
    }

    private fun applyPlaybackSpeed() {
        val active = player ?: return
        if (Build.VERSION.SDK_INT < 23) return
        try {
            if (active.isPlaying) active.playbackParams = PlaybackParams().setSpeed(playbackSpeed).setPitch(1.0f)
        } catch (_: Exception) { }
    }

    private fun exportAudio(file: File) {
        if (!file.isFile) {
            toast("သိမ်းရန် အသံဖိုင်ကို ရှာမတွေ့ပါ။")
            return
        }
        pendingExport = file
        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, "Yelinvoice-$date.wav")
        }
        startActivityForResult(intent, REQUEST_EXPORT_AUDIO)
    }

    private fun shareAudio(file: File) {
        if (!file.isFile) {
            toast("မျှဝေရန် အသံဖိုင်ကို ရှာမတွေ့ပါ။")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "အသံဖိုင်", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "အသံဖိုင် မျှဝေရန်"))
        } catch (error: Exception) {
            toast("မျှဝေရန် မအောင်မြင်ပါ။ ${error.message ?: ""}")
        }
    }

    private fun confirmDelete(record: AudioRecord) {
        AlertDialog.Builder(this)
            .setTitle("အသံဖိုင် ဖျက်ရန်")
            .setMessage("ဒီအသံဖိုင်ကို app သိုလှောင်ခန်းမှ အပြီးဖျက်မလား။")
            .setNegativeButton("မဖျက်ပါ") { dialog, _ -> dialog.dismiss() }
            .setPositiveButton("ဖျက်မည်") { _, _ ->
                worker.execute {
                    val deleted = historyRepository.delete(record.id)
                    mainHandler.post {
                        if (lastOutput?.id == record.id) lastOutput = historyRepository.all().firstOrNull()
                        toast(if (deleted) "အသံဖိုင်ကို ဖျက်ပြီးပါပြီ။" else "ဖိုင်ကို ဖျက်မရပါ။")
                        if (currentTab == TAB_HISTORY) renderHistoryPage()
                    }
                }
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_EXPORT_AUDIO) pendingExport = null
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT_AUDIO -> {
                val source = pendingExport
                pendingExport = null
                if (source == null || !source.isFile) {
                    toast("သိမ်းရန် အသံဖိုင်ကို ရှာမတွေ့ပါ။")
                    return
                }
                worker.execute {
                    try {
                        contentResolver.openOutputStream(uri, "w")?.use { output ->
                            source.inputStream().buffered().use { input -> input.copyTo(output, 64 * 1024) }
                        } ?: throw IllegalStateException("သိမ်းမည့်နေရာကို ဖွင့်မရပါ။")
                        mainHandler.post { toast("ဖုန်းထဲသို့ အသံဖိုင် သိမ်းပြီးပါပြီ။") }
                    } catch (error: Exception) {
                        mainHandler.post { toast("သိမ်းမရပါ။ ${error.message ?: ""}") }
                    }
                }
            }
            REQUEST_IMPORT_CATALOG -> importCatalog(uri)
        }
    }

    private fun addHeader(page: LinearLayout, title: String, subtitle: String) {
        val brand = TextView(this).apply {
            text = "YELINVOICE  ·  OFFLINE"
            textSize = 11f
            letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primaryColor)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(getColorResource(R.color.brand_primary_container), 16)
        }
        page.addView(brand, wrapWidth())
        page.addView(space(14), matchWidth())
        page.addView(label(title, 27f, inkColor, true), matchWidth())
        page.addView(space(5), matchWidth())
        page.addView(label(subtitle, 14f, mutedColor), matchWidth())
        page.addView(space(18), matchWidth())
    }

    private fun card(parent: LinearLayout, content: LinearLayout.() -> Unit): MaterialCardView {
        val view = cardView()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            content()
        }
        view.addView(column)
        parent.addView(view, matchWidth())
        parent.addView(space(13), matchWidth())
        return view
    }

    private fun cardView() = MaterialCardView(this).apply {
        setCardBackgroundColor(surfaceColor)
        radius = dp(20).toFloat()
        cardElevation = dp(1).toFloat()
        strokeColor = getColorResource(R.color.brand_surface_variant)
        strokeWidth = dp(1)
        useCompatPadding = false
    }

    private fun actionButton(text: String, filled: Boolean, stroke: Int = primaryColor) = MaterialButton(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        cornerRadius = dp(14)
        minHeight = dp(46)
        insetTop = 0
        insetBottom = 0
        if (filled) {
            backgroundTintList = ColorStateList.valueOf(primaryColor)
            setTextColor(Color.WHITE)
        } else {
            backgroundTintList = ColorStateList.valueOf(surfaceColor)
            strokeColor = ColorStateList.valueOf(stroke)
            strokeWidth = dp(1)
            setTextColor(stroke)
        }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = true
    }

    private fun showStatus(message: String) {
        statusMessage = message
        statusText?.text = message
    }

    private fun setSpeechProgress(percent: Int, message: String) {
        progressPercent = percent.coerceIn(1, 100)
        statusMessage = message
        statusText?.text = message
        progressBar?.apply {
            isIndeterminate = false
            progress = progressPercent
            visibility = View.VISIBLE
        }
    }

    private fun applyBusyState() {
        makeButton?.isEnabled = !busy
        makeButton?.alpha = if (busy) 0.65f else 1f
        voicePicker?.isEnabled = !busy
        textInput?.isEnabled = !busy
        listenButton?.isEnabled = !busy && lastOutput != null
        listenButton?.alpha = if (listenButton?.isEnabled == true) 1f else 0.55f
        progressBar?.visibility = if (busy) View.VISIBLE else View.GONE
        progressBar?.isIndeterminate = false
        progressBar?.progress = progressPercent
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun speedText(speed: Float) = String.format(Locale.US, "%.2f×", speed)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun getColorResource(id: Int): Int = if (Build.VERSION.SDK_INT >= 23) getColor(id) else resources.getColor(id)
    private fun space(heightDp: Int) = View(this).apply { minimumHeight = dp(heightDp) }
    private fun spaceWidth(widthDp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun wrapWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        downloadDialog?.dismiss()
        downloadDialog = null
        player?.release()
        player = null
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAB_CREATE = 1
        private const val TAB_HISTORY = 2
        private const val TAB_SETTINGS = 3
        private const val REQUEST_EXPORT_AUDIO = 601
        private const val REQUEST_IMPORT_CATALOG = 602
        private const val MAX_CATALOG_BYTES = 512 * 1024
    }
}
