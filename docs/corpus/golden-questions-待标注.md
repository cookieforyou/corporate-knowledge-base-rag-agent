# Golden 语料问题集（待标注）

> **2026-08-03 标注完成**：本表全部问题 + DocMind PDF 新增 11 题 + 5 条对抗性负题已标注 chunkId，
> 落入 `kb-eval/src/main/resources/golden/`（finance/k8s/product/cross/docmind-qa.json +
> negative-out-of-kb.json，合计 74 条）。本文件保留为设计工作底稿，数据以 golden/*.json 为准。

> 用途：三份语料文档上传入库后，执行下方 SQL 导出 chunk 映射，据此为每题填写 `expectedChunkIds`，写入 `kb-eval/src/main/resources/golden/` 下的 JSON 文件。
>
> **chunk 映射导出 SQL**（IDEA 数据库工具或 psql）：
>
> ```sql
> SELECT d.name AS doc, c.id, c.chunk_index, c.chunk_type, c.page_num, c.content
> FROM kb_chunk c JOIN kb_document d ON c.doc_id = d.id
> WHERE d.name IN ('增值税发票管理实务手册.md','Kubernetes集群运维规范.md',
> '智能硬件产品规格目录.md','阿里云文档解析（大模型版）介绍.pdf')
> ORDER BY d.name, c.chunk_index;
> ```
>
> 标注原则（同 README-标注指南.md）：只标**能回答该题的必要 chunk**；跨章节/跨文档题标多个；按内容判定而非按得分。

## 发票手册（finance-qa.json，14 题）

| id | category | question | 预期答案锚点 |
|----|----------|----------|------------|
| fin-01 | FACTOID | 增值税专用发票的认证期限是多少天？ | 开具之日起 360 日内认证/勾选 |
| fin-02 | FACTOID | 增值税一般纳税人销售货物适用什么税率？ | 13% |
| fin-03 | FACTOID | 360 日认证期限是哪一年从 180 日延长而来的？ | 2020 年 3 月 1 日起（2019 年 45 号公告） |
| fin-04 | FACTOID | 红字增值税专用发票信息表由谁填开？ | 购买方已认证→购买方填开；未认证→销售方填开 |
| fin-05 | FACTOID | 增值税电子普通发票可以作废吗？ | 不可以，只能红冲 |
| fin-06 | FACTOID | 税务 UKey 需要缴纳年度服务费吗？ | 免费发放，无年度服务费 |
| fin-07 | FACTOID | 每个属期勾选发票后还必须执行什么操作才能申报抵扣？ | 确认签名 |
| fin-08 | FACTOID | 税控盘的技术维护费大约多少？ | 约 280 元/年 |
| fin-09 | TABLE | 各种增值税发票在认证要求上有什么区别？ | 发票种类表的「是否需要认证」列 |
| fin-10 | TABLE | 开票设备金税盘和税控盘有什么区别？ | 开票设备表（白盘/黑盘、发行机构） |
| fin-11 | FACTOID | 什么是异常增值税扣税凭证？已抵扣的异常凭证如何处理？ | 异常凭证定义 + 已抵扣一律进项转出 |
| fin-12 | FACTOID | 失控发票和异常凭证是同一个概念吗？ | 不同；失控发票定义（丢失被盗税控设备/走逃企业脱离监管） |
| fin-13 | REASONING | 跨月发现开具错误的纸质专用发票应该怎么处理，为什么不能直接作废？ | 作废仅限开票当月且未抄税未认证，跨月走红字流程 |
| fin-14 | REASONING | 小规模纳税人需要给客户开具专用发票，有哪些途径？ | 2021 年起可自行开具，无需代开 |

## Kubernetes 运维规范（k8s-qa.json，14 题）

| id | category | question | 预期答案锚点 |
|----|----------|----------|------------|
| k8s-01 | FACTOID | 生产集群的 etcd 副本数为什么固定为 3？ | 法定人数要求，容忍单节点故障 |
| k8s-02 | FACTOID | 滚动更新策略的 maxSurge 和 maxUnavailable 默认值是多少？ | 均为 25% |
| k8s-03 | FACTOID | 核心业务的可用性目标是多少？ | 99.95%（月度停机 ≤ 21.6 分钟） |
| k8s-04 | FACTOID | etcd 快照备份的频率和保留策略是什么？ | 每日凌晨 02:00，保留 7 天 |
| k8s-05 | FACTOID | 命名空间默认最多可以创建多少个 Pod？ | 200 |
| k8s-06 | FACTOID | 周五下午可以执行生产变更吗？ | 禁止（周五下午及节假日前禁止非紧急变更） |
| k8s-07 | TABLE | Deployment、StatefulSet、DaemonSet 三种控制器怎么选？ | 工作负载选型表 |
| k8s-08 | TABLE | 出现 OOMKilled 应该怎么排查处置？ | 故障排查表 OOMKilled 行 |
| k8s-09 | TABLE | 各节点池的规格和用途是怎么划分的？ | 节点规划表 |
| k8s-10 | TABLE | 蓝绿发布和金丝雀发布在回滚速度和资源开销上怎么对比？ | 发布策略表 |
| k8s-11 | FACTOID | Ingress 和 Service 的本质区别是什么？ | Service 四层 kube-proxy；Ingress 七层 HTTP 路由 |
| k8s-12 | FACTOID | 删除 StatefulSet 会自动删除 PVC 吗？ | 不会，防止数据误删 |
| k8s-13 | REASONING | 为什么规范禁止把无状态服务放进 StatefulSet？ | 滚动更新语义不同，会拖慢发布 |
| k8s-14 | REASONING | 一个 Java 业务服务上线，资源限制应该怎么配置才合规？ | limits.cpu ≤ 2×requests.cpu；limits.memory 与 -Xmx 配套、堆外预留 25% |

## 产品规格目录（product-qa.json，12 题）

| id | category | question | 预期答案锚点 |
|----|----------|----------|------------|
| prd-01 | FACTOID | XG-9000Pro 的电池容量是多少？ | 6800mAh |
| prd-02 | FACTOID | XG-9000Pro 的防护等级是什么？ | IP68 + 1.8 米六面抗跌落（MIL-STD-810H） |
| prd-03 | FACTOID | XR-5500 的续航时间大约多久？ | 约 2.5 小时（1200mAh） |
| prd-04 | FACTOID | XS-200 网关的最低工作温度是多少？ | -40℃ |
| prd-05 | FACTOID | 红外热成像模组 XG-TIR-01 支持哪些型号？ | 仅 XG-9000Pro |
| prd-06 | FACTOID | 磁吸充电底座 ACC-MCD-33 兼容哪些型号？ | XG-9000Pro / XG-7000 |
| prd-07 | FACTOID | XG 系列的整机质保期是多久？ | 24 个月 |
| prd-08 | TABLE | XG-9000Pro 和 XG-7000 有哪些主要差异？ | XG-9000Pro 规格表 + 差异说明段 |
| prd-09 | TABLE | 各产品系列的质保政策有什么区别？ | 售后服务政策表 |
| prd-10 | FACTOID | 型号命名规则里档位码 9 代表什么？ | 旗舰档 |
| prd-11 | REASONING | 户外电力巡检需要厘米级定位和热成像功能，应该选哪款产品，配什么模组？ | XG-9000Pro（RTK）+ XG-TIR-01 |
| prd-12 | REASONING | XG 系列设备用了两年半坏了，能免费保修吗？ | 整机质保 24 个月已过；电池 12 个月也过；除非购买过延保 |

## 跨文档（cross-qa.json，2 题）

| id | category | question | 预期答案锚点（跨文档多 chunk） |
|----|----------|----------|------------------------------|
| cross-01 | MULTI_DOC | 发票手册和 K8s 运维规范分别如何定义「异常」情形的处置流程？ | 发票：异常凭证四步处置；K8s：故障排查表 |
| cross-02 | MULTI_DOC | 知识库里有哪些关于期限/周期的硬性规定？ | 发票认证 360 日 + 红字信息表/作废规则；K8s etcd 备份保留 7 天/变更窗口；产品质保期表 |

## 对抗性负向（补充至 negative-out-of-kb.json，5 题）

> 看似库内实则库外——检验模型不被「话题相似」诱导幻觉：

| id | question | 陷阱说明 |
|----|----------|---------|
| neg-adv-01 | 2026 年最新的小规模纳税人免税额度是多少？ | 话题在发票领域但手册无此数据 |
| neg-adv-02 | Kubernetes 如何安装 Dashboard 可视化插件？ | 运维话题但规范未涉及安装步骤 |
| neg-adv-03 | XG-9000Pro 的蓝牙有效传输距离是多少米？ | 型号真实但该参数目录未列出 |
| neg-adv-04 | XR-5500 支持近视度数定制镜片吗？ | 产品真实但售后/定制政策未提及 |
| neg-adv-05 | 税控盘丢失后登报声明的具体流程是什么？ | 发票话题但手册仅讲失控发票认定，无登报流程 |
