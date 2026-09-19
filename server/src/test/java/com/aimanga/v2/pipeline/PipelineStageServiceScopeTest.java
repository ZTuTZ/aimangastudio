package com.aimanga.v2.pipeline;

import com.aimanga.v2.repository.PipelineStageItemMapper;
import com.aimanga.v2.repository.PipelineStageMapper;
import com.aimanga.v2.model.PipelineStageItem;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;

class PipelineStageServiceScopeTest {

    @BeforeAll
    static void initializePipelineStageItemMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "PipelineStageItemMapper"),
                PipelineStageItem.class);
    }

    @Test
    void emptyScopedBusinessIdsDoNotExpandToProjectPendingItems() {
        PipelineStageItemMapper itemMapper = mock(PipelineStageItemMapper.class);
        PipelineStageService service = new PipelineStageService(mock(PipelineStageMapper.class), itemMapper);
        when(itemMapper.selectPendingIds(1L, "IMAGE", 64)).thenReturn(List.of(101L));

        List<Long> pending = service.getPendingItemIdsInScope(
                1L, "IMAGE", "PAGE", List.of(), 64);

        assertThat(pending).isEmpty();
        verify(itemMapper, never()).selectPendingIds(anyLong(), anyString(), anyInt());
        verify(itemMapper, never()).selectPendingIdsInScope(
                anyLong(), anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @Test
    void successfulItemResetClearsJsonResultReferenceWithSqlNull() {
        PipelineStageItemMapper itemMapper = mock(PipelineStageItemMapper.class);
        PipelineStageService service = new PipelineStageService(mock(PipelineStageMapper.class), itemMapper);
        when(itemMapper.update(isNull(), any())).thenReturn(1);

        service.resetSuccessfulItemsByBusiness(1L, "IMAGE", "PAGE", List.of(11L));

        ArgumentCaptor<LambdaUpdateWrapper<PipelineStageItem>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(itemMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getParamNameValuePairs().get("MPGENVAL2")).isNull();
    }
}
