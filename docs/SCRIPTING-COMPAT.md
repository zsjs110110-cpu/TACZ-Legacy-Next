# 脚本化与展示层兼容（CraftTweaker / GroovyScript / JEI-HEI）

> 面向整合包作者与后续接手这块的 agent。记录 2026-11-06 落地的三项兼容、各自的**验证状态**，
> 以及还没被证实的部分。不要把这些当成"已完成"读——按表里的验证等级用。
>
> **2026-11-06 晚更新**：三项都已在装了真实 JEI/CT/GroovyScript 的测试实例里跑通（§7 是原始证据），
> 期间修掉三个真缺陷：lang 键只进了 `.json`（§4.2）、JEI 材料格不显示数量（§4.4，用户按 R 实测发现）、
> 以及 23 条制作台配方被整条丢弃（§8，配方总数 298 → 316）。

## 1. 共同地基

三条兼容读的都是同一层，所以先说清这层。

TaCZ 的制作台配方**不是** vanilla `IRecipe`：它们是枪包里的
`data/<ns>/recipes/*.json`，类型串 `tacz:gun_smith_table_crafting`（默认包 160 条：枪 47 / 配件 92 / 弹药 21），
解析成 `LegacyGunSmithRecipe`（internal），制作时由 `craft()` 直接扣背包。
因此 CraftTweaker 自带的 `recipes.remove()` 与 JEI 的自动配方列表**都看不见它们**。
另有 10 条工作台方块本身是 `assets/tacz/recipes` 下的 `forge:ore_shaped` 原版配方，那部分 CT/GroovyScript 原生就能改，本项目不重复提供。

公开入口：`com.tacz.legacy.api.gunsmith.GunSmithRecipes`

| 方法 | 语义 |
|---|---|
| `all()` / `get(id)` / `exists(id)` | 读当前生效配方（已含脚本改动） |
| `remove(id): Boolean` | 删除一条；返回**此前是否生效存在**（重复删除返回 false） |
| `add(id, json): Boolean` | 新增/覆盖；json 结构与枪包配方文件同构；返回是否覆盖了同名条目 |
| `clearChanges(): Int` / `changeCount()` | 撤销全部脚本改动 / 改动条数 |

改动是**叠加式**的：基础表在读取时才套上 overlay，所以脚本改动**活过 `/tacz reload`**。
这是刻意的——1.12.2 的 CraftTweaker 不能重跑脚本（只有 1.11- 的 `/ct reload` 与 1.14+ 的 `/reload` 可以），
重载即失效等于逼玩家重启游戏。能重跑脚本的加载器应先 `clearChanges()` 再重建。

事件钩子：`com.tacz.legacy.api.event.GunPackReloadedEvent`（`api/event/GunPackEvents.kt`），
在快照替换之后广播；失败分支不广播。
**线程**：`/tacz reload` 在服务端线程、断线重连在客户端线程，客户端消费者必须自己
`Minecraft.getMinecraft().addScheduledTask { }` 切回主线程。

## 2. CraftTweaker 4（ZenScript）

ZenScript 名 `mods.tacz.GunSmithing`，实现在 `integration/crafttweaker/GunSmithingTweaker.kt`。

```js
import mods.tacz.GunSmithing;

// 下面每一行都在活环境里跑过；没有跑过的写法（例如对返回的 List 取 .length）不写在这里
GunSmithing.remove("tacz:ammo/9mm");
print(GunSmithing.exists("tacz:ammo/9mm"));              // false
print(GunSmithing.materialCount("tacz:gun/aug"));        // 4
GunSmithing.add("tacz:gun/my_test",
  "{\"result\":{\"type\":\"gun\",\"id\":\"tacz:ak47\"},\"materials\":[{\"item\":{\"tag\":\"forge:ingots/iron\"},\"count\":3}]}");
GunSmithing.clear();                                     // 撤销全部脚本改动，返回被清掉的条数
```

两个坑是 2026-11-06 在真实 CT4 环境里跑出来的，不是推测：

- `@ZenGetter` 在 CT4 的**静态类**上不生效，`mods.tacz.GunSmithing.recipes` 会报
  `No such static member`。取列表要调方法 `getRecipeIds()`。
- `result` 必须是枪包那套结构（`type` 取 `gun` / `ammo` / `attachment` / `custom`）。
  写成 `{"item":"tacz:modern_kinetic_gun","data":{...}}` 会落到 `custom` 分支走
  `CraftingHelper`，字段不合就整条解析失败并被拒绝。

第三个坑属于 ZenScript 语言本身，写探针脚本时踩到：

- **文件里不能写 `int x = 0;`**，会得到 `Error parsing tacz.zs:24 -- Invalid expression, last token: int`，
  而**解析失败是整文件级的**——前面所有 `print` 也一行都不会执行。数数要么用 GroovyScript 侧，
  要么写成 ZenScript 支持的声明形式；探针脚本尽量只用 `print(...)` + 方法调用。

编译期依赖：`build.gradle.kts` 里条件引用 `../mc-devlibs/CraftTweaker2-1.12-4.1.20.715.jar`
（从本地整合包副本取出；CT4 没有可公开解析的 maven 坐标）。jar 不在时只打印 WARN，compat 类不编译，
主模组不受影响。运行期由整合包自己装 CT，本项目**不打包** CT。

## 3. GroovyScript（1.12.2）

GroovyScript 的模型是"脚本直接访问 Java 代码"，所以它**不需要我们写适配层**——
只要有一层稳定的公开 API 就够了，也就是第 1 节那个面。

两个 2026-11-06 实测出来的接入细节（都是踩过才写下来的）：

- 脚本目录是 **`<gameDir>/groovy/<stage>/*.groovy`**，不是 `scripts/`。`scripts/` 下的文件会被
  静默忽略；GS 首次运行会自己生成 `groovy/postInit/main.groovy` 作为示例。
- 公开 API 的每个方法都必须带 `@JvmStatic`。Kotlin `object` 的成员默认是单例实例方法，
  Groovy 的静态调用会报 `MissingMethodException: No signature of method: static ...all()`。

示例（`groovy/postInit/tacz.groovy`）：

```groovy
import com.tacz.legacy.api.gunsmith.GunSmithRecipes

GunSmithRecipes.all().each { println it.id }
GunSmithRecipes.remove("tacz:ammo/9mm")
// 热重载时先撤销再重建，避免同一条改动叠加两次
def n = GunSmithRecipes.clearChanges()
println "cleared ${n} script changes"
```

**运行期热重载（2026-11-06 实测，之前的"没跑过"已作废）**：GroovyScript 自带
`/gs reload`（别名 `/groovyscript`、`/grs`），会重扫 `groovy/<stage>/` 并重跑脚本，
实测 3 次 reload 都正确，且**我们的叠加层活过 reload**（第二次 reload 时 `remove` 同一条返回
`false`、总数不回弹）。联机模式下它被禁（"Reloading in multiplayer is currently not allowed
to avoid desync"），单人可用。两个 JEI 相关开关：

- `/gs reload`：只重跑脚本，**不动** JEI 已注册的列表（实测删除一条后 `GunSmithRecipes` 295、
  JEI 仍 296）。
- `/gs reload --entire-jei`：连 JEI 注册表一起重建，我们的 `register()` 会再跑一次，
  列表跟着变成 295，且**去重后仍 295**（没有重复注册）。

也就是说：装了 GroovyScript 的整合包，脚本改完配方不必按 F3+T，`/gs reload --entire-jei`
就是运行期刷新路径；没装 GroovyScript 才需要 F3+T。

**诚实标注**：这一项交付的是**可被脚本直接调用的公开面 + 示例**，不是"接进了它的 compat
注册表"。它的文档明确写着"配方注册表的热重载必须由模组作者手动实现"——我们用"叠加层 +
重载事件"满足了这条要求，并在 GroovyScript 1.5.0 + MixinBooter 11.17 环境里跑通。

## 4. JEI / HEI

`integration/jei/GunSmithTableCategory.kt` + `GunSmithRecipeWrapper`，注册在
`integration/jei/TACZJeiPlugin.kt` 的 `registerCategories` / `register`：

- 一个分类 `tacz.gun_smith_table`，产出在右、材料从左侧单行排开。实测材料条数 **1~6**
  （3 个包 235 条配方：1 格 11 条 / 2 格 91 / 3 格 73 / 4 格 50 / 5 格 9 / 6 格 1），
  第 6 格右边界 114 < 产出格 136，单行放得下，所以不做上游"≥7 条换双行"的分支。
  （原先这里写"最多 4 种"是错的，5~6 条的配方确实存在。）
- 槽位框一格一格挂在 JEI 自己的 `getSlotDrawable()` 上（`IGuiIngredientGroup.setBackground(index, frame)`），
  分类背景保持 `createBlankDrawable`。材料格与产出格之间用一个 `Gui.drawRect` 叠出的实心小箭头
  （x=116~130，垂直居中）填掉空白。
  **走过的弯路**：先试过自己写合成背景 `SlotFramesDrawable`，在 `IDrawable.draw` 里
  `pushMatrix + translate` 逐格贴——画出来只有一格且压在物品上。原因是
  `IDrawable.draw(mc, a, b)` 的两个 int 是鼠标坐标不是位置。所以别自己算位置，
  槽位框就交给 `setBackground`。
- 催化剂：`GUN_SMITH_TABLE` + `WORKBENCH_A/B/C` 四件（上游是按工作台分别建 RecipeType，这里合并成一个分类）。
- 标题键 `jei.tacz.gun_smith_table.title` 已加进 en_us / zh_cn / zh_tw。
- **不做"+合成"按钮**：工作台容器只有隐藏槽（`GunSmithTableContainer`），JEI transfer 拿不到可写槽位，接上必然失败。

时序是安全的：枪包快照在 `preInit` 建立（`TACZLegacy` → `DefaultGunPackExporter`），
JEI/HEI 的插件注册在 `FMLLoadCompleteEvent`，晚于它。

**活重载不刷新**：`IJeiHelpers` 不暴露配方注册表，HEI 的 `IRecipeRegistry` 需要 `@Inject` 才拿得到。
所以 `/tacz reload` 改完配方后要按一次 **F3+T** 重跑注册（与 lang 那条已知限制同一个操作）；
装了 GroovyScript 时 `/gs reload --entire-jei` 是等效的运行期刷新（见 §3）。

### 4.1 运行期注册表实测（不靠按键）

桥不能按 JEI 的 R 键，所以改用 GroovyScript 直接查 JEI 运行期注册表
（`mezz.jei.Internal.getRuntime().getRecipeRegistry()`），实测结果：

| 查的东西 | 结果 |
|---|---|
| 分类总数 / 其中我们的 | 6 / 1（uid `tacz.gun_smith_table`） |
| 标题 | `制作台配方`（修复后，见 §4.2） |
| 背景尺寸 | 160×36 |
| 配方条数 | 295（= 当时叠加层的条数，脚本删掉的 ak47/aug 确实不在里面） |
| 催化剂 | `gun_smith_table` + `workbench_a/b/c` 四件 |
| wrapper 类 | `com.tacz.legacy.integration.jei.GunSmithRecipeWrapper`，材料槽数与 JSON 一致 |
| **295 条配方的布局构建** | **成功 295 / 失败 0**（`createRecipeLayoutDrawable` 会走我们的 `setRecipe()` 槽位代码） |

槽位坐标本身是静态核过的：产出 `x=136,y=9`（16×16，右边界 154 ≤ 160），材料从 `x=6` 每 18px
一个（最多 4 个 → 右边界 78）。**仍未像素验证**的只有"打开 JEI 页面后的观感"。

### 4.2 这一轮修掉的真缺陷：lang 键只进了 `.json`

`jei.tacz.gun_smith_table.title` 当时只加进 `assets/tacz/lang/*.json`，而 1.12.2 的
`LanguageMap` 读的是 `assets/tacz/lang/*.lang`（`key=value` 格式，JSON lang 是 1.14+ 的事）。
本项目的 lang 目录**两种格式都有**（20 个 `.lang` + 若干 `.json`，上游移植时留下的），
所以只加 JSON 的键在运行期等于没加——实测 `I18n.format` 原样返回键名。
已补 `en_us.lang` / `zh_cn.lang` / `zh_tw.lang`（1.12.2 的 `LanguageMap` 有 en_us 兜底，
其余 17 个 locale 不必逐个加），重新构建部署后运行期显示"制作台配方"。

**给后续接手的规则**：新增任何 lang 键，先确认它进的是 `.lang`；`.json` 那份只是上游遗留，
在 1.12.2 上不会被读。

### 4.3 HEI 与真 JEI 4.x

本项目编译期用的是 HEI（`had-enough-items-557549`，其 `mcmod.info` 自报 `modid: jei, version 4.2x`），
它保留 `mezz.jei.api` 的 4.x 包形状。我们只用了 4.x 共有成员，**没有**用 HEI 独有的
`IRecipeRegistry`、`createDrawableIngredient` 等。2026-11-06 已用**真 JEI 4.24.0.1042** 跑通
（注册、内容、布局构建全绿），这条从"间接证据"升级为"已验证"。

### 4.4 用户按 R 实测出的缺陷：材料只显示物品、不显示数量

第一版把 `material.options` 直接交给 JEI（`slots.set(slot, material.options)`），
而 `Ingredient.getMatchingStacks()` 给的每条候选 `stackSize` 都是 1——JEI 槽位上的数字
取自要画的那个 stack，所以**数量整个丢失**（`material.count` 解析出来了，只是没往下传）。
用户 2026-11-06 19:50 的截图就是这个：AK47 配方显示铁锭/青金石/木板三个图标，没有任何数字。

改法：材料格与 `IIngredients` 都改传"带数量的拷贝"（`GunSmithMaterialView.sizedStacks()`），
产出本来就走 `view.result`（`buildGunResult`/`buildAmmoResult` 已按 `result.count` 建 stack），
所以只有材料侧需要改。注意 `ItemStack.stackSize` 在本工程的开发映射下是私有字段，
写 `copy().apply { stackSize = n }` 编译不过，用 `copy().also { it.grow(n - 1) }`。

**验证方式（不需要按键）**：用 GroovyScript 直接调 JEI 的
`mezz.jei.gui.recipes.RecipeLayout.create(0, cat, wrapper, focus, 0, 0)`，
再读 `layout.itemStacks.guiIngredients` 里每格 `getDisplayedIngredient()` 的数量，
与 `GunSmithRecipes` 视图里的 `material.count` 逐条比对：

```
[CNT] 检查槽位 = 1123 数量不符 = 0 空槽 = 21
[CNT] 背景实现 = com.tacz.legacy.integration.jei.SlotFramesDrawable 160x36
[CNT] AK47 每条材料 = [38×铁锭, 6×青金石, 10×橡木]
```

**剩下的 21 个空槽**是数据问题不是渲染问题：这些配方（`contract:` 包的几个瞄准镜）
某条材料引用的物品在本机不存在，`候选数=0` 但 `需要=3` 之类。制作台界面同样画不出来，
属于 §7.4 那一类"枪包引用了不存在的物品"。

### 4.5 排版重做（同一天第二轮，用户又发了一张图）

数量修好后用户再按 R，暴露的是观感：物品浮在空白上，而我那版自算坐标的槽位框只画出一格、
还压在物品上。改成 JEI 的正路之后：

- 每格用 `slots.setBackground(index, guiHelper.getSlotDrawable())` 挂框；
- 材料区与产出之间加一个实心小箭头（`Gui.drawRect` 逐像素叠成三角形，x=116~130）；
- 分类背景仍是 `createBlankDrawable(160, 36)`。

同样的思路做**不靠眼睛的验证**：`RecipeLayout.create(0, cat, wrapper, focus, 0, 0)` 造出真实布局，
再读每格的 `getRect()` 与私有 `background` 字段：

```
[LAY] 槽位总数 = 1123 无槽位框 = 0 坐标错 = 0 数量错 = 0
[LAY] 样本 = [2材料 槽1 java.awt.Rectangle[x=6,y=9,width=18,height=18] bg=true, ...]
```

也就是说"每格都有框、每格都在我算的坐标上、每格数量正确"这三件事是**程序化确认**过的；
只有"看起来好不好看"仍然必须用户眼睛判（箭头颜色 `0xFF6E6E6E`、框与物品是否贴合）。

顺带记一条 GroovyScript 沙箱的限制：`java.lang.reflect.Method` 在黑名单里，
脚本里写 `obj.class.declaredMethods.find { ... }` 会得到
`SandboxSecurityException: Tried to access class java.lang.reflect.Method, but class is blacklisted!`。
探针只能**调用**方法，不能反射内省。

## 5. 验证状态（别把编译当验证）

| 项 | 状态 | 依据 |
|---|---|---|
| 叠加层 add/remove/clear/changeCount 语义 | **已测** | `src/test/kotlin/com/tacz/legacy/api/gunsmith/GunSmithRecipesOverlayTest.kt` 4 条用例，全绿；其中"重复删除要返回 false"这条**测出并修掉了一个真 bug**（原先查的是基础表，第二次删除仍报 true） |
| 脚本改动活过快照重建 | **已测** | 同上 `scriptChangesSurviveASnapshotRebuild`，活环境 `/gs reload` 二次确认 |
| 非法 JSON 的错误契约 | **已测** | 单测 + 活环境 CT：`add("{not json")` 与 `add('{"materials":[]}')` 都返回 false 且不中断脚本 |
| `add(id, json)` 成功路径 | **已测** | 活环境：CT 侧新增后 `exists=true`、`clear()` 后回到 false；GS 侧把 `daffas_arsenal:gun/auxur` 原样加回，制作台 Rifle 页第一行恢复 Steyr ACR |
| CraftTweaker ZenScript 实际调用 | **已测** | CT 4.1.20.715 + MTLib 3.0.7，`scripts/tacz.zs` 14 行探针，0 错误 |
| GroovyScript 实际调用 | **已测** | GroovyScript 1.5.0 + MixinBooter 11.17，`groovy/postInit/*.groovy`，含 `/gs reload` 与 `--entire-jei` |
| JEI 分类注册与内容 | **已测** | 真 JEI 4.24.0.1042：分类/条数/催化剂/标题/295 条布局构建，见 §4.1 |
| JEI 分类打开后的观感 | **部分已测** | 用户两次按 R：第一次查出 §4.4 数量缺失（已修），第二次查出 §4.5 排版问题（已改成 JEI 逐格挂槽位框 + 箭头）。"每格有框 / 坐标正确 / 数量正确"用 `RecipeLayout` 程序化确认过（1123 格 0 异常），**只剩观感本身**需要人眼 |
| 制作台 UI 跟随脚本改动 | **已测** | 同一会话三张图：基线第一行 Steyr ACR → `remove` 后变 G36C → `add` 后恢复 Steyr ACR |
| `forge:partial_nbt` 超集匹配语义 | **已测** | `GunSmithMaterialParsingTest` 6 条断言（正确枪 / 带额外 NBT / 别的 GunId / 缺键 / 无 NBT / 空栈）+ 嵌套 compound 两条 |
| 18 条被丢弃配方重新生效 | **已测** | 活环境：解析失败 23 → 3，配方总数 298 → 316，JEI 同步 316，`uji`/`ammo_mod_fmj`/`556smite` 都能查到且材料图标正确（§8.5） |
| 端到端实际合成（partial_nbt 材料扣减） | **已测** | 活环境生存模式跑完整生产路径：错枪 canCraft=false → 给齐材料 true → craft 出 Dual MAC10 → 材料扣光、产物进背包（§9.1）。internal 的 `getSnapshot` 用 `$module` 后缀名按字符串调用即可 |
| 空材料格的可见性 | **已测** | 21 格全是 `forge:gems/amethyst`；JEI 改画屏障+缺料名+数量（§9.2），矿典映射的 3 个真 bug 一并修掉并有单测与活环境证明 |

## 6. 下一步（按性价比）

1. ~~人工按一次 JEI 的 R~~（已做，查出 §4.4 的数量缺失并修好）。剩下只需要人眼确认一件事：
   `SlotFramesDrawable` 画的槽位框是否正好压在物品格上（坐标是 6+18i / 136，y=9，
   与 `setRecipe` 用的是同一组常量，静态看应当对齐）。
2. 附件查询分类（上游的 `AttachmentQueryCategory`）还没做：反查逻辑已存在
   （`matchesHeldItem` → `allowAttachmentTags` 递归标签展开），lang 键
   `jei.tacz.attachment_query.title` / `.more` 也已经存在但零引用，接上即可。
   **注意**：这两个键在 `.lang` 里有，新增键时按 §4.2 的规则同时补两处。
3. ~~`forge:partial_nbt`~~ **已实现**（2026-11-06 22:31 构建，见 §8）：daffas_arsenal 的 12 条配方回来了。
   注意我们是在自己的材料解析里支持的，**没有**注册 Forge 的 `IIngredientSerializer`，
   所以只覆盖制作台配方，不覆盖枪包里的原版工作台配方。
4. ~~裸字符串 item~~ **已实现**（同上，见 §8）：`"item": "namespace:path"` 与 `"item": "#forge:ingots/iron"`
   两种 1.15+ 写法都接受；`custom` 产出的 `result.item` 同样接受字符串。

## 7. 活环境验证记录（2026-11-06 晚）

环境：测试实例 `mc test/TEST`，Forge 14.23.5.2859 + OptiFine G5，本轮新装
`jei_1.12.2-4.24.0.1042`、`CraftTweaker2-1.12-4.1.20.715` + `MTLib-3.0.7`、`groovyscript-1.5.0`
（前置 `!mixinbooter-11.17` 本来就在）。构建产物 `TACZ-Legacy-0.1.1.jar`（18:47，含 lang 修复）。

### 7.1 配方基线

枪包解析出的基础表当时是 **298 条**（不是本文早先写的 160——160 只是默认包，测试实例还装了
daffas_arsenal、fatal_contract、jccrossbow）。叠加层按 `298 → CT 删 1 → GS 删 2 加 1` 逐级核对：
CT 阶段后 GS 看到 297，GS 阶段后 296，JEI 注册到的也是 296。三层数字对得上，说明读的是同一张表。
**注**：§8 之后这张表变成 **316** 条（18 条原本被丢弃的配方解析回来了），本文其余章节的 298/296
都是 §8 之前的实测值，保留原样作为证据。

### 7.2 CraftTweaker（`scripts/tacz.zs`，14 行，0 error）

```
exists tacz:gun/aug before = true        exists tacz:gun/ak47 before = true
materialCount tacz:gun/aug = 4           remove tacz:gun/aug = true
exists tacz:gun/aug after remove = false add valid = false        # false = 没覆盖同名条目，即"新增成功"
exists tacz:gun/ct_added = true          add malformed = false
add without result = false               clear removed = 2        # 1 条新增 + 1 条删除
exists tacz:gun/ct_added after clear = false
exists tacz:gun/aug after clear = true   # 撤销后回到枪包原状
final remove tacz:gun/aug = true         exists tacz:gun/aug final = false
```

### 7.3 GroovyScript（`groovy/postInit/tacz.groovy`）

冷启动：`total seen = 297`、`sees tacz:gun/aug = false` → **CraftTweaker 的删除对 GroovyScript 可见**，
加载顺序是 CT(INITIALIZATION) 早于 GS(postInit)。随后 `remove` 两条 ak47、`add` 一条，`total now = 296`。
`/gs reload` 第二次跑同一段脚本时 `remove` 返回 `false`、总数不回弹——叠加层是幂等的，
不会越 reload 越多。

### 7.4 顺带查出的数据面问题：23 条配方被整条跳过

每次快照首次解析时 `debug.log` 有 23 条 `Failed to parse gunsmith recipe` WARN，按原因分：

| 原因 | 条数 | 归属 | 定性 | 修复后 |
|---|---|---|---|---|
| `Unknown ingredient type: forge:partial_nbt` | 12 | daffas_arsenal 枪械 | 1.12 Forge 没有这个 ingredient 类型（1.20 才有） | **已修**（§8.1） |
| `Unknown item 'minecraft:crying_obsidian'` | 6 | 默认包 5 条 `ammo_mod_*` + contract 1 条 | 默认包引用 1.21 物品，属于**我们自己的数据** | 默认包 5 条**已修**（换 `minecraft:obsidian`）；contract 那 1 条是第三方数据，仍然跳过 |
| `Expected item to be a JsonObject` | 2 | contract | 是枪包里混的**原版工作台配方**，本就不该由制作台解析 | **已修**（§8.3：按 type 跳过，不再误解析） |
| `Unknown item 'minecraft:popped_chorus_fruit'` | 1 | contract | 1.14 物品 | 无法修，仍然跳过 |
| `Unknown item 'minecraft:netherite_scrap'` | 1 | contract | 1.16 物品 | 无法修，仍然跳过 |
| `Unknown item 'minecraft:glistering_melon_slice'` | 1 | contract | 1.13 扁平化改名，同一物品 | **已修**（§8.2 别名表） |

同一轮改动后活环境实测：解析失败 **23 → 3**，制作台配方总数 **298 → 316**，
JEI 注册到的条数同步变成 316（含 `daffas_arsenal:gun/uji`、`tacz:attachments/ammo_mod_fmj`）。
剩下 3 条都是第三方枪包引用了 1.12 根本不存在的物品。

### 7.5 制作台 UI 的删除/新增对照（同一会话三张图）

Rifle 页第一行：基线 `Steyr ACR` → `GunSmithRecipes.remove("daffas_arsenal:gun/auxur")` 后变
`G36C` → `GunSmithRecipes.add("daffas_arsenal:gun/auxur", <原 JSON>)` 后恢复 `Steyr ACR`
（预览模型与自带配件一起回来）。截图：`F:\new work\_ui_A_noaug.png`、`_ui_B_noauxur.png`、`_ui_C_crop.png`。

### 7.6 复现方式与测试残留

探针脚本留在实例里，**已改名加 `.off` 后缀**让加载器忽略它们（否则下次启动会静默删掉 AK47/AUG）：

- `scripts/tacz.zs`（CraftTweaker 探针）
- `groovy/postInit/tacz.groovy`（GroovyScript 探针）
- `groovy/postInit/jei_probe.groovy`（JEI 运行期注册表探针）
- `groovy/postInit/ab_ui.groovy`（制作台删除/新增对照）
- `groovy/postInit/zz_count_probe.groovy`（JEI 槽位数量逐条比对，§4.4）
- `groovy/postInit/zz_layout_probe.groovy`（JEI 槽位框与坐标逐格比对，§4.5）
- `groovy/postInit/zz_craft_e2e.groovy`（端到端合成，§9.1；会临时切生存模式并清空背包，跑完切回创造）
- `groovy/postInit/zz_missing.groovy`（矿典映射与缺料显示，§9.2）

要再跑一遍：去掉 `.off`，启动游戏，进世界，`/gs reload --entire-jei`，看 `logs/groovy.log`
与 `crafttweaker.log`。`crafttweaker.log` 每次启动覆盖，`groovy.log` 追加。

### 7.7 第二轮（用户按 R 之后）

用户 2026-11-06 19:50 的截图暴露了 §4.4 的数量缺失。修复后重新构建部署（jar 19:57），
用 §4.4 那个 `RecipeLayout.create` 探针逐条比对 1123 个槽位：**数量不符 0**，
另有 21 个空槽（枪包引用了本机不存在的物品，不是渲染问题）。
测试结束后探针已重新 `.off`。当时配方基线还是 298 条，§8 之后变成 **316** 条。

## 8. 配方解析兼容性（D-32，2026-11-06 22:31 构建）

改动集中在 `LegacyGunSmithingRuntime` 的材料解析与匹配，没有新增依赖，也没有注册 Forge 的
`IIngredientSerializer`（那样会去动原版工作台配方，风险面更大）。

### 8.1 `forge:partial_nbt`

1.20 Forge 才有这个 ingredient 类型，1.12.2 的 `CraftingHelper` 见到就抛 `Unknown ingredient type`，
整条配方被丢弃。现在由 `parsePartialNbtSample` 自己读成"物品 + 部分 NBT"的样本栈，
材料结构多了一个 `partialSample` 字段。

**匹配语义（关键）**：不能走原版 `Ingredient.apply`——原版比的是 NBT **全等**，
而枪械的 NBT 里还有弹药数、已装配件、射击模式等运行时字段，全等永远不成立。
所以 `LegacyGunSmithIngredient.matches` 在 `partialSample != null` 时改成：

> 物品与元数据相同，**且**手上物品的 NBT 是样本 NBT 的**超集**（嵌套 compound 递归同样判定）。

样本栈同时充当图标来源，所以 JEI 与制作台里这一格会渲染成"那把具体的枪"而不是通用枪械物品
（活环境实测 `uji` 第一格 = `2×MAC10 nbt=GunId:"daffas_arsenal:makten_single"`）。

判定逻辑单独抽成 `PartialNbtMatcher.contains(actual, required)`，与物品注册表无关因此可单测：
`GunSmithMaterialParsingTest` 覆盖"正确枪 / 正确枪+额外 NBT / 别的 GunId / 缺键 / 无 NBT / 空栈"
与嵌套 compound 的两种情形。

匹配点全部换到新方法上：`buildConsumptionPlan`（真正扣材料）、`ingredientCount`（界面上的 x/y 计数）、
`sumOfMatching`。少换一处就会出现"界面说够、按下合成没反应"。

### 8.2 物品 id 解析

- 接受 1.15+ 的裸字符串写法 `{"item": "minecraft:obsidian"}`；`"#forge:ingots/iron"` 视为标签。
- `custom` 产出的 `result.item` 同样接受字符串（原来只认嵌套对象，报 `Expected item to be a JsonObject`）。
- 1.13 扁平化改名别名表 `FLATTENING_RENAMES`，目前只有一条：
  `minecraft:glistering_melon_slice` → **`minecraft:speckled_melon`**。
  目标名是实测定的：本机物品表里含 melon 的注册名只有
  `melon / speckled_melon / melon_seeds / melon_block`，**没有** `golden_melon`
  （第一版按"1.12 叫 golden_melon"写，别名不命中，活环境探针纠正）。
  往这张表加条目的规则：只收"同一物品换了 id"，不要塞不同物品。
- 物品不存在时，报错同时带上原名与别名结果，便于区分"别名没命中"和"真没这个物品"。

### 8.3 枪包里混原版工作台配方

`data/<ns>/recipes/**` 里 `type` 不是 `tacz:gun_smith_table_crafting` 的文件（fatal_contract 有 2 个
`minecraft:crafting_shaped`）现在**按类型跳过**，只打 debug 日志，不再被当成制作台配方误解析。
后果要说清楚：1.12.2 没有 datapack，这类文件本来也不会自动生效；真要支持"枪包里的原版配方"
得把 pattern/key 注册成 1.12 的 `IRecipe`，是另一件事，本轮没做也没假装做了。

### 8.4 仍然修不了的

3 条 contract 配方引用 `popped_chorus_fruit`(1.14) / `netherite_scrap`(1.16) / `crying_obsidian`(1.16)，
1.12 没有这些物品，任何解析器都救不回来。另有 **21 个材料格候选为空**，全部是
`forge:gems/amethyst`（1.12 没有紫水晶，也没有 mod 在本机注册 `gemAmethyst`）——
物品造不出来，但"空白格"这个观感问题在 §9.2 里处理了：JEI 改画带名字的屏障。

### 8.5 活环境结果（jar 22:31）

```
解析失败：23 → 3
制作台配方总数：298 → 316
JEI 注册条数：316（含 daffas_arsenal:gun/uji、tacz:attachments/ammo_mod_fmj）
[D29] uji 材料 = [2×MAC10 nbt=GunId:"daffas_arsenal:makten_single", 2×Light Ammo Extended mag
        nbt=AttachmentId:"tacz:light_extended_mag_1", 30×铁锭, 8×青金石, 8×金锭]
[D29] 556smite 材料 = [15×红石块, 5×闪烁的西瓜, 3×火药]
```

**当时没做到的**：端到端实际合成没跑（`getSnapshot` 是 internal，名字带 `$module` 后缀，
按普通名字调不到）。**2026-11-07 已补上**，见 §9.1：按字符串名调用后缀方法即可，
完整生产路径在活环境生存模式下验证通过。




## 9. 两件"没做到"的收尾（2026-11-07）

### 9.1 端到端合成：已验证（活环境、生存模式、服务端线程）

之前卡在"GroovyScript 调不到 internal 方法"。其实只有 `getSnapshot` 是 internal（编译后名字带
`$module` 后缀），`recipes / canCraft / craft / ingredientCount` 都是 public、不带后缀。
按字符串名调用即可，不需要反射内省（沙箱禁的是 `java.lang.reflect.Method`，不是按名调用）：

```groovy
def snap = TACZGunPackRuntimeRegistry.INSTANCE.'getSnapshot$TACZ_Legacy'()
def player = FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers()[0]
```

对 `daffas_arsenal:gun/uji`（材料含两条 `forge:partial_nbt`）跑完整生产路径，实测：

```
只给错的枪(AK47×2) 时 canCraft = false          ← 超集匹配真的会拒绝别的 GunId
每种材料在手 = [2, 2, 30, 8, 8]
给齐正确材料后 canCraft = true
craft 返回 = Dual MAC10 nbt={GunId:"daffas_arsenal:uji", GunFireMode:"BURST", ...}
合成后每种材料在手 = [0, 0, 0, 0, 0]            ← 真的扣了
背包里出现产物 = true
合成后 canCraft = false
```

探针会把玩家临时切出生存再切回创造（`canCraft` 在创造模式恒真，不切就等于没测）。
脚本留在 `groovy/postInit/zz_craft_e2e.groovy.off`。

### 9.2 空材料格：能修的都修了，剩下的改成"看得见的缺"

先把 21 个空格的来源查清楚：**全部是 `forge:gems/amethyst`**（1.12 没有紫水晶）。
物品造不出来，但顺手查出矿典映射里三个真 bug，它们在任何包里都会造成同样的空格：

| 标签 | 以前映射成 | 现在 | 1.12 矿典名 |
|---|---|---|---|
| `forge:storage_blocks/iron` | `storage_blockIron`（不存在） | `blockIron` | 铁块 |
| `forge:storage_blocks/redstone` | `storage_blockRedstone` | `blockRedstone` | 红石块 |
| `forge:glass/silica` | `glasSilica`（不存在） | `blockGlass` | 各种玻璃 |
| `minecraft:stone_crafting_materials` | null（非 forge 前缀直接放弃） | `cobblestone` | 圆石 |

活环境证明：临时加一条用 `forge:storage_blocks/iron` 的配方，候选解析出 **[铁块]**；
`forge:glass/silica` 解析出 **17** 个候选。单测 `oreTagNamesMatchThe112OreDictionary` 覆盖 8 条映射。

紫水晶本身：材料模型新增 `missingTag`（解析时记下"来自哪个标签且一个候选都没有"），
一路带到公开视图 `GunSmithMaterialView.missingTag`，JEI 在 `setRecipe` 里把这种格子画成
**屏障图标 + 名字写明缺什么 + 需要的数量**，只影响显示、不参与匹配（`getIngredients` 仍给空列表，
所以搜索不会误命中屏障）。实测：

```
[MISS] 槽2 = minecraft:barrier 名字=[缺少材料：forge:gems/amethyst] 数量=3
```

lang 键 `jei.tacz.missing_material` 按 §4.2 的规则同时进了 `.lang` 与 `.json`。
制作台界面没动：它本来就有 hover 提示 `gui.tacz.gun_smith_table.missing_ingredient`。

**包作者的自救路径**（文档，不是代码）：紫水晶这类"1.12 没有但别的 mod 可能有"的材料，
只要往矿典注册对应名字即可让配方复活，例如 CraftTweaker：
`<ore:gemAmethyst>.add(<mod:some_gem>);`。我们的解析读的就是矿典，注册后无需改包。
