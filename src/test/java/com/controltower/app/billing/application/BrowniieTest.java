package com.controltower.app.billing.application;

import com.controltower.app.billing.domain.BillingEventRepository;
import com.controltower.app.billing.domain.StripeCustomer;
import com.controltower.app.billing.domain.StripeCustomerRepository;
import com.controltower.app.billing.domain.StripeWebhookEvent;
import com.controltower.app.licenses.application.LicenseService;
import com.controltower.app.licenses.domain.License;
import com.controltower.app.licenses.domain.LicenseRepository;
import com.stripe.model.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "BROWNIIE_RECORDER", matches = ".+")
class BrowniieTest {

    private License runSubscriptionUpdated(String apiVersion, String subscriptionJson) {
        StripeCustomerRepository customers = mock(StripeCustomerRepository.class);
        LicenseRepository licenses = mock(LicenseRepository.class);
        StripeCustomer customer = new StripeCustomer();
        customer.setClientId(UUID.randomUUID());
        customer.setTenantId(UUID.randomUUID());
        License license = new License();
        when(customers.findByStripeCustomerId("cus_1")).thenReturn(Optional.of(customer));
        when(licenses.findByClientIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(license));

        String eventJson = "{\"id\":\"evt_1\",\"object\":\"event\",\"type\":\"customer.subscription.updated\","
                + "\"api_version\":\"" + apiVersion + "\",\"data\":{\"object\":" + subscriptionJson + "}}";
        Event event = Event.GSON.fromJson(eventJson, Event.class);

        new BillingEventProcessor(customers, mock(BillingEventRepository.class),
                mock(LicenseService.class), licenses)
                .process(event, new StripeWebhookEvent());
        return license;
    }

    @Test
    void periodEndFromItemsOnCurrentApiVersion() {
        License license = runSubscriptionUpdated(com.stripe.Stripe.API_VERSION,
                "{\"id\":\"sub_1\",\"object\":\"subscription\",\"customer\":\"cus_1\","
                        + "\"items\":{\"object\":\"list\",\"data\":["
                        + "{\"id\":\"si_1\",\"object\":\"subscription_item\",\"current_period_end\":1700000000},"
                        + "{\"id\":\"si_2\",\"object\":\"subscription_item\",\"current_period_end\":1800000000}]}}");
        assertEquals(Instant.ofEpochSecond(1800000000L), license.getCurrentPeriodEnd());
    }

    @Test
    void periodEndFromSubscriptionOnOldApiVersion() {
        License license = runSubscriptionUpdated("2024-06-20",
                "{\"id\":\"sub_1\",\"object\":\"subscription\",\"customer\":\"cus_1\","
                        + "\"current_period_end\":1750000000,"
                        + "\"items\":{\"object\":\"list\",\"data\":["
                        + "{\"id\":\"si_1\",\"object\":\"subscription_item\"}]}}");
        assertEquals(Instant.ofEpochSecond(1750000000L), license.getCurrentPeriodEnd());
    }
}
