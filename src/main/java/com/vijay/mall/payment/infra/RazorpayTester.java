package com.vijay.mall.payment.infra;

import com.vijay.mall.config.ConnectionTester;
import java.util.Map;
import org.springframework.stereotype.Component;

/** "Test Razorpay" button of the config screen. */
@Component
public class RazorpayTester implements ConnectionTester {
  private final RazorpayGateway gateway;

  public RazorpayTester(RazorpayGateway gateway) { this.gateway = gateway; }

  @Override public String target() { return "razorpay"; }

  @Override public Result test(Map<String, String> args) { return gateway.checkKeys(); }
}
