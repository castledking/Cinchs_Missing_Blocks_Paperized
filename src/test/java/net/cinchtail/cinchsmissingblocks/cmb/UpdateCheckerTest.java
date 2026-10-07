package net.cinchtail.cinchsmissingblocks.cmb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

    @Test
    void comparesVersionsPartByPart() {
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.0.0"));
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.2"), "numerically, not as text");
        assertTrue(UpdateChecker.isNewer("2.0", "1.9.9"));
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.0"));
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.1"));
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"), "a missing part is 0");
    }

    @Test
    void aSuffixOnlyBreaksATie() {
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.1.0-beta"));
        assertFalse(UpdateChecker.isNewer("1.1.0-beta", "1.1.0"));
        assertTrue(UpdateChecker.isNewer("1.2.0-beta", "1.1.0"));
    }

    @Test
    void takesTheNewestReleaseNotABeta() {
        String json = """
                [{"version_number": "1.2.0-beta", "version_type": "beta"},
                 {"version_number": "1.1.0", "version_type": "release"},
                 {"version_number": "1.0.0", "version_type": "release"}]""";
        assertEquals("1.1.0", UpdateChecker.newestRelease(json));
        assertNull(UpdateChecker.newestRelease("[]"));
    }
}
