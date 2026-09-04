package com.example.campusmarket.payment.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 双门禁的第二道校验：即使控制器因 Profile 被排除，错误启用也必须让应用启动失败。 */
@Component
public final class SimulatedPaymentProviderGuard {
    public SimulatedPaymentProviderGuard(
        @Value("${campus.market.payment.simulation-enabled:false}") boolean enabled,
        Environment environment) {
        if (enabled && !environment.matchesProfiles("local", "test")) {
            throw new IllegalStateException("模拟支付控制端仅允许 local/test Profile");
        }
    }
}
