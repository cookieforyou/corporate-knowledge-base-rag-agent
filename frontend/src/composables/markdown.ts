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

/**
 * 渲染助手回答（3.15）：按 [ref-N] 切段分别做 markdown 渲染 + 消毒，
 * ref 位置插入可点击徽标（data-ref=N，与溯源 final 序列下标对齐，11.1.2）。
 * 分段渲染避免 [ref-N] 被 markdown 语法吞掉或被消毒器剥离。
 */
export function renderAnswer(content: string): string {
  if (!content) return ''
  content = normalizeCircledRefs(content)
  const parts: string[] = []
  let last = 0
  for (const m of content.matchAll(REF_RE)) {
    parts.push(renderMd(content.slice(last, m.index)))
    parts.push(
      `<span class="ref-tag" data-ref="${m[1]}" role="button" tabindex="0">ref-${m[1]}</span>`
    )
    last = (m.index ?? 0) + m[0].length
  }
  parts.push(renderMd(content.slice(last)))
  return parts.join('')
}
