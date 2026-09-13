package com.yagay.aihub.core;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkspaceRegistry {
    private final Map<String, AiWorkspace> byId = new LinkedHashMap<>();

    public synchronized void register(AiWorkspace workspace) {
        byId.put(workspace.id(), workspace);
    }

    public synchronized AiWorkspace require(String id) {
        AiWorkspace workspace = byId.get(id);
        if (workspace == null) throw new IllegalArgumentException("Unknown workspace: " + id);
        return workspace;
    }
}
