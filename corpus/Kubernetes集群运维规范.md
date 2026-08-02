# Kubernetes 集群运维规范

> 生产集群运维标准 · 适用于 v1.28+ 版本的企业级 Kubernetes 集群

## 一、集群架构与节点规划

生产集群采用多可用区部署，控制面节点固定 3 个（etcd 法定人数要求），工作节点按业务域划分节点池：

| 节点池 | 规格 | 数量 | 用途 | 污点（Taint） |
|-------|------|------|------|--------------|
| control-plane | 8C16G | 3 | API Server / etcd / 控制器 | node-role.kubernetes.io/control-plane:NoSchedule |
| app-general | 16C32G | 6 | 无状态业务负载 | 无 |
| app-stateful | 16C64G + SSD | 4 | 数据库、消息队列等有状态服务 | node.kubernetes.io/stateful=true:NoSchedule |
| batch-compute | 32C64G | 弹性 2-10 | 离线计算、CI 构建 | node.kubernetes.io/batch=true:NoSchedule |

**etcd 规范**：etcd 副本数固定为 3（容忍单节点故障），数据盘必须使用独立 SSD（IOPS ≥ 3000），禁止与业务负载混部。etcd 每日凌晨 02:00 执行快照备份，保留最近 7 天。

## 二、工作负载选型

三种核心工作负载控制器的适用场景严格区分：

| 控制器 | 适用场景 | 网络标识 | 存储特征 | 典型业务 |
|-------|---------|---------|---------|---------|
| Deployment | 无状态服务，副本可互换 | Pod IP 随机 | 无需持久化或共享存储 | Web API、网关、Worker |
| StatefulSet | 有状态服务，需稳定身份 | 固定 DNS（pod-0.svc） | 每副本独立 PVC | 数据库、Kafka、ZooKeeper |
| DaemonSet | 每节点单实例的系统服务 | 宿主机网络常见 | 挂载宿主机路径 | 日志采集、监控探针、CNI 插件 |

**StatefulSet 使用约束**：必须配置 `volumeClaimTemplates` 提供稳定的持久化存储；副本按序号顺序启停（0 → N-1），删除 StatefulSet 不会自动删除 PVC，防止数据误删；禁止将无状态服务放入 StatefulSet（滚动更新语义不同，会拖慢发布）。

## 三、资源配额与限制

命名空间级资源配额（ResourceQuota）与容器级限制（LimitRange）双层管控：

| 配额项 | 业务命名空间默认值 | 说明 |
|-------|------------------|------|
| requests.cpu | 32 核 | 命名空间 CPU 申请总量上限 |
| requests.memory | 64Gi | 命名空间内存申请总量上限 |
| limits.cpu | 64 核 | 命名空间 CPU 限制总量上限 |
| limits.memory | 128Gi | 命名空间内存限制总量上限 |
| pods | 200 | 最大 Pod 数量 |
| persistentvolumeclaims | 20 | 最大 PVC 数量 |

**容器级强制规则**：所有容器必须声明 requests 与 limits；limits.cpu 不得超过 requests.cpu 的 2 倍（防止超卖挤兑）；Java 应用 limits.memory 须与 JVM 堆参数（-Xmx）配套设置，建议堆外预留 25%。

## 四、发布策略

| 策略 | 机制 | 回滚速度 | 资源开销 | 适用场景 |
|-----|------|---------|---------|---------|
| 滚动更新（RollingUpdate） | 逐批替换旧 Pod，maxSurge=25%、maxUnavailable=25% | 分钟级（kubectl rollout undo） | 低（+25% 峰值） | 绝大多数无状态服务 |
| 蓝绿发布（Blue-Green） | 新旧两套完整环境，流量一次性切换 | 秒级（切回旧环境） | 高（双倍资源） | 核心交易链路、需即时回滚 |
| 金丝雀发布（Canary） | 少量 Pod 承接小比例流量，逐步放量 | 秒级（摘除金丝雀） | 中（+少量 Pod） | 高风险变更、需灰度验证 |

**选型原则**：默认滚动更新；核心交易链路采用蓝绿发布保障秒级回滚；用户感知强、影响面大的功能变更采用金丝雀发布，初始流量比例不超过 5%，观察期不少于 30 分钟。

## 五、网络与服务暴露

- **ClusterIP Service**：集群内部服务发现默认方式，通过 kube-dns 提供 `<svc>.<ns>.svc.cluster.local` 域名解析。
- **Ingress**：南北向流量入口统一由 Ingress 网关承载（生产使用 Nginx Ingress Controller，3 副本跨可用区部署），禁止业务 Service 直接暴露 LoadBalancer 类型。
- **Ingress 与 Service 的本质区别**：Service 工作在四层（TCP/UDP 转发，kube-proxy 实现），Ingress 工作在七层（HTTP 路由、域名分流、TLS 终结），Ingress 规则最终仍转发到后端 Service。
- **NetworkPolicy**：生产命名空间默认拒绝所有入向流量（default-deny），按最小权限原则显式放通。

## 六、常见故障排查

| 故障现象 | 常见原因 | 排查命令 | 处置要点 |
|---------|---------|---------|---------|
| CrashLoopBackOff | 启动命令错误、探针配置过严、依赖服务未就绪 | `kubectl logs <pod> --previous` | 查看上一周期日志，区分配置错误与依赖问题 |
| ImagePullBackOff | 镜像不存在、私有仓库凭证失效、网络不通 | `kubectl describe pod <pod>` | 核对镜像 tag 与 imagePullSecrets |
| Pending | 资源不足、节点污点无对应容忍、PVC 未绑定 | `kubectl describe pod <pod>` | 检查事件（Events）中的调度失败原因 |
| OOMKilled | 内存 limit 低于实际用量、内存泄漏 | `kubectl top pod` + 监控曲线 | 调大 limit 或排查泄漏 |
| Evicted | 节点磁盘压力（DiskPressure）触发驱逐 | `kubectl get events --field-selector reason=Evicted` | 清理节点磁盘、检查 emptyDir 用量 |

## 七、服务等级与变更纪律

- **SLA 定义**：核心业务可用性目标 99.95%（月度停机 ≤ 21.6 分钟）；API 网关 P99 延迟 ≤ 500ms。
- **变更窗口**：生产变更限于工作日 10:00-12:00 与 14:00-17:00；周五下午及节假日前禁止非紧急变更。
- **发布三件套**：每次发布必须包含回滚方案、监控看板链接、验证用例清单，缺一不得执行。
