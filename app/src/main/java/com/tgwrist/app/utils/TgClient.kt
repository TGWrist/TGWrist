package com.tgwrist.app.utils

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
     * 保持你原来的 close 行为：
     *
     * 1. 旧 Client 发送 TdApi.Close()
     * 2. client = null
     * 3. notify == 1 时发送 TgClientClose
     *
     * 不等待 AuthorizationStateClosed。
     */
    fun close(notify: Int = 1) {
        val oldClient: Client?

        synchronized(clientLock) {
            oldClient = client
        }

        if (oldClient != null) {
            sendCloseDirectly(oldClient, "close")
        }

        synchronized(clientLock) {
            if (client === oldClient) {
                client = null

                // 废弃旧 Client 的 generation。
                generation.incrementAndGet()
            }
        }

        if (notify == 1) {
            GlobalEventBus.send(TgClientClose)
        }
    }

    /**
     * 发送 TDLib 请求。
     *
     * response callback 也不直接在 TDLib 回调线程执行，
     * 而是进入 TgClient 的事件队列。
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
                postEvent(
                    generation = currentGeneration,
                    name = "Response:$queryName"
                ) {
                    callback(result)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "TDLib send failed: $queryName", e)
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
                    callback(typedObj)
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

    private fun dispatchUpdate(update: TdApi.Object) {
        val list = callbacks[update.javaClass] ?: return

        for (callback in list) {
            try {
                callback(update)
            } catch (e: Throwable) {
                Log.e(TAG, "Subscriber callback failed: ${update.javaClass.simpleName}", e)
            }
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
