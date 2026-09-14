package com.yagay.aihub.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.yagay.aihub.model.SessionKey
import com.yagay.aihub.provider.ProviderCatalog
import com.yagay.aihub.web.WebRuntime
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
    var isGenerating by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    init {
        DiagnosticLogger.i("VM", "viewmodel_created providers=${providers.size} accounts=${accounts.size}")
        reloadConversation()
    }

    val selectedProvider get() = ProviderCatalog.byId(selectedProviderId)
    val selectedAccount: AccountProfile get() = accounts.firstOrNull { it.id == selectedAccountId } ?: defaultAccount(selectedProviderId)
    val session: SessionKey get() = SessionKey(selectedProviderId, selectedAccount.id)

    fun accountsFor(providerId: String) = accounts.filter { it.providerId == providerId }

    fun selectAccount(accountId: String) {
        if (isGenerating) {
            DiagnosticLogger.w("VM", "account_switch_ignored generating=true")
            return
        }
        val account = accounts.firstOrNull { it.id == accountId } ?: return
        selectedProviderId = account.providerId
        selectedAccountId = account.id
        DiagnosticLogger.i("VM", "account_selected provider=${account.providerId} account=${safeAccountId(account.id)}")
        status = null
        reloadConversation()
    }

    fun addAccount(label: String, runtime: WebRuntime) {
        if (!runtime.supportsMultiProfile) {
            DiagnosticLogger.w("VM", "add_account_rejected provider=$selectedProviderId reason=multi_profile_unsupported")
            status = "当前 Android System WebView 不支持 Multi-Profile，暂时只能使用每个 AI 的默认账号。"
            return
        }
        val account = accountStore.add(selectedProviderId, label)
        accounts = accountStore.loadAll()
        selectedAccountId = account.id
        messages.clear()
        showWeb = true
        DiagnosticLogger.i("VM", "account_added provider=$selectedProviderId account=${safeAccountId(account.id)}")
        status = "请登录 ${selectedProvider.name} 的新账号"
    }

    fun openWeb() {
        DiagnosticLogger.i("VM", "web_opened provider=$selectedProviderId account=${safeAccountId(selectedAccount.id)}")
        showWeb = true
        status = null
    }

    fun closeWeb() {
        DiagnosticLogger.i("VM", "web_closed provider=$selectedProviderId")
        showWeb = false
    }

    fun send(prompt: String, runtime: WebRuntime, attachmentCount: Int = 0) {
        val text = prompt.trim()
        if ((text.isBlank() && attachmentCount <= 0) || isGenerating) return
        val currentSession = session
        val provider = selectedProvider
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
        persist(currentSession)

        viewModelScope.launch {
            isGenerating = true
            status = "正在连接 ${provider.name}…"

            val loggedInHint = runCatching { runtime.isLoggedIn(currentSession, provider) }
                .onFailure { DiagnosticLogger.e("CHAT", "login_check_exception provider=${provider.id}", it) }
                .getOrDefault(false)
            if (!loggedInHint) {
                DiagnosticLogger.w("CHAT", "login_preflight_false provider=${provider.id} action=try_send_anyway")
            }

            val baselineResponse = runCatching { runtime.lastResponse(currentSession, provider) }
                .onFailure { DiagnosticLogger.w("CHAT", "response_baseline_error provider=${provider.id} type=${it.javaClass.simpleName}") }
                .getOrDefault("")
            DiagnosticLogger.d("CHAT", "response_baseline provider=${provider.id} chars=${baselineResponse.length}")

            val sent = runCatching { runtime.send(currentSession, provider, text) }
                .onFailure { DiagnosticLogger.e("CHAT", "send_exception provider=${provider.id}", it) }
                .getOrDefault(false)
            if (!sent) {
                DiagnosticLogger.w("CHAT", "send_failed provider=${provider.id} reason=adapter_or_dom loginHint=$loggedInHint attachments=$attachmentCount")
                isGenerating = false
                showWeb = true
                status = if (!loggedInHint) {
                    "没有找到 ${provider.name} 的聊天输入框。请在网页中确认已登录，然后返回重试。"
                } else {
                    "网页结构可能已经变化，未找到输入框或发送按钮。"
                }
                return@launch
            }

            DiagnosticLogger.i("CHAT", "send_injected provider=${provider.id} loginHint=$loggedInHint attachments=$attachmentCount")
            status = "等待 ${provider.name} 回复…"
            var last = ""
            var stableCount = 0
            var sawGenerating = false
            var idleAfterGenerating = 0
            var staleLogged = false

            for (poll in 0 until 180) {
                delay(700)
                val response = runCatching { runtime.lastResponse(currentSession, provider) }
                    .onFailure { DiagnosticLogger.w("CHAT", "response_poll_error provider=${provider.id} poll=$poll type=${it.javaClass.simpleName}") }
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
                        "stale_response_ignored provider=${provider.id} poll=$poll chars=${response.length} baselineChars=${baselineResponse.length}"
                    )
                }

                if (poll == 0 || poll == 4 || poll == 10 || poll == 20) {
                    DiagnosticLogger.d(
                        "CHAT",
                        "response_poll provider=${provider.id} poll=$poll responseChars=${response.length} generating=$generating fresh=$freshResponse baselineMatch=${response.isNotBlank() && response == baselineResponse}"
                    )
                    if (response.isBlank() || !freshResponse) {
                        val probe = runCatching { runtime.probeSummary(currentSession, provider) }.getOrDefault("")
                        if (probe.isNotBlank()) {
                            DiagnosticLogger.d(
                                "CHAT",
                                "dom_probe provider=${provider.id} poll=$poll summary=${DiagnosticLogger.scrub(probe).take(1400)}"
                            )
                        }
                    }
                }

                if (freshResponse) {
                    if (response == last) stableCount++ else stableCount = 0
                    last = response
                    if (!generating && stableCount >= 2) {
                        messages += ChatMessage(role = MessageRole.ASSISTANT, text = response)
                        persist(currentSession)
                        isGenerating = false
                        status = null
                        DiagnosticLogger.i(
                            "CHAT",
                            "response_completed provider=${provider.id} responseChars=${response.length} polls=${poll + 1} baselineChars=${baselineResponse.length}"
                        )
                        return@launch
                    }
                } else {
                    stableCount = 0
                }

                if (sawGenerating && idleAfterGenerating >= 8 && last.isBlank() && response.isBlank()) {
                    val probe = runCatching { runtime.probeSummary(currentSession, provider) }.getOrDefault("")
                    DiagnosticLogger.w(
                        "CHAT",
                        "response_extract_failed provider=${provider.id} poll=$poll probe=${DiagnosticLogger.scrub(probe).take(1400)}"
                    )
                    isGenerating = false
                    status = "${provider.name} 已完成回复，但 AIHub 没有识别到回答。请导出诊断日志。"
                    return@launch
                }
            }

            if (last.isNotBlank()) {
                messages += ChatMessage(role = MessageRole.ASSISTANT, text = last)
                persist(currentSession)
            }
            isGenerating = false
            status = if (last.isBlank()) "没有读取到新的回复，可打开网页检查当前状态。" else null
            DiagnosticLogger.w(
                "CHAT",
                "response_timeout provider=${provider.id} lastResponseChars=${last.length} baselineChars=${baselineResponse.length}"
            )
        }
    }

    fun stop(runtime: WebRuntime) {
        viewModelScope.launch {
            DiagnosticLogger.i("CHAT", "stop_requested provider=$selectedProviderId")
            runtime.stop(session, selectedProvider)
            isGenerating = false
            status = "已请求停止生成"
        }
    }

    fun newChat(runtime: WebRuntime) {
        if (isGenerating) return
        val current = session
        DiagnosticLogger.i("CHAT", "new_chat provider=$selectedProviderId account=${safeAccountId(current.accountId)}")
        messages.clear()
        conversationStore.clear(current)
        viewModelScope.launch {
            runtime.newChat(current, selectedProvider)
            status = null
        }
    }

    private fun reloadConversation() {
        messages.clear()
        messages.addAll(conversationStore.load(session))
        DiagnosticLogger.d("VM", "conversation_loaded provider=$selectedProviderId messages=${messages.size}")
    }

    private fun persist(target: SessionKey) = conversationStore.save(target, messages.toList())

    private fun safeAccountId(id: String): String = id.take(12)

    private fun defaultAccount(providerId: String): AccountProfile =
        accounts.firstOrNull { it.providerId == providerId }
            ?: AccountProfile("default_$providerId", providerId, "Default")

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AIHubViewModel(application) as T
    }
}
