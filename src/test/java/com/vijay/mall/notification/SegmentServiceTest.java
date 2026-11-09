package com.vijay.mall.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.notification.app.SegmentService;
import com.vijay.mall.notification.domain.Segment;
import com.vijay.mall.notification.infra.SegmentQueryRepository;
import com.vijay.mall.notification.infra.SegmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SegmentServiceTest {
  private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
  private final SegmentRepository store = mock(SegmentRepository.class);
  private final SegmentQueryRepository query = mock(SegmentQueryRepository.class);
  private final AuditService audit = mock(AuditService.class);
  private final SegmentService service = new SegmentService(store, query, audit, Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID admin = UUID.randomUUID();
  private final Segment good = SegmentValidatorTest.all("{\"type\":\"HAS_REVIEWED\"}");

  private static SegmentRepository.Row row(UUID id, Segment s) {
    return new SegmentRepository.Row(id, "Name", "", s.match(), s.rules(), UUID.randomUUID(), NOW, NOW);
  }

  private static void assertField(Runnable r, String field) {
    assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
      assertThat(e.details().toString()).contains(field);
    });
  }

  @Test
  void createTrimsTheNameChecksItAndAuditsIt() {
    when(store.nameTaken(eq("Big"), any())).thenReturn(false);
    when(store.find(any())).thenAnswer(i -> Optional.of(row(i.getArgument(0), good)));

    var v = service.create(admin, "  Big  ", null, good);

    assertThat(v.rules()).hasSize(1);
    verify(store).insert(any(), eq("Big"), eq(""), any(), eq(admin), eq(NOW));
    verify(audit).log(eq(admin), eq("SEGMENT_CREATE"), eq("notification_segment"), any(), eq("Big (1 conditions)"));
  }

  @Test
  void nameAndDescriptionLimits() {
    assertField(() -> service.create(admin, null, "", good), "name");
    assertField(() -> service.create(admin, "   ", "", good), "name");
    assertField(() -> service.create(admin, "n".repeat(61), "", good), "name");
    assertField(() -> service.create(admin, "ok", "d".repeat(301), good), "description");
    assertField(() -> service.create(admin, "ok", "", new Segment("ALL", List.of())), "rules");
    verify(store, never()).insert(any(), any(), any(), any(), any(), any());
  }

  @Test
  void aNameInUseIsRefusedOnCreateAndUpdate() {
    UUID id = UUID.randomUUID();
    when(store.nameTaken(eq("Dup"), any())).thenReturn(true);
    when(store.find(id)).thenReturn(Optional.of(row(id, good)));
    assertThatThrownBy(() -> service.create(admin, "Dup", "", good)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo("SEGMENT_NAME_TAKEN");
      assertThat(e.status().value()).isEqualTo(409);
    });
    assertThatThrownBy(() -> service.update(admin, id, "Dup", "", good)).isInstanceOf(ApiException.class);
  }

  @Test
  void updateReplacesAndAudits() {
    UUID id = UUID.randomUUID();
    when(store.find(id)).thenReturn(Optional.of(row(id, good)));
    service.update(admin, id, "New", "d", good);
    verify(store).update(eq(id), eq("New"), eq("d"), any(), eq(NOW));
    verify(audit).log(eq(admin), eq("SEGMENT_UPDATE"), eq("notification_segment"), eq(id), any());
  }

  @Test
  void updateOrDeleteOfAnUnknownSegmentIs404() {
    when(store.find(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(admin, UUID.randomUUID(), "n", "", good)).isInstanceOf(ApiException.class).hasMessageContaining("not found");
    assertThatThrownBy(() -> service.archive(admin, UUID.randomUUID())).isInstanceOf(ApiException.class);
  }

  @Test
  void archiveKeepsTheRowButHidesIt() {
    UUID id = UUID.randomUUID();
    when(store.find(id)).thenReturn(Optional.of(row(id, good)));
    service.archive(admin, id);
    verify(store).archive(id, NOW);
    verify(audit).log(admin, "SEGMENT_DELETE", "notification_segment", id, "Name");
  }

  @Test
  void listClampsThePage() {
    UUID id = UUID.randomUUID();
    when(store.page(0, 50)).thenReturn(new Page<>(List.of(row(id, good)), 0, 50, 1));
    var p = service.list(-1, 999);
    assertThat(p.items()).hasSize(1);
    assertThat(p.size()).isEqualTo(50);
  }

  @Test
  void aBroadcastUsesEitherASavedOrAnInlineSegmentButNotBothOrNeither() {
    UUID id = UUID.randomUUID();
    when(store.find(id)).thenReturn(Optional.of(row(id, good)));
    assertThat(service.resolve(id, null).segmentId()).isEqualTo(id);
    var inline = service.resolve(null, new Segment("any", good.rules()));
    assertThat(inline.segmentId()).isNull();
    assertThat(inline.segment().match()).isEqualTo("ANY");
    assertField(() -> service.resolve(null, null), "segment");
    assertField(() -> service.resolve(id, good), "segment");
    when(store.find(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.resolve(UUID.randomUUID(), null)).isInstanceOf(ApiException.class).hasMessageContaining("not found");
  }

  @Test
  void previewReportsTheCountSampleAndMasksMobileNumbers() {
    UUID u = UUID.randomUUID();
    when(query.preview(any(), eq(NOW), eq(10_000), eq(5))).thenReturn(new SegmentQueryRepository.Preview(
        new SegmentQueryRepository.Count(10_000, 4, false),
        List.of(new SegmentQueryRepository.Sample(u, "Asha", "9876543210"),
            new SegmentQueryRepository.Sample(u, null, null), new SegmentQueryRepository.Sample(u, "Short", "1234"))));

    var r = service.preview(null, good);

    assertThat(r.recipients()).isEqualTo(10_000);
    assertThat(r.totalExact()).isFalse();
    assertThat(r.withDevices()).isEqualTo(4);
    assertThat(r.sample()).extracting(SegmentService.SampleView::mobile).containsExactly("98XXXXXX10", null, "XXXX");
  }

  @Test
  void theCatalogIsTheValidatorsDefinitions() {
    assertThat(service.conditionTypes()).extracting(e -> e.type()).contains("ORDER_COUNT", "TOTAL_SPENT", "HAS_DEVICE");
  }
}
