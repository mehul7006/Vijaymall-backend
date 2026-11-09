package com.vijay.mall.pos.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.pos.app.BarcodeGenerationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Store-internal barcodes for variants; open to admin and counter staff because they print the labels. */
@RestController
@RequestMapping("/api/v1/admin/variants")
public class AdminBarcodeController {
  private final BarcodeGenerationService generation;

  /** Creates the controller. */
  public AdminBarcodeController(BarcodeGenerationService generation) { this.generation = generation; }

  /**
   * Gives a variant a store-internal EAN-13 barcode and saves it with the variant's other barcodes, so scanning works at the
   * counter at once. A variant that already has a generated barcode gets the same one back ({@code created: false}). Answers 404
   * for an unknown variant and 400 for an item sold by weight.
   */
  @PostMapping("/{id}/barcode/generate")
  public BarcodeGenerationService.Generated generate(@PathVariable UUID id) { return generation.generate(id, CurrentUser.id()); }
}
