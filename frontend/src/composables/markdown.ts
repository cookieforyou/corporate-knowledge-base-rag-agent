import { marked } from 'marked'
import DOMPurify from 'dompurify'

// 聊天形态：换行即分段（breaks），表格/删除线（gfm）
marked.setOptions({ gfm: true, breaks: true })

const REF_RE = /\[ref-(\d+)\]/g

/**
 * 圈号引用兜底归一（v2.15）：模型偶发抄用文档正文圈号标题输出 [ref-⑤]
 * （① U+2460 … ⑳ U+2473）。仅归一 [ref-X] 形态内的圈号，正文圈号内容不动。
 * 后端编号化 documentFormatter 已根治，此处为概率性残留的确定性兜底。
 */
const CIRCLED_REF_RE = /\[ref-([①-⑳])\]/g

function normalizeCircledRefs(content: string): string {
  return content.replace(CIRCLED_REF_RE, (_, c: string) =>
    `[ref-${c.charCodeAt(0) - 0x2460 + 1}]`)
}

function renderMd(md: string): string {
  if (!md) return ''
  // marked 同步形态返回 string；v-html 前必须 DOMPurify 消毒（答案内容经模型转述文档，不可信）
  return DOMPurify.sanitize(marked.parse(md) as string)
}

// [ref-N] 占位符 token（v2.16）：@ 非 markdown 元字符，对 marked 与 DOMPurify 均透明
const REF_TOKEN_RE = /@@REF(\d+)@@/g

/**
 * 渲染助手回答（3.15）：[ref-N] 先替换为占位符 token，全文单次 markdown 渲染 + 消毒，
 * sanitize 后把 token 换回可点击徽标（data-ref=N，与溯源 final 序列下标对齐，11.1.2）。
 * 全文单次渲染保证徽标内联于段落（v2.16 修复旧切段渲染：各段被包成块级 <p>，
 * 徽标孤立于块级元素之间独占一行、邻接标点/表格行被切断成孤儿段）；
 * 替换发生在 sanitize 之后——消毒器剥不掉徽标，markdown 也吞不到方括号语法。
 */
export function renderAnswer(content: string): string {
  if (!content) return ''
  const masked = normalizeCircledRefs(content).replace(REF_RE, (_, n: string) => `@@REF${n}@@`)
  return renderMd(masked).replace(
    REF_TOKEN_RE,
    (_, n: string) =>
      `<span class="ref-tag" data-ref="${n}" role="button" tabindex="0">ref-${n}</span>`
  )
}
