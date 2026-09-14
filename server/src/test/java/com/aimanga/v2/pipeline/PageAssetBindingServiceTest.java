package com.aimanga.v2.pipeline;

import com.aimanga.v2.model.Asset;
import com.aimanga.v2.model.PageAssetRef;
import com.aimanga.v2.model.PageEntity;
import com.aimanga.v2.repository.AssetMapper;
import com.aimanga.v2.repository.PageAssetRefMapper;
import com.aimanga.v2.repository.PageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6.1 页-素材绑定单元测试:
 * speaker/visual/scene_description 匹配、别名命中、角色 required=1、AI id 校验过滤。
 */
class PageAssetBindingServiceTest {

    private PageMapper pageMapper;
    private AssetMapper assetMapper;
    private PageAssetRefMapper refMapper;
    private PageAssetBindingService service;
    private PageEntity page;

    @BeforeEach
    void setUp() {
        pageMapper = mock(PageMapper.class);
        assetMapper = mock(AssetMapper.class);
        refMapper = mock(PageAssetRefMapper.class);
        service = new PageAssetBindingService(pageMapper, assetMapper, refMapper, new ObjectMapper());
        page = new PageEntity();
        page.setId(10L);
        page.setProjectId(100L);
        page.setPageNo(1);
        page.setDialogue("[{\"speaker\":\"林凡\",\"line\":\"我们走\"}]");
        page.setSceneDescription("教室内,夕阳斜照");
        page.setVisual("林凡站在学校天台边缘,手持黑色手机");
        when(refMapper.selectList(any())).thenReturn(List.of());
    }

    private Asset asset(long id, int type, String name, String aliasesJson) {
        Asset a = new Asset();
        a.setId(id);
        a.setProjectId(100L);
        a.setAssetType(type);
        a.setName(name);
        a.setAliases(aliasesJson);
        return a;
    }

    @Test
    void bindPage_matchesSpeakerVisualScene_andWritesRefs() {
        List<Asset> assets = List.of(
                asset(1L, Asset.TYPE_CHARACTER, "林凡", "[\"小凡\"]"),
                asset(2L, Asset.TYPE_SCENE, "学校天台", "[]"),
                asset(3L, Asset.TYPE_PROP, "黑色手机", "[]"),
                asset(4L, Asset.TYPE_CHARACTER, "苏晚", "[]") // 未出现
        );

        int count = service.bindPage(page, assets, List.of());

        ArgumentCaptor<PageAssetRef> captor = ArgumentCaptor.forClass(PageAssetRef.class);
        verify(refMapper).delete(any());
        verify(refMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertThat(count).isEqualTo(3);
        List<PageAssetRef> refs = captor.getAllValues();
        // 排序:角色在前
        assertThat(refs.get(0).getAssetId()).isEqualTo(1L);
        assertThat(refs.get(0).getRequiredFlag()).isEqualTo(1);
        assertThat(refs.get(0).getSource()).isEqualTo(PageAssetRef.SOURCE_MATCH);
        assertThat(refs.get(1).getAssetId()).isEqualTo(2L);
        assertThat(refs.get(1).getRequiredFlag()).isZero();
        assertThat(refs.get(2).getAssetId()).isEqualTo(3L);
    }

    @Test
    void bindPage_aliasHitAndAiIdsFiltered() {
        // AI 返回了非法 id 999,应被过滤;别名"小凡"命中角色1
        List<Asset> assets = List.of(
                asset(1L, Asset.TYPE_CHARACTER, "林凡", "[\"小凡\"]"),
                asset(2L, Asset.TYPE_SCENE, "学校天台", "[]")
        );

        service.bindPage(page, assets, List.of(999L, 2L));

        ArgumentCaptor<PageAssetRef> captor = ArgumentCaptor.forClass(PageAssetRef.class);
        verify(refMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<PageAssetRef> refs = captor.getAllValues();
        assertThat(refs).extracting(PageAssetRef::getAssetId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(refs).extracting(PageAssetRef::getSource)
                .contains(PageAssetRef.SOURCE_AI, PageAssetRef.SOURCE_MATCH);
    }
}
