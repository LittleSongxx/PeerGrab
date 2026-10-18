package com.peergrab.application.usecase.security;

import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 平台角色策略。
 *
 * <p>本项目没有独立用户/角色表，仲裁员是配置里的固定身份。为了让"仲裁者不得同时是交易当事人"
 * 这条约束可执行，所有会改变交易双方身份的入口都必须经过本类：
 * 仲裁员既不能发布任务，也不能抢单；仲裁用例还会做一次当事人校验作为纵深防御。</p>
 */
@Component
public class ActorRolePolicy {

    private final long arbitratorId;

    public ActorRolePolicy(@Value("${peergrab.auth.arbitrator-id:9001}") long arbitratorId) {
        this.arbitratorId = arbitratorId;
    }

    public boolean isArbitrator(long userId) {
        return userId == arbitratorId;
    }

    /** 交易动作（发布/抢单）前的角色校验。 */
    public void requireTradable(long userId) {
        if (isArbitrator(userId)) {
            throw new BizException(ErrorCode.ROLE_NOT_ALLOWED,
                    "仲裁员身份不能发布或抢单，避免成为争议当事人");
        }
    }

    public long arbitratorId() {
        return arbitratorId;
    }
}
