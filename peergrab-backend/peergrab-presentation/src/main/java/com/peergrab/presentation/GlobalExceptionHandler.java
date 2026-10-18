package com.peergrab.presentation;

import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.Result;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一异常映射。
 *
 * <p>HTTP 状态语义：业务异常与参数错误都返回带业务码的 JSON；只有真正的服务端故障才 5xx。
 * 所有分支都计入 {@code peergrab.api.exceptions}，避免"HTTP 200 的业务失败"在监控里消失。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MeterRegistry registry;

    public GlobalExceptionHandler(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 业务异常返回 200 + 业务错误码，不用 HTTP 5xx——抢不到单不是服务器错误 */
    @ExceptionHandler(BizException.class)
    public Result<Void> onBiz(BizException e) {
        return fail(e.code(), e.getMessage());
    }

    /** 唯一索引冲突：说明并发防线生效了，这是预期行为 */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> onDuplicate(DuplicateKeyException e) {
        return fail(ErrorCode.GRAB_CONFLICT, e.getMessage());
    }

    /**
     * 客户端可修正的参数问题统一 400。非法枚举、非法游标、非法请求体（如仲裁 favor）
     * 都属于这一类；此前它们落到兜底分支返回 500，既误导监控也误导客户端。
     */
    @ExceptionHandler({
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> onInvalidArgument(Exception e) {
        return fail(ErrorCode.INVALID_ARGUMENT, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> onOther(Exception e) {
        log.error("未预期异常", e);
        return fail(ErrorCode.INTERNAL_ERROR, e.getMessage());
    }

    private Result<Void> fail(ErrorCode code, String detail) {
        registry.counter("peergrab.api.exceptions", "code", code.name()).increment();
        if (detail != null && log.isDebugEnabled()) {
            log.debug("业务/参数异常 code={} detail={}", code, detail);
        }
        return Result.fail(code);
    }
}
