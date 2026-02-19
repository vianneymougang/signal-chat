package cm.iusjc.signalchat.client.crypto;

import cm.iusjc.signalchat.client.monitoring.CryptoLogger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Security;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour KeyManager.
 * Valide la generation des cles, les echanges DH et les signatures XEdDSA.
 */
class KeyManagerTest {

    private static KeyManager keyManager;

    @BeforeAll
    static void setUp() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        keyManager = new KeyManager(new CryptoLogger());
    }

    // Tests generation de cles d'identite (XEdDSA)

    @Test
    @DisplayName("generateIdentityKeyPair produit un seed de 32 bytes")
    void identityKeyPair_seedIs32Bytes() {
        IdentityKeyPair kp = keyManager.generateIdentityKeyPair();
        assertEquals(32, kp.getSeed().length, "Le seed doit faire 32 bytes");
    }

    @Test
    @DisplayName("generateIdentityKeyPair produit une cle X25519 de 32 bytes")
    void identityKeyPair_x25519PublicIs32Bytes() {
        IdentityKeyPair kp = keyManager.generateIdentityKeyPair();
        assertEquals(32, kp.getX25519PublicKey().length, "La cle publique X25519 doit faire 32 bytes");
    }

    @Test
    @DisplayName("generateIdentityKeyPair produit une cle Ed25519 de 32 bytes")
    void identityKeyPair_ed25519PublicIs32Bytes() {
        IdentityKeyPair kp = keyManager.generateIdentityKeyPair();
        assertEquals(32, kp.getEd25519PublicKey().length, "La cle publique Ed25519 doit faire 32 bytes");
    }

    @Test
    @DisplayName("Deux paires d'identite differentes sont uniques")
    void identityKeyPair_isUnique() {
        IdentityKeyPair kp1 = keyManager.generateIdentityKeyPair();
        IdentityKeyPair kp2 = keyManager.generateIdentityKeyPair();
        assertFalse(Arrays.equals(kp1.getSeed(), kp2.getSeed()),
                "Deux paires generees doivent avoir des seeds differents");
    }

    // Tests generation de Signed Pre-Key

    @Test
    @DisplayName("generateSignedPreKeyPair produit un seed et une cle publique de 32 bytes")
    void signedPreKeyPair_hasCorrectedSizes() {
        byte[][] spk = keyManager.generateSignedPreKeyPair();
        assertEquals(2, spk.length);
        assertEquals(32, spk[0].length, "Seed/privateKey doit faire 32 bytes");
        assertEquals(32, spk[1].length, "PublicKey doit faire 32 bytes");
    }

    // Tests generation de One-Time Pre-Keys

    @Test
    @DisplayName("generateOneTimePreKeys produit le bon nombre de paires")
    void oneTimePreKeys_generatesRequestedCount() {
        int count = 10;
        byte[][][] opks = keyManager.generateOneTimePreKeys(count);
        assertEquals(count, opks.length, "Doit generer exactement " + count + " paires");
    }

    @Test
    @DisplayName("Chaque OPK a un seed et une cle publique de 32 bytes")
    void oneTimePreKeys_eachHasCorrectSizes() {
        byte[][][] opks = keyManager.generateOneTimePreKeys(5);
        for (int i = 0; i < 5; i++) {
            assertEquals(32, opks[i][0].length, "Seed OPK #" + i + " doit faire 32 bytes");
            assertEquals(32, opks[i][1].length, "PublicKey OPK #" + i + " doit faire 32 bytes");
        }
    }

    @Test
    @DisplayName("Les OPK generees sont toutes differentes")
    void oneTimePreKeys_areUnique() {
        byte[][][] opks = keyManager.generateOneTimePreKeys(5);
        for (int i = 0; i < opks.length - 1; i++) {
            assertFalse(Arrays.equals(opks[i][0], opks[i + 1][0]),
                    "Deux OPK consecutives doivent avoir des seeds differents");
        }
    }

    // Tests echange Diffie-Hellman X25519

    @Test
    @DisplayName("X25519 est commutatif : DH(A_priv, B_pub) == DH(B_priv, A_pub)")
    void dh_isCommutative() {
        byte[][] kpA = keyManager.generateRatchetKeyPair();
        byte[][] kpB = keyManager.generateRatchetKeyPair();

        byte[] secretAtoB = keyManager.performDH(kpA[0], kpB[1],
                cm.iusjc.signalchat.client.model.CryptoEventType.RATCHET_STEP, "test A→B");
        byte[] secretBtoA = keyManager.performDH(kpB[0], kpA[1],
                cm.iusjc.signalchat.client.model.CryptoEventType.RATCHET_STEP, "test B→A");

        assertArrayEquals(secretAtoB, secretBtoA,
                "DH doit etre commutatif : DH(A_priv,B_pub) == DH(B_priv,A_pub)");
    }

    @Test
    @DisplayName("DH avec des cles differentes produit des secrets differents")
    void dh_differentKeysDifferentSecrets() {
        byte[][] kpA  = keyManager.generateRatchetKeyPair();
        byte[][] kpB1 = keyManager.generateRatchetKeyPair();
        byte[][] kpB2 = keyManager.generateRatchetKeyPair();

        byte[] secret1 = keyManager.performDH(kpA[0], kpB1[1],
                cm.iusjc.signalchat.client.model.CryptoEventType.RATCHET_STEP, "test1");
        byte[] secret2 = keyManager.performDH(kpA[0], kpB2[1],
                cm.iusjc.signalchat.client.model.CryptoEventType.RATCHET_STEP, "test2");

        assertFalse(Arrays.equals(secret1, secret2),
                "Des cles differentes doivent produire des secrets differents");
    }

    @Test
    @DisplayName("Le secret DH fait 32 bytes")
    void dh_secretIs32Bytes() {
        byte[][] kpA = keyManager.generateRatchetKeyPair();
        byte[][] kpB = keyManager.generateRatchetKeyPair();

        byte[] secret = keyManager.performDH(kpA[0], kpB[1],
                cm.iusjc.signalchat.client.model.CryptoEventType.RATCHET_STEP, "taille");
        assertEquals(32, secret.length, "Le secret DH X25519 doit faire 32 bytes");
    }

    // Tests XEdDSA (signature Ed25519)

    @Test
    @DisplayName("signXEdDSA produit une signature de 64 bytes")
    void xedDSA_signatureIs64Bytes() {
        IdentityKeyPair kp   = keyManager.generateIdentityKeyPair();
        byte[] data           = "donnees a signer".getBytes();
        byte[] signature      = keyManager.signXEdDSA(kp.getSeed(), data);
        assertEquals(64, signature.length, "La signature Ed25519 doit faire 64 bytes");
    }

    @Test
    @DisplayName("verifyXEdDSA accepte une signature valide")
    void xedDSA_verifyAcceptsValidSignature() {
        IdentityKeyPair kp   = keyManager.generateIdentityKeyPair();
        byte[] data           = "cle publique SPK".getBytes();
        byte[] signature      = keyManager.signXEdDSA(kp.getSeed(), data);
        boolean valid         = keyManager.verifyXEdDSA(kp.getEd25519PublicKey(), data, signature);
        assertTrue(valid, "La verification XEdDSA doit accepter une signature valide");
    }

    @Test
    @DisplayName("verifyXEdDSA rejette une signature avec les mauvaises donnees")
    void xedDSA_verifyRejectsWrongData() {
        IdentityKeyPair kp   = keyManager.generateIdentityKeyPair();
        byte[] originalData   = "donnees originales".getBytes();
        byte[] modifiedData   = "donnees modifiees!".getBytes();
        byte[] signature      = keyManager.signXEdDSA(kp.getSeed(), originalData);
        boolean valid         = keyManager.verifyXEdDSA(kp.getEd25519PublicKey(), modifiedData, signature);
        assertFalse(valid, "La verification XEdDSA doit rejeter une signature avec de mauvaises donnees");
    }

    @Test
    @DisplayName("verifyXEdDSA rejette une signature d'une autre cle")
    void xedDSA_verifyRejectsWrongKey() {
        IdentityKeyPair kp1  = keyManager.generateIdentityKeyPair();
        IdentityKeyPair kp2  = keyManager.generateIdentityKeyPair();
        byte[] data           = "donnees signees".getBytes();
        byte[] signature      = keyManager.signXEdDSA(kp1.getSeed(), data);
        // Verifier avec la cle publique d'une autre identite
        boolean valid         = keyManager.verifyXEdDSA(kp2.getEd25519PublicKey(), data, signature);
        assertFalse(valid, "La signature d'une cle ne doit pas etre validee par une autre cle");
    }
}
