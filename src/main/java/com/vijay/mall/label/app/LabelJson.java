package com.vijay.mall.label.app;

import com.vijay.mall.label.domain.LabelElement;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.PrintJob;
import com.vijay.mall.label.domain.SheetLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON description of a print, for apps that draw the labels themselves (for example for a Bluetooth printer). Positions are
 * millimetres from the label's top-left corner; for a sheet template the {@code sheet.placements} give each label's corner on its
 * page.
 */
final class LabelJson {
  private LabelJson() { }

  static Map<String, Object> of(PrintJob job) {
    LabelTemplate t = job.template();
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> template = new LinkedHashMap<>();
    template.put("id", t.id());
    template.put("name", t.name());
    template.put("kind", t.kind());
    template.put("widthMm", t.widthMm());
    template.put("heightMm", t.heightMm());
    template.put("barcodeType", t.barcodeType());
    out.put("template", template);
    out.put("dpi", job.dpi());
    out.put("totalLabels", job.totalLabels());
    List<Map<String, Object>> items = new ArrayList<>();
    for (PrintJob.Entry e : job.entries()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("variantId", e.variantId());
      item.put("name", e.name());
      item.put("copies", e.copies());
      Map<String, Object> barcode = new LinkedHashMap<>();
      barcode.put("type", e.barcodeType());
      barcode.put("payload", e.barcode());
      item.put("barcode", barcode);
      item.put("elements", e.elements().stream().map(LabelJson::element).toList());
      items.add(item);
    }
    out.put("items", items);
    if (t.kind() == LabelTemplate.Kind.SHEET) out.put("sheet", sheet(job));
    return out;
  }

  private static Map<String, Object> element(LabelElement e) {
    Map<String, Object> m = new LinkedHashMap<>();
    switch (e) {
      case LabelElement.Text x -> {
        m.put("type", "text");
        box(m, x.xMm(), x.yMm(), x.widthMm(), x.heightMm());
        m.put("text", x.text());
        m.put("fontPt", x.fontPt());
        m.put("bold", x.bold());
        m.put("align", x.align());
        m.put("gujarati", x.gujarati());
      }
      case LabelElement.Barcode b -> {
        m.put("type", "barcode");
        box(m, b.xMm(), b.yMm(), b.widthMm(), b.heightMm());
        m.put("barcodeType", b.type());
        m.put("payload", b.payload());
        m.put("humanReadable", b.humanReadable());
      }
    }
    return m;
  }

  private static void box(Map<String, Object> m, double x, double y, double w, double h) {
    m.put("xMm", round(x));
    m.put("yMm", round(y));
    m.put("widthMm", round(w));
    m.put("heightMm", round(h));
  }

  private static double round(double v) { return Math.round(v * 100) / 100.0; }

  private static Map<String, Object> sheet(PrintJob job) {
    LabelTemplate t = job.template();
    Map<String, Object> sheet = new LinkedHashMap<>();
    sheet.put("pageWidthMm", LabelTemplate.A4_WIDTH_MM);
    sheet.put("pageHeightMm", LabelTemplate.A4_HEIGHT_MM);
    sheet.put("columns", t.columns());
    sheet.put("rows", t.rows());
    sheet.put("startPosition", job.startPosition());
    sheet.put("pages", SheetLayout.pages(t, job.startPosition(), job.totalLabels()));
    List<SheetLayout.Placement> places = SheetLayout.place(t, job.startPosition(), job.totalLabels());
    List<Map<String, Object>> list = new ArrayList<>(places.size());
    int index = 0;
    for (int item = 0; item < job.entries().size(); item++) {
      for (int c = 0; c < job.entries().get(item).copies(); c++) {
        SheetLayout.Placement p = places.get(index++);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", item);
        m.put("page", p.page());
        m.put("column", p.column());
        m.put("row", p.row());
        m.put("xMm", round(p.xMm()));
        m.put("yMm", round(p.yMm()));
        list.add(m);
      }
    }
    sheet.put("placements", list);
    return sheet;
  }
}
