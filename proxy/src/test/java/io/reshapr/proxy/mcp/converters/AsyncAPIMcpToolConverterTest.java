/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.proxy.mcp.converters;

import io.reshapr.proxy.events.EventHubFixture;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.events.AsyncAPIChannelCatalog;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test case for {@link AsyncAPIMcpToolConverter}: the AsyncAPI receive operations are exposed as MCP tools that publish a message to the channel.
 * @author laurent
 */
class AsyncAPIMcpToolConverterTest {

   static final String SPEC = """
         asyncapi: 3.0.0
         info: {title: Orders, version: 1.0.0}
         channels:
           orderCreated:
             address: orders.created
             messages:
               OrderCreated: {payload: {type: object}}
           createOrderCommand:
             address: orders.commands.create
             messages:
               CreateOrder: {$ref: '#/components/messages/CreateOrder'}
           noteCommand:
             address: orders.notes
             messages:
               Note: {payload: {type: string}}
         operations:
           onOrderCreated:
             action: send
             channel: {$ref: '#/channels/orderCreated'}
           createOrder:
             action: receive
             summary: Create an order
             channel: {$ref: '#/channels/createOrderCommand'}
           addNote:
             action: receive
             channel: {$ref: '#/channels/noteCommand'}
             description: Add a note to an order
         components:
           messages:
             CreateOrder:
               payload: {type: object, required: [customerId], properties: {customerId: {type: string}}}
         """;

   final WorkCache workCache = new WorkCache(100);
   final EventHubFixture f = new EventHubFixture(new AsyncAPIChannelCatalog(workCache));
   final ExpositionEntry exposition = new ExpositionEntry("expo", "orders",
         new ServiceEntry("svc", "org", "Orders", "1.0.0", "ASYNC", List.of(
               new OperationEntry("onOrderCreated", "SEND", "orders.created", "OrderCreated", "OrderCreated"),
               new OperationEntry("createOrder", "RECEIVE", "orders.commands.create", "CreateOrder", "CreateOrder"),
               new OperationEntry("addNote", "RECEIVE", "orders.notes", "Note", "Note"))),
         f.exposition.configuration(),
         new ArtifactEntry("art", "orders.yaml", "orders.yaml", ArtifactEntryType.ASYNC_API_SPEC, true, SPEC), List.of());
   final AsyncAPIMcpToolConverter converter = new AsyncAPIMcpToolConverter(exposition, workCache, f.mapper, f.hub);

   private OperationEntry op(String name) {
      return exposition.service().operations().stream().filter(o -> o.name().equals(name)).findFirst().orElseThrow();
   }

   @Test
   void testOnlyReceiveOperationsAreExposedAsTools() {
      List<OperationEntry> tools = converter.getAvailableOperations(exposition.service());
      assertEquals(List.of("createOrder", "addNote"), tools.stream().map(OperationEntry::name).toList());
   }

   @Test
   void testGetToolDescription() {
      // Description comes from operation if present.
      assertEquals("Publish an event on channel 'orders.notes': Add a note to an order", converter.getToolDescription(op("addNote")));

      // Description comes from channels if missing.
      assertEquals("Publish an event on channel 'orders.commands.create': Create an order",
            converter.getToolDescription(op("createOrder")));

      assertEquals("Publish an event on channel 'orders.created'", converter.getToolDescription(op("onOrderCreated")));
   }

   @Test
   void testGetInputSchema() {
      McpSchema.JsonSchema schema = converter.getInputSchema(op("createOrder"));
      assertEquals("object", schema.type());
      assertEquals(List.of("customerId"), schema.required());
      assertTrue(schema.properties().containsKey("customerId"));
   }

   @Test
   void nonObjectPayloadIsWrappedInAPayloadArgument() throws Exception {
      McpSchema.JsonSchema schema = converter.getInputSchema(op("addNote"));
      assertEquals(List.of("payload"), schema.required());
      assertTrue(schema.properties().containsKey("payload"));

      var response = converter.getCallResponse(op("addNote"), exposition.configuration(),
            new McpSchema.SimpleRequest("addNote", Map.of("payload", "hello")), Map.of());
      assertFalse(response.isFault());
      assertEquals(List.of("orders.notes=hello"), f.broker.published);
   }

   @Test
   void callPublishesArgumentsAsJsonAndReturnsAReceipt() throws Exception {
      var response = converter.getCallResponse(op("createOrder"), exposition.configuration(),
            new McpSchema.SimpleRequest("createOrder", Map.of("customerId", "c1")), Map.of());
      assertFalse(response.isFault());
      assertEquals(List.of("orders.commands.create={\"customerId\":\"c1\"}"), f.broker.published);
      JsonNode body = f.mapper.readTree(response.content());
      assertEquals("published", body.get("status").asText());
      assertEquals("orders.commands.create", body.get("destination").asText());
      assertFalse(body.get("messageId").asText().isBlank());
      assertFalse(body.has("offset") || body.has("partition") || body.has("topic"));
   }

   @Test
   void brokerFailureIsReportedAsAFault() throws Exception {
      f.broker.failPublish = true;
      var response = converter.getCallResponse(op("createOrder"), exposition.configuration(),
            new McpSchema.SimpleRequest("createOrder", Map.of("customerId", "c1")), Map.of());
      assertTrue(response.isFault());
      JsonNode body = f.mapper.readTree(response.content());
      assertEquals("failure", body.get("status").asText());
      assertEquals("orders.commands.create", body.get("destination").asText());
      assertTrue(body.get("error").asText().contains("broker down"));
      assertTrue(f.broker.published.isEmpty());
   }

   @Test
   void unknownChannelIsAFault() {
      var response = converter.getCallResponse(new OperationEntry("ghost", "RECEIVE", "x", "X", "X"),
            exposition.configuration(), new McpSchema.SimpleRequest("ghost", Map.of()), Map.of());
      assertTrue(response.isFault());
   }
}
