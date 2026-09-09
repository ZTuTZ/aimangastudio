package com.aimanga.v2.dto.export;

import java.util.List;

/** manifest 话节点:chapter_no 升序,title 必填 */
public record ComicManifestChapter(
        Integer chapterNo,
        String title,
        List<ComicManifestPage> pages) {
}
