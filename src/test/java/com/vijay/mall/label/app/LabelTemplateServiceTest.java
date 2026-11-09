package com.vijay.mall.label.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.label.LabelTestData;
import com.vijay.mall.label.app.LabelTemplateService.Input;
import com.vijay.mall.label.domain.LabelFields;
import com.vijay.mall.label.domain.LabelFields.Alignment;
import com.vijay.mall.label.domain.LabelFields.NameLanguage;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import com.vijay.mall.label.domain.LabelTemplate.Kind;
import com.vijay.mall.label.infra.LabelTemplateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LabelTemplateServiceTest {
  private final UUID actor = UUID.randomUUID();
  private LabelTemplateRepository repo;
  private AuditService audit;
  private LabelTemplateService service;

  @BeforeEach
  void setUp() {
    repo = mock(LabelTemplateRepository.class);
    audit = mock(AuditService.class);
    service = new LabelTemplateService(repo, audit, Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC));
  }

  private static LabelFields fields() { return LabelTestData.fields(NameLanguage.EN); }

  private static Input thermal(String name, Double w, Double h, LabelFields f) {
    return new Input(name, Kind.THERMAL, w, h, null, null, null, null, null, null, null, f);
  }

  private static Input sheet(Integer cols, Integer rows, Double w, Double h, Double gx, Double gy, Double ml, Double mt) {
    return new Input("sheet", Kind.SHEET, w, h, cols, rows, gx, gy, ml, mt, BarcodeType.EAN13, fields());
  }

  private void assertInvalid(Input in, String field) {
    assertThatThrownBy(() -> service.create(in, actor)).isInstanceOf(ApiException.class).satisfies(e -> {
      assertThat(((ApiException) e).status().value()).isEqualTo(400);
      assertThat(((ApiException) e).details().toString()).contains(field);
    });
    verify(repo, never()).insert(any(), any());
  }

  private LabelTemplate created(Input in) {
    when(repo.find(any())).thenAnswer(inv -> Optional.of(LabelTestData.thermal().withIdentity(inv.getArgument(0), "created", false)));
    return service.create(in, actor);
  }

  @Test void create_thermalNormalisesTheGridAndDefaultsTheBarcodeType() {
    created(thermal("  My label ", 50.0, 25.0, fields()));
    var captor = org.mockito.ArgumentCaptor.forClass(LabelTemplate.class);
    verify(repo).insert(captor.capture(), any());
    var t = captor.getValue();
    assertThat(t.name()).isEqualTo("My label");
    assertThat(t.columns()).isEqualTo(1);
    assertThat(t.rows()).isEqualTo(1);
    assertThat(t.barcodeType()).isEqualTo(BarcodeType.EAN13);
    assertThat(t.builtIn()).isFalse();
    verify(audit).log(eq(actor), eq("CREATE"), eq("label_template"), eq(t.id()), eq("My label"));
  }

  @Test void create_aThermalTemplateIgnoresSheetValues() {
    created(new Input("t", Kind.THERMAL, 50.0, 40.0, 9, 99, 99.0, 99.0, 999.0, 999.0, BarcodeType.QR, fields()));
    var captor = org.mockito.ArgumentCaptor.forClass(LabelTemplate.class);
    verify(repo).insert(captor.capture(), any());
    assertThat(captor.getValue().columns()).isEqualTo(1);
    assertThat(captor.getValue().marginLeftMm()).isZero();
    assertThat(captor.getValue().barcodeType()).isEqualTo(BarcodeType.QR);
  }

  @Test void create_nameRules() {
    assertInvalid(thermal(null, 50.0, 25.0, fields()), "name");
    assertInvalid(thermal("  ", 50.0, 25.0, fields()), "name");
    assertInvalid(thermal("x".repeat(61), 50.0, 25.0, fields()), "name");
    when(repo.nameTaken(eq("Taken"), any())).thenReturn(true);
    assertThatThrownBy(() -> service.create(thermal("Taken", 50.0, 25.0, fields()), actor)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NAME_TAKEN");
  }

  @Test void create_kindAndFieldsRequired() {
    assertInvalid(new Input("x", null, 50.0, 25.0, null, null, null, null, null, null, null, fields()), "kind");
    assertInvalid(thermal("x", 50.0, 25.0, null), "fields");
  }

  @Test void create_sizesMustBe10To200Mm() {
    for (Double bad : new Double[] {null, 9.9, 200.1, Double.NaN, -5.0}) {
      assertInvalid(thermal("x", bad, 25.0, fields()), "widthMm");
      assertInvalid(thermal("x", 50.0, bad, fields()), "heightMm");
    }
    created(thermal("edge", 10.0, 200.0, new LabelFields(false, NameLanguage.NONE, false, false, false, false, false, false, false, 7, Alignment.LEFT)));
    created(thermal("edge2", 200.0, 10.0, new LabelFields(false, NameLanguage.NONE, false, false, false, false, false, false, false, 7, Alignment.LEFT)));
  }

  @Test void create_fontSizeAndFieldCombos() {
    assertInvalid(thermal("x", 50.0, 25.0, new LabelFields(false, NameLanguage.EN, true, true, true, false, false, false, false, 3, Alignment.LEFT)), "fontSize");
    assertInvalid(thermal("x", 50.0, 25.0, new LabelFields(false, NameLanguage.EN, true, true, true, false, false, false, false, 25, Alignment.LEFT)), "fontSize");
    assertInvalid(thermal("x", 50.0, 60.0, new LabelFields(false, NameLanguage.EN, true, false, false, false, false, false, true, 7, Alignment.LEFT)), "pricePerUnit");
  }

  @Test void create_fontSizeZeroMeansSeven() {
    created(thermal("x", 50.0, 40.0, new LabelFields(false, NameLanguage.EN, true, true, true, false, false, false, false, 0, Alignment.LEFT)));
    var captor = org.mockito.ArgumentCaptor.forClass(LabelTemplate.class);
    verify(repo).insert(captor.capture(), any());
    assertThat(captor.getValue().fields().fontSize()).isEqualTo(7);
  }

  @Test void create_tooManyFieldsForTheLabelHeightIsRefusedWithTheNeededHeight() {
    var all = new LabelFields(true, NameLanguage.BOTH, true, true, true, true, true, true, true, 10, Alignment.CENTER);
    assertThatThrownBy(() -> service.create(thermal("x", 38.0, 25.0, all), actor)).isInstanceOf(ApiException.class)
        .hasMessageContaining("mm of height").hasMessageContaining("38 x 25");
  }

  @Test void create_sheetGridMustFitA4() {
    assertInvalid(sheet(0, 8, 64.0, 34.0, 2.0, 2.0, 7.0, 5.5), "columns");
    assertInvalid(sheet(11, 8, 15.0, 34.0, 0.0, 0.0, 0.0, 0.0), "columns");
    assertInvalid(sheet(3, 0, 64.0, 34.0, 2.0, 2.0, 7.0, 5.5), "rows");
    assertInvalid(sheet(3, 31, 64.0, 10.0, 2.0, 2.0, 7.0, 5.5), "rows");
    assertInvalid(sheet(4, 8, 64.0, 34.0, 2.0, 2.0, 7.0, 5.5), "wider than an A4");
    assertInvalid(sheet(3, 9, 64.0, 34.0, 2.0, 2.0, 7.0, 5.5), "taller than an A4");
    assertInvalid(sheet(3, 8, 64.0, 34.0, 51.0, 2.0, 7.0, 5.5), "gapXMm");
    assertInvalid(sheet(3, 8, 64.0, 34.0, 2.0, -1.0, 7.0, 5.5), "gapYMm");
    assertInvalid(sheet(3, 8, 64.0, 34.0, 2.0, 2.0, 101.0, 5.5), "marginLeftMm");
    assertInvalid(sheet(3, 8, 64.0, 34.0, 2.0, 2.0, 7.0, Double.NaN), "marginTopMm");
  }

  @Test void create_aValidSheetKeepsItsGrid_andOmittedValuesDefault() {
    created(sheet(3, 8, 64.0, 34.0, 2.0, 2.0, 7.0, 5.5));
    created(new Input("one", Kind.SHEET, 100.0, 100.0, null, null, null, null, null, null, null, fields()));
    var captor = org.mockito.ArgumentCaptor.forClass(LabelTemplate.class);
    verify(repo, org.mockito.Mockito.times(2)).insert(captor.capture(), any());
    assertThat(captor.getAllValues().get(0).perPage()).isEqualTo(24);
    assertThat(captor.getAllValues().get(1).perPage()).isEqualTo(1);
  }

  @Test void qrNeedsEnoughHeight() {
    var in = new Input("q", Kind.THERMAL, 30.0, 25.0, null, null, null, null, null, null, BarcodeType.QR, new LabelFields(false, NameLanguage.EN, true, true, true, false, false, false, false, 8, Alignment.CENTER));
    assertInvalid(in, "fields");
  }

  @Test void update_changesACustomTemplateAndAudits() {
    var id = UUID.randomUUID();
    when(repo.find(id)).thenReturn(Optional.of(LabelTestData.thermal().withIdentity(id, "old", false)));
    service.update(id, thermal("New", 50.0, 30.0, fields()), actor);
    verify(repo).update(any(), any());
    verify(audit).log(eq(actor), eq("UPDATE"), eq("label_template"), eq(id), eq("New"));
  }

  @Test void update_builtInsAndUnknownIds_andNameClash() {
    var builtIn = LabelTestData.thermal();
    when(repo.find(builtIn.id())).thenReturn(Optional.of(builtIn));
    assertThatThrownBy(() -> service.update(builtIn.id(), thermal("x", 50.0, 25.0, fields()), actor)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("BUILT_IN_TEMPLATE");
    assertThatThrownBy(() -> service.delete(builtIn.id(), actor)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("BUILT_IN_TEMPLATE");
    assertThatThrownBy(() -> service.update(UUID.randomUUID(), thermal("x", 50.0, 25.0, fields()), actor)).isInstanceOf(ApiException.class).hasMessageContaining("not found");
    var id = UUID.randomUUID();
    when(repo.find(id)).thenReturn(Optional.of(LabelTestData.thermal().withIdentity(id, "old", false)));
    when(repo.nameTaken(eq("Clash"), eq(id))).thenReturn(true);
    assertThatThrownBy(() -> service.update(id, thermal("Clash", 50.0, 25.0, fields()), actor)).extracting("code").isEqualTo("NAME_TAKEN");
    verify(repo, never()).delete(any());
  }

  @Test void delete_removesACustomTemplate() {
    var id = UUID.randomUUID();
    when(repo.find(id)).thenReturn(Optional.of(LabelTestData.thermal().withIdentity(id, "mine", false)));
    service.delete(id, actor);
    verify(repo).delete(id);
    verify(audit).log(eq(actor), eq("DELETE"), eq("label_template"), eq(id), eq("mine"));
  }

  @Test void list_clampsPagingAndCountsAll() {
    when(repo.page(0, 50)).thenReturn(List.of(LabelTestData.thermal()));
    when(repo.count()).thenReturn(1L);
    var page = service.list(-1, 500);
    assertThat(page.items()).hasSize(1);
    assertThat(page.size()).isEqualTo(50);
    assertThat(page.total()).isEqualTo(1);
  }
}
