package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    public Result order(@PathVariable("id") Long id) {
        com.hmdp.entity.VoucherOrder order = voucherOrderService.getById(id);
        if(order == null || !order.getUserId().equals(com.hmdp.utils.UserHolder.getUser().getId())) return Result.fail("订单不存在或尚未落库");
        return Result.ok(order);
    }
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
