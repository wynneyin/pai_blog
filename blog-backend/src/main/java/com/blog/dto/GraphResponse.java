package com.blog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphResponse {

    private List<GraphNode> nodes;
    private List<GraphLink> links;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphNode {
        private String id;
        private String type;   // "article" | "tag"
        private String title;
        private String category;
        private int linkCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphLink {
        private String source;
        private String target;
        private String type;   // "tag" | "wikilink"
    }
}
