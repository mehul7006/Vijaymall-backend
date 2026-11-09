package com.vijay.mall.common.web;

import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.ValueDeserializerModifier;
import tools.jackson.databind.deser.std.DelegatingDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Makes date-time request fields forgiving for the apps: besides a proper ISO-8601 instant with {@code Z} or an offset (unchanged),
 * an {@link Instant} may be written as a zone-less date-time ({@code 2026-11-09T10:00:00}) or a plain date ({@code 2026-11-09}),
 * both read as India time (Asia/Kolkata; a plain date is the start of that day). Responses are not affected: they stay UTC with {@code Z}.
 * The parsing itself lives in {@link RequestInstants}, shared with the query parameters. Spring Boot registers every {@link JacksonModule} bean on the shared JSON mapper.
 */
@Configuration
public class IndiaInstantModule {
  /** The module that wraps the standard {@link Instant} reader. */
  @Bean
  JacksonModule lenientInstantModule() {
    SimpleModule module = new SimpleModule("lenient-instant");
    module.setDeserializerModifier(new ValueDeserializerModifier() {
      @Override
      public ValueDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription.Supplier beanDesc,
                                                     ValueDeserializer<?> deserializer) {
        return beanDesc.getBeanClass() == Instant.class ? new Lenient(deserializer) : deserializer;
      }
    });
    return module;
  }

  /** Reads the two India-time forms itself and leaves everything else to the standard reader. */
  static final class Lenient extends DelegatingDeserializer {
    Lenient(ValueDeserializer<?> delegate) { super(delegate); }

    @Override
    protected ValueDeserializer<?> newDelegatingInstance(ValueDeserializer<?> newDelegatee) { return new Lenient(newDelegatee); }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) {
      if (p.hasToken(JsonToken.VALUE_STRING)) {
        Instant india = RequestInstants.parseIndia(p.getString());
        if (india != null) return india;
      }
      return super.deserialize(p, ctxt);
    }
  }
}
