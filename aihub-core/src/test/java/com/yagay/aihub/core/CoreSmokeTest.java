package com.yagay.aihub.core;

import com.yagay.aihub.core.command.AiCommand;
import com.yagay.aihub.core.command.AiCommandBus;
import com.yagay.aihub.core.provider.BuiltinProviders;
import com.yagay.aihub.core.provider.ProviderRegistry;
import com.yagay.aihub.core.runtime.RecordingSessionRuntime;
import java.util.Map;

public final class CoreSmokeTest {
    public static void main(String[] args) {
        ProviderRegistry providers = BuiltinProviders.createDefaultRegistry();
        AccountRegistry accounts = new AccountRegistry();
        accounts.register(new AiAccount("gpt_personal", "chatgpt", "Personal", "personal_gpt"));
        accounts.register(new AiAccount("gpt_work", "chatgpt", "Work", "work_gpt"));
        accounts.register(new AiAccount("claude_personal", "claude", "Personal", "personal_claude"));
        accounts.register(new AiAccount("claude_work", "claude", "Work", "work_claude"));
        accounts.register(new AiAccount("gemini_personal", "gemini", "Personal", "personal_gemini"));
        accounts.register(new AiAccount("grok_personal", "grok", "Personal", "personal_grok"));
        accounts.register(new AiAccount("deepseek_personal", "deepseek", "Personal", "personal_deepseek"));

        WorkspaceRegistry workspaces = new WorkspaceRegistry();
        workspaces.register(new AiWorkspace("personal", "Personal", Map.of(
                "chatgpt", "gpt_personal",
                "claude", "claude_personal",
                "gemini", "gemini_personal")));
        workspaces.register(new AiWorkspace("work", "Work", Map.of(
                "chatgpt", "gpt_work",
                "claude", "claude_work")));

        RecordingSessionRuntime runtime = new RecordingSessionRuntime();
        SessionManager sessions = new SessionManager(providers, accounts, workspaces, runtime);
        AiCommandBus bus = new AiCommandBus(sessions);

        assert bus.execute(AiCommand.switchTo("chatgpt", null, "personal")).success();
        assert sessions.currentKey().equals(new AiSessionKey("chatgpt", "gpt_personal"));
        assert bus.execute(AiCommand.send("hello")).success();
        assert bus.execute(AiCommand.switchTo("claude", null, "work")).success();
        assert sessions.currentKey().equals(new AiSessionKey("claude", "claude_work"));
        sessions.previousProvider();
        assert sessions.currentKey().providerId().equals("chatgpt");
        assert runtime.events().stream().anyMatch(e -> e.startsWith("send:chatgpt:gpt_personal:hello"));

        System.out.println("AIHub core smoke test passed");
        runtime.events().forEach(System.out::println);
    }
}
