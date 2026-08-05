package com.enterprise.kb.ai.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mock 工具层测试（3.3）—— 读工具契约（工号/姓名双路匹配、未命中返回 null）
 */
class EnterpriseMockToolsTest {

    private final EnterpriseMockTools tools = new EnterpriseMockTools();

    @Test
    void queryEmployeeById() {
        EnterpriseMockTools.EmployeeInfo info = tools.queryEmployee("E1001");

        assertThat(info).isNotNull();
        assertThat(info.name()).isEqualTo("张三");
        assertThat(info.department()).isEqualTo("研发部");
        assertThat(info.position()).isEqualTo("高级工程师");
        assertThat(info.hireDate()).isEqualTo("2021-03-15");
    }

    @Test
    void queryEmployeeByName() {
        EnterpriseMockTools.EmployeeInfo info = tools.queryEmployee("李四");

        assertThat(info).isNotNull();
        assertThat(info.employeeId()).isEqualTo("E1002");
        assertThat(info.department()).isEqualTo("财务部");
    }

    @Test
    void queryEmployeeUnknownReturnsNull() {
        assertThat(tools.queryEmployee("不存在的人")).isNull();
    }

    @Test
    void queryLeaveBalance() {
        EnterpriseMockTools.LeaveBalance balance = tools.queryLeaveBalance("E1003");

        assertThat(balance).isNotNull();
        assertThat(balance.remainingDays()).isEqualTo(15);
    }

    @Test
    void queryLeaveBalanceUnknownReturnsNull() {
        assertThat(tools.queryLeaveBalance("E9999")).isNull();
    }
}
