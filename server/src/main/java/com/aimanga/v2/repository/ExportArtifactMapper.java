package com.aimanga.v2.repository;

import com.aimanga.v2.model.ExportArtifact;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ExportArtifactMapper extends BaseMapper<ExportArtifact> {

    @Select("SELECT * FROM export_artifact WHERE task_id = #{taskId} LIMIT 1")
    ExportArtifact selectByTaskId(@Param("taskId") Long taskId);

    @Update("UPDATE export_artifact SET storage_url = #{storageUrl}, byte_size = #{byteSize}, " +
            "sha256 = #{sha256}, status = 1, expires_at = #{expiresAt}, update_time = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int activate(@Param("id") Long id, @Param("storageUrl") String storageUrl,
                 @Param("byteSize") long byteSize, @Param("sha256") String sha256,
                 @Param("expiresAt") LocalDateTime expiresAt);

    @Select("SELECT a.* FROM export_artifact a JOIN task t ON t.id = a.task_id " +
            "WHERE a.status IN (0,1) AND (t.status IN (3,5) OR (a.expires_at IS NOT NULL AND a.expires_at <= NOW())) " +
            "ORDER BY a.id LIMIT 100")
    List<ExportArtifact> selectCleanupCandidates();

    @Update("UPDATE export_artifact SET status = 2, storage_url = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND status IN (0,1)")
    int markExpired(@Param("id") Long id);
}
