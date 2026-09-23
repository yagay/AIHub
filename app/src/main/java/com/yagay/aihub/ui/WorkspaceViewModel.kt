package com.yagay.aihub.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yagay.aihub.data.ConversationStore
import com.yagay.aihub.data.PendingAttachmentStore
import com.yagay.aihub.data.WindowStore
import com.yagay.aihub.diagnostics.DiagnosticLogger
import com.yagay.aihub.model.AttachmentMeta
import com.yagay.aihub.model.ChatMessage
import com.yagay.aihub.model.ChatWindow
import com.yagay.aihub.model.MessageRole
import com.yagay.aihub.model.ProviderSpec
import com.yagay.aihub.model.WindowSessionKey
import com.yagay.aihub.model.WindowViewMode
import com.yagay.aihub.provider.ProviderCatalog
import com.yagay.aihub.web.WebRuntime
import com.yagay.aihub.web.WindowWebRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val windowStore = WindowStore(application)
    private val conversationStore = ConversationStore(application)
    private val pendingAttachmentStore = PendingAttachmentStore(application)

    val providers = ProviderCatalog.all

    var windows by mutableStateOf<List<ChatWindow>>(emptyList())
        private set

    var activeWindowId by mutableStateOf("")
        private set

    val messages = mutableStateListOf<ChatMessage>()

    private val statuses = mutableStateMapOf<String, String?>()
    private val pendingAttachments = mutableStateMapOf<String, List<AttachmentMeta>>()
    private val drafts = mutableStateMapOf<String, String>()
    private val generationJobs = mutableMapOf<String, Job>()
    private val refreshJobs = mutableMapOf<String, Job>()
    private val syncJobs = mutableMapOf<String, Job>()

    init {
        val restored = windowStore.load()
        windows = if (restored.isEmpty()) {
            listOf(createWindowModel(ProviderCatalog.all.first().id))
        } else {
            restored.map { it.copy(generating = false, unread = false) }
        }
        activeWindowId = windowStore.loadActiveId()
            ?.takeIf { id -> windows.any { it.id == id } }
            ?: windows.first().id
        persist()
        reloadConversation()
        DiagnosticLogger.i(
            "WORKSPACE",
            "workspace_created windows=${windows.size} active=${activeWindowId.take(12)}"
        )
    }

    val activeWindow: ChatWindow
        get() = windows.firstOrNull { it.id == activeWindowId } ?: windows.first()

    val activeProvider: ProviderSpec
        get() = ProviderCatalog.byId(activeWindow.providerId)

    val activeStatus: String?
        get() = statuses[activeWindowId]

    val activePendingAttachments: List<AttachmentMeta>
        get() = pendingAttachments[activeWindowId].orEmpty()

    val activeDraft: String
        get() = drafts[activeWindowId].orEmpty()

    val boundWindows: List<ChatWindow>
        get() = windows.filter {
            !it.boundUrl.isNullOrBlank() ||
                !it.boundRepo.isNullOrBlank() ||
                !it.boundProject.isNullOrBlank()
        }

    val tabWindows: List<ChatWindow>
        get() {
            val bound = boundWindows
            val active = windows.firstOrNull { it.id == activeWindowId }
            return if (active != null && active.boundUrl.isNullOrBlank()) {
                (bound + active).distinctBy { it.id }
            } else {
                bound
            }
        }

    fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return

        val targetsRaw = intent.getStringExtra(EXTRA_TARGETS_JSON)
        if (!targetsRaw.isNullOrBlank()) {
            val array = runCatching { JSONArray(targetsRaw) }.getOrNull()
            if (array != null) {
                var merged = windows
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url").trim()
                    val provider = ProviderCatalog.fromUrl(url) ?: continue
                    val repoKey = item.optString("repoKey").trim()
                    val project = item.optString("project").trim()
                        .ifBlank {
                            repoKey.substringAfterLast('/')
                                .takeIf { repoKey.isNotBlank() }
                                .orEmpty()
                        }
                    val displayTitle = project
                        .ifBlank { item.optString("title").trim() }
                        .ifBlank { provider.name }

                    val existingIndex = merged.indexOfFirst {
                        (repoKey.isNotBlank() && it.boundRepo == repoKey) ||
                            sameBoundPage(it.boundUrl ?: it.url, url)
                    }
                    if (existingIndex >= 0) {
                        merged = merged.mapIndexed { windowIndex, window ->
                            if (windowIndex == existingIndex) {
                                window.copy(
                                    providerId = provider.id,
                                    title = displayTitle,
                                    url = url,
                                    boundUrl = url,
                                    boundRepo = repoKey.takeIf(String::isNotBlank),
                                    boundProject = project.takeIf(String::isNotBlank),
                                )
                            } else {
                                window
                            }
                        }
                    } else {
                        merged = merged + ChatWindow(
                            providerId = provider.id,
                            title = displayTitle,
                            url = url,
                            boundUrl = url,
                            boundRepo = repoKey.takeIf(String::isNotBlank),
                            boundProject = project.takeIf(String::isNotBlank),
                            viewMode = WindowViewMode.CHAT,
                            createdAt = item.optLong("addedAt", System.currentTimeMillis()),
                            lastActiveAt = item.optLong("addedAt", System.currentTimeMillis()),
                        )
                    }
                }
                windows = merged
            }
        }

        val requestedWindowId = intent
            .getStringExtra(EXTRA_WINDOW_ID)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val requestedBindUrl = intent
            .getStringExtra(EXTRA_BIND_URL)
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val requestedUrl = intent
            .getStringExtra(EXTRA_URL)
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: requestedBindUrl
        val requestedRepo = intent
            .getStringExtra(EXTRA_BIND_REPO)
            .orEmpty()
            .trim()
        val requestedProject = intent
            .getStringExtra(EXTRA_BIND_PROJECT)
            .orEmpty()
            .trim()
            .ifBlank {
                requestedRepo.substringAfterLast('/')
                    .takeIf { requestedRepo.isNotBlank() }
                    .orEmpty()
            }
        val requestedTitle = intent
            .getStringExtra(EXTRA_BIND_TITLE)
            .orEmpty()
            .trim()

        val existing = when {
            requestedWindowId != null ->
                windows.firstOrNull { it.id == requestedWindowId }
            requestedRepo.isNotBlank() ->
                windows.firstOrNull { it.boundRepo == requestedRepo }
            requestedUrl != null ->
                windows.firstOrNull {
                    sameBoundPage(it.boundUrl ?: it.url, requestedUrl)
                }
            else -> null
        }

        if (existing != null) {
            if (requestedUrl != null) {
                updateWindow(existing.id) {
                    it.copy(
                        providerId = ProviderCatalog.fromUrl(requestedUrl)?.id
                            ?: it.providerId,
                        title = requestedProject
                            .ifBlank { requestedTitle }
                            .ifBlank { it.title },
                        url = requestedUrl,
                        boundUrl = if (
                            requestedRepo.isNotBlank() ||
                            requestedProject.isNotBlank() ||
                            requestedBindUrl != null ||
                            intent.action == ACTION_BINDING_SYNC
                        ) {
                            requestedUrl
                        } else {
                            it.boundUrl
                        },
                        boundRepo = requestedRepo.takeIf(String::isNotBlank)
                            ?: it.boundRepo,
                        boundProject = requestedProject.takeIf(String::isNotBlank)
                            ?: it.boundProject,
                    )
                }
            }
            activeWindowId = existing.id
            windowStore.saveActiveId(existing.id)
        } else if (requestedUrl != null) {
            val provider = ProviderCatalog.fromUrl(requestedUrl)
            if (provider != null) {
                val isBinding =
                    requestedBindUrl != null ||
                        requestedRepo.isNotBlank() ||
                        requestedProject.isNotBlank() ||
                        intent.action == ACTION_BINDING_SYNC
                val created = ChatWindow(
                    id = requestedWindowId ?: java.util.UUID.randomUUID().toString(),
                    providerId = provider.id,
                    title = requestedProject
                        .ifBlank { requestedTitle }
                        .ifBlank { provider.name },
                    url = requestedUrl,
                    boundUrl = requestedUrl.takeIf { isBinding },
                    boundRepo = requestedRepo.takeIf(String::isNotBlank),
                    boundProject = requestedProject.takeIf(String::isNotBlank),
                    viewMode = WindowViewMode.CHAT,
                )
                windows = windows + created
                activeWindowId = created.id
                windowStore.saveActiveId(created.id)
            }
        }

        persist()
        reloadConversation()
    }

    fun requestBinding(windowId: String) {
        val target = windows.firstOrNull { it.id == windowId } ?: return
        val url = (target.url ?: target.boundUrl)
            ?.takeIf { it.isNotBlank() }

        if (url == null) {
            setStatus(
                windowId,
                "请先开始这个聊天，等网页生成会话地址后再绑定项目。",
            )
            return
        }

        val app = getApplication<Application>()
        val intent = Intent(ACTION_REQUEST_BINDING).apply {
            setPackage(YAGAYHUB_PACKAGE)
            putExtra(EXTRA_WINDOW_ID, windowId)
            putExtra(EXTRA_BIND_URL, url)
            putExtra(EXTRA_REQUESTER_PACKAGE, app.packageName)
            target.boundRepo
                ?.takeIf(String::isNotBlank)
                ?.let { putExtra(EXTRA_BIND_REPO, it) }
            target.boundProject
                ?.takeIf(String::isNotBlank)
                ?.let { putExtra(EXTRA_BIND_PROJECT, it) }
            putExtra(
                EXTRA_BIND_TITLE,
                target.boundProject.orEmpty()
                    .ifBlank { target.title }
                    .ifBlank { "AI" },
            )
            addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }

        runCatching { app.startActivity(intent) }
            .onFailure {
                setStatus(windowId, "无法打开 YagaYHub 绑定选择器")
            }
    }

    fun unbindWindow(windowId: String) {
        val target = windows.firstOrNull { it.id == windowId } ?: return
        val url = target.boundUrl ?: target.url ?: return
        val app = getApplication<Application>()

        runCatching {
            app.sendBroadcast(
                Intent(ACTION_REMOVE_BINDING).apply {
                    setPackage(YAGAYHUB_PACKAGE)
                    putExtra(EXTRA_BIND_URL, url)
                }
            )
        }
        runCatching {
            app.sendBroadcast(
                Intent(ACTION_YBROWSER_REMOVE_BINDING).apply {
                    setPackage(YBROWSER_PACKAGE)
                    putExtra(EXTRA_BIND_URL, url)
                }
            )
        }

        updateWindow(windowId) {
            it.copy(
                boundUrl = null,
                boundRepo = null,
                boundProject = null,
            )
        }

        DiagnosticLogger.i(
            "WORKSPACE",
            "window_unbound id=" + windowId.take(12) +
                " url=" + url.take(160)
        )
    }

    private fun pageIdentity(value: String?): String? = runCatching {
        val uri = Uri.parse(value.orEmpty().trim())
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme !in setOf("http", "https") || host.isBlank()) {
            return@runCatching null
        }
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        "$scheme://$host$path"
    }.getOrNull()

    private fun sameBoundPage(left: String?, right: String?): Boolean {
        val a = pageIdentity(left)
        val b = pageIdentity(right)
        return a != null && b != null && a == b
    }

    fun windowsFor(providerId: String): List<ChatWindow> =
        windows.filter { it.providerId == providerId }
            .sortedByDescending { it.lastActiveAt }

    fun updateDraft(value: String) {
        drafts[activeWindowId] = value
    }

    fun newWindow(providerId: String = activeWindow.providerId) {
        val window = createWindowModel(providerId)
        windows = windows + window
        activeWindowId = window.id
        messages.clear()
        pendingAttachments[window.id] = emptyList()
        windowStore.saveActiveId(window.id)
        persist()
        DiagnosticLogger.i(
            "WORKSPACE",
            "window_created provider=$providerId id=${window.id.take(12)}"
        )
    }

    fun switchWindow(id: String) {
        if (id == activeWindowId || windows.none { it.id == id }) return
        updateWindow(id) {
            it.copy(lastActiveAt = System.currentTimeMillis(), unread = false)
        }
        activeWindowId = id
        windowStore.saveActiveId(id)
        reloadConversation()
        DiagnosticLogger.i("WORKSPACE", "window_selected id=${id.take(12)}")
    }

    fun closeWindow(id: String, runtime: WindowWebRuntime) {
        val target = windows.firstOrNull { it.id == id } ?: return
        generationJobs.remove(id)?.cancel()
        refreshJobs.remove(id)?.cancel()
        syncJobs.remove(id)?.cancel()
        runtime.destroyWindow(id, ProviderCatalog.byId(target.providerId))
        conversationStore.clear(session(target))
        pendingAttachmentStore.clear(session(target))
        pendingAttachments.remove(id)
        drafts.remove(id)
        statuses.remove(id)

        var remaining = windows.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            remaining = listOf(createWindowModel(target.providerId))
        }
        windows = remaining

        if (activeWindowId == id) {
            activeWindowId = remaining.maxByOrNull { it.lastActiveAt }?.id ?: remaining.first().id
            windowStore.saveActiveId(activeWindowId)
            reloadConversation()
        }

        persist()
        DiagnosticLogger.i(
            "WORKSPACE",
            "window_closed id=${id.take(12)} remaining=${windows.size}"
        )
    }

    fun deleteChat(
        windowId: String,
        runtime: WindowWebRuntime,
    ) {
        val target = windows.firstOrNull {
            it.id == windowId
        } ?: return

        if (!target.boundUrl.isNullOrBlank()) {
            unbindWindow(windowId)
        }

        closeWindow(windowId, runtime)

        DiagnosticLogger.i(
            "WORKSPACE",
            "chat_deleted id=" + windowId.take(12) +
                " bound=" +
                (!target.boundUrl.isNullOrBlank())
        )
    }

    fun setViewMode(mode: WindowViewMode) {
        updateWindow(activeWindowId) {
            it.copy(viewMode = mode, lastActiveAt = System.currentTimeMillis())
        }
    }

    fun onPageChanged(windowId: String, provider: ProviderSpec, url: String) {
        val target = windows.firstOrNull { it.id == windowId } ?: return
        if (target.providerId != provider.id) return
        updateWindow(windowId) {
            it.copy(url = url, lastActiveAt = System.currentTimeMillis())
        }
    }

    fun onAttachments(windowId: String, attachments: List<AttachmentMeta>) {
        pendingAttachments[windowId] = attachments
    }

    fun send(runtime: WindowWebRuntime) {
        val target = activeWindow
        val provider = ProviderCatalog.byId(target.providerId)
        val prompt = activeDraft.trim()
        val attachments = pendingAttachments[target.id].orEmpty()

        if ((prompt.isBlank() && attachments.isEmpty()) || target.generating) return

        drafts[target.id] = ""

        val visibleText = when {
            attachments.isNotEmpty() && prompt.isBlank() ->
                "📎 " + attachments.joinToString(", ") { it.name }
            attachments.isNotEmpty() ->
                prompt + "\n\n📎 " + attachments.joinToString(", ") { it.name }
            else -> prompt
        }

        val targetMessages = conversationStore.load(session(target)).toMutableList()
        targetMessages += ChatMessage(
            role = MessageRole.USER,
            text = visibleText,
            attachments = attachments
        )
        conversationStore.save(session(target), targetMessages)

        if (target.id == activeWindowId) {
            messages.clear()
            messages.addAll(targetMessages)
        }

        if (target.title == "新对话") {
            val title = prompt.lineSequence().firstOrNull()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.take(28)
                ?: attachments.firstOrNull()?.name?.take(28)
                ?: provider.name
            updateWindow(target.id) { it.copy(title = title) }
        }

        generationJobs[target.id]?.cancel()
        generationJobs[target.id] = viewModelScope.launch {
            setGenerating(target.id, true)
            setStatus(target.id, "正在连接 ${provider.name}…")
            try {
                runtime.ensureSession(target)
                val baseline = runCatching {
                    runtime.responseSnapshot(target.id, provider)
                }.getOrDefault(WebRuntime.ResponseSnapshot())

                val sent = runCatching {
                    runtime.send(target.id, provider, prompt)
                }.onFailure {
                    DiagnosticLogger.e(
                        "WORKSPACE",
                        "send_exception provider=${provider.id} window=${target.id.take(12)}",
                        it
                    )
                }.getOrDefault(false)

                if (!sent) {
                    setStatus(
                        target.id,
                        "消息没有被官网确认提交，请在 YBrowser 网页检查。",
                    )
                    return@launch
                }

                if (attachments.isNotEmpty()) {
                    runtime.markAttachmentsSubmitted(target.id, provider)
                    pendingAttachments[target.id] = emptyList()
                }

                runtime.currentUrl(target.id, provider)?.let { url ->
                    updateWindow(target.id) { it.copy(url = url) }
                }

                setStatus(target.id, "等待 ${provider.name} 回复…")
                awaitResponse(runtime, target.id, provider, baseline)
            } finally {
                setGenerating(target.id, false)
                generationJobs.remove(target.id)
            }
        }
    }

    fun stop(runtime: WindowWebRuntime) {
        val target = activeWindow
        val provider = activeProvider
        generationJobs.remove(target.id)?.cancel()
        viewModelScope.launch {
            runCatching { runtime.stop(target.id, provider) }
            setGenerating(target.id, false)
            setStatus(target.id, "已请求停止生成")
        }
    }

    private suspend fun awaitResponse(
        runtime: WindowWebRuntime,
        windowId: String,
        provider: ProviderSpec,
        baseline: WebRuntime.ResponseSnapshot
    ) {
        var last = WebRuntime.ResponseSnapshot()
        var stableCount = 0
        var sawGenerating = false

        for (poll in 0 until 180) {
            delay(700)

            val snap = runCatching {
                runtime.responseSnapshot(windowId, provider)
            }.getOrDefault(WebRuntime.ResponseSnapshot())

            if (snap.isGenerating) sawGenerating = true

            val structuralChange =
                (snap.key.isNotBlank() && snap.key != baseline.key) ||
                    snap.responseCount > baseline.responseCount ||
                    snap.turnCount > baseline.turnCount ||
                    (
                        baseline.path.isNotBlank() &&
                            snap.path.isNotBlank() &&
                            snap.path != baseline.path
                    )

            val textChange = snap.text.isNotBlank() && snap.text != baseline.text
            val fresh = snap.text.isNotBlank() && (structuralChange || textChange || sawGenerating)

            if (snap.state == "error") {
                setStatus(
                    windowId,
                    "官网没有完成消息提交，请在 YBrowser 网页检查。",
                )
                return
            }

            if (fresh) {
                val same = snap.key == last.key && snap.text == last.text
                stableCount = if (same) stableCount + 1 else 0
                last = snap

                if (!snap.isGenerating && stableCount >= 2) {
                    commitAssistant(windowId, snap.text)

                    runtime.currentUrl(windowId, provider)?.let { url ->
                        updateWindow(windowId) { it.copy(url = url) }
                    }

                    setStatus(windowId, null)
                    DiagnosticLogger.i(
                        "WORKSPACE",
                        "response_completed provider=${provider.id} " +
                            "window=${windowId.take(12)} polls=${poll + 1}"
                    )
                    return
                }
            } else {
                stableCount = 0
            }

            if (poll == 0 || poll == 10 || poll == 40) {
                val probe = runCatching {
                    runtime.probeSummary(windowId, provider)
                }.getOrDefault("")

                DiagnosticLogger.d(
                    "WORKSPACE",
                    "response_poll provider=${provider.id} window=${windowId.take(12)} " +
                        "poll=$poll state=${snap.state} chars=${snap.text.length} " +
                        "probe=${DiagnosticLogger.scrub(probe).take(500)}"
                )
            }
        }

        if (last.text.isNotBlank()) {
            commitAssistant(windowId, last.text)
            setStatus(windowId, null)
        } else {
            setStatus(windowId, "没有读取到新的回复，可切到网页视图检查。")
        }
    }

    private fun commitAssistant(windowId: String, text: String) {
        val window = windows.firstOrNull { it.id == windowId } ?: return
        val list = conversationStore.load(session(window)).toMutableList()
        list += ChatMessage(role = MessageRole.ASSISTANT, text = text)
        conversationStore.save(session(window), list)

        if (windowId == activeWindowId) {
            messages.clear()
            messages.addAll(list)
            updateWindow(windowId) { it.copy(unread = false) }
        } else {
            updateWindow(windowId) { it.copy(unread = true) }
        }
    }

    fun syncConversation(
        runtime: WindowWebRuntime,
        windowId: String = activeWindowId,
    ) {
        val target = windows.firstOrNull {
            it.id == windowId
        } ?: return
        val provider =
            ProviderCatalog.byId(target.providerId)

        if (syncJobs[windowId]?.isActive == true) {
            return
        }

        val before =
            conversationStore.load(
                session(target)
            )

        if (
            windowId == activeWindowId &&
            before.isEmpty()
        ) {
            setStatus(
                windowId,
                "正在读取聊天内容…",
            )
        }

        syncJobs[windowId] =
            viewModelScope.launch {
                try {
                    runtime.ensureSession(target)

                    val bridgeCount =
                        runCatching {
                            runtime.syncConversation(
                                windowId,
                                provider,
                            )
                        }.onFailure {
                            DiagnosticLogger.e(
                                "WORKSPACE",
                                "bridge_sync_failed provider=" +
                                    provider.id +
                                    " window=" +
                                    windowId.take(12),
                                it,
                            )
                        }.getOrDefault(0)

                    val latest =
                        conversationStore.load(
                            session(target)
                        )

                    if (windowId == activeWindowId) {
                        messages.clear()
                        messages.addAll(latest)
                    } else if (
                        latest.isNotEmpty() &&
                        latest != before
                    ) {
                        updateWindow(windowId) {
                            it.copy(unread = true)
                        }
                    }

                    if (
                        latest.isNotEmpty() ||
                        before.isNotEmpty()
                    ) {
                        setStatus(windowId, null)
                    } else if (
                        windowId == activeWindowId
                    ) {
                        setStatus(
                            windowId,
                            "暂未读取到聊天内容。",
                        )
                    }

                    DiagnosticLogger.i(
                        "WORKSPACE",
                        "bridge_sync_complete provider=" +
                            provider.id +
                            " window=" +
                            windowId.take(12) +
                            " bridgeCount=" +
                            bridgeCount +
                            " stored=" +
                            latest.size,
                    )
                } finally {
                    syncJobs.remove(windowId)
                }
            }
    }

    fun refreshChat(
        runtime: WindowWebRuntime,
        windowId: String = activeWindowId,
    ) {
        val target = windows.firstOrNull {
            it.id == windowId
        } ?: return
        val provider =
            ProviderCatalog.byId(target.providerId)

        if (refreshJobs[windowId]?.isActive == true) {
            return
        }

        val before =
            conversationStore.load(
                session(target)
            )

        refreshJobs[windowId] =
            viewModelScope.launch {
                setStatus(
                    windowId,
                    "正在同步当前聊天历史…",
                )

                try {
                    // ensureSession is metadata-only for standalone AIHub.
                    // The history request itself is handled by YBrowser's
                    // shared ChatGPT Page API broker, so refresh no longer
                    // reloads or creates this tab's live Gecko session.
                    runtime.ensureSession(target)

                    val bridgeCount =
                        runCatching {
                            runtime.syncConversation(
                                windowId,
                                provider,
                            )
                        }.onFailure {
                            DiagnosticLogger.e(
                                "WORKSPACE",
                                "chat_refresh_sync_failed provider=" +
                                    provider.id +
                                    " window=" +
                                    windowId.take(12),
                                it,
                            )
                        }.getOrDefault(0)

                    val latest =
                        conversationStore.load(
                            session(target)
                        )

                    if (
                        windowId == activeWindowId &&
                        latest.isNotEmpty()
                    ) {
                        messages.clear()
                        messages.addAll(latest)
                    } else if (
                        windowId != activeWindowId &&
                        latest.isNotEmpty() &&
                        latest != before
                    ) {
                        updateWindow(windowId) {
                            it.copy(unread = true)
                        }
                    }

                    setStatus(
                        windowId,
                        when {
                            latest.isNotEmpty() -> null
                            before.isNotEmpty() -> null
                            bridgeCount > 0 -> null
                            else -> "暂未读取到聊天历史。"
                        },
                    )

                    DiagnosticLogger.i(
                        "WORKSPACE",
                        "chat_refresh_complete provider=" +
                            provider.id +
                            " window=" +
                            windowId.take(12) +
                            " before=" +
                            before.size +
                            " bridgeCount=" +
                            bridgeCount +
                            " stored=" +
                            latest.size,
                    )
                } finally {
                    refreshJobs.remove(windowId)
                }
            }
    }

    fun refreshConversationFromBridge(
        windowId: String,
    ) {
        val window =
            windows.firstOrNull {
                it.id == windowId
            } ?: return
        val stored =
            conversationStore.load(
                session(window)
            )
        if (windowId == activeWindowId) {
            messages.clear()
            messages.addAll(stored)
        } else if (stored.isNotEmpty()) {
            updateWindow(windowId) {
                it.copy(unread = true)
            }
        }

        if (
            statuses[windowId] ==
                "正在同步当前聊天历史…"
        ) {
            setStatus(windowId, null)
        }
    }

    private fun setGenerating(windowId: String, value: Boolean) {
        updateWindow(windowId) { it.copy(generating = value) }
    }

    private fun setViewModeFor(windowId: String, mode: WindowViewMode) {
        updateWindow(windowId) { it.copy(viewMode = mode) }
    }

    private fun setStatus(windowId: String, value: String?) {
        if (value == null) {
            statuses.remove(windowId)
        } else {
            statuses[windowId] = value
        }
    }

    private fun reloadConversation() {
        val target = activeWindow
        messages.clear()
        messages.addAll(conversationStore.load(session(target)))
        pendingAttachments[target.id] = pendingAttachmentStore.load(session(target))
        updateWindow(target.id) { it.copy(unread = false) }
    }

    private fun updateWindow(
        id: String,
        transform: (ChatWindow) -> ChatWindow
    ) {
        windows = windows.map { window ->
            if (window.id == id) transform(window) else window
        }
        persist()
    }

    private fun persist() {
        windowStore.save(
            windows.map { it.copy(generating = false, unread = false) }
        )
        if (activeWindowId.isNotBlank()) {
            windowStore.saveActiveId(activeWindowId)
        }
    }

    private fun createWindowModel(providerId: String): ChatWindow =
        ChatWindow(providerId = providerId, title = "新对话")

    private fun session(window: ChatWindow): WindowSessionKey =
        WindowSessionKey(
            providerId = window.providerId,
            windowId = window.id
        )

    companion object {
        const val ACTION_OPEN_AI =
            "com.yagay.AIHub.action.OPEN_AI"
        const val ACTION_BINDING_SYNC =
            "com.yagay.AIHub.action.CHATGPT_BINDING_SYNC"

        private const val YAGAYHUB_PACKAGE =
            "com.yagay.YagaYHub"
        private const val YBROWSER_PACKAGE =
            "com.yagay.YBrowser"
        private const val ACTION_REQUEST_BINDING =
            "com.yagay.YagaYHub.action.REQUEST_CHATGPT_BINDING"
        private const val ACTION_REMOVE_BINDING =
            "com.yagay.YagaYHub.action.REMOVE_CHATGPT_BINDING"
        private const val ACTION_YBROWSER_REMOVE_BINDING =
            "com.yagay.YBrowser.action.CHATGPT_BINDING_REMOVE"

        const val EXTRA_URL =
            "com.yagay.YBrowser.extra.URL"
        const val EXTRA_TARGETS_JSON =
            "com.yagay.YBrowser.extra.CHAT_TARGETS_JSON"
        const val EXTRA_BIND_REPO =
            "com.yagay.YBrowser.extra.BIND_REPO"
        const val EXTRA_BIND_PROJECT =
            "com.yagay.YBrowser.extra.BIND_PROJECT"
        const val EXTRA_BIND_TITLE =
            "com.yagay.YBrowser.extra.BIND_TITLE"
        const val EXTRA_BIND_URL =
            "com.yagay.YBrowser.extra.BIND_URL"
        const val EXTRA_WINDOW_ID =
            "com.yagay.YBrowser.extra.AI_WINDOW_ID"
        const val EXTRA_REQUESTER_PACKAGE =
            "com.yagay.YBrowser.extra.BIND_REQUESTER_PACKAGE"
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return WorkspaceViewModel(application) as T
        }
    }
}
