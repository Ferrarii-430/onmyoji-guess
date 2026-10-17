# OnmyojiGuess - 阴阳师对弈竞猜采集与统计

自动采集网易大神（ds.163.com）上竞猜博主的对弈竞猜动态，解析出每位用户在每一局的押注方向（左红 / 右蓝），提供胜率统计与可视化看板。

### 在每年对弈竞猜开启时，可通过以下地址预览本项目
**https://frp-hat.com:55740/**

## 功能特性

- **脚本联动全自动录入**：配合 [onmyoji-script](https://github.com/Ferrarii-430/onmyoji-script) 脚本，使用其 **"系统方案 -- 对弈竞猜采集"**，即可实现**对局式神数据**及**竞猜结果**的全自动识别与录入（自动上报至本系统），无需任何人工操作
- **定时采集**：按竞猜场次感知调度，每局最后一个小时的 4 个刻度抓取 13 位博主的最新动态，每天都会执行。
- **本地规则优先**：正则解析 `HH:MM` / `第N场` + `左(红)` / `右(蓝)`，绝大多数帖子无需 AI
- **AI 兜底判定**：仅对本地无法判定且含 红/蓝/左/右 关键字的候选帖调用 AI，默认使用 **StartLux-Decision** 本地决策模型，可切换 DeepSeek 云端模式
  - **StartLux-Decision**（本地，默认）：类型化概率决策模型，单帖毫秒级推理，置信度阈值过滤。**需自行部署本地推理服务**（见 [鸣谢](#鸣谢)）
  - **DeepSeek**（V4.1-Flash，云端，可选）：OpenAI 兼容协议，10 帖/批，分步判定提示词
- **去重入库**：`feed` 表按 `feed_id` 唯一去重，`guess_record` 按 `(feed_id, round_start)` 唯一去重，AI 判定结果随 `feed.is_guess` 持久化（每个 feed 最多送 AI 一次，重启不丢）
- **赛果管理**：自动从"上局 右(蓝)竞猜成功"类赛果帖推断上一局胜方（AUTO 首条生效），支持手动录入修正（MANUAL 覆盖）
- **统计看板**：当前对局押红/押蓝人数、每位用户押注与猜中情况、用户历史胜率排名，30 秒自动刷新

## 技术栈

| 组件 | 版本/说明 |
|---|---|
| Java | 17 |
| Spring Boot | 4.1.1（spring-webmvc + spring-jdbc） |
| MySQL | 8.0 |
| Spring AI | 2.0.1（`spring-ai-starter-model-openai`，OpenAI 兼容协议接入 DeepSeek） |
| fastjson2 | 2.0.53（JSON 解析与请求体序列化） |
| Lombok | 注解简化 |

## 快速开始

### 环境要求

1. **JDK 17**（注意：`JAVA_HOME` 需指向 JDK 17，否则 text block 语法编译报错）
2. **MySQL 8.0**，应用启动时自动建库建表（`createDatabaseIfNotExist=true` + 幂等 `init.sql`）
3. （仅 DeepSeek 模式）环境变量 `DEEPSEEK_API_KEY`
4. （StartLux 模式，默认）**需自行部署** StartLux-Decision 本地推理服务，部署后将其地址填入 `app.ai.startlux.base-url`（详见 [鸣谢](#鸣谢)）

### 配置

编辑 [application.properties](src/main/resources/application.properties)：

```properties
# MySQL 连接与密码
spring.datasource.url=jdbc:mysql://localhost:3306/guess?...
spring.datasource.password=你的密码

# AI 提供方: startlux(默认) | deepseek(可选)
app.ai.provider=startlux

# DeepSeek (切回 deepseek 模式前需设置环境变量)
# setx DEEPSEEK_API_KEY "sk-xxx"
spring.ai.openai.api-key=${DEEPSEEK_API_KEY:}

# StartLux-Decision 本地决策模型
app.ai.startlux.base-url=http://192.168.1.46:8090
app.ai.startlux.min-confidence=0.55
```

### 运行

```powershell
$env:JAVA_HOME='D:\jdk-17'
.\mvnw.cmd spring-boot:run
```

启动后访问 <http://localhost:8080/> 打开看板。

## 采集调度

竞猜周期为每天 **10:00~24:00，每 2 小时一局**（共 7 局）。押注帖一般在对局开始一段时间后才发布，开局瞬间抓取大概率没有数据，因此只在**每局最后一个小时的 4 个刻度**抓取：

| 场次 | 抓取时刻 |
|---|---|
| 10 点场 | 11:00 / 11:15 / 11:30 / 11:45 |
| 12 点场 | 13:00 / 13:15 / 13:30 / 13:45 |
| …… | …… |
| 22 点场 | 23:00 / 23:15 / 23:30 / 23:45 |

cron：`0 0,15,30,45 11,13,15,17,19,21,23 * * *`（zone=Asia/Shanghai）。抓取用户列表在 [Config.java](src/main/java/com/example/onmyojiguess/config/Config.java) 的 `USER_KEY_DS` 中维护（key=昵称，value=uid）。

## 判定链路

```
抓取 (3~6s 随机间隔)
  ├─ 本地规则命中 (时间/第N场 + 左(红)/右(蓝) 同行) → 直接入库 guess_record
  ├─ 不含 红/蓝/左/右 任一关键字                    → 标记无关, 不进 AI
  └─ 含关键字但规则无法确定 (is_guess=NULL)          → 查库去重后送 AI
                                                      ↓
                       AI 判定 (deepseek 批量 10帖/批 | startlux 单帖+500ms间隔)
                                                      ↓
                       feed.is_guess 固化 + guess_record 落库
```

- 顺带解析"上局 右(蓝)竞猜成功/失败"赛果帖，自动推断上一局胜方写入 `round_result`（AUTO，已有记录不覆盖）
- AI 请求失败的 feed 保持未判定状态，下一轮自动重试

## 数据库表

| 表 | 说明 | 去重键 |
|---|---|---|
| `feed` | 大神动态原始数据（feed_id、正文、is_guess 三态：1有效竞猜 / 0无关 / NULL待AI） | `feed_id` |
| `guess_record` | 押注记录（用户、场次 1~7、方向 RED/BLUE、原文匹配行） | `(feed_id, round_start)` |
| `round_result` | 对局赛果（胜方，source=MANUAL/AUTO） | `(round_date, round_no)` |
| `guess_shikigami` | 对局双方式神 OCR 数据（属性按 OCR 原样字符串保存） | `(round_date, round_no, side, no)` |

建表脚本：[db/init.sql](src/main/resources/db/init.sql)（幂等，启动时自动执行）。

## API

### `GET /api/guess/current`

当前最新对局的竞猜数据（服务端 15 秒 TTL 缓存，无需传参）：

```json
{
  "round": { "date": "2026-10-13", "roundNo": 3, "roundStart": "2026-10-13T14:00",
             "roundEnd": "2026-10-13T16:00", "status": "BETTING", "winSide": null,
             "redCount": 2, "blueCount": 1 },
  "bets": [ { "userName": "面灵气喵", "betSide": "RED", "betSideText": "左(红)",
              "rawLine": "14:00 左(红)", "source": "POST", "win": null,
              "content": "动态原文…", "postTime": "..." } ],
  "userWinRates": [ { "userName": "面灵气喵", "total": 12, "win": 9, "winRate": 0.75 } ]
}
```

- 对局定位：凌晨（0~10 点）返回昨日第 7 场；11 点仍属 10 点场
- `win`：true 猜中 / false 未中 / null 尚无赛果；胜率仅统计已出赛果的对局

### `POST /api/guess/round/result?roundDate=2026-10-13&roundNo=3&winSide=RED`

手动录入/修正某局胜方（MANUAL，覆盖 AUTO 推断），用于胜率统计兜底。`winSide` 取 `RED` / `BLUE`。

### `POST /api/guess/shikigami?roundDate=2026-10-13&roundNo=3&red=[...]&blue=[...]`

上报一局的双方式神 OCR 数据（每方最多 5 个座位，重复上报以最后一次为准）。数组元素示例：

```json
[{"no":1,"name":"千姬","attack":"2800","hp":"3200","defense":"200",
  "speed":"145","critRate":"30%","critDamage":"150%","effectHit":"0","effectResist":"0","mitama":"招财"}]
```

### `GET /api/guess/shikigami/current`

当前对局双方式神数据（红/蓝两组列表）。

## Web 看板

访问 <http://localhost:8080/> ，包含：

- 对局信息卡（场次、状态、赛果、押红 vs 押蓝人数）
- 当局用户竞猜列表（方向徽章、原文匹配行、判定来源、猜中标记）
- 当局竞猜动态原文（命中行按红/蓝高亮）
- 用户历史胜率表（进度条排名）

纯静态单文件页面（原生 JS + 内联 CSS），每 30 秒自动刷新，数据滞后最大 15 秒（服务端缓存 TTL）。

## 配置参考

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `app.fetch.run-on-startup` | `false` | 应用启动完成后是否立即执行一次采集 |
| `app.ai.provider` | `startlux` | AI 提供方：`startlux` / `deepseek` |
| `app.ai.max-text-len` | `3000` | 送入 AI 的正文截断上限（字符），全局通用 |
| `app.ai.startlux.base-url` | `http://192.168.1.46:8090` | StartLux 服务地址 |
| `app.ai.startlux.min-confidence` | `0.55` | 方向置信度低于该值时保守判为非押注贴 |
| `spring.ai.openai.*` | - | DeepSeek 连接配置（base-url / model / completions-path / api-key） |

## 项目结构

```
src/main/java/com/example/onmyojiguess/
├── config/Config.java               # 抓取用户列表 (昵称 -> uid)
├── controller/GuessQueryController  # REST 接口
├── dto/                             # 接口响应体
├── model/                           # FeedData / GuessInfo / AiVerdict / RoundResultHint
├── scheduler/FeedFetchTask.java     # 定时调度 (启动采集开关 + 单线程防重叠)
└── service/
    ├── DsFeedClient                 # 大神动态抓取客户端
    ├── GuessParser                  # 本地规则解析 + 赛果推断 + 候选筛选
    ├── AiGuessJudge                 # AI 判定接口 (含文本预处理)
    ├── DeepSeekJudgeService         # DeepSeek 实现 (批量, 条件装配)
    ├── StartLuxJudgeService         # StartLux 实现 (单帖概率决策, 条件装配)
    ├── FeedStorageService           # 入库去重 (feed/guess/round_result)
    ├── GuessQueryService            # 当前对局查询 (15s TTL 缓存)
    └── ShikigamiService             # 式神 OCR 数据入库与查询
src/main/resources/
├── application.properties           # 全部配置
├── db/init.sql                      # 建表脚本 (幂等)
└── static/index.html                # 看板页面
```

## 鸣谢

- [StartLuxLabs/StartLux-Decision-v1](https://github.com/StartLuxLabs/StartLux-Decision-v1) — 本项目默认 AI 判定所使用的类型化决策模型。StartLux-Decision 通过 TypeSafe `/v1/systemone` 协议提供毫秒级、带校准概率的结构化决策能力，本项目基于其文本决策能力实现对押注帖的相关性、方向与场次的判定。该模型需用户**自行部署**本地推理服务。
