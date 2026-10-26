package com.ticketing;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 技术分层与业务归属分别校验，保留无环及公开交易协作边界。 */
class ArchitectureRulesTest {
    private static final Path SOURCES = Path.of("src/main/java/com/ticketing");
    private static final Set<String> MODULES = Set.of("application", "auth", "checkin", "common",
            "appconfig", "activity", "messaging", "trade", "inventory", "waitlist", "audit", "staff", "admission");
    private static final Set<String> LAYERS = Set.of("controller", "service", "mapper", "entity", "dto", "vo",
            "config", "enums", "security", "task", "messaging", "cache", "exception", "common");
    private static final Set<String> ORDER_COLLABORATORS = Set.of(
            "com.ticketing.service.OrderReservation", "com.ticketing.service.TradeResourceLocker");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package ([\\w.]+);");
    private static final Pattern REFERENCE = Pattern.compile("com\\.ticketing\\.[A-Za-z0-9_.]+");
    private static final Pattern NON_CODE = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*");

    @Test
    void everySourceHasAnExplicitBusinessOwnerAndMatchingLayerPackage() throws IOException {
        Map<String, String> modules = modules();
        Map<String, Path> sources = sources();
        assertThat(modules.keySet()).containsExactlyInAnyOrderElementsOf(sources.keySet());
        for (var source : sources.entrySet()) {
            Path relative = SOURCES.relativize(source.getValue());
            assertThat(modules.get(source.getKey())).as(source.getKey()).isIn(MODULES);
            if (source.getKey().equals("com.ticketing.TicketingApplication")) {
                assertThat(relative.getNameCount()).isEqualTo(1);
                continue;
            }
            assertThat(relative.getNameCount()).as(relative.toString()).isEqualTo(2);
            String layer = relative.getName(0).toString();
            assertThat(layer).as(source.getKey()).isIn(LAYERS);
            assertThat(source.getKey()).isEqualTo("com.ticketing." + layer + "."
                    + relative.getFileName().toString().replace(".java", ""));
            String name = source.getValue().getFileName().toString();
            if (name.endsWith("Controller.java")) assertThat(layer).isEqualTo("controller");
            if (name.endsWith("Mapper.java")) assertThat(layer).isEqualTo("mapper");
            if (name.endsWith("Service.java")) assertThat(layer).isEqualTo("service");
            if (name.endsWith("Properties.java") || name.endsWith("Config.java")) {
                assertThat(layer).isEqualTo("config");
            }
            if (name.endsWith("View.java")) assertThat(layer).isEqualTo("vo");
            if (name.endsWith("Job.java")) assertThat(layer).isEqualTo("task");
        }
    }

    @Test
    void businessDependenciesRemainAcyclicAndUsePublicOrderContracts() throws IOException {
        Map<String, String> modules = modules();
        Map<String, Set<String>> graph = new HashMap<>();
        for (var source : sources().entrySet()) {
            String module = modules.get(source.getKey());
            assertThat(module).as("新类必须登记业务归属 %s", source.getKey()).isNotNull();
            String code = NON_CODE.matcher(Files.readString(source.getValue())).replaceAll(" ");
            Set<String> references = new HashSet<>();
            var qualified = REFERENCE.matcher(code);
            while (qualified.find()) {
                String target = qualified.group();
                while (!modules.containsKey(target) && target.contains(".")) {
                    target = target.substring(0, target.lastIndexOf('.'));
                }
                if (modules.containsKey(target)) {
                    references.add(target);
                }
            }
            // 同层类可以省略 import；不能因目录合并而漏掉这些依赖。
            String packageName = source.getKey().substring(0, source.getKey().lastIndexOf('.'));
            for (String target : modules.keySet()) {
                int dot = target.lastIndexOf('.');
                if (target.substring(0, dot).equals(packageName)
                        && Pattern.compile("\\b" + target.substring(dot + 1) + "\\b").matcher(code).find()) {
                    references.add(target);
                }
            }
            for (String reference : references) {
                assertLayerDependency(source.getKey(), reference);
                String target = modules.get(reference);
                if (target.equals(module)) {
                    continue;
                }
                graph.computeIfAbsent(module, ignored -> new HashSet<>()).add(target);
                if (module.equals("trade")) {
                    assertThat(target).as(source.getKey()).isNotIn("waitlist", "checkin");
                }
                if (module.equals("inventory") || module.equals("messaging")) {
                    assertThat(target).as(source.getKey()).isNotEqualTo("waitlist");
                }
                if ((module.equals("waitlist") || module.equals("checkin")) && target.equals("trade")) {
                    assertThat(isPublicOrderContract(reference)).as("%s → %s", source.getKey(), reference).isTrue();
                }
            }
        }
        assertThat(graph.get("trade")).contains("inventory", "auth");
        for (String module : graph.keySet()) {
            visit(module, graph, new HashSet<>());
        }
    }

    private void assertLayerDependency(String source, String target) {
        String fromLayer = source.substring("com.ticketing.".length()).split("\\.")[0];
        String toLayer = target.substring("com.ticketing.".length()).split("\\.")[0];
        if (fromLayer.equals("controller")) {
            assertThat(toLayer).as("%s → %s", source, target).isNotIn("mapper", "entity");
        }
        if (fromLayer.equals("service")) {
            assertThat(toLayer).as("%s → %s", source, target).isNotEqualTo("controller");
        }
        if (Set.of("mapper", "entity", "dto", "vo", "enums").contains(fromLayer)) {
            assertThat(toLayer).as("%s → %s", source, target)
                    .isNotIn("controller", "service", "task", "messaging", "cache");
            if (toLayer.equals("security")) {
                // 登录响应沿用可信会话身份投影，其余数据类型不依赖安全实现。
                assertThat(source).isEqualTo("com.ticketing.vo.UserView");
                assertThat(target).isEqualTo("com.ticketing.security.AccountPrincipal");
            }
        }
    }

    private boolean isPublicOrderContract(String reference) {
        return Set.of("OrderLifecycle", "PurchaseLifecycle", "TradeContext", "OrderStatus", "OrderOperation", "OrderView", "PaymentView",
                "RefundResult", "RefundView", "RefundReason").contains(reference.substring(reference.lastIndexOf('.') + 1))
                || ORDER_COLLABORATORS.contains(reference);
    }

    private Map<String, String> modules() throws IOException {
        Properties properties = new Properties();
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
                "/architecture-modules.properties")), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Map<String, String> result = new HashMap<>();
        properties.forEach((name, module) -> result.put(name.toString(), module.toString()));
        return result;
    }

    private Map<String, Path> sources() throws IOException {
        Map<String, Path> result = new HashMap<>();
        try (var paths = Files.walk(SOURCES)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                var packageName = PACKAGE.matcher(Files.readString(path));
                assertThat(packageName.find()).as(path.toString()).isTrue();
                String name = packageName.group(1) + "." + path.getFileName().toString().replace(".java", "");
                assertThat(result.put(name, path)).as("重复类 %s", name).isNull();
            }
        }
        return result;
    }

    private void visit(String module, Map<String, Set<String>> graph, Set<String> path) {
        assertThat(path.add(module)).as("模块依赖出现环 %s → %s", path, module).isTrue();
        for (String target : graph.getOrDefault(module, Set.of())) {
            visit(target, graph, new HashSet<>(path));
        }
    }
}
