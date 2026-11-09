package com.vijay.mall.payment.paytm.settlement;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * One normalized Paytm settlement line, the same shape whether it came from the dashboard CSV or from a settlement API. The bank
 * module stores these in its settlement tables (see the guide for the glue); this module creates no tables. Amounts are in paise
 * (a refund or chargeback may be negative); text fields are null when the source does not have them.
 *
 * @param provider always {@code paytm}
 * @param source where it came from: {@code CSV}, {@code API_DETAIL}, {@code API_TRANSACTION}, {@code API_ORDER} or {@code API_SUMMARY}
 * @param transactionId Paytm's transaction id
 * @param orderId the order id (for a machine sale, the merchant transaction id)
 * @param transactionType {@code ACQUIRING} (a sale), {@code REFUND}, {@code CHARGEBACK} or the raw upper-case value
 * @param status the status text, upper-case
 * @param paymentMode {@code UPI}, {@code CARD}, {@code BALANCE}, {@code NETBANKING} or {@code OTHER}
 * @param requestType for example {@code UPI_QR_CODE} (a QR payment) or a machine card sale, when the API says
 * @param rrn retrieval reference number
 * @param maskedCard masked card number
 * @param cardNetwork card network
 * @param vpa payer UPI address, masked
 * @param posId machine id as Paytm reports it
 * @param extSerialNo machine serial number
 * @param amountPaise amount paid by the customer
 * @param settledAmountPaise amount paid out for this line
 * @param commissionPaise Paytm's fee
 * @param gstPaise GST on the fee
 * @param payoutId Paytm's payout (settlement batch) id
 * @param utr the bank transfer reference of the payout
 * @param transactionDate when the customer paid (India time)
 * @param settledDate the payout date
 * @param splitFlag the split-settlement flag, if any
 * @param bankName the bank the payout went to (summary API only)
 * @param bankAccount that account, masked (summary API only)
 * @param detail every source field that has no place above, so nothing is lost
 */
public record PaytmSettlementRecord(String provider, String source, String transactionId, String orderId, String transactionType, String status,
                                    String paymentMode, String requestType, String rrn, String maskedCard, String cardNetwork, String vpa,
                                    String posId, String extSerialNo, Long amountPaise, Long settledAmountPaise, Long commissionPaise,
                                    Long gstPaise, String payoutId, String utr, LocalDateTime transactionDate, LocalDate settledDate,
                                    String splitFlag, String bankName, String bankAccount, Map<String, String> detail) {}
