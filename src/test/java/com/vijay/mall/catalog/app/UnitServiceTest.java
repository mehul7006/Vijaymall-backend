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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnitServiceTest {
  private final UnitRepository repo = mock(UnitRepository.class);
  private final UnitService service = new UnitService(repo, mock(AuditService.class));
  private final UUID admin = UUID.randomUUID();

  private static UnitOfMeasure unit(String code, boolean active) {
    return new UnitOfMeasure(code, code, "", UnitOfMeasure.UNIT, 1, active);
  }

  @Test
  void canonicalUsesTheUnitsOwnSpellingAndDefaultsWhenBlank() {
    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    when(repo.findIgnoreCase("pc")).thenReturn(Optional.of(unit("pc", true)));
    assertThat(service.canonical(" KG".strip().toLowerCase())).isEqualTo("kg");
    assertThat(service.canonical(null)).isEqualTo("pc");
  }

  @Test
  void canonicalRefusesUnknownOrSwitchedOffUnits() {
    when(repo.findIgnoreCase("box")).thenReturn(Optional.empty());
    when(repo.findIgnoreCase("old")).thenReturn(Optional.of(unit("old", false)));
    assertThatThrownBy(() -> service.canonical("box")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.canonical("old")).isInstanceOf(ApiException.class);
  }

  @Test
  void aProductKeepsItsCurrentUnitEvenAfterItIsSwitchedOff() {
    when(repo.findIgnoreCase("old")).thenReturn(Optional.of(unit("old", false)));
    assertThat(service.canonicalOrKeep("old", "old")).isEqualTo("old");
    assertThat(service.canonicalOrKeep(" ", "old")).isEqualTo("old");
  }

  @Test
  void createRejectsADuplicateCodeIgnoringCase() {
    when(repo.findIgnoreCase("Dozen")).thenReturn(Optional.of(unit("dozen", true)));
    assertThatThrownBy(() -> service.create(new UnitOfMeasure("Dozen", "Dozen", "", "UNIT", 1, true), admin))
        .isInstanceOf(ApiException.class);
    verify(repo, never()).insert(any());
  }

  @Test
  void createValidatesNameAndSoldBy() {
    assertThatThrownBy(() -> service.create(new UnitOfMeasure("x", " ", "", "UNIT", 1, true), admin)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.create(new UnitOfMeasure("x", "X", "", "BOX", 1, true), admin)).isInstanceOf(ApiException.class);
  }

  @Test
  void changingTheCodeMovesEveryProductToTheNewCode() {
    when(repo.findIgnoreCase("ltr")).thenReturn(Optional.of(unit("ltr", true)));
    when(repo.findIgnoreCase("L")).thenReturn(Optional.empty());
    when(repo.update(anyString(), any())).thenReturn(Optional.of(unit("L", true)));
    service.update("ltr", new UnitOfMeasure("L", "Litre", "", "WEIGHT", 2, true), admin);
    verify(repo).rename("ltr", "L");
  }

  @Test
  void aUnitInUseCannotBeDeleted() {
    when(repo.findIgnoreCase("kg")).thenReturn(Optional.of(unit("kg", true)));
    when(repo.productsUsing("kg")).thenReturn(3L);
    assertThatThrownBy(() -> service.delete("kg", admin)).isInstanceOf(ApiException.class);
    when(repo.productsUsing("kg")).thenReturn(0L);
    service.delete("kg", admin);
    verify(repo).delete("kg");
  }

  @Test
  void listsComeFromTheRepository() {
    when(repo.allActive()).thenReturn(List.of(unit("pc", true)));
    assertThat(service.active()).hasSize(1);
  }
}
