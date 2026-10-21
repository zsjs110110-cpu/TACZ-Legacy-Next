# 与上游 TACZ 的差异记录

> 本文记录 **TACZ-Legacy-Next（1.12.2 Forge 移植）** 相对 **上游 TACZ 1.20.1** 的差异轨迹：
> 哪些已经对齐、哪些是平台约束绕不过去、哪些有意不做。
>
> 判断行为时请以**代码与提交历史**为准；本文只做索引，不替代注释。

---

## 1. 对照基线

| | 项目 | 说明 |
|---|---|---|
| 上游 | [TACZ](https://github.com/MCModderAnchor/TACZ) **1.20.1** | 包名 `com.tacz.guns`，枪包格式的定义方 |
| 本分支起点 | `hippo08241/TACZ-Legacy` `master @ 3a22edb` | 1.12.2 移植版，包名 `com.tacz.legacy` |
| 本仓库 | `TACZ-Legacy-Next` | 在起点之上继续做兼容与还原 |

本文所说的"对齐"指**行为等价**，不是代码逐行相同：1.12.2 没有 1.20 的 `RenderSystem`、`DataPack`、`Component`、`ItemStack` 组件体系，实现手段必然不同。

---

## 2. 差异的四种性质

| 类别 | 含义 | 处理方式 |
|---|---|---|
| **A 平台约束** | 1.12.2 缺对应机制，或依赖的库没有 1.12.2 版本 | 明确记为限制；能等价实现的就等价实现 |
| **B 已对齐** | 上游有明确语义，本分支用 1.12.2 的手段做出了同样行为 | 有对应代码与测试 |
| **C 未实现** | 上游有、本分支没做 | 在 README「仍未做的」里列出，不假装 |
| **D 修正原移植版** | 移植版自己的 bug / 空实现，与 1.20 无关 | 直接修掉，记录在 README |

---

## 3. 逐系统对照

### 3.1 数据层

| 项 | 上游 1.20.1 | 本分支 1.12.2 | 类别 |
|---|---|---|---|
| 枪包目录结构 | `data/<ns>/index/guns`、`data/<ns>/data/guns`、`assets/<ns>/display/guns` | 完全一致 | B |
| `gunpack.meta.json` 缺失 | 按目录名推断 namespace | 同，并记一条 issue | B |
| `dependencies` 版本门 | 不匹配报错 | **记 issue 后继续加载**（容错粒度降到单文件，不整包丢弃） | B（有意放宽） |
| 坏 JSON / 坏字段 | `CommonGunIndex.checkData` 校验较严 | 逐文件容错：坏条目跳过并记 issue，其余照常加载 | B（有意放宽） |
| `allow_attachment_types` 含 `null` | 不校验该字段 | 同样跳过该元素并记 issue | B |
| 基岩模型 `format_version` | 支持 `1.8.0` 起的次版本 | 支持，但**次版本 < 12 不建模型**（与上游一致，记 WARN） | B |
| `CUBICSPLINE` 关键帧 | 支持 | 支持（按 glTF 规范做 Hermite 插值，含每秒导数） | B |
| 战利品注入器 | `tacz_loot_injectors` + 数据包 | 同格式，挂钩 `LootTableLoadEvent`，含 1.20 语法归一化 | B |

容错策略差异的**理由**：第三方枪包常见"一个字段写错"，上游会整枪不可见，本分支选择"只丢那一项并提示"，让包作者能继续调试。这是有意的方向性差异，不是遗漏。

### 3.2 配件与 modifier

| 项 | 说明 | 类别 |
|---|---|---|
| modifier 聚合 | `addend` / `percent` / `multiplier` / `function` 四段式，与上游 `Modifier` 同义 | B |
| 16 个 modifier id | 全部注册**且被玩法消费**（不只是 GUI 显示）：`rpm` `ads` `ammo_speed` `pierce` `knockback` `effective_range` `recoil` `inaccuracy` `ignite` `silence` `damage` `armor_ignore` `weight` `explosion` `extended_mag` `melee` | B |
| `exclusive_attachments` | 按上游语义**整份 `AttachmentData` 替换**，不是逐键覆盖 | B |
| 坏 modifier 硬阈值 | 仅对独占条目生效：坏值占多数时整条丢弃；普通配件保留条目、跳过坏字段 | B |
| `function` 的 Lua 求值 | 受限沙箱（无 `os` / `io` / `luajava`）+ 预编译缓存 | B |
| `weight` → 移速 | 不持枪时施加 `MULTIPLY_BASE` 修饰符，卸下时移除 | B |
| `AimInaccuracyModifier` | 上游已废弃且未注册 | B（同样不注册） |

### 3.3 射击

| 项 | 上游 | 本分支 | 类别 |
|---|---|---|---|
| 射击两条路径 | `LivingEntityShoot`（实体）+ `LocalPlayerShoot`（客户端预测） | `LivingEntityShoot` + `LegacyClientPlayerGunBridge`，三点（服务端/客户端/UI）数值一致 | B |
| 射速 | `clamp(1, 1200)` 后乘 heat 倍率 | 同 | B |
| heat → RPM | `min_rpm_mod` / `max_rpm_mod` 随热量线性插值 | 同（无 heat 数据的枪不参与） | B |
| 蓄力 | `charge_during_cooldown` 允许在冷却最后 50 ms 内开始蓄力 | 同 | B |
| 散布四档 | stand / move / sneak / lie，通用 `default` 只作用于前两档 | 同（修正了原移植版"全塌到 stand"的问题） | B/D |
| 射击 ↔ 换弹 ↔ 拉栓互斥 | 拉栓入口先查射击冷却，再查换弹、切枪冷却、拉栓状态 | 同序（原移植版缺射击冷却这一道） | B/D |
| 输入校验 | 角度有限性与范围在消耗弹药前校验 | 同 | B/D |

### 3.4 子弹、伤害与爆炸

| 项 | 上游 | 本分支 | 类别 |
|---|---|---|---|
| 伤害曲线 | `damageAdjust` 按 JSON 数组顺序取第一个满足 `distance` 的条目（**不排序**） | 同 | B |
| 穿透 `pierce` | **总共可命中的实体数**（`pierce:1` 只中 1 个） | 同 | B |
| 击退 | 走原版 `knockBack`，因此被 `knockback_resistance` 削减 | 同（铁傀儡抗性 1.0 时击退为 0 是原版行为） | B |
| 爆炸时机 | 命中即爆；`delay` 只用于**未命中**时自爆 | 同 | B |
| 爆炸伤害 | 构造时乘全局 `DamageBaseMultiplier` | 同（在 `triggerExplosion` 里乘） | B |
| 燃烧弹 | `ignite` 为 OR 语义（子弹自带 或 配件） | 同 | B |
| 护甲穿透 | `armor_ignore` 在结算前扣减护甲值 | 同 | B |

### 3.5 近战

| 项 | 上游 | 本分支 | 类别 |
|---|---|---|---|
| 优先级 | 枪托 > 刺刀 > 推击（数据缺失时逐级回退） | 同 | B |
| 判定顺序 | 切枪冷却 → 拉栓 → 近战冷却 → 准备时间 → 距离/角度 | 同 | B |
| 伤害与击退顺序 | 先击退、再伤害、再加附魔效果，目标死亡则中断 | 同 | B |
| 火焰附加 | 上游**没有**近战火焰附加 | 同样没有 | B |

### 3.6 音频

| 项 | 上游 1.20.1 | 本分支 1.12.2 | 类别 |
|---|---|---|---|
| 播放后端 | vanilla `SoundManager` | 自建 `direct_openal` 后端（ogg 直接解码成 OpenAL buffer），跨版本共享同一套 `sounds/` 定义 | A（实现手段不同） |
| SoundSystem 生命周期 | 1.20 不会在资源重载时销毁 OpenAL 上下文 | **1.12.2 会**：`refreshResources()` → SoundSystem shutdown → 上下文重建 → 所有 buffer id 失效 | A |
| 上下文失效处理 | 无需处理 | 三层防线：①上下文句柄比对 ②`alSourcei(AL_BUFFER)` 后的 `alGetError` 兜底 ③**哨兵 buffer** 做 epoch 判定，没有证据就一个 id 都不删 | A |
| 解码策略 | 交给 SoundManager 流式解码 | 懒解码（首次播放时解，约 10 ms/个），避免 reload 时阻塞主线程 | A |
| 坏 ogg / 缺失音效 | 交给 SoundManager 报错 | 解析期分类为 `MISSING` / `INVALID_OGG` 并 DROPPED，负缓存避免反复重试 | A |

### 3.7 渲染

| 项 | 上游 | 本分支 | 类别 |
|---|---|---|---|
| 第一人称枪模 | 新 `RenderSystem` + `PoseStack` | 自建矩阵管线（兼容 1.12.2 的固定管线与 GL 状态） | A |
| 第三人称角色动画 | `playerAnimator` 驱动 | **不可用**：`playerAnimator` 最低 1.16.4，没有 1.12.2 版本；`display` 里的 `player_animator_3rd` 会被解析但不生效 | A |
| 基岩模型 | 支持 | 支持（含 UV 旋转、父子骨骼、非均匀缩放） | B |
| 开镜光学渲染 | 模板缓冲 + `division` 骨骼 | 同（stencil-style optic） | B |
| 枪口火焰 / 弹壳 / 曳光 | 实体组件 | 按射手状态驱动，避免"共享模型渲染别人的火焰" | B/D |
| GUI 3D 预览 | 上游无此功能（改装台只有 2D 图标） | 本分支**新增** 3D 预览，并按 cube 位置中位数居中（包围盒会被离群骨骼拉偏） | 额外实现 |
| LOD / 渲染距离 | 有基于距离的简化 | 不做（1.12.2 缺对应机制） | A |

### 3.8 网络与多人

| 项 | 上游 | 本分支 | 类别 |
|---|---|---|---|
| 枪包下发 | `ServerMessageSyncGunPack` 携带 `DataType` 的 11 类映射 | 同思路：下发**数值 / 脚本 / lang / display**，用长度前缀 UTF-8 编码 | B |
| 不下发的内容 | 模型、贴图、音效（体积过大） | 同 | B |
| 玩家数据同步 | `ServerMessageUpdateEntityData` 等 | 同（含趴下、开镜、切枪等状态回同步） | B |
| 专用服首次同步 | 无特殊处理 | 登录后**延迟 15 秒**再发，并把多个包的"应用 + 重建"**防抖合并**成一次——否则客户端会因连续重建阻塞主线程 30 秒而被服务端判定超时踢掉 | A（1.12.2 客户端重建更慢） |

### 3.9 命令与 GUI

| 项 | 上游 | 本分支 | 类别 |
|---|---|---|---|
| 命令 | `/tacz reload` 等 | 追加 `list_pack`、`issues`、`convert` | 额外实现 |
| 改装台 | 完整 | 功能对齐，另有 3D 预览 | B |
| 枪械工作台 | 完整 | 完整（含 2D 回退分支） | B |

---

## 4. 明确不实现的

| 项 | 原因 |
|---|---|
| `playerAnimator` 第三人称动画 | 该模组最低 1.16.4，没有 1.12.2 版本 |
| KubeJS 脚本 | 上游是 JS、本项目脚本层是 Lua，引擎不互通 |
| CraftTweaker / GroovyScript 兼容 | 本分支未接线（整合包无法用脚本增删改配方） |
| JEI 制作台配方分类 | 只做了 NBT 子类型注册，玩家在 JEI 里查不到合成方式 |
| 会开枪的 NPC | 缺 `EntityAIBase`；底层 `IGunOperator` 已面向任意 `LivingEntity`，第三方可自行实现 |
| LOD / 渲染距离 | 1.12.2 缺对应机制 |
| 皮肤系统 | 上游本身只有空壳（`skinIndexMap` 从不写入），两个版本都不会加载皮肤 |

---

## 5. 差异演进的主要节点

按主题列（不含逐次提交，仓库历史已压平为单个提交）：

| 主题 | 做了什么 |
|---|---|
| 枪包加载链 | 从"26 个脚本全部加载失败"到"只剩包自身的问题"；脚本路径改为上游规范的 `assets/<ns>/scripts/`，全量加载，支持跨包 `require` |
| 配件 modifier | 16 个从"只有 4 个生效"到全部接入玩法，并加了"注册了就必须有消费点"的守门测试 |
| 数值语义 | `recoil` 改按 percent；inaccuracy 通用值不再污染 aim/sneak/lie；补上 weight→移速 |
| 数据同步 | 从"26 个网络包无一携带枪包数据"到专用服下发数据/脚本/lang/display，并解决长包与登录超时 |
| 音频 | 从"枪包音效播不出来"到自建 OpenAL 后端；再从"资源重载后全体静音"到上下文哨兵 |
| 渲染 | 修正开镜拉长、GUI 黑遮罩、配件预览露出分划、枪口火焰串模型、地面 2D 等一批可见问题 |
| 存档安全 | 旧版单方块工作台不再被当作残缺多方块拆掉；枪包覆盖改为先 staging 再替换并保留备份 |

---

## 6. 怎么自查某个行为

1. **看实现**：按类名搜，`src/main/kotlin/com/tacz/legacy/` 下大致按 `api` / `common` / `client` / `resource` 分层。
   配件 modifier 的消费点集中在 `common/item`、`common/entity`、`client/gameplay` 三处。
2. **看差异来源**：`README.md` 的「本分支做了什么」与「1.12.2 移植版上游遗留的缺陷」两张表按危害排序，是本文件的摘要版。
3. **看限制**：README「已知限制」列出所有"做不到 / 需要手动操作 / 上游本来没有"的项。
4. **对照上游**：需要确认某个语义时，读 TACZ 1.20.1 的对应类；类名大多保留上游命名（`LivingEntityShoot`、`EntityKineticBullet`、`GunData` 等），便于比对。
