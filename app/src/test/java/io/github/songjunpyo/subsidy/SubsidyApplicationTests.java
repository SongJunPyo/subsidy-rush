package io.github.songjunpyo.subsidy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SubsidyApplicationTests {

    @Test
    void contextLoads() {
    }
}
