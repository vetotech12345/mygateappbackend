package com.mygateapp.notifications.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the Firebase Admin SDK initialises from a service-account file with this project's dependency set
 * (Firestore / Cloud Storage excluded). Uses a freshly generated, throw-away key: no network call is made and no
 * real Firebase project is involved, so actual delivery is NOT tested here.
 */
class FirebasePushSenderTest {

    @TempDir
    Path dir;

    @Test
    void initialisesFromAServiceAccountFile() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";
        Path credentials = dir.resolve("service-account.json");
        Files.writeString(credentials, """
                {
                  "type": "service_account",
                  "project_id": "mygateapp-test",
                  "private_key_id": "test-key-id",
                  "private_key": "%s",
                  "client_email": "push@mygateapp-test.iam.gserviceaccount.com",
                  "client_id": "1234567890",
                  "token_uri": "https://oauth2.googleapis.com/token"
                }
                """.formatted(pem));
        FirebasePushSender sender = new FirebasePushSender(credentials.toString());
        assertThat(sender.name()).isEqualTo("firebase");
        // A second instance reuses the already initialised Firebase app.
        assertThat(new FirebasePushSender(credentials.toString()).name()).isEqualTo("firebase");
    }

    @Test
    void missingCredentialsFileFailsFast() {
        assertThatThrownBy(() -> new FirebasePushSender(dir.resolve("absent.json").toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREBASE_CREDENTIALS_PATH");
    }
}
