# ScreenQuiz 状态（截至 2026-10-23）

百词斩「单词对战」（**排位赛 + 练习赛**）全自动答题 + **自我学习**，**全端侧**（Android 无障碍服务），包名 `com.nairocy.screenquiz`。
工程蓝本 `PLAN-p2.md`；学习管道（乙方案）蓝本 `PLAN-v2-learning.md`（§4 为 W4）。

## v0.9.0（2026-11-01）方正主义 UI + 方形开关 + 打卡改开关

### 视觉重构（P1–P4）
- 令牌层：零圆角（所有 drawable 的 radius 归 0）、2dp 墨边、纸底、去阴影；浅色 / 深色两套
- 主界面：状态块改海军蓝 hero、齐边砖红操作条、数据双列、日志卡墨底、新增田字格印记块
- 两个抽屉方正化；四个浮层（悬浮球 / 打卡球 / 准星 / 校准条）全部改方，静态校验 `drawRoundRect/drawCircle/drawOval` 零命中
- 七块入场动效

### 方形开关 `SquareSwitch`
- 纯 `drawRect` 自绘（46×26、2dp 墨边、18dp 方块滑块、160ms 过渡、尊重系统动画缩放）
- 程序赋值不触发回调，只有用户点击才回调；`isFocusable=false`，避免被 ScrollView 当焦点目标滚进可视区

### 每日单词打卡改成开关
- 右侧箭头位置换成同款方形开关，直接控制打卡浮窗显隐；行点击仍可打开浮窗并同步拨到开

### 修：点开关整页「抽搐」
- 根因：overlay 窗口（悬浮球 / 打卡球）增删会让系统重新分发 window insets，`rootScroll` 的 insets 监听每次无条件 `setPadding` → 内容高度变化 → 滚动位置被重置
- 修：insets 只在首次拿到非零系统栏值时应用一次 + 两个开关切换前后保存 / 恢复 `scrollY` + 悬浮球 hint 加 `minLines="2"`
- 验收：纯截图对比，点击前后 `calib_summary` 的 y 完全一致（666–1081），主内容区仅 27 行差异
- ⚠️ 测坑：`uiautomator dump` 会重绑无障碍服务、顺带重置布局，用它验「有没有跳」会得到假阳性

## v0.8.1（2026-11-01）打卡按钮校准

- `TapPoint` 从 3 点扩到 8 点：新增 `DAKA_OPT_TL/TR/BL/BR` + `DAKA_CONTINUE`
- 新增 `MultiCalibrationHud`：一次摆 5 个准星（四个选项块 + 继续），默认停屏幕顶端避免遮挡下半屏
- 真机校准结果：选项块中心 (281,1815) (799,1815) (281,2135) (799,2135)，继续 (892,2350)

## v0.8.0（2026-11-01）每日单词打卡

- 定义：每日完成一定单词学习量（不是点签到按钮）。用户手动进学习页后点打卡浮球开始
- 三流程：新学（中文释义）→ 复习A（中文释义无例句）→ 复习B（英文近形词 / 图片题）；复习轮复用第一轮出词顺序
- 终点：跑完停在「完成 N/N 组」结束页，**绝不代点「完成今日计划」/ 打卡**
- 实测：10 词 × 三流程 = 25 题，约 60s，2.4 秒/题；结束页 `daka=stop reason=done answered=0` 零点击
- 修的坑：打卡引擎无自己的轮询、离页不计帧、详解页转场帧误判 `no_answer`、详解页 word 读成词根（`elbow→bow`/`attraction→at`）、结束页被误判为对战 `IN_GAME`、`SystemClock.uptimeMillis()` 在 JVM 单测恒返回 0

## v0.7.2（2026-11-01）校准条改成可拖动悬浮窗 + 「试一下」修复

### 校准条跨应用常驻、可自由拖动
- 原 app 内确认条（`ScrollView` 顶部卡片）删掉 → 换成无障碍服务上的 `CalibrationHud` 悬浮窗，**在百词斩页面上也能看到和操作**（拖完不用切回本应用）
- 按住条上**空白处**即可拖动（按钮区域仍走点击）；左侧加手绘 `≡` 把手提示；拖动透明度 0.92 反馈
- 位置以比例存 prefs（`calib_hud_x/y`），下次校准沿用 → 用户可把它拖开，避免挡住目标按钮
- 条内含：目标名 + 实时百分比 + ←→↑↓（1px）+ **试一下** + 确定 + ✕；按钮最小高度 38dp 便于点中

### 准星改小、可拖到屏幕顶
- 环 84dp → **52dp**（231→143px），十字 9/27dp → 6/17dp，中心仍镂空
- clamp 下限从「中心 ≥ 半径(42dp)」放宽到 **屏幕 y=0**（状态栏会压住上半圈，靠数值/微调对缝）

### 「试一下」点了没反应（真机：十次九次无响应）
- 根因：注入点就在准星中心，原先只改 `FLAG_NOT_TOUCHABLE`，而窗口 relayout 是**异步**的 → 注入的手势仍被准星自己吞掉
- 修法：`marker.hide()` → 120ms → `tap()` → 350ms → `showAt(当前中心)`（真正移除/重建窗口）
- 实测连点 3 次 → 3 条 `calib=test ... ok=true`，且点击穿透真实命中 App（截图里抽屉被点开）
- 按钮加「已点」900ms 反馈，用户能确认这次确实发起了注入

## v0.7.1（2026-10-31）校准坐标系修正（真机实测：准星比实际点击高一个状态栏）

### 根因
- overlay 窗口默认 `fitTypes=STATUS_BARS|NAVIGATION_BARS|CAPTION_BAR` → 系统把窗口父容器压到状态栏下沿（本机 122px）
  `dumpsys window` 证据：`fitTypes=STATUS_BARS... Frames: parent=[0,122][1080,2460] frame=[44,1190]`（而 params.y=1068）
- 但 `dispatchGesture` 用的是物理屏幕坐标（从 y=0）→ 准星视觉中心比保存值低 122px，点击落在准星上方 122px

### 修复
- `CalibrationMarker` / `OverlayBall` 的 `WindowManager.LayoutParams` 加 `fitInsetsTypes=0` + `layoutInDisplayCutoutMode=ALWAYS`
- `centerRatio()` / `clampToScreen()` 改用 `getLocationOnScreen`（窗口真实屏幕位置），不再直接用 params.y；`show()` 后做一次二段对齐（防 ROM 无视 fitTypes）
- 实测（Redmi pearl 1080x2460）：`50%/50%` → `frame=[425,1115]`、截图圆心 (540,1230)、`calib=save px=540 py=1230` **零偏差**；再拖到 (203,1496) → 确认条显示 18.8% / 60.8% ✓

### 同批修真机暴露的 UI 问题
- 确认条原来在滚动内容顶部 → 切回应用若停在滚动中间就看不见 → 移到 `ScrollView` 之外固定顶部（按状态栏 inset 设 topMargin）
- 无障碍状态误报「未开启」：MIUI 的 `AccessibilityManager.getEnabledAccessibilityServiceList()` 漏报第三方服务（服务 bound 着也返回空）→ 改读 `Settings.Secure` 的启用列表
- 加排障入口（无 UI）：`adb shell am start -n com.nairocy.screenquiz/.MainActivity --es calib pk [--ez stay true]`（stay=不跳百词斩，便于截图量准星）
- ⚠️ 装机（`adb install -r`）会掉无障碍绑定；本机还需注意：`settings put enabled_accessibility_services` 后若再 force-stop，系统可能把本应用从列表里摘掉 → 重开一次或重设即可

## v0.7.0（2026-10-23）画布点击位置可校准（悬浮准星摆位）

### 问题
- 不同屏占比下，百词斩画布元素（首页 PK 入口等）在整屏上的比例会漂移 → 每 5 局「退出重进」时可能点进别的按钮；默认常量只在 MEIZU 21 Pro 上核过。

### 做法
- 三个「无文本节点可兜底」的点抽成 `TapPoint`（**PK 入口 / 对战页退出 X / 匹配返回 <**）；校准值以整屏比例存 prefs（`tap_pk_x/y`、`tap_exit_x/y`、`tap_match_back_x/y`），未校准时回落默认常量 → 老行为不变
- 主界面设置卡新增「点击位置校准」→ 右侧抽屉列三项（显示当前值：默认 / 已校准）；点某项 → 无障碍服务挂一个可拖动准星 `CalibrationMarker` 并**直接跳到百词斩**
- 准星 = 双层描边圆环 + 四向十字，**中心镂空**（能看见下面的按钮），标签实时显示百分比；拖动窗口即改位置
- 切回本应用 → 顶部确认条「校准中 · PK 入口 14.2% / 49.6%」+ 微调（←→↑↓ 各 1px）+ 重摆 / 取消 / 确定；确定写 prefs（日志 `calib=save`）
- 门禁：引擎运行中不许起校准；校准未结束不许起引擎（`calib=busy_toggle`，浮球与主按钮都拦）；无悬浮窗权限跳授权页
- ⚠️ `clickRatio` 与准星**统一改用 `WindowManager.maximumWindowMetrics.bounds`**（原 `resources.displayMetrics`）——两者必须同坐标系，否则校准完仍然点偏
- 单测 +4（`TapPointTest`：默认回落 / 校准优先 / 清除 / 贴边值当无效）→ 共 **26 例**

## v0.4.0 / v0.5.0 / v0.6.0（2026-10-02）抢答提速定论 + 结算词典融合 + 局间等待修复 + 错题本全砍 + 每5局退出重进 + 词库导出/导入

### ① 提速：实测已到 UI 物理上限
- **答题开放窗口实测 2.2–2.6s**（题面出现后 App 才接受点击）；用 HUD 数字探针（页面 `+N` 奖励出现那一帧）测出真实命中时刻
- **最终参数 `QUESTION_SETTLE_MS=600 / RETAP_MS=70 / MAX_RETAPS=50`**：首点 0.63s、补点粒度 ~78ms、覆盖到 ~4.6s；命中 **mean 2.38s**，全部落在补点覆盖内
- **反例（重要）**：`RETAP_MS 35–40ms`（~22 次/秒）实测把窗口**推后到 2.7–3.9s**（3 轮一致）→ App/WebView 被点击洪水拖慢，**更密的点击严格更差**
- 修掉「匹配失败时每次补点随机换选项」的真 bug：每题**首判即锁**（`pickLock`）
- 结论：点 UI 最快也只能窗口开放即答；若对手 <2.2s 答完，其手段不可能是点 UI

### ② 结算词典融合（首遇词正确率）
- 数据源（可选）：自行准备的本机词库（**本仓库不随附**；数据来源与许可请自行确认）
- 做法：ECDICT 为基线，可自行合并同源词库以提高措辞一致度；asset 为 ECDICT 裁剪版 **4.9MB / 56,992 词**，`EcdictDb.ensureDb()` 按文件长度变化自动重灌 → 替换即生效、**零代码改动**
- 实测：真机 2 局 **14/14 全对**（此前 `clinch`/`incarcerate`/`wastrel` 等首遇词会猜错）
- ⚠️ **合并时别只覆盖**：单库释义可能反而退化（如 `wastrel` 旧库「浪费者」1.0 命中，换库后错选）→ 两者拼接更稳

### ③ 局间等待修复（每局白等 3s）
- 根因：每局回 Hub 后**第一次点「练习赛」磁贴被页面入场动画吞掉**，而重试间隔是 `HUB_WAIT_MS=3000` → 干等 3s 才重试
- 改：`HUB_SETTLE_MS=900`（等页面铺开再点）+ `HUB_WAIT_MS=700`（快速重试）+ `MAX_HUB_TRIES 3→8` + `MAX_HUB_TOTAL 15→120`（原值 15 会让 10 局长跑中途 `stopped=err`）
- 实测：结算→下一局首题 **12.8s → 9.49s**，`hub_retry` 归零

### ④ 错题本链路全砍
- 依据：settle 每局全覆盖本局题且优先级更高；错词本每轮 ~2.5s、9 天净换 7 条词 → 删 `SyncTask.kt` / `WrongbookParser.kt` / `WrongbookParserTest.kt` 与启动同步；优先级只剩 `manual(4) > settle(3)`

### ⑤ 悬浮球显示剩余局数
- 中央数字：运行中=剩余局数、停止=计划局数；引擎事件驱动同步（`syncOverlayRemaining`）

### ⑥ 每 5 局「退出对战页重进」（治嵌套 WebView 长跑变卡）
- 背景：百词斩对战页是**嵌套 WebView**，每局多叠一层 → 局数多了卡顿 / 渲染出错
- 做法：`rounds % 5 == 0` 且还要继续时，结算页改点**左上角 X**（`RESET_X/Y = 0.152/0.065`，彻底退出）而不是「返回」——X 左边的「<」(`0.070`) **只回退一层**，正是嵌套累积之源 → 落到**百词斩首页**（新状态 `HOME`，识别字「一起背」）→ 点画布入口 **PK**（`PK_X/Y = 0.139/0.501`）→ 回「单词对战」Hub → 正常续跑
- 兜底：点了 X 后 1.5s 仍停在结算页 → 自动退回点「返回」
- 实测：X 退出 0.28s 到首页、PK 重进成功、每轮仅多 ~0.5s；6 局 **41/42**

### ⑦ 无障碍树退化时仍能作答（重要健壮性）
- 现象：`uiautomator dump` 解绑重建服务后，**WebView 虚拟节点丢失 clickable 信息**（选项行容器整片消失）→ 选项读成空 → 判定 UNKNOWN → 超时→后退→重开，无限循环
- 修复：① 选项检测**不再强制要求可点容器**（容器只作更贴身的点击区，缺失就用文本自身 bounds）② `clickText` 取不到容器时退回**点文本中心**（日志 `click=text_fallback`）
- 教训：跑局验收期间**绝对不要** `uiautomator dump`（坑 4 的后果比预想严重）

### ⑧ 词库导出 / 导入（v0.6.0）
- 入口：**已学习词条抽屉**标题栏两个手绘描边图标 —— 导出 / 导入（✕ 左边）；id `drawer_export` / `drawer_import`
- 格式：**原生 `user_dict.db`**（无损往返）
- 导出：SAF `OpenDocumentTree` 选目录 → `UserDict.snapshot()` 用 **`VACUUM INTO`** 写一致快照（不吃绑定参数、目标已存在会失败 → 先 delete；异常退化为字节拷贝）→ 复制到所选目录，名 `screenquiz-words-<yyyyMMdd-HHmm>.db`
- 导入：SAF `OpenDocument`（`*/*`）选 .db → 拷进 `cache/user_dict_import.db` → `UserDict.inspect()` 只读校验（表 `user_dict` + 四列齐，缺则提示「这个文件不是 ScreenQuiz 词库」）→ 底部面板选 **合并 / 覆盖**
  - **合并**：逐行按优先级（现有 `priorityOf >= 来者` 则不覆盖；文件缺 source 记 `import`=0）
  - **覆盖**：`DELETE` 后全量写入（不看优先级，文件说了算）+ **弹一次确认**（appcompat `AlertDialog`）
- 实测：导出落到 `/sdcard/Nairocy/work/screenquiz-words-20261002-1845.db`（192512B；VACUUM 后比原库 196608 略小属正常），用户真机验收「正常」

## v0.2.0（2026-09-25）引擎提速 + 界面现代化

### 提速：对齐百词斩的「答题开放窗口」（根因）
- **实测结论**：题面卡片出现后，App 在 **~2.0–2.6s 内会吞掉注入的点击** —— 100ms / 450ms 首点实测均无效（截图：选项全白、双方 0 分；且无补点时整题 ~13s 超时）。旧版真正生效的是 1.2s 后的第一次补点（≈2.9s），这就是「选词太慢、常被对手抢先」的根因
- **新策略**：入场稳定期 `QUESTION_SETTLE_MS=1800` 后再首点，`RETAP_MS=200 / MAX_RETAPS=8` 细补点（1.8s→3.4s 覆盖开放窗口）→ 窗口一开即命中。真机练习赛 **7/7 全对**，首点稳定 1.83–1.84s
- 其余链路：防抖 60ms（事件风暴下强制读上限 200ms）、稳定门槛 90ms、轮询 250ms、无障碍 `notificationTimeout` 50ms；埋点 `latency_ms` + 读页耗时
- 混合帧守卫改**对称 XOR**（「新词+旧选项」与「旧词+新选项」都拦，原版只拦一边）

### 界面：卡片单页 + 跟随系统双主题
- 一页卡片分组（状态 / 服务 / 数据 / 设置 / 日志）；`values/` + `values-night/` 双配色（键名 14/14 一致）；手绘描边 SVG 图标（play/stop/plus/minus）
- **修两个交付缺陷**（真机抓到，均已修）：
  1. **启动崩溃**：`NestedScrollView` 上写 `android:nestedScrollingEnabled`（构造期 NestedScrollingChildHelper 尚未初始化 → NPE）→ 删该属性
  2. **卡片圆角啃字**：`MaterialCardView` 不认 `android:padding`（须用 `app:contentPadding`）→ 内容贴左上角、被 18dp 圆角切掉半截（用户可见）→ 6 张卡全改 `app:contentPadding`


## v0.3.0（2026-09-25）日志自动清理 + 已学习词条抽屉

### 日志分级保留（防长跑刷屏）
- `LogBus` 分两类：**关键行**（state=/round=/stopped=/reconcile…）最多 **150** 条；**噪音行**（`perf ` / `click=gesture` / 补点行 `retaps>0`，`retaps=0` 首次命中仍算关键）最多 **20** 条；边入边裁、保持时间顺序
- **引擎每次启动 `LogBus.clear()`**（跨局不累积）
- 新增 `LogBusTest` 5 例（噪音封顶 / retaps 例外 / 关键封顶保最新 / 跨类顺序 / clear）；JVM 单测需 `testOptions { unitTests.isReturnDefaultValues = true }`（否则 `android.util.Log` 抛异常）→ 共 **22 例全绿**

### 已学习词条抽屉（点数据卡「已学习词条」打开）
- 右侧抽屉（scrim 点击 / ✕ / 返回键退出，220ms 滑入滑出）；标题 + 计数徽标「共 N 条」+ 长按提示
- 行 = 单词 + 来源徽标（手动 / 结算 / 错词本）+ 加入日期（MM-dd）+ 释义；按 `updated_at` 倒序（批量导入的行时间戳相同 → 退化为字母序）
- **搜索**：顶部内嵌放大镜（无按钮、回车收键盘），即输即过滤（单词或释义、忽略大小写），右侧 ✕ 清空；**每次打开抽屉重置关键词**
- **长按词条 → 删除**（`UserDict.delete`）+ Snackbar「已删除 X · 撤销」（`UserDict.restore` 原样恢复 source/updated_at，不走优先级覆盖）
- 空状态文案居中在列表区（「暂无已学习词条」/「没有匹配的词条」）

## 已交付

### 判定与闭环
- **判定层**：ECDICT 裁剪版 **4.9 MB / 56,992 词**（可自行合并本机词库）+ Matcher（bigram + 字级 Jaccard×0.6）；合成 500 题 98.4% 正确、0 判错
- **双模式（W4）**：
  - **排位赛（默认）**：HUB →「开始对战吧!」→ 匹配 → 对局 → 结算 → 返回 **hub** → 下一局（每局 ~55s）
  - **练习赛**：HUB →「练习赛」→「随机匹配」→ 对局 → 结算 → 返回练习赛页
- **循环局数（W4）**：主界面数字输入（prefs `max_rounds`，1=只跑一局，默认 10）；跑满 `stopped=limit` 即停、不自动重启
- **体力停机（W4）**：点开局 8s 内未进「等待对手」页 → `start=fail reason=no_energy` → 停；**不读体力数字**（百词斩显示混乱，用户拍板）
- **残留结算不计轮**：启动遇上一局残留结算（一题未答）→ 不计轮（日志 `residual=true`）
- **免 OCR**：页面全原生节点直读（含 WebView 子树）；结算判定依赖 WebView 文本「分享战绩」
- **悬浮球**：单击开关引擎、拖动挪位置（比例存盘）、长按开主界面；注入手势后 250ms 忽略触摸防误触

### 学习管道（W1–W4，全部自动、无需人工开页面）
- **必答保底（W1）**：识别放松（EN 主候选 + 短语兜底候选）+ `no_dict` 短语兜底猜（PhraseGuesser 词块打分）+ TRANSITION 保底猜 + retap 自愈 —— 真机 2 局 18 题 **0 未答**
- **UserDict 用户词典层**：独立 `user_dict.db`（sqlite，端侧），lookup 先叠加层后基础库；基础库（asset）只读不动（`ensureDb()` 会重灌）
- **错词本同步（W2）**：~~引擎启动时 + 每局结算后自动进错词本读全~~ → **2026-10-02 全链路删除**（详见 v0.4.0/v0.5.0 §④）
- **结算对账（W3）**：SETTLE 当场解析「题面词 → 正确答案」（右侧首个 CJK 切分、支持短语行；<5 行时 250ms×2 重读）→ 写 `settle` 源；对错**自算**（本轮点选 vs 结算答案）
- **来源优先级**：`manual(4) > settle(3) > 其它(0)`（2026-10-02 起删除 `wrongbook(2)`），低优先级不覆盖高优先级（settle=题面原文，最利于同题 1.0 命中）
- **积累效果**：user_dict 已 **2435+ 条**（2026-10-02 真机实读；settle 源为主），全部由引擎跑局自动积累

## 真机验收汇总（2026-09-24，MEIZU 21 Pro / Android 16）
- 必答：AUTO 2 局 18 题未作答=0；单次/循环停机正常
- 错词本同步：18/18 一屏读全（`scrolled=0`）；连续 3 局「SETTLE→sync→下一局」每轮 ~2.5s
- 结算对账：逐题 7/7 `ok=true`；25→74 条稳定累积；`limp` 的 settle 覆盖 wrongbook 实测生效
- 排位两局连跑：`battle=rank max_rounds=2` → `stopped=limit rounds=2`；对账 7/7 与 6/7（`suave` 唯一错题，答案已入库下次命中）
- 单测 17 例全绿（Matcher 5 / PhraseGuesser 3 / WrongbookParser 4 / SettleParser 5）；`assembleDebug` 成功

## 提交链（main）
`d9dc46d` 骨架 → `410bacd` 判定+状态机 → `a5f13d4` stable gate → `8df5164` 悬浮球 → `ff33f4e` STATUS → `68e0a9c` 蓝本 v2 + W1 派单 → `62f3fa5` W1 必答 + UserDict → `b0444eb` STATUS → `d72dc7a` W2 派单 → `97e63b0` W2 错词本同步 → `6916351` W3 派单 → `cc00d3d` W3 结算对账 → `9fff298` W4 派单 + 蓝本 §4 → `4ac0306` W4 排位/停机/局数 → `6f1690f` / `64d2ee0` / `1551edf` 文档 → `3d7b338` v0.2.0 提速 + UI 现代化 → `4198fd4` STATUS → `d9e686c` v0.3.0 日志分级保留 + 已学习词条抽屉 → `52b8c81` **v0.5.0 抢答定稿 + 词典融合 + 局间等待修复 + 错题本全砍** → `c75e86f` **v0.7.0 点击位置校准（悬浮准星摆位 PK / 退出 X / 匹配返回）** → `b1bcc51` **v0.7.1 overlay fitInsets 坐标系修正（准星=落点）** → `0b8de0c` **v0.7.2 校准条悬浮可拖 + 试一下穿透修复**

## 真机实测坑（改之前先看）
1. **UNKNOWN 耐心别太短**：5 拍会在「MATCH!」过场时按 BACK 掐死正在建立的匹配 → 反复匹配死循环（现 30 拍 ≈15s + 匹配后 20s 宽限）
2. **选可点容器**取「重叠最大、面积最小」；**选项行 `performAction` 假成功** → 点击一律手势优先（`dispatchGesture`）
3. **过场混合帧**：会读到「新词 + 上一题选项」→ 连续两帧 key 一致才判定（stable gate）
4. **`uiautomator dump` 会让所有无障碍服务解绑重建**（讯飞同）→ 跑局验收期间勿 dump；有自愈（保留运行意图，自然结束置回）。**⚠️ 后果比预想严重**：重建后 WebView 虚拟节点会**丢失 clickable 信息**（选项行容器整片消失、`findAccessibilityNodeInfosByText` 也失效）→ 详见「坑 14」
5. **错词本会「归档」旧词**：实测 103 → 18（只剩最近天数分组）——同步读到什么吸收什么，属百词斩侧行为
6. **等对手期间页面不变** → retap 会把同一选项连点至上限（无害但噪音大）
7. **排位局题数不定**：实测 6/7 题均正常（`SETTLE_MIN_ROWS=5` 兼容）
8. **结算页对错标记**：排位有 `selection_wrong/right` 节点、练习赛没有 → 对错统一自算（pick vs answer），不依赖标记
9. **答题点击被吞窗口**：题面出现后 **~2.0–2.6s 内注入的点击无效**（v0.2.0 根因）；要判「窗口开了没」可读顶部 HUD 数字节点（`+N` 奖励 / 总分变化）
10. **两个 UI 属性坑**：`MaterialCardView` 不认 `android:padding`（用 `app:contentPadding`）；`NestedScrollView` 别在 XML 写 `android:nestedScrollingEnabled`（启动 NPE）
11. **`MaterialButton` 的 `app:icon` 画图标不居中**（方按钮 + 空文本时按文本行盒定位）→ 改 `ImageView` + `bg_stepper`（ripple 套 1dp 描边圆角）+ `padding=12dp` + `scaleType=centerInside`；像素级实测两轴居中（框 2327–2481 / 图案中心 2404）
12. **adb 注入文字别用 `input text`**：会被本机中文 IME 吞进组合态（页面看不到，甚至漏进上一个有焦点的输入框——循环局数框被写成 `z'z`）→ 用 `adb shell input keyboard text <串>`；收键盘 `keyevent 111`
13. **别用 `adb shell "run-as … 'cat > file'" < local` 写二进制**（shell stdin 做行尾转换 → sqlite 直接 `database disk image is malformed`）→ `adb push` 到 `/data/local/tmp` + `run-as … cp`；写完必须 `pragma integrity_check` + 读回行数核对
14. **a11y 树退化：WebView 虚拟节点丢 clickable**（`uiautomator dump` 之后最典型）：选项行容器整片消失、`options` 读成空 → 判定 `UNKNOWN` → 超时→BACK→重开死循环；`clickText` 也会 `by=coord`。已修（选项不强制容器 + `clickText` 退回点文本中心），不改代码也能靠**重启手机/重启百词斩进程**恢复
15. **SAF 的 tree URI ≠ document URI**：`OpenDocumentTree()` 回来的是 `content://.../tree/primary%3ANairocy%2Fwork`，直接喂 `DocumentsContract.createDocument()` → `IllegalArgumentException: Invalid URI`（导出静默失败，界面只显示「导出失败：…」）→ 必须先 `buildDocumentUriUsingTree(treeUri, getTreeDocumentId(treeUri))` 取**父目录的 document URI**。诊断法：`adb logcat -d | grep DocumentsContract`（会打出 `Failed to create document` + 完整栈，一眼定位）
16. **校准与点击必须同一坐标系**：`clickRatio` 原用 `resources.displayMetrics`，准星窗口用 `maximumWindowMetrics`；多窗口/分屏/折叠屏下二者可能不同 → 校准值写进去照样点偏。现统一 `maximumWindowMetrics.bounds`（并加 `left/top` 偏移）。改坐标逻辑前先确认两处用的是同一函数
17. **overlay 窗口的 params.y ≠ 屏幕坐标**（默认 fit insets）：`TYPE_APPLICATION_OVERLAY` 默认 `fitTypes=STATUS_BARS|NAVIGATION_BARS|CAPTION_BAR`，父容器从状态栏下沿开始 → params.y 比实际屏幕位置小一个状态栏高度（本机 122px）。凡是「overlay 定位 + dispatchGesture 点击」的功能都会差这个高度。修法：`LayoutParams.fitInsetsTypes=0`，并用 `getLocationOnScreen` 校验/兜底。验收：`dumpsys window windows` 里 `parent=[0,0]` 且 `frame.top == params.y`，再截图量圆心核对 `calib=save px/py`

## 主界面（v0.7.0：卡片单页 + 已学习词条抽屉 + 点击位置校准抽屉，跟随系统浅/深双主题）
标题（图标 + 版本 chip）· 状态卡（状态 chip + 开始/停止）· 服务卡（无障碍授权）· 数据卡（词典条目数 / 已学习词条 → 点开抽屉）· 设置卡（自动点击 / 悬浮球 / 对战类型 / 循环局数 / **点击位置校准**，加减为描边方按钮）· 日志卡（实时日志）；校准中页面顶部出现确认条（百分比 + 微调 + 重摆/取消/确定）

## 已知限制
- 措辞缺口：ECDICT 侧约 1/7 题（`cheap`→「便宜的」vs 题面「廉价的」）；并入同源词库可大幅缓解（需自行准备），仍有极少数（如 `abysmal`「极坏的」vs 选项「悲伤或失望到难忍程度的」）需靠结算自愈
- `feet` 等不规则复数不在裁剪库 → 短语兜底缺词块；靠「吸收整短语」自愈（W3）
- `accessibility_service_config` 的 description 仍写「只读不点击」，与 AUTO 能力不符
- **体力停机未自然触发验证**（未见体力耗尽；逻辑已审查、8s 判据简单）

## 运维备注（装机改配用）
- 开无障碍：`adb shell settings put secure enabled_accessibility_services '<内联单引号串>'`（**用 shell 变量传参会丢**，踩过）
- prefs（`shared_prefs/screenquiz.xml`）：**int 必须 `<int name="x" value="N"/>`**（写成 text 读不到）、string 用 `<string name="x">v</string>`
- 测试环境：`JAVA_HOME=D:/App/JDK/jdk-21`、`GRADLE_USER_HOME=D:/gradle/home`

## 可选下一步
中文同义词表（治 ~1/7 措辞缺口）；不规则复数表（feet→foot）；retap 噪音消除（读选项「已选中」状态）；Engine 仪器化测试（`UiOps` 已接口化）；轮数/时长全做成设置项
