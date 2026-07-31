package com.enterprise.kb.eval.runner;

/**
 * 评估门禁失败异常 —— CI 模式下使进程非零退出
 */
public class EvalFailedException extends RuntimeException {
    public EvalFailedException(String message) {
        super(message);
    }
}
