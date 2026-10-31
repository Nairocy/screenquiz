# ScreenQuiz 学习管道 + 必答保底 — 实现蓝本 v2

- 日期：2026-09-24
- 前置：`STATUS.md`（P2 现状）、`PLAN-p2.md`（工程骨架）、本文件（v2 增量）
- 用户拍板（2026-09-24）：
  1. **乙方案**：用户词典层 + 错词本吸收 + 结算对账 + 库外词识别放松
  2. **必答**：答题环节**不许出现「未作答」**——猜也要猜一个。旧偏好「少往错词本塞词」**作废**，改为「猜错至少进错词本」
  3. 数据全部端侧，不依赖云端

---

## 1. 真机勘察结论（2026-09-24 一局实测，设计依据）

| # | 结论 | 证据 |
|---|---|---|
| 1 | **结算页列的是「正确答案」**（不是玩家作答） | 故意答错的 camaraderie 在结算页显示 `同志之爱, 友情`（正确项），而非本喵点的「香烟, 纸烟」 |
| 2 | 结算页**每行带左右对错图标**，content-desc = `selection_right`/`selection_wrong`；**左=我方，右=对手** | 我方标记序列 ✕✓✓✓✕✓✓ 与本局作答记录 100% 吻合（camaraderie 故意错、crucify 缺口错） |
| 3 | **错词本完全可读且实时入账** | 打局后「单词数量」101→103，新增 = camaraderie（n. 友情）、crucify（v. 折磨，迫害; …）；列表 = word + 百词斩释义 + 日期分组 |
| 4 | 局内选完瞬间有反馈卡片（「✕ + 所选文本」），<1.5s 即随切题消失 | 截图 sq_frame_1_a（0.25s 在）/ sq_frame_1_b（2.5s 已切题），dump 追不上（>1.5s）→ **不作为信号源** |
| 5 | **结算页词表/错词本词条均在 WebView 子树下**（TextView ← … ← WebView），且 hub 页今天也可 dump 出内容 | XML 祖先链实测；PLAN §5.5 时代「H5 读不到」应为当时系统未启用无障碍服务所致 |
| 6 | **端侧读 WebView 有先例**：9-22 端侧 SETTLE 判定（`contains("返回")&&contains("分享战绩")`，这两个文本就在 WebView 子树里）实际跑通过 | STATUS 提交链 W2 闭环记录 + 今日 XML 结构 |
| 7 | `crucify` = 措辞/义项缺口实证：ECDICT 仅「vt. 十字架上钉死」→ 匹配 0 分答错；**错词本释义含「v. 折磨，迫害」** | 结算页 + 错词本双方数据 |
| 8 | 对局页 7 题（含偏词）识别 100% 成功；题干/4 选项全部可读 | 本局 sq_play_log.json |

---

## 2. 「未作答」根因（代码级定位，2026-09-24）

用户观察：答题时**偶发整个题不点击**，等到 10s 倒计时归零；**超时的词不进错词本**（百词斩只记「已作答」的对错）→ 学习管道收不到，必答是学习完整性的前提。

| # | 路径 | 代码位置 | 说明 |
|---|---|---|---|
| **P1 主因** | 题面词**不在词典** → `PageReader` 识别不出 word → 页面落入 `TRANSITION`（options≥3 但 word=null）→ `Engine` 对 TRANSITION 是 `Unit` **空转** | PageReader.kt L102（`ecdict.lookup(text)==null → continue`）+ L110-114（状态判定）+ Engine.kt L122 | 库外词/短语/变形题都会命中；这就是「不选等超时」的主体 |
| P2 | 选项读取不全（<3 个）→ 不满足 IN_GAME → 同为空转 | PageReader L110 | 偶发 |
| P3 | 点空/被动画吞掉，MAX_RETAPS=3 用尽后不再动 | Engine L251-254、L291 | 表现同为「未选择」 |
| P4 | `options.isEmpty()` / `target==null` 等 return 分支 | Engine L223、L284 | 低概率 |

---

## 3. 设计

### 3.1 用户词典层 `UserDict`（新文件 `UserDict.kt`）

```sql
CREATE TABLE IF NOT EXISTS user_dict (
  word       TEXT PRIMARY KEY,   -- 小写（与基础库 lookup 语义一致）
  translation TEXT NOT NULL,     -- 百词斩措辞的释义（原样存）
  source     TEXT NOT NULL,      -- wrongbook | settle | manual
  updated_at INTEGER NOT NULL
)
```

- 文件：`filesDir/user_dict.db`（**独立文件**，避开 `EcdictDb.ensureDb()` 的 asset 长度校验重灌逻辑；APK 升级不会覆盖）
- API：`upsert(word, translation, source)`（INSERT OR REPLACE，幂等）、`get(word): String?`、`count(): Int`、`all(): List<Row>`
- `EcdictDb.lookup(word)` 改为**合并查询**：先 `user_dict`（精确 lowercase），miss 再查 `dict` 表
- 写入时机全在引擎空闲/页面稳定时（见 3.3/3.4），不阻塞答题轮询

### 3.2 必答保底（W1，改动最小、收益最大）

**D1 识别放松（PageReader.kt）**
- word 判定**去掉「ECDICT 可查」硬门槛**，保留「最大字号」原则：
  - 主候选：`EN_RE`（`^[A-Za-z][A-Za-z'\- ]*$`）匹配的英文 TextView，取 bounds 高度最大者
  - 兜底候选（主候选为空时）：放宽为 `^[A-Za-z][A-Za-z'.\- ]*$`（允许句点，覆盖 `remind sb. of sth.` 类短语），仍要求 len≥2、非 EXCLUDE
  - **排除规则**：含数字的串（`S54`、hash id）、EXCLUDE 表、纯数字
- **状态判定顺序调整**：把 `HUB`（含「单词对战」）与 `PRACTICE`（含「随机匹配」+「好友对战」）**提到 IN_GAME 之前**（防 hub/练习赛页被误判成题面——防御性，虽然今日数据下 options 数不会达标）
- 防误判回归清单（改完必查）：
  | 页面 | 会不会被当题面 | 原因 |
  |---|---|---|
  | hub | 否 | 无合法英文候选（S54/哈希含数字被排除）；且「单词对战」判定前置 |
  | 练习赛页 | 否 | 唯一英文 WIN/LOSE 大字，但 options=0（中文全在 EXCLUDE）→ IN_GAME 不成立；且「随机匹配」判定前置 |
  | 结算页 | 否 | SETTLE 判定最优先 |
  | MATCH!/等待对手 | 否 | 「MATCH!」含 `!`、不在允许集；「等待对手」判定优先 |
  | 对局页 | 是（预期） | 题面字号 223px > 对手英文昵称 ~84px，取最大者 |

**D2 IN_GAME 兜底猜（Engine.kt）**
- `lookup`（用户层∪基础库）全 miss → **短语兜底猜**（D2b）→ 必选一个点掉；日志 `err=no_dict guessed=true score=…`
- 目的：库外词也**必作答**；答错→进错词本→被同步吸收→下轮命中（学习闭环）

**D2b 短语兜底猜 `PhraseGuesser`（2026-09-24 用户点名：`knock sb. off one's feet` 至少能借 feet 选到「使某人双脚离地」）**
- 输入：未收录的 word（多词结构，含空格/句点/撇号）+ 选项列表
- 算法：
  1. tokenize：按「非字母」切分 → 丢弃虚词（sb/sth/one/s/someone/somebody/something/the/a/an/of/to）与单字母 → 保留实词
  2. 每个 token 查 UserDict→基础库，收集全部 normTerms 词块（miss 跳过；可选：token 去尾 s/es 再试一次）
  3. 每个选项打分 = Matcher.scoreOption(option, 全部词块)（复用既有打分）
  4. 最高 > 0 → 选它（reason=phrase_partial）；全 0 → 随机（reason=random）
- 日志：`guess=phrase tokens=[knock,off,feet] scores=[…] pick=…`
- 已知局限（接受）：成语性短语「单词义项求和 ≠ 短语义项」，可能偏（如 knock→「打击」压过 feet→「脚」）；先上线看真实数据，猜错进错词本被吸收后自愈

**D3 TRANSITION 保底猜（Engine.kt）**
- 条件：`state==TRANSITION` 且 options≥3 且**连续两帧 key 一致**（stable gate，250ms 重读，防过场动画中点空）→ 随机选一选项点击；日志 `state=TRANSITION action=guess`
- 防重：`guessedKeys` 集合（同 key 只猜一次）+ 复用 retap；**每局猜次数上限 10**（防在非题目页反复点击）

**D4 retap 增强**
- `RETAP_MS` 1600→1200、`MAX_RETAPS` 3→4（10s 窗口内多一次补救机会）
- （可选）retap 目标容器中心 ↔ 文本中心交替

**D5 日志与验收断言**
- 每题必须有 `clicked=true` 或 `guessed=true`；统计「未作答题数」必须为 0
- `dict_miss` 的 word 单独打日志（= 未收录词清单，后续分析用）

**D6 残留结算不计轮（2026-09-24 实装）**
- 启动/重置后「一题未答」就遇到结算页（上一局残留）→ 不计轮、不触发 maxRounds；日志带 `residual=true`
- 场景：用户停在结算页时启动引擎，单次模式不至于被残留局吃掉一次计数

### 3.3 错词本同步
（✅ 2026-09-24 实装 `97e63b0`：启动时 + 每局后；只在 HUB 启动、同步期间屏蔽状态机；真机 18/18 一屏读全、连续 3 局每轮 ~2.5s） `WrongbookSync`（W2）

**触发**：引擎 start 时置 `pendingSync=true`；每局 SETTLE 处理完（回到 hub）再置一次；主界面手动按钮（W3）备用
**执行**：作为 Engine 内的子流程——`handleHub()` 开头：`if (pendingSync) { syncStep(); return }`，由既有 poll 轮询逐步推进（幂等步骤机）：

| 步 | 动作 | 完成判据 |
|---|---|---|
| S0 | 校验在 hub（joined 含「单词对战」）；不在则 BACK ≤3 次回 hub | hub 判定通过 |
| S1 | 点「错词本」格子（相对坐标 **(0.808, 0.717)**，实测 (1105,2288)/1368×3192；若节点可读则优先节点定位） | 页面出现「错词本」+「单词数量」 |
| S2 | 一屏一屏读列表（`show_texts` 等价解析），每屏后 `swipe` 上滑（dispatchGesture）；滚动直到收齐「单词数量：N」个词条或连续两屏无新增 | 收集数 ≥ N 或到底 |
| S3 | 点左上 ‹ 返回（相对坐标 **(0.041, 0.058)**，实测 (56,184)）→ 校验回 hub | hub 判定通过 → `pendingSync=false`，upsert 全部（source=wrongbook） |

- **解析规则**：日期行（`20xx年x月x日`）为分组标记；词条 = 「word 行（无 CJK、非纯数字、len≥2）+ 下一释义行（以词性前缀或 CJK 开头）」成对；**短语词条**（含空格/句点）也要收
- **失败安全**：任意异常 → 尝试回 hub → `pendingSync=false` → 继续正常循环（同步永不阻塞答题流程）
- 日志：`sync=wrongbook loaded=N total=M ms=…`
- **风险待实测**：列表懒加载节奏、swipe 在 WebView 列表的实际效果、`单词数量` 是否含未渲染词条

### 3.4 结算对账
（✅ 2026-09-24 实装 `cc00d3d`：SettleParser 右侧首个 CJK 切分；自算对错 pick vs answer；settle 源优先级最低不覆盖；真机 reconcile 7/7） `SettleReconcile`（W3）

**PageReader 扩展**（SETTLE 时解析）：
- 词行：含 CJK 且「首个 CJK 字符左侧为非空英文段」的 TextView → word = 左侧（去尾空格）、zh = 右侧
- 标记配对：过滤出 `selection_right/wrong` 的 Image（content-desc）与词行 TextView 的**出现序**，规整模式为 `[img_L][词行][img_R]` 三连 → 按 3 个一组解析；不成组则整体跳过
- 输出：`List<SettleRow(word, zh, myResult: Boolean, otherResult: Boolean)>`（**左=我方**，依据：我方头像/名字/分数在左列）

**Engine.handleSettle**：
1. 首帧 SETTLE（页面会停留直到点返回，窗口充足）→ 取 `settleRows`
2. upsert **全部 7 题**：`word → zh`（source=settle）——「正确答案」无论我方对错都是有效学习样本（含「猜对」的）
3. 日志：`reconcile=n=7 wrong=[camaraderie, crucify]`
4. 然后才执行既有的点「返回」（相对坐标 (0.265, 0.832)，现状不变）
5. 解析失败 → 跳过对账、照常返回（不阻塞）

### 3.5 主界面可见性（W3，轻量）

- MainActivity 增加：`已学习词数`（UserDict.count()）+「同步错词本」按钮（手动触发 `pendingSync=true`，仅空闲可用）
- `accessibility_service_config.xml` 的 `description` 文案更新（STATUS 遗留项：现文案与 AUTO 能力不符）

---

## 4. 文件改动清单

| 文件 | 改动 |
|---|---|
| `UserDict.kt`（新） | 用户层 sqlite：upsert / get / count / all |
| `WrongbookSync.kt`（新） | 错词本步骤机 + 列表滚动解析 + 批量 upsert（可并入 Engine，由写手定） |
| `PageReader.kt` | word 放松（D1）、状态顺序调整、SETTLE 词表+标记解析 |
| `Engine.kt` | D2/D3 猜逻辑、D4 参数、SETTLE 对账钩子、pendingSync 子流程、猜计数 |
| `EcdictDb.kt` | lookup 合并 UserDict |
| `QuizAccessibilityService.kt` | UiOps 增加 `swipe()`；读取当前页文本的辅助接口 |
| `MainActivity.kt` / `strings.xml` / `accessibility_service_config.xml` | 3.5 项 |

---

## 5. 待验证清单（实现前/中）

- [ ] **端侧 OBSERVE 读 WebView**：起服务停在错词本/结算页，日志确认文本可读（有 9-22 先例，低风险；W1 完成后第一件真机验证）
- [ ] 错词本列表滚动加载行为（一屏 dump 到多少条、swipe 距离/次数）
- [ ] dispatchGesture swipe 在 WebView 列表的实际效果
- [ ] 过场帧猜的点击命中率（stable gate 后）
- [ ] 结算页三连解析在真实多变排版下的稳定性
- [ ] 短语题端到端：识别（两种撇号/句点）→ 短语兜底猜 → 猜错进错词本 → 吸收 → 下次命中
- [ ] `contentDescription` 在端侧 `AccessibilityNodeInfo` 上的可读性（selection 标记）——dump 层面已证明可读

## 6. 实现顺序（派单批次）

- **W1（必答保底）**：UserDict + D1 识别放松 + D2/D2b 短语兜底 + D3/D4 + 单测（识别排除规则 / PhraseGuesser）
  - ✅ 2026-09-24 已实装（`62f3fa5`）；另加：单次模式开关（跑完一局即停）、残留结算不计轮（D6）
- **W2（错词本同步）**：3.3 全量
- **W3（结算对账 + 可见性）**：3.4 + 3.5
- 每批完成后：本喵真机验收（OBSERVE 读 WebView → AUTO ≥3 局统计「未作答=0」→ user_dict 词数增长 → 已学词复测匹配分）

## 7. 验收标准

1. AUTO 连跑 ≥3 局：**每题均有 clicked/guessed，未作答题数 = 0**；短语/库外词题（`knock sb. off one's feet` 类）必须走到 guessed，不得空转到超时
2. 学习生效：对账/同步后 `user_dict` 词数 > 0；把 `crucify` 预置进 user_dict 后，同题判定 best ≥ 0.9（模拟复测）
3. 不回归：闭环（匹配→答题→结算→循环）、悬浮球、OBSERVE 语义不变；无误点非题目页日志

## 8. 明确不做

- 不云同步；不改只读基础库；不碰好友对战/排行榜/分享战绩/奖励；不做局内反馈卡片读取（已证实 JS 侧 <1.5s 即逝，收益低）

## 4. W4：对战类型 / 体力停机 / 循环局数（2026-09-24 拍板）

- **对战类型**（UI RadioGroup，默认排位）：排位=hub「开始对战吧!」（[174,1704][1191,1917]，中心 (682,1810)，比例 (0.499,0.567)）；练习赛=底部格子→「随机匹配」（原流程）。排位结算「返回」回 hub；练习赛结算返回练习赛页。
- **体力停机**（用户拍板）：百词斩体力显示混乱不可靠，**不读数字**。判据＝**点了开局却没进「等待对手」页**：首次点击起 8s 内未达 MATCHING/IN_GAME → `stopped=no_energy`（没体力时点开局毫无反应）。匹配页停留久不算失败。
- **循环局数**：原「单次模式」开关**删除**（删除要彻底），改「循环局数」数字输入（1=只跑一局，默认 10）；prefs `max_rounds`。
- 勘察证据：排位结算页**含** `selection_wrong/right` 节点（练习赛结算页无）——W3 自算对错方案两模式统一，**不改**；排位实测（本喵救场 4 题 best=1.0）873:812 胜；排位含短语题（`persist in`）。
