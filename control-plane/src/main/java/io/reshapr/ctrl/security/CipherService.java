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

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service for encrypting and decrypting sensitive data stored in the database.
 * <p>
 * New values are encrypted with {@code AES/GCM/NoPadding} (AEAD: confidentiality + integrity) using
 * one of several configured keys, each identified by a key id ({@code kid}). The resulting ciphertext
 * is self-describing: {@code <kid>:<base64(iv || ciphertext || tag)>}. Storing the {@code kid} alongside
 * the ciphertext allows several keys to coexist, so a new "active" key can be introduced for future
 * writes while older keys remain available to decrypt values they previously encrypted, enabling
 * key rotation without a big-bang re-encryption of the whole database.
 * <p>
 * For backward compatibility, values encrypted before this migration (plain Base64, no {@code kid:}
 * prefix, produced by the legacy {@code AES/ECB/PKCS5Padding} scheme) are still decrypted using a
 * configured legacy key.
 * @author laurent
 */
@Unremovable
@ApplicationScoped
public class CipherService {

   private static final String AEAD_ALGORITHM = "AES/GCM/NoPadding";
   private static final String LEGACY_ALGORITHM = "AES/ECB/PKCS5Padding";
   private static final int[] AES_KEYSIZES = { 16, 24, 32 };
   private static final int GCM_IV_LENGTH_BYTES = 12;
   private static final int GCM_TAG_LENGTH_BITS = 128;

   private final Map<String, SecretKeySpec> keysById;
   private final String activeKeyId;
   private final SecretKeySpec legacySecretKey;
   private final SecureRandom secureRandom = new SecureRandom();

   public CipherService(
         @ConfigProperty(name = "reshapr.encryption.keys") Map<String, String> encryptionKeys,
         @ConfigProperty(name = "reshapr.encryption.active-key-id") String activeKeyId,
         @ConfigProperty(name = "reshapr.encryption.legacy-key") Optional<String> legacyKey) {

      if (encryptionKeys == null || encryptionKeys.isEmpty()) {
         throw new IllegalArgumentException("At least one encryption key must be configured under reshapr.encryption.keys");
      }
      Map<String, SecretKeySpec> keys = new HashMap<>();
      encryptionKeys.forEach((kid, key) -> keys.put(kid, toSecretKey(kid, key)));
      this.keysById = Map.copyOf(keys);

      if (!this.keysById.containsKey(activeKeyId)) {
         throw new IllegalArgumentException("Active encryption key id '" + activeKeyId + "' not found in reshapr.encryption.keys");
      }
      this.activeKeyId = activeKeyId;

      this.legacySecretKey = legacyKey.filter(key -> !key.isBlank()).map(key -> toSecretKey("legacy", key)).orElse(null);
   }

   /**
    * Encrypts the given plaintext with the active key, using AES/GCM with a random IV.
    * @param data The plaintext to encrypt.
    * @return The self-describing ciphertext, in the form {@code <kid>:<base64(iv || ciphertext || tag)>}.
    */
   public String encrypt(String data) {
      try {
         byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
         secureRandom.nextBytes(iv);

         Cipher cipher = Cipher.getInstance(AEAD_ALGORITHM);
         cipher.init(Cipher.ENCRYPT_MODE, keysById.get(activeKeyId), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
         byte[] ciphertext = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));

         ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
         buffer.put(iv).put(ciphertext);
         return activeKeyId + ":" + Base64.getEncoder().encodeToString(buffer.array());
      } catch (GeneralSecurityException e) {
         throw new RuntimeException("Unable to encrypt data", e);
      }
   }

   /**
    * Decrypts a value produced by {@link #encrypt(String)}, or a legacy AES/ECB value predating
    * the key-rotation scheme.
    * @param encryptedData The ciphertext to decrypt.
    * @return The decrypted plaintext.
    */
   public String decrypt(String encryptedData) {
      int separatorIndex = encryptedData.indexOf(':');
      if (separatorIndex > 0) {
         String kid = encryptedData.substring(0, separatorIndex);
         SecretKeySpec key = keysById.get(kid);
         if (key == null) {
            throw new IllegalStateException("Unable to decrypt value: unknown encryption key id '" + kid + "'");
         }
         return decryptGcm(key, encryptedData.substring(separatorIndex + 1));
      }
      if (legacySecretKey == null) {
         throw new IllegalStateException("Unable to decrypt legacy value: no reshapr.encryption.legacy-key configured");
      }
      return decryptLegacy(encryptedData);
   }

   private String decryptGcm(SecretKeySpec key, String payload) {
      try {
         byte[] raw = Base64.getDecoder().decode(payload);
         byte[] iv = Arrays.copyOfRange(raw, 0, GCM_IV_LENGTH_BYTES);
         byte[] ciphertext = Arrays.copyOfRange(raw, GCM_IV_LENGTH_BYTES, raw.length);

         Cipher cipher = Cipher.getInstance(AEAD_ALGORITHM);
         cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
         return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
      } catch (GeneralSecurityException e) {
         throw new RuntimeException("Unable to decrypt data", e);
      }
   }

   private String decryptLegacy(String encryptedData) {
      try {
         Cipher cipher = Cipher.getInstance(LEGACY_ALGORITHM);
         cipher.init(Cipher.DECRYPT_MODE, legacySecretKey);
         return new String(cipher.doFinal(Base64.getDecoder().decode(encryptedData)), StandardCharsets.UTF_8);
      } catch (GeneralSecurityException e) {
         throw new RuntimeException("Unable to decrypt data", e);
      }
   }

   private static SecretKeySpec toSecretKey(String kid, String key) {
      byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
      if (!isKeySizeValid(keyBytes.length)) {
         throw new IllegalArgumentException("Encryption key '" + kid + "' must be 16, 24 or 32 characters long");
      }
      return new SecretKeySpec(keyBytes, "AES");
   }

   private static boolean isKeySizeValid(int len) {
      for (int aesKeysize : AES_KEYSIZES) {
         if (len == aesKeysize) {
            return true;
         }
      }
      return false;
   }
}
