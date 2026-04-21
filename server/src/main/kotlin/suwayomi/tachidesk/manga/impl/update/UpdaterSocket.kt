package suwayomi.tachidesk.manga.impl.update

import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.websocket.WsContext
import io.javalin.websocket.WsMessageContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import suwayomi.tachidesk.manga.impl.Library
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

object UpdaterSocket : Websocket<UpdateStatus>() {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val updater: IUpdater by injectLazy()
    private var job: Job? = null
    private val sessionToUserId = ConcurrentHashMap<String, Int>()

    override fun notifyClient(
        ctx: WsContext,
        value: UpdateStatus?,
    ) {
        val userId = sessionToUserId[ctx.sessionId()] ?: return
        val scopedValue =
            Library.filterUpdateStatusForUser(
                userId = userId,
                status = value ?: updater.statusDeprecated.value,
            )
        ctx.send(scopedValue)
    }

    override fun handleRequest(ctx: WsMessageContext) {
        when (ctx.message()) {
            "STATUS" -> {
                notifyClient(ctx, updater.statusDeprecated.value)
            }

            else -> {
                ctx.send(
                    """
                        |Invalid command.
                        |Supported commands are:
                        |    - STATUS
                        |       sends the current update status
                        |
                    """.trimMargin(),
                )
            }
        }
    }

    fun addClient(
        ctx: WsContext,
        userId: Int,
    ) {
        sessionToUserId[ctx.sessionId()] = userId
        addClient(ctx)
    }

    override fun addClient(ctx: WsContext) {
        logger.info { ctx.sessionId() }
        super.addClient(ctx)
        if (job?.isActive != true) {
            job = start()
        }
    }

    override fun removeClient(ctx: WsContext) {
        sessionToUserId.remove(ctx.sessionId())
        super.removeClient(ctx)
        if (clients.isEmpty()) {
            job?.cancel()
            job = null
        }
    }

    override fun notifyAllClients(value: UpdateStatus) {
        clients.values.forEach { notifyClient(it, value) }
    }

    fun start(): Job =
        updater.status
            .onEach {
                notifyAllClients(it)
            }.launchIn(scope)
}
