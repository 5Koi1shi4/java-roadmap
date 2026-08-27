package com.example.files.application.access;

/** 单条查询返回的访问决策；hidden 决策不携带任何资源元数据。 */
public record AccessDecision(FileView view, boolean readable) {
    public AccessDecision {
        if (!readable && view != null) {
            throw new IllegalArgumentException("hidden access decision must not expose view");
        }
    }

    public static AccessDecision of(FileView view, boolean readable) {
        return readable ? new AccessDecision(view, true) : hidden();
    }

    public static AccessDecision hidden() { return new AccessDecision(null, false); }
}
