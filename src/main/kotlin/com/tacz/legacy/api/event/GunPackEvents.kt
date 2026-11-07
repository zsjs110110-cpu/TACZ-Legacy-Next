package com.tacz.legacy.api.event

import net.minecraftforge.fml.common.eventhandler.Event
import net.minecraftforge.fml.relauncher.Side

/**
 * 枪包重载完成、运行时快照已经替换之后触发。
 *
 * 存在的理由是给外部脚本/展示层一个**确定的时机**：CraftTweaker 与 GroovyScript 要在这里
 * 重新读取制作台配方，JEI/HEI 要在这里刷新它已经注册过的配方列表。在此之前
 * （`TACZGunPackRuntime.reload`）换完快照什么都不发，外部只能靠猜或者等游戏重启。
 *
 * 事件本身故意不带快照参数：`TACZRuntimeSnapshot` 是 internal 类型，把它放进公开事件签名
 * 等于把内部数据结构暴露给脚本层。消费方要数据请走各自的公开入口（制作台配方见
 * `LegacyGunSmithingRuntime`）。
 *
 * **线程注意**：`/tacz reload` 走的是（集成）服务端线程，断线重连走客户端线程，
 * 所以本事件不保证在客户端主线程上。客户端侧的消费者（JEI/HEI）必须自己
 * `Minecraft.getMinecraft().addScheduledTask { ... }` 切回去，否则会踩 GL 线程。
 */
public class GunPackReloadedEvent(
    public val logicalSide: Side,
) : Event()
