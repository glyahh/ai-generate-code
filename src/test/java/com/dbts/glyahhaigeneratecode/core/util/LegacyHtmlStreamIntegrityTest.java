package com.dbts.glyahhaigeneratecode.core.util;

import com.dbts.glyahhaigeneratecode.core.support.LegacyHtmlStreamIntegrity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyHtmlStreamIntegrityTest {

    @Test
    void detects_incomplete_closing_tag_line() {
        assertTrue(LegacyHtmlStreamIntegrity.looksLikeIncompleteTrailingTag("x\n</div"));
        assertTrue(LegacyHtmlStreamIntegrity.looksLikeIncompleteTrailingTag("<section"));
    }

    @Test
    void passes_complete_tag() {
        assertFalse(LegacyHtmlStreamIntegrity.looksLikeIncompleteTrailingTag("<div>ok</div>"));
        assertFalse(LegacyHtmlStreamIntegrity.looksLikeIncompleteTrailingTag("text\n</div>\n"));
    }
}
