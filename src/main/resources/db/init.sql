-- =====================================================
-- 阴阳师对弈竞猜抓取系统建表脚本 (MySQL 8.0)
-- 数据库: onmyoji_guess
-- 字符集: utf8mb4
-- 说明: 脚本幂等, 可重复执行; 应用启动时也会自动执行
-- =====================================================

-- CREATE DATABASE IF NOT EXISTS onmyoji_guess DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
-- USE onmyoji_guess;

-- -----------------------------------------------------
-- 1. 网易大神动态表 (原始 feeds 数据, 按 feed_id 去重)
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS feed
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    feed_id      VARCHAR(32)  NOT NULL COMMENT 'feed 唯一id (去重键)',
    user_name    VARCHAR(64)  NOT NULL COMMENT '用户昵称 (userKey_ds 的 key)',
    uid          VARCHAR(32)  NOT NULL COMMENT '用户uid (userKey_ds 的 value)',
    feed_type    INT          NULL COMMENT 'feed 类型',
    content_text TEXT         NULL COMMENT '动态正文文本',
    ip_location  VARCHAR(32)  NULL COMMENT 'ip 属地',
    create_time  DATETIME     NULL COMMENT '动态发布时间',
    update_time  DATETIME     NULL COMMENT '动态编辑时间',
    is_guess     TINYINT      NULL COMMENT '是否有效竞猜帖: 1是 0否 NULL=候选帖待AI判定',
    fetched_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '抓取入库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_feed_id (feed_id),
    KEY idx_user_create (user_name, create_time),
    KEY idx_unjudged (is_guess)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '网易大神动态原始数据';

-- 已有旧表时执行以下 ALTER 补充列 (新库无需执行):
-- ALTER TABLE feed ADD COLUMN is_guess TINYINT NULL COMMENT '是否有效竞猜帖: 1是 0否 NULL=候选帖待AI判定' AFTER update_time,
--   ADD KEY idx_unjudged (is_guess);

-- -----------------------------------------------------
-- 2. 对弈竞猜押注记录表 (解析结果, 按 feed_id + 场次去重)
--    场次: 10 点开始 24 点结束, 每 2 小时一局, 共 7 局
--    round_no: 1~7 对应 10/12/14/16/18/20/22 点场
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS guess_record
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    feed_id     VARCHAR(32)  NOT NULL COMMENT '来源 feed id',
    user_name   VARCHAR(64)  NOT NULL COMMENT '用户昵称',
    uid         VARCHAR(32)  NOT NULL COMMENT '用户uid',
    round_date  DATE         NOT NULL COMMENT '竞猜场次日期',
    round_no    INT          NOT NULL COMMENT '场次序号 1~7 (10/12/14/16/18/20/22 点场)',
    round_start DATETIME     NOT NULL COMMENT '该局开始时间 (去重键之一)',
    bet_side    VARCHAR(8)   NOT NULL COMMENT '押注方向: RED=左(红) BLUE=右(蓝)',
    raw_line    VARCHAR(255) NULL COMMENT '动态中匹配到的原始文本行',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_feed_round (feed_id, round_start),
    KEY idx_user_round (user_name, round_start),
    KEY idx_round (round_date, round_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '对弈竞猜押注记录';

-- -----------------------------------------------------
-- 3. 对局赛果表 (该局胜方, 用于胜率统计)
--    source: MANUAL=手动录入(优先) AUTO=赛果帖自动推断(首条生效, 不覆盖)
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS round_result
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    round_date  DATE        NOT NULL COMMENT '竞猜场次日期',
    round_no    INT         NOT NULL COMMENT '场次序号 1~7',
    win_side    VARCHAR(8)  NOT NULL COMMENT '该局胜方: RED=左(红) BLUE=右(蓝)',
    source      VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT '来源: MANUAL/AUTO',
    reporter    VARCHAR(64) NULL COMMENT 'AUTO 来源的报告者昵称',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_round (round_date, round_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '对局赛果(胜方)';

-- -----------------------------------------------------
-- 4. 对局式神数据表 (游戏画面 OCR 采集, 同局重复上报按最后一次覆盖)
--    所有属性值按 OCR 原样字符串保存, 不做数值转换
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS guess_shikigami
(
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    round_date    VARCHAR(10) NOT NULL COMMENT '场次日期 yyyy-MM-dd',
    round_no      INT         NOT NULL COMMENT '场次序号 1~7',
    side          VARCHAR(4)  NOT NULL COMMENT 'RED=红方(左) BLUE=蓝方(右)',
    no            INT         NOT NULL COMMENT '座位序号 1~5',
    name          VARCHAR(64) NOT NULL DEFAULT '' COMMENT '式神名称',
    attack        VARCHAR(64) NOT NULL DEFAULT '' COMMENT '攻击',
    hp            VARCHAR(64) NOT NULL DEFAULT '' COMMENT '生命',
    defense       VARCHAR(64) NOT NULL DEFAULT '' COMMENT '防御',
    speed         VARCHAR(64) NOT NULL DEFAULT '' COMMENT '速度',
    crit_rate     VARCHAR(64) NOT NULL DEFAULT '' COMMENT '暴击 (含百分号)',
    crit_damage   VARCHAR(64) NOT NULL DEFAULT '' COMMENT '暴击伤害 (含百分号)',
    effect_hit    VARCHAR(64) NOT NULL DEFAULT '' COMMENT '命中效果 (含百分号)',
    effect_resist VARCHAR(64) NOT NULL DEFAULT '' COMMENT '效果抵抗 (含百分号)',
    mitama        VARCHAR(64) NOT NULL DEFAULT '' COMMENT '御魂名称',
    PRIMARY KEY (id),
    UNIQUE KEY uk_round_side_no (round_date, round_no, side, no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '对局式神数据(OCR采集)';
