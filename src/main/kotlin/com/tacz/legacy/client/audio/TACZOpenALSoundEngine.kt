package com.tacz.legacy.client.audio

import com.jcraft.jogg.Packet
import com.jcraft.jogg.Page
import com.jcraft.jogg.StreamState
import com.jcraft.jogg.SyncState
import com.jcraft.jorbis.Block
import com.jcraft.jorbis.Comment
import com.jcraft.jorbis.DspState
import com.jcraft.jorbis.Info
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.client.sound.GunPackAssetLocator
import com.tacz.legacy.client.sound.TACZClientSoundHandle
import com.tacz.legacy.common.foundation.FocusedSmokeRuntime
import net.minecraft.entity.Entity
import net.minecraft.util.ResourceLocation
import org.apache.logging.log4j.Marker
import org.apache.logging.log4j.MarkerManager
import org.lwjgl.BufferUtils
import org.lwjgl.openal.AL10
import org.lwjgl.openal.ALC10
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

internal object TACZOpenALSoundEngine {
    private const val MAX_CONCURRENT_SOURCES: Int = 24
    private const val OGG_READ_BUFFER_SIZE: Int = 8192
    private const val MAX_DECODE_BYTES: Int = 60 * 1024 * 1024

    private val MARKER: Marker = MarkerManager.getMarker("TACZOpenAL")

    internal data class PcmData(
        val data: ByteArray,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
    )

    private val soundBuffers = ConcurrentHashMap<ResourceLocation, Int>()
    private val activeSources = CopyOnWriteArrayList<Int>()

    /**
     * 创建 [soundBuffers] 时所在的 OpenAL 上下文句柄（`ALC10.alcGetCurrentContext()`）。
     *
     * **必须记录它。** vanilla 的 paulscode SoundSystem 在每次资源重载时都会
     * `shutdown()` 销毁 device/context，再 `start()` 建一个新的（日志里表现为
     * "SoundSystem shutting down..." → "OpenAL initialized."）。上下文一换，
     * 之前 `alGenBuffers` / `alGenSources` 拿到的 id 全部失效：
     * `alSourcei(src, AL_BUFFER, 旧id)` 会返回 `AL_INVALID_VALUE`，
     * 于是 `play()` 检测到错误后静默返回 null —— 表现就是**所有枪声都没了**。
     *
     * 实测（2026-10-20 10:22:59 日志）：TACZ 加载完 1642 个 buffer 后紧接着
     * `registerGunPackResourcePacks` 触发 `mc.refreshResources()`，
     * SoundSystem 当场重启，1642 个 buffer 全部作废，之后再没重建过。
     */
    private var bufferContext: Long = 0L

    /** 最近一次 [reload] 的枪包源，供上下文失效后按需重建单个 buffer。 */
    private var lastPackSources: List<File> = emptyList()

    /** "整批 buffer 已失效"的 WARN 只报一次，避免每枪刷屏。 */
    private var staleBufferWarningLogged: Boolean = false

    /** 解码失败过的音效：负缓存，避免损坏的 ogg 每次开火都重解一遍。 */
    private val failedBuffers: MutableSet<ResourceLocation> = ConcurrentHashMap.newKeySet()

    /**
     * "这个 id 属于当前上下文"的哨兵 buffer。
     *
     * 只用 `alIsBuffer` 判断是不够的：OpenAL 的 buffer id 是**每个上下文各自从 1 开始
     * 分配的小整数**，vanilla 重启 SoundSystem 后新上下文会重新发同样的号，
     * 旧缓存的 id 很可能正好落在一个新 buffer（常是 vanilla 自己的）上，
     * `alIsBuffer` 返回 true，于是 `alDeleteBuffers` 删掉了别人的 buffer。
     *
     * 这里自己建一个已知字节数的 buffer 并记下 id：只要 `alGetBufferi(id, AL_SIZE)`
     * 还能读回 [SENTINEL_BYTES]，就说明这确实是**我们自己**在当前上下文里建的那个，
     * 缓存里同批的 id 也才可信。上下文一换，要么 id 失效，要么读回的 size 对不上。
     */
    private var sentinelBuffer: Int = 0

    /** 哨兵 buffer 的字节数：一个不常见的质数，不会与任何真实音频长度撞车。 */
    private const val SENTINEL_BYTES: Int = 7919

    /** 删除失败（id 其实已不属于本上下文）的 WARN 只报一次。 */
    private var deleteFailureWarningLogged: Boolean = false

    /** 上下文句柄只在首帧打印一次，便于排查 ALC 在本机是否可读。 */
    private var contextDiagnosticLogged: Boolean = false

    @Volatile
    private var openAlUnavailableLogged: Boolean = false

    /**
     * 记录本次枪包重载的音效清单，**不做全量预解码**。
     *
     * 历史包袱：这里原本会把 `descriptors` 里每个 ogg 解成 PCM 再上传 buffer，
     * 10 个包实测 1642 个音效、耗时十几秒，而它跑在客户端主线程
     * （`ClientProxy.onGunPackReloaded` → `mc.addScheduledTask`）。这段时间里
     * 客户端既不处理也不回送 keep-alive，服务端计时到点就判定
     * `lost connection: 连接超时` 把玩家踢下线（R-03 / B9 诊断）。
     *
     * 现在改为**纯按需**：只记 [lastPackSources]，每个音效第一次播放时由
     * [loadBufferLazily] 单独解码（约 10ms，一次性）。代价仅是每把枪首次发声
     * 有一次几毫秒的抖动；损坏的 ogg 由 [failedBuffers] 负缓存挡住，
     * 上下文失效后由 [invalidateStaleBuffers] 整批丢弃再按需重建。
     */
    @JvmStatic
    fun reload(packSources: List<File>, manifest: TACZAudioManifest) {
        clear()
        lastPackSources = ArrayList(packSources)
        bufferContext = currentContext()
        val available = manifest.entries.values.count { descriptor ->
            descriptor.exists && descriptor.assetLocation != null
        }
        if (available == 0) {
            TACZLegacy.logger.info(MARKER, "OpenAL direct audio reload skipped: no pack-backed audio assets")
            return
        }
        TACZLegacy.logger.info(
            MARKER,
            "OpenAL direct audio ready: lazy decode, {} sound(s) will decode on first play",
            available,
        )
    }

    @JvmStatic
    fun clear() {
        // 先判断上下文是否还活着：vanilla 重启 SoundSystem 之后，缓存里的 id 属于
        // 已销毁的上下文，这时再 alDelete* 有可能删到新上下文里别人的 buffer/source。
        syncContextGeneration()
        // 哨兵是唯一的"同批 id 仍可信"的证据（alIsBuffer 会被 id 复用骗过）。
        // 证据不在就整批丢弃、一个 id 都不删、也不 stop source。
        if (!isContextEpochValid()) {
            invalidateStaleBuffers()
            return
        }
        stopAllActiveSources()
        var failedDeletes = 0
        soundBuffers.values.forEach { bufferId ->
            // 两次检查之间上下文仍可能被换掉，删除前再让 AL 自己确认一次 id 有效。
            // alIsBuffer 也和其余 AL 调用一样包在 runCatching 里：原生错误不能让
            // 整个 clear()（进而让 reload()）抛出去。
            val deleted = runCatching {
                if (AL10.alIsBuffer(bufferId)) {
                    AL10.alDeleteBuffers(bufferId)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (!deleted) {
                failedDeletes++
            }
        }
        if (failedDeletes > 0 && !deleteFailureWarningLogged) {
            deleteFailureWarningLogged = true
            TACZLegacy.logger.warn(
                MARKER,
                "{} gun-pack buffer(s) could not be deleted during reload; dropping the ids without deleting",
                failedDeletes,
            )
        }
        soundBuffers.clear()
        failedBuffers.clear()
        // 这一代的 buffer 已经删干净；下一次 clear() 是否可信，由哨兵（[isContextEpochValid]）
        // 来判断，不再维护"上一批 id 是否有效"这种会过期的记账。
        bufferContext = currentContext()
    }

    /**
     * 检测 vanilla SoundSystem 是否重建过 OpenAL 上下文；是则丢弃全部缓存 id（不删除）。
     *
     * 不删除的原因：新上下文里 id 是重新分配的，对旧 id 调 `alDeleteBuffers` 可能
     * 恰好命中 vanilla 自己刚创建的 buffer，造成别人的音效/音乐消失。
     *
     * **实测（2026-10-20 10:46:01）：本机客户端线程上 `ALC10.alcGetCurrentContext()`
     * 返回 0** —— vanilla 的 paulscode SoundSystem 在 "Sound Library Loader" 线程
     * 创建并 make-current 上下文，客户端线程自身没有 current context。所以这条检测
     * 在实机上**通常不会触发**，真正兜住上下文失效的是 [play] 里
     * `alSourcei(AL_BUFFER)` 的错误检测（见 [invalidateStaleBuffers]）。
     * 保留它是因为部分平台/驱动会返回有效句柄，两条路径互为补充，都不依赖顺序。
     *
     * @return true 表示本次发生了失效（缓存已清空）
     */
    private fun syncContextGeneration(): Boolean {
        val context = currentContext()
        if (context == 0L || context == bufferContext) {
            return false
        }
        val invalidated = soundBuffers.size
        val hadContext = bufferContext != 0L
        soundBuffers.clear()
        // 旧 source 同样随上下文销毁，直接丢弃句柄，不要跨上下文 alSourceStop
        activeSources.clear()
        // 哨兵也属于旧上下文
        sentinelBuffer = 0
        bufferContext = context
        if (hadContext && invalidated > 0) {
            TACZLegacy.logger.info(
                MARKER,
                "OpenAL context changed (vanilla SoundSystem restarted); dropped {} stale gun-pack buffers, rebuilding on demand",
                invalidated,
            )
        }
        return true
    }

    /**
     * buffer id 已被证明失效（`alSourcei(src, AL_BUFFER, id)` 报错）时的整批丢弃。
     *
     * 同一批 buffer 是在同一个上下文里创建的，坏一个说明整批都随旧上下文销毁了，
     * 因此不做逐个判断，直接清空让它们按需重建。
     */
    private fun invalidateStaleBuffers() {
        val invalidated = soundBuffers.size
        soundBuffers.clear()
        failedBuffers.clear()
        activeSources.clear()
        // 哨兵本身也属于旧上下文，一起作废；下一次建 buffer 时会重新建一个
        sentinelBuffer = 0
        bufferContext = currentContext()
        if (invalidated > 0 && !staleBufferWarningLogged) {
            staleBufferWarningLogged = true
            TACZLegacy.logger.warn(
                MARKER,
                "Cached gun-pack buffers were invalidated by an OpenAL context reset; dropped {} and rebuilding on demand",
                invalidated,
            )
        }
    }

    /**
     * 哨兵还在、且读回的字节数对得上 → 缓存里同批的 id 仍属于当前上下文。
     */
    private fun isContextEpochValid(): Boolean {
        val sentinel = sentinelBuffer
        if (sentinel <= 0) {
            return false
        }
        return runCatching {
            AL10.alGetError()
            AL10.alIsBuffer(sentinel) && AL10.alGetBufferi(sentinel, AL10.AL_SIZE) == SENTINEL_BYTES
        }.getOrDefault(false)
    }

    /**
     * 确保当前上下文里有一个哨兵 buffer，返回它的 id（失败返回 0）。
     */
    private fun ensureSentinelBuffer(): Int {
        val existing = sentinelBuffer
        if (existing > 0) {
            if (isContextEpochValid()) {
                return existing
            }
            // 哨兵读不回来 = 上下文换过一代，而 soundBuffers 里可能还留着上一代的 id
            // （玩家在 reload 之前先触发了一个未缓存的音效，就是这个窗口）。
            // 此刻若直接建新哨兵，isContextEpochValid() 会立刻对**整批**回答 true，
            // 上一代的旧 id 就被当成可信的、在下一次 clear() 里交给 alDeleteBuffers ——
            // 而 OpenAL 的 id 是按上下文从 1 重新分配的，撞上 vanilla 的 buffer 就会删别人的。
            // 所以先把整批作废（invalidateStaleBuffers 顺手把哨兵置 0），再往下建新的。
            invalidateStaleBuffers()
        }
        if (!isOpenAlAvailable()) {
            sentinelBuffer = 0
            return 0
        }
        val created = runCatching {
            AL10.alGetError()
            val bufferId = AL10.alGenBuffers()
            // 内容无所谓（全零），关键是 alGetBufferi 能把它读回来
            AL10.alBufferData(
                bufferId,
                AL10.AL_FORMAT_MONO8,
                BufferUtils.createByteBuffer(SENTINEL_BYTES),
                8000,
            )
            if (AL10.alGetError() == AL10.AL_NO_ERROR) bufferId else 0
        }.getOrDefault(0)
        sentinelBuffer = created
        if (created > 0 && created != existing) {
            // 每个上下文只会出现一次，正好用来在日志里确认"自我标记"生效
            TACZLegacy.logger.info(
                MARKER,
                "OpenAL context sentinel established (buffer={}, {} bytes)",
                created,
                SENTINEL_BYTES,
            )
        }
        return created
    }

    private fun currentContext(): Long = try {
        // 反编译签名在 Kotlin 侧解析成 Any!（LWJGL 2 的 long 句柄），统一按 Number 取
        (ALC10.alcGetCurrentContext() as? Number)?.toLong() ?: 0L
    } catch (_: UnsatisfiedLinkError) {
        0L
    } catch (_: NoClassDefFoundError) {
        0L
    } catch (_: IllegalStateException) {
        0L
    }

    @JvmStatic
    fun tick() {
        if (!contextDiagnosticLogged) {
            contextDiagnosticLogged = true
            TACZLegacy.logger.info(
                MARKER,
                "OpenAL context handle on client thread = {} (0 means the calling thread has no current context)",
                currentContext(),
            )
        }
        // SoundSystem 可能在两帧之间重启（F3+T、资源包变化），这里顺带发现失效
        syncContextGeneration()
        if (!isOpenAlAvailable()) {
            return
        }
        cleanupFinishedSources()
    }

    @JvmStatic
    fun play(
        entity: Entity?,
        soundId: ResourceLocation,
        volume: Float,
        pitch: Float,
        distance: Int,
        assetLocation: ResourceLocation?,
    ): TACZClientSoundHandle? {
        // 上下文换过就重来：旧 buffer 已随上下文销毁
        syncContextGeneration()
        if (!isOpenAlAvailable()) {
            return null
        }
        // 解码失败过的音效直接跳过：解码在主线程，坏了就每次开火重解一遍是不可接受的
        if (failedBuffers.contains(soundId)) {
            return null
        }
        // 缓存里有就直接用；没有（首次、或上下文刚失效被清空）就按需解码这一个音效。
        // 每次只解码 ~10ms，避免 1642 个 ogg 一次性重解把主线程卡十几秒。
        var bufferId = soundBuffers[soundId] ?: loadBufferLazily(soundId, assetLocation) ?: return null
        cleanupFinishedSources()
        trimOldestSourceIfNeeded()

        AL10.alGetError()
        val sourceId = AL10.alGenSources()
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            return null
        }

        return runCatching {
            AL10.alGetError()
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId)
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                // 兜底，也是**实机上真正生效的那条**：ALC10.alcGetCurrentContext() 在
                // 客户端线程返回 0（上下文由 SoundSystem 的加载线程持有），句柄比对失效，
                // 只能靠这里发现"buffer id 已随旧上下文销毁"。
                // 清掉整批缓存（它们同属旧上下文），把要播的这一个重新解码后再试一次。
                invalidateStaleBuffers()
                // 注意：这里已经 createOpenAlSource 过了，早退必须自己删 source，
                // 否则 runCatching 的 getOrElse 不会执行（return 是 non-local 的），
                // 会留下一个没有任何持有者的孤儿 source。
                val rebuilt = loadBufferLazily(soundId, assetLocation)
                if (rebuilt == null) {
                    runCatching { AL10.alDeleteSources(sourceId) }
                    return null
                }
                bufferId = rebuilt
                AL10.alGetError()
                AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId)
                if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                    runCatching { AL10.alDeleteSources(sourceId) }
                    return null
                }
            }
            AL10.alSourcef(sourceId, AL10.AL_GAIN, volume.coerceIn(0f, 1f))
            AL10.alSourcef(sourceId, AL10.AL_PITCH, pitch.coerceIn(0.5f, 2f))

            if (entity == null || distance <= 0) {
                AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
                AL10.alSource3f(sourceId, AL10.AL_POSITION, 0f, 0f, 0f)
            } else {
                AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE)
                AL10.alSource3f(sourceId, AL10.AL_POSITION, entity.posX.toFloat(), entity.posY.toFloat(), entity.posZ.toFloat())
                AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 1f)
                AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, max(1f, distance / 8f))
                AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, max(1f, distance.toFloat()))
            }

            AL10.alSourcePlay(sourceId)
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                runCatching { AL10.alDeleteSources(sourceId) }
                return null
            }

            activeSources.add(sourceId)
            if (FocusedSmokeRuntime.enabled) {
                FocusedSmokeRuntime.markAudioPlaybackObserved("location=$soundId class=TACZOpenALSoundHandle")
            }
            TACZOpenALSoundHandle(soundId, sourceId)
        }.getOrElse {
            runCatching { AL10.alDeleteSources(sourceId) }
            null
        }
    }

    internal fun decodeOggToPcm(inputStream: InputStream): PcmData? {
        val syncState = SyncState()
        val streamState = StreamState()
        val page = Page()
        val packet = Packet()
        val info = Info()
        val comment = Comment()
        val dspState = DspState()
        val block = Block(dspState)
        val pcmOutput = ByteArrayOutputStream()

        try {
            syncState.init()
            info.init()
            comment.init()

            var headersParsed = false
            var packetCount = 0
            var eos = false

            outer@ while (!eos) {
                val bufferIndex = syncState.buffer(OGG_READ_BUFFER_SIZE)
                val buffer = syncState.data
                val bytesRead = inputStream.read(buffer, bufferIndex, OGG_READ_BUFFER_SIZE)
                if (bytesRead <= 0) {
                    syncState.wrote(0)
                    break
                }
                syncState.wrote(bytesRead)

                while (true) {
                    val pageResult = syncState.pageout(page)
                    if (pageResult == 0) break
                    if (pageResult < 0) continue

                    if (!headersParsed && packetCount == 0) {
                        streamState.init(page.serialno())
                    }
                    streamState.pagein(page)

                    while (true) {
                        val packetResult = streamState.packetout(packet)
                        if (packetResult == 0) break
                        if (packetResult < 0) continue

                        if (packetCount < 3) {
                            if (info.synthesis_headerin(comment, packet) < 0) {
                                return null
                            }
                            packetCount += 1
                            if (packetCount == 3) {
                                headersParsed = true
                                dspState.synthesis_init(info)
                                block.init(dspState)
                            }
                            continue
                        }

                        if (block.synthesis(packet) == 0) {
                            dspState.synthesis_blockin(block)
                        }

                        val pcmArray = Array<Array<FloatArray>>(1) { emptyArray() }
                        val index = IntArray(info.channels)

                        while (true) {
                            val samples = dspState.synthesis_pcmout(pcmArray, index)
                            if (samples <= 0) {
                                break
                            }

                            val channelData = pcmArray[0]
                            for (i in 0 until samples) {
                                for (ch in 0 until info.channels) {
                                    var sample = (channelData[ch][index[ch] + i] * 32767f).toInt()
                                    sample = sample.coerceIn(-32768, 32767)
                                    pcmOutput.write(sample and 0xFF)
                                    pcmOutput.write((sample shr 8) and 0xFF)
                                }
                            }
                            dspState.synthesis_read(samples)

                            if (pcmOutput.size() > MAX_DECODE_BYTES) {
                                TACZLegacy.logger.warn(MARKER, "Decode size limit exceeded; truncating current sound")
                                break@outer
                            }
                        }
                    }

                    if (page.eos() != 0) {
                        eos = true
                    }
                }
            }

            if (!headersParsed) {
                return null
            }
            val pcmBytes = pcmOutput.toByteArray()
            if (pcmBytes.isEmpty()) {
                return null
            }

            return PcmData(
                data = pcmBytes,
                channels = info.channels,
                sampleRate = info.rate,
                bitsPerSample = 16,
            )
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { block.clear() }
            runCatching { dspState.clear() }
            runCatching { info.clear() }
            runCatching { streamState.clear() }
            runCatching { syncState.clear() }
        }
    }

    internal fun stopSource(sourceId: Int) {
        // 只处理还属于当前上下文的 source。上下文被 vanilla 重启后 activeSources 已被清空，
        // 这时拿旧 id 去 alSourceStop/alDeleteSources 可能误伤新上下文里别人的 source。
        if (!activeSources.remove(sourceId)) {
            return
        }
        if (isOpenAlAvailable()) {
            // 同样会被 id 复用骗过：操作前让 AL 自己确认这个 id 现在确实是个 source
            runCatching {
                if (AL10.alIsSource(sourceId)) {
                    AL10.alSourceStop(sourceId)
                    AL10.alDeleteSources(sourceId)
                }
            }
        }
    }

    /**
     * 单个音效的按需解码。
     *
     * 用于两种场景：一是缓存里本来就没有（首帧、包热重载后新增的引用），
     * 二是 OpenAL 上下文被 vanilla 重启、缓存被 [syncContextGeneration] 清空之后。
     *
     * 刻意不做"整体重建"：1642 个 ogg 全量解码约十几秒，会把主线程卡死；
     * 单个约 10ms，摊到每次开火几乎无感。
     */
    private fun loadBufferLazily(soundId: ResourceLocation, assetLocation: ResourceLocation?): Int? {
        if (assetLocation == null) {
            return null
        }
        val pcm = runCatching {
            GunPackAssetLocator.openResource(lastPackSources, assetLocation)?.use(::decodeOggToPcm)
        }.getOrNull()
        if (pcm == null) {
            // 只有"读不到 / 解不开"才值得负缓存：重试多少次结果都一样。
            failedBuffers.add(soundId)
            return null
        }
        val bufferId = createOpenAlBuffer(pcm)
        if (bufferId == null) {
            // AL 侧的失败可能是瞬时的（上下文正在切换、显存/内存紧张），
            // 不能进 failedBuffers，否则这个音效在本次运行里就永久哑了。
            return null
        }
        // 顺序不能换：ensureSentinelBuffer() 在"有旧哨兵但读不回来"时会调用
        // invalidateStaleBuffers() 整批清空 soundBuffers。若先把 bufferId 登记进去，
        // 这个刚建好的 id 会被自己清掉 —— 既漏删又白解码一次。
        sentinelBuffer = ensureSentinelBuffer()
        soundBuffers[soundId] = bufferId
        // 每个音效只会走到这里一次（之后命中缓存），用来确认"上下文失效后重建"确实生效
        TACZLegacy.logger.info(MARKER, "Rebuilt gun-pack audio buffer on demand: {}", soundId)
        return bufferId
    }

    private fun createOpenAlBuffer(pcm: PcmData): Int? {
        if (!isOpenAlAvailable()) {
            return null
        }
        val format = when (pcm.channels) {
            1 -> if (pcm.bitsPerSample == 8) AL10.AL_FORMAT_MONO8 else AL10.AL_FORMAT_MONO16
            2 -> if (pcm.bitsPerSample == 8) AL10.AL_FORMAT_STEREO8 else AL10.AL_FORMAT_STEREO16
            else -> return null
        }
        val byteBuffer: ByteBuffer = BufferUtils.createByteBuffer(pcm.data.size)
        byteBuffer.put(pcm.data)
        byteBuffer.flip()

        // 与本文件其它 AL 调用保持一致：驱动异常不能从 play() 抛到渲染/tick 路径上。
        // 注意失败必须返回 null 而不是 0 —— 0 会被当成合法的 buffer id 传下去。
        return runCatching {
            AL10.alGetError()
            val bufferId = AL10.alGenBuffers()
            AL10.alBufferData(bufferId, format, byteBuffer, pcm.sampleRate)
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                runCatching { AL10.alDeleteBuffers(bufferId) }
                null
            } else {
                bufferId
            }
        }.getOrNull()
    }

    private fun trimOldestSourceIfNeeded() {
        if (activeSources.size < MAX_CONCURRENT_SOURCES) {
            return
        }
        val oldest = activeSources.firstOrNull() ?: return
        stopSource(oldest)
    }

    private fun cleanupFinishedSources() {
        if (!isOpenAlAvailable()) {
            return
        }
        val finished = activeSources.filter { sourceId ->
            val state = runCatching { AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE) }.getOrDefault(AL10.AL_STOPPED)
            state != AL10.AL_PLAYING && state != AL10.AL_PAUSED
        }
        finished.forEach(::stopSource)
    }

    private fun stopAllActiveSources() {
        activeSources.toList().forEach(::stopSource)
        activeSources.clear()
    }

    private fun isOpenAlAvailable(): Boolean {
        return try {
            AL10.alGetError()
            openAlUnavailableLogged = false
            true
        } catch (_: UnsatisfiedLinkError) {
            logOpenAlUnavailableOnce()
            false
        } catch (_: NoClassDefFoundError) {
            logOpenAlUnavailableOnce()
            false
        } catch (_: IllegalStateException) {
            logOpenAlUnavailableOnce()
            false
        }
    }

    private fun logOpenAlUnavailableOnce() {
        if (openAlUnavailableLogged) {
            return
        }
        openAlUnavailableLogged = true
        TACZLegacy.logger.debug(MARKER, "OpenAL context not ready yet; direct gun-pack audio will retry later")
    }

    private class TACZOpenALSoundHandle(
        private val soundId: ResourceLocation,
        private val sourceId: Int,
    ) : TACZClientSoundHandle {
        override fun getSoundId(): ResourceLocation = soundId

        override fun stop() {
            stopSource(sourceId)
        }
    }
}
