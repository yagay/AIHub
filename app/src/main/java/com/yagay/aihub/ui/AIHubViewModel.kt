package com.yagay.aihub.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yagay.aihub.data.AccountStore
import com.yagay.aihub.data.ConversationBindingStore
import com.yagay.aihub.data.ConversationStore
import com.yagay.aihub.diagnostics.DiagnosticLogger
import com.yagay.aihub.model.AccountProfile
import com.yagay.aihub.model.ChatMessage
import com.yagay.aihub.model.MessageRole
import com.yagay.aihub.model.ProviderSpec
import com.yagay.aihub.model.SessionKey
import com.yagay.aihub.provider.ProviderCatalog
import com.yagay.aihub.web.WebRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AIHubViewModel(application: Application) : AndroidViewModel(application) {
    private val accountStore = AccountStore(application)
    private val conversationStore = ConversationStore(application)
    private val bindingStore = ConversationBindingStore(application)

    val providers = ProviderCatalog.all
    var accounts by mutableStateOf(accountStore.loadAll())
        private set
    var selectedProviderId by mutableStateOf(providers.first().id)
        private set
    var selectedAccountId by mutableStateOf(defaultAccount(selectedProviderId).id)
        private set
    val messages = mutableStateListOf<ChatMessage>()
    var showWeb by mutableStateOf(false)
        private set

    private val generatingSessions = mutableStateMapOf<String, Boolean>()
    private val sessionStatuses = mutableStateMapOf<String, String?>()
    private val unreadSessions = mutableStateMapOf<String, Boolean>()
    private val generationJobs = mutableMapOf<String, Job>()

    init {
        DiagnosticLogger.i("VM", "viewmodel_created providers=${providers.size} accounts=${accounts.size}")
        reloadConversation()
    }

    val selectedProvider get() = ProviderCatalog.byId(selectedProviderId)
    val selectedAccount: AccountProfile
        get() = accounts.firstOrNull { it.id == selectedAccountId } ?: defaultAccount(selectedProviderId)
    val session: SessionKey get() = SessionKey(selectedProviderId, selectedAccount.id)
    val isGenerating: Boolean get() = isSessionGenerating(session)
    val status: String? get() = sessionStatuses[session.storageKey]

    fun accountsFor(providerId: String) = accounts.filter { it.providerId == providerId }

    fun preferredWebUrl(): String? = bindingStore.loadUrl(session)

    fun onWebPageChanged(target: SessionKey, provider: ProviderSpec, url: String) {
        if (target.providerId != provider.id) return
        bindingStore.saveUrl(target, url)
    }

    fun isSessionGenerating(providerId: String, accountId: String): Boolean =
        generatingSessions[SessionKey(providerId, accountId).storageKey] == true

    fun hasUnreadResponse(providerId: String, accountId: String): Boolean =
        unreadSessions[SessionKey(providerId, accountId).storageKey] == true

    fun selectAccount(accountId: String) {
        val account = accounts.firstOrNull { it.id == accountId } ?: return
        val previous = session
        selectedProviderId = account.providerId
        selectedAccountId = account.id
        unreadSessions[session.storageKey] = false
        DiagnosticLogger.i(
            "VM",
            "account_selected provider=${account.providerId} account=${safeAccountId(account.id)} previous=${previous.storageKey} backgroundJobs=${generationJobs.size}"
        )
        reloadConversation()
    }

    fun addAccount(label: String, runtime: WebRuntime) {
        if (!runtime.supportsMultiProfile) {
            DiagnosticLogger.w("VM", "add_account_rejected provider=$selectedProviderId reason=multi_profile_unsupported")
            setStatus(session, "当前 Android System WebView 不支持 Multi-Profile，暂时只能使用每个 AI 的默认账号。")
            return
        }
        val account = accountStore.add(selectedProviderId, label)
        accounts = accountStore.loadAll()
        selectedAccountId = account.id
        messages.clear()
        unreadSessions[session.storageKey] = false
        bindingStore.clear(session)
        showWeb = true
        DiagnosticLogger.i("VM", "account_added provider=$selectedProviderId account=${safeAccountId(account.id)}")
        setStatus(session, "请登录 ${selectedProvider.name} 的新账号")
    }

    fun openWeb() {
        DiagnosticLogger.i("VM", "web_opened provider=$selectedProviderId account=${safeAccountId(selectedAccount.id)}")
        showWeb = true
        setStatus(session, null)
    }

    fun closeWeb() {
        DiagnosticLogger.i("VM", "web_closed provider=$selectedProviderId")
        showWeb = false
    }

    fun send(prompt: String, runtime: WebRuntime, attachmentCount: Int = 0) {
        val text = prompt.trim()
        val currentSession = session
        val provider = selectedProvider
        if ((text.isBlank() && attachmentCount <= 0) || isSessionGenerating(currentSession)) return

        DiagnosticLogger.i(
            "CHAT",
            "send_started provider=${provider.id} account=${safeAccountId(currentSession.accountId)} promptChars=${text.length} attachments=$attachmentCount"
        )
        val visibleUserText = when {
            attachmentCount > 0 && text.isBlank() -> "📎 $attachmentCount 个附件"
            attachmentCount > 0 -> "$text\n\n📎 $attachmentCount 个附件"
            else -> text
        }
        messages += ChatMessage(role = MessageRole.USER, text = visibleUserText)
        conversationStore.save(currentSession, messages.toList())
        unreadSessions[currentSession.storageKey] = false

        launchGeneration(currentSession) {
            setGenerating(currentSession, true)
            setStatus(currentSession, "正在连接 ${provider.name}…")

            val loggedInHint = runCatching { runtime.isLoggedIn(currentSession, provider) }
                .onFailure { DiagnosticLogger.e("CHAT", "login_check_exception provider=${provider.id}", it) }
                .getOrDefault(false)
            if (!loggedInHint) {
                DiagnosticLogger.w("CHAT", "login_preflight_false provider=${provider.id} action=try_send_anyway")
            }

            val baseline = responseBaseline(runtime, currentSession, provider)
            val sent = runCatching { runtime.send(currentSession, provider, text) }
                .onFailure { DiagnosticLogger.e("CHAT", "send_exception provider=${provider.id}", it) }
                .getOrDefault(false)
            if (!sent) {
                DiagnosticLogger.w(
                    "CHAT",
                    "send_failed provider=${provider.id} reason=adapter_or_dom loginHint=$loggedInHint attachments=$attachmentCount"
                )
                setGenerating(currentSession, false)
                if (isCurrentSession(currentSession)) showWeb = true
                setStatus(
                    currentSession,
                    if (!loggedInHint) {
                        "没有找到 ${provider.name} 的聊天输入框。请在网页中确认已登录，然后返回重试。"
                    } else {
                        "消息没有被官网确认提交。请打开官网检查附件、限额或页面状态。"
                    }
                )
                return@launchGeneration
            }

            runtime.currentUrl(currentSession, provider)?.let { bindingStore.saveUrl(currentSession, it) }
            DiagnosticLogger.i(
                "CHAT",
                "send_injected provider=${provider.id} loginHint=$loggedInHint attachments=$attachmentCount"
            )
            setStatus(currentSession, "等待 ${provider.name} 回复…")
            awaitProviderResponse(
                runtime = runtime,
                currentSession = currentSession,
                provider = provider,
                baseline = baseline,
                replaceLastAssistant = false,
                source = "send"
            )
        }
    }

    fun runProviderAction(action: String, runtime: WebRuntime) {
        val currentSession = session
        val provider = selectedProvider
        val generationAction = action == "retry" || action == "continue"
        if (generationAction && isSessionGenerating(currentSession)) return

        if (generationAction) {
            launchGeneration(currentSession) {
                val baseline = responseBaseline(runtime, currentSession, provider)
                val result = runCatching { runtime.performAction(currentSession, provider, action) }
                    .onFailure {
                        DiagnosticLogger.e(
                            "CAP",
                            "provider_action_exception provider=${provider.id} action=$action",
                            it
                        )
                    }
                    .getOrDefault("")

                if (result != "ok" && result != "scheduled") {
                    setStatus(currentSession, "${provider.name} 当前页面不支持此操作（$action）。")
                    return@launchGeneration
                }

                setGenerating(currentSession, true)
                setStatus(currentSession, if (action == "retry") "正在重新生成…" else "正在继续生成…")
                awaitProviderResponse(
                    runtime = runtime,
                    currentSession = currentSession,
                    provider = provider,
                    baseline = baseline,
                    replaceLastAssistant = true,
                    source = "action:$action"
                )
            }
            return
        }

        viewModelScope.launch {
            val result = runCatching { runtime.performAction(currentSession, provider, action) }
                .onFailure {
                    DiagnosticLogger.e(
                        "CAP",
                        "provider_action_exception provider=${provider.id} action=$action",
                        it
                    )
                }
                .getOrDefault("")

            if (result != "ok" && result != "scheduled") {
                setStatus(currentSession, "${provider.name} 当前页面不支持此操作（$action）。")
                return@launch
            }

            if (action == "deleteConversation") {
                conversationStore.clear(currentSession)
                bindingStore.clear(currentSession)
                unreadSessions[currentSession.storageKey] = false
                if (isCurrentSession(currentSession)) messages.clear()
            }
            setStatus(currentSession, null)
        }
    }

    fun stop(runtime: WebRuntime) {
        val currentSession = session
        val provider = selectedProvider
        viewModelScope.launch {
            DiagnosticLogger.i(
                "CHAT",
                "stop_requested provider=${provider.id} account=${safeAccountId(currentSession.accountId)}"
            )
            generationJobs.remove(currentSession.storageKey)?.cancel()
            runtime.stop(currentSession, provider)
            setGenerating(currentSession, false)
            setStatus(currentSession, "已请求停止生成")
        }
    }

    fun newChat(runtime: WebRuntime) {
        val current = session
        if (isSessionGenerating(current)) {
            setStatus(current, "当前对话正在生成，请先停止后再新建对话。")
            return
        }
        val provider = selectedProvider
        DiagnosticLogger.i(
            "CHAT",
            "new_chat provider=${provider.id} account=${safeAccountId(current.accountId)}"
        )
        messages.clear()
        conversationStore.clear(current)
        bindingStore.clear(current)
        unreadSessions[current.storageKey] = false
        viewModelScope.launch {
            runtime.newChat(current, provider)
            setStatus(current, null)
        }
    }

    private fun launchGeneration(target: SessionKey, block: suspend () -> Unit) {
        val key = target.storageKey
        generationJobs[key]?.cancel()
        generationJobs[key] = viewModelScope.launch {
            try {
                block()
            } finally {
                generationJobs.remove(key)
            }
        }
    }

    private suspend fun responseBaseline(
        runtime: WebRuntime,
        currentSession: SessionKey,
        provider: ProviderSpec
    ): WebRuntime.ResponseSnapshot {
        val baseline = runCatching { runtime.responseSnapshot(currentSession, provider) }
            .onFailure {
                DiagnosticLogger.w(
                    "CHAT",
                    "response_baseline_error provider=${provider.id} type=${it.javaClass.simpleName}"
                )
            }
            .getOrDefault(WebRuntime.ResponseSnapshot())
        DiagnosticLogger.d(
            "CHAT",
            "response_baseline provider=${provider.id} chars=${baseline.text.length} key=${baseline.key.takeLast(40)} responses=${baseline.responseCount} turns=${baseline.turnCount} path=${baseline.path}"
        )
        return baseline
    }

    private suspend fun awaitProviderResponse(
        runtime: WebRuntime,
        currentSession: SessionKey,
        provider: ProviderSpec,
        baseline: WebRuntime.ResponseSnapshot,
        replaceLastAssistant: Boolean,
        source: String
    ) {
        var last = WebRuntime.ResponseSnapshot()
        var stableCount = 0
        var sawGenerating = false
        var idlePolls = 0
        var staleLogged = false

        for (poll in 0 until 180) {
            delay(700)
            val snap = runCatching { runtime.responseSnapshot(currentSession, provider) }
                .onFailure {
                    DiagnosticLogger.w(
                        "CHAT",
                        "response_poll_error provider=${provider.id} poll=$poll source=$source type=${it.javaClass.simpleName}"
                    )
                }
                .getOrDefault(WebRuntime.ResponseSnapshot())

            val generating = snap.isGenerating
            if (generating) {
                sawGenerating = true
                idlePolls = 0
            } else if (sawGenerating) {
                idlePolls++
            }

            val structuralChange = snap.key.isNotBlank() && snap.key != baseline.key ||
                snap.responseCount > baseline.responseCount ||
                snap.turnCount > baseline.turnCount ||
                (baseline.path.isNotBlank() && snap.path.isNotBlank() && snap.path != baseline.path)
            val textChange = snap.text.isNotBlank() && snap.text != baseline.text
            val sameTextGenerationAction = replaceLastAssistant &&
                snap.text.isNotBlank() && sawGenerating && !generating && idlePolls >= 2
            val freshResponse = snap.text.isNotBlank() && (structuralChange || textChange || sameTextGenerationAction)

            if (!freshResponse && snap.text.isNotBlank() && !staleLogged) {
                staleLogged = true
                DiagnosticLogger.d(
                    "CHAT",
                    "stale_response_ignored provider=${provider.id} poll=$poll source=$source chars=${snap.text.length} key=${snap.key.takeLast(40)} baselineChars=${baseline.text.length} baselineKey=${baseline.key.takeLast(40)}"
                )
            }

            if (poll == 0 || poll == 4 || poll == 10 || poll == 20 || poll == 40) {
                DiagnosticLogger.d(
                    "CHAT",
                    "response_poll provider=${provider.id} poll=$poll source=$source state=${snap.state} reason=${snap.reason} responseChars=${snap.text.length} fresh=$freshResponse keyChanged=${snap.key != baseline.key} responseGrowth=${snap.responseCount > baseline.responseCount} turnGrowth=${snap.turnCount > baseline.turnCount} background=${!isCurrentSession(currentSession)}"
                )
                if (snap.text.isBlank() || !freshResponse) {
                    val probe = runCatching { runtime.probeSummary(currentSession, provider) }.getOrDefault("")
                    if (probe.isNotBlank()) {
                        DiagnosticLogger.d(
                            "CHAT",
                            "dom_probe provider=${provider.id} poll=$poll source=$source summary=${DiagnosticLogger.scrub(probe).take(1800)}"
                        )
                    }
                }
            }

            if (snap.state == "error") {
                setGenerating(currentSession, false)
                setStatus(currentSession, "${provider.name} 官网没有完成消息提交。请打开官网检查附件或页面提示。")
                DiagnosticLogger.w(
                    "CHAT",
                    "generation_state_error provider=${provider.id} source=$source reason=${snap.reason}"
                )
                return
            }

            if (freshResponse) {
                val sameSnapshot = snap.key == last.key && snap.text == last.text
                stableCount = if (sameSnapshot) stableCount + 1 else 0
                last = snap
                if (!generating && stableCount >= 2) {
                    commitAssistantResponse(snap.text, currentSession, replaceLastAssistant)
                    runtime.currentUrl(currentSession, provider)?.let { bindingStore.saveUrl(currentSession, it) }
                    setGenerating(currentSession, false)
                    setStatus(currentSession, null)
                    DiagnosticLogger.i(
                        "CHAT",
                        "response_completed provider=${provider.id} source=$source responseChars=${snap.text.length} key=${snap.key.takeLast(40)} polls=${poll + 1} background=${!isCurrentSession(currentSession)}"
                    )
                    return
                }
            } else {
                stableCount = 0
            }

            if (sawGenerating && idlePolls >= 8 && last.text.isBlank() && snap.text.isBlank()) {
                val probe = runCatching { runtime.probeSummary(currentSession, provider) }.getOrDefault("")
                DiagnosticLogger.w(
                    "CHAT",
                    "response_extract_failed provider=${provider.id} poll=$poll source=$source state=${snap.state} reason=${snap.reason} probe=${DiagnosticLogger.scrub(probe).take(1800)}"
                )
                setGenerating(currentSession, false)
                setStatus(currentSession, "${provider.name} 已完成回复，但 AIHub 没有识别到回答。请导出诊断日志。")
                return
            }
        }

        if (last.text.isNotBlank()) {
            commitAssistantResponse(last.text, currentSession, replaceLastAssistant)
            runtime.currentUrl(currentSession, provider)?.let { bindingStore.saveUrl(currentSession, it) }
        }
        setGenerating(currentSession, false)
        setStatus(currentSession, if (last.text.isBlank()) "没有读取到新的回复，可打开网页检查当前状态。" else null)
        DiagnosticLogger.w(
            "CHAT",
            "response_timeout provider=${provider.id} source=$source lastResponseChars=${last.text.length} lastKey=${last.key.takeLast(40)} baselineChars=${baseline.text.length}"
        )
    }

    private fun commitAssistantResponse(
        text: String,
        target: SessionKey,
        replaceLastAssistant: Boolean
    ) {
        val targetMessages = conversationStore.load(target).toMutableList()
        if (replaceLastAssistant) {
            val index = targetMessages.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (index >= 0) {
                targetMessages[index] = targetMessages[index].copy(
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                targetMessages += ChatMessage(role = MessageRole.ASSISTANT, text = text)
            }
        } else {
            targetMessages += ChatMessage(role = MessageRole.ASSISTANT, text = text)
        }
        conversationStore.save(target, targetMessages)

        if (isCurrentSession(target)) {
            messages.clear()
            messages.addAll(targetMessages)
            unreadSessions[target.storageKey] = false
        } else {
            unreadSessions[target.storageKey] = true
        }
    }

    private fun setGenerating(target: SessionKey, value: Boolean) {
        if (value) generatingSessions[target.storageKey] = true else generatingSessions.remove(target.storageKey)
    }

    private fun isSessionGenerating(target: SessionKey): Boolean =
        generatingSessions[target.storageKey] == true

    private fun setStatus(target: SessionKey, value: String?) {
        if (value == null) sessionStatuses.remove(target.storageKey)
        else sessionStatuses[target.storageKey] = value
    }

    private fun isCurrentSession(target: SessionKey): Boolean = target == session

    private fun reloadConversation() {
        messages.clear()
        messages.addAll(conversationStore.load(session))
        unreadSessions[session.storageKey] = false
        DiagnosticLogger.d(
            "VM",
            "conversation_loaded provider=$selectedProviderId messages=${messages.size} binding=${bindingStore.loadUrl(session) != null} generating=$isGenerating backgroundJobs=${generationJobs.size}"
        )
    }

    private fun safeAccountId(id: String): String = id.take(12)

    private fun defaultAccount(providerId: String): AccountProfile =
        accounts.firstOrNull { it.providerId == providerId }
            ?: AccountProfile("default_$providerId", providerId, "Default")

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AIHubViewModel(application) as T
    }
}
