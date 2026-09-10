package com.aimanga.v2.pipeline;

/**
 * 流水线内置默认提示词(可被 system_config 的 prompt_* 覆盖,经 PromptService 渲染)。
 * 与旧版教程规范对齐:对白逐字保留原文、页面顺序与原文一致、无标点空格断句、避免单一上下结构。
 */
public final class PipelinePrompts {

    /** SPLIT:滚动分包分话规划(AI 只返回 endUnit/title/summary,绝不返回原文) */
    public static final String DEFAULT_SPLIT = """
            你是漫画分话规划器。你只负责选择“漫画话”的自然结束边界和生成话标题/摘要。
            系统已经把故事原文按顺序编号为 U0001、U0002...。

            【最重要】
            - 禁止改写、删减、复述、翻译正文。
            - 禁止输出完整原文。
            - 只返回 endUnit、title、summary。
            - endUnit 必须来自输入 Unit 编号，严格递增。
            - 剧情必须按原文顺序。

            【漫画分话目标】
            目标每话约 {target_chars} 字；建议范围 {min_chars}~{max_chars} 字，但自然剧情断点优先。
            优先在以下位置结束一话：
            1. 一个事件阶段完成；
            2. 场景自然切换；
            3. 冲突升级；
            4. 重要信息揭露；
            5. 人物做出关键决定；
            6. 悬念/反转/危险出现，适合作为追读钩子。

            禁止：
            - 切断同一轮连续对白；
            - 切断连续动作；
            - 切在一句话中间；
            - 重排；
            - 跨段拼接；
            - 为了凑字数强制切窗口尾部。

            当前是否最后一包：{is_last_pack}
            若不是最后一包，窗口尾部剧情尚未自然完成时可不返回该尾部边界。
            若是最后一包，最后一个 endUnit 必须是 {last_unit}。

            只返回合法 JSON：
            {"chapters":[{"endUnit":18,"title":"...","summary":"..."}]}

            【上一话上下文】
            {previous_context}

            【当前待规划原文】
            {text}
            """;

    /** METADATA:拆话完成后的独立小请求(仅用标题+分话规划+首尾摘录) */
    public static final String DEFAULT_METADATA = """
            你是漫画内容策划。根据作品的分话规划生成漫画平台所需的元数据。
            【作品标题】{title}
            【分话规划】
            {chapters}
            【开头摘录】
            {head}
            【结尾摘录】
            {tail}
            生成:
            - tagline:20-50 字,一句话卖点;
            - description:80-300 字正式简介,适合漫画详情页,避免直接剧透结局;
            - category:只能从 古风/都市/恋爱/悬疑/科幻/奇幻/热血/搞笑/治愈/校园/其他 中选一个;
            - tags:3-8 个内容标签;
            - seriesStatus:1=连载中 2=已完结,默认 2,仅当内容明确未完结时为 1。
            只返回合法 JSON,不要 Markdown:
            {"tagline":"...","description":"...","category":"...","tags":["..."],"seriesStatus":2}
            """;

    /** SCRIPT:六段式分镜脚本(注入全书标准资产 + 页级 Source Spine 契约) */
    public static final String DEFAULT_STORYBOARD = """
            你是竖版条漫分镜脚本生成器。把本话原文规划为若干漫画页。
            系统已把本话原文按顺序编号为 U0001、U0002...。

            【全书标准资产设定｜最高优先】
            {assets}
            - 已存在于标准资产中的人物,姓名、发型、配饰、服装等不得重新设计;
            - 本话视觉描述引用人物时必须重复/遵循标准设定(衣着特征写入画面描述);
            - 若本话确实出现标准资产中完全没有的新人物,可以在 characters 中报告该人物,但禁止修改已有角色。

            【内容对应】所有页面内容必须严格对应本话原文,按 U 编号顺序逐页覆盖,禁止使用本话之外的情节、结局或台词,禁止提前使用后面页面的剧情信息。
            【制作要求】1. 视觉风格:韩系精致唯美条漫,高清细腻线稿,极细轮廓线,现代插画风格,采用“局部上色”手法:背景保持极简黑白/灰度,仅给人物主体或关键道具赋予鲜明的平涂色彩。2. 版式结构:竖版(3:4)构图,每一页必须使用漫画分镜布局(如斜切分镜、画中画、多格拼贴、破格构图等),避免单一的上下结构。3. 文字处理:旁白用矩形叙述框,对白用气泡;对白尽可能逐字保留原文台词,仅去掉标点、断句用空格分开,禁止改写台词;不用标点符号。
            {style}
            【页面规划】共规划 {page_count} 页(禁止为凑页数复制剧情,也不输出空页):
            - pages 按 page 递增;
            - 每页必须给出 sourceStartUnit 和 sourceEndUnit,表示该页覆盖的连续原文区间;
            - 第 1 页必须从 U0001 开始;下一页的 sourceStartUnit = 上一页 sourceEndUnit + 1;
            - 最后一页必须覆盖到最后一个 Unit;
            - 禁止重复、跳过或倒序 Unit;
            - 每页的旁白/对白/画面只基于自己覆盖的原文区间组织。
            【画面详述规则】每页 visual 必须明确分镜构图形式;描述人物时重复其标准衣着特征;穿插眼神、手部动作等细节传达情感。
            本话原文(已编号):
            {text}
            只返回合法 JSON,不要 Markdown。结构:{"summary":"...","objective":"...","requirements":"...","characters":[{"role":"...","name":"...","age":"...","hair":"...","accessories":"...","top":"...","bottom":"...","description":"..."}],"pages":[{"page":1,"sourceStartUnit":1,"sourceEndUnit":4,"narration":"...","dialogue":[{"speaker":"...","line":"..."}],"visual":"..."}],"tagline":"..."}
            """;

    /** ASSET:四类资产提取(按话分包调用) */
    public static final String DEFAULT_ASSET = """
            你是漫画资产策划。从故事原文中提取制作漫画所需的资产,分四类:assetType 1=角色 2=场景 3=道具 4=服装。
            要求:
            1. 角色:必须给出结构化设定(role 身份/age 年龄段/hair 发型/accessories 配饰/top 上衣颜色款式/bottom 下装颜色款式)与外貌性格描述;
            2. 场景/道具/服装:给出明确可绘制的视觉描述,structured 置为 null;
            3. name 全书唯一;同名角色不同时期可用别名区分;aliases 填常见称呼;
            4. 不要输出与剧情无关的泛化对象;合计不超过 20 个;
            5. 已有资产(不要重复输出,除非补充了明显新信息):{existing}
            只返回合法 JSON,不要 Markdown:
            {"assets":[{"assetType":1,"name":"林夏","aliases":["小夏"],"description":"18岁少年,黑色蓬松短发,银色项链","structured":{"role":"男主","age":"18","hair":"黑色蓬松短发","accessories":"银色项链","top":"白色短袖衬衫","bottom":"深蓝牛仔裤"}}]}
            故事原文:
            {text}
            """;

    private PipelinePrompts() {
    }
}
