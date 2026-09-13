package com.yagay.aihub.core;

import com.yagay.aihub.core.command.AiCommand;
import com.yagay.aihub.core.command.AiCommandBus;
import com.yagay.aihub.core.command.AiCommandType;
import com.yagay.aihub.core.provider.BuiltinProviders;
import com.yagay.aihub.core.provider.ProviderRegistry;
import com.yagay.aihub.core.runtime.RecordingSessionRuntime;
import java.util.List;

public final class CoreSmokeTest {
    public static void main(String[] args) {
        ProviderRegistry providers = BuiltinProviders.createDefaultRegistry();
        RecordingSessionRuntime runtime = new RecordingSessionRuntime();
        SessionManager sessions = new SessionManager(providers, runtime);
        AiCommandBus bus = new AiCommandBus(sessions);

        check(bus.execute(AiCommand.switchTo("chatgpt")).success(), "activate ChatGPT");
        check(sessions.currentKey().equals(new AiSessionKey("chatgpt")), "provider key");
        check(bus.execute(AiCommand.send("hello")).success(), "send current provider");

        check(bus.execute(AiCommand.sendTo("claude", "provider target")).success(), "provider-targeted send");
        check(sessions.currentKey().equals(new AiSessionKey("claude")), "provider-targeted send switches provider");

        sessions.previousProvider();
        check(sessions.currentKey().equals(new AiSessionKey("chatgpt")), "previous provider");
        sessions.nextProvider();
        check(sessions.currentKey().equals(new AiSessionKey("claude")), "next provider");

        check(bus.execute(AiCommand.simple(AiCommandType.BACK)).success(), "back");
        check(bus.execute(AiCommand.simple(AiCommandType.FORWARD)).success(), "forward");
        check(bus.execute(AiCommand.simple(AiCommandType.RELOAD)).success(), "reload");
        check(bus.execute(AiCommand.attach(List.of("content://example/a.pdf"))).success(), "attach");
        check(bus.execute(AiCommand.attachAndSend(
                List.of("content://example/b.png"), "describe this image")).success(), "attach and send");

        sessions.switchProvider("chatgpt");
        check(runtime.events().stream().filter(e -> e.equals("open:chatgpt")).count() == 1,
                "retained provider session opens only once");
        check(runtime.events().stream().anyMatch(e -> e.equals("send:chatgpt:hello")), "send event");
        check(runtime.events().stream().anyMatch(e -> e.equals("send:claude:provider target")),
                "provider-targeted send event");
        check(runtime.events().stream().anyMatch(e -> e.equals("attach:claude:1")), "attach event");
        check(runtime.events().stream().anyMatch(e -> e.equals(
                "attachAndSend:claude:1:describe this image")), "attach and send event");

        System.out.println("AIHub provider-only core smoke test passed");
        runtime.events().forEach(System.out::println);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
