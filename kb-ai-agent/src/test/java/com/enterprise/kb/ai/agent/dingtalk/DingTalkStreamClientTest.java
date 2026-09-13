package com.enterprise.kb.ai.agent.dingtalk;

import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * DingTalkStreamClient 监听器形态测试（Phase5簇⑥ 批1 热修）：SDK
 * CallbackDescriptor.build 的非 lambda 分支经 getGenericInterfaces() 读
 * 具体化泛型——lambda 合成类不可读即抛 illegal callback implementation
 * （用户侧首启日志实证）。本测试复现 SDK 解析路径钉住匿名类形态。
 */
class DingTalkStreamClientTest {

    @Test
    void listenerIsAnonymousClassWithReadableGenericSignature() {
        DingTalkStreamClient client = new DingTalkStreamClient(
            new DingTalkProperties(), mock(DingTalkChatService.class));

        OpenDingTalkCallbackListener<ChatbotMessage, Void> listener = client.buildListener();

        // 非 lambda 合成类（SDK LambdaUtils.isLambda 判 false → 走泛型签名分支）
        assertThat(listener.getClass().isSynthetic()).isFalse();
        // SDK AopUtils.getTargetClass + getGenericInterfaces 解析路径：
        // 匿名类直接实现具体化接口 ParameterizedType<ChatbotMessage, Void>
        Class<?> targetClass = AopUtils.getTargetClass(listener);
        Type genericInterface = null;
        for (Type type : targetClass.getGenericInterfaces()) {
            if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == OpenDingTalkCallbackListener.class) {
                genericInterface = type;
            }
        }
        assertThat(genericInterface).isNotNull();
        Type[] arguments = ((ParameterizedType) genericInterface).getActualTypeArguments();
        assertThat(arguments[0]).isEqualTo(ChatbotMessage.class);
    }
}
