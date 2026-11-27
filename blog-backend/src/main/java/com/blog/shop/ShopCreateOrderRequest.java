package com.blog.shop;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for POST /api/shop/orders. quantity is kept as a JsonNode so
 * non-integer values can be rejected with the business message instead of
 * failing Jackson binding.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShopCreateOrderRequest {

    private String productId;
    private JsonNode quantity;
    private String contact;
    private String note;
    /** True when the body carried a non-string note; the service rejects it. */
    private boolean noteInvalid;

    /**
     * Builds the typed request from a raw JSON body. Only textual values are
     * accepted for productId/contact/note; non-object bodies (e.g. arrays)
     * behave like an empty object, matching the reference backend.
     */
    public static ShopCreateOrderRequest fromJson(JsonNode body) {
        JsonNode source = body != null && body.isObject() ? body : null;
        JsonNode noteNode = source == null ? null : source.get("note");
        boolean noteInvalid = noteNode != null && !noteNode.isNull() && !noteNode.isTextual();
        return ShopCreateOrderRequest.builder()
                .productId(textValue(source, "productId"))
                .quantity(source == null ? null : source.get("quantity"))
                .contact(textValue(source, "contact"))
                .note(noteInvalid ? "" : noteValue(source))
                .noteInvalid(noteInvalid)
                .build();
    }

    private static String textValue(JsonNode body, String field) {
        if (body == null) {
            return "";
        }
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private static String noteValue(JsonNode body) {
        if (body == null) {
            return "";
        }
        JsonNode value = body.get("note");
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isTextual() ? value.asText() : value.asText("");
    }
}
