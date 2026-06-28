package com.tgwrist.app.runtime

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

const val TgClientClose = "TgClientClose"
const val TgClientReInit = "TgClientReInit"

typealias UpdateCallback = (TdApi.Object) -> Unit

object TgClient {

    private const val TAG = "TgClient"

    @Volatile
    private var client: Client? = null

    /**
     * 已调用 close()、正在等待 AuthorizationStateClosed 的 Client。
     *
     * close() 后不会立即把 client 置空，而是先记录在这里，
     * 等到 TDLib 真正回吐 TdApi.AuthorizationStateClosed 时再彻底丢弃。
     * 这样可以保证：
     * 1. AuthorizationStateClosed 之前不会出现两个 TDLib 实例；
     * 2. 依赖 AuthorizationStateClosed 的其他代码能正常收到该 update。
     */
    @Volatile
    private var pendingCloseClient: Client? = null

    private val clientLock = Any()

    /**
     * 当前 TDLib Client 代数。
     *
     * 每次创建新 Client 或废弃旧 Client 时都会递增。
     * 旧 Client 后续吐出的 update 会因为 generation 不匹配而被丢弃。
     */
    private val generation = AtomicLong(0L)

    /**
     * 线程安全的订阅表。
     *
     * CopyOnWriteArrayList 适合“订阅变动少、update 分发频繁”的场景。
     */
    private val callbacks =
        ConcurrentHashMap<Class<out TdApi.Object>, CopyOnWriteArrayList<UpdateCallback>>()

    /**
     * TgClient 自己的事件处理线程。
     *
     * TDLib 原始 callback 只负责把事件投递进队列，
     * 不直接执行外部业务逻辑，避免卡住 TDLib 回调线程。
     */
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val eventChannel = Channel<Event>(capacity = Channel.UNLIMITED)

    private data class Event(
        val generation: Long,
        val name: String,
        val action: () -> Unit
    )

    class Subscription internal constructor(
        private val onClose: () -> Unit
    ) : Closeable {

        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                onClose()
            }
        }

        fun unsubscribe() {
            close()
        }
    }

    init {
        startEventLoop()
    }

    fun init() {
        synchronized(clientLock) {
            // 仅当前面已经执行过 close()（存在 pendingCloseClient，
            // 即旧实例正在等待 AuthorizationStateClosed）时，init() 才强制
            // 废弃这个待关闭的旧实例，保证 init() 一定能让新实例进入。
            // 递增 generation 会让旧实例后续吐出的 update / response 因
            // generation 不匹配而被丢弃。
            if (pendingCloseClient != null) {
                Log.d(TAG, "Discarding pending-close client before re-init")
                client = null
                pendingCloseClient = null
            }

            // 没有执行过 close/reInit、client 正常存活时，保持原逻辑：直接返回，不替换。
            if (client != null) return

            val currentGeneration = generation.incrementAndGet()

            Log.d(TAG, "Initializing TDLib client, generation=$currentGeneration")

            client = Client.create(
                { update ->
                    postEvent(
                        generation = currentGeneration,
                        name = update.javaClass.simpleName
                    ) {
                        dispatchUpdate(update)
                    }
                },
                { error ->
                    Log.e(TAG, "TDLib exception handler", error)
                },
                { error ->
                    Log.e(TAG, "TDLib default exception handler", error)
                }
            )
        }
    }

    /**
     * 保持你原来的 reInit 行为：
     *
     * 1. 旧 Client 发送 TdApi.Close()
     * 2. 发送 TgClientReInit
     * 3. client = null
     * 4. 立刻 init() 新 Client
     *
     * 不等待 AuthorizationStateClosed。
     */
    fun reInit() {
        val oldClient: Client?

        synchronized(clientLock) {
            oldClient = client
        }

        if (oldClient != null) {
            sendCloseDirectly(oldClient, "reInit")

            GlobalEventBus.send(TgClientReInit)

            synchronized(clientLock) {
                if (client === oldClient) {
                    client = null

                    // 废弃旧 Client 的 generation，旧实例后续 update 会被丢弃。
                    generation.incrementAndGet()
                }
            }
        }

        init()
    }

    /**
     * close 行为（已按需求调整）：
     *
     * 1. 旧 Client 发送 TdApi.Close()
     * 2. **不再立即** 把 client 置空，也不立即递增 generation
     * 3. notify == 1 时发送 TgClientClose
     *
     * 关键点：
     * TDLib 实例不能同时存在两个，所以在收到
     * TdApi.UpdateAuthorizationState(AuthorizationStateClosed) 之前，
     * 必须保留当前 Client（不置空）。这样：
     * - AuthorizationStateClosed 之前不会出现两个实例；
     * - 依赖 AuthorizationStateClosed 的其他代码仍能正常收到该 update。
     *
     * 真正的丢弃（client = null + generation 递增）在
     * dispatchUpdate 收到 AuthorizationStateClosed 后由 finalizeClose() 完成。
     */
    fun close(notify: Int = 1) {
        val oldClient: Client?

        synchronized(clientLock) {
            oldClient = client
        }

        if (oldClient != null) {
            sendCloseDirectly(oldClient, "close")

            synchronized(clientLock) {
                if (client === oldClient) {
                    // 标记为“等待 AuthorizationStateClosed”，但不置空 client。
                    pendingCloseClient = oldClient
                }
            }
        }

        if (notify == 1) {
            GlobalEventBus.send(TgClientClose)
        }
    }

    /**
     * 收到 AuthorizationStateClosed 后真正丢弃 Client。
     *
     * 此时才把 client 置空并递增 generation，旧实例后续 update 会被丢弃。
     */
    private fun finalizeClose() {
        synchronized(clientLock) {
            val closedClient = pendingCloseClient ?: return

            if (client === closedClient) {
                client = null

                // 废弃旧 Client 的 generation。
                generation.incrementAndGet()
            }

            pendingCloseClient = null

            Log.d(TAG, "Client discarded after AuthorizationStateClosed")
        }
    }

    /**
     * 发送 TDLib 请求。
     *
     * send 调用本身只把请求投递给 TDLib（TDLib 内部异步处理），立即返回，不会阻塞。
     *
     * response callback 不再进入串行事件队列（eventChannel），
     * 而是每个响应在 eventScope 上独立并发执行。
     *
     * 这样多个 send 的响应彼此独立，不会出现“发送 1 条、处理 1 条、
     * 完成后才处理下一条”的串行阻塞。
     */
    fun send(
        query: TdApi.Function<*>,
        callback: (TdApi.Object) -> Unit = {}
    ) {
        val currentClient: Client?
        val currentGeneration: Long

        synchronized(clientLock) {
            currentClient = client
            currentGeneration = generation.get()
        }

        if (currentClient == null) {
            Log.w(TAG, "send ignored, client is null: ${query.javaClass.simpleName}")
            return
        }

        val queryName = query.javaClass.simpleName

        try {
            currentClient.send(query) { result ->
                dispatchResponse(currentGeneration, queryName, result, callback)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "TDLib send failed: $queryName", e)
        }
    }

    /**
     * 响应回调分发。
     *
     * 每个响应独立 launch，彼此并发，不经过串行事件队列。
     * 仍然保留 generation 校验，丢弃旧 Client 的过期响应。
     */
    private fun dispatchResponse(
        eventGeneration: Long,
        queryName: String,
        result: TdApi.Object,
        callback: (TdApi.Object) -> Unit
    ) {
        eventScope.launch {
            val currentGeneration = generation.get()

            if (eventGeneration != currentGeneration) {
                Log.d(
                    TAG,
                    "Drop stale response: Response:$queryName, " +
                            "eventGeneration=$eventGeneration, currentGeneration=$currentGeneration"
                )
                return@launch
            }

            try {
                callback(result)
            } catch (e: Throwable) {
                Log.e(TAG, "TgClient response failed: Response:$queryName", e)
            }
        }
    }

    /**
     * 保留原 subscribe 入口，并增加几个可选能力。
     *
     * 原写法仍然可用：
     *
     * TgClient.subscribe(Type::class.java) { ... }
     * TgClient.subscribe(Type::class.java, owner) { ... }
     *
     * 新增：
     *
     * TgClient.subscribe(Type::class.java, scope = viewModelScope) { ... }
     *
     * owner:
     * Lifecycle onDestroy 时自动取消订阅。
     *
     * scope:
     * CoroutineScope 结束时自动取消订阅，适合 ViewModel。
     *
     * dispatchOnMain:
     * true 时 callback 在主线程执行，适合直接更新 Compose/UI 状态。
     *
     * warnIfNoLifecycle:
     * owner 和 scope 都为空时打印 warning。
     */
    fun <T : TdApi.Object> subscribe(
        type: Class<T>,
        owner: LifecycleOwner? = null,
        scope: CoroutineScope? = null,
        dispatchOnMain: Boolean = false,
        warnIfNoLifecycle: Boolean = true,
        callback: (T) -> Unit
    ): Subscription {
        if (owner?.lifecycle?.currentState == Lifecycle.State.DESTROYED) {
            return Subscription {}
        }

        if (owner == null && scope == null && warnIfNoLifecycle) {
            Log.w(
                TAG,
                "subscribe without lifecycle: ${type.simpleName}. " +
                        "This is safe only for global/long-lived subscriptions."
            )
        }

        val list = callbacks.getOrPut(type) {
            CopyOnWriteArrayList()
        }

        val wrapper: UpdateCallback = { obj ->
            if (type.isInstance(obj)) {
                @Suppress("UNCHECKED_CAST")
                val typedObj = obj as T

                if (dispatchOnMain) {
                    mainScope.launch {
                        try {
                            callback(typedObj)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Subscriber callback failed on Main: ${type.simpleName}", e)
                        }
                    }
                } else {
                    // 每个回调独立并发执行，不再内联阻塞 update 事件循环。
                    // Dispatchers.Default 会把真实并行度限制在 CPU 核心数，
                    // 订阅者很多时只是协程排队，不会出现线程爆炸。
                    eventScope.launch {
                        try {
                            callback(typedObj)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Subscriber callback failed: ${type.simpleName}", e)
                        }
                    }
                }
            }
        }

        list.add(wrapper)

        var lifecycleObserver: DefaultLifecycleObserver? = null
        var jobHandle: DisposableHandle? = null

        val subscription = Subscription {
            list.remove(wrapper)

            if (list.isEmpty()) {
                callbacks.remove(type, list)
            }

            lifecycleObserver?.let { observer ->
                owner?.lifecycle?.removeObserver(observer)
            }

            jobHandle?.dispose()
        }

        if (owner != null) {
            lifecycleObserver = object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    subscription.unsubscribe()
                }
            }

            owner.lifecycle.addObserver(lifecycleObserver)
        }

        if (scope != null) {
            val job = scope.coroutineContext[Job]

            if (job != null) {
                jobHandle = job.invokeOnCompletion {
                    subscription.unsubscribe()
                }
            } else {
                Log.w(
                    TAG,
                    "subscribe(scope) called but scope has no Job: ${type.simpleName}"
                )
            }
        }

        return subscription
    }

    private fun startEventLoop() {
        eventScope.launch {
            for (event in eventChannel) {
                if (event.generation != generation.get()) {
                    Log.d(
                        TAG,
                        "Drop stale event: ${event.name}, eventGeneration=${event.generation}, currentGeneration=${generation.get()}"
                    )
                    continue
                }

                try {
                    event.action()
                } catch (e: Throwable) {
                    Log.e(TAG, "TgClient event failed: ${event.name}", e)
                }
            }
        }
    }

    /**
     * update 分发。
     *
     * 这里只做轻量 fan-out：把 update 投递给每个订阅者的 wrapper。
     * 实际回调已在 wrapper 内 launch 到 eventScope / mainScope 上并发执行，
     * 因此本方法不会被慢回调阻塞，update 事件循环也不会被拖住。
     *
     * 注意：并发执行意味着同一 update 类型的多个订阅者之间不再保证顺序。
     */
    private fun dispatchUpdate(update: TdApi.Object) {
        val list = callbacks[update.javaClass]

        if (list != null) {
            for (callback in list) {
                try {
                    callback(update)
                } catch (e: Throwable) {
                    Log.e(TAG, "Subscriber dispatch failed: ${update.javaClass.simpleName}", e)
                }
            }
        }

        // 订阅者已收到 update 后，再判断是否到了可以彻底丢弃 Client 的时机。
        // 必须在 fan-out 之后调用，保证依赖 AuthorizationStateClosed 的代码先拿到该 update。
        if (update is TdApi.UpdateAuthorizationState &&
            update.authorizationState is TdApi.AuthorizationStateClosed
        ) {
            finalizeClose()
        }
    }

    private fun postEvent(
        generation: Long,
        name: String,
        action: () -> Unit
    ) {
        val result = eventChannel.trySend(
            Event(
                generation = generation,
                name = name,
                action = action
            )
        )

        if (result.isFailure) {
            Log.e(TAG, "Failed to enqueue TDLib event: $name")
        }
    }

    /**
     * reInit / close 专用。
     *
     * 不走 TgClient.send()，避免 client 即将置空时 Close 请求丢失。
     */
    private fun sendCloseDirectly(
        targetClient: Client,
        reason: String
    ) {
        try {
            targetClient.send(TdApi.Close()) { result ->
                when (result) {
                    is TdApi.Ok -> {
                        Log.d(TAG, "TdApi.Close accepted, reason=$reason")
                    }

                    is TdApi.Error -> {
                        Log.w(
                            TAG,
                            "TdApi.Close error, reason=$reason, code=${result.code}, message=${result.message}"
                        )
                    }

                    else -> {
                        Log.d(
                            TAG,
                            "TdApi.Close result, reason=$reason, result=${result.javaClass.simpleName}"
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to send TdApi.Close(), reason=$reason", e)
        }
    }
}
