package com.vijay.mall.ops;

import com.vijay.mall.common.AppProps;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * One line at start-up saying how the server is set up (never a secret, only yes/no and names), so whoever reads the logs
 * after a deploy can see at a glance that the right profile, login channel and payment provider are active.
 */
@Component
public class StartupSummary implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

  private final Environment env;
  private final AppProps props;

  public StartupSummary(Environment env, AppProps props) {
    this.env = env;
    this.props = props;
  }

  static boolean set(String s) { return s != null && !s.isBlank(); }

  @Override
  public void run(ApplicationArguments args) {
    AppProps.Payment pay = props.payment();
    boolean razorpay = pay != null && set(pay.razorpayKeyId()) && set(pay.razorpayKeySecret());
    boolean webhook = pay != null && set(pay.razorpayWebhookSecret());
    String cors = env.getProperty("app.cors.allowed-origin-patterns", "");
    log.atInfo().setMessage("Server configuration: profiles={} otpChannel={} smsChannel={} onlinePayment={} razorpayKeys={} razorpayWebhookSecret={} "
            + "corsOrigins={} requireOpenShift={} cashierReturnDays={}")
        .addArgument(Arrays.toString(env.getActiveProfiles()))
        .addArgument(props.otp() == null ? "?" : props.otp().channel())
        .addArgument(env.getProperty("app.otp.sms-channel", "none"))
        .addArgument(pay == null ? "?" : pay.onlineProvider())
        .addArgument(razorpay ? "set" : "missing")
        .addArgument(webhook ? "set" : "missing")
        .addArgument(set(cors) ? cors.split(",").length : 0)
        .addArgument(env.getProperty("app.pos.require-open-shift", "false"))
        .addArgument(env.getProperty("app.pos.cashier-return-days", "7"))
        .addKeyValue("event", "startup-summary")
        .log();
  }
}
