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
    private var timestampMode = false
    @Volatile private var timestampSpeedFactor = 1.0f
    private var playbackSpeed = 1.0f
    private var busy = false
    private var progressPercent = 0
    private var player: MediaPlayer? = null
    private var playbackFile: File? = null
    private var playbackControlButton: MaterialButton? = null
    private var playbackPrepared = false
    private var lastOutput: AudioRecord? = null
    private var pendingExport: File? = null
    private var downloadDialog: ProgressDialog? = null

    private var voicePicker: MaterialAutoCompleteTextView? = null
    private var textInput: TextInputEditText? = null
    private var makeButton: MaterialButton? = null
    private var listenButton: MaterialButton? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var formatModeButton: MaterialButton? = null
    private var timestampSpeedSlider: Slider? = null
    private var previewButton: MaterialButton? = null
    private var playbackProgressBar: ProgressBar? = null
    private var playbackTimeLabel: TextView? = null
    private var playbackProgressRunnable: Runnable? = null
    private var previewFile: File? = null
    private var selectedTranscriptionLanguage = "my"
    private var selectedChineseScript = "Hans"
    private var transcriptionUri: Uri? = null
    private var transcriptionOutput = ""
    private var transcriptionStatus = "အသံဖိုင်ရွေးပြီး ဘာသာစကားရွေးကာ စာသားထုတ်ယူပါ။"
    private var transcriptionStatusView: TextView? = null
    private var transcribeButton: MaterialButton? = null
    private var whisperDownloadButton: MaterialButton? = null
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
            timestampSpeedFactor = prefs.getFloat("timestamp_speed_factor", 1.0f).coerceIn(1.0f, 2.0f)
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
            menu.add(Menu.NONE, TAB_TIMESTAMP, Menu.NONE, "Timestamp").setIcon(R.drawable.ic_timestamp)
            menu.add(Menu.NONE, TAB_TRANSCRIBE, Menu.NONE, "စာသားထုတ်").setIcon(R.drawable.ic_transcribe)
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
        timestampMode = tab == TAB_TIMESTAMP
        voicePicker = null
        textInput = null
        makeButton = null
        listenButton = null
        statusText = null
        progressBar = null
        formatModeButton = null
        timestampSpeedSlider = null
        previewButton = null
        transcribeButton = null
        whisperDownloadButton = null
        transcriptionStatusView = null
        when (tab) {
            TAB_HISTORY -> renderHistoryPage()
            TAB_TRANSCRIBE -> renderTranscriptionPage()
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
        if (timestampMode) {
            addHeader(page, "Timestamp dubbing", "Timestamp တစ်ခုချင်းစီကို သီးခြား WAV အသံပိုင်းအဖြစ် အင်တာနက်မလိုဘဲ ဖန်တီးပါ။")
        } else {
            addHeader(page, "စာသားမှ အသံဖိုင်သို့", "အင်တာနက်မလိုဘဲ မြန်မာနှင့် အင်္ဂလိပ်စာကို အသံထုတ်ပါ။")
        }

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
            val voiceRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            voiceRow.addView(voiceLayout, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            voiceRow.addView(spaceWidth(8), LinearLayout.LayoutParams(dp(8), 1))
            val preview = actionButton("နမူနာအသံစမ်း", filled = false).apply {
                textSize = 11.5f
                setOnClickListener { previewSelectedVoice() }
            }
            previewButton = preview
            voiceRow.addView(preview, LinearLayout.LayoutParams(dp(116), dp(56)))
            addView(voiceRow, matchWidth())
            addView(space(8), matchWidth())
            addView(label("${current.repository} · ${current.id}", 12f, mutedColor), matchWidth())
        }

        card(page) {
            addView(label(if (timestampMode) "Timestamp ဖြင့် အသံထုတ်ရန်" else "ဖတ်စေလိုသော စာသား", 16f, inkColor, true), matchWidth())
            addView(space(10), matchWidth())
            val textLayout = TextInputLayout(this@MainActivity).apply {
                hint = if (timestampMode) "[00:00 - 00:03] မြန်မာ/English စာသား" else "မြန်မာ သို့မဟုတ် English စာသား"
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }
            val edit = TextInputEditText(this@MainActivity).apply {
                setText(draftText)
                textSize = 17f
                minLines = 5
                maxLines = Int.MAX_VALUE
                gravity = Gravity.TOP or Gravity.START
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                isNestedScrollingEnabled = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnTouchListener { view, event ->
                    // Keep the outer page ScrollView from stealing vertical
                    // swipes that the user starts inside this text editor.
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN,
                        android.view.MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false // Let EditText handle cursor movement and scrolling.
                }
            }
            textInput = edit
            val characterCount = label("0 / 10,000", 12f, mutedColor).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                contentDescription = "စာလုံးရေ"
            }
            edit.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    draftText = s?.toString() ?: ""
                    characterCount.text = String.format(Locale.US, "%d / 10,000", s?.length ?: 0)
                    characterCount.setTextColor(if ((s?.length ?: 0) > 10_000) errorColor else mutedColor)
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            textLayout.addView(edit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
            ))
            addView(textLayout, matchWidth())
            characterCount.text = String.format(Locale.US, "%d / 10,000", edit.text?.length ?: 0)
            val countRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(2), dp(4), dp(2))
                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(characterCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)))
            }
            addView(countRow, matchWidth())
            val resizeHandle = label("⋮  အကွက်အမြင့်ပြောင်းရန်", 12f, mutedColor).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setPadding(dp(8), dp(4), dp(4), dp(4))
                setOnTouchListener(object : View.OnTouchListener {
                    private var startY = 0f
                    private var startHeight = 0
                    private var resizing = false
                    override fun onTouch(view: View, event: android.view.MotionEvent): Boolean {
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                startY = event.rawY
                                startHeight = edit.layoutParams.height
                                resizing = true
                                return true
                            }
                            android.view.MotionEvent.ACTION_MOVE -> if (resizing) {
                                val maxHeight = (resources.displayMetrics.heightPixels * 0.60f).toInt()
                                edit.layoutParams = edit.layoutParams.apply {
                                    height = (startHeight + (event.rawY - startY).toInt())
                                        .coerceIn(dp(110), maxHeight)
                                }
                                edit.requestLayout()
                                return true
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                val wasResizing = resizing
                                resizing = false
                                return wasResizing
                            }
                        }
                        return resizing
                    }
                })
            }
            addView(resizeHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
            if (timestampMode) {
                addView(space(8), matchWidth())
                addView(label("[00:20 - 00:23] စာသား သို့မဟုတ် 00:20-00:23 စာသား ပုံစံဖြင့်ရေးပါ။ အလွတ်လိုင်းများနှင့် စာသားတစ်ခုလုံးကို ဝိုင်းထားသော ( ) ကို လျစ်လျူရှုပါမည်။ ပထမအသံမစမီကာလနှင့် အချိန်ကွာဟချက်များကို အသံတိတ်ထားပြီး၊ စာကြောင်းရှည်လျှင် အချိန်ကွက်အတွင်း ဝင်စေရန် အမြန်နှုန်းကို အလိုအလျောက်ညှိကာ WAV တစ်ဖိုင်တည်း ထုတ်ပေးပါမည်။", 12f, mutedColor), matchWidth())
            }
        }

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
            addView(space(6), matchWidth())
            val playbackProgress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = PLAYBACK_PROGRESS_MAX
                progress = 0
                progressTintList = ColorStateList.valueOf(primaryColor)
                progressBackgroundTintList = ColorStateList.valueOf(outlineColor)
            }
            addView(playbackProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)))
            val playbackTime = label("00:00 / --:--", 12f, mutedColor)
            addView(playbackTime, matchWidth())
            addView(space(12), matchWidth())

            val firstRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val playButton = actionButton("နားထောင်", false).apply {
                setOnClickListener { playAudio(record.file, playbackProgress, playbackTime, this) }
            }
            firstRow.addView(playButton, LinearLayout.LayoutParams(0, dp(46), 1f))
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

        addSpeedControl(page)

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

    private fun renderTranscriptionPage() {
        val page = newPage()
        addHeader(page, "အသံမှ စာသားသို့", "Whisper ကို ဖုန်းထဲတွင် အင်တာနက်မလိုဘဲ အသုံးပြုပြီး မြန်မာနှင့် တရုတ်စကားပြောကို အချိန်မှတ်တမ်းပါ စာသားအဖြစ် ပြောင်းပေးပါမည်။")
        card(page) {
            addView(label("ဘာသာစကားရွေးရန်", 16f, inkColor, true), matchWidth())
            addView(space(8), matchWidth())
            val languageLayout = TextInputLayout(this@MainActivity).apply {
                hint = "အသံဘာသာစကား"
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            }
            val languageLabels = listOf("မြန်မာဘာသာ", "တရုတ်စာ (ရိုးရှင်း) · 简体中文", "တရုတ်စာ (ရိုးရာ) · 繁體中文")
            val languagePicker = MaterialAutoCompleteTextView(this@MainActivity).apply {
                inputType = android.text.InputType.TYPE_NULL
                keyListener = null
                setSimpleItems(languageLabels.toTypedArray())
                val index = when {
                    selectedTranscriptionLanguage == "my" -> 0
                    selectedChineseScript == "Hant" -> 2
                    else -> 1
                }
                setText(languageLabels[index], false)
                setOnItemClickListener { _, _, position, _ ->
                    when (position) {
                        0 -> selectedTranscriptionLanguage = "my"
                        1 -> { selectedTranscriptionLanguage = "zh"; selectedChineseScript = "Hans" }
                        2 -> { selectedTranscriptionLanguage = "zh"; selectedChineseScript = "Hant" }
                    }
                }
            }
            languageLayout.addView(languagePicker, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(languageLayout, matchWidth())
            addView(space(12), matchWidth())
            addView(actionButton("အသံဖိုင်ရွေးရန်", false).apply {
                setOnClickListener { openAudioPicker() }
            }, matchWidth())
            addView(space(6), matchWidth())
            addView(label(transcriptionUri?.lastPathSegment?.substringAfterLast('/') ?: "အသံဖိုင် မရွေးရသေးပါ", 12f, mutedColor).apply {
                maxLines = 2
                setTextIsSelectable(true)
            }, matchWidth())
            addView(space(10), matchWidth())
            val runButton = actionButton("အင်တာနက်မလိုဘဲ စာသားထုတ်ရန်", true).apply {
                isEnabled = !busy
                alpha = if (isEnabled) 1f else 0.6f
                setOnClickListener { transcribeSelectedAudio() }
            }
            transcribeButton = runButton
            addView(runButton, matchWidth())
        }

        card(page) {
            addView(label("Whisper အသံမှတ်သားစနစ်", 15f, inkColor, true), matchWidth())
            addView(space(4), matchWidth())
            val ready = OfflineWhisperModelRepository(this@MainActivity).installed()
            addView(label(if (ready) "ဘာသာစကားစုံသုံး မော်ဒယ်အသေး · ထည့်သွင်းပြီး · အင်တာနက်မလိုဘဲ သုံးနိုင်ပါပြီ" else "ဘာသာစကားစုံသုံး မော်ဒယ်အသေး · 74 MiB ခန့် · ပထမဆုံးအကြိမ် ဒေါင်းလုဒ်လုပ်ပါမည်", 12f, if (ready) primaryColor else mutedColor), matchWidth())
            addView(space(6), matchWidth())
            addView(label("မော်ဒယ်ဒေါင်းလုဒ်ပြီးပါက အသံမှတ်သားခြင်းကို ဖုန်းထဲတွင်ပင် အင်တာနက်မလိုဘဲ လုပ်ဆောင်ပါမည်။ မော်ဒယ်အသေးဖြစ်သောကြောင့် မြန်မာစကားသိရှိမှု တိကျမှုသည် အသံအရည်အသွေးအပေါ် မူတည်နိုင်ပါသည်။", 12f, mutedColor), matchWidth())
            addView(space(10), matchWidth())
            val downloadButton = actionButton(if (ready) "Whisper မော်ဒယ် ထည့်သွင်းပြီးပါပြီ" else "Whisper မော်ဒယ် ဒေါင်းလုဒ်လုပ်ရန်", filled = !ready).apply {
                isEnabled = !ready && !busy
                alpha = if (isEnabled) 1f else 0.6f
                setOnClickListener { downloadWhisperModel() }
            }
            whisperDownloadButton = downloadButton
            addView(downloadButton, matchWidth())
        }

        card(page) {
            addView(label("အချိန်မှတ်တမ်းပါ စာသားရလဒ်", 16f, inkColor, true), matchWidth())
            addView(space(8), matchWidth())
            val outputLayout = TextInputLayout(this@MainActivity).apply {
                hint = "ထုတ်ယူထားသော စာသား"
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            }
            val outputCount = label("${transcriptionOutput.length} / 10,000", 12f, mutedColor).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            val output = TextInputEditText(this@MainActivity).apply {
                setText(transcriptionOutput)
                textSize = 16f
                gravity = Gravity.TOP or Gravity.START
                minLines = 6
                maxLines = Int.MAX_VALUE
                isVerticalScrollBarEnabled = true
                isNestedScrollingEnabled = true
                setTextIsSelectable(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            output.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    transcriptionOutput = s?.toString() ?: ""
                    outputCount.text = String.format(Locale.US, "%d / 10,000", s?.length ?: 0)
                    outputCount.setTextColor(if ((s?.length ?: 0) > 10_000) errorColor else mutedColor)
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            outputLayout.addView(output, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
            addView(outputLayout, matchWidth())
            addView(outputCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))
            addView(space(8), matchWidth())
            addView(actionButton("စာသားကို ကူးယူရန်", false).apply {
                setOnClickListener {
                    val script = transcriptionOutput.trim()
                    if (script.isBlank()) {
                        toast("ကူးယူရန် စာသားရလဒ် မရှိသေးပါ။")
                    } else {
                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Yelinvoice transcript", script))
                        toast("စာသားကို clipboard သို့ ကူးယူပြီးပါပြီ။")
                    }
                }
            }, matchWidth())
            addView(space(8), matchWidth())
            addView(actionButton("Gemini အတွက် မြန်မာဘာသာပြန် Prompt ကူးယူရန်", false).apply {
                setOnClickListener { copyGeminiTranslationPrompt() }
            }, matchWidth())
            addView(space(8), matchWidth())
            addView(actionButton("အချိန်အလိုက် အသံထုတ်ရန် စာမျက်နှာသို့ ပို့ရန်", false).apply {
                setOnClickListener {
                    if (transcriptionOutput.isBlank()) toast("ပို့ရန် စာသားရလဒ် မရှိသေးပါ။")
                    else {
                        draftText = transcriptionOutput
                        navigation.selectedItemId = TAB_TIMESTAMP
                    }
                }
            }, matchWidth())
        }
        val statusCard = cardView()
        val status = label(transcriptionStatus, 13f, mutedColor).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        transcriptionStatusView = status
        statusCard.addView(status)
        page.addView(statusCard, matchWidth())
        applyBusyState()
    }

    private fun copyGeminiTranslationPrompt() {
        val source = transcriptionOutput.trim()
        if (source.isBlank()) {
            toast("ဘာသာပြန်ရန် timestamp ပါသော တရုတ်စာသားကို အရင်ထုတ်ယူပါ။")
            return
        }
        if (selectedTranscriptionLanguage != "zh") {
            toast("အသံမှ တရုတ်ဘာသာကို ရွေးပြီး စာသားထုတ်ယူပါ။")
            return
        }
        val prompt = buildString {
            appendLine("သင်သည် တရုတ်စကားမှ မြန်မာ dubbing ဘာသာပြန်သူ ဖြစ်သည်။ အောက်ပါ timestamp ပါသော တရုတ်စာသားကို သဘာဝကျသော မြန်မာစကားပြောပုံစံဖြင့် ပြန်ဆိုပါ။")
            appendLine()
            appendLine("လိုက်နာရန် စည်းကမ်းများ:")
            appendLine("၁။ စာကြောင်းတစ်ကြောင်းချင်းစီကို သီးခြားပြန်ပါ။ မူရင်း timestamp တစ်ခုချင်းစီကို အတိအကျ မပြောင်းဘဲ ထားပါ။")
            appendLine("၂။ စတင်ချိန်နှင့် အဆုံးချိန်ကြား စက္ကန့်ကြာချိန်ကိုတွက်ပြီး၊ သတ်မှတ်ချိန်အတွင်း သဘာဝနှုန်းဖြင့် ဖတ်လို့ရမည့် အတိုချုံး မြန်မာဘာသာပြန်ကို ရွေးပါ။ အသံဖတ်နှုန်းကို အလွန်မြန်အောင် မလုပ်ပါနှင့်။")
            appendLine("၃။ မြန်မာစကားလုံး/အသံထွက်အရေအတွက်ကို ကြာချိန်နှင့် နှိုင်းယှဉ်တွက်ပါ။ ပုံမှန်အားဖြင့် တစ်စက္ကန့်လျှင် ၃ မှ ၄ သံခန့်သာ ရည်မှန်းပြီး၊ အသံရှူနားချိန်အတွက် အနည်းငယ်ချန်ထားပါ။ အချိန်ကွက်တိုလျှင် အဓိကအဓိပ္ပာယ်ကို ထိန်းသိမ်းကာ တိုတိုပြန်ပါ။")
            appendLine("၄။ မူရင်းစာသား၏ အဓိပ္ပာယ်၊ ဇာတ်ကောင်အမည်နှင့် ပြောဆိုသူကို မပြောင်းပါနှင့်။ ထပ်နေသော ရွေးချယ်စကားများကိုသာ လိုအပ်သလို အတိုချုံးပါ။ မူရင်းထဲမရှိသော အချက်အလက် မထည့်ပါနှင့်။")
            appendLine("၅။ အချိန်ကွာဟမှုကို မဖြည့်ပါနှင့်။ စာကြောင်းများကို မပေါင်းပါနှင့်၊ မခွဲပါနှင့်၊ timestamp အသစ် မထည့်ပါနှင့်။ မူရင်း timestamp ရှိသော လိုင်းတစ်ခုစီအတွက် ဘာသာပြန်တစ်ကြောင်းစီသာ ထုတ်ပါ။")
            appendLine("၆။ ရလဒ်ကို အောက်ပါပုံစံအတိုင်းသာ ထုတ်ပါ။ အခြားရှင်းလင်းချက်၊ ခေါင်းစဉ် သို့မဟုတ် မှတ်ချက် မထည့်ပါနှင့်။ ဥပမာ - [00:00 - 00:02] မြန်မာဘာသာပြန်စာသား")
            appendLine()
            appendLine("တရုတ်မူရင်း timestamp စာသား:")
            appendLine(source)
        }
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Gemini Myanmar dubbing prompt", prompt))
        toast("Gemini prompt နှင့် တရုတ်စာသားကို ကူးယူပြီးပါပြီ။")
    }

    private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, REQUEST_TRANSCRIBE_AUDIO)
    }

    private fun downloadWhisperModel() {
        if (busy) return
        val repository = OfflineWhisperModelRepository(this)
        if (repository.installed()) {
            toast("Whisper မော်ဒယ်ကို ထည့်သွင်းပြီးပါပြီ။ အင်တာနက်မလိုဘဲ သုံးနိုင်ပါသည်။")
            return
        }
        busy = true
        transcriptionStatus = "Whisper မော်ဒယ် ဒေါင်းလုဒ်လုပ်ရန် ပြင်ဆင်နေပါသည်…"
        applyBusyState()
        worker.execute {
            try {
                repository.ensureDownloaded { received, total ->
                    val percent = if (total > 0L) (received * 100L / total).toInt().coerceIn(1, 99) else 1
                    val downloadedMb = received / (1024.0 * 1024.0)
                    val totalText = if (total > 0L) " / ${"%.1f".format(Locale.US, total / (1024.0 * 1024.0))} MB" else " MB"
                    mainHandler.post {
                        updateTranscriptionStatus("Whisper မော်ဒယ် ဒေါင်းလုဒ်လုပ်နေသည်: ${"%.1f".format(Locale.US, downloadedMb)}$totalText · $percent%")
                    }
                }
                mainHandler.post {
                    busy = false
                    transcriptionStatus = "Whisper မော်ဒယ် ဒေါင်းလုဒ်ပြီးပါပြီ။ အင်တာနက်မလိုဘဲ သုံးနိုင်ပါပြီ။"
                    if (currentTab == TAB_TRANSCRIBE) renderTranscriptionPage() else applyBusyState()
                    toast("Whisper မော်ဒယ် ဒေါင်းလုဒ်ပြီးပါပြီ။")
                }
            } catch (error: Exception) {
                mainHandler.post {
                    busy = false
                    transcriptionStatus = "Whisper မော်ဒယ် ဒေါင်းလုဒ်မအောင်မြင်ပါ။ ${error.message ?: "ကွန်ရက်ကို စစ်ဆေးပါ။"}"
                    if (currentTab == TAB_TRANSCRIBE) renderTranscriptionPage() else applyBusyState()
                    toast(transcriptionStatus)
                }
            }
        }
    }

    private fun transcribeSelectedAudio() {
        val uri = transcriptionUri
        if (uri == null) {
            toast("အသံဖိုင်ကို အရင်ရွေးချယ်ပါ။")
            return
        }
        if (busy) return
        val language = selectedTranscriptionLanguage
        val script = selectedChineseScript
        busy = true
        transcriptionStatus = "Whisper မော်ဒယ်နှင့် အသံဖိုင်ကို ပြင်ဆင်နေပါသည်…"
        applyBusyState()
        worker.execute {
            try {
                val model = OfflineWhisperModelRepository(this).ensureDownloaded { received, total ->
                    val progress = if (total > 0L) (received * 100L / total).toInt().coerceIn(1, 99) else 1
                    val downloadedMb = received / (1024.0 * 1024.0)
                    val totalMb = if (total > 0L) " / ${"%.1f".format(Locale.US, total / (1024.0 * 1024.0))} MB" else " MB"
                    mainHandler.post { updateTranscriptionStatus("Whisper မော်ဒယ် ဒေါင်းလုဒ်လုပ်နေသည်: ${"%.1f".format(Locale.US, downloadedMb)}$totalMb · $progress%") }
                }
                mainHandler.post { updateTranscriptionStatus("အသံဖိုင်ကို ဖတ်ရှုရန် ပြင်ဆင်နေပါသည်…") }
                val audio = AndroidAudioDecoder.decodeMono16k(this, uri) { progress ->
                    mainHandler.post { updateTranscriptionStatus("အသံဖိုင်ကို မှတ်သားရန် ပြင်ဆင်နေသည်… $progress%") }
                }
                mainHandler.post { updateTranscriptionStatus("Whisper က အင်တာနက်မလိုဘဲ စကားပြောကို စာသားအဖြစ် ပြောင်းနေပါသည်…") }
                val raw = OfflineWhisperTranscriber(model).use { it.transcribe(audio, language) }
                val converted = if (language == "zh" && script == "Hant") {
                    try { android.icu.text.Transliterator.getInstance("Simplified-Traditional").transliterate(raw) }
                    catch (_: Exception) { raw }
                } else raw
                mainHandler.post {
                    transcriptionOutput = converted
                    transcriptionStatus = if (converted.isBlank()) "စကားပြောသံကို မသိရှိနိုင်ပါ။ အသံအရည်အသွေးကောင်းသော ဖိုင်ကို ပြန်ရွေးပါ။" else "အသံမှစာသား ပြောင်းလဲပြီးပါပြီ · အသံကြာချိန် ${audio.size / 16_000} စက္ကန့် · အင်တာနက်မလိုပါ"
                    busy = false
                    if (currentTab == TAB_TRANSCRIBE) renderTranscriptionPage() else applyBusyState()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    transcriptionStatus = "အသံမှစာသား ပြောင်းလဲမရပါ: ${error.message ?: "အမှားတစ်ခု ဖြစ်ပွားခဲ့သည်။"}"
                    busy = false
                    if (currentTab == TAB_TRANSCRIBE) renderTranscriptionPage() else applyBusyState()
                }
            }
        }
    }

    private fun updateTranscriptionStatus(message: String) {
        transcriptionStatus = message
        transcriptionStatusView?.text = message
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

    private fun addTimestampSpeedControl(page: LinearLayout) {
        card(page) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(label("Timestamp အသံမြန်နှုန်း", 15f, inkColor, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val valueLabel = label(String.format(Locale.US, "%.1f×", timestampSpeedFactor), 14f, primaryColor, true)
            row.addView(valueLabel, wrapWidth())
            addView(row, matchWidth())
            val slider = Slider(this@MainActivity).apply {
                valueFrom = 1.0f
                valueTo = 2.0f
                stepSize = 0.1f
                value = timestampSpeedFactor.coerceIn(valueFrom, valueTo)
                // Intentionally remains enabled while rendering; subsequent
                // transcript lines use the updated factor immediately.
                isEnabled = true
            }
            slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    timestampSpeedFactor = value
                    valueLabel.text = String.format(Locale.US, "%.1f×", value)
                    prefs.edit().putFloat("timestamp_speed_factor", value).apply()
                }
            }
            timestampSpeedSlider = slider
            addView(slider, matchWidth())
            addView(label("၁.၀× မှ ၂.၀× အထိ ရွေးနိုင်သည်။ Slot ထက်ရှည်သောစာကြောင်းကို အလိုအလျောက် အချိန်ကိုက်ညှိပြီး၊ slider ကို render လုပ်နေစဉ်လည်း ပြောင်းလဲနိုင်သည်။", 12f, mutedColor), matchWidth())
        }
    }

    private fun generateSpeech() {
        if (busy) return
        val text = draftText.trim()
        if (text.isEmpty()) {
            showStatus("အသံထုတ်ရန် စာသားကို အရင်ထည့်ပါ။")
            return
        }
        if (timestampMode) {
            try {
                TimestampTranscriptParser.parse(text)
            } catch (error: Exception) {
                showStatus(error.message ?: "Timestamp စာသားပုံစံကို စစ်ဆေးပါ။")
                return
            }
        }
        val timestampModeForJob = timestampMode
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
                var record: AudioRecord? = null
                OfflineTtsEngine(files).use { engine ->
                    if (timestampModeForJob) {
                        TimestampDubRenderer.render(
                            transcript = text,
                            engine = engine,
                            outputFile = temporary!!,
                            speedFactorProvider = { timestampSpeedFactor }
                        ) { percent, message -> mainHandler.post { setSpeechProgress(percent, message) } }
                    } else {
                        engine.synthesize(text, temporary!!) { percent ->
                            mainHandler.post { setSpeechProgress(percent, "အသံဖန်တီးနေသည်… $percent%") }
                        }
                    }
                }
                record = historyRepository.add(temporary!!, text, selected)
                temporary?.delete()
                mainHandler.post {
                    lastOutput = record
                    busy = false
                    setSpeechProgress(100, if (timestampModeForJob) "Timestamp အလိုက် အသံတစ်ဖိုင်တည်း ဖန်တီးပြီးပါပြီ။ 100%" else "အသံဖိုင် ဖန်တီးပြီးပါပြီ။ 100%")
                    if (currentTab == TAB_CREATE || currentTab == TAB_TIMESTAMP) {
                        navigation.selectedItemId = TAB_HISTORY
                    } else if (currentTab == TAB_HISTORY) {
                        renderHistoryPage()
                    }
                    applyBusyState()
                }
            } catch (error: Exception) {
                temporary?.delete()
                mainHandler.post {
                    busy = false
                    val detail = error.message ?: error.javaClass.simpleName
                    showStatus("အသံဖိုင် ဖန်တီးမရပါ။ $detail")
                    toast("အသံဖိုင် ဖန်တီးမရပါ။ $detail")
                    applyBusyState()
                    if (currentTab == TAB_CREATE || currentTab == TAB_TIMESTAMP) renderCreatePage()
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

    private fun previewSelectedVoice() {
        if (busy) return
        val selected = modelRepository.voices.firstOrNull { it.id == selectedVoiceId } ?: return
        val sample = "မင်္ဂလာပါ။ Hello, this is a voice preview."
        busy = true
        setSpeechProgress(1, "${selected.label} နမူနာအသံ ပြင်ဆင်နေသည်… 1%")
        applyBusyState()
        worker.execute {
            var temporary: File? = null
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                val nltkDirectory = prepareEnglishResources()
                Python.getInstance().getModule("nara_bridge")
                    .callAttr("configure_english_data", nltkDirectory.absolutePath)
                val files = modelRepository.ensureInstalled(selected.id) { event ->
                    val percent = if (event.totalBytes > 0L) {
                        (event.downloadedBytes * 100L / event.totalBytes).toInt().coerceIn(1, 99)
                    } else 1
                    val downloaded = event.downloadedBytes / (1024.0 * 1024.0)
                    val total = if (event.totalBytes > 0L) " / ${"%.1f".format(Locale.US, event.totalBytes / (1024.0 * 1024.0))} MB" else " MB"
                    mainHandler.post {
                        setSpeechProgress(percent, "${event.fileName}: ${"%.1f".format(Locale.US, downloaded)}$total · $percent%")
                    }
                }
                temporary = File(cacheDir, "voice-preview-${System.currentTimeMillis()}.wav")
                OfflineTtsEngine(files).use { engine ->
                    engine.synthesize(sample, temporary!!) { percent ->
                        mainHandler.post { setSpeechProgress(percent, "နမူနာအသံ ဖန်တီးနေသည်… $percent%") }
                    }
                }
                mainHandler.post {
                    busy = false
                    previewFile = temporary
                    setSpeechProgress(100, "${selected.label} နမူနာအသံ ဖွင့်နေပါသည်။")
                    applyBusyState()
                    playAudio(temporary!!)
                }
            } catch (error: Exception) {
                temporary?.delete()
                mainHandler.post {
                    busy = false
                    showStatus("နမူနာအသံ မဖန်တီးနိုင်ပါ။ ${error.message ?: "အမှားတစ်ခု ဖြစ်ပွားခဲ့သည်။"}")
                    applyBusyState()
                }
            }
        }
    }

    private fun playAudio(
        file: File,
        progressBar: ProgressBar? = null,
        timeLabel: TextView? = null,
        controlButton: MaterialButton? = null
    ) {
        if (!file.isFile) {
            toast("အသံဖိုင်ကို ရှာမတွေ့ပါ။")
            return
        }
        try {
            if (playbackFile == file && player != null && playbackPrepared) {
                val active = player!!
                if (active.isPlaying) {
                    active.pause()
                    controlButton?.text = "ဆက်ဖွင့်"
                    stopPlaybackProgress()
                } else {
                    active.start()
                    controlButton?.text = "ခဏရပ်"
                    updatePlaybackProgress(active)
                }
                return
            }
            stopPlaybackProgress()
            playbackControlButton?.text = "နားထောင်"
            player?.release()
            previewFile?.takeIf { it != file }?.delete()
            if (previewFile != file) previewFile = null
            playbackFile = file
            playbackControlButton = controlButton
            playbackPrepared = false
            playbackProgressBar = progressBar
            playbackTimeLabel = timeLabel
            progressBar?.progress = 0
            timeLabel?.text = "00:00 / --:--"
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
                playbackPrepared = true
                playbackControlButton?.text = "ခဏရပ်"
                updatePlaybackProgress(media)
            }
            next.setOnCompletionListener { media ->
                stopPlaybackProgress()
                playbackProgressBar?.progress = PLAYBACK_PROGRESS_MAX
                playbackTimeLabel?.text = "${formatPlaybackTime(media.duration)} / ${formatPlaybackTime(media.duration)}"
                media.release()
                if (player === media) player = null
                playbackControlButton?.text = "နားထောင်"
                playbackControlButton = null
                playbackFile = null
                playbackPrepared = false
                if (previewFile == file) {
                    file.delete()
                    previewFile = null
                }
                playbackProgressBar = null
                playbackTimeLabel = null
            }
            next.setOnErrorListener { media, _, _ ->
                stopPlaybackProgress()
                media.release()
                if (player === media) player = null
                playbackControlButton?.text = "နားထောင်"
                playbackControlButton = null
                playbackFile = null
                playbackPrepared = false
                if (previewFile == file) {
                    file.delete()
                    previewFile = null
                }
                playbackProgressBar?.progress = 0
                playbackTimeLabel?.text = "00:00 / --:--"
                playbackProgressBar = null
                playbackTimeLabel = null
                toast("အသံဖိုင်ကို ဖွင့်မရပါ။")
                true
            }
            next.prepareAsync()
            toast("အသံဖိုင်ကို ဖွင့်နေပါသည်။")
        } catch (error: Exception) {
            player?.release()
            player = null
            playbackControlButton?.text = "နားထောင်"
            playbackControlButton = null
            playbackFile = null
            playbackPrepared = false
            toast("အသံဖိုင်ကို ဖွင့်မရပါ။ ${error.message ?: ""}")
        }
    }

    private fun updatePlaybackProgress(media: MediaPlayer) {
        try {
            val duration = media.duration.coerceAtLeast(1)
            val position = media.currentPosition.coerceIn(0, duration)
            playbackProgressBar?.progress = (position.toLong() * PLAYBACK_PROGRESS_MAX / duration).toInt()
            playbackTimeLabel?.text = "${formatPlaybackTime(position)} / ${formatPlaybackTime(duration)}"
        } catch (_: Exception) { }
        if (media.isPlaying) {
            val update = Runnable { if (player === media) updatePlaybackProgress(media) }
            playbackProgressRunnable = update
            mainHandler.postDelayed(update, 200L)
        }
    }

    private fun stopPlaybackProgress() {
        playbackProgressRunnable?.let(mainHandler::removeCallbacks)
        playbackProgressRunnable = null
    }

    private fun formatPlaybackTime(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun formatTranscriptTime(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0L) / 1000L).toInt()
        return if (seconds >= 3600) String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
        else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
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
        val preview = record.text.replace('\n', ' ').trim().take(120)
        AlertDialog.Builder(this)
            .setTitle("ဒီအသံဖိုင်တစ်ခုတည်းကို ဖျက်ရန်")
            .setMessage("ရွေးထားသော အသံဖိုင်တစ်ခုတည်းကိုသာ ဖျက်ပါမည်။ အခြားအသံဖိုင်များကို မဖျက်ပါ။\n\n$preview")
            .setNegativeButton("မဖျက်ပါ") { dialog, _ -> dialog.dismiss() }
            .setPositiveButton("ဒီတစ်ခုတည်း ဖျက်မည်") { _, _ ->
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
            REQUEST_TRANSCRIBE_AUDIO -> {
                transcriptionUri = uri
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                transcriptionStatus = "အသံဖိုင်ရွေးပြီးပါပြီ။ ဘာသာစကားစစ်ဆေးပြီး Offline စာသားထုတ်ယူရန် နှိပ်ပါ။"
                if (currentTab == TAB_TRANSCRIBE) renderTranscriptionPage()
            }
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
        formatModeButton?.isEnabled = !busy
        previewButton?.isEnabled = !busy
        previewButton?.alpha = if (busy) 0.6f else 1f
        transcribeButton?.isEnabled = !busy
        transcribeButton?.alpha = if (busy) 0.6f else 1f
        whisperDownloadButton?.isEnabled = !busy && !OfflineWhisperModelRepository(this).installed()
        whisperDownloadButton?.alpha = if (whisperDownloadButton?.isEnabled == true) 1f else 0.6f
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
        stopPlaybackProgress()
        downloadDialog?.dismiss()
        downloadDialog = null
        player?.release()
        player = null
        previewFile?.delete()
        previewFile = null
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAB_CREATE = 1
        private const val TAB_HISTORY = 2
        private const val TAB_SETTINGS = 3
        private const val TAB_TIMESTAMP = 4
        private const val TAB_TRANSCRIBE = 5
        private const val REQUEST_EXPORT_AUDIO = 601
        private const val REQUEST_IMPORT_CATALOG = 602
        private const val REQUEST_TRANSCRIBE_AUDIO = 603
        private const val PLAYBACK_PROGRESS_MAX = 1_000
        private const val MAX_CATALOG_BYTES = 512 * 1024
    }
}
