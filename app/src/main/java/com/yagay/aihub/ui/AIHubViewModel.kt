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

    // Generation state is session-scoped. This lets ChatGPT keep polling in the
    // background while the user switches to Gemini/Claude/etc. and starts more work.
    private val generatingSessions = mutableStateMapOf<String, Boolean>()
    private val sessionStatuses = mutableStateMapOf<String, String?>()
    private val unreadSessions = mutableStateMapOf<String, Boolean>()
    private val generationJobs = mutableMapOf<String, Job>()

    init {
        DiagnosticLogger.i("VM", "viewmodel_created providers=${providers.size} accounts=${accounts.size}")
        reloadConversation()
    }

    val selectedProvider get() = ProviderCatalog.byId(selectedProviderId)
    val selectedAccount: AccountProfile get() = accounts.firstOrNull { it.id == selectedAccountId } ?: defaultAccount(selectedProviderId)
    val session: SessionKey get() = SessionKey(selectedProviderId, selectedAccount.id)
    val isGenerating: Boolean get() = isSessionGenerating(session)
    val status: String? get() = sessionStatuses[session.storageKey]

    fun accountsFor(providerId: String) = accounts.filter { it.providerId == providerId }

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

            val baselineResponse = responseBaseline(runtime, currentSession, provider)
            val sent = runCatching { runtime.send(currentSession, provider, text) }
                .onFailure { DiagnosticLogger.e("CHAT", "send_exception provider=${provider.id}", it) }
                .getOrDefault(false)
            if (!sent) {
                DiagnosticLogger.w("CHAT", "send_failed provider=${provider.id} reason=adapter_or_dom loginHint=$loggedInHint attachments=$attachmentCount")
                setGenerating(currentSession, false)
                if (isCurrentSession(currentSession)) showWeb = true
                setStatus(
                    currentSession,
                    if (!loggedInHint) {
                        "没有找到 ${provider.name} 的聊天输入框。请在网页中确认已登录，然后返回重试。"
                    } else {
                        "网页结构可能已经变化，未找到输入框或发送按钮。"
                    }
                )
                return@launchGeneration
            }

            DiagnosticLogger.i("CHAT", "send_injected provider=${provider.id} loginHint=$loggedInHint attachments=$attachmentCount")
            setStatus(currentSession, "等待 ${provider.name} 回复…")
            awaitProviderResponse(
                runtime = runtime,
                currentSession = currentSession,
                provider = provider,
                baselineResponse = baselineResponse,
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
                val baselineResponse = responseBaseline(runtime, currentSession, provider)
                val result = runCatching { runtime.performAction(currentSession, provider, action) }
                    .onFailure { DiagnosticLogger.e("CAP", "provider_action_exception provider=${provider.id} action=$action", it) }
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
                    baselineResponse = baselineResponse,
                    replaceLastAssistant = true,
                    source = "action:$action"
                )
            }
            return
        }

        viewModelScope.launch {
            val result = runCatching { runtime.performAction(currentSession, provider, action) }
                .onFailure { DiagnosticLogger.e("CAP", "provider_action_exception provider=${provider.id} action=$action", it) }
                .getOrDefault("")

            if (result != "ok" && result != "scheduled") {
                setStatus(currentSession, "${provider.name} 当前页面不支持此操作（$action）。")
                return@launch
            }

            if (action == "deleteConversation") {
                conversationStore.clear(currentSession)
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
            DiagnosticLogger.i("CHAT", "stop_requested provider=${provider.id} account=${safeAccountId(currentSession.accountId)}")
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
        DiagnosticLogger.i("CHAT", "new_chat provider=${provider.id} account=${safeAccountId(current.accountId)}")
        messages.clear()
        conversationStore.clear(current)
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

    private suspend fun responseBaseline(runtime: WebRuntime, currentSession: SessionKey, provider: ProviderSpec): String {
        val baseline = runCatching { runtime.lastResponse(currentSession, provider) }
            .onFailure { DiagnosticLogger.w("CHAT", "response_baseline_error provider=${provider.id} type=${it.javaClass.simpleName}") }
            .getOrDefault("")
        DiagnosticLogger.d("CHAT", "response_baseline provider=${provider.id} chars=${baseline.length}")
        return baseline
    }

    private suspend fun awaitProviderResponse(
        runtime: WebRuntime,
        currentSession: SessionKey,
        provider: ProviderSpec,
        baselineResponse: String,
        replaceLastAssistant: Boolean,
        source: String
    ) {
        var last = ""
        var stableCount = 0
        var sawGenerating = false
        var idleAfterGenerating = 0
        var staleLogged = false

        for (poll in 0 until 180) {
            delay(700)
            val response = runCatching { runtime.lastResponse(currentSession, provider) }
                .onFailure { DiagnosticLogger.w("CHAT", "response_poll_error provider=${provider.id} poll=$poll source=$source type=${it.javaClass.simpleName}") }
                .getOrDefault("")
            val generating = runCatching { runtime.isGenerating(currentSession, provider) }.getOrDefault(false)
            val changedFromBaseline = response.isNotBlank() && response != baselineResponse

            if (generating) {
                sawGenerating = true
                idleAfterGenerating = 0
            } else if (sawGenerating && !changedFromBaseline) {
                idleAfterGenerating++
            } else {
                idleAfterGenerating = 0
            }

            val identicalAfterGeneration = response.isNotBlank() &&
                response == baselineResponse &&
                sawGenerating &&
                !generating &&
                idleAfterGenerating >= 3
            val freshResponse = changedFromBaseline || identicalAfterGeneration

            if (!freshResponse && response.isNotBlank() && !staleLogged) {
                staleLogged = true
                DiagnosticLogger.d(
                    "CHAT",
                    "stale_response_ignored provider=${provider.id} poll=$poll source=$source chars=${response.length} baselineChars=${baselineResponse.length}"
                )
            }

            if (poll == 0 || poll == 4 || poll == 10 || poll == 20) {
                DiagnosticLogger.d(
                    "CHAT",
                    "response_poll provider=${provider.id} poll=$poll source=$source responseChars=${response.length} generating=$generating fresh=$freshResponse baselineMatch=${response.isNotBlank() && response == baselineResponse} background=${!isCurrentSession(currentSession)}"
                )
                if (response.isBlank() || !freshResponse) {
                    val probe = runCatching { runtime.probeSummary(currentSession, provider) }.getOrDefault("")
                    if (probe.isNotBlank()) {
                        DiagnosticLogger.d(
                            "CHAT",
                            "dom_probe provider=${provider.id} poll=$poll source=$source summary=${DiagnosticLogger.scrub(probe).take(1400)}"
                        )
                    }
                }
            }

            if (freshResponse) {
                if (response == last) stableCount++ else stableCount = 0
                last = response
                if (!generating && stableCount >= 2) {
                    commitAssistantResponse(response, currentSession, replaceLastAssistant)
                    setGenerating(currentSession, false)
                    setStatus(currentSession, null)
                    DiagnosticLogger.i(
                        "CHAT",
                        "response_completed provider=${provider.id} source=$source responseChars=${response.length} polls=${poll + 1} baselineChars=${baselineResponse.length} background=${!isCurrentSession(currentSession)}"
                    )
                    return
                }
            } else {
                stableCount = 0
            }

            if (sawGenerating && idleAfterGenerating >= 8 && last.isBlank() && response.isBlank()) {
                val probe = runCatching { runtime.probeSummary(currentSession, provider) }.getOrDefault("")
                DiagnosticLogger.w(
                    "CHAT",
                    "response_extract_failed provider=${provider.id} poll=$poll source=$source probe=${DiagnosticLogger.scrub(probe).take(1400)}"
                )
                setGenerating(currentSession, false)
                setStatus(currentSession, "${provider.name} 已完成回复，但 AIHub 没有识别到回答。请导出诊断日志。")
                return
            }
        }

        if (last.isNotBlank()) {
            commitAssistantResponse(last, currentSession, replaceLastAssistant)
        }
        setGenerating(currentSession, false)
        setStatus(currentSession, if (last.isBlank()) "没有读取到新的回复，可打开网页检查当前状态。" else null)
        DiagnosticLogger.w(
            "CHAT",
            "response_timeout provider=${provider.id} source=$source lastResponseChars=${last.length} baselineChars=${baselineResponse.length}"
        )
    }

    private fun commitAssistantResponse(text: String, target: SessionKey, replaceLastAssistant: Boolean) {
        val targetMessages = conversationStore.load(target).toMutableList()
        if (replaceLastAssistant) {
            val index = targetMessages.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (index >= 0) {
                targetMessages[index] = targetMessages[index].copy(text = text, timestamp = System.currentTimeMillis())
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

    private fun isSessionGenerating(target: SessionKey): Boolean = generatingSessions[target.storageKey] == true

    private fun setStatus(target: SessionKey, value: String?) {
        if (value == null) sessionStatuses.remove(target.storageKey) else sessionStatuses[target.storageKey] = value
    }

    private fun isCurrentSession(target: SessionKey): Boolean = target == session

    private fun reloadConversation() {
        messages.clear()
        messages.addAll(conversationStore.load(session))
        unreadSessions[session.storageKey] = false
        DiagnosticLogger.d(
            "VM",
            "conversation_loaded provider=$selectedProviderId messages=${messages.size} generating=${isGenerating} backgroundJobs=${generationJobs.size}"
        )
    }

    private fun safeAccountId(id: String): String = id.take(12)

    private fun defaultAccount(providerId: String): AccountProfile =
        accounts.firstOrNull { it.providerId == providerId }
            ?: AccountProfile("default_$providerId", providerId, "Default")

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AIHubViewModel(application) as T
    }
}
