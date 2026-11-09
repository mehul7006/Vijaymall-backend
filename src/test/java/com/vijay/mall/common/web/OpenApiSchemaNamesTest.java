package com.vijay.mall.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

/**
 * Swagger names a schema after the simple class name, so two API models with the same simple name (for example two nested
 * {@code View} records) silently merge into one wrong schema. This test walks every controller method's parameter and return
 * types and fails when two different model classes end up with the same schema name.
 */
class OpenApiSchemaNamesTest {
  private static final String ROOT = "com.vijay.mall";

  @Test
  void everyApiModelHasItsOwnSchemaName() throws Exception {
    Map<String, Set<Class<?>>> byName = new TreeMap<>();
    Set<Class<?>> seen = new HashSet<>();
    for (Class<?> controller : controllers()) {
      for (Method m : controller.getDeclaredMethods()) {
        collect(ResolvableType.forMethodReturnType(m), byName, seen);
        for (int i = 0; i < m.getParameterCount(); i++) collect(ResolvableType.forMethodParameter(m, i), byName, seen);
      }
    }
    List<String> clashes = new ArrayList<>();
    byName.forEach((name, classes) -> {
      if (classes.size() > 1) clashes.add(name + " <- " + classes);
    });
    assertThat(clashes).as("schema names shared by different classes; give each a unique @Schema(name)").isEmpty();
  }

  /** The schema name Swagger gives a class: the {@code @Schema(name)} when set, otherwise the simple name. */
  static String schemaName(Class<?> type) {
    Schema schema = type.getAnnotation(Schema.class);
    return schema != null && !schema.name().isEmpty() ? schema.name() : type.getSimpleName();
  }

  private static List<Class<?>> controllers() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    List<Class<?>> found = new ArrayList<>();
    for (BeanDefinition def : scanner.findCandidateComponents(ROOT)) found.add(Class.forName(def.getBeanClassName()));
    return found;
  }

  private static void collect(ResolvableType type, Map<String, Set<Class<?>>> byName, Set<Class<?>> seen) {
    for (ResolvableType generic : type.getGenerics()) collect(generic, byName, seen);
    if (type.isArray()) collect(type.getComponentType(), byName, seen);
    Class<?> raw = type.resolve();
    if (raw == null || !raw.getName().startsWith(ROOT) || raw.isEnum() || !seen.add(raw)) return;
    if (raw.isRecord()) {
      byName.computeIfAbsent(schemaName(raw), k -> new HashSet<>()).add(raw);
      for (RecordComponent c : raw.getRecordComponents()) collect(ResolvableType.forType(c.getGenericType()), byName, seen);
    }
  }
}
