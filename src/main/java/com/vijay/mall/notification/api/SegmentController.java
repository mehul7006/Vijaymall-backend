package com.vijay.mall.notification.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.notification.app.SegmentService;
import com.vijay.mall.notification.domain.ConditionType;
import com.vijay.mall.notification.domain.Rule;
import com.vijay.mall.notification.domain.Segment;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Saved customer segments under {@code /api/v1/admin/notifications/segments}. Admin only (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/notifications/segments")
public class SegmentController {
  /** A segment to save: name, optional description, {@code match} ({@code ALL} or {@code ANY}) and the conditions. */
  public record SegmentBody(String name, String description, String match, List<Rule> rules) {}

  /** What to preview: a saved segment ({@code segmentId}) or inline conditions ({@code match} and {@code rules}). */
  public record PreviewBody(UUID segmentId, String match, List<Rule> rules) {}

  private final SegmentService segments;

  /** Creates the controller (wired by Spring). */
  public SegmentController(SegmentService segments) { this.segments = segments; }

  /** The condition types, their parameters and examples, for the segment builder screen. */
  @GetMapping("/condition-types")
  public List<ConditionType.Entry> conditionTypes() { return segments.conditionTypes(); }

  /** Shows who the conditions would reach, before saving or sending. */
  @PostMapping("/preview")
  public SegmentService.PreviewResult preview(@RequestBody PreviewBody body) {
    boolean inline = body.match() != null || body.rules() != null;
    return segments.preview(body.segmentId(), inline ? new Segment(body.match(), body.rules()) : null);
  }

  /** Saves a segment (201). */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SegmentService.View create(@RequestBody SegmentBody b) {
    return segments.create(CurrentUser.id(), b.name(), b.description(), new Segment(b.match(), b.rules()));
  }

  /** Saved segments, newest first. */
  @GetMapping
  public Page<SegmentService.View> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return segments.list(page, size);
  }

  /** One saved segment; 404 when unknown or deleted. */
  @GetMapping("/{id}")
  public SegmentService.View get(@PathVariable UUID id) { return segments.get(id); }

  /** Replaces a saved segment. */
  @PutMapping("/{id}")
  public SegmentService.View update(@PathVariable UUID id, @RequestBody SegmentBody b) {
    return segments.update(CurrentUser.id(), id, b.name(), b.description(), new Segment(b.match(), b.rules()));
  }

  /** Deletes (archives) a saved segment (204). Broadcasts already sent keep their snapshot. */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) { segments.archive(CurrentUser.id(), id); }
}
