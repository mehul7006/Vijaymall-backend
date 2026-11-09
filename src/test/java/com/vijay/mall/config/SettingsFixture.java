package com.vijay.mall.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A real RuntimeSettings on an in-memory settings table, so tests exercise the real rules. */
final class SettingsFixture {
  static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
  static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

  final List<SettingEntity> rows = new ArrayList<>();
  final AppSettingJpa jpa = mock(AppSettingJpa.class);
  final AuditService audit = mock(AuditService.class);
  final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  final MockEnvironment env = new MockEnvironment();
  final ConfigCrypto crypto;
  final RuntimeSettings settings;

  SettingsFixture() { this(KEY); }

  SettingsFixture(String encryptionKey) {
    crypto = new ConfigCrypto(encryptionKey);
    when(jpa.findAll()).thenAnswer(i -> new ArrayList<>(rows));
    when(jpa.findById(anyString())).thenAnswer(i -> rows.stream().filter(r -> r.name.equals(i.getArgument(0))).findFirst());
    when(jpa.save(any(SettingEntity.class))).thenAnswer(i -> {
      SettingEntity e = i.getArgument(0);
      rows.removeIf(r -> r.name.equals(e.name));
      rows.add(e);
      return e;
    });
    doAnswer(i -> { rows.remove(i.<SettingEntity>getArgument(0)); return null; }).when(jpa).delete(any(SettingEntity.class));
    settings = new RuntimeSettings(jpa, crypto, env, audit, Clock.fixed(NOW, ZoneOffset.UTC),
        new TransactionTemplate(mock(PlatformTransactionManager.class)), events, JsonMapper.builder().build());
  }

  Optional<SettingEntity> row(String name) { return rows.stream().filter(r -> r.name.equals(name)).findFirst(); }

  static SettingEntity entity(String name, String value, boolean secret) {
    SettingEntity e = new SettingEntity();
    e.name = name;
    e.value = value;
    e.secret = secret;
    e.updatedAt = NOW;
    return e;
  }
}
