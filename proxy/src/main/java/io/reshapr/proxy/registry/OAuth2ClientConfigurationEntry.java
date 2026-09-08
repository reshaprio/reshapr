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
package io.reshapr.proxy.registry;

import org.infinispan.protostream.annotations.ProtoField;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a third-party OAuth2 configuration entry in the registry.
 * @param clientId The OAuth2 client ID
 * @param clientSecret The OAuth2 client secret if any
 * @param authorizationEndpoint The OAuth2 authorization endpoint URL
 * @param tokenEndpoint The OAuth2 token endpoint URL
 * @param scopes The OAuth2 scopes to request (optional, mainly for Client Credentials)
 * @author laurent
 */
public record OAuth2ClientConfigurationEntry(
      @ProtoField(1) String clientId,
      @ProtoField(2) String clientSecret,
      @ProtoField(3) String authorizationEndpoint,
      @ProtoField(4) String tokenEndpoint,
      @ProtoField(value = 5, collectionImplementation = ArrayList.class) List<String> scopes) {
}
