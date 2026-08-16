# 词表带外导入 inbox 模板（安全簇① T4）

`import_words.py` 消费的本目录形态说明。**inbox 建议放仓库外本地路径**
（如 `~/guardrail-inbox/`），明文词面永不进仓库；导入完成后 inbox 自行处置。

## 文件格式

目录下 `.jsonl` 与 `.csv` 均被消费（其余文件忽略），每文件逐条词项。

### JSONL（推荐：字段可省略、支持预编码值）

每行一个 JSON 对象：

| 字段 | 必填 | 说明 |
|---|---|---|
| `value` | ✅ | 词面（KEYWORD 干词或 REGEX 模式源文）；`encoding:"base64"` 时为 Base64 编码态 |
| `side` | ✅ | `injection`（注入侧）/ `output`（输出侧） |
| `family` | — | 注入侧七族系 / 输出侧三分类枚举名（缺省 `UNCLASSIFIED`，须精确拼写） |
| `lang` | — | `zh` / `en` / …（缺省空） |
| `type` | — | `KEYWORD`（缺省）/ `REGEX` |
| `action` | — | `BLOCK`（缺省）/ `FLAG`。**新词建议先 FLAG 观察，零误伤后转 BLOCK** |
| `encoding` | — | `base64`（value 已编码时声明，脚本校验后原样落盘）；省略 = 明文由脚本编码 |

合法 family 枚举：

- 注入侧：`INSTRUCTION_OVERRIDE` / `ROLE_HIJACK` / `INFO_EXTRACTION` /
  `ENCODING_OBFUSCATION` / `MULTILINGUAL` / `JAILBREAK` / `TOOL_INDUCED` / `UNCLASSIFIED`
- 输出侧：`BUSINESS_CONFIDENTIAL` / `COMPLIANCE_SENSITIVE` / `COMPETITOR_COMPARISON` / `UNCLASSIFIED`

### CSV

首行表头固定 `value,side,family,lang,type,action`（不支持 encoding 列）。

## 运行

```bash
# 试运行（只报告不落盘）
python3 tools/guardrail/import_words.py --inbox ~/guardrail-inbox --dry-run

# 正式导入（输出仅计数 / ID 段 / 族系分布 / 指纹，不回显词值）
python3 tools/guardrail/import_words.py --inbox ~/guardrail-inbox
```

脚本按「解码值指纹 + 侧 + 类型」幂等去重，重复运行不产生重复词项。
ID 自动编号：注入侧默认前缀 `import-inj-NN`，输出侧 `import-out-NN`
（`--id-prefix-injection/--id-prefix-output` 可改）。

## sample.jsonl 说明

随模板附带的 `sample.jsonl` 两条均为**无害占位词**（仅演示格式，
导入后会占用序号——正式导入前请删除本文件）。
