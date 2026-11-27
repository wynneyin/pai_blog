package com.blog.shop;

import com.blog.dto.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shop")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;

    @GetMapping("/catalog")
    public ResponseEntity<ApiResponse<?>> getCatalog() {
        return ResponseEntity.ok(ApiResponse.success(shopService.getCatalog()));
    }

    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<?>> createOrder(
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.ok(ApiResponse.success(
                shopService.createOrder(ShopCreateOrderRequest.fromJson(body))));
    }

    @GetMapping("/orders/{orderNo}")
    public ResponseEntity<ApiResponse<?>> getOrder(@PathVariable String orderNo) {
        return ResponseEntity.ok(ApiResponse.success(shopService.getOrder(orderNo)));
    }

    @PostMapping("/orders/{orderNo}/pay")
    public ResponseEntity<ApiResponse<?>> payOrder(
            @PathVariable String orderNo,
            @RequestBody(required = false) ShopPayRequest request) {
        String method = request == null ? null : request.getMethod();
        return ResponseEntity.ok(ApiResponse.success(shopService.payOrder(orderNo, method)));
    }
}
