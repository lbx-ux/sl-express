package com.sl.ms.dispatch.mq;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class OrderMQListenerTest {
    @Autowired
    private OrderMQListener orderMQListener;

    @Test
    void listenerOrderMsg(){
        String msg = "{\"orderId\":123, \"agencyId\": 1024981295454874273, \"taskType\":1, \"mark\":\"带包装\", \"longitude\":31.263354, " +
                "\"latitude\":31.263354121.492485, \"created\":1790667173689, \"estimatedEndTime\": 1790755200000}";
        this.orderMQListener.listenOrderMsg(msg);
    }
}
