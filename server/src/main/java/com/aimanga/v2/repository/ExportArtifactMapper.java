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

    @Select("SELECT * FROM export_artifact WHERE task_id = #{taskId} LIMIT 1 FOR UPDATE")
    ExportArtifact lockByTaskId(@Param("taskId") Long taskId);

    @Select("SELECT * FROM export_artifact WHERE id = #{id} FOR UPDATE")
    ExportArtifact lockById(@Param("id") Long id);

    @Update("UPDATE export_artifact SET generation=generation+1, publisher_token=#{token}, update_time=NOW() " +
            "WHERE id=#{id} AND (publisher_token IS NULL OR publisher_token != #{token})")
    int beginGeneration(@Param("id") Long id, @Param("token") String token);

    @Update("UPDATE export_artifact SET current_object_id=#{objectId}, storage_url=#{storageUrl}, " +
            "byte_size=#{byteSize}, sha256=#{sha256}, status=1, expires_at=#{expiresAt}, update_time=NOW() " +
            "WHERE id=#{id} AND generation=#{generation} AND publisher_token=#{token}")
    int publishOwned(@Param("id") Long id, @Param("generation") long generation,
                     @Param("token") String token, @Param("objectId") Long objectId,
                     @Param("storageUrl") String storageUrl, @Param("byteSize") long byteSize,
                     @Param("sha256") String sha256, @Param("expiresAt") LocalDateTime expiresAt);

    @Update("UPDATE export_artifact SET current_object_id=#{objectId}, update_time=NOW() " +
            "WHERE id=#{id} AND current_object_id IS NULL AND storage_url=#{storageUrl}")
    int attachLegacyObject(@Param("id") Long id, @Param("objectId") Long objectId,
                           @Param("storageUrl") String storageUrl);

    @Update("UPDATE export_artifact SET storage_url = #{storageUrl}, byte_size = #{byteSize}, " +
            "sha256 = #{sha256}, status = 1, expires_at = #{expiresAt}, update_time = NOW() " +
            "WHERE id = #{id} AND status IN (0,1,2)")
    int activate(@Param("id") Long id, @Param("storageUrl") String storageUrl,
                 @Param("byteSize") long byteSize, @Param("sha256") String sha256,
                 @Param("expiresAt") LocalDateTime expiresAt);

    @Select("SELECT * FROM export_artifact WHERE status = 1 " +
            "AND expires_at IS NOT NULL AND expires_at <= NOW() ORDER BY id LIMIT 100")
    List<ExportArtifact> selectCleanupCandidates();

    @Update("UPDATE export_artifact SET status = 2, storage_url = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND status = 1 AND storage_url = #{storageUrl}")
    int markExpired(@Param("id") Long id, @Param("storageUrl") String storageUrl);

    @Select("SELECT * FROM export_artifact WHERE current_object_id IS NULL AND storage_url IS NOT NULL " +
            "AND status=1 ORDER BY id LIMIT 100")
    List<ExportArtifact> selectLegacyBackfillCandidates();
}
