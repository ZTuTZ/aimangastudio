package com.aimanga.v2.repository;

import com.aimanga.v2.model.ExportStoredObject;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ExportStoredObjectMapper extends BaseMapper<ExportStoredObject> {
    String ORPHAN_FINAL_PREDICATE = "(o.kind='FINAL' AND o.state='RETAINED' AND o.upload_deadline<NOW() " +
            "AND NOT EXISTS (SELECT 1 FROM task t WHERE t.id=o.task_id AND t.status IN (0,1,6,7)) " +
            "AND NOT EXISTS (SELECT 1 FROM export_artifact a WHERE a.id=o.artifact_id " +
            "AND (a.current_object_id=o.id OR (a.current_object_id IS NULL AND a.storage_url=o.storage_url))))";

    @Select("SELECT * FROM export_stored_object WHERE id = #{id} FOR UPDATE")
    ExportStoredObject lockById(@Param("id") Long id);

    @Select("SELECT * FROM export_stored_object WHERE storage_key = #{key} LIMIT 1")
    ExportStoredObject selectByStorageKey(@Param("key") String key);

    @Update("UPDATE export_stored_object SET state='RETAINED', storage_url=#{url}, byte_size=#{bytes}, " +
            "sha256=#{sha256}, update_time=NOW() WHERE id=#{id} AND state='UPLOADING'")
    int markUploaded(@Param("id") Long id, @Param("url") String url,
                     @Param("bytes") long bytes, @Param("sha256") String sha256);

    @Update("UPDATE export_stored_object SET state='DELETE_PENDING', storage_url=#{url}, byte_size=#{bytes}, " +
            "sha256=#{sha256}, delete_after=NOW(), cleanup_token=NULL, cleanup_lease_until=NULL, " +
            "last_error='上传在清理后完成，重新排队删除', update_time=NOW() WHERE id=#{id}")
    int queueLateUploadDeletion(@Param("id") Long id, @Param("url") String url,
                                @Param("bytes") long bytes, @Param("sha256") String sha256);

    @Update("UPDATE export_stored_object SET state='DELETE_PENDING', delete_after=#{deleteAfter}, " +
            "last_error=#{reason}, update_time=NOW() WHERE id=#{id} AND state IN ('UPLOADING','RETAINED')")
    int requestDeletion(@Param("id") Long id, @Param("deleteAfter") java.time.LocalDateTime deleteAfter,
                        @Param("reason") String reason);

    @Select("SELECT * FROM export_stored_object o WHERE ((o.state='DELETE_PENDING' AND o.delete_after<=NOW()) " +
            "OR (o.state='DELETING' AND o.cleanup_lease_until<NOW()) " +
            "OR (o.state='UPLOADING' AND o.upload_deadline<NOW() " +
            "AND NOT EXISTS (SELECT 1 FROM task t WHERE t.id=o.task_id AND t.status IN (0,1,6,7))) " +
            "OR " + ORPHAN_FINAL_PREDICATE + ") " +
            "AND NOT EXISTS (SELECT 1 FROM export_object_read_lease l WHERE l.object_id=o.id AND l.lease_until>NOW()) " +
            "ORDER BY o.id LIMIT 100")
    List<ExportStoredObject> selectCleanupCandidates();

    @Update("UPDATE export_stored_object o SET o.state='DELETING', o.cleanup_token=#{token}, " +
            "o.cleanup_lease_until=DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND), o.update_time=NOW() " +
            "WHERE o.id=#{id} AND (o.state='DELETE_PENDING' OR " +
            "(o.state='UPLOADING' AND o.upload_deadline<NOW() " +
            "AND NOT EXISTS (SELECT 1 FROM task t WHERE t.id=o.task_id AND t.status IN (0,1,6,7))) OR " +
            "(o.state='DELETING' AND o.cleanup_lease_until<NOW()) OR " + ORPHAN_FINAL_PREDICATE + ") " +
            "AND NOT EXISTS (SELECT 1 FROM export_object_read_lease l WHERE l.object_id=o.id AND l.lease_until>NOW())")
    int claimCleanup(@Param("id") Long id, @Param("token") String token,
                     @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE export_stored_object SET state='DELETED', cleanup_token=NULL, cleanup_lease_until=NULL, " +
            "storage_url=NULL, update_time=NOW() WHERE id=#{id} AND state='DELETING' AND cleanup_token=#{token}")
    int markDeleted(@Param("id") Long id, @Param("token") String token);

    @Update("UPDATE export_stored_object SET state='DELETE_PENDING', cleanup_token=NULL, cleanup_lease_until=NULL, " +
            "retry_count=retry_count+1, last_error=#{error}, " +
            "delete_after=DATE_ADD(NOW(), INTERVAL LEAST(3600, POW(2, LEAST(retry_count,10))) SECOND), update_time=NOW() " +
            "WHERE id=#{id} AND state='DELETING' AND cleanup_token=#{token}")
    int cleanupFailed(@Param("id") Long id, @Param("token") String token, @Param("error") String error);

    @Update("UPDATE export_stored_object SET state='DELETE_PENDING', delete_after=NOW(), " +
            "last_error=#{reason}, update_time=NOW() WHERE task_id=#{taskId} AND state IN ('UPLOADING','RETAINED')")
    int requestTaskDeletion(@Param("taskId") Long taskId, @Param("reason") String reason);

    @Update("UPDATE export_stored_object o LEFT JOIN task t ON t.id=o.task_id " +
            "SET o.state='DELETE_PENDING', o.delete_after=NOW(), o.last_error='检查点超过保留期', o.update_time=NOW() " +
            "WHERE o.kind='CHECKPOINT' AND o.state='RETAINED' " +
            "AND o.create_time<DATE_SUB(NOW(), INTERVAL #{ttlHours} HOUR) " +
            "AND (t.id IS NULL OR t.status NOT IN (0,1,6,7))")
    int expireCheckpoints(@Param("ttlHours") int ttlHours);
}
