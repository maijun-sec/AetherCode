package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.FilesystemPermission;

import java.util.List;
import java.util.Set;

/**
 * Specification for a subagent.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.subagents.SubAgent} TypedDict. The
 * record captures the (name, description, system prompt) triple the
 * main agent uses to decide when to delegate, plus optional
 * configuration for the tools, model, middleware, interrupt config,
 * skills, permissions, and structured response format.</p>
 */
public record SubAgent(
        String name,
        String description,
        String systemPrompt,
        List<SubAgentTool> tools,
        SubAgentModel model,
        List<Middleware> middleware,
        Set<String> interruptOn,
        List<String> skills,
        List<FilesystemPermission> permissions,
        SubAgentResponseFormat responseFormat) {

    public SubAgent {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (description == null) description = "";
        if (systemPrompt == null) systemPrompt = "";
        tools = tools == null ? List.of() : List.copyOf(tools);
        middleware = middleware == null ? List.of() : List.copyOf(middleware);
        interruptOn = interruptOn == null ? Set.of() : Set.copyOf(interruptOn);
        skills = skills == null ? List.of() : List.copyOf(skills);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    public static Builder builder(String name, String description, String systemPrompt) {
        return new Builder(name, description, systemPrompt);
    }

    public SubAgent withTools(List<SubAgentTool> t) {
        return new SubAgent(name, description, systemPrompt, t, model, middleware,
                interruptOn, skills, permissions, responseFormat);
    }
    public SubAgent withModel(SubAgentModel m) {
        return new SubAgent(name, description, systemPrompt, tools, m, middleware,
                interruptOn, skills, permissions, responseFormat);
    }
    public SubAgent withMiddleware(List<Middleware> m) {
        return new SubAgent(name, description, systemPrompt, tools, model, m,
                interruptOn, skills, permissions, responseFormat);
    }
    public SubAgent withInterruptOn(Set<String> i) {
        return new SubAgent(name, description, systemPrompt, tools, model, middleware,
                i, skills, permissions, responseFormat);
    }
    public SubAgent withSkills(List<String> s) {
        return new SubAgent(name, description, systemPrompt, tools, model, middleware,
                interruptOn, s, permissions, responseFormat);
    }
    public SubAgent withPermissions(List<FilesystemPermission> p) {
        return new SubAgent(name, description, systemPrompt, tools, model, middleware,
                interruptOn, skills, p, responseFormat);
    }
    public SubAgent withResponseFormat(SubAgentResponseFormat r) {
        return new SubAgent(name, description, systemPrompt, tools, model, middleware,
                interruptOn, skills, permissions, r);
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final String systemPrompt;
        private List<SubAgentTool> tools = List.of();
        private SubAgentModel model;
        private List<Middleware> middleware = List.of();
        private Set<String> interruptOn = Set.of();
        private List<String> skills = List.of();
        private List<FilesystemPermission> permissions = List.of();
        private SubAgentResponseFormat responseFormat;

        private Builder(String name, String description, String systemPrompt) {
            this.name = name;
            this.description = description;
            this.systemPrompt = systemPrompt;
        }
        public Builder tools(List<SubAgentTool> v) { this.tools = v; return this; }
        public Builder withTools(List<SubAgentTool> v) { this.tools = v; return this; }
        public Builder model(SubAgentModel v) { this.model = v; return this; }
        public Builder withModel(SubAgentModel v) { this.model = v; return this; }
        public Builder model(String spec) { this.model = SubAgentModel.fromSpec(spec); return this; }
        public Builder middleware(List<Middleware> v) { this.middleware = v; return this; }
        public Builder withMiddleware(List<Middleware> v) { this.middleware = v; return this; }
        public Builder interruptOn(Set<String> v) { this.interruptOn = v; return this; }
        public Builder withInterruptOn(Set<String> v) { this.interruptOn = v; return this; }
        public Builder skills(List<String> v) { this.skills = v; return this; }
        public Builder withSkills(List<String> v) { this.skills = v; return this; }
        public Builder permissions(List<FilesystemPermission> v) { this.permissions = v; return this; }
        public Builder withPermissions(List<FilesystemPermission> v) { this.permissions = v; return this; }
        public Builder responseFormat(SubAgentResponseFormat v) { this.responseFormat = v; return this; }
        public Builder withResponseFormat(SubAgentResponseFormat v) { this.responseFormat = v; return this; }
        public SubAgent build() {
            return new SubAgent(name, description, systemPrompt, tools, model, middleware,
                    interruptOn, skills, permissions, responseFormat);
        }
    }
}
