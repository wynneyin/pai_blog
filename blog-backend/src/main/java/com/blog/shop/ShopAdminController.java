package com.blog.shop;

import com.blog.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/shop")
@RequiredArgsConstructor
public class ShopAdminController {

    private final ShopService shopService;

    @Value("${blog.admin-token:}")
    private String adminToken;

    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<?>> listOrders(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String limit) {
        verifyAdminToken(token);
        return ResponseEntity.ok(ApiResponse.success(shopService.listOrders(status, limit)));
    }

    @PostMapping("/orders/{orderNo}/status")
    public ResponseEntity<ApiResponse<?>> updateOrderStatus(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String orderNo,
            @RequestBody(required = false) ShopStatusUpdateRequest request) {
        verifyAdminToken(token);
        String status = request == null ? null : request.getStatus();
        return ResponseEntity.ok(ApiResponse.success(shopService.updateOrderStatus(orderNo, status)));
    }

    private void verifyAdminToken(String token) {
        if (adminToken != null && !adminToken.isBlank() && !adminToken.equals(token)) {
            throw new ShopException(HttpStatus.UNAUTHORIZED, "Invalid admin token");
        }
    }
}
