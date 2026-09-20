package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.PageEntity;

/** Shared completion/publication definition for a page's current final image. */
public final class PageReadiness {
    private PageReadiness() { }

    public static boolean hasCurrentImage(PageEntity page) {
        return page != null
                && page.getGenerateStatus() != null && page.getGenerateStatus() == PageEntity.GEN_SUCCESS
                && page.getGeneratedImageUrl() != null && !page.getGeneratedImageUrl().isBlank()
                && page.getScriptVersion() != null && page.getImageScriptVersion() != null
                && page.getScriptVersion().equals(page.getImageScriptVersion());
    }

    public static boolean hasText(PageEntity page) {
        return (page.getNarration() != null && !page.getNarration().isBlank())
                || (page.getDialogue() != null && !page.getDialogue().isBlank()
                    && !"[]".equals(page.getDialogue().trim()));
    }
}
