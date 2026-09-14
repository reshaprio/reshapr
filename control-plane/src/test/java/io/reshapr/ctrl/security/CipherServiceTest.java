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
package io.reshapr.ctrl.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CipherService}: AES/GCM round-trip, key rotation and legacy AES/ECB
 * decryption fallback.
 * @author laurent
 */
class CipherServiceTest {

   private static final String KEY_V1 = "0123456789abcdef";
   private static final String KEY_V2 = "fedcba9876543210";

   // ---------------------------------------------------------------------
   //  Constructor validation
   // ---------------------------------------------------------------------

   @Test
   void testConstructorRejectsEmptyKeys() {
      assertThrows(IllegalArgumentException.class,
            () -> new CipherService(Map.of(), "v1", Optional.empty()));
   }

   @Test
   void testConstructorRejectsUnknownActiveKeyId() {
      assertThrows(IllegalArgumentException.class,
            () -> new CipherService(Map.of("v1", KEY_V1), "v2", Optional.empty()));
   }

   @Test
   void testConstructorRejectsInvalidKeySize() {
      assertThrows(IllegalArgumentException.class,
            () -> new CipherService(Map.of("v1", "too-short"), "v1", Optional.empty()));
   }

   // ---------------------------------------------------------------------
   //  Encrypt / decrypt round-trip
   // ---------------------------------------------------------------------

   @Test
   void testEncryptThenDecryptReturnsOriginalValue() {
      var service = new CipherService(Map.of("v1", KEY_V1), "v1", Optional.empty());

      String encrypted = service.encrypt("hello world");

      assertEquals("hello world", service.decrypt(encrypted));
   }

   @Test
   void testEncryptPrefixesCiphertextWithActiveKeyId() {
      var service = new CipherService(Map.of("v1", KEY_V1), "v1", Optional.empty());

      assertTrue(service.encrypt("some-secret").startsWith("v1:"));
   }

   @Test
   void testEncryptIsNonDeterministic() {
      // Unlike the legacy AES/ECB scheme, GCM uses a random IV so encrypting the same plaintext
      // twice must not yield the same ciphertext.
      var service = new CipherService(Map.of("v1", KEY_V1), "v1", Optional.empty());

      assertNotEquals(service.encrypt("same-value"), service.encrypt("same-value"));
   }

   @Test
   void testDecryptRejectsTamperedCiphertext() {
      // GCM is authenticated: flipping a byte in the payload must fail decryption rather than
      // silently returning corrupted plaintext.
      var service = new CipherService(Map.of("v1", KEY_V1), "v1", Optional.empty());
      String encrypted = service.encrypt("hello world");
      String tampered = encrypted.substring(0, encrypted.length() - 4) + "abcd";

      assertThrows(RuntimeException.class, () -> service.decrypt(tampered));
   }

   // ---------------------------------------------------------------------
   //  Key rotation
   // ---------------------------------------------------------------------

   @Test
   void testDecryptUsesKeyIdEmbeddedInCiphertextAfterRotation() {
      var serviceV1 = new CipherService(Map.of("v1", KEY_V1), "v1", Optional.empty());
      String encryptedWithV1 = serviceV1.encrypt("rotate-me");

      // After rotation, both keys are configured but "v2" is now active for new writes.
      var serviceV2 = new CipherService(Map.of("v1", KEY_V1, "v2", KEY_V2), "v2", Optional.empty());

      // A value encrypted before rotation still decrypts using the retired "v1" key...
      assertEquals("rotate-me", serviceV2.decrypt(encryptedWithV1));
      // ...while new values are encrypted with the new active key.
      assertTrue(serviceV2.encrypt("rotate-me").startsWith("v2:"));
   }

   @Test
   void testDecryptRejectsUnknownKeyId() {
      var service = new CipherService(Map.of("v1", KEY_V1), "v1", Optional.empty());

      assertThrows(IllegalStateException.class, () -> service.decrypt("unknown-kid:AAAA"));
   }

   // ---------------------------------------------------------------------
   //  Legacy AES/ECB fallback
   // ---------------------------------------------------------------------

   @Test
   void testDecryptFallsBackToLegacyEcbWhenNoKidPrefix() throws Exception {
      String legacyEncrypted = legacyEcbEncrypt(KEY_V1, "pre-existing-secret");
      var service = new CipherService(Map.of("v1", KEY_V1), "v1", Optional.of(KEY_V1));

      assertEquals("pre-existing-secret", service.decrypt(legacyEncrypted));
   }

   @Test
   void testDecryptLegacyValueFailsWithoutLegacyKeyConfigured() throws Exception {
      String legacyEncrypted = legacyEcbEncrypt(KEY_V1, "pre-existing-secret");
      var service = new CipherService(Map.of("v1", KEY_V1), "v1", Optional.empty());

      assertThrows(IllegalStateException.class, () -> service.decrypt(legacyEncrypted));
   }

   /** Reproduces the pre-migration {@code AES/ECB/PKCS5Padding} encryption scheme, for fallback testing. */
   private static String legacyEcbEncrypt(String key, String data) throws Exception {
      Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
      return Base64.getEncoder().encodeToString(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
   }
}
