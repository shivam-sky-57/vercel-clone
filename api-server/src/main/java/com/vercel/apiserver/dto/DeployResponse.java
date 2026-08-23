package com.vercel.apiserver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeployResponse {
    private String status;
    private DeploymentData data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeploymentData {
        private String projectSlug;
        private String url;
        private String wsTopic;
        private String sseStreamUrl;
    }
}
