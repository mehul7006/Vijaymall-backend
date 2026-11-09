package com.vijay.mall.catalog.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.UnitOfMeasure;
import com.vijay.mall.catalog.infra.UnitRepository;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnitServiceCoverageTest {
  private final UnitRepository repo = mock(UnitRepository.class);
  private final AuditService audit = mock(AuditService.class);
  private final LiveEvents live = mock(LiveEvents.class);
  private final UnitService service = new UnitService(repo, audit);
  private final UUID admin = UUID.randomUUID();

  @BeforeEach
  void wireLive() { service.setLive(live); }

  private static UnitOfMeasure unit(String code, boolean active) {
    return new UnitOfMeasure(code, code, "", UnitOfMeasure.UNIT, 1, active);
  }

  private static void assertApi(Throwable t, int status, String code) {
    assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status().value()).isEqualTo(status);
      assertThat(e.code()).isEqualTo(code);
    });
  }

  @Test
  void allListsEveryUnitAndActiveOnlyTheSwitchedOnOnes() {
    when(repo.all()).thenReturn(List.of(unit("a", true), unit("b", false)));
    when(repo.allActive()).thenReturn(List.of(unit("a", true)));
    assertThat(service.all()).hasSize(2);
    assertThat(service.active()).hasSize(1);
  }

  @Test
  void canonicalTreatsBlankLikeNullAndStripsTheCode() {
    when(repo.findIgnoreCase("pc")).thenReturn(Optional.of(unit("pc", true)));
    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    assertThat(service.canonical("   ")).isEqualTo("pc");
    assertThat(service.canonical("  kg ")).isEqualTo("kg");
  }

  @Test
  void canonicalOrKeepFallsBackToTheDefaultWhenThereIsNothingToKeep() {
    when(repo.findIgnoreCase("pc")).thenReturn(Optional.of(unit("pc", true)));
    assertThat(service.canonicalOrKeep(null, null)).isEqualTo("pc");
    assertThat(service.canonicalOrKeep("", " ")).isEqualTo("pc");
    assertThat(service.canonicalOrKeep("", "kg")).isEqualTo("kg");
  }

  @Test
  void canonicalOrKeepAcceptsActiveUnitsRefusesUnknownOrOffUnitsThatAreNotCurrent() {
    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    when(repo.findIgnoreCase("old")).thenReturn(Optional.of(unit("old", false)));
    when(repo.findIgnoreCase("box")).thenReturn(Optional.empty());
    assertThat(service.canonicalOrKeep(" kg ", "pc")).isEqualTo("kg");
    assertThatThrownBy(() -> service.canonicalOrKeep("old", "pc")).satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    assertThatThrownBy(() -> service.canonicalOrKeep("box", "pc")).satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
  }

  @Test
  void createStoresTheCleanedUnitAuditsAndNotifies() {
    when(repo.findIgnoreCase("box")).thenReturn(Optional.empty());
    when(repo.insert(any())).thenAnswer(i -> i.getArgument(0));

    var saved = service.create(new UnitOfMeasure(" box ", " Box ", null, " weight ", 3, true), admin);

    assertThat(saved).isEqualTo(new UnitOfMeasure("box", "Box", "", "WEIGHT", 3, true));
    verify(repo).insert(saved);
    verify(audit).log(admin, "CREATE", "unit", "box", "box / Box");
    verify(live).area(CatalogChanged.UNITS);
  }

  @Test
  void createDefaultsSoldByToUnitWhenBlankOrMissing() {
    when(repo.findIgnoreCase(anyString())).thenReturn(Optional.empty());
    when(repo.insert(any())).thenAnswer(i -> i.getArgument(0));
    assertThat(service.create(new UnitOfMeasure("a", "A", "x", null, 1, true), admin).soldBy()).isEqualTo("UNIT");
    assertThat(service.create(new UnitOfMeasure("b", "B", "x", "  ", 1, true), admin).soldBy()).isEqualTo("UNIT");
  }

  @Test
  void createValidatesCodeNameSoldByLengthAndDuplicates() {
    assertThatThrownBy(() -> service.create(new UnitOfMeasure(null, "N", "", "UNIT", 1, true), admin))
        .satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    assertThatThrownBy(() -> service.create(new UnitOfMeasure("  ", "N", "", "UNIT", 1, true), admin))
        .satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    assertThatThrownBy(() -> service.create(new UnitOfMeasure("a".repeat(21), "N", "", "UNIT", 1, true), admin))
        .satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    assertThatThrownBy(() -> service.create(new UnitOfMeasure("a", null, "", "UNIT", 1, true), admin))
        .satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    assertThatThrownBy(() -> service.create(new UnitOfMeasure("a", "  ", "", "UNIT", 1, true), admin))
        .satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    assertThatThrownBy(() -> service.create(new UnitOfMeasure("a", "A", "", "BOGUS", 1, true), admin))
        .satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    when(repo.findIgnoreCase("KG")).thenReturn(Optional.of(unit("kg", true)));
    assertThatThrownBy(() -> service.create(new UnitOfMeasure("KG", "Kg", "", "UNIT", 1, true), admin))
        .satisfies(t -> assertApi(t, 409, "UNIT_EXISTS"));
    verify(repo, never()).insert(any());
  }

  @Test
  void updateOfAnUnknownUnitIsNotFound() {
    when(repo.findIgnoreCase("zz")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update("zz", unit("zz", true), admin)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
  }

  @Test
  void updateKeepsTheCurrentCodeWhenTheInputCodeIsBlankOrNull() {
    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    when(repo.update(anyString(), any())).thenAnswer(i -> Optional.of(i.getArgument(1)));

    var a = service.update("kg", new UnitOfMeasure(null, "Kilo", "", "WEIGHT", 2, true), admin);
    var b = service.update("kg", new UnitOfMeasure(" ", "Kilo", "", "WEIGHT", 2, true), admin);

    assertThat(a.code()).isEqualTo("kg");
    assertThat(b.code()).isEqualTo("kg");
    verify(repo, never()).rename(anyString(), anyString());
    verify(live, never()).catalog();
  }

  @Test
  void theLastActiveUnitInUseCannotBeSwitchedOff() {
    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    when(repo.productsUsing("kg")).thenReturn(3L);
    when(repo.allActive()).thenReturn(List.of(unit("kg", true)));
    assertThatThrownBy(() -> service.update("kg", unit("kg", false), admin)).satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    verify(repo, never()).update(anyString(), any());
  }

  @Test
  void aUnitCanBeSwitchedOffWhenUnusedOrWhenAnotherActiveUnitExists() {
    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    when(repo.update(anyString(), any())).thenAnswer(i -> Optional.of(i.getArgument(1)));
    when(repo.productsUsing("kg")).thenReturn(0L);
    assertThat(service.update("kg", unit("kg", false), admin).active()).isFalse();

    when(repo.productsUsing("kg")).thenReturn(4L);
    when(repo.allActive()).thenReturn(List.of(unit("kg", true), unit("pc", true)));
    assertThat(service.update("kg", unit("kg", false), admin).active()).isFalse();
  }

  @Test
  void renamingMovesProductsAuditsAndRefreshesTheCatalog() {
    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    when(repo.findIgnoreCase("KG")).thenReturn(Optional.of(unit("kg", true))); // only itself, not a clash
    when(repo.update(anyString(), any())).thenAnswer(i -> Optional.of(i.getArgument(1)));

    var saved = service.update("kg", unit("KG", true), admin);

    assertThat(saved.code()).isEqualTo("KG");
    verify(repo).rename("kg", "KG");
    verify(audit).log(admin, "UPDATE", "unit", "KG", "kg -> KG");
    verify(live).area(CatalogChanged.UNITS);
    verify(live).catalog();
  }

  @Test
  void renamingToAnotherUnitsCodeOrAnOverlongCodeIsRefused() {
    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    when(repo.findIgnoreCase("pc")).thenReturn(Optional.of(unit("pc", true)));
    assertThatThrownBy(() -> service.update("kg", unit("pc", true), admin)).satisfies(t -> assertApi(t, 409, "UNIT_EXISTS"));
    assertThatThrownBy(() -> service.update("kg", unit("x".repeat(21), true), admin))
        .satisfies(t -> assertApi(t, 400, "VALIDATION_FAILED"));
    verify(repo, never()).rename(anyString(), anyString());
  }

  @Test
  void renamingToAFreeCodeWorksAndAUnitThatVanishesMidUpdateIsNotFound() {
    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    when(repo.findIgnoreCase("kilo")).thenReturn(Optional.empty());
    when(repo.update(anyString(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update("kg", unit("kilo", true), admin)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));
    verify(repo).rename("kg", "kilo");
  }

  @Test
  void updateWorksWithoutALiveListener() {
    var quiet = new UnitService(repo, audit);
    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    when(repo.findIgnoreCase("kilo")).thenReturn(Optional.empty());
    when(repo.update(anyString(), any())).thenAnswer(i -> Optional.of(i.getArgument(1)));
    assertThat(quiet.update("kg", unit("kilo", true), admin).code()).isEqualTo("kilo");
    verify(live, never()).catalog();
  }

  @Test
  void deleteRefusesUnknownAndUsedUnitsAndRemovesUnusedOnes() {
    when(repo.findIgnoreCase("zz")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete("zz", admin)).satisfies(t -> assertApi(t, 404, "NOT_FOUND"));

    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    when(repo.productsUsing("kg")).thenReturn(2L);
    assertThatThrownBy(() -> service.delete("kg", admin)).satisfies(t -> {
      assertApi(t, 409, "UNIT_IN_USE");
      assertThat(((ApiException) t).details()).containsEntry("products", 2L);
    });
    verify(repo, never()).delete(anyString());

    when(repo.productsUsing("kg")).thenReturn(0L);
    service.delete("kg", admin);
    verify(repo).delete("kg");
    verify(audit).log(admin, "DELETE", "unit", "kg", "kg");
    verify(live).area(CatalogChanged.UNITS);
  }

  @Test
  void anAlreadySwitchedOffUnitCanBeSavedAsSwitchedOffAgain() {
    when(repo.findIgnoreCase("old")).thenReturn(Optional.of(unit("old", false)));
    when(repo.update(anyString(), any())).thenAnswer(i -> Optional.of(i.getArgument(1)));
    assertThat(service.update("old", unit("old", false), admin).active()).isFalse();
    verify(repo, never()).productsUsing(anyString());
  }
}
