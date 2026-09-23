package com.aimanga.v2.repository;

import com.aimanga.v2.model.ExportObjectReadLease;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ExportObjectReadLeaseMapper extends BaseMapper<ExportObjectReadLease> {
    @Update("UPDATE export_object_read_lease SET lease_until=DATE_ADD(NOW(), INTERVAL #{seconds} SECOND) " +
            "WHERE token=#{token} AND lease_until>NOW()")
    int renew(@Param("token") String token, @Param("seconds") int seconds);

    @Delete("DELETE FROM export_object_read_lease WHERE token=#{token}")
    int release(@Param("token") String token);

    @Delete("DELETE FROM export_object_read_lease WHERE lease_until<=NOW()")
    int deleteExpired();
}
