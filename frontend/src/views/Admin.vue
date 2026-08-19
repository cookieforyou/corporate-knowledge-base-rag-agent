<template>
  <div class="admin-page">
    <header class="page-head reveal">
      <div>
        <h1 class="t-display page-title">运维中心</h1>
        <p class="page-desc">统计仪表盘 · Chunk 运维与索引重建 · 审计日志查询 · Bad Case 标注与 Golden 回灌闭环 · 护栏词表视图与命中演练</p>
      </div>
    </header>

    <el-tabs v-model="tab" class="admin-tabs reveal" style="--d:.05s">
      <!-- ════ 统计仪表盘 ════ -->
      <el-tab-pane label="统计仪表盘" name="dashboard">
        <div v-loading="dashLoading">
          <div class="stat-strip">
            <div class="stat-item panel">
              <span class="stat-num t-data">{{ overview?.documentTotal ?? '—' }}</span>
              <span class="t-label">文档总数</span>
            </div>
            <div class="stat-item panel">
              <span class="stat-num t-data">{{ overview?.chunkTotal ?? '—' }}</span>
              <span class="t-label">存活 Chunks</span>
            </div>
            <div class="stat-item panel">
              <span class="stat-num t-data">{{ badCaseTotal }}</span>
              <span class="t-label">Bad Case（点踩）</span>
            </div>
            <div class="stat-item panel">
              <span class="stat-num t-data">{{ unannotatedTotal }}</span>
              <span class="t-label">待标注</span>
            </div>
          </div>

          <div class="dash-grid">
            <div class="panel dash-card">
              <div class="t-label">解析状态分布</div>
              <div class="kv-list">
                <div v-for="(v, k) in overview?.documentsByStatus ?? {}" :key="k" class="kv-row">
                  <el-tag :type="statusTag(k)" size="small" effect="plain">{{ k }}</el-tag>
                  <span class="kv-val t-data">{{ v }}</span>
                </div>
              </div>
              <div class="t-label" style="margin-top:14px">解析路由分布</div>
              <div class="kv-list">
                <div v-for="(v, k) in overview?.documentsByParseRoute ?? {}" :key="k" class="kv-row">
                  <span class="kv-key">{{ k }}</span>
                  <span class="kv-val t-data">{{ v }}</span>
                </div>
              </div>
            </div>

            <div class="panel dash-card">
              <div class="t-label">近 14 天入库趋势（文档 / Chunk）</div>
              <div class="trend-bars">
                <el-tooltip v-for="d in overview?.dailyIngestion ?? []" :key="d.date"
                  :content="`${d.date} · 文档 ${d.documents} · Chunks ${d.chunks}`" placement="top">
                  <div class="trend-col">
                    <div class="trend-bar chunks" :style="{ height: barH(d.chunks, trendMax) + 'px' }" />
                    <div class="trend-bar docs" :style="{ height: barH(d.documents, trendMax) + 'px' }" />
                  </div>
                </el-tooltip>
              </div>
            </div>

            <div class="panel dash-card">
              <div class="t-label">处理中文档</div>
              <div v-if="!processing?.documents?.length" class="dash-empty">当前无处理中文档</div>
              <div v-for="d in processing?.documents ?? []" :key="d.id" class="proc-row">
                <el-tag size="small" type="warning" effect="plain">{{ d.status }}</el-tag>
                <span class="proc-name">{{ d.name }}</span>
                <span class="t-data proc-route">{{ d.parseRoute ?? '—' }}</span>
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ════ Chunk 运维（簇③ 4.4/4.5 前端面） ════ -->
      <el-tab-pane label="Chunk 运维" name="chunks">
        <div class="filter-bar panel">
          <el-select v-model="chunkDocId" placeholder="选择文档" filterable style="width: 320px"
            @change="loadAdminChunks">
            <el-option v-for="d in docs" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-button :disabled="!chunkDocId" @click="loadAdminChunks">刷新</el-button>
          <span class="t-label bc-hint">编辑 = 同源消毒 + 异步重嵌入（chunk ID 不变）· 软删可恢复 · 列表含软删行</span>
        </div>

        <el-table v-loading="chunkLoading" :data="adminChunks" class="log-table" stripe>
          <el-table-column prop="chunkIndex" label="#" width="60">
            <template #default="{ row }"><span class="t-data">{{ row.chunkIndex }}</span></template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.chunkType === 'TABLE' ? 'warning' : 'success'" effect="plain">
                {{ row.chunkType }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
          <el-table-column label="页 / tokens" width="110">
            <template #default="{ row }">
              <span class="t-data">{{ row.pageNum ?? '—' }} / {{ row.tokenCount ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.isDeleted ? 'danger' : 'success'" effect="plain">
                {{ row.isDeleted ? '已软删' : '存活' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <template v-if="!row.isDeleted">
                <el-button size="small" @click="openChunkEdit(row)">编辑</el-button>
                <el-popconfirm title="软删该 Chunk？检索立即不可见，可恢复" @confirm="doSoftDeleteChunk(row)">
                  <template #reference>
                    <el-button size="small" type="danger">软删</el-button>
                  </template>
                </el-popconfirm>
              </template>
              <el-button v-else size="small" type="primary" @click="doRestoreChunk(row)">恢复</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 索引重建（4.5） -->
        <div class="rebuild-panel panel">
          <div class="rebuild-head">
            <div>
              <div class="t-label rebuild-title">索引重建（文档级 reparse 蓝绿编排 + ES 孤儿清扫）</div>
              <div class="t-label rebuild-sub">以 MinIO 原件重走管线——手工编辑将被原件覆写（设计行为）；内存任务表，重启丢失</div>
            </div>
            <div class="rebuild-actions">
              <el-select v-model="rebuildDocIds" multiple collapse-tags collapse-tags-tooltip
                placeholder="留空 = 租户全量" style="width: 280px">
                <el-option v-for="d in docs" :key="d.id" :label="d.name" :value="d.id" />
              </el-select>
              <el-popconfirm title="确认发起重建？目标文档将重解析重入库" @confirm="doStartRebuild">
                <template #reference>
                  <el-button type="primary" :loading="rebuildStarting">发起重建</el-button>
                </template>
              </el-popconfirm>
            </div>
          </div>
          <el-table v-loading="rebuildLoading" :data="rebuildTasks" size="small">
            <el-table-column label="任务" width="120">
              <template #default="{ row }"><span class="t-data">{{ row.taskId.slice(0, 8) }}…</span></template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'COMPLETED' ? 'success' : 'warning'" effect="plain">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="进度" min-width="200">
              <template #default="{ row }">
                <span class="t-data">{{ row.succeeded + row.failed + row.skipped }} / {{ row.total }}</span>
                <span class="t-label">（成功 {{ row.succeeded }} · 失败 {{ row.failed }} · 跳过 {{ row.skipped }}）</span>
              </template>
            </el-table-column>
            <el-table-column label="发起时间" width="165">
              <template #default="{ row }"><span class="t-data">{{ fmtTime(row.startedAt) }}</span></template>
            </el-table-column>
            <el-table-column label="失败/跳过明细" min-width="140">
              <template #default="{ row }">
                <el-tooltip v-if="row.failures?.length" placement="top" effect="light">
                  <template #content>
                    <div v-for="f in row.failures" :key="f.docId" class="rebuild-fail-line">
                      {{ f.docId.slice(0, 8) }}…：{{ f.reason }}
                    </div>
                  </template>
                  <span class="t-data rebuild-fail-count">{{ row.failures.length }} 条</span>
                </el-tooltip>
                <span v-else class="t-label">—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- Chunk 编辑对话框 -->
        <el-dialog v-model="chunkEditVisible" title="编辑 Chunk 内容" width="640px" :append-to-body="true" :modal-append-to-body="true" top="20vh" destroy-on-close>
          <div class="t-label chunk-edit-meta">
            #{{ chunkEditTarget?.chunkIndex }} · {{ chunkEditTarget?.id }}
            <template v-if="chunkEditTarget?.headingPath"> · {{ chunkEditTarget.headingPath }}</template>
          </div>
          <el-input v-model="chunkEditContent" type="textarea" :rows="10" placeholder="Chunk 内容" />
          <div class="t-label chunk-edit-hint">
            提交后：同源消毒（PII 掩码 / 注入打标不阻断）→ PG 同步更新 → 异步重嵌入（向量 delete→add + ES 覆写）；chunk ID 不变，检索短暂窗口缺失属运维形态
          </div>
          <template #footer>
            <el-button @click="chunkEditVisible = false">取消</el-button>
            <el-button type="primary" :loading="chunkEditBusy"
              :disabled="!chunkEditContent.trim()" @click="submitChunkEdit">保存</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ════ 日志查询 ════ -->
      <el-tab-pane label="日志查询" name="logs">
        <div class="filter-bar panel">
          <el-date-picker v-model="logFilter.range" type="datetimerange" size="default"
            range-separator="→" start-placeholder="起始时间" end-placeholder="结束时间"
            style="width: 360px" />
          <el-input v-model="logFilter.userId" placeholder="用户 ID" clearable style="width: 140px" />
          <el-input v-model="logFilter.sessionId" placeholder="会话 ID" clearable style="width: 200px" />
          <el-select v-model="logFilter.feedback" placeholder="反馈" clearable style="width: 110px">
            <el-option label="点踩" value="NEGATIVE" />
            <el-option label="点赞" value="POSITIVE" />
          </el-select>
          <el-select v-model="logFilter.status" placeholder="状态" clearable style="width: 120px">
            <el-option label="SUCCESS" value="SUCCESS" />
            <el-option label="REJECTED" value="REJECTED" />
            <el-option label="ERROR" value="ERROR" />
          </el-select>
          <el-button type="primary" @click="loadLogs(0)">查询</el-button>
        </div>

        <el-table v-loading="logLoading" :data="logs" class="log-table" stripe
          @row-click="openDetail">
          <el-table-column label="时间" width="120">
            <template #default="{ row }"><span class="t-data">{{ fmtTime(row.createdAt) }}</span></template>
          </el-table-column>
          <el-table-column prop="userId" label="用户" width="150" show-overflow-tooltip />
          <el-table-column prop="sessionId" label="会话" width="150" show-overflow-tooltip />
          <el-table-column prop="mode" label="链路" width="70">
            <template #default="{ row }">
              <el-tag size="small" :type="row.mode === 'tool' ? 'warning' : 'success'" effect="plain">
                {{ row.mode ?? '—' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="105">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTag(row.status)" effect="plain">
                {{ row.status ?? '—' }}
              </el-tag>
              <div v-if="row.errorCode" class="err-code t-data">{{ row.errorCode }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="queryText" label="问题" min-width="240" show-overflow-tooltip />
          <el-table-column label="反馈" width="80">
            <template #default="{ row }">
              <span v-if="row.feedback === 'NEGATIVE'">👎</span>
              <span v-else-if="row.feedback === 'POSITIVE'">👍</span>
              <span v-else class="t-label">—</span>
            </template>
          </el-table-column>
          <el-table-column label="根因" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.rootCause" size="small" type="danger" effect="plain">
                {{ rootCauseLabel(row.rootCause) }}
              </el-tag>
              <span v-else class="t-label">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="latencyMs" label="延迟 ms" width="90">
            <template #default="{ row }"><span class="t-data">{{ row.latencyMs ?? '—' }}</span></template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="logPage" :page-size="logSize" :total="logTotal"
          layout="prev, pager, next, total" style="margin-top: 14px" @current-change="loadLogs()" />

        <el-drawer v-model="detailVisible" title="审计详情" size="40%" :append-to-body="true" :modal-append-to-body="true" destroy-on-close>
          <template v-if="detail">
            <div class="detail-kv"><span class="t-label">Trace</span><span class="t-data">{{ detail.traceId ?? '—' }}</span></div>
            <div class="detail-kv"><span class="t-label">会话</span><span class="t-data">{{ detail.sessionId ?? '—' }}</span></div>
            <div class="detail-kv"><span class="t-label">模型</span><span class="t-data">{{ detail.modelName ?? '—' }} · {{ detail.latencyMs ?? '—' }}ms</span></div>
            <div class="detail-sec">
              <div class="t-label">原始问题</div>
              <div class="detail-text">{{ detail.queryText }}</div>
            </div>
            <div v-if="detail.rewrittenQuery" class="detail-sec">
              <div class="t-label">改写后</div>
              <div class="detail-text">{{ detail.rewrittenQuery }}</div>
            </div>
            <div v-if="detail.finalAnswer" class="detail-sec">
              <div class="t-label">回答</div>
              <div class="detail-text">{{ detail.finalAnswer }}</div>
            </div>
            <div v-if="detail.rerankedChunks" class="detail-sec">
              <div class="t-label">重排序命中</div>
              <pre class="detail-json">{{ pretty(detail.rerankedChunks) }}</pre>
            </div>
            <div v-if="detail.retrievedChunks" class="detail-sec">
              <div class="t-label">双路原始命中</div>
              <pre class="detail-json">{{ pretty(detail.retrievedChunks) }}</pre>
            </div>
            <div v-if="detail.toolCalls" class="detail-sec">
              <div class="t-label">工具调用</div>
              <pre class="detail-json">{{ pretty(detail.toolCalls) }}</pre>
            </div>
          </template>
        </el-drawer>
      </el-tab-pane>

      <!-- ════ Bad Case 处置 ════ -->
      <el-tab-pane label="Bad Case 处置" name="badcase">
        <div class="filter-bar panel">
          <el-select v-model="bcFilter.annotated" placeholder="标注态" clearable style="width: 130px">
            <el-option label="未标注" :value="false" />
            <el-option label="已标注" :value="true" />
          </el-select>
          <el-select v-model="bcFilter.rootCause" placeholder="根因" clearable style="width: 140px">
            <el-option v-for="(label, key) in ROOT_CAUSES" :key="key" :label="label" :value="key" />
          </el-select>
          <el-button type="primary" @click="loadBadCases(0)">查询</el-button>
          <span class="t-label bc-hint">范围：本租户点踩反馈关联的审计行</span>
        </div>

        <el-table v-loading="bcLoading" :data="badCases" class="log-table" stripe>
          <el-table-column label="时间" width="165">
            <template #default="{ row }"><span class="t-data">{{ fmtTime(row.createdAt) }}</span></template>
          </el-table-column>
          <el-table-column prop="userId" label="用户" width="100" show-overflow-tooltip />
          <el-table-column prop="queryText" label="问题" min-width="200" show-overflow-tooltip />
          <el-table-column label="期望回答（用户）" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.feedbackExpectedAnswer">{{ row.feedbackExpectedAnswer }}</span>
              <span v-else class="t-label">未提供</span>
            </template>
          </el-table-column>
          <el-table-column label="根因" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.rootCause" size="small" type="danger" effect="plain">
                {{ rootCauseLabel(row.rootCause) }}
              </el-tag>
              <el-tag v-else size="small" type="info" effect="plain">未标注</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openAnnotate(row)">标注</el-button>
              <el-button size="small" type="primary" @click="openReingest(row)">回灌</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="bcPage" :page-size="bcSize" :total="bcTotal"
          layout="prev, pager, next, total" style="margin-top: 14px" @current-change="loadBadCases()" />

        <!-- 根因标注对话框 -->
        <el-dialog v-model="annotateVisible" title="根因标注（四分类）" width="550px" :append-to-body="true" :modal-append-to-body="true" top="20vh" destroy-on-close>
          <div class="annotate-q">{{ annotateTarget?.queryText }}</div>
          <el-radio-group v-model="annotateChoice" class="annotate-group">
            <el-radio v-for="(label, key) in ROOT_CAUSES" :key="key" :value="key" class="annotate-opt">
              <b>{{ label }}</b>
              <span class="t-label annotate-hint">{{ ROOT_CAUSE_HINTS[key] }}</span>
            </el-radio>
          </el-radio-group>
          <template #footer>
            <el-button @click="annotateVisible = false">取消</el-button>
            <el-button type="primary" :loading="annotateBusy" :disabled="!annotateChoice"
              @click="submitAnnotate">确认标注</el-button>
          </template>
        </el-dialog>

        <!-- Golden 回灌对话框 -->
        <el-dialog v-model="reingestVisible" title="回灌 Golden Set" width="640px" :append-to-body="true" :modal-append-to-body="true" top="10vh" destroy-on-close>
          <div class="annotate-q">{{ reingestTarget?.queryText }}</div>
          <el-form label-position="top" class="reingest-form">
            <el-form-item label="用例分类">
              <el-select v-model="reingestForm.category" style="width: 220px">
                <el-option v-for="c in REINGEST_CATEGORIES" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
            <el-form-item label="期望命中 Chunk（点击候选快速添加，可手动补充）">
              <div class="chip-row">
                <el-tag v-for="id in chunkCandidates" :key="id" class="cand-chip" effect="plain"
                  :type="reingestForm.expectedChunkIds.includes(id) ? 'success' : 'info'"
                  @click="toggleChunk(id)">{{ id.slice(0, 8) }}…</el-tag>
                <span v-if="!chunkCandidates.length" class="t-label">无检索命中候选</span>
              </div>
              <el-input v-model="chunkInput" placeholder="chunk id，回车添加" size="small"
                style="margin-top: 8px" @keyup.enter="addChunkInput" />
              <div class="chip-row" style="margin-top: 6px">
                <el-tag v-for="id in reingestForm.expectedChunkIds" :key="id" closable
                  @close="removeChunk(id)">{{ id }}</el-tag>
              </div>
            </el-form-item>
            <el-form-item label="期望命中文档（文件名）">
              <el-select v-model="reingestForm.expectedDocs" multiple filterable allow-create
                default-first-option placeholder="选择候选或输入文件名" style="width: 100%">
                <el-option v-for="name in docCandidates" :key="name" :label="name" :value="name" />
              </el-select>
            </el-form-item>
            <el-form-item label="理想回答（LLM-as-Judge 评分基准，可空）">
              <el-input v-model="reingestForm.expectedAnswer" type="textarea" :rows="3"
                placeholder="留空则跳过 Answer Correctness" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="reingestVisible = false">取消</el-button>
            <el-button type="primary" :loading="reingestBusy" @click="submitReingest">回灌</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ════ 护栏词表（安全簇⑥ F2 只读运营面） ════ -->
      <el-tab-pane label="护栏词表" name="guardrail">
        <div class="filter-bar panel">
          <el-select v-model="grFilter.side" placeholder="侧别" clearable style="width: 120px">
            <el-option label="注入侧" value="injection" />
            <el-option label="输出侧" value="output" />
          </el-select>
          <el-select v-model="grFilter.family" placeholder="族系" clearable filterable style="width: 220px">
            <el-option v-for="f in grFamilyOptions" :key="f" :label="f" :value="f" />
          </el-select>
          <el-select v-model="grFilter.action" placeholder="动作档" clearable style="width: 110px">
            <el-option label="BLOCK 拦截" value="BLOCK" />
            <el-option label="FLAG 观察" value="FLAG" />
          </el-select>
          <el-select v-model="grFilter.type" placeholder="匹配类型" clearable style="width: 120px">
            <el-option label="KEYWORD" value="KEYWORD" />
            <el-option label="REGEX" value="REGEX" />
          </el-select>
          <el-select v-model="grFilter.enabled" placeholder="启用态" clearable style="width: 110px">
            <el-option label="启用" :value="true" />
            <el-option label="停用" :value="false" />
          </el-select>
          <el-button type="primary" @click="loadGuardrailRules">查询</el-button>
          <span class="t-label bc-hint">
            共 {{ grRules.length }} 条 · BLOCK {{ grBlockCount }} / FLAG {{ grFlagCount }}
          </span>
        </div>

        <el-table v-loading="grLoading" :data="grRules" class="log-table gr-table" stripe>
          <el-table-column prop="id" label="词项 ID">
            <template #default="{ row }"><span class="t-data">{{ row.id }}</span></template>
          </el-table-column>
          <el-table-column label="侧别">
            <template #default="{ row }">
              <el-tag size="small" :type="row.side === 'injection' ? 'danger' : 'warning'" effect="plain">
                {{ row.side === 'injection' ? '注入' : '输出' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="family" label="族系">
            <template #default="{ row }"><span class="t-data">{{ row.family }}</span></template>
          </el-table-column>
          <el-table-column label="类型">
            <template #default="{ row }">
              <el-tag size="small" :type="row.type === 'REGEX' ? 'success' : 'info'" effect="plain">
                {{ row.type }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="动作">
            <template #default="{ row }">
              <el-tag size="small" :type="row.action === 'BLOCK' ? 'danger' : 'warning'" effect="plain">
                {{ row.action }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="启用">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'" effect="plain">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="lang" label="语种">
            <template #default="{ row }"><span class="t-data">{{ row.lang || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="指纹 / 长度">
            <template #default="{ row }">
              <el-tooltip :content="row.sha256" placement="top">
                <span class="t-data">{{ row.sha256 }}</span>
              </el-tooltip>
              <span class="t-label"> · {{ row.charLen }}</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 命中演练台 -->
        <div class="drill-panel panel">
          <div class="t-label rebuild-title">命中演练（与运行时同口径：归一化检测视图 → 双侧词表匹配）</div>
          <div class="t-label rebuild-sub">
            纯运营视图——不计指标不落审计；value 明文不回显（元数据形态）。词表变更经 Git Ops：
            inbox 带外创作 → import_words.py → git 同步完整文件至运行环境 → 发信号免重启生效
          </div>
          <div class="drill-input-row">
            <el-input v-model="drillText" type="textarea" :rows="3"
              placeholder="输入待演练文本（消息或候选词面）" />
            <el-button type="primary" :loading="drillBusy" :disabled="!drillText.trim()"
              @click="runDrill">演练</el-button>
          </div>
          <template v-if="drillResult">
            <div class="drill-sec">
              <div class="t-label">注入侧命中 {{ drillResult.injectionMatches.length }} 条</div>
              <div v-if="!drillResult.injectionMatches.length" class="dash-empty">无命中</div>
              <div v-for="m in drillResult.injectionMatches" :key="'i' + m.id" class="drill-row">
                <span class="t-data">{{ m.id }}</span>
                <el-tag size="small" :type="m.action === 'BLOCK' ? 'danger' : 'warning'" effect="plain">{{ m.action }}</el-tag>
                <el-tag size="small" type="info" effect="plain">{{ m.type }}</el-tag>
                <span class="t-label">{{ m.family }}</span>
              </div>
            </div>
            <div class="drill-sec">
              <div class="t-label">输出侧命中 {{ drillResult.outputMatches.length }} 条</div>
              <div v-if="!drillResult.outputMatches.length" class="dash-empty">无命中</div>
              <div v-for="m in drillResult.outputMatches" :key="'o' + m.id" class="drill-row">
                <span class="t-data">{{ m.id }}</span>
                <el-tag size="small" :type="m.action === 'BLOCK' ? 'danger' : 'warning'" effect="plain">{{ m.action }}</el-tag>
                <el-tag size="small" type="info" effect="plain">{{ m.type }}</el-tag>
                <span class="t-label">{{ m.family }}</span>
              </div>
            </div>
            <div v-if="drillVerdict" class="drill-verdict">
              <el-tag :type="drillVerdict.type" effect="plain">{{ drillVerdict.text }}</el-tag>
            </div>
          </template>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getStatsOverview, getProcessingStats, searchAuditLogs, annotateRootCause, reingestGolden,
  listDocuments, getChunks, editChunk, softDeleteChunk, restoreChunk,
  startRebuild, listRebuildTasks, listGuardrailRules, drillGuardrail
} from '@/api'
import type {
  StatsOverview, ProcessingView, AuditLogItem, RootCause, AuditQuery,
  KbDoc, KbChunk, RebuildTask, GuardrailRuleView, GuardrailRuleQuery, DrillResult
} from '@/api'

const tab = ref('dashboard')

// ── 仪表盘 ──

const dashLoading = ref(false)
const overview = ref<StatsOverview | null>(null)
const processing = ref<ProcessingView | null>(null)
const badCaseTotal = ref(0)
const unannotatedTotal = ref(0)

const trendMax = computed(() =>
  Math.max(1, ...(overview.value?.dailyIngestion ?? []).map(d => Math.max(d.documents, d.chunks))))
const barH = (v: number, max: number) => Math.max((v / max) * 60, v > 0 ? 4 : 1)

async function loadDashboard() {
  dashLoading.value = true
  try {
    const [ov, proc, bc, un] = await Promise.all([
      getStatsOverview(),
      getProcessingStats(),
      searchAuditLogs({ feedback: 'NEGATIVE', size: 1 }),
      searchAuditLogs({ feedback: 'NEGATIVE', annotated: false, size: 1 })
    ])
    overview.value = ov
    processing.value = proc
    badCaseTotal.value = bc.total
    unannotatedTotal.value = un.total
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '统计加载失败')
  } finally {
    dashLoading.value = false
  }
}

// ── Chunk 运维（簇③ 4.4/4.5 前端面）──

const docs = ref<KbDoc[]>([])
const chunkDocId = ref('')
const adminChunks = ref<KbChunk[]>([])
const chunkLoading = ref(false)

async function loadDocs() {
  try {
    docs.value = await listDocuments()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '文档列表加载失败')
  }
}

async function loadAdminChunks() {
  if (!chunkDocId.value) return
  chunkLoading.value = true
  try {
    adminChunks.value = await getChunks(chunkDocId.value)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || 'Chunk 列表加载失败')
  } finally {
    chunkLoading.value = false
  }
}

// 编辑对话框
const chunkEditVisible = ref(false)
const chunkEditTarget = ref<KbChunk | null>(null)
const chunkEditContent = ref('')
const chunkEditBusy = ref(false)

function openChunkEdit(row: KbChunk) {
  chunkEditTarget.value = row
  chunkEditContent.value = row.content
  chunkEditVisible.value = true
}

async function submitChunkEdit() {
  if (!chunkEditTarget.value) return
  chunkEditBusy.value = true
  try {
    await editChunk(chunkEditTarget.value.id, chunkEditContent.value)
    ElMessage.success('已保存，重嵌入异步进行（短暂检索窗口缺失属预期）')
    chunkEditVisible.value = false
    loadAdminChunks()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || 'Chunk 编辑失败')
  } finally {
    chunkEditBusy.value = false
  }
}

async function doSoftDeleteChunk(row: KbChunk) {
  try {
    await softDeleteChunk(row.id)
    ElMessage.success('已软删（检索不可见，可恢复）')
    loadAdminChunks()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '软删失败')
  }
}

async function doRestoreChunk(row: KbChunk) {
  try {
    await restoreChunk(row.id)
    ElMessage.success('已恢复，重嵌入异步进行')
    loadAdminChunks()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '恢复失败')
  }
}

// 索引重建（4.5）
const rebuildDocIds = ref<string[]>([])
const rebuildTasks = ref<RebuildTask[]>([])
const rebuildStarting = ref(false)
const rebuildLoading = ref(false)
let rebuildTimer: ReturnType<typeof setInterval> | null = null

async function loadRebuildTasks() {
  rebuildLoading.value = !rebuildTimer   // 仅手动刷新显示 loading，轮询静默
  try {
    rebuildTasks.value = await listRebuildTasks()
  } catch {
    /* 轮询期静默，避免刷屏 */
  } finally {
    rebuildLoading.value = false
  }
  const running = rebuildTasks.value.some(t => t.status === 'RUNNING')
  if (running && !rebuildTimer) {
    rebuildTimer = setInterval(loadRebuildTasks, 3000)
  } else if (!running && rebuildTimer) {
    clearInterval(rebuildTimer)
    rebuildTimer = null
  }
}

async function doStartRebuild() {
  rebuildStarting.value = true
  try {
    const task = await startRebuild(rebuildDocIds.value.length ? rebuildDocIds.value : undefined)
    ElMessage.success(`重建已受理：${task.taskId.slice(0, 8)}…（异步执行，轮询进度）`)
    await loadRebuildTasks()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '重建发起失败')
  } finally {
    rebuildStarting.value = false
  }
}

// ── 日志查询 ──

const logFilter = reactive<{ range: [Date, Date] | null; userId: string; sessionId: string;
  feedback: string; status: string }>({ range: null, userId: '', sessionId: '', feedback: '', status: '' })
const logs = ref<AuditLogItem[]>([])
const logLoading = ref(false)
const logPage = ref(1)
const logSize = 20
const logTotal = ref(0)
const detailVisible = ref(false)
const detail = ref<AuditLogItem | null>(null)

async function loadLogs(reset?: number) {
  if (reset !== undefined) logPage.value = reset + 1
  logLoading.value = true
  try {
    const params: AuditQuery = { page: logPage.value - 1, size: logSize }
    if (logFilter.range?.length === 2) {
      params.from = fmtIso(logFilter.range[0])
      params.to = fmtIso(logFilter.range[1])
    }
    if (logFilter.userId.trim()) params.userId = logFilter.userId.trim()
    if (logFilter.sessionId.trim()) params.sessionId = logFilter.sessionId.trim()
    if (logFilter.feedback) params.feedback = logFilter.feedback
    if (logFilter.status) params.status = logFilter.status
    const page = await searchAuditLogs(params)
    logs.value = page.items
    logTotal.value = page.total
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '审计查询失败')
  } finally {
    logLoading.value = false
  }
}

function openDetail(row: AuditLogItem) {
  detail.value = row
  detailVisible.value = true
}

// ── Bad Case 处置 ──

const ROOT_CAUSES: Record<RootCause, string> = {
  RETRIEVAL_MISS: '检索未命中',
  REWRITE_DRIFT: '改写漂移',
  HALLUCINATION: '生成幻觉',
  PARSING_GAP: '解析不足'
}
const ROOT_CAUSE_HINTS: Record<RootCause, string> = {
  RETRIEVAL_MISS: '目标证据未进入重排序列（切分/向量/BM25/重排环节丢失）',
  REWRITE_DRIFT: '多轮压缩/改写偏离原意，检索方向错误',
  HALLUCINATION: '证据命中且正确，回答编造或偏离证据',
  PARSING_GAP: '解析/切分缺陷导致证据本身缺失'
}
const REINGEST_CATEGORIES = ['FACTOID', 'REASONING', 'TABLE', 'MULTI_DOC', 'NEGATIVE']

const bcFilter = reactive<{ annotated: boolean | ''; rootCause: string }>({ annotated: '', rootCause: '' })
const badCases = ref<AuditLogItem[]>([])
const bcLoading = ref(false)
const bcPage = ref(1)
const bcSize = 20
const bcTotal = ref(0)

async function loadBadCases(reset?: number) {
  if (reset !== undefined) bcPage.value = reset + 1
  bcLoading.value = true
  try {
    const params: AuditQuery = { feedback: 'NEGATIVE', page: bcPage.value - 1, size: bcSize }
    if (bcFilter.annotated !== '') params.annotated = bcFilter.annotated as boolean
    if (bcFilter.rootCause) params.rootCause = bcFilter.rootCause
    const page = await searchAuditLogs(params)
    badCases.value = page.items
    bcTotal.value = page.total
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || 'Bad Case 查询失败')
  } finally {
    bcLoading.value = false
  }
}

// 标注对话框
const annotateVisible = ref(false)
const annotateTarget = ref<AuditLogItem | null>(null)
const annotateChoice = ref<RootCause | ''>('')
const annotateBusy = ref(false)

function openAnnotate(row: AuditLogItem) {
  annotateTarget.value = row
  annotateChoice.value = (row.rootCause as RootCause) || ''
  annotateVisible.value = true
}

async function submitAnnotate() {
  if (!annotateTarget.value || !annotateChoice.value) return
  annotateBusy.value = true
  try {
    await annotateRootCause(annotateTarget.value.id, annotateChoice.value)
    ElMessage.success('根因已标注')
    annotateVisible.value = false
    loadBadCases()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '标注失败')
  } finally {
    annotateBusy.value = false
  }
}

// 回灌对话框
const reingestVisible = ref(false)
const reingestTarget = ref<AuditLogItem | null>(null)
const reingestBusy = ref(false)
const chunkInput = ref('')
const reingestForm = reactive({
  category: 'FACTOID',
  expectedChunkIds: [] as string[],
  expectedDocs: [] as string[],
  expectedAnswer: ''
})

const snapshotEntries = computed<{ chunk_id?: string; file_name?: string }[]>(() => {
  const raw = reingestTarget.value?.rerankedChunks || reingestTarget.value?.retrievedChunks
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
})
const chunkCandidates = computed(() =>
  [...new Set(snapshotEntries.value.map(e => e.chunk_id).filter(Boolean) as string[])])
const docCandidates = computed(() =>
  [...new Set(snapshotEntries.value.map(e => e.file_name).filter(Boolean) as string[])])

function openReingest(row: AuditLogItem) {
  reingestTarget.value = row
  reingestForm.category = 'FACTOID'
  reingestForm.expectedChunkIds = []
  reingestForm.expectedDocs = []
  reingestForm.expectedAnswer = row.feedbackExpectedAnswer || ''
  chunkInput.value = ''
  reingestVisible.value = true
}

function toggleChunk(id: string) {
  const idx = reingestForm.expectedChunkIds.indexOf(id)
  if (idx >= 0) reingestForm.expectedChunkIds.splice(idx, 1)
  else reingestForm.expectedChunkIds.push(id)
}
function addChunkInput() {
  const id = chunkInput.value.trim()
  if (id && !reingestForm.expectedChunkIds.includes(id)) reingestForm.expectedChunkIds.push(id)
  chunkInput.value = ''
}
function removeChunk(id: string) {
  reingestForm.expectedChunkIds = reingestForm.expectedChunkIds.filter(c => c !== id)
}

async function submitReingest() {
  if (!reingestTarget.value) return
  reingestBusy.value = true
  try {
    const result = await reingestGolden({
      auditLogId: reingestTarget.value.id,
      category: reingestForm.category,
      expectedChunkIds: reingestForm.expectedChunkIds,
      expectedDocs: reingestForm.expectedDocs,
      expectedAnswer: reingestForm.expectedAnswer.trim() || undefined
    })
    ElMessage.success(`已回灌 ${result.goldenId} → ${result.file}，git commit 后 CI 复跑即生效`)
    reingestVisible.value = false
    loadBadCases()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '回灌失败')
  } finally {
    reingestBusy.value = false
  }
}

// ── 护栏词表（安全簇⑥ F2 只读运营面）──

/** 注入侧七分法 ∪ 输出侧三分类（各含 UNCLASSIFIED 兜底桶），中性枚举名（§7 纪律） */
const INJECTION_FAMILIES = ['INSTRUCTION_OVERRIDE', 'ROLE_HIJACK', 'INFO_EXTRACTION',
  'ENCODING_OBFUSCATION', 'MULTILINGUAL', 'JAILBREAK', 'TOOL_INDUCED', 'UNCLASSIFIED']
const OUTPUT_FAMILIES = ['BUSINESS_CONFIDENTIAL', 'COMPLIANCE_SENSITIVE',
  'COMPETITOR_COMPARISON', 'UNCLASSIFIED']

const grFilter = reactive<{ side: string; family: string; action: string; type: string;
  enabled: boolean | '' }>({ side: '', family: '', action: '', type: '', enabled: '' })
const grRules = ref<GuardrailRuleView[]>([])
const grLoading = ref(false)

const grFamilyOptions = computed(() => {
  if (grFilter.side === 'injection') return INJECTION_FAMILIES
  if (grFilter.side === 'output') return OUTPUT_FAMILIES
  return [...new Set([...INJECTION_FAMILIES, ...OUTPUT_FAMILIES])]
})
const grBlockCount = computed(() => grRules.value.filter(r => r.action === 'BLOCK').length)
const grFlagCount = computed(() => grRules.value.filter(r => r.action === 'FLAG').length)

async function loadGuardrailRules() {
  grLoading.value = true
  try {
    const params: GuardrailRuleQuery = {}
    if (grFilter.side) params.side = grFilter.side
    if (grFilter.family) params.family = grFilter.family
    if (grFilter.action) params.action = grFilter.action
    if (grFilter.type) params.type = grFilter.type
    if (grFilter.enabled !== '') params.enabled = grFilter.enabled
    grRules.value = await listGuardrailRules(params)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '词表查询失败')
  } finally {
    grLoading.value = false
  }
}

// 命中演练台
const drillText = ref('')
const drillBusy = ref(false)
const drillResult = ref<DrillResult | null>(null)

const drillVerdict = computed(() => {
  const r = drillResult.value
  if (!r) return null
  const hits = [...r.injectionMatches, ...r.outputMatches]
  if (!hits.length) return { type: 'success' as const, text: '全档零命中——运行时放行且无 FLAG 计数' }
  if (hits.some(m => m.action === 'BLOCK')) return { type: 'danger' as const, text: '含 BLOCK 命中——运行时拒绝（PROMPT_INJECTION / 输出替换）' }
  return { type: 'warning' as const, text: '仅 FLAG 命中——运行时放行 + 计数，REGEX 命中且无干词命中时触发 L2 二判' }
})

async function runDrill() {
  if (!drillText.value.trim()) return
  drillBusy.value = true
  try {
    drillResult.value = await drillGuardrail(drillText.value)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '演练失败')
  } finally {
    drillBusy.value = false
  }
}

// ── 展示辅助 ──

const rootCauseLabel = (rc: string) => ROOT_CAUSES[rc as RootCause] ?? rc

const statusTag = (status?: string) => {
  switch (status) {
    case 'SUCCESS': return 'success'
    case 'REJECTED': return 'warning'
    case 'ERROR': case 'FAILED': return 'danger'
    default: return 'info'
  }
}

const fmtTime = (iso?: string) => iso ? iso.replace('T', ' ').slice(0, 19) : '—'

function fmtIso(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}` +
    `T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const pretty = (json?: string) => {
  if (!json) return ''
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

onMounted(() => {
  loadDashboard()
  loadDocs()
  loadRebuildTasks()
  loadLogs()
  loadBadCases()
  loadGuardrailRules()
})

onUnmounted(() => {
  if (rebuildTimer) {
    clearInterval(rebuildTimer)
    rebuildTimer = null
  }
})
</script>

<style scoped>
.admin-page { height: 100%; overflow-y: auto; padding-bottom: 28px; }

.page-head { padding: 22px 28px 6px; }
.page-title { margin: 0; font-size: 24px; color: var(--pine-900); }
.page-desc { margin: 5px 0 0; color: var(--ink-3); font-size: 13px; }

.admin-tabs { margin: 8px 28px 0; }
.admin-tabs :deep(.el-tabs__header) { margin-bottom: 16px; }

/* ── 仪表盘 ── */
.stat-strip { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat-item {
  padding: 13px 16px; display: flex; flex-direction: column; gap: 3px;
  border-top: 3px solid var(--pine-600);
}
.stat-item:nth-child(2) { border-top-color: var(--gold-500); }
.stat-item:nth-child(3) { border-top-color: var(--c-vector); }
.stat-item:nth-child(4) { border-top-color: var(--c-rerank); }
.stat-num { font-size: 24px; font-weight: 700; color: var(--pine-900); line-height: 1.1; }

.dash-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-top: 12px; }
.dash-card { padding: 14px 17px; min-height: 150px; }
.kv-list { display: flex; flex-direction: column; gap: 6px; margin-top: 9px; }
.kv-row { display: flex; align-items: center; justify-content: space-between; font-size: 12.5px; }
.kv-key { color: var(--ink-2); }
.kv-val { font-weight: 700; color: var(--pine-900); }

.trend-bars { display: flex; align-items: flex-end; gap: 4px; height: 82px; margin-top: 14px; }
.trend-col { flex: 1; display: flex; align-items: flex-end; justify-content: center; gap: 2px; height: 100%; }
.trend-bar { width: 6px; border-radius: 2px 2px 0 0; transition: height .4s var(--ease); }
.trend-bar.docs { background: linear-gradient(180deg, var(--gold-500), var(--gold-600)); }
.trend-bar.chunks { background: linear-gradient(180deg, var(--pine-600), var(--pine-800)); }

.dash-empty { margin-top: 14px; font-size: 12.5px; color: var(--ink-3); }
.proc-row { display: flex; align-items: center; gap: 8px; margin-top: 9px; font-size: 12.5px; }
.proc-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink-2); }
.proc-route { font-size: 11px; color: var(--ink-3); }

/* ── 日志查询 / Bad Case ── */
.filter-bar {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 12px 14px; margin-bottom: 12px;
}
.bc-hint { margin-left: auto; font-size: 12px; }
.log-table { border-radius: 10px; overflow: hidden; }
.log-table :deep(tr) { cursor: pointer; }
.err-code { font-size: 10px; color: var(--ink-3); margin-top: 2px; }

.detail-kv { display: flex; gap: 12px; margin-bottom: 6px; font-size: 12.5px; }
.detail-kv .t-label { width: 42px; flex-shrink: 0; }
.detail-sec { margin-top: 14px; }
.detail-sec .t-label { margin-bottom: 5px; }
.detail-text {
  font-size: 12.5px; line-height: 1.7; color: var(--ink-2);
  white-space: pre-wrap; word-break: break-word;
  background: var(--pine-50, #F2F7F5); border-radius: 8px; padding: 10px 12px;
}
.detail-json {
  font-size: 11px; line-height: 1.55; max-height: 220px; overflow: auto;
  background: #0F2B25; color: #B9E8DC; border-radius: 8px; padding: 10px 12px;
  margin: 0;
}

/* ── Chunk 运维 ── */
.rebuild-panel { margin-top: 14px; padding: 14px 17px; }
.rebuild-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 14px; margin-bottom: 12px; flex-wrap: wrap;
}
.rebuild-title { font-weight: 700; color: var(--pine-900); font-size: 13px; }
.rebuild-sub { margin-top: 4px; font-size: 11.5px; }
.rebuild-actions { display: flex; align-items: center; gap: 10px; }
.rebuild-fail-count { cursor: help; border-bottom: 1px dashed var(--ink-3); }
.rebuild-fail-line { max-width: 420px; }
.chunk-edit-meta { margin-bottom: 10px; word-break: break-all; }
.chunk-edit-hint { margin-top: 10px; line-height: 1.7; }

/* ── Bad Case 对话框 ── */
.annotate-q {
  font-size: 13px; color: var(--ink-2); line-height: 1.6;
  background: var(--pine-50, #F2F7F5); border-radius: 8px; padding: 9px 12px;
  margin-bottom: 14px; word-break: break-word;
}
.annotate-group { display: flex; flex-direction: column; gap: 4px; }
.annotate-opt { display: flex; align-items: baseline; height: auto; margin: 1px; white-space: normal; justify-content: flex-start; width: 100%; }
.annotate-hint { margin-left: 8px; font-size: 11.5px; }

.reingest-form { margin-top: 4px; }
.chip-row { display: flex; flex-wrap: wrap; gap: 6px; }
.cand-chip { cursor: pointer; }

/* ── 护栏词表（簇⑥ F2）── */
.gr-table :deep(tr) { cursor: default; }
.drill-panel { margin-top: 14px; padding: 14px 17px; }
.drill-input-row { display: flex; gap: 10px; align-items: flex-start; margin-top: 12px; }
.drill-input-row .el-textarea { flex: 1; }
.drill-sec { margin-top: 14px; }
.drill-sec .t-label:first-child { margin-bottom: 6px; font-weight: 700; color: var(--pine-900); }
.drill-row { display: flex; align-items: center; gap: 8px; margin-top: 7px; font-size: 12.5px; }
.drill-verdict { margin-top: 14px; }
</style>
