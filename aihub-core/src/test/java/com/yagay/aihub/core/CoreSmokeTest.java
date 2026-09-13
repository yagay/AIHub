package com.yagay.aihub.core;

import com.yagay.aihub.core.command.AiCommand;
import com.yagay.aihub.core.command.AiCommandBus;
import com.yagay.aihub.core.command.AiCommandType;
import com.yagay.aihub.core.provider.BuiltinProviders;
import com.yagay.aihub.core.provider.ProviderRegistry;
import com.yagay.aihub.core.runtime.RecordingSessionRuntime;
import java.util.List;
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
        workspaces.register(new AiWorkspace("stale", "Stale", Map.of(
                "chatgpt", "missing_account",
                "claude", "gpt_personal")));

        RecordingSessionRuntime runtime = new RecordingSessionRuntime();
        SessionManager sessions = new SessionManager(providers, accounts, workspaces, runtime);
        AiCommandBus bus = new AiCommandBus(sessions);

        check(bus.execute(AiCommand.switchTo("chatgpt", null, "personal")).success(), "activate personal GPT");
        check(sessions.currentKey().equals(new AiSessionKey("chatgpt", "gpt_personal")), "workspace account mapping");
        check("personal".equals(sessions.activeWorkspaceId()), "workspace retained");

        check(bus.execute(AiCommand.send("hello")).success(), "send");
        sessions.switchProvider("claude");
        check(sessions.currentKey().equals(new AiSessionKey("claude", "claude_personal")), "provider switch keeps workspace");

        sessions.switchWorkspace("work");
        check(sessions.currentKey().equals(new AiSessionKey("claude", "claude_work")), "workspace switch");
        sessions.previousProvider();
        check(sessions.currentKey().equals(new AiSessionKey("chatgpt", "gpt_work")), "previous provider keeps workspace");

        sessions.switchWorkspace("stale");
        check(sessions.currentKey().equals(new AiSessionKey("chatgpt", "gpt_work")), "stale workspace account falls back safely");
        sessions.switchProvider("claude");
        check(sessions.currentKey().equals(new AiSessionKey("claude", "claude_work")), "wrong-provider workspace account falls back safely");

        check(bus.execute(AiCommand.simple(AiCommandType.BACK)).success(), "back");
        check(bus.execute(AiCommand.simple(AiCommandType.FORWARD)).success(), "forward");
        check(bus.execute(AiCommand.simple(AiCommandType.RELOAD)).success(), "reload");
        check(bus.execute(AiCommand.attach(List.of("content://example/a.pdf"))).success(), "attach");
        check(bus.execute(AiCommand.attachAndSend(
                List.of("content://example/b.png"), "describe this image")).success(), "attach and send");

        AiAccount renamed = sessions.renameAccount("claude_work", "Office");
        check("Office".equals(renamed.label()), "rename account");
        sessions.removeAccount("claude_personal");
        check(!accounts.contains("claude_personal"), "remove non-current account");
        check("claude_work".equals(workspaces.require("personal").accountFor("claude")),
                "workspace remapped after account removal");
        boolean rejectedLastAccountRemoval = false;
        try {
            sessions.removeAccount("claude_work");
        } catch (IllegalStateException expected) {
            rejectedLastAccountRemoval = true;
        }
        check(rejectedLastAccountRemoval, "last provider account cannot be removed");

        check(runtime.events().stream().anyMatch(e -> e.startsWith("send:chatgpt:gpt_personal:hello")), "send event");
        check(runtime.events().stream().anyMatch(e -> e.startsWith("back:claude:claude_work")), "back event");
        check(runtime.events().stream().anyMatch(e -> e.startsWith("forward:claude:claude_work")), "forward event");
        check(runtime.events().stream().anyMatch(e -> e.startsWith("reload:claude:claude_work")), "reload event");
        check(runtime.events().stream().anyMatch(e -> e.equals("attach:claude:claude_work:1")), "attach event");
        check(runtime.events().stream().anyMatch(e -> e.equals(
                "attachAndSend:claude:claude_work:1:describe this image")), "attach and send event");

        System.out.println("AIHub core smoke test passed");
        runtime.events().forEach(System.out::println);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
