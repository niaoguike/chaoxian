package com.hmdp.controller;
import com.hmdp.dto.Result;
import com.hmdp.service.OrderLifecycle;
import com.hmdp.utils.UserHolder;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
/** Local demonstration only; a real payment provider must authenticate its callback. */
@Profile("demo")
@RestController
@RequestMapping("/demo/payment")
public class DemoPaymentController {
    @Resource private OrderLifecycle lifecycle;
    @PostMapping("/{id}") public Result pay(@PathVariable long id) {
        return lifecycle.paid(id,UserHolder.getUser().getId()) ? Result.ok() : Result.fail("订单状态不允许支付");
    }
}
