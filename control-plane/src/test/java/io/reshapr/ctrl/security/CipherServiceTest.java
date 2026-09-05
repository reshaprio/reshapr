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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CipherService.
 * @author vaishnav
 */
class CipherServiceTest {

    private CipherService cipherService;

    @BeforeEach
    void setUp() {
        cipherService = new CipherService();
    }

    @Test
    void testInitializeWithValid16CharKey() {
        cipherService.encryptionKey = "1234567890123456";
        assertDoesNotThrow(() -> cipherService.initialize());
    }

    @Test
    void testInitializeWithValid24CharKey() {
        cipherService.encryptionKey = "123456789012345678901234";
        assertDoesNotThrow(() -> cipherService.initialize());
    }

    @Test
    void testInitializeWithValid32CharKey() {
        cipherService.encryptionKey = "12345678901234567890123456789012";
        assertDoesNotThrow(() -> cipherService.initialize());
    }

    @Test
    void testInitializeWithInvalidKeySize() {
        cipherService.encryptionKey = "short";
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> cipherService.initialize());
        assertEquals("Encryption key must 16, 24 or 32 characters long", exception.getMessage());
    }

    @Test
    void testEncryptAndDecrypt() {
        cipherService.encryptionKey = "1234567890123456";
        cipherService.initialize();

        String originalData = "SuperSecretData123!";
        
        String encryptedData = cipherService.encrypt(originalData);
        assertNotNull(encryptedData);
        assertNotEquals(originalData, encryptedData);

        String decryptedData = cipherService.decrypt(encryptedData);
        assertEquals(originalData, decryptedData);
    }
}
