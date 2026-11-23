package com.solo;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("""
        默认 webEnvironment=MOCK 没有 Servlet 容器，ServerEndpointExporter 必然初始化失败；
        改成 RANDOM_PORT 又会真实绑定 SIP/MySQL。上下文装配由部署启动验证，
        单元测试只覆盖可离线运行的部分。""")
class SoloApplicationTests {

    @Test
    void contextLoads() {
    }

}
