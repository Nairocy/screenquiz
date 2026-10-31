# ScreenQuiz · 词斩斩（WordSlasher）

百词斩「单词对战」全自动答题 + 每日单词打卡 Android app：**无障碍服务读屏 → 本地词库判定 → 手势注入作答**。
全端侧运行，不联网、不 hook、不修改目标应用。

> ⚠️ 仅供个人学习与研究。自动化对战很可能违反目标应用的服务条款，账号风险与合规责任由使用者自行承担。

## 功能

- **双模式对战**：排位赛（Hub「开始对战吧!」）/ 练习赛（底部格子 →「随机匹配」），自动循环 N 局
- **抢答对齐**：实测答题开放窗口在题面出现后约 2.2–2.6s，首点后按 70ms 粒度补点，命中 mean ≈ 2.38s
- **每日单词打卡**：打开百词斩学习页后点打卡浮球，自动跑完新学 → 复习A → 复习B 三轮（10 词 25 题实测约 60s）；做完**停在「完成 N/N 组」结束页、不代点打卡**，打卡由你手点
- **本地判定**：ECDICT 词库 + bigram/字级 Jaccard 相似度，结算对账写入自建 `user_dict`，同题下次 1.0 命中
- **抗卡顿**：每 5 局退出对战页重进（WebView 逐局嵌套会变卡）
- **悬浮球**：单击开关引擎、拖动挪位置、长按打开本应用；中央显示剩余局数
- **点击位置校准**：画布按钮（PK 入口 / 退出 X / 匹配返回）+ 打卡所需的四个选项块与「继续」按钮，用悬浮准星摆位；校准条可自由拖动、1px 微调、「试一下」直接验证落点
- **方正主义界面**：零圆角 / 2dp 墨边 / 纸底 / 无阴影，方形自绘开关，浅色与深色两套配色

## 下载

预构建 APK 与作者自用词库（6,844 词）见 [Releases](https://github.com/Nairocy/wordslasher/releases)。

## 构建

环境：**JDK 21**、Android SDK **36**（compileSdk 36 / minSdk 30 / targetSdk 36）。

```bash
cd app
./gradlew testDebugUnitTest assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Windows 下若 `gradlew` 报 `ClassNotFoundException: GradleWrapperMain`（MSYS 路径未转换），直接调 wrapper：

```bash
java -classpath "app/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain testDebugUnitTest assembleDebug
```

## 使用

1. 安装 APK，在系统设置里开启「词斩斩」无障碍服务
2. 授予「显示在其他应用上层」权限（悬浮球与校准准星需要）
3. 首次使用先做点击位置校准：设置卡 →「点击位置校准」→ 逐项把准星拖到目标按钮上 → 确定
   - 不同屏占比下画布按钮位置会漂移，这一步是必须的
   - 打卡前还要校准四个选项块与「继续」按钮（一次摆 5 个准星）
   - 校准条可按住空白处拖动，避免挡住目标；位置会记住
4. 对战：选对战类型与循环局数 → 开始对战（悬浮球单击也可开关）
5. 打卡：主界面「每日单词打卡」开关打开 → 手动进入百词斩学习页 → 点打卡浮球开始；跑完停在结束页，自行点打卡

## 词典

`app/src/main/assets/ecdict.db` 是 [ECDICT](https://github.com/skywind3000/ECDICT)（MIT）的裁剪版：**56,992 词**，表结构 `dict(word, translation, frq, bnc)`。

若想让释义更贴近题面，可自行准备本机已有词库，按同一表结构合并进这个 db（**仓库不随附任何第三方词典数据**，数据来源与许可请自行确认）。

## 项目结构

```
app/app/src/main/java/com/nairocy/screenquiz/
├── Engine.kt                  对战状态机（HUB → PRACTICE/MATCHING → IN_GAME → SETTLE）
├── DakaEngine.kt              打卡状态机（新学 → 复习A → 复习B → 结束页停手）
├── PageReader.kt              无障碍树读页（HUB/HOME/PRACTICE/MATCHING/IN_GAME/SETTLE/STUDY…）
├── Matcher.kt / EcdictDb.kt   本地判定 + 词典
├── UserDict.kt                自建学习词库（manual > settle）
├── CalibrationMarker.kt       校准准星（overlay）
├── CalibrationHud.kt          校准确认条（跨应用悬浮窗，可拖动）
├── MultiCalibrationHud.kt     多点校准确认条（一次摆多个准星）
├── TapPoint.kt                可校准的画布坐标点
├── OverlayBall.kt             对战悬浮球
├── DakaOverlay.kt             打卡悬浮球
├── SquareSwitch.kt            方形自绘开关（零圆角设计语言的一部分）
└── QuizAccessibilityService.kt 无障碍服务（读屏调度 + 手势注入 + overlay 管理）
```

## 设计文档与实测记录

- `STATUS.md`：版本记录 + 真机实测坑（WebView 无障碍退化、overlay insets 偏移、SAF URI 等）
- `PLAN-p2.md` / `PLAN-v2-learning.md`：实现蓝本（状态机、学习管道）

## 许可

MIT，见 [LICENSE](LICENSE)。
