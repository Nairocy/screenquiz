# ScreenQuiz P2 端侧集成 — 实现蓝本 v1

- 日期：2026-09-22
- 上游依据：`PLAN-v1-baicizhan.md`（其中 **§5.5 是真机实测结论，本文件的硬规格不得偏离**）
- 目标：Android 端侧 app（Kotlin），在百词斩「练习赛」里全自动跑：
  `hub → 练习赛 → 随机匹配 → 逐题读屏判定点击 → 结算返回 → 循环`
- 交付：可安装 APK（debug + release），MEIZU 21 Pro（1368×3192）真机验收

---

## 0. 已实测的环境（照抄，不要自创版本）

| 项 | 值 |
|---|---|
| JDK | `D:\App\JDK\jdk-21`（**JAVA_HOME 必须指它**；Gradle 8.14.3 / AGP 8.11 不认 JDK25） |
| Gradle | wrapper **8.14.3**，`distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-bin.zip`；`GRADLE_USER_HOME=D:\gradle\home` |
| AGP / Kotlin | `com.android.tools.build:gradle:8.11.0` / `org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.25`（**均已本地缓存**，勿升级） |
| 仓库 | `maven.aliyun.com/repository/google`、`.../public`、`google()`、`mavenCentral()` |
| SDK | `C:\Users\Nairocy\AppData\Local\Android\Sdk`（platform android-36 / build-tools 36.0.0） |
| 应用 | 包名 `com.nairocy.screenquiz`；minSdk **30**；compileSdk/targetSdk 36；versionName `0.1.0` |
| ADB | `C:\Users\Nairocy\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| 目标 app | `com.jiongji.andriod.card`（百词斩） |

依赖（同 Taskin 已验证过的组合，能直接用缓存）：
`androidx.appcompat:appcompat:1.7.1`、`androidx.activity:activity-ktx:1.10.1`、`com.google.android.material:material:1.12.0`
（**不引** OCR / onnxruntime / llama.cpp —— 实测不需要）

---

## 1. 目录结构（新建 `E:\Work\ScreenQuiz\app\`，PC 原型 `proto\` 保留不动）

```
E:\Work\ScreenQuiz\app\
  settings.gradle                 include ':app'
  build.gradle.kts                buildscript{classpath agp 8.11.0 / kotlin 1.9.25} + allprojects 仓库
  gradle.properties               android.useAndroidX=true / org.gradle.jvmargs=-Xmx2g
  local.properties                sdk.dir=C\:\\Users\\Nairocy\\AppData\\Local\\Android\\Sdk
  gradle\wrapper\gradle-wrapper.properties   （照 §0 的 URL）
  app\
    build.gradle.kts              com.android.application + kotlin-android；namespace=com.nairocy.screenquiz
    src\main\AndroidManifest.xml
    src\main\assets\ecdict.db             ← 从 proto\ecdict-trim.db 拷（4.7MB / 56,992 词）
    src\main\res\xml\accessibility_service_config.xml
    src\main\res\values\{strings,themes}.xml
    src\main\res\drawable\ic_launcher_fg.xml  （手绘 vector，不要 emoji/字体字符）
    src\main\java\com\nairocy\screenquiz\
      MainActivity.kt
      QuizAccessibilityService.kt
      PageReader.kt       节点树 → 页面语义
      Matcher.kt          ECDICT 打分（照 python 原型 1:1 移植）
      EcdictDb.kt         assets 词典
      Engine.kt           状态机
      LogBus.kt           日志总线（UI + logcat）
```

---

## 2. 无障碍服务（硬规格）

`accessibility_service_config.xml`：
```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowContentChanged|typeWindowStateChanged"
    android:packageNames="com.jiongji.andriod.card"
    android:notificationTimeout="150"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canTakeScreenshot="false"
    android:description="@string/svc_desc" />
```
Manifest：`<service android:name=".QuizAccessibilityService" android:exported="false"
android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">` + 上述 config 的 intent-filter。
`onServiceConnected` 里 `serviceInfo.flags` 保持不变（不要动系统给的 flags，避免部分 ROM 不生效）。

---

## 3. PageReader：节点树 → 页面语义（判别文本全部来自 §5.5 实测）

从 `rootInActiveWindow` 递归收集 `(className, text, clickable, bounds)`，然后：

| 状态 | 判据（文本） | 备注 |
|---|---|---|
| `SETTLE` | 含 `返回` **且**含 `分享战绩` | 只点「返回」，**绝不点分享战绩/奖励** |
| `IN_GAME` | 能取到「题干英文词」（词典可查、字号最大）+ ≥3 个「中文选项」（落在 clickable 容器内、有中文、非纯数字、非排除词） | 过场时无 word → 视为 `TRANSITION` |
| `MATCHING` | 含 `等待对手` | |
| `PRACTICE` | 含 `随机匹配` 且含 `好友对战` | |
| `HUB` | WebView 活动里原生标题 = `单词对战`（H5 内容读不到 ✗） | 用相对坐标兜底 |
| 其他 | — | `UNKNOWN` |

- 排除词表：`第 题 返回 分享战绩 奖励 学习得分 开始对战吧 随机匹配 好友对战 全部战绩 本周战绩 练习赛 排行榜 错词本 单词对战 最后一题 双倍得分`
- 选项节点定位：文本节点中心落在某个 `clickable=true` 的节点 bounds 内 → 那个容器就是点击目标
- 题干词判据：`^[A-Za-z][A-Za-z'\- ]*$` 且 `ECDICT` 查得到（避免把对手昵称 `Homlily` 当题干——**这是 PC 原型踩过的坑**）

**实测坐标（1368×3192，一律换算成相对比例再用）**
| 目标 | 像素 | 相对 |
|---|---|---|
| hub「练习赛」 | (257,2318) | **(18.8%, 72.6%)** |
| 练习赛页「随机匹配」 | (988,2613) | 节点定位优先，坐标兜底 (72.2%, 81.9%) |
| 对局顶栏 ‹（退出） | (97,206) | (7.1%, 6.5%) |
| 结算「返回」 | (363,2655) | 节点定位优先 (26.5%, 83.2%) |

---

## 4. Matcher（照 `proto/answer_engine.py` 逐条移植，勿自由发挥）

```
normTerms(s):
  s = s.replace("\\n","\n")                       # ECDICT 用【字面 \n】分行（关键坑）
  对每一行：剥行首词性前缀 n./v./vt./vi./adj./adv./ad./a./prep./... （正则，含 a. 和 ad.）
            按 [；;，,、/｜|·…—()（）[]【】「」《》""''’] 切
            去首部（的|地|得|使|把|让|被|很|尤指|指|某人|某物|某事物）
bigrams(s): 去非文字字符后取相邻二字集合
dice(a,b) = 2|∩|/(|A|+|B|)      charJac(a,b) = |∩|/|∪|（按字符集）
termScore(o,d) = 1.0 全同 / 0.9 包含 / 否则 max(dice, 0.6*charJac)
scoreOption = 对 (选项词块 × 词块) 取 max
pick: 按分降序；confident = best ≥ 0.4 && margin ≥ 0.25；
      不 confident 也点最高分（用户拍板：拿不准就猜）；全 0 → 四项随机
```

Kotlin 单测必须包含：
1. `neighborhood` + [`牙齿`,`附近,邻近`,`死敌,宿敌`,`纪念品`] → `附近,邻近`（best 1.0 / margin 1.0）
2. `snake` 的 ECDICT 词块里必须有 `蛇`（验证字面 `\n` 与 `vi./a.` 剥离没丢）
3. `closed` + [`关着的`] → 得分 > 0（验证字级 Jaccard 兜底）
4. 载入 `proto/real_cases.jsonl`（14 组真机样本）逐条：答案得分 ≥ 0.4 的比例 ≥ 6/7

---

## 5. EcdictDb
- `assets/ecdict.db` 首次启动复制到 `filesDir/ecdict.db`（幂等：文件不存在或长度不符才拷）
- `SELECT translation FROM dict WHERE word = ?`（word 一律 `lower()`）
- 主界面显示条目数（56,992）

---

## 6. Engine 状态机（幂等 + 异常，全部要有日志）

```
HUB      : 点 (18.8%,72.6%) → 3s 内应进 PRACTICE；否则记异常重试（≤3 次）
PRACTICE : 「随机匹配」节点 → performAction(ACTION_CLICK)；失败退坐标 (72.2%,81.9%)
MATCHING : 等；>45s 未变 → 点顶栏 ‹ 退出重来（≤3 次）
IN_GAME  : 读 word+options → Matcher → 点正确项容器（±6px 抖动）
           幂等键 = (word, options 文本元组) —— 同键不重复点
           OBSERVE 模式下只记日志不点
SETTLE   : 只点「返回」（回到 HUB，循环继续）
UNKNOWN  : 按一次 BACK；≤3 次仍未知 → 停止并报错
上限     : 单次运行轮次上限（默认 10 轮）、单局作答上限（默认 7 题），到上限自动停
```

---

## 7. 模式与 UI（用户风格：**无 emoji 图标**、文案中文、简洁）

- `OBSERVE`（只标注不点击，**默认**）/ `AUTO`（自动点击）：开关存 SharedPreferences
- 主界面一页：服务开关状态（未开启时按钮跳无障碍设置）、模式开关、开始/停止、实时日志（环形 200 条）、词典条目数、当前状态
- 日志同时 `Log.i("ScreenQuiz", ...)`（格式固定：`state=IN_GAME word=glass options=[...] pick=玻璃,玻璃杯 best=1.0 margin=1.0 clicked=true`），便于本喵 adb 验收

---

## 8. 批次与验收（每批都由本喵在真机上亲验）

| 批 | 内容 | 验收方式 |
|---|---|---|
| **W1** | 工程骨架 + 无障碍服务 + PageReader + 日志 UI | 装机开服务 → 停在百词斩题目页 → `adb logcat -s ScreenQuiz` 看到 `state=IN_GAME word=… options=[…]` |
| **W2** | Matcher + EcdictDb + Engine（OBSERVE/AUTO） | OBSERVE：跑一局逐题给出判定；AUTO：跑完整局，结算比分 > 0 且 7 题全对 |
| **W3** | 面板/上限/图标/release 包 | release APK 装机、模式切换、上限停止、图标显示正常 |

构建命令（本喵验收也用它）：
```bash
export JAVA_HOME="D:/App/JDK/jdk-21"; export GRADLE_USER_HOME="D:/gradle/home"
cd /e/Work/ScreenQuiz/app && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s ScreenQuiz:V
```

---

## 9. 明确不做（本期）
- 不做 OCR / 不引 PP-OCRv5（免 OCR 已实测成立）
- 不做 LLM 兜底、不做浮窗人工介入（拿不准就猜，用户已拍板）
- 不碰好友对战/排行榜/错词本/分享战绩
- 不写后台常驻花样（就用系统无障碍服务，Flyme 白名单由用户手动加）
