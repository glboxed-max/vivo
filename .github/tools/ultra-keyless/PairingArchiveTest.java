package com.sukisu.ultra.backend;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class PairingArchiveTest {
    private final char[] password = "test-backup-password".toCharArray();

    @Test public void roundTripAndRandomization() {
        byte[] payload = "test pairing data".getBytes(StandardCharsets.UTF_8);
        byte[] first = PairingArchive.INSTANCE.seal(payload, password);
        byte[] second = PairingArchive.INSTANCE.seal(payload, password);
        assertFalse(java.util.Arrays.equals(first, second));
        assertArrayEquals(payload, PairingArchive.INSTANCE.open(first, password));
    }

    @Test public void wrongPasswordFails() {
        byte[] archive = PairingArchive.INSTANCE.seal(new byte[]{1}, password);
        assertThrows(Exception.class, () -> PairingArchive.INSTANCE.open(archive, "another-password".toCharArray()));
    }

    @Test public void tamperingFails() {
        byte[] archive = PairingArchive.INSTANCE.seal(new byte[]{1}, password);
        archive[archive.length - 1] ^= 1;
        assertThrows(Exception.class, () -> PairingArchive.INSTANCE.open(archive, password));
    }

    @Test public void malformedAndOversizedInputFails() {
        assertThrows(IllegalArgumentException.class, () -> PairingArchive.INSTANCE.open(new byte[32], password));
        assertThrows(IllegalArgumentException.class, () -> PairingArchive.INSTANCE.read(new ByteArrayInputStream(new byte[1048577])));
    }
}
