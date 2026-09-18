-- =====================================================================
-- AIMangaStudio v2 — V1 Baseline Schema(Phase 8.7 Flyway)
-- 正式数据库变更以 Flyway 为唯一来源。
-- CI 验证:空 MySQL → 应用启动 → 自动得到完整 Schema。
-- =====================================================================

-- MySQL dump 10.13  Distrib 8.4.11, for macos26.6 (arm64)
--
-- Host: localhost    Database: aimanga_v2
-- ------------------------------------------------------
-- Server version	8.4.11

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `asset`
--

DROP TABLE IF EXISTS `asset`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `asset` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `asset_type` tinyint NOT NULL COMMENT '1角色 2场景 3道具 4服装',
  `name` varchar(128) NOT NULL,
  `aliases` json DEFAULT NULL COMMENT '别名数组(同人合并)',
  `description` text,
  `structured` json DEFAULT NULL COMMENT '结构化设定(角色:{role,age,hair,accessories,top,bottom})',
  `reference_url` varchar(512) DEFAULT NULL COMMENT '参考图(OSS)',
  `sheet_image_url` varchar(512) DEFAULT NULL COMMENT '设定表图(角色=六姿势)',
  `gen_status` tinyint NOT NULL DEFAULT '0' COMMENT '0空闲 1生成中 9失败',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_asset_project_type_name` (`project_id`,`asset_type`,`name`),
  KEY `idx_asset_project` (`project_id`),
  CONSTRAINT `fk_asset_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资产';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `chapter`
--

DROP TABLE IF EXISTS `chapter`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `chapter` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `chapter_no` int NOT NULL,
  `title` varchar(255) NOT NULL DEFAULT '',
  `script_text` longtext COMMENT '本话故事原文',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0待处理1脚本生成中2脚本就绪3出图中4完成5部分失败',
  `page_count` int NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chapter_project_no` (`project_id`,`chapter_no`),
  KEY `idx_chapter_project` (`project_id`),
  CONSTRAINT `fk_chapter_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='话';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `comic`
--

DROP TABLE IF EXISTS `comic`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comic` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `content_uid` char(36) NOT NULL COMMENT '跨系统稳定ID(与包 manifest 一致)',
  `title` varchar(255) NOT NULL,
  `tagline` varchar(64) DEFAULT '',
  `description` text,
  `cover_url` varchar(512) DEFAULT NULL,
  `category` varchar(64) DEFAULT '',
  `tags` json DEFAULT NULL,
  `series_status` tinyint DEFAULT '2',
  `aspect_ratio` varchar(20) DEFAULT '3:4',
  `color_mode` varchar(20) DEFAULT 'partial',
  `complete` tinyint DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comic_content_uid` (`content_uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[APP模拟]漫画';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `comic_chapter`
--

DROP TABLE IF EXISTS `comic_chapter`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comic_chapter` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `comic_id` bigint unsigned NOT NULL,
  `chapter_no` int NOT NULL,
  `title` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cc_comic_no` (`comic_id`,`chapter_no`),
  CONSTRAINT `fk_cc_comic` FOREIGN KEY (`comic_id`) REFERENCES `comic` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[APP模拟]话';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `comic_page`
--

DROP TABLE IF EXISTS `comic_page`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comic_page` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `chapter_id` bigint unsigned NOT NULL,
  `page_no` int NOT NULL,
  `image_url` varchar(512) NOT NULL,
  `file_path` varchar(255) DEFAULT NULL COMMENT '包内相对路径',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cp_chapter_no` (`chapter_id`,`page_no`),
  CONSTRAINT `fk_cp_chapter` FOREIGN KEY (`chapter_id`) REFERENCES `comic_chapter` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[APP模拟]页';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `comic_pipeline_stage`
--

DROP TABLE IF EXISTS `comic_pipeline_stage`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comic_pipeline_stage` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `stage_type` varchar(32) NOT NULL COMMENT 'SPLIT/ASSET/SCRIPT/SHEET/LAYOUT/IMAGE/EXPORT',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0排队 1进行中 2成功 3失败 4暂停 5停止',
  `progress` int NOT NULL DEFAULT '0' COMMENT '0-100',
  `total_count` int NOT NULL DEFAULT '0',
  `success_count` int NOT NULL DEFAULT '0',
  `failed_count` int NOT NULL DEFAULT '0',
  `result_ref` json DEFAULT NULL COMMENT '阶段结果引用(如 chapterIds/assetIds)',
  `error` varchar(512) DEFAULT '',
  `start_time` datetime DEFAULT NULL,
  `finish_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stage_project_type` (`project_id`,`stage_type`),
  KEY `idx_stage_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流水线阶段进度';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `generation_record`
--

DROP TABLE IF EXISTS `generation_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `generation_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `chapter_id` bigint unsigned DEFAULT NULL,
  `page_id` bigint unsigned DEFAULT NULL,
  `task_id` bigint unsigned DEFAULT NULL,
  `kind` varchar(32) NOT NULL COMMENT 'LAYOUT/PAGE/COLORIZE/CLEAN/REPAINT',
  `model` varchar(128) DEFAULT '',
  `prompt` text,
  `reference_urls` json DEFAULT NULL COMMENT '参考图 URL 数组',
  `input_url` varchar(512) DEFAULT NULL COMMENT '输入图(后处理为原图)',
  `result_url` varchar(512) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED',
  `error` varchar(512) DEFAULT '',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_gr_page` (`page_id`),
  KEY `idx_gr_project_kind` (`project_id`,`kind`),
  CONSTRAINT `fk_gr_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 生成记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `page`
--

DROP TABLE IF EXISTS `page`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `page` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `chapter_id` bigint unsigned NOT NULL,
  `page_no` int NOT NULL,
  `narration` text COMMENT '旁白(矩形框)',
  `dialogue` json DEFAULT NULL COMMENT '对白[{speaker,line}](气泡)',
  `visual` text COMMENT '画面详述',
  `scene_description` text COMMENT '合成展示脚本(旁白+对白+画面)',
  `layout_image_url` varchar(512) DEFAULT NULL COMMENT 'AI 分镜布局图',
  `generated_image_url` varchar(512) DEFAULT NULL COMMENT '成品页',
  `color_mode` varchar(20) DEFAULT NULL COMMENT '本页成图色彩模式',
  `generate_status` tinyint NOT NULL DEFAULT '0' COMMENT '0待生成1生成中2成功3失败',
  `fail_reason` varchar(512) DEFAULT '',
  `generate_records` json DEFAULT NULL COMMENT '生成历史[{url,colorMode,kind,time}]',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `script_version` int NOT NULL DEFAULT '1' COMMENT '脚本版本:文本修改+1',
  `layout_script_version` int NOT NULL DEFAULT '0' COMMENT '布局图生成时的脚本版本',
  `image_script_version` int NOT NULL DEFAULT '0' COMMENT '成品图生成时的脚本版本',
  `text_layout_version` int NOT NULL DEFAULT '0' COMMENT '文本层同步时的脚本版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_page_chapter_no` (`chapter_id`,`page_no`),
  KEY `idx_page_project` (`project_id`),
  CONSTRAINT `fk_page_chapter` FOREIGN KEY (`chapter_id`) REFERENCES `chapter` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='页';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `page_asset_ref`
--

DROP TABLE IF EXISTS `page_asset_ref`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `page_asset_ref` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `page_id` bigint unsigned NOT NULL,
  `asset_id` bigint unsigned NOT NULL,
  `required_flag` tinyint NOT NULL DEFAULT '0' COMMENT '1必需(角色) 0可选(场景/道具/服装)',
  `source` varchar(16) NOT NULL DEFAULT 'MATCH' COMMENT 'AI模型返回/MATCH程序匹配/MANUAL人工',
  `sort_order` int NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_page_asset` (`page_id`,`asset_id`),
  KEY `idx_par_project_page` (`project_id`,`page_id`),
  KEY `idx_par_asset` (`asset_id`),
  CONSTRAINT `fk_par_asset` FOREIGN KEY (`asset_id`) REFERENCES `asset` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_par_page` FOREIGN KEY (`page_id`) REFERENCES `page` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='页-资产素材绑定';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `page_text_element`
--

DROP TABLE IF EXISTS `page_text_element`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `page_text_element` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `chapter_id` bigint unsigned NOT NULL,
  `page_id` bigint unsigned NOT NULL,
  `element_uid` varchar(64) NOT NULL,
  `element_type` varchar(32) NOT NULL COMMENT 'DIALOGUE/NARRATION/THOUGHT/SFX',
  `dialogue_index` int DEFAULT NULL COMMENT '对应page.dialogue数组索引,旁白可为空',
  `speaker` varchar(128) DEFAULT NULL,
  `text_content` text NOT NULL,
  `x` decimal(8,6) NOT NULL,
  `y` decimal(8,6) NOT NULL,
  `width` decimal(8,6) NOT NULL,
  `height` decimal(8,6) DEFAULT NULL,
  `tail_x` decimal(8,6) DEFAULT NULL,
  `tail_y` decimal(8,6) DEFAULT NULL,
  `bubble_style` varchar(32) NOT NULL DEFAULT 'DEFAULT_DIALOGUE',
  `font_style` varchar(32) NOT NULL DEFAULT 'DEFAULT_DIALOGUE',
  `font_size_ratio` decimal(8,6) NOT NULL DEFAULT '0.028000',
  `text_align` varchar(16) NOT NULL DEFAULT 'CENTER',
  `max_lines` int DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `source_type` varchar(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'AUTO/MANUAL/AI',
  `version` int NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_page_text_uid` (`element_uid`),
  KEY `idx_page_text_page` (`page_id`),
  KEY `idx_page_text_project` (`project_id`),
  CONSTRAINT `fk_page_text_page` FOREIGN KEY (`page_id`) REFERENCES `page` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='页动态文本层元素(归一化坐标)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `pipeline_stage_item`
--

DROP TABLE IF EXISTS `pipeline_stage_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pipeline_stage_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `stage_type` varchar(32) NOT NULL COMMENT 'SPLIT/ASSET/SCRIPT/SHEET/LAYOUT/IMAGE/EXPORT',
  `business_type` varchar(32) NOT NULL COMMENT '业务类型:CHAPTER/PAGE',
  `business_id` bigint unsigned NOT NULL COMMENT '业务主键(chapter.id/page.id/asset.id)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0排队 1进行中 2成功 3失败',
  `retry_count` int NOT NULL DEFAULT '0',
  `result_ref` json DEFAULT NULL COMMENT '结果引用(如 assetId/pageUrl)',
  `error_message` varchar(512) DEFAULT '',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `attempt_no` int NOT NULL DEFAULT '0' COMMENT '执行代次',
  `attempt_token` varchar(64) DEFAULT NULL COMMENT '当前执行 fencing token',
  `claimed_at` datetime DEFAULT NULL COMMENT '当前 attempt 领取时间',
  `finish_time` datetime DEFAULT NULL COMMENT '最后完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_item` (`project_id`,`stage_type`,`business_type`,`business_id`),
  KEY `idx_item_status` (`project_id`,`stage_type`,`status`),
  KEY `idx_item_attempt` (`id`,`attempt_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流水线阶段执行单元';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `project`
--

DROP TABLE IF EXISTS `project`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `project` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `content_uid` char(36) NOT NULL COMMENT '跨系统稳定作品ID',
  `user_id` bigint unsigned NOT NULL,
  `title` varchar(255) NOT NULL,
  `source_text` longtext COMMENT '故事原文',
  `aspect_ratio` varchar(20) NOT NULL DEFAULT '3:4' COMMENT '3:4/2:3/1:1/16:9',
  `scene_ratio` varchar(20) NOT NULL DEFAULT '16:9' COMMENT '场景参考图画幅(默认16:9)',
  `prop_ratio` varchar(20) NOT NULL DEFAULT '1:1' COMMENT '道具参考图画幅(默认1:1)',
  `costume_ratio` varchar(20) NOT NULL DEFAULT '3:4' COMMENT '服装参考图画幅(默认3:4)',
  `color_mode` varchar(20) NOT NULL DEFAULT 'partial' COMMENT 'partial局部上色/monochrome黑白/color全彩',
  `style_preset_id` bigint unsigned DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0准备中1待出图2出图中3完成4部分失败',
  `tagline` varchar(64) DEFAULT '' COMMENT '简介文案',
  `description` text COMMENT '漫画正式简介',
  `cover_url` varchar(512) DEFAULT NULL COMMENT '漫画封面OSS URL',
  `category` varchar(64) NOT NULL DEFAULT '' COMMENT '漫画主分类',
  `tags` json DEFAULT NULL COMMENT '漫画标签数组',
  `series_status` tinyint NOT NULL DEFAULT '2' COMMENT '1连载中 2已完结',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_content_uid` (`content_uid`),
  KEY `idx_project_user` (`user_id`),
  KEY `idx_project_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='作品';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `style_preset`
--

DROP TABLE IF EXISTS `style_preset`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `style_preset` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL,
  `positive_prompt` text COMMENT '风格提示词',
  `negative_prompt` text COMMENT '负面提示词',
  `color_mode` varchar(20) DEFAULT NULL COMMENT '建议色彩模式 partial/monochrome/color',
  `ref_images` json DEFAULT NULL COMMENT '风格参考图 URL 数组',
  `sort` int NOT NULL DEFAULT '0',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `remark` varchar(255) DEFAULT '',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_preset_status_sort` (`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风格预设';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `system_config`
--

DROP TABLE IF EXISTS `system_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `config_key` varchar(128) NOT NULL,
  `config_value` text,
  `config_group` varchar(64) NOT NULL DEFAULT 'default',
  `remark` varchar(255) DEFAULT '',
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `task`
--

DROP TABLE IF EXISTS `task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `task` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `project_id` bigint unsigned DEFAULT NULL,
  `chapter_id` bigint unsigned DEFAULT NULL,
  `task_type` varchar(32) NOT NULL COMMENT 'SPLIT/SCRIPT/ASSET/SHEET/BATCH/PAGE/LAYOUT/COLORIZE/CLEAN/REPAINT',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0排队中1进行中2成功3失败4部分失败5已停止6停止中',
  `priority` int NOT NULL DEFAULT '0',
  `progress` int NOT NULL DEFAULT '0' COMMENT '0-100',
  `total_count` int NOT NULL DEFAULT '0',
  `success_count` int NOT NULL DEFAULT '0',
  `fail_count` int NOT NULL DEFAULT '0',
  `current_no` int NOT NULL DEFAULT '0' COMMENT '当前处理页/BATCH 断点',
  `payload` json DEFAULT NULL COMMENT '任务参数(colorMode/skipGenerated/pageId/maskUrl/repaintPrompt...)',
  `error` varchar(512) DEFAULT '',
  `result` json DEFAULT NULL COMMENT '任务结果(如 failedPages)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `start_time` datetime DEFAULT NULL,
  `end_time` datetime DEFAULT NULL,
  `heartbeat_time` datetime DEFAULT NULL COMMENT 'Worker心跳',
  `claim_token` varchar(64) DEFAULT NULL COMMENT '任务执行锁(领取时生成,终态校验)',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '看门狗自动重试次数',
  `max_retry_count` int NOT NULL DEFAULT '3' COMMENT '最大自动重试次数',
  `timeout_seconds` int NOT NULL DEFAULT '600' COMMENT '心跳超时阈值(秒),超过判定僵尸',
  `processed_count` int NOT NULL DEFAULT '0' COMMENT '已处理步数(成功+失败)',
  `last_error` text COMMENT '最后一次错误记录',
  `lease_until` datetime DEFAULT NULL COMMENT '执行租约截止',
  `worker_instance_id` varchar(64) DEFAULT NULL COMMENT '执行实例',
  `max_execution_seconds` int NOT NULL DEFAULT '3600' COMMENT '单次Attempt最大执行时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_user` (`user_id`),
  KEY `idx_task_status` (`status`),
  KEY `idx_task_project` (`project_id`),
  KEY `idx_task_watch` (`status`,`heartbeat_time`),
  KEY `idx_task_lease` (`status`,`lease_until`),
  CONSTRAINT `fk_task_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user`
--

DROP TABLE IF EXISTS `user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `password_hash` varchar(100) NOT NULL COMMENT 'BCrypt',
  `role` varchar(16) NOT NULL DEFAULT 'USER' COMMENT 'ADMIN/USER',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-18 11:08:02

