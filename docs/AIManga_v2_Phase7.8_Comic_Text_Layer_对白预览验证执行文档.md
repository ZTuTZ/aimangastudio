# AIManga v2 Phase 7.8
# Comic Text Layer & Dialogue Preview 动态对白层预览验证执行文档

## 1. Phase目标

当前系统已经能够完成漫画内容生产，但对白/旁白的最终渲染计划是在未来 APP / 小程序客户端完成。

本 Phase 的目的不是把文字烧进漫画成品图，而是在当前 Web 系统中提前验证：

1. `dialogue / narration` 数据是否足够支撑最终阅读；
2. 动态气泡/旁白框的布局协议是否可行；
3. 同一份布局数据能否适配不同屏幕尺寸；
4. 当前漫画成品图是否给对白留出了合理空间；
5. 未来 APP / 小程序是否可以基于同一套协议实现；
6. 用户是否可以手动微调气泡并保存；
7. 后续是否具备增加 AI 自动气泡布局的基础。

核心原则：

> 漫画底图和文本层必须解耦。

禁止为了预览把对白重新合成到 OSS 成品图。

---

## 2. 最终目标结构

每一页漫画由两层组成：

```text
Page
 ├─ Image Layer
 │    generated_image_url
 │
 └─ Text Layer
      Dialogue
      Narration
      Thought
      SFX（预留）
```

Web 预览：

```text
漫画图片
+
动态文字 / 气泡 Overlay
```

未来 APP / 小程序：

```text
漫画图片
+
读取同一套 Text Layer Schema
+
客户端渲染
```

当前 Web 预览器必须验证的是“数据协议”，而不是某一个浏览器的最终视觉实现。

---

## 3. 不修改原有 dialogue / narration 语义职责

当前 `page.dialogue` / `page.narration` 继续保留。

它们负责表达：

```text
谁说了什么
旁白是什么
```

例如：

```json
[
  {
    "speaker": "林凡",
    "line": "这里是什么地方？"
  },
  {
    "speaker": "苏雨",
    "line": "你终于醒了。"
  }
]
```

不要把视觉坐标直接塞进现有 dialogue JSON。

原因：

```text
剧情语义 != 视觉排版
```

新增独立文本布局层。

---

## 4. 新增 page_text_element 表

推荐使用独立表，而不是整个页面塞一个不可查询的大 JSON。

```sql
CREATE TABLE page_text_element (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    project_id BIGINT UNSIGNED NOT NULL,
    chapter_id BIGINT UNSIGNED NOT NULL,
    page_id BIGINT UNSIGNED NOT NULL,

    element_uid VARCHAR(64) NOT NULL,

    element_type VARCHAR(32) NOT NULL COMMENT 'DIALOGUE/NARRATION/THOUGHT/SFX',

    dialogue_index INT DEFAULT NULL COMMENT '对应page.dialogue数组索引，旁白可为空',

    speaker VARCHAR(128) DEFAULT NULL,
    text_content TEXT NOT NULL,

    x DECIMAL(8,6) NOT NULL,
    y DECIMAL(8,6) NOT NULL,
    width DECIMAL(8,6) NOT NULL,
    height DECIMAL(8,6) DEFAULT NULL,

    tail_x DECIMAL(8,6) DEFAULT NULL,
    tail_y DECIMAL(8,6) DEFAULT NULL,

    bubble_style VARCHAR(32) NOT NULL DEFAULT 'DEFAULT_DIALOGUE',
    font_style VARCHAR(32) NOT NULL DEFAULT 'DEFAULT_DIALOGUE',

    font_size_ratio DECIMAL(8,6) NOT NULL DEFAULT 0.028000,
    text_align VARCHAR(16) NOT NULL DEFAULT 'CENTER',

    max_lines INT DEFAULT NULL,
    sort_order INT NOT NULL DEFAULT 0,

    source_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL'
      COMMENT 'AUTO/MANUAL/AI',

    version INT NOT NULL DEFAULT 1,

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_page_text_uid (element_uid),
    KEY idx_page_text_page (page_id),
    KEY idx_page_text_project (project_id),

    CONSTRAINT fk_page_text_page
      FOREIGN KEY (page_id) REFERENCES page(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

如果当前项目命名风格不同，本地 Agent 可调整表名/字段名，但语义必须保持一致。

---

## 5. 坐标必须使用归一化坐标

禁止保存：

```text
x = 638px
y = 320px
```

统一保存：

```text
0.0 ~ 1.0
```

例如：

```json
{
  "x": 0.62,
  "y": 0.14,
  "width": 0.25,
  "height": 0.11
}
```

含义：

```text
x      = 元素左上角相对图片宽度
y      = 元素左上角相对图片高度
width  = 元素宽度相对图片宽度
height = 建议高度相对图片高度
```

渲染：

```text
realX = renderWidth  * x
realY = renderHeight * y
realW = renderWidth  * width
realH = renderHeight * height
```

---

## 6. height 不作为跨端绝对约束

Web / Android / iOS / 小程序字体栅格化可能不同。

同一句话可能出现：

```text
Web       = 3行
Android   = 4行
iOS       = 3行
```

因此 `height` 仅作为建议布局框。

真正客户端应根据：

```text
width
font_size_ratio
font_style
max_lines
```

重新计算文本高度。

如果重新计算后超过建议区域：

1. 优先轻微缩小字号；
2. 其次扩展高度；
3. 最后提示布局异常。

不能直接截断正文。

---

## 7. 字号也使用比例值

不要保存：

```text
font-size: 24px
```

保存：

```text
font_size_ratio
```

例如：

```text
0.028
```

建议统一公式：

```text
fontSize = min(renderWidth, renderHeight) * font_size_ratio
```

Web 与未来 APP / 小程序必须采用同一规则。

---

## 8. 文本类型

第一版支持：

```text
DIALOGUE
NARRATION
THOUGHT
```

预留：

```text
SFX
```

### DIALOGUE
普通人物对白，支持 speaker、bubble、tail。

### NARRATION
旁白，默认无 tail，矩形 / 半透明框。

### THOUGHT
心理活动。第一版可只做不同 preset，不要求复杂云朵气泡。

### SFX
拟声词暂不实现复杂艺术字，只预留协议。

---

## 9. Style Preset 不绑定具体本地字体

不要保存具体 Mac / Windows 本地字体名。

核心数据保存：

```text
DEFAULT_DIALOGUE
DEFAULT_NARRATION
DEFAULT_THOUGHT
EMPHASIS
SFX
```

Web / APP / 小程序各自映射到可控字体实现。

---

## 10. 第一版 Bubble Preset

第一版只需要：

```text
DEFAULT_DIALOGUE
NARRATION_BOX
THOUGHT_SIMPLE
```

### DEFAULT_DIALOGUE
- 白底
- 深色边框
- 圆角
- 支持 tail
- 文本居中

### NARRATION_BOX
- 矩形框
- 无 tail
- 文本左对齐或居中

### THOUGHT_SIMPLE
- 可先使用圆角框
- 视觉区别于普通对白即可

复杂爆炸气泡、锯齿气泡、云朵气泡、多尾巴、艺术字暂不实现。

---

## 11. Tail 数据

对白尾巴使用：

```text
tail_x
tail_y
```

同样为归一化坐标。

表示：

```text
尾巴指向人物的位置
```

第一版可用简单三角形或 SVG path 实现。

重点是验证协议能够表达“这个气泡属于哪个角色”。

---

## 12. Safe Area

Text Element 默认建议位于：

```text
3% ~ 97%
```

安全范围内。

推荐：

```text
x >= 0.03
y >= 0.03
x + width  <= 0.97
y + height <= 0.97
```

如果用户拖出 Safe Area：

前端显示 warning。

第一版可允许保存，但必须提示：

```text
该文本框可能在部分客户端被裁切
```

---

## 13. Comic Text Layer Schema v1

后端提供统一 DTO。

```json
{
  "schemaVersion": "comic-text-layer-1.0",
  "pageId": 123,
  "pageVersion": 3,

  "elements": [
    {
      "uid": "TXT_01",
      "type": "DIALOGUE",
      "dialogueIndex": 0,

      "speaker": "林凡",
      "text": "这里是什么地方？",

      "position": {
        "x": 0.61,
        "y": 0.12,
        "width": 0.25,
        "height": 0.11
      },

      "style": {
        "fontPreset": "DEFAULT_DIALOGUE",
        "fontSizeRatio": 0.028,
        "align": "CENTER",
        "maxLines": 4
      },

      "bubble": {
        "preset": "DEFAULT_DIALOGUE",
        "tail": {
          "x": 0.55,
          "y": 0.33
        }
      },

      "sortOrder": 1
    }
  ]
}
```

未来 APP / 小程序必须能够直接消费该结构。

---

## 14. 新增 TextLayerService

新增：

```java
TextLayerService
```

职责：

```text
getTextLayer(pageId)
initializeFromPage(pageId)
saveTextLayer(pageId, request)
resetTextLayer(pageId)
syncFromPageContent(pageId)
```

---

## 15. initializeFromPage

第一次打开某个 Page 且没有 text elements：

从：

```text
page.dialogue
page.narration
```

生成默认 Text Elements。

例如：

```text
2条dialogue
1条narration
```

生成 3 个 `page_text_element`。

初始位置可采用规则布局：

```text
DIALOGUE 1 → 左上
DIALOGUE 2 → 右上
NARRATION   → 底部
```

第一版只要求可用。

不要在第一阶段调用视觉 AI 自动布局。

---

## 16. syncFromPageContent

如果用户后续修改：

```text
page.dialogue
page.narration
```

需要处理文本层同步。

### 已存在对应 dialogueIndex

更新：

```text
speaker
text_content
```

保留：

```text
x/y/width/style
```

### 新增 dialogue

创建新 element。

### 删除 dialogue

删除或失效对应 element。

### narration 修改

更新对应 narration element 文本。

---

## 17. Page 脚本版本与 Text Layer 版本

如果项目已经实现：

```text
page.script_version
```

则 Text Layer 保存对应 source script version。

推荐增加：

```sql
ALTER TABLE page
ADD COLUMN text_layout_version INT NOT NULL DEFAULT 0;
```

如果：

```text
script_version > text_layout_source_version
```

前端显示：

```text
脚本文本已变更，当前对白布局可能需要同步
```

提供：

```text
[同步文本，保留布局]
```

不要自动重置用户已编辑好的布局。

---

## 18. 后端 API

### 获取

```http
GET /api/pages/{pageId}/text-layer
```

### 初始化

```http
POST /api/pages/{pageId}/text-layer/initialize
```

### 保存

```http
PUT /api/pages/{pageId}/text-layer
```

### 重置

```http
POST /api/pages/{pageId}/text-layer/reset
```

### 同步 page 文本

```http
POST /api/pages/{pageId}/text-layer/sync
```

同步 `dialogue / narration`，但尽量保留已有位置。

---

## 19. 前端 Page Dialogue Editor

在当前 PageDetail 中新增：

```text
Text Layer / 对白预览
```

建议布局：

```text
┌──────────────────────────────────────────────┐
│ Page Detail                                  │
├──────────────┬───────────────────────────────┤
│ 文本元素列表  │         漫画预览区域           │
│              │                               │
│ 林凡          │    [动态气泡]                 │
│ 这里是哪？    │                               │
│              │                     [气泡]     │
│ 苏雨          │                               │
│ 你醒了        │                               │
│              │                               │
│ 旁白          │  [旁白框]                    │
│ 三年前……      │                               │
├──────────────┴───────────────────────────────┤
│ 手机预览 | 小屏 | 中屏 | 大屏                 │
└──────────────────────────────────────────────┘
```

---

## 20. 编辑能力

第一版必须支持：

```text
拖动
缩放
编辑文字
调整字号
切换气泡 preset
文字对齐
拖动 tail point
新增文本框
删除文本框
恢复默认
保存
```

暂时不做旋转。

---

## 21. 推荐渲染技术

当前 Web 第一版优先：

```text
HTML / DOM Overlay
+
SVG tail
```

而不是 Canvas。

原因：

- 中文文本排版容易；
- 拖拽编辑简单；
- 调试方便；
- 浏览器 resize 适配直接；
- 后续与 APP 原生渲染器更容易对照。

---

## 22. 不允许保存固定像素定位

前端显示阶段可以用 px。

保存前必须转换成 normalized coordinates。

```text
normalizedX = pxX / imageDisplayWidth
normalizedY = pxY / imageDisplayHeight
```

页面尺寸变化时再反向计算。

---

## 23. Cross-device Preview

必须加入模拟尺寸，至少：

```text
375 × 667
390 × 844
430 × 932
768 × 1024
```

切换尺寸时：

- 只改变 viewport；
- 不修改数据库；
- 使用同一套归一化布局重算位置。

---

## 24. Warning Validator

第一版至少检测：

### 文本溢出
文字超出 bubble。

### Safe Area
文本框越界。

### 重叠
两个文本框严重重叠。

### 尺寸过小
气泡无法容纳合理字号。

### 空文本
存在空 element。

### speaker 异常
dialogue element 的 speaker 与当前项目角色资产不一致。

这些先作为 warning，不阻止保存。

---

## 25. 真实漫画验证

至少选择 10~20 页真实生成漫画测试：

### 少对白页
1~2 条对白。

### 中对白页
3~5 条对白。

### 多对白页
6+ 条对白。

### 多角色页
2~4 人。

### 长旁白页
较长 narration。

记录：

```text
能否找到合理空白区
是否遮脸
是否遮动作
是否频繁需要缩小字号
是否有页面天然不适合动态气泡
```

---

## 26. 如果图片没有对白空间

不要用“强塞气泡”解决。

应回到 `PagePromptCompiler`，在验证确实存在问题后增加例如：

```text
根据当前页对白数量预留自然负空间；
人物头部不要紧贴页面边缘；
对白较多时在人物附近保留干净背景区域；
避免关键动作、脸部、手部占满所有留白区域。
```

只有真实测试证明必要后再改 Prompt。

---

## 27. 第一阶段不做 AI 自动气泡布局

第一阶段先验证：

```text
手工 Text Layer Schema
+
Web 动态渲染
+
跨尺寸适配
```

暂时不要调用视觉模型自动返回坐标。

否则难以区分：

```text
协议问题
渲染问题
AI布局问题
```

---

## 28. 第二阶段可选：AutoTextLayoutService

第一阶段稳定后可增加：

```text
AutoTextLayoutService
```

输入：

```text
generated_image_url
dialogue
narration
角色信息
```

AI 只允许返回：

```text
x
y
width
tail point
```

禁止修改对白文本。

结果：

```text
AI自动排版
↓
用户人工微调
↓
保存
```

---

## 29. 与未来 APP 的边界

Web 实现不是未来 APP 源码。

真正需要继承的是：

```text
Comic Text Layer Schema v1
坐标规则
字号规则
style preset语义
tail语义
Safe Area规则
```

未来 iOS / Android / 微信小程序各自实现渲染器，但必须使用同一份 Schema 测试数据。

---

## 30. 增加 Text Layer Fixture

新增：

```text
test-fixtures/
comic-text-layer-v1/
```

至少准备：

```text
dialogue-single.json
dialogue-multi.json
narration.json
long-text.json
multi-character.json
edge-position.json
```

未来 APP 开发时直接用这些 Fixture 做兼容测试。

---

## 31. Comic Package 导出兼容

未来 Page manifest 可增加：

```json
{
  "pageNo": 1,
  "image": "...",
  "textLayer": {
    "schemaVersion": "comic-text-layer-1.0",
    "elements": []
  }
}
```

如果某页没有 Text Layer：

```json
"textLayer": null
```

不能影响旧漫画阅读。

---

## 32. 兼容旧数据

已经生成的漫画没有 `page_text_element` 时仍必须正常打开。

第一次进入 Text Layer Editor：

```text
检测为空
↓
initializeFromPage()
↓
生成默认布局
```

不重新生成漫画图片。

---

## 33. 验收测试

### 测试1：单对白
初始化、拖动、保存、刷新后位置不变。

### 测试2：多对白
一页 5 条对白，5 个独立元素，可单独调整。

### 测试3：旁白
dialogue 为空、narration 存在时可生成 narration box。

### 测试4：文本修改同步
修改 `page.dialogue` 后执行 sync，文字更新但位置基本保持。

### 测试5：不同屏幕
在 375×667 / 390×844 / 430×932 / 768×1024 下相对位置稳定。

### 测试6：服务重启
保存后重启服务，完整恢复。

### 测试7：长文本
单条 40~80 个中文字符，验证换行、maxLines 和 overflow warning。

### 测试8：发布包
导出 Comic Package，检查 `comic-text-layer-1.0` 数据完整。

---

## 34. Phase Gate

完成第一阶段后必须能回答：

1. 动态文本层是否可以稳定覆盖现有漫画？
2. 不同设备尺寸下相对位置是否稳定？
3. 长对白是否可接受？
4. 大部分成品图是否存在足够对白留白？
5. 是否需要修改 PagePromptCompiler 预留负空间？
6. 现有 dialogue / narration 数据是否足够？
7. 是否值得进入 AI 自动布局阶段？

只有有明确结论后，才决定未来 APP 的最终实现方式。

---

## 35. 本地 Agent 执行顺序

```text
T7.8.1
数据库 + Entity + Mapper

↓

T7.8.2
Comic Text Layer DTO / Schema

↓

T7.8.3
TextLayerService

↓

T7.8.4
初始化 / 同步 / 保存 API

↓

T7.8.5
PageDetail Text Layer Editor

↓

T7.8.6
Drag / Resize / Tail

↓

T7.8.7
Cross-device Preview

↓

T7.8.8
Warning Validator

↓

T7.8.9
Comic Package 导出兼容

↓

T7.8.10
Fixture + 验收测试
```

---

## 36. 禁止事项

本 Phase 禁止：

1. 把对白烧进 `generated_image_url`；
2. 为了预览重新生成漫画底图；
3. 保存固定 px 坐标；
4. 把某个 Web 本地字体名称写入核心数据；
5. 删除原有 `page.dialogue / narration`；
6. 第一阶段就接 AI 自动气泡布局；
7. 为 Text Layer 再创建一套 Task/Pipeline；
8. 编辑文本布局时重新执行 IMAGE Stage；
9. 修改成功成品图 URL；
10. 破坏旧 Comic Package 兼容性。

---

## 37. 最终预期成果

完成后：

```text
漫画成品图
   +
动态 Text Layer
   ↓
Web真实预览
   ↓
拖拽/缩放/调整
   ↓
归一化数据保存
   ↓
多尺寸模拟
   ↓
Comic Package 导出
   ↓
未来 APP 直接复用协议
```

最终要验证的是：

> “AI 负责生成无文字漫画图，APP / 小程序负责动态对白与旁白渲染”

是否是一条稳定、可跨端、可规模化使用的技术路线。
