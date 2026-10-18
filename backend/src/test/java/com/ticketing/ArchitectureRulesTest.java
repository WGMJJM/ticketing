package com.ticketing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 无额外架构运行时依赖；守住业务包的编译依赖方向及公开协作边界。 */
class ArchitectureRulesTest {
    private static final Path SOURCES = Path.of("src/main/java/com/ticketing");
    private static final Pattern REFERENCE = Pattern.compile("com\\.ticketing\\.([a-z]+)\\.([A-Za-z0-9_.]+)");

    @Test
    void businessPackagesHaveNoReverseImplementationDependenciesOrCycles() throws IOException {
        Map<String, Set<String>> graph = new HashMap<>();
        try (var paths = Files.walk(SOURCES)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String module = SOURCES.relativize(path).getName(0).toString();
                if (module.endsWith(".java")) {
                    continue;
                }
                var references = REFERENCE.matcher(Files.readString(path));
                while (references.find()) {
                    String target = references.group(1);
                    if (target.equals(module)) {
                        continue;
                    }
                    graph.computeIfAbsent(module, ignored -> new HashSet<>()).add(target);
                    if (module.equals("order")) {
                        assertThat(target).as(path.toString()).isNotIn("waitlist", "checkin");
                    }
                    if (module.equals("ticket") || module.equals("messaging")) {
                        assertThat(target).as(path.toString()).isNotEqualTo("waitlist");
                    }
                    if ((module.equals("waitlist") || module.equals("checkin")) && target.equals("order")) {
                        String reference = references.group(2);
                        assertThat(Set.of("api.", "model.", "dto.").stream().anyMatch(reference::startsWith))
                                .as(path.toString()).isTrue();
                    }
                }
            }
        }
        assertThat(graph.get("order")).contains("ticket", "auth");
        for (String module : graph.keySet()) {
            visit(module, graph, new HashSet<>());
        }
    }

    private void visit(String module, Map<String, Set<String>> graph, Set<String> path) {
        assertThat(path.add(module)).as("模块依赖出现环 %s → %s", path, module).isTrue();
        for (String target : graph.getOrDefault(module, Set.of())) {
            visit(target, graph, new HashSet<>(path));
        }
    }
}
