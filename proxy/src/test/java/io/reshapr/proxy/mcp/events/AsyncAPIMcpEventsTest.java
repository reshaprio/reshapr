package io.reshapr.proxy.mcp.events;

import io.reshapr.proxy.events.EventHubFixture;
import io.reshapr.proxy.events.EventSubscription;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.ReshaprResourcesMcpResourceBuilder;
import io.reshapr.proxy.mcp.WorkCache;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP mapping of AsyncAPI expositions: events resources (legacy path) and publishing tools.
 * @author laurent
 */
class AsyncAPIMcpEventsTest {

   final WorkCache workCache = new WorkCache(100);
   final EventHubFixture f = new EventHubFixture(new AsyncAPIChannelCatalog(workCache));

   @Test
   void resourceReadDeliversFilteredEventsOncePerSubscriber() throws Exception {
      EventSubscription sub = f.hub.subscribe(f.exposition, "alice", "1", null);
      f.broker.emit("orders.created", "{\"orderId\":\"a\",\"org\":\"other\"}");
      f.broker.emit("orders.created", "{\"orderId\":\"b\",\"org\":\"foobar\"}");
      f.broker.emit("orders.created", "{\"orderId\":\"c\",\"org\":\"foobar\"}");
      assertEquals(List.of("orderCreated"), sub.awaitUpdates(1, TimeUnit.SECONDS));

      McpSchema.ReadResourceRequest read = new McpSchema.ReadResourceRequest("events://orderCreated");
      AsyncAPIEventsMcpResourceBuilder builder = builder("1");
      JsonNode first = readBody(builder, read);
      assertEquals(2, first.get("events").size());
      assertEquals("b", first.get("events").get(0).get("payload").get("orderId").asText());
      assertEquals(0, first.get("lostEvents").asInt());
      assertEquals(0, readBody(builder, read).get("events").size());
   }

   @Test
   void overflowIsReportedAsLostEvents() throws Exception {
      f.hub.subscribe(f.exposition, "alice", "1", null);
      for (int i = 0; i < 15; i++) {
         f.broker.emit("orders.created", "{\"orderId\":\"" + i + "\",\"org\":\"foobar\"}");
      }
      JsonNode body = readBody(builder(null), new McpSchema.ReadResourceRequest("events://orderCreated"));
      assertEquals(10, body.get("events").size());
      assertEquals(5, body.get("lostEvents").asInt());
   }

   @Test
   void parsedDocumentIsInvalidatedWithItsArtifact() {
      AsyncAPIDocument first = AsyncAPIChannels.getDocument(f.exposition, workCache);
      assertSame(first, AsyncAPIChannels.getDocument(f.exposition, workCache));
      workCache.invalidateMajor(f.exposition.mainArtifact().id());
      assertNotSame(first, AsyncAPIChannels.getDocument(f.exposition, workCache));
   }

   @Test
   void documentKeepsProtocolBindingsRaw() {
      var document = AsyncAPIDocument.parse("""
            asyncapi: 3.0.0
            channels:
              orderCreated:
                address: orders.created
                bindings:
                  kafka: {topic: prod-orders-v2}
            operations:
              onOrderCreated:
                action: send
                channel: { $ref: '#/channels/orderCreated' }
            """);
      var channel = document.getChannelForOperation("onOrderCreated");
      assertEquals("orders.created", channel.address());
      assertEquals("prod-orders-v2", channel.bindings().get("kafka").get("topic").asText());
   }

   private AsyncAPIEventsMcpResourceBuilder builder(String subscriptionId) {
      return new AsyncAPIEventsMcpResourceBuilder(f.exposition,
            new ReshaprResourcesMcpResourceBuilder(f.exposition.service(), List.of(), workCache, f.mapper, null),
            workCache, f.mapper, f.hub, "alice", subscriptionId);
   }

   private JsonNode readBody(AsyncAPIEventsMcpResourceBuilder builder, McpSchema.ReadResourceRequest read) throws Exception {
      var contents = builder.readResource(read, f.exposition.configuration());
      return f.mapper.readTree(((McpSchema.TextResourceContents) contents.getFirst()).text());
   }
}
