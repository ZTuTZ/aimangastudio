-- Non-secret, idempotent defaults. Existing operator values are never overwritten.
INSERT INTO system_config (config_key, config_value, config_group, remark) VALUES
('task_max_concurrency', '5', 'task', '全局任务Worker数量,1-64'),
('task_user_concurrency', '2', 'task', '每用户并发任务数,1-20'),
('task_max_execution_seconds', '3600', 'task', '单次Attempt最大执行秒数,60-86400'),
('task_lease_seconds', '90', 'task', '任务租约秒数,30-600'),
('task_heartbeat_interval_seconds', '30', 'task', '心跳间隔秒数,1-30且小于租约'),
('task_heartbeat_threads', '2', 'task', '心跳线程数,1-8'),
('task_permit_ttl_seconds', '120', 'task', '用户并发许可续租TTL秒数,30-600'),
('task_page_concurrency', '5', 'task', '单任务页并发,1-20'),
('image_generation_concurrency', '5', 'task', '图像Stage并发,1-20'),
('image_generation_max_retry', '3', 'task', '图像单元最多重试次数,0-10'),
('script_generation_concurrency', '2', 'task', '脚本Stage并发,1-10'),
('script_generation_max_retry', '1', 'task', '脚本单元最多重试次数,0-10'),
('storyboard_page_count', '10', 'task', '六段式脚本默认页数'),
('page_direct_output', '0', 'task', '1=跳过布局阶段直接生成成品页'),
('page_generation_asset_gate', '1', 'task', '1=缺必需角色素材时阻止出图'),
('page_reference_max_images', '8', 'task', '单页最大参考图数量'),
('remote_image_max_bytes', '31457280', 'security', '远程图片最大字节数'),
('remote_image_allow_hosts', '', 'security', '远程图片允许域名,逗号分隔;空为仅IP安全校验'),
('export_max_projects', '50', 'export', '单个导出任务最大作品数,1-200'),
('export_max_bytes', '1073741824', 'export', '单个批量导出包最大字节数'),
('export_artifact_ttl_hours', '168', 'export', '导出产物保留小时数'),
('split_pack_max_chars', '8000', 'pipeline', '拆话滚动分包单包最大字数'),
('split_target_chars_per_page', '60', 'pipeline', '拆话目标每页原文字数'),
('split_min_chars_per_page', '35', 'pipeline', '拆话目标每页最少字数'),
('split_max_chars_per_page', '90', 'pipeline', '拆话目标每页最多字数'),
('asset_pack_max_chapters', '5', 'pipeline', '资产提取每包最多话数'),
('asset_pack_max_chars', '12000', 'pipeline', '资产提取每包最大字数'),
('asset_pack_concurrency', '2', 'pipeline', '资产提取单任务内分包并发'),
('script_asset_context_max', '30', 'pipeline', 'SCRIPT注入标准资产上下文上限'),
('feature_auto_split', '1', 'feature', '作品创建后自动拆话'),
('feature_auto_asset', '1', 'feature', '脚本完成后自动提取资产'),
('feature_auto_sheet', '0', 'feature', '自动生成全部角色设定表'),
('prompt_split', '', 'prompt', '拆话提示词({text})'),
('prompt_script', '', 'prompt', '六段式脚本提示词({text}/{aspect}/{page_count}/{style})'),
('prompt_asset', '', 'prompt', '资产提取提示词({text})'),
('prompt_layout', '', 'prompt', '分镜布局提示词({aspect}/{characters}/{text})'),
('prompt_page', '', 'prompt', '漫画页生成提示词({color_mode}/{characters}/{style}/{aspect}/{text})'),
('prompt_colorize', '', 'prompt', '上色提示词'),
('prompt_clean', '', 'prompt', '清晰化提示词'),
('prompt_repaint', '', 'prompt', '局部重绘提示词({prompt})'),
('ai_text_api_url', '', 'ai_text', '文本AI地址,首次使用前配置'),
('ai_text_api_key', '', 'ai_text', '文本AI密钥'),
('ai_text_model', '', 'ai_text', '文本模型'),
('ai_text_timeout', '120000', 'ai_text', '文本AI总超时毫秒'),
('ai_text_concurrency', '10', 'ai_text', '文本AI并发,1-100'),
('ai_text_protocol', 'gemini', 'ai_text', 'gemini或openai'),
('ai_image_api_url', '', 'ai_image', '生图AI地址,首次使用前配置'),
('ai_image_api_key', '', 'ai_image', '生图AI密钥'),
('ai_image_model', '', 'ai_image', '生图模型'),
('ai_image_timeout', '600000', 'ai_image', '生图AI总超时毫秒'),
('ai_image_concurrency', '10', 'ai_image', '生图AI并发,1-100'),
('ai_image_protocol', 'gemini', 'ai_image', 'gemini或openai'),
('ai_merge_api_url', '', 'ai_merge', '编辑AI地址,首次使用前配置'),
('ai_merge_api_key', '', 'ai_merge', '编辑AI密钥'),
('ai_merge_model', '', 'ai_merge', '编辑模型'),
('ai_merge_timeout', '600000', 'ai_merge', '编辑AI总超时毫秒'),
('ai_merge_concurrency', '10', 'ai_merge', '编辑AI并发,1-100'),
('ai_merge_protocol', 'gemini', 'ai_merge', 'gemini或openai'),
('oss_access_key', '', 'oss', 'OSS AccessKeyId'),
('oss_access_secret', '', 'oss', 'OSS AccessKeySecret'),
('oss_bucket', '', 'oss', 'OSS Bucket'),
('oss_region', 'oss-cn-hangzhou', 'oss', 'OSS地域'),
('oss_endpoint', '', 'oss', 'OSS自定义endpoint'),
('oss_image_process', 'x-oss-process=image/resize,w_600', 'oss', '缩略图处理参数')
ON DUPLICATE KEY UPDATE config_key = VALUES(config_key);

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'style_preset' AND COLUMN_NAME = 'preset_key');
SET @sql := IF(@exist = 0, 'ALTER TABLE style_preset ADD COLUMN preset_key VARCHAR(64) NULL, ADD UNIQUE KEY uk_style_preset_key (preset_key)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE style_preset SET preset_key = 'default-korean-partial' WHERE preset_key IS NULL AND name = '韩系唯美·局部上色' LIMIT 1;
UPDATE style_preset SET preset_key = 'default-manga-mono' WHERE preset_key IS NULL AND name = '日漫黑白' LIMIT 1;
UPDATE style_preset SET preset_key = 'default-modern-color' WHERE preset_key IS NULL AND name = '全彩现代' LIMIT 1;

INSERT INTO style_preset (preset_key, name, positive_prompt, negative_prompt, color_mode, sort, status, remark) VALUES
('default-korean-partial', '韩系唯美·局部上色', '韩系精致唯美条漫,高清细腻线稿,现代插画,背景黑白灰度,主体关键色局部上色', '文字,水印,低质量,变形', 'partial', 1, 1, '系统默认'),
('default-manga-mono', '日漫黑白', '经典日式黑白漫画,清晰线条,网点与排线光影,表情和动态明确', '文字,水印,低质量,彩色', 'monochrome', 2, 1, '系统默认'),
('default-modern-color', '全彩现代', '现代全彩漫画,色彩鲜明,光影层次丰富,人物与背景完整上色', '文字,水印,低质量,灰度', 'color', 3, 1, '系统默认')
ON DUPLICATE KEY UPDATE preset_key = VALUES(preset_key);
