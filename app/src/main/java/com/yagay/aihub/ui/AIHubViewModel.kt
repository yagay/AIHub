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

    fun send(prompt: String, runtime: WebRuntime) {
        val text = prompt.trim()
        if (text.isBlank() || isGenerating) return
        val currentSession = session
        val provider = selectedProvider
        DiagnosticLogger.i("CHAT", "send_started provider=${provider.id} account=${safeAccountId(currentSession.accountId)} promptChars=${text.length}")
        messages += ChatMessage(role = MessageRole.USER, text = text)
        persist(currentSession)

        viewModelScope.launch {
            isGenerating = true
            status = "正在连接 ${provider.name}…"
            val loggedIn = runCatching { runtime.isLoggedIn(currentSession, provider) }
                .onFailure { DiagnosticLogger.e("CHAT", "login_check_exception provider=${provider.id}", it) }
                .getOrDefault(false)
            if (!loggedIn) {
                DiagnosticLogger.w("CHAT", "send_blocked provider=${provider.id} reason=not_logged_in")
                isGenerating = false
                showWeb = true
                status = "请先在网页中登录 ${provider.name}，登录后返回聊天页再次发送。"
                return@launch
            }
            val sent = runCatching { runtime.send(currentSession, provider, text) }
                .onFailure { DiagnosticLogger.e("CHAT", "send_exception provider=${provider.id}", it) }
                .getOrDefault(false)
            if (!sent) {
                DiagnosticLogger.w("CHAT", "send_failed provider=${provider.id} reason=adapter_or_dom")
                isGenerating = false
                showWeb = true
                status = "网页结构可能已经变化，未找到输入框或发送按钮。"
                return@launch
            }

            DiagnosticLogger.i("CHAT", "send_injected provider=${provider.id}")
            status = "等待 ${provider.name} 回复…"
            var last = ""
            var stableCount = 0
            repeat(180) { poll ->
                delay(700)
                val response = runCatching { runtime.lastResponse(currentSession, provider) }
                    .onFailure { DiagnosticLogger.w("CHAT", "response_poll_error provider=${provider.id} poll=$poll type=${it.javaClass.simpleName}") }
                    .getOrDefault("")
                val generating = runCatching { runtime.isGenerating(currentSession, provider) }.getOrDefault(false)
                if (response.isNotBlank()) {
                    if (response == last) stableCount++ else stableCount = 0
                    last = response
                    if (!generating && stableCount >= 2) {
                        messages += ChatMessage(role = MessageRole.ASSISTANT, text = response)
                        persist(currentSession)
                        isGenerating = false
                        status = null
                        DiagnosticLogger.i("CHAT", "response_completed provider=${provider.id} responseChars=${response.length} polls=${poll + 1}")
                        return@launch
                    }
                }
            }
            if (last.isNotBlank()) {
                messages += ChatMessage(role = MessageRole.ASSISTANT, text = last)
                persist(currentSession)
            }
            isGenerating = false
            status = if (last.isBlank()) "没有读取到回复，可打开网页检查当前状态。" else null
            DiagnosticLogger.w("CHAT", "response_timeout provider=${provider.id} lastResponseChars=${last.length}")
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
