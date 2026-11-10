package com.vijay.mall.common.web;

import com.vijay.mall.pos.domain.GstRates;
import java.math.BigDecimal;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/** Writes a GST rate as a plain JSON number without needless zeros: 18 (not 18.00), 5 (not 5.0), 0.25 stays 0.25. */
public class GstRateSerializer extends ValueSerializer<BigDecimal> {
  @Override
  public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
    gen.writeNumber(GstRates.normalize(value));
  }
}
