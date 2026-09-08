-- AIMangaStudio v2 种子数据
-- 说明:初始管理员账号由后端启动引导创建(见 T2.1 AdminBootstrap),不在此硬编码密码哈希
USE `aimanga_v2`;

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `remark`) VALUES
-- AI 文本通道
('ai_text_api_url', 'https://www.geeknow.top', 'ai_text', '文本 AI 接口地址'),
('ai_text_api_key', '', 'ai_text', '文本 AI API Key(脱敏展示)'),
('ai_text_model', 'deepseek-v4-flash', 'ai_text', '文本模型'),
('ai_text_timeout', '120000', 'ai_text', '超时毫秒'),
('ai_text_concurrency', '15', 'ai_text', '并发上限(Redis 信号量)'),
-- AI 生图通道
('ai_image_api_url', 'https://www.geeknow.top', 'ai_image', '生图 AI 接口地址'),
('ai_image_api_key', '', 'ai_image', '生图 AI API Key(脱敏展示)'),
('ai_image_model', 'gemini-2.5-flash-image-preview', 'ai_image', '生图模型'),
('ai_image_timeout', '600000', 'ai_image', '超时毫秒'),
('ai_image_concurrency', '10', 'ai_image', '并发上限(Redis 信号量)'),
-- AI 编辑/合并通道
('ai_merge_api_url', 'https://www.geeknow.top', 'ai_merge', '编辑 AI 接口地址'),
('ai_merge_api_key', '', 'ai_merge', '编辑 AI API Key(脱敏展示)'),
('ai_merge_model', 'gemini-2.5-flash-image-preview', 'ai_merge', '编辑模型'),
('ai_merge_timeout', '600000', 'ai_merge', '超时毫秒'),
('ai_merge_concurrency', '10', 'ai_merge', '并发上限(Redis 信号量)'),
-- 提示词(留空则使用代码内置默认模板)
('prompt_split', '', 'prompt', '拆话提示词({text})'),
('prompt_script', '', 'prompt', '六段式脚本提示词({text}/{aspect}/{page_count}/{style})'),
('prompt_asset', '', 'prompt', '资产提取提示词({text})'),
('prompt_layout', '', 'prompt', '分镜布局提示词({aspect}/{characters}/{text})'),
('prompt_page', '', 'prompt', '漫画页生成提示词({color_mode}/{characters}/{style}/{aspect}/{text})'),
('prompt_colorize', '', 'prompt', '上色提示词'),
('prompt_clean', '', 'prompt', '清晰化提示词'),
('prompt_repaint', '', 'prompt', '局部重绘提示词({prompt})'),
-- 任务并发(四层)
('task_max_concurrency', '5', 'task', '全局并行任务数(Worker 线程数)'),
('task_user_concurrency', '2', 'task', '每用户并行任务数(Redis 信号量)'),
('task_page_concurrency', '5', 'task', '单任务内页级并发'),
('storyboard_page_count', '10', 'task', '六段式脚本默认页数'),
-- 功能开关
('feature_auto_split', '1', 'feature', '作品创建后自动拆话'),
('feature_auto_asset', '1', 'feature', '脚本完成后自动提取资产'),
('feature_auto_sheet', '1', 'feature', '资产提取后自动生成角色设定表'),
-- OSS
('oss_access_key', '', 'oss', '阿里云 AccessKeyId(脱敏展示)'),
('oss_access_secret', '', 'oss', '阿里云 AccessKeySecret(脱敏展示)'),
('oss_bucket', '', 'oss', 'Bucket 名称'),
('oss_region', 'oss-cn-hangzhou', 'oss', '地域'),
('oss_endpoint', '', 'oss', '自定义 endpoint(可空)'),
('oss_image_process', 'x-oss-process=image/resize,w_600', 'oss', '缩略图处理参数(可空)')
ON DUPLICATE KEY UPDATE `config_value` = `config_value`;

-- 风格预设
INSERT INTO `style_preset` (`name`, `positive_prompt`, `negative_prompt`, `color_mode`, `sort`, `status`, `remark`) VALUES
('韩系唯美·局部上色', '韩系精致唯美条漫，高清细腻线稿，极细轮廓线，现代插画风格。采用局部上色手法：背景保持极简黑白/灰度，仅给人物主体或关键道具赋予鲜明的平涂色彩。强调画面的细腻感和唯美氛围。', '文字，水印，低质量，变形', 'partial', 1, 1, '默认：教程风格'),
('日漫黑白', '经典日式黑白漫画，清晰干净的线条，使用网点与排线表现光影，人物造型简洁有辨识度，强调表情与动态。', '文字，水印，低质量，彩色', 'monochrome', 2, 1, '黑白传统'),
('全彩现代', '现代全彩漫画，色彩鲜明饱满，光影层次丰富，人物与背景均完整上色，画面通透有氛围感。', '文字，水印，低质量，灰度', 'color', 3, 1, '全彩')
ON DUPLICATE KEY UPDATE `name` = `name`;
