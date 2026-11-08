package com.vijay.mall.config;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin's configuration screen: payment keys, push, SMS, e-mail, log levels, delivery, counter rules. Admin only (everything
 * under /admin). Secrets are never sent back: the screen only learns whether each is set. History of changes is in the audit log
 * (entity app_setting).
 */
@RestController
@RequestMapping("/api/v1/admin/config")
public class AdminConfigController {
  public record Group(String group, String label, List<RuntimeSettings.Row> items) {}

  public record View(List<Group> groups, List<String> issues) {}

  public record SaveBody(@NotEmpty List<RuntimeSettings.Change> changes) {}

  public record TestBody(Map<String, String> args) {}

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminConfigController.class);

  private final RuntimeSettings settings;
  private final BootstrapStatus bootstrap;
  private final List<ConnectionTester> testers;

  public AdminConfigController(RuntimeSettings settings, BootstrapStatus bootstrap, List<ConnectionTester> testers) {
    this.settings = settings;
    this.bootstrap = bootstrap;
    this.testers = testers;
  }

  private View view() {
    Map<String, Group> groups = new LinkedHashMap<>();
    for (RuntimeSettings.Row r : settings.view()) {
      groups.computeIfAbsent(r.group(), g -> new Group(g, r.groupLabel(), new ArrayList<>())).items().add(r);
    }
    return new View(List.copyOf(groups.values()), settings.issues());
  }

  @GetMapping
  public View get() { return view(); }

  /**
   * Saves the listed settings together or not at all. A blank value clears a setting (back to the server's own value or default); a
   * secret you do not list stays as it is. Takes effect straight away, no restart.
   */
  @PutMapping
  public View save(@Valid @RequestBody SaveBody body) {
    settings.save(body.changes(), CurrentUser.id());
    return view();
  }

  /** The settings the server needs before it can open the database; read-only, values never shown. */
  @GetMapping("/bootstrap")
  public List<BootstrapStatus.Item> bootstrap() { return bootstrap.items(); }

  /** Tries the saved credentials for real: target is razorpay, fcm, sms or email. */
  @PostMapping("/test/{target}")
  public ConnectionTester.Result test(@PathVariable String target, @RequestBody(required = false) TestBody body) {
    ConnectionTester t = testers.stream().filter(x -> x.target().equals(target)).findFirst().orElseThrow(() -> ApiException.notFound("Test"));
    ConnectionTester.Result result = t.test(body == null || body.args() == null ? Map.of() : body.args());
    log.atInfo().setMessage("Config test {} {}").addArgument(target).addArgument(result.ok() ? "passed" : "failed").addKeyValue("event", "config-test")
        .addKeyValue("target", target).addKeyValue("ok", result.ok()).addKeyValue("actor", String.valueOf(CurrentUser.id())).log();
    return result;
  }
}
