package com.ticketing.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.messaging.InventoryChangeReason;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryEventTest {
    @Test
    void messagePayloadKeepsExistingFieldNamesAndValues() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var message = new InventoryEvent("existing-event", "10001", InventoryChangeReason.ORDER_CANCELLED);
        assertThat(json.readTree(json.writeValueAsBytes(message)))
                .isEqualTo(json.readTree("{\"eventId\":\"existing-event\",\"skuId\":\"10001\",\"reason\":\"ORDER_CANCELLED\"}"));
        assertThat(InventoryEvent.TYPE).isEqualTo("WAITLIST_DISPATCH_REQUESTED");
        assertThat(InventoryEvent.ROUTING_KEY).isEqualTo("inventory.available");
    }
}
