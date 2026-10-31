/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T-507: verify {@link SecureFilePermissions} applies 0600
 * on POSIX systems and is a no-op on Windows. The test
 * skips the mode assertion on non-POSIX platforms.
 */
class SecureFilePermissionsT507Test {

    @Test
    void appliesOwnerReadWriteOnlyOnExistingFile(@TempDir Path tmp) throws Exception {
        assumeTrue(SecureFilePermissions.isPosixSupported(),
                "POSIX-only test; skipped on Windows");
        Path file = tmp.resolve("secret.json");
        Files.writeString(file, "{}");
        // Create with a permissive umask first.
        Files.setPosixFilePermissions(file, java.util.EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));
        SecureFilePermissions.applyOwnerReadWriteOnly(file);
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertEquals(SecureFilePermissions.OWNER_RW_ONLY, perms,
                "expected 0600 (owner rw only)");
    }

    @Test
    void noopOnMissingFile(@TempDir Path tmp) {
        Path file = tmp.resolve("does-not-exist.json");
        // Should not throw; the helper is a no-op when the file is absent.
        SecureFilePermissions.applyOwnerReadWriteOnly(file);
    }

    @Test
    void noopOnNullPath() {
        SecureFilePermissions.applyOwnerReadWriteOnly(null);
    }

    @Test
    void ownerReadWriteOnlyFileAttribute(@TempDir Path tmp) throws Exception {
        assumeTrue(SecureFilePermissions.isPosixSupported(),
                "POSIX-only test; skipped on Windows");
        // Use the asFileAttribute variant to create a new
        // file with 0600 already set, so there's no
        // window where the file exists with the process
        // umask.
        Path file = tmp.resolve("fresh.json");
        Files.createFile(file, SecureFilePermissions.ownerReadWriteOnly());
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ),
                "owner can read");
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE),
                "owner can write");
        assertEquals(2, perms.size(),
                "expected exactly owner-read + owner-write (0600), got " + perms);
    }
}
