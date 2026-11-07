package com.controltower.app.billing.application;

import com.controltower.app.billing.domain.*;
import com.controltower.app.licenses.application.LicenseService;
import com.controltower.app.licenses.domain.LicenseRepository;
import com.stripe.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingEventProcessor {

    private final StripeCustomerRepository stripeCustomerRepository;
    private final BillingEventRepository   billingEventRepository;
    private final LicenseService           licenseService;
    private final LicenseRepository        licenseRepository;

    public void process(com.stripe.model.Event stripeEvent, StripeWebhookEvent webhookEvent) {
        switch (stripeEvent.getType()) {
            case "customer.subscription.created",
                 "customer.subscription.updated" -> handleSubscriptionUpdated(stripeEvent);
            case "customer.subscription.deleted"  -> handleSubscriptionDeleted(stripeEvent);
            case "invoice.paid"                   -> handleInvoicePaid(stripeEvent);
            case "invoice.payment_failed"         -> handlePaymentFailed(stripeEvent);
            default -> {
                log.debug("Unhandled Stripe event type: {}", stripeEvent.getType());
                webhookEvent.markSkipped();
            }
        }
    }

    /**
     * Deserializes the event object. Events sent on an older API version than the SDK pins
     * are deserialized leniently; the fields read here (id, customer, amount_paid, currency)
     * have the same shape across these versions.
     */
    private StripeObject dataObject(com.stripe.model.Event event) {
        EventDataObjectDeserializer d = event.getDataObjectDeserializer();
        java.util.Optional<StripeObject> o = d.getObject();
        if (o.isPresent()) return o.get();
        try {
            return d.deserializeUnsafe();
        } catch (Exception e) {
            log.warn("Could not deserialize Stripe event {}: {}", event.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Current period end: on subscription items in current API versions, on the
     * subscription itself (current_period_end) in events from older API versions.
     */
    private long periodEnd(com.stripe.model.Event event, Subscription sub) {
        if (sub.getItems() != null && sub.getItems().getData() != null) {
            java.util.Optional<Long> fromItems = sub.getItems().getData().stream()
                    .map(SubscriptionItem::getCurrentPeriodEnd)
                    .filter(java.util.Objects::nonNull)
                    .max(Long::compare);
            if (fromItems.isPresent()) return fromItems.get();
        }
        com.google.gson.JsonElement raw = com.google.gson.JsonParser
                .parseString(event.getDataObjectDeserializer().getRawJson())
                .getAsJsonObject().get("current_period_end");
        if (raw == null || raw.isJsonNull()) {
            throw new IllegalStateException("Subscription " + sub.getId() + " has no current period end");
        }
        return raw.getAsLong();
    }

    private void handleSubscriptionUpdated(com.stripe.model.Event event) {
        Subscription sub = (Subscription) dataObject(event);
        if (sub == null) return;

        stripeCustomerRepository.findByStripeCustomerId(sub.getCustomer())
                .ifPresent(customer -> {
                    customer.setStripeSubscriptionId(sub.getId());
                    stripeCustomerRepository.save(customer);

                    licenseRepository.findByClientIdAndDeletedAtIsNull(customer.getClientId())
                            .ifPresent(license -> {
                                long latestEnd = periodEnd(event, sub);
                                Instant periodEnd = Instant.ofEpochSecond(latestEnd);
                                license.activate(periodEnd);
                                licenseRepository.save(license);
                                record(customer.getTenantId(), customer.getClientId(),
                                        "SUBSCRIPTION_UPDATED", null, null, event.getId());
                            });
                });
    }

    private void handleSubscriptionDeleted(com.stripe.model.Event event) {
        Subscription sub = (Subscription) dataObject(event);
        if (sub == null) return;

        stripeCustomerRepository.findByStripeCustomerId(sub.getCustomer())
                .ifPresent(customer -> {
                    licenseRepository.findByClientIdAndDeletedAtIsNull(customer.getClientId())
                            .ifPresent(license -> {
                                license.cancel();
                                licenseRepository.save(license);
                                record(customer.getTenantId(), customer.getClientId(),
                                        "SUBSCRIPTION_CANCELLED", null, null, event.getId());
                            });
                });
    }

    private void handleInvoicePaid(com.stripe.model.Event event) {
        Invoice invoice = (Invoice) dataObject(event);
        if (invoice == null) return;

        stripeCustomerRepository.findByStripeCustomerId(invoice.getCustomer())
                .ifPresent(customer -> {
                    BigDecimal amount = BigDecimal.valueOf(invoice.getAmountPaid())
                            .movePointLeft(2); // Stripe uses cents
                    record(customer.getTenantId(), customer.getClientId(),
                            "INVOICE_PAID", amount, invoice.getCurrency(), event.getId());
                });
    }

    private void handlePaymentFailed(com.stripe.model.Event event) {
        Invoice invoice = (Invoice) dataObject(event);
        if (invoice == null) return;

        stripeCustomerRepository.findByStripeCustomerId(invoice.getCustomer())
                .ifPresent(customer -> {
                    // Enter grace period on first payment failure
                    licenseRepository.findByClientIdAndDeletedAtIsNull(customer.getClientId())
                            .ifPresent(license -> {
                                license.enterGracePeriod(Instant.now().plus(7, ChronoUnit.DAYS));
                                licenseRepository.save(license);
                            });
                    record(customer.getTenantId(), customer.getClientId(),
                            "PAYMENT_FAILED", null, null, event.getId());
                });
    }

    private void record(UUID tenantId, UUID clientId, String eventType,
                        BigDecimal amount, String currency, String stripeEventId) {
        BillingEvent be = new BillingEvent();
        be.setTenantId(tenantId);
        be.setClientId(clientId);
        be.setEventType(eventType);
        be.setAmount(amount);
        be.setCurrency(currency);
        be.setStripeEventId(stripeEventId);
        billingEventRepository.save(be);
    }
}
