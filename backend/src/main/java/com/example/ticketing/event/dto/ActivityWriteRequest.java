package com.example.ticketing.event.dto;

import com.example.ticketing.event.model.ActivityCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ActivityWriteRequest(
        @NotBlank(message = "请填写活动名称") @Size(max = 200, message = "活动名称不能超过200字") String title,
        @NotNull(message = "请选择活动类别") ActivityCategory category,
        @NotBlank(message = "请填写举办城市") @Size(max = 64, message = "城市不能超过64字") String city,
        @NotBlank(message = "请填写举办场馆") @Size(max = 200, message = "场馆不能超过200字") String venue,
        @NotBlank(message = "请填写活动简介") @Size(max = 500, message = "简介不能超过500字") String summary,
        @NotBlank(message = "请填写活动详情") @Size(max = 20000, message = "详情不能超过20000字") String description,
        @Size(max = 2048, message = "封面地址过长")
        @Pattern(regexp = "(?:https?://[^\\s]+)?", message = "封面只支持完整的HTTP或HTTPS地址") String coverUrl,
        @PositiveOrZero(message = "版本号不能为负数") Integer version) {
}
