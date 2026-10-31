package com.nairocy.screenquiz

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var runDot: View
    private lateinit var runStatus: TextView
    private lateinit var currentState: TextView
    private lateinit var actionButton: MaterialButton
    private lateinit var serviceDot: View
    private lateinit var serviceStatus: TextView
    private lateinit var enableButton: MaterialButton
    private lateinit var dictCount: TextView
    private lateinit var learnedCount: TextView
    private lateinit var dakaSummary: TextView
    private lateinit var overlaySwitch: SquareSwitch
    private lateinit var dakaSwitch: SquareSwitch
    private lateinit var overlayHint: TextView
    private lateinit var battleTypeGroup: MaterialButtonToggleGroup
    private lateinit var roundsMinus: ImageView
    private lateinit var roundsPlus: ImageView
    private lateinit var roundsInput: EditText
    private lateinit var logScroll: NestedScrollView
    private lateinit var rootScroll: NestedScrollView
    private lateinit var logView: TextView
    private lateinit var versionChip: TextView
    private lateinit var learnedCard: View
    private lateinit var learnedList: RecyclerView
    private lateinit var learnedAdapter: LearnedWordsAdapter
    private lateinit var drawerLayer: FrameLayout
    private lateinit var drawerPanel: View
    private lateinit var drawerScrim: View
    private lateinit var drawerCount: TextView
    private lateinit var drawerEmpty: TextView
    private lateinit var drawerSearch: EditText
    private lateinit var drawerClear: ImageView
    private lateinit var backCallback: OnBackPressedCallback

    // 点击位置校准
    private lateinit var calibSummary: TextView
    private lateinit var calibDakaOptsValue: TextView
    private lateinit var calibLayer: FrameLayout
    private lateinit var calibPanel: View
    private lateinit var calibScrim: View
    private val calibValueViews = HashMap<TapPoint, TextView>()
    private var calibOpen = false

    private var drawerOpen = false
    private var learnedRows: List<UserDict.Row> = emptyList()
    private var learnedQuery = ""

    // 词库导出：SAF 选目录（Android 11+ 只能这样落到用户目录）
    private val exportDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) doExport(uri)
        }

    // 词库导入：SAF 选 .db 文件
    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) prepareImport(uri)
        }

    private var dotAnimator: ObjectAnimator? = null
    private var hasAnimated = false

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshRunStatus()
            handler.postDelayed(this, RUN_POLL_MS)
        }
    }

    private val logListener: (List<String>) -> Unit = { lines ->
        runOnUiThread { renderLog(lines) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        rootScroll = findViewById(R.id.root_scroll)
        // 只在第一次拿到系统栏 inset 时应用 padding：
        // overlay 窗口（悬浮球/打卡球）增删会重新分发 insets，每次都 setPadding 会改变
        // ScrollView 的内容高度 → 滚动位置被 clamp → 点开关时整页「抽搐」（真机实测）
        var rootBarPaddingApplied = false
        ViewCompat.setOnApplyWindowInsetsListener(rootScroll) { view, insets ->
            if (!rootBarPaddingApplied) {
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                if (bars.top > 0 || bars.bottom > 0) {
                    view.setPadding(0, bars.top, 0, bars.bottom)
                    rootBarPaddingApplied = true
                }
            }
            insets
        }

        runDot = findViewById(R.id.run_dot)
        runStatus = findViewById(R.id.run_status)
        currentState = findViewById(R.id.current_state)
        actionButton = findViewById(R.id.action_button)
        serviceDot = findViewById(R.id.service_dot)
        serviceStatus = findViewById(R.id.service_status)
        enableButton = findViewById(R.id.enable_service)
        dictCount = findViewById(R.id.dict_count)
        learnedCount = findViewById(R.id.learned_count)
        dakaSummary = findViewById(R.id.daka_summary)
        overlaySwitch = findViewById(R.id.overlay_switch)
        dakaSwitch = findViewById(R.id.daka_switch)
        overlayHint = findViewById(R.id.overlay_hint)
        battleTypeGroup = findViewById(R.id.battle_type_group)
        roundsMinus = findViewById(R.id.rounds_minus)
        roundsPlus = findViewById(R.id.rounds_plus)
        roundsInput = findViewById(R.id.rounds_input)
        logScroll = findViewById(R.id.log_scroll)
        logView = findViewById(R.id.log_view)
        versionChip = findViewById(R.id.version_chip)

        findViewById<View>(R.id.logo_tile).clipToOutline = true

        versionChip.text = try {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (t: Throwable) {
            "v?"
        }

        bindState(null)

        learnedCard = findViewById(R.id.learned_card)
        learnedList = findViewById(R.id.learned_list)
        drawerLayer = findViewById(R.id.drawer_layer)
        drawerPanel = findViewById(R.id.drawer_panel)
        drawerScrim = findViewById(R.id.drawer_scrim)
        drawerCount = findViewById(R.id.drawer_count)
        drawerEmpty = findViewById(R.id.drawer_empty)
        drawerSearch = findViewById(R.id.drawer_search)
        drawerClear = findViewById(R.id.drawer_clear)

        ViewCompat.setOnApplyWindowInsetsListener(drawerPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (view.paddingTop != bars.top || view.paddingBottom != bars.bottom) {
                view.setPadding(0, bars.top, 0, bars.bottom)
            }
            insets
        }

        learnedAdapter = LearnedWordsAdapter { row, position -> deleteLearnedWord(row, position) }
        learnedList.layoutManager = LinearLayoutManager(this)
        learnedList.adapter = learnedAdapter

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (calibOpen) closeCalibDrawer() else closeLearnedDrawer()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        learnedCard.setOnClickListener { openLearnedDrawer() }
        findViewById<ImageView>(R.id.drawer_close).setOnClickListener { closeLearnedDrawer() }
        drawerScrim.setOnClickListener { closeLearnedDrawer() }
        drawerClear.setOnClickListener { drawerSearch.setText("") }
        findViewById<ImageView>(R.id.drawer_export).setOnClickListener { exportDirLauncher.launch(null) }
        findViewById<ImageView>(R.id.drawer_import).setOnClickListener { importFileLauncher.launch(arrayOf("*/*")) }
        drawerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                learnedQuery = s?.toString().orEmpty()
                drawerClear.visibility = if (learnedQuery.isEmpty()) View.GONE else View.VISIBLE
                applyLearnedFilter()
            }
        })
        drawerSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else {
                false
            }
        }

        // ---------- 点击位置校准 ----------
        calibSummary = findViewById(R.id.calib_summary)
        calibLayer = findViewById(R.id.calib_layer)
        calibPanel = findViewById(R.id.calib_panel)
        calibScrim = findViewById(R.id.calib_scrim)
        calibValueViews[TapPoint.PK] = findViewById(R.id.calib_item_pk_value)
        calibValueViews[TapPoint.EXIT] = findViewById(R.id.calib_item_exit_value)
        calibValueViews[TapPoint.MATCH_BACK] = findViewById(R.id.calib_item_match_back_value)
        calibValueViews[TapPoint.DAKA_CONTINUE] = findViewById(R.id.calib_daka_continue_value)
        calibDakaOptsValue = findViewById(R.id.calib_daka_opts_value)
        ViewCompat.setOnApplyWindowInsetsListener(calibPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        findViewById<View>(R.id.calib_row).setOnClickListener { openCalibDrawer() }
        findViewById<View>(R.id.daka_row).setOnClickListener { openDakaOverlay() }
        dakaSwitch.isChecked = AppPrefs.get(this).getBoolean(AppPrefs.KEY_DAKA_OVERLAY, false)
        dakaSwitch.onCheckedChanged = { on ->
            // 开关直接控制打卡浮窗的显隐（关掉后长按也收不起来，只能在这里重开）
            val keepY = rootScroll.scrollY
            AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_DAKA_OVERLAY, on).apply()
            QuizAccessibilityService.instance?.syncDakaOverlay()
            if (on) Toast.makeText(this, R.string.daka_shown, Toast.LENGTH_SHORT).show()
            refreshDakaSummary()
            rootScroll.post { rootScroll.scrollTo(0, keepY) }
        }
        findViewById<ImageView>(R.id.calib_close).setOnClickListener { closeCalibDrawer() }
        calibScrim.setOnClickListener { closeCalibDrawer() }
        findViewById<View>(R.id.calib_item_pk).setOnClickListener { startCalib(TapPoint.PK) }
        findViewById<View>(R.id.calib_item_exit).setOnClickListener { startCalib(TapPoint.EXIT) }
        findViewById<View>(R.id.calib_item_match_back).setOnClickListener { startCalib(TapPoint.MATCH_BACK) }
        findViewById<View>(R.id.calib_item_daka_opts).setOnClickListener {
            startMultiCalib(
                listOf(
                    TapPoint.DAKA_OPT_TL,
                    TapPoint.DAKA_OPT_TR,
                    TapPoint.DAKA_OPT_BL,
                    TapPoint.DAKA_OPT_BR
                )
            )
        }
        findViewById<View>(R.id.calib_item_daka_continue).setOnClickListener {
            startCalib(TapPoint.DAKA_CONTINUE)
        }
        findViewById<View>(R.id.calib_reset).setOnClickListener { resetCalibration() }

        enableButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        overlaySwitch.isChecked = AppPrefs.get(this).getBoolean(AppPrefs.KEY_OVERLAY, true)
        overlaySwitch.onCheckedChanged = { checked ->
            // overlay 窗口增删会让 ScrollView 回到顶部（真机实测整页下移 440px）→ 切换前后保住滚动位置
            val keepY = rootScroll.scrollY
            AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_OVERLAY, checked).apply()
            if (checked && !Settings.canDrawOverlays(this)) {
                // 只在关->开时跳授权页
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            QuizAccessibilityService.instance?.syncOverlay()
            refreshOverlayHint()
            rootScroll.post { rootScroll.scrollTo(0, keepY) }
        }

        battleTypeGroup.check(
            if (AppPrefs.battleType(this) == Engine.BATTLE_PRACTICE) {
                R.id.type_practice
            } else {
                R.id.type_rank
            }
        )
        battleTypeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val type = if (checkedId == R.id.type_practice) Engine.BATTLE_PRACTICE else Engine.BATTLE_RANK
            AppPrefs.get(this).edit().putString(AppPrefs.KEY_BATTLE_TYPE, type).apply()
            QuizAccessibilityService.instance?.engine?.battleType = type
        }

        roundsInput.setText(AppPrefs.maxRounds(this).toString())
        roundsInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val rounds = AppPrefs.parseMaxRounds(s?.toString())
                AppPrefs.get(this@MainActivity).edit().putInt(AppPrefs.KEY_MAX_ROUNDS, rounds).apply()
                QuizAccessibilityService.instance?.engine?.maxRounds = rounds
                QuizAccessibilityService.instance?.syncOverlayRemaining()
            }
        })

        roundsMinus.setOnClickListener { adjustRounds(-1) }
        roundsPlus.setOnClickListener { adjustRounds(1) }

        actionButton.setOnClickListener {
            val service = QuizAccessibilityService.instance
            if (service?.engine?.running == true) {
                service.engine.stop("user")
            } else if (service == null) {
                Toast.makeText(this, R.string.service_required, Toast.LENGTH_SHORT).show()
            } else if (service.isCalibrating()) {
                Toast.makeText(this, R.string.calib_busy_start, Toast.LENGTH_SHORT).show()
            } else if (service.dakaEngine.running) {
                LogBus.log("battle=busy_daka")
                Toast.makeText(this, R.string.battle_busy_daka, Toast.LENGTH_SHORT).show()
            } else {
                AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_ENGINE_RUNNING, true).apply()
                service.engine.battleType = currentBattleType()
                service.engine.maxRounds = currentMaxRounds()
                service.engine.start(Engine.Mode.AUTO)
            }
            refreshRunStatus()
        }

        dictCount.text = "0"
        Thread {
            val total = try {
                EcdictDb.get(applicationContext).count()
            } catch (t: Throwable) {
                -1
            }
            runOnUiThread { dictCount.text = total.toString() }
        }.start()

        learnedCount.text = "0"
        refreshLearnedCount()
        refreshCalibUi()
        playBlockEnterAnimation(savedInstanceState)
    }

    private fun playBlockEnterAnimation(savedInstanceState: Bundle?) {
        if (savedInstanceState != null || hasAnimated) return
        hasAnimated = true
        val blocks = listOf(
            R.id.status_card,
            R.id.service_card,
            R.id.dict_card,
            R.id.learned_card,
            R.id.settings_card,
            R.id.log_card,
            R.id.stamp_card
        ).map { findViewById<View>(it) }
        val scale = Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        if (scale == 0f) {
            blocks.forEach {
                it.alpha = 1f
                it.translationY = 0f
            }
            return
        }
        val offset = 10f * resources.displayMetrics.density
        blocks.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = offset
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(BLOCK_ANIM_MS)
                .setStartDelay(index * BLOCK_STAGGER_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    override fun onStart() {
        super.onStart()
        LogBus.addListener(logListener)
        handler.postDelayed(refreshRunnable, RUN_POLL_MS)
    }

    override fun onStop() {
        LogBus.removeListener(logListener)
        handler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
        refreshRunStatus()
        refreshLearnedCount()
        refreshCalibUi()
        refreshDakaSummary()
        QuizAccessibilityService.instance?.syncOverlay()
        refreshOverlayHint()
        handleCalibIntent()
    }

    /** Activity 已在前台时再来一次 am start：走 onNewIntent，也要能重新起校准。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCalibIntent()
    }

    /**
     * 排障/验收入口（无 UI 暴露）：
     * `adb shell am start -n com.nairocy.screenquiz/.MainActivity --es calib pk [--ez stay true]`
     * stay=true 时不跳百词斩，便于截图校对准星与坐标是否一致。
     */
    private fun handleCalibIntent() {
        val key = intent?.getStringExtra(EXTRA_CALIB) ?: return
        intent.removeExtra(EXTRA_CALIB)
        val stay = intent?.getBooleanExtra(EXTRA_STAY, false) ?: false
        intent.removeExtra(EXTRA_STAY)
        val target = when (key) {
            "pk" -> TapPoint.PK
            "exit" -> TapPoint.EXIT
            "match" -> TapPoint.MATCH_BACK
            else -> null
        } ?: return
        handler.postDelayed({
            val service = QuizAccessibilityService.instance ?: return@postDelayed
            if (!service.startCalibration(target)) return@postDelayed
            if (!stay) launchBaicizhan()
        }, 400)
    }

    private fun refreshOverlayHint() {
        val on = overlaySwitch.isChecked
        val granted = Settings.canDrawOverlays(this)
        overlayHint.setText(
            when {
                !on -> R.string.overlay_hint_off
                !granted -> R.string.overlay_hint_perm
                else -> R.string.overlay_hint_on
            }
        )
    }

    private fun refreshServiceStatus() {
        val enabled = isServiceEnabled()
        val stateText = getString(if (enabled) R.string.service_on else R.string.service_off)
        serviceStatus.text = getString(R.string.service_label) + "：" + stateText
        enableButton.visibility = if (enabled) View.GONE else View.VISIBLE
        serviceDot.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (enabled) R.color.success else R.color.text_secondary
        )
    }

    private fun refreshRunStatus() {
        val running = QuizAccessibilityService.instance?.engine?.running == true
        runStatus.text = getString(if (running) R.string.run_on else R.string.run_off)
        actionButton.setText(if (running) R.string.stop else R.string.start)
        actionButton.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        actionButton.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (running) R.color.danger else R.color.accent
        )
        actionButton.iconTint = ContextCompat.getColorStateList(this, R.color.on_accent)
        actionButton.setTextColor(ContextCompat.getColor(this, R.color.on_accent))
        runDot.backgroundTintList = ContextCompat.getColorStateList(
            this,
            // 方正主义：状态点在海军蓝 hero 块上，停止=芥黄（待机提示）、运行=纸白（高对比）
            if (running) R.color.paper else R.color.mustard
        )
        if (running) startBreathing() else stopBreathing()
    }

    private fun startBreathing() {
        if (dotAnimator != null) return
        dotAnimator = ObjectAnimator.ofFloat(runDot, View.ALPHA, 1f, 0.35f).apply {
            duration = BREATH_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopBreathing() {
        dotAnimator?.cancel()
        dotAnimator = null
        runDot.alpha = 1f
    }

    private fun isServiceEnabled(): Boolean {
        // MIUI 实测：AccessibilityManager.getEnabledAccessibilityServiceList() 会漏掉第三方服务
        // （服务明明 bound 着却返回空 → 界面误报「未开启」）→ 直接读系统设置里的启用列表
        if (Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return false
        }
        val flat = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val mine = ComponentName(this, QuizAccessibilityService::class.java).flattenToString()
        return flat.split(':').any { it.equals(mine, ignoreCase = true) }
    }

    // ---------- 每日单词打卡 ----------

    /** 点入口：打开打卡浮窗（长按浮窗收起）。 */
    private fun openDakaOverlay() {
        AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_DAKA_OVERLAY, true).apply()
        if (::dakaSwitch.isInitialized) dakaSwitch.isChecked = true
        QuizAccessibilityService.instance?.syncDakaOverlay()
        Toast.makeText(this, R.string.daka_shown, Toast.LENGTH_LONG).show()
    }

    private fun refreshDakaSummary() {
        if (!::dakaSummary.isInitialized) return
        val engine = QuizAccessibilityService.instance?.dakaEngine
        dakaSummary.text = if (engine?.running == true) {
            getString(R.string.daka_summary_running, engine.answered)
        } else {
            getString(R.string.daka_summary_idle)
        }
    }

    private fun currentBattleType(): String =
        if (battleTypeGroup.checkedButtonId == R.id.type_practice) {
            Engine.BATTLE_PRACTICE
        } else {
            Engine.BATTLE_RANK
        }

    private fun currentMaxRounds(): Int {
        val rounds = AppPrefs.parseMaxRounds(roundsInput.text?.toString())
        AppPrefs.get(this).edit().putInt(AppPrefs.KEY_MAX_ROUNDS, rounds).apply()
        return rounds
    }

    private fun adjustRounds(delta: Int) {
        val current = AppPrefs.parseMaxRounds(roundsInput.text?.toString())
        val next = (current + delta).coerceIn(MIN_ROUNDS, MAX_ROUNDS)
        if (next != current) {
            roundsInput.setText(next.toString())
        }
    }

    private fun renderLog(lines: List<String>) {
        val wasAtBottom = !logScroll.canScrollVertically(1)
        logView.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
        if (wasAtBottom) logScroll.fullScroll(View.FOCUS_DOWN)
        val last = lines.lastOrNull()
        // 只在日志行里出现 state= 时更新状态 chip：click/perf 等行不该把状态打回「待机」
        val state = last?.substringAfter("state=", "")?.substringBefore(' ')?.trim()
        if (!state.isNullOrEmpty()) bindState(state)
    }

    // ---------- 点击位置校准 ----------

    private fun openCalibDrawer() {
        if (calibOpen) return
        if (drawerOpen) closeLearnedDrawer()
        calibOpen = true
        backCallback.isEnabled = true
        calibLayer.visibility = View.VISIBLE
        calibPanel.translationX = DRAWER_HIDDEN_X
        calibPanel.post { calibPanel.animate().translationX(0f).setDuration(DRAWER_MS).start() }
        calibScrim.alpha = 0f
        calibScrim.animate().alpha(1f).setDuration(DRAWER_MS).start()
        refreshCalibUi()
    }

    private fun closeCalibDrawer() {
        if (!calibOpen) return
        calibOpen = false
        backCallback.isEnabled = drawerOpen
        calibPanel.animate()
            .translationX(calibPanel.width.toFloat())
            .setDuration(DRAWER_MS)
            .withEndAction {
                calibLayer.visibility = View.GONE
                calibPanel.translationX = 0f
            }
            .start()
        calibScrim.animate().alpha(0f).setDuration(DRAWER_MS).start()
    }

    /** 点某一项：起准星并直接跳到百词斩（拖完切回来确定）。 */
    private fun startCalib(target: TapPoint) {
        val service = QuizAccessibilityService.instance
        if (service == null) {
            toast(R.string.calib_need_service)
            return
        }
        if (service.engine.running) {
            toast(R.string.calib_need_stop)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(R.string.calib_need_overlay)
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (!service.startCalibration(target)) {
            toast(R.string.calib_need_service)
            return
        }
        closeCalibDrawer()
        Toast.makeText(
            this,
            getString(R.string.calib_started_fmt, getString(target.labelRes)),
            Toast.LENGTH_SHORT
        ).show()
        launchBaicizhan()
    }

    /** 多点校准入口：一次摆 4 个准星（与单点校准同样的前置检查）。 */
    private fun startMultiCalib(targets: List<TapPoint>) {
        val service = QuizAccessibilityService.instance
        if (service == null) {
            toast(R.string.calib_need_service)
            return
        }
        if (service.engine.running || service.dakaEngine.running) {
            toast(R.string.calib_need_stop)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(R.string.calib_need_overlay)
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (!service.startMultiCalibration(targets)) {
            toast(R.string.calib_need_service)
            return
        }
        closeCalibDrawer()
        Toast.makeText(this, R.string.calib_multi_hint, Toast.LENGTH_LONG).show()
        launchBaicizhan()
    }

    private fun launchBaicizhan() {
        val intent = packageManager.getLaunchIntentForPackage(TapPoint.BAICIZHAN_PACKAGE)
        if (intent == null) {
            toast(R.string.calib_app_missing)
        } else {
            startActivity(intent)
        }
    }

    private fun resetCalibration() {
        val prefs = AppPrefs.get(this)
        TapPoint.values().forEach { it.clear(prefs) }
        refreshCalibUi()
        toast(R.string.calib_reset_done)
    }

    private fun refreshCalibUi() {
        if (!::calibSummary.isInitialized) return
        val prefs = AppPrefs.get(this)
        var calibrated = 0
        for (target in TapPoint.values()) {
            val (fx, fy) = target.resolve(prefs)
            val mine = target.isCalibrated(prefs)
            if (mine) calibrated++
            calibValueViews[target]?.text = if (mine) {
                getString(R.string.calib_value_fmt, fx * 100, fy * 100)
            } else {
                getString(R.string.calib_value_default_fmt, fx * 100, fy * 100)
            }
        }
        // 四个选项块是一组：只显示「N / 4 已校准」
        val dakaOptsCalibrated = DAKA_OPT_POINTS.count { it.isCalibrated(prefs) }
        calibDakaOptsValue.text =
            getString(R.string.calib_daka_opts_value_fmt, dakaOptsCalibrated)
        calibSummary.text = if (calibrated == 0) {
            getString(R.string.calib_summary_default)
        } else {
            getString(R.string.calib_summary_fmt, calibrated, TapPoint.values().size)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    // ---------- 已学习词条抽屉 ----------

    private fun openLearnedDrawer() {
        if (drawerOpen) return
        if (calibOpen) closeCalibDrawer()
        drawerOpen = true
        backCallback.isEnabled = true
        drawerSearch.setText("")   // 每次打开重置关键词，避免残留上次过滤
        drawerLayer.visibility = View.VISIBLE
        drawerPanel.translationX = DRAWER_HIDDEN_X
        drawerPanel.post { drawerPanel.animate().translationX(0f).setDuration(DRAWER_MS).start() }
        drawerScrim.alpha = 0f
        drawerScrim.animate().alpha(1f).setDuration(DRAWER_MS).start()
        loadLearnedRows()
    }

    private fun closeLearnedDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        backCallback.isEnabled = calibOpen
        hideKeyboard()
        drawerPanel.animate()
            .translationX(drawerPanel.width.toFloat())
            .setDuration(DRAWER_MS)
            .withEndAction {
                drawerLayer.visibility = View.GONE
                drawerPanel.translationX = 0f
            }
            .start()
        drawerScrim.animate().alpha(0f).setDuration(DRAWER_MS).start()
    }

    private fun loadLearnedRows() {
        Thread {
            val rows = try {
                UserDict.get(applicationContext).all().sortedByDescending { it.updatedAt }
            } catch (t: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                learnedRows = rows
                applyLearnedFilter()
            }
        }.start()
    }

    private fun applyLearnedFilter() {
        val query = learnedQuery.trim().lowercase()
        val filtered = if (query.isEmpty()) {
            learnedRows
        } else {
            learnedRows.filter {
                it.word.contains(query) || it.translation.lowercase().contains(query)
            }
        }
        learnedAdapter.submit(filtered)
        drawerCount.text = getString(R.string.learned_count_fmt, filtered.size)
        drawerEmpty.setText(if (query.isEmpty()) R.string.learned_empty else R.string.learned_empty_search)
        drawerEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 长按删除 + Snackbar 撤销（纠错用；撤销按原样恢复，不走优先级覆盖） */
    private fun deleteLearnedWord(row: UserDict.Row, position: Int) {
        learnedAdapter.removeAt(position)
        learnedRows = learnedRows.filterNot { it.word == row.word }
        drawerCount.text = getString(R.string.learned_count_fmt, learnedAdapter.itemCount)
        drawerEmpty.visibility = if (learnedAdapter.itemCount == 0) View.VISIBLE else View.GONE
        Thread {
            try {
                UserDict.get(applicationContext).delete(row.word)
            } catch (t: Throwable) {
                // 忽略：删除失败仅影响下一轮判定，不打断界面
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) refreshLearnedCount()
            }
        }.start()
        Snackbar.make(drawerPanel, getString(R.string.learned_deleted, row.word), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                Thread {
                    try {
                        UserDict.get(applicationContext).restore(row)
                    } catch (t: Throwable) {
                        // 忽略
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        learnedRows = (learnedRows + row).sortedByDescending { it.updatedAt }
                        applyLearnedFilter()
                        refreshLearnedCount()
                    }
                }.start()
            }
            .show()
    }

    private fun refreshLearnedCount() {
        Thread {
            val total = try {
                UserDict.get(applicationContext).count()
            } catch (t: Throwable) {
                -1
            }
            runOnUiThread { learnedCount.text = total.toString() }
        }.start()
    }

    // ---------- 词库导出 / 导入 ----------

    /** 导出：SAF 选中的目录里写一份 user_dict.db 快照 */
    private fun doExport(treeUri: Uri) {
        Thread {
            var message = ""
            try {
                try {
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (ignored: Throwable) {
                    // 有些 provider 不给持久权限，本次写入够用
                }
                val dict = UserDict.get(applicationContext)
                val total = dict.count()
                val name = "screenquiz-words-" +
                    SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".db"
                val snapshot = dict.snapshot(File(cacheDir, "user_dict_snapshot.db"))
                // 注意：SAF 给的是 tree URI，createDocument 要的是"目录的 document URI"，必须转一道
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                val docUri = DocumentsContract.createDocument(
                    contentResolver, parentUri, "application/octet-stream", name
                ) ?: throw IllegalStateException("createDocument returned null")
                contentResolver.openOutputStream(docUri, "w").use { out ->
                    requireNotNull(out) { "openOutputStream returned null" }
                    snapshot.inputStream().use { input -> input.copyTo(out) }
                }
                message = getString(R.string.export_done_fmt, total, name)
            } catch (t: Throwable) {
                message = getString(R.string.export_failed) + "：" + (t.message ?: t.javaClass.simpleName)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Snackbar.make(drawerPanel, message, Snackbar.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 导入第一步：把所选文件拷到私有目录并校验结构 */
    private fun prepareImport(source: Uri) {
        Thread {
            val target = File(cacheDir, "user_dict_import.db")
            var count: Int? = null
            var error: String? = null
            try {
                contentResolver.openInputStream(source).use { input ->
                    requireNotNull(input) { "openInputStream returned null" }
                    target.outputStream().use { input.copyTo(it) }
                }
                count = UserDict.get(applicationContext).inspect(target)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val rows = count
                if (rows == null) {
                    Snackbar.make(
                        drawerPanel,
                        if (error == null) getString(R.string.import_invalid)
                        else getString(R.string.import_failed) + "：" + error,
                        Snackbar.LENGTH_LONG
                    ).show()
                } else {
                    showImportChoice(rows)
                }
            }
        }.start()
    }

    /** 导入第二步：底部小面板选「合并 / 覆盖」（不用居中 modal） */
    private fun showImportChoice(fileRows: Int) {
        val density = resources.displayMetrics.density
        val pad = (18 * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (pad * 1.4f).toInt())
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_drawer)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.import_choose_fmt, fileRows)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 15f
        })
        val dialog = Dialog(this)
        fun addRow(labelRes: Int, action: () -> Unit) {
            box.addView(MaterialButton(this).apply {
                setText(labelRes)
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (pad * 0.6f).toInt() })
        }
        addRow(R.string.import_merge) { doImport(false) }
        addRow(R.string.import_replace) { confirmReplace(fileRows) }
        dialog.setContentView(box)
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setLayout(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    /** 覆盖前的确认（清空不可逆，弹一次） */
    private fun confirmReplace(fileRows: Int) {
        val current = UserDict.get(applicationContext).count()
        AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(getString(R.string.import_confirm_fmt, current, fileRows))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ -> doImport(true) }
            .show()
    }

    /** 导入第三步：执行（overwrite=false 合并 / true 覆盖）并刷新界面 */
    private fun doImport(overwrite: Boolean) {
        val target = File(cacheDir, "user_dict_import.db")
        Thread {
            var message = ""
            try {
                val dict = UserDict.get(applicationContext)
                val report = if (overwrite) dict.replaceFrom(target) else dict.mergeFrom(target)
                message = if (overwrite) {
                    getString(R.string.import_done_replace_fmt, report.written)
                } else {
                    getString(R.string.import_done_merge_fmt, report.written, report.fileRows)
                }
            } catch (t: Throwable) {
                message = getString(R.string.import_failed) + "：" + (t.message ?: t.javaClass.simpleName)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Snackbar.make(drawerPanel, message, Snackbar.LENGTH_LONG).show()
                refreshLearnedCount()
                loadLearnedRows()
            }
        }.start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(drawerSearch.windowToken, 0)
    }

    private fun bindState(raw: String?) {
        val label: Int
        val chip: Int
        val color: Int
        when (raw?.trim()?.uppercase()) {
            "HUB" -> {
                label = R.string.state_hub
                chip = R.drawable.bg_chip_neutral
                color = R.color.text_secondary
            }
            "HOME" -> {
                label = R.string.state_home
                chip = R.drawable.bg_chip_neutral
                color = R.color.text_secondary
            }
            "PRACTICE" -> {
                label = R.string.state_practice
                chip = R.drawable.bg_chip_neutral
                color = R.color.text_secondary
            }
            "MATCHING" -> {
                label = R.string.state_matching
                chip = R.drawable.bg_chip_accent
                color = R.color.accent
            }
            "IN_GAME" -> {
                label = R.string.state_in_game
                chip = R.drawable.bg_chip_accent
                color = R.color.accent
            }
            "SETTLE" -> {
                label = R.string.state_settle
                chip = R.drawable.bg_chip_success
                color = R.color.success
            }
            "WORDBOOK" -> {
                label = R.string.state_wordbook
                chip = R.drawable.bg_chip_neutral
                color = R.color.text_secondary
            }
            "TRANSITION" -> {
                label = R.string.state_transition
                chip = R.drawable.bg_chip_neutral
                color = R.color.text_secondary
            }
            "UNKNOWN" -> {
                label = R.string.state_unknown
                chip = R.drawable.bg_chip_neutral
                color = R.color.text_secondary
            }
            else -> {
                label = R.string.state_idle
                chip = R.drawable.bg_chip_neutral
                color = R.color.text_secondary
            }
        }
        currentState.setText(label)
        // 方正主义：状态是海军蓝 hero 块上的 30sp/800 大字，**不套 chip**（chip 只留给 run_status 小标签）；
        // 颜色只用于区分「运行中 / 异常 / 空闲」，且都在海军蓝底上可读
        val emphasis = when (raw?.trim()?.uppercase()) {
            "MATCHING", "IN_GAME", "SETTLE" -> R.color.mustard
            else -> R.color.paper
        }
        currentState.setBackgroundResource(0)
        currentState.setTextColor(ContextCompat.getColor(this, emphasis))
    }

    companion object {
        private const val RUN_POLL_MS = 1_000L
        private const val BREATH_MS = 900L
        private const val MIN_ROUNDS = 1
        private const val MAX_ROUNDS = 99
        private const val DRAWER_MS = 220L
        private const val DRAWER_HIDDEN_X = 10_000f
        private const val BLOCK_ANIM_MS = 500L
        private const val BLOCK_STAGGER_MS = 40L
        private const val EXTRA_CALIB = "calib"
        private const val EXTRA_STAY = "stay"
        private val DAKA_OPT_POINTS = listOf(
            TapPoint.DAKA_OPT_TL,
            TapPoint.DAKA_OPT_TR,
            TapPoint.DAKA_OPT_BL,
            TapPoint.DAKA_OPT_BR
        )
    }
}
