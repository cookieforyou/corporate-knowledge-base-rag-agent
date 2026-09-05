package com.enterprise.kb.ai.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 企业 Mock 读工具（簇⑤ 5.3 自 EnterpriseMockTools 拆分——读/写半区隔离）
 *
 * <p>拆分动机：编排链数据查询子代理（SubAgentSpec data-query）仅挂读工具——
 * 2.0 GA 的 defaultTools 为对象级全量挂载（无方法级过滤），拆类是零核验成本的
 * 物理隔离路径；tool 链 defaultTools 双挂（读+写）行为与拆分前等价。
 *
 * <p>契约不变（11.2.1 任务 3.3 定稿）：内存确定性假数据，签名/描述/返回结构
 * 按真实系统设计，后续替换为真实服务客户端而不动 Advisor 链与 HITL 机制。
 * 读操作自动执行，无审批需求。
 */
@Component
public class EnterpriseMockReadTools {

    /** Mock 员工主数据（契约对应真实 ERP 员工服务） */
    private static final Map<String, EmployeeInfo> EMPLOYEES = Map.of(
        "E1001", new EmployeeInfo("E1001", "张三", "研发部", "高级工程师", "2021-03-15"),
        "E1002", new EmployeeInfo("E1002", "李四", "财务部", "财务专员", "2022-07-01"),
        "E1003", new EmployeeInfo("E1003", "王五", "销售部", "区域销售经理", "2020-11-20"));

    /** Mock 年假余额（天） */
    private static final Map<String, Integer> LEAVE_BALANCE = Map.of(
        "E1001", 10, "E1002", 5, "E1003", 15);

    /**
     * 员工信息查询（ERP 读操作，自动执行）
     */
    @Tool(description = "根据员工工号或姓名查询员工基本信息，包括部门、职位、入职日期")
    public EmployeeInfo queryEmployee(
            @ToolParam(description = "员工工号（如 E1001）或姓名（如 张三）") String keyword) {
        // 工号精确匹配优先，其次姓名匹配
        EmployeeInfo byId = EMPLOYEES.get(keyword);
        if (byId != null) {
            return byId;
        }
        return EMPLOYEES.values().stream()
            .filter(e -> e.name().equals(keyword))
            .findFirst()
            .orElse(null);
    }

    /**
     * 年假余额查询（OA 读操作，自动执行）
     */
    @Tool(description = "根据员工工号查询该员工剩余年假天数")
    public LeaveBalance queryLeaveBalance(
            @ToolParam(description = "员工工号（如 E1001）") String employeeId) {
        Integer days = LEAVE_BALANCE.get(employeeId);
        return days == null ? null : new LeaveBalance(employeeId, days);
    }

    /** 员工信息（契约 record） */
    public record EmployeeInfo(String employeeId, String name, String department,
                               String position, String hireDate) {}

    /** 年假余额（契约 record） */
    public record LeaveBalance(String employeeId, int remainingDays) {}
}
