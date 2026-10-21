# TACZ-Legacy-Next

> **TACZ-Legacy 的兼容性延续分支。**
> 上游是 [TACZ](https://github.com/MCModderAnchor/TACZ)（Minecraft **1.20.1**，包名 `com.tacz.guns`）；
> 本项目是它的 **1.12.2 Forge 移植版**，包名 `com.tacz.legacy`，技术栈 Kotlin + RetroFuturaGradle + Mixin。
>
> 本分支从 [hippo08241/TACZ-Legacy](https://github.com/hippo08241/TACZ-Legacy)（下称"韩文分叉"）的
> `master @ 3a22edb` 继续向前。目标有两条，**不是"能跑就行"**：
>
> 1. **尽量还原上游 1.20.1 的原本体验** —— 玩法数值、配件机制、射击手感、动作与音效、脚本行为，
>    都以 1.20.1 为基准对齐；凡是上游做了而 1.12.2 平台能做的，都补上。
> 2. **尽量兼容为 1.20.1 编写的第三方枪包** —— 按上游格式写的包应当**直接放进去就能用**，
>    不需要作者为 1.12.2 改数据。
>
> 两者的优先级关系是：**先对齐上游语义，再做平台缺失处的等价实现**；
> 平台确实做不到的（如依赖 `PlayerAnimator`、KubeJS），明确记录为限制而不是假装实现。

---

## 下载与安装

最新构建：**[Release v0.1.1](https://github.com/zsjs110110-cpu/TACZ-Legacy-Next/releases/tag/v0.1.1)**（52.8 MB，从 Release 附件下载）

SHA256 `017470E2078EAB45BA59E77F5A4F04B07BAD9F3ED5B13B3C0C2A9AFDFA937730`

> 构建产物不放进 Git 仓库，避免每个克隆都多下 50 MB；源码与文档在仓库里，jar 走 Release 附件。

**客户端**

1. 装 Forge **14.23.5.2859**（Minecraft 1.12.2）
2. `mods/` 里放三样：
   - `TACZ-Legacy-0.1.1.jar`
   - [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixinbooter) 11.x
   - Forgelin-Continuous（1.12.2 的 Kotlin 运行时）
3. 启动游戏。枪包放到 `.minecraft/tacz/<包名>/`，**目录或 zip 都可以**，
   按 1.20.1 格式写的包直接放进去即可，不需要为 1.12.2 改数据。

**专用服**

服务端需要同样的 mods。玩家进服后由服务端下发枪包数据（数值 / 脚本 / lang / display），
但**模型与贴图仍需客户端本地有包**——上游架构也是如此，这两类文件体积过大不适合走数据包同步。

---

## 一、源流

```
TACZ                        1.20.1 · com.tacz.guns      ← 枪包格式的定义方
 └─ TaCZ-Legacy             1.12.2 · com.tacz.legacy    ← 社区移植（Hikari_Nova 发起，
      │                                                   后续由 hippo0824 / Claude 以韩文提交继续）
      ├─ hippo08241/TACZ-Legacy   master @ 3a22edb       ← 韩文分叉，本仓库的起点
      └─ 本仓库 TACZ-Legacy-Next  ← 兼容性分支（当前）
```

说明：

- TaCZ-Legacy 的 README、提交信息原本以**韩文**为主，本分支保留其代码与资源结构不动。
- 本仓库**不是**上游 1.20.1 项目的 fork，也不向其回推任何改动；
  它只是 1.12.2 移植版上的一个兼容性分支。
- 本分支的提交以中文/英文为主，改动集中在兼容层。

---

## 二、本分支做了什么

核心思路是**行为适配，而不是数据翻译**：不改枪包 JSON，让移植版的解析与运行时行为对齐 1.20.1。

已完成的主要工作：

| 方向 | 内容 |
|---|---|
| 枪包加载 | dependencies 版本门放宽、缺 `gunpack.meta.json` 不再静默丢包、重复 id 告警、目录与 zip 直读 |
| 配件系统 | 统一 modifier resolver，16 个 modifier 全部接入玩法（不再"只进 GUI"） |
| `exclusive_attachments` | 按上游语义做**整份 AttachmentData 替换**；条目/容器解析失败只跳过该条并记 issue，不再让整把枪消失；坏 modifier 占多数时整条丢弃（仅独占条目） |
| 数值语义 | `recoil_modifier` 按 percent、inaccuracy 通用值不再越界 aim/sneak/lie、逐 key 缺省；rpm / ads / ammo_speed / pierce / knockback / effective_range / explosion / weight→移速 全部接入玩法 |
| 脚本系统 | 从上游规范的 `assets/<ns>/scripts/` 读取、**全量加载**（不只 display 引用的）、支持跨包 `require` |
| Lua 沙箱 | modifier 的 `function` 在受限环境中求值（无 `os`/`io`/`luajava`，预编译缓存），与上游一致 |
| 战利品注入 | `tacz_loot_injectors` 挂钩 `LootTableLoadEvent`，含 1.20 语法归一化（`minecraft:item`、常数额 rolls） |
| 多人同步 | 专用服向客户端下发枪包数据（数值 / 脚本 / lang / display），客户端无需预装同版本包 |
| 模型容错 | 缺 `uv` / `size` / `parent` 时降级为"跳过该 cube/bone"并告警，不再整枪渲染失败 |
| 资源接入 | 枪包目录注册为 vanilla 资源包（lang/贴图/声音可被 JEI 等读取），修了 `defaultResourcePacks` 的 SRG 名错误 |
| 热重载 | `/tacz reload` 重建客户端资产；lang 等 vanilla 资源仍需 F3+T（见"已知限制"） |
| 诊断 | 移除调试日志噪音；枪包问题改为结构化 issue；按 ID 查不到时首次告警 |

### 原移植版没做完、本分支补上的部分

从 `3a22edb` 起点的这段时间里，补完的具体清单（"原移植版"指起点时的状态）：

| 能力 | 起点时的状态 | 现在 |
|---|---|---|
| **配件 modifier 进玩法** | 16 个里只有 4 个真正生效（recoil / inaccuracy / ignite / silence），其余只影响 GUI 显示或压根没人读 | **16/16 全部接入**，并有守门测试断言"注册了就一定有人消费" |
| **`exclusive_attachments`** | 非整体替换、只覆盖 2 个键；GUI 遇到对象写法（如 `"damage":{"addend":1}`）会直接渲染崩溃 | 整份 `AttachmentData` 替换 + 回退语义；崩溃路径已整段删除 |
| **`recoil_modifier` 语义** | 当 addend 用 | 按上游的 **percent** |
| **inaccuracy 默认值** | 通用 `default` 越界污染 aim / sneak / lie | 只作用于 stand / move，缺省逐 key 回退 |
| **weight → 移速** | 链路完全缺失，`weightSpeedMultiplier` 配置零读取者 | 补上第二条 `MULTIPLY_BASE` modifier，不持枪时连同移除 |
| **explosion 的 OR 语义** | `exp != null && exp.explode`，配件永远点不亮爆炸 | 子弹自带爆炸 **OR** 配件爆炸，无数值时回退默认参数 |
| **射速 / 开镜 / 弹速** | 读原始标量，配件不生效 | 全部走 modifier 聚合（rpm 含 `clamp(1,1200)`，两条射击路径 + 客户端预测三点一致） |
| **`tacz_loot_injectors`** | 完全未实现 | 挂钩 `LootTableLoadEvent`；含 1.20 语法归一化、Forge 战利品上下文栈平衡 |
| **枪包数据同步** | 26 个网络包无一携带枪包数据，多人必须人人装同版本包 | 专用服下发数值 / 脚本 / lang / display |
| **脚本加载** | 只认 `data/<ns>/scripts/`，且只加载 display 引用到的脚本 | 认**上游规范的 `assets/<ns>/scripts/`**，**全量加载**，支持跨包 `require`（测试包上 26 个 state_machine 从全部失败降到 2 个，且剩余 2 个是包自身问题） |
| **Lua `function` 沙箱** | 用标准 Lua 环境 → 枪包脚本可调 `os` / `io` / `luajava` | 受限环境（无这三者）+ 预编译缓存，与上游一致 |
| **模型容错** | 缺 `uv` / `visible_bounds_offset` / `pivot` / 坏 `parent` 直接 NPE | 降级为"跳过该 cube/bone"并 WARN，不再整枪渲染失败 |
| **版本门** | `dependencies` 不匹配 → **整包丢弃** | 记 issue 后继续加载（容错粒度降到单文件） |
| **缺 `gunpack.meta.json`** | 静默 `return` 丢包 | 记 issue + 从目录名推断 namespace 后继续 |
| **配方 group 命名空间** | 用配方包自己的 namespace → 第三方配方在工作台消失 | 用 mod 的 namespace |
| **客户端资产热重载** | 只在启动时加载一次，改包必须重启游戏 | `/tacz reload` 重建（未撤除前会自动 `refreshResources()` 导致集成服 StackOverflow，已改为只重注册并提示 F3+T） |
| **弹药 `sort`** | 字段缺失，弹药排序与上游不一致 | 补上并钳位到 `[0, 65536]` |
| **改装台结果预览** | 只有 2D 图标 | 3D 预览，失败回退 2D |
| **诊断能力** | 调试日志噪音，坏包无任何提示 | 结构化 issue（分组 + 折叠计数）、按 ID 查不到时首次 WARN |

> 逐条开发记录不随仓库发布。

### 1.12.2 移植版上游遗留的缺陷与空实现

上面一节是"相对 1.20.1 缺的功能"。这里不同：**这些是 1.12.2 移植版自己的上游**（Hikari_Nova/KasumiNova 的原版
`6cb35cd3` 与韩文分叉 `3a22edb`）**写错或没写完的东西**，本分支逐个修掉。

**最典型的是一类"空实现" —— 接口在、代码不在，功能静默失效：**

| 项 | 移植版上游的状态 | 本分支 |
|---|---|---|
| **glTF 样条动画** | `Spline.java` 是**空 stub**，恒返回 `{0,0,0,1}` —— `CUBICSPLINE` 通道**从未工作过**，所有用样条关键帧的动画实际是静止的 | 按 glTF 规范实现 Hermite 插值（含每秒导数与旋转归一化），首次真正可用 |
| **制作台结果列表 3D** | 3D 渲染器已写好，但**只接到大预览框**；结果列表仍是 2D 图标（原作者 `Todo.md` 里唯一未勾选的功能项） | 结果按钮改为调用 3D 预览，失败回退 2D |

**其余是"有实现但做错了"，按危害排序：**

| 类别 | 上游移植版 | 本分支 |
|---|---|---|
| **存档数据破坏** | 旧版单方块工作台被当作"残缺多方块结构"**自动拆掉** | 引入 `structureVersion`，旧结构按单方块行为处理，不拆除、不覆盖 |
| **枪包覆盖不可逆** | 覆盖导出时直接删除原枪包、且不保留备份 | 先复制到 staging 再替换，备份路径可回滚 |
| **配件无限复制** | 锁定枪械卸配件时，锁校验在写背包**之后** | 锁校验前置，返还与卸除同一次原子提交 |
| **弹药盒漏洞** | 提取数量不核实、可绕过准入与容量限制、直接改槽位返回栈的 NBT | 按实际提取量入账，走 `IItemHandler`/`Slot` 正规提交；不可写后端不报告成功 |
| **射击 / 换弹互斥** | 连发子弹可在换弹或拉栓期间继续击发；无限装填仍消耗背包弹药 | 加互斥检查；无限装填直接补满弹匣 |
| **射击输入校验** | 角度未校验有限性，NaN/越界仍会推进状态并消耗弹药 | 在消耗前校验 `isFinite` 与 `-90..90` |
| **换弹无法被射击打断** | 按住射击键无法中断换弹 | 恢复中断，并用按键上升沿避免反复取消自动换弹 |
| **默认音频后端** | 播放不了枪包 `sounds` 目录下的音效 | 统一解析重定向路径，失败回退原路径 |
| **枪口火焰串模型** | 共享模型的通用 TEISR 里 `isSelf` 未复位，会**用本地枪口火焰**渲染别人的枪 | 按实际射手状态驱动 |
| **非均匀缩放下定位错** | 定位逆变换没做 `S·I·S⁻¹` 坐标变换 | 先构建完整逆矩阵再变换 |
| **地面场景双重变换** | GROUND 下叠加了两套地面矩阵 | 分开判断"是否采用 3D"与"是否保留 JSON 矩阵" |
| **降级分支不可见** | 弹药盒 fallback 只绑纹理不画几何 → 物品完全看不见 | 降级分支实际绘制几何，GL 状态成对平衡 |
| **模型缓存 / UV** | 失败结果不缓存、UV 端点排序消除镜像、纹理尺寸允许 0/负 | 缓存失败结果、保留端点顺序参与映射、拒绝非正尺寸 |
| **附属方块放置** | 绕过碰撞与权限校验，可越权放置 | 预检查边界 / `isBlockModifiable` / `canPlayerEdit` / `mayPlace` |
| **左手持枪姿势** | 主手枪姿势固定给右臂 | 按 `getPrimaryHand()` 选择左右臂 |
| **MouseTweaks 兼容** | 过滤只覆盖右键入口 | 左键与滚轮入口应用同一过滤 |
| **韩语本地化** | 缺约 30 个键 | 按 `en_us` 补齐并翻译 |

**渲染 / 开镜 / 移动手感** —— 这类是玩家最容易直接看到、也最容易被误判成"模组就这样"的：

| 问题 | 上游移植版 | 本分支 |
|---|---|---|
| **开镜后枪管被拉长** | 瞄具缺 `zoom_model_fov` 时没有兜底，FOV 沿用导致模型拉伸 | 补 FOV 兜底 |
| **GUI 里瞄具显示黑色遮罩** | `renderItemModelIntoGUI` 不调用 `handleCameraTransforms`，`transformType` 残留上一次的 `FIRST_PERSON`，配件被误判成 3D 上下文 | 用 `currentScreen != null` 兜底，GUI 内一律走平面槽位贴图 |
| **配件预览露出光学遮罩** | 预览走裸 `BedrockModel`，`division` 骨骼（黑色遮罩 + 分划）没被隐藏 | 预览改走 `BedrockAttachmentModel` 分支 |
| **手持瞄具时仍显示准心贴图** | 未按"是否装瞄具"切换 | 装瞄具后隐藏准心 |
| **枪丢在地上显示成 2D** | 掉落物上下文没走 3D 渲染路径 | 按 transform 类型选择模型 |
| **枪口火焰偏灰** | 火焰纹理的颜色/混合状态不对 | 修正 |
| **站/走的散布档位判错** | 用速度而非位置差判断 `isMoving`，站着也可能被判成移动 | 改为位置差判断（BUG A） |
| **切射击模式后射速/伤害不变** | `fire_mode_adjust` 的 `rpm`/`damage` 未叠加；脚本枪那条路径完全没补 | 两条射击路径都叠加（BUG B/C） |
| **换弹时长与上游不一致** | `feeding=1.0`、`finishing=0.5`，且空仓换弹塌缩成战术值 | `2.0 / 2.5 / 2.0 / 2.5`，空仓与战术严格区分 |
| **拉栓枪少字段就卡住** | 缺 `bolt_action_time` 时默认 0.5s | 主键 + `bolt_time` 兜底 + 默认 0 |
| **燃烧弹不生效** | `ignite` 键名读错，且 `evalIgnite` **零调用点**，解析完从未合并进子弹 | 键名修正 + 接入两条射击路径（OR 语义） |
| **散布分档全塌到 stand** | 移动/蹲/卧都回退 `standBase=5` | 各自独立默认（move 5.75 / sneak 3.5 / lie 2.5） |
| **切枪/收枪动画时长** | 无字段 | `draw` / `put_away` 0.4s、`sprint` 0.2s |

**其余健壮性 / 构建修复：**

| 项 | 上游移植版 | 本分支 |
|---|---|---|
| **子弹命中包不校验** | `ServerMessageBulletHitBlock` 解码前不检查长度、面索引、坐标有限性，坏包可让客户端异常 | 解码前校验长度、面索引 `0..5`、坐标 `isFinite` |
| **冒烟场景状态残留** | 重置时不清完成标志与场景位置 | 一并重置 |
| **MouseTweaks 的 refmap 名错** | 配置里写的是旧名，mixin 实际不生效 | 改为构建实际生成的 `mixins.tacz.refmap.json` |
| **Blockbench 模型直接编译失败** | 用了 1.12.2 不存在的 `SimpleReloadListener.reloadResources` | 改为 `IResourceManagerReloadListener.onResourceManagerReload` |

> 这一批共 40 项，覆盖上述全部条目。修复过程中一并处理了修复本身可能引入的边界问题
> （备份恢复遇到不可覆盖文件时中断、第三人称特效被误关、`Files.walk` 惰性遍历的异常绕过 catch 等）。

### 仍未做的（明确列出，不假装）

| 项 | 现状 | 说明 |
|---|---|---|
| **CraftTweaker 兼容** | ❌ 全仓零代码 | 整合包无法用脚本增删改枪械/配件/弹药配方 |
| **GroovyScript 兼容** | ❌ 全仓零代码 | 同上 |
| **JEI 制作台配方分类** | ❌ 只有 NBT 子类型注册 | 玩家在 JEI 里查不到枪械/配件的合成方式 |
| **会开枪的 NPC** | ❌ 缺 `EntityAIBase` | 底层 `IGunOperator` 已面向任意 `LivingEntity`，第三方理论上可自行实现 |
| **渲染 / 动画管线重构** | ❌ 未开始 | 原作者自述"推测是老旧 AI 反向移植，需要大重构" |

---

## 三、构建

环境：

- JDK **21**（RetroFuturaGradle 需要）
- Gradle Wrapper 自带，无需单独安装

```bash
# 编译（跳过测试）
./gradlew build -x test

# 产物
# build/libs/TACZ-Legacy-<version>.jar
```

运行时依赖（1.12.2 客户端）：

- Forge `14.23.5.2859`
- [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixinbooter) 11.x
- Forgelin-Continuous（1.12.2 的 Kotlin 运行时）

> `build/` 与 `release/` 都不入库；构建产物只发在 GitHub Release（见上方「下载与安装」）。

---

## 四、测试

单元测试（Kotlin / JUnit 4），`./gradlew test` 当前覆盖：

- `ExclusiveAttachmentNumericTest` — exclusive 数值（散布 4.875、后坐 percent、重量 addend）
- `InaccuracyDefaultsPerKeyTest` — inaccuracy 逐 key 缺省
- `AttachmentModifierReaderPriorityTest` — recoil / inaccuracy 新旧字段优先级
- `InaccuracyEvalScopeAndExclusiveReplacementTest` — default 键作用域 + 整份替换
- `ExclusiveAttachmentEntryIsolationTest` — 条目级隔离（容器 / 值 / 裸数字 / 无命名空间）
- `ModifierConsumptionCoverageTest` — 守门：每个注册的 modifier id 都必须有玩法消费点
- `ModifierFunctionSandboxTest` — Lua `function` 沙箱（`os` / `io` / `luajava` 不可达、预编译、错误回退）
- `AttachmentModifierHardThresholdScopeTest` — 坏 modifier 硬阈值的作用域（独占条目丢弃 vs 普通配件保留）
- `BedrockModelParsingTest` — 基岩模型解析、版本识别边界（含 `format_version` 非数字）

游戏内验收走手工流程：装包 → 射击 / 换弹 / 拉栓 / 开镜 / 装配件 / 近战，
配合 `/tacz reload`、`/tacz list_pack`、`/tacz issues` 观察加载与运行时告警。

多人同步在同一台机器上验证：起一台 Forge 1.12.2 专用服，客户端用 `--server` 连入。

---

## 五、已知限制

**平台做不到的**

1. **`PlayerAnimator` 第三人称动画** —— 依赖的 [playerAnimator](https://modrinth.com/mod/playeranimator)
   最低只支持 1.16.4，**没有 1.12.2 版本**。`display` 里的 `player_animator_3rd` 会被解析但不会生效。
2. **KubeJS 脚本** —— 上游是 JS、本项目的脚本层是 Lua，引擎不互通。
3. **1.20.1 特有的 datapack 能力** —— 1.12.2 的配方/战利品系统不完备，部分类型无法 1:1。
4. **联机时的模型/贴图** —— 服务端只下发数据、脚本、lang 与 display 定义；
   模型、贴图、音效文件体积过大仍由客户端本地包提供（上游架构亦如此）。
   远程客户端只保证"数值与表现定义一致"，**外观需要本地装包**。

**需要手动操作或已知边界**

5. **`/tacz reload` 后 lang / vanilla 侧贴图需要 F3+T**。
   在集成服环境下自动调用 `refreshResources()` 会触发
   `MinecraftServer.runScheduledTasks → addScheduledTask` 的递归并 StackOverflow，
   因此该路径只重新注册资源包，不做自动重载。
6. **战利品注入只在世界（战利品表）加载时生效**，`/tacz reload` 不会重注入已加载的表。
7. **多人同步只在专用服启用**；单人/局域网客户端本来就有本地包，无需同步
   （联调可用 `-Dtacz.syncGunPack=true` 强制开启）。
8. **`format_version` 次版本 < 12 的基岩模型不建模型**（`1.8.0`/`1.9.0`/`1.11.0`）——
   与上游行为一致，会记一条 WARN。

**上游本来就没有的**

9. **皮肤系统**：上游只留了空壳（`skinIndexMap` 从不写入、`getSkinIndex()` 无调用方），
   皮肤文件不会被任何版本加载。
10. **`AimInaccuracyModifier`**：上游已废弃且未注册。

---

## 六、文档

本仓库只保留**代码**与**两份说明**：

- `README.md`（本文）—— 项目定位、构建、安装、已知限制
- [`docs/UPSTREAM-DIFF.md`](docs/UPSTREAM-DIFF.md) —— 与上游 1.20.1 的差异记录：
  哪些已对齐、哪些是平台约束、哪些明确不做，按系统逐项对照

开发过程中的调研记录、审计记录与逐条修复日志**不随仓库发布**。
需要确认某个具体行为时，以**代码**为准。

---

## 七、许可

遵循上游 TACZ 的版权与许可约束；TaCZ-Legacy 作为社区移植版亦沿用其许可。
本分支仅包含移植开发所需的代码与资源，不重新分发上游资产的所有权。
